package dev.foldkeys

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Draws the keyboard and turns touches into key actions. Keyboard state
 * (mode, shift) is owned by the service and pushed in through the setters.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onCharacter(text: String)
        fun onDelete(repeatCount: Int)
        fun onShiftTap()
        fun onSpace()
        fun onReturn()
        fun onModeKey(type: KeyType)
        fun onGlobe(longPress: Boolean)
        fun onToggleSplit()
        fun onCursorMove(dx: Int, dy: Int)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Transparent strip above the keys where the key-press bubbles are drawn. */
    val popupSpace: Int get() = dp(54f).toInt()
    private val rowH get() = dp(54f)
    private val topPad get() = dp(6f)
    private val bottomPad get() = dp(4f)
    private val sidePad get() = dp(3f)
    private val gapH get() = dp(6f)
    private val gapV get() = dp(11f)
    private val radius get() = dp(5.5f)

    var mode = Mode.LETTERS
        set(v) { if (field != v) { field = v; rebuild() } }
    var shift = Shift.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var split = false
        set(v) { if (field != v) { field = v; rebuild() } }
    var showGlobe = true
        set(v) { if (field != v) { field = v; rebuild() } }
    var returnLabel = "return"
        set(v) { field = v; invalidate() }
    var returnHighlighted = false
        set(v) { field = v; invalidate() }

    private var rows: List<Row> = Layouts.rows(mode, split, showGlobe)

    // ---- colors -------------------------------------------------------------------------

    private class Palette(val bg: Int, val key: Int, val func: Int, val shadow: Int, val text: Int, val blue: Int)

    private val light = Palette(0xFFD1D3D9.toInt(), 0xFFFFFFFF.toInt(), 0xFFABB0BA.toInt(), 0xFF898A8D.toInt(), 0xFF000000.toInt(), 0xFF007AFF.toInt())
    private val dark = Palette(0xFF2B2B2D.toInt(), 0xFF6B6B6D.toInt(), 0xFF464648.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF0A84FF.toInt())

    private val palette: Palette
        get() = if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES) dark else light

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- touch state --------------------------------------------------------------------

    private class Touch(var key: Key, var x: Float, var y: Float) {
        var consumed = false   // long-press already handled; do nothing on release
        var accX = 0f
        var accY = 0f
    }

    private val touches = HashMap<Int, Touch>()
    private var bubbleKey: Key? = null
    private var trackpad = false
    private val handler = Handler(Looper.getMainLooper())
    private var deleteCount = 0
    private var longPressTouch: Touch? = null

    private val deleteRepeat = object : Runnable {
        override fun run() {
            deleteCount++
            listener.onDelete(deleteCount)
            handler.postDelayed(this, if (deleteCount > 20) 160 else 75)
        }
    }

    private val longPress = Runnable {
        val t = longPressTouch ?: return@Runnable
        when (t.key.type) {
            KeyType.SPACE -> { trackpad = true; bubbleKey = null; invalidate() }
            KeyType.GLOBE -> listener.onGlobe(true)
            KeyType.TO_NUMBERS, KeyType.TO_LETTERS -> listener.onToggleSplit()
            else -> return@Runnable
        }
        t.consumed = true
        haptic()
    }

    // ---- layout -------------------------------------------------------------------------

    private fun rebuild() {
        rows = Layouts.rows(mode, split, showGlobe)
        layoutKeys()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = popupSpace + topPad + rows.size * rowH + bottomPad
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun List<Key>.weight() = fold(0f) { acc, k -> acc + k.weight }

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f) return
        val inner = w - 2 * sidePad
        var top = popupSpace + topPad
        if (!split) {
            val unit = inner / rows.maxOf { it.left.weight() }
            for (row in rows) {
                place(row.left, sidePad + (inner - row.left.weight() * unit) / 2, top, unit)
                top += rowH
            }
        } else {
            // Each half sits under one thumb with an open gap over the fold.
            val half = (inner - w * 0.24f) / 2
            val unit = half / rows.maxOf { max(it.left.weight(), it.right.weight()) }
            for (row in rows) {
                place(row.left, sidePad, top, unit)
                place(row.right, w - sidePad - row.right.weight() * unit, top, unit)
                top += rowH
            }
        }
    }

    private fun place(keys: List<Key>, startX: Float, top: Float, unit: Float) {
        var x = startX
        for (k in keys) {
            val kw = k.weight * unit
            k.rect.set(x + gapH / 2, top + gapV / 2, x + kw - gapH / 2, top + rowH - gapV / 2)
            x += kw
        }
    }

    /** The nearest real key in the touched row, so the gaps between keys still register. */
    private fun keyAt(x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        val i = ((y - popupSpace - topPad) / rowH).toInt().coerceIn(0, rows.size - 1)
        return rows[i].all.filter { it.type != KeyType.SPACER }.minByOrNull {
            when {
                x < it.rect.left -> it.rect.left - x
                x > it.rect.right -> x - it.rect.right
                else -> 0f
            }
        }
    }

    // ---- touch --------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (y < popupSpace) return touches.isNotEmpty()
                // Fast typing: a new finger lands before the last one lifts, so commit the last one now.
                touches.entries.filter { it.value.key.type == KeyType.CHAR }.forEach {
                    release(it.value)
                    touches.remove(it.key)
                }
                val key = keyAt(x, y) ?: return true
                val t = Touch(key, x, y)
                touches[e.getPointerId(i)] = t
                press(t)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = touches[e.getPointerId(i)] ?: continue
                    move(t, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touches.remove(e.getPointerId(e.actionIndex))
                if (t != null) release(t)
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        invalidate()
        return true
    }

    private fun press(t: Touch) {
        haptic()
        when (t.key.type) {
            KeyType.CHAR -> bubbleKey = t.key
            KeyType.SHIFT -> listener.onShiftTap()
            KeyType.DELETE -> {
                deleteCount = 0
                listener.onDelete(0)
                handler.removeCallbacks(deleteRepeat)
                handler.postDelayed(deleteRepeat, 450)
            }
            KeyType.SPACE, KeyType.GLOBE, KeyType.TO_NUMBERS, KeyType.TO_LETTERS -> {
                longPressTouch = t
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, if (t.key.type == KeyType.SPACE) 380 else 550)
            }
            else -> Unit
        }
    }

    private fun move(t: Touch, x: Float, y: Float) {
        if (trackpad && t.key.type == KeyType.SPACE) {
            t.accX += x - t.x
            t.accY += y - t.y
            val stepX = dp(9f)
            val stepY = dp(24f)
            val dx = (t.accX / stepX).toInt()
            val dy = (t.accY / stepY).toInt()
            if (dx != 0 || dy != 0) {
                t.accX -= dx * stepX
                t.accY -= dy * stepY
                listener.onCursorMove(dx, dy)
            }
        } else if (t.key.type == KeyType.CHAR) {
            // Sliding to a neighbour moves the bubble, like iOS.
            val k = keyAt(x, y)
            if (k != null && k.type == KeyType.CHAR && k !== t.key) {
                t.key = k
                bubbleKey = k
            }
        }
        t.x = x
        t.y = y
    }

    private fun release(t: Touch) {
        if (longPressTouch === t) {
            handler.removeCallbacks(longPress)
            longPressTouch = null
        }
        val k = t.key
        if (k.type == KeyType.DELETE) handler.removeCallbacks(deleteRepeat)
        if (bubbleKey === k) bubbleKey = null
        if (k.type == KeyType.SPACE && trackpad) {
            trackpad = false
            return
        }
        if (t.consumed) return
        when (k.type) {
            KeyType.CHAR -> listener.onCharacter(k.label)
            KeyType.SPACE -> listener.onSpace()
            KeyType.RETURN -> listener.onReturn()
            KeyType.GLOBE -> listener.onGlobe(false)
            KeyType.TO_NUMBERS, KeyType.TO_SYMBOLS, KeyType.TO_LETTERS -> listener.onModeKey(k.type)
            else -> Unit
        }
    }

    fun reset() {
        handler.removeCallbacks(deleteRepeat)
        handler.removeCallbacks(longPress)
        touches.clear()
        longPressTouch = null
        bubbleKey = null
        trackpad = false
        invalidate()
    }

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ---- drawing ------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val p = palette
        fill.color = p.bg
        c.drawRect(0f, popupSpace.toFloat(), width.toFloat(), height.toFloat(), fill)
        val pressed = touches.values.map { it.key }.toSet()
        for (row in rows) for (k in row.all) {
            if (k.type != KeyType.SPACER) drawKey(c, k, k in pressed, p)
        }
        bubbleKey?.let { drawBubble(c, it, p) }
    }

    private fun drawKey(c: Canvas, k: Key, down: Boolean, p: Palette) {
        val isFunc = k.type != KeyType.CHAR && k.type != KeyType.SPACE
        var bg = if (isFunc) p.func else p.key
        var fg = p.text
        when {
            trackpad -> bg = p.func
            k.type == KeyType.SHIFT && shift != Shift.OFF -> bg = p.key
            k.type == KeyType.RETURN && returnHighlighted -> { bg = if (down) p.func else p.blue; fg = 0xFFFFFFFF.toInt() }
            isFunc && down -> bg = p.key
            k.type == KeyType.SPACE && down -> bg = p.func
        }
        val r = k.rect
        fill.color = p.shadow
        c.drawRoundRect(r.left, r.top + dp(1f), r.right, r.bottom + dp(1f), radius, radius, fill)
        fill.color = bg
        c.drawRoundRect(r, radius, radius, fill)
        if (trackpad) return

        text.color = fg
        stroke.color = fg
        fill.color = fg
        when (k.type) {
            KeyType.CHAR -> {
                val upper = mode == Mode.LETTERS && shift != Shift.OFF
                drawText(c, if (upper) k.label.uppercase() else k.label, r.centerX(), r.centerY(), sp(if (mode == Mode.LETTERS) 23f else 21f))
            }
            KeyType.SPACE -> if (k === rows.last().left.lastOrNull { it.type == KeyType.SPACE } || !split) {
                drawText(c, "space", r.centerX(), r.centerY(), sp(16f))
            }
            KeyType.RETURN -> drawText(c, returnLabel, r.centerX(), r.centerY(), sp(16f))
            KeyType.TO_NUMBERS, KeyType.TO_SYMBOLS, KeyType.TO_LETTERS -> drawText(c, k.label, r.centerX(), r.centerY(), sp(16f))
            KeyType.SHIFT -> drawShift(c, r)
            KeyType.DELETE -> drawDelete(c, r, down)
            KeyType.GLOBE -> drawGlobe(c, r)
            KeyType.SPACER -> Unit
        }
    }

    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, size: Float) {
        text.textSize = size
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawShift(c: Canvas, r: RectF) {
        val s = dp(19f)
        val l = r.centerX() - s / 2
        val t = r.centerY() - s / 2 - if (shift == Shift.LOCKED) dp(2f) else 0f
        val path = Path().apply {
            moveTo(l + s * 0.5f, t + s * 0.05f)
            lineTo(l + s * 0.95f, t + s * 0.52f)
            lineTo(l + s * 0.7f, t + s * 0.52f)
            lineTo(l + s * 0.7f, t + s * 0.9f)
            lineTo(l + s * 0.3f, t + s * 0.9f)
            lineTo(l + s * 0.3f, t + s * 0.52f)
            lineTo(l + s * 0.05f, t + s * 0.52f)
            close()
        }
        if (shift == Shift.OFF) {
            stroke.strokeWidth = dp(1.6f)
            c.drawPath(path, stroke)
        } else {
            c.drawPath(path, fill)
        }
        if (shift == Shift.LOCKED) {
            c.drawRect(l + s * 0.3f, t + s * 1.02f, l + s * 0.7f, t + s * 1.12f, fill)
        }
    }

    private fun drawDelete(c: Canvas, r: RectF, down: Boolean) {
        val w = dp(23f)
        val h = dp(17f)
        val l = r.centerX() - w / 2
        val t = r.centerY() - h / 2
        val body = Path().apply {
            moveTo(l, t + h / 2)
            lineTo(l + w * 0.32f, t)
            lineTo(l + w, t)
            lineTo(l + w, t + h)
            lineTo(l + w * 0.32f, t + h)
            close()
        }
        stroke.strokeWidth = dp(1.6f)
        if (down) c.drawPath(body, fill) else c.drawPath(body, stroke)
        val xc = l + w * 0.64f
        val yc = t + h / 2
        val d = h * 0.2f
        stroke.color = if (down) palette.key else text.color
        c.drawLine(xc - d, yc - d, xc + d, yc + d, stroke)
        c.drawLine(xc - d, yc + d, xc + d, yc - d, stroke)
        stroke.color = text.color
    }

    private fun drawGlobe(c: Canvas, r: RectF) {
        val rad = dp(9.5f)
        val cx = r.centerX()
        val cy = r.centerY()
        stroke.strokeWidth = dp(1.4f)
        c.drawCircle(cx, cy, rad, stroke)
        c.drawOval(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad, stroke)
        c.drawLine(cx - rad, cy, cx + rad, cy, stroke)
        c.drawLine(cx, cy - rad, cx, cy + rad, stroke)
    }

    /** The enlarged letter balloon that rises out of the pressed key. */
    private fun drawBubble(c: Canvas, k: Key, p: Palette) {
        val r = k.rect
        val bw = max(r.width() * 1.5f, r.width() + dp(16f))
        val bh = r.height() * 1.12f
        val bl = (r.centerX() - bw / 2).coerceIn(dp(2f), max(dp(2f), width - dp(2f) - bw))
        val bt = max(0f, r.top - bh - dp(8f))
        val br = radius * 1.8f

        val shape = Path().apply {
            addRoundRect(bl, bt, bl + bw, bt + bh, br, br, Path.Direction.CW)
            val neck = Path().apply {
                moveTo(bl, bt + bh - br)
                cubicTo(bl, bt + bh + dp(4f), r.left, r.top, r.left, r.top + dp(6f))
                lineTo(r.left, r.bottom - radius)
                lineTo(r.right, r.bottom - radius)
                lineTo(r.right, r.top + dp(6f))
                cubicTo(r.right, r.top, bl + bw, bt + bh + dp(4f), bl + bw, bt + bh - br)
                close()
            }
            op(neck, Path.Op.UNION)
            op(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }, Path.Op.UNION)
        }
        bubble.color = p.key
        bubble.setShadowLayer(dp(3f), 0f, dp(1f), 0x55000000)
        c.drawPath(shape, bubble)

        val upper = mode == Mode.LETTERS && shift != Shift.OFF
        text.color = p.text
        drawText(c, if (upper) k.label.uppercase() else k.label, bl + bw / 2, bt + bh / 2, sp(32f))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    init {
        // Shadow layers on paths need a layer to render reliably.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isHapticFeedbackEnabled = true
    }
}
