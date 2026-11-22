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
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
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

    /** Room for Android's navigation bar, which is drawn over the bottom of the keyboard window. */
    private var navInset = 0

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()).bottom
        if (bottom != navInset) {
            navInset = bottom
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootWindowInsets?.let { onApplyWindowInsets(it) }
        requestApplyInsets()
    }

    var mode = Mode.LETTERS
        set(v) {
            if (field != v) {
                field = v
                if (v == Mode.EMOJI) loadEmoji()
                rebuild()
            }
        }
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

    private val isSplit get() = split && mode != Mode.EMOJI
    private var rows: List<Row> = Layouts.rows(mode, isSplit, showGlobe)

    // ---- colors -------------------------------------------------------------------------

    private class Palette(val bg: Int, val key: Int, val func: Int, val shadow: Int, val text: Int, val blue: Int, val dim: Int)

    private val light = Palette(0xFFD1D3D9.toInt(), 0xFFFFFFFF.toInt(), 0xFFABB0BA.toInt(), 0xFF898A8D.toInt(),
        0xFF000000.toInt(), 0xFF007AFF.toInt(), 0xFF6D6D72.toInt())
    private val dark = Palette(0xFF2B2B2D.toInt(), 0xFF6B6B6D.toInt(), 0xFF464648.toInt(), 0xFF000000.toInt(),
        0xFFFFFFFF.toInt(), 0xFF0A84FF.toInt(), 0xFFA0A0A5.toInt())

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

    // Accent strip (hold a letter)
    private var accentTouch: Touch? = null
    private var accentOptions: List<String> = emptyList()
    private var accentSel = 0
    private val accentStrip = RectF()
    private var accentCellW = 0f

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
            KeyType.SPACE -> { trackpad = true; bubbleKey = null }
            KeyType.GLOBE -> { listener.onGlobe(true); t.consumed = true }
            KeyType.TO_NUMBERS, KeyType.TO_LETTERS -> { listener.onToggleSplit(); t.consumed = true }
            KeyType.CHAR -> openAccents(t)
            else -> return@Runnable
        }
        haptic()
        invalidate()
    }

    // ---- layout -------------------------------------------------------------------------

    private fun rebuild() {
        rows = Layouts.rows(mode, isSplit, showGlobe)
        layoutKeys()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = popupSpace + topPad + rows.size * rowH + bottomPad + navInset
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun List<Key>.weight() = fold(0f) { acc, k -> acc + k.weight }

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f) return
        val inner = w - 2 * sidePad
        var top = popupSpace + topPad
        if (!isSplit) {
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
        if (mode == Mode.EMOJI && emojiTouch(e)) {
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (y < popupSpace) return touches.isNotEmpty()
                // Fast typing: a new finger lands before the last one lifts, so commit the last one now.
                touches.entries.filter { it.value.key.type == KeyType.CHAR }.forEach { (id, t) ->
                    release(t)
                    touches.remove(id)
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

    private fun scheduleLongPress(t: Touch) {
        handler.removeCallbacks(longPress)
        longPressTouch = t
        val delay = when (t.key.type) {
            KeyType.SPACE -> 380L
            KeyType.CHAR -> 420L
            else -> 550L
        }
        handler.postDelayed(longPress, delay)
    }

    private fun press(t: Touch) {
        haptic()
        when (t.key.type) {
            KeyType.CHAR -> {
                bubbleKey = t.key
                if (Layouts.accents.containsKey(t.key.label)) scheduleLongPress(t)
            }
            KeyType.SHIFT -> listener.onShiftTap()
            KeyType.DELETE -> {
                deleteCount = 0
                listener.onDelete(0)
                handler.removeCallbacks(deleteRepeat)
                handler.postDelayed(deleteRepeat, 450)
            }
            KeyType.SPACE, KeyType.GLOBE, KeyType.TO_NUMBERS, KeyType.TO_LETTERS -> scheduleLongPress(t)
            else -> Unit
        }
    }

    private fun move(t: Touch, x: Float, y: Float) {
        if (accentTouch === t) {
            accentSel = ((x - accentStrip.left - dp(4f)) / accentCellW).toInt().coerceIn(0, accentOptions.size - 1)
        } else if (trackpad && t.key.type == KeyType.SPACE) {
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
                if (longPressTouch === t) handler.removeCallbacks(longPress)
                if (Layouts.accents.containsKey(k.label)) scheduleLongPress(t)
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
        if (accentTouch === t) {
            listener.onCharacter(accentOptions[accentSel])
            accentTouch = null
            return
        }
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
            KeyType.TO_NUMBERS, KeyType.TO_SYMBOLS, KeyType.TO_LETTERS, KeyType.TO_EMOJI -> listener.onModeKey(k.type)
            else -> Unit
        }
    }

    private fun openAccents(t: Touch) {
        val opts = Layouts.accents[t.key.label] ?: return
        val r = t.key.rect
        accentCellW = max(r.width(), dp(34f))
        val stripW = accentCellW * opts.size + dp(8f)
        val maxL = width - dp(2f) - stripW
        val left = if (maxL < dp(2f)) dp(2f) else (r.left - dp(4f)).coerceIn(dp(2f), maxL)
        val top = max(0f, r.top - r.height() - dp(14f))
        accentStrip.set(left, top, left + stripW, top + r.height() + dp(8f))
        accentOptions = opts
        accentSel = ((r.centerX() - left - dp(4f)) / accentCellW).toInt().coerceIn(0, opts.size - 1)
        accentTouch = t
        bubbleKey = null
    }

    fun reset() {
        handler.removeCallbacks(deleteRepeat)
        handler.removeCallbacks(longPress)
        touches.clear()
        longPressTouch = null
        bubbleKey = null
        accentTouch = null
        trackpad = false
        emojiPointer = -1
        emojiPressed = -1
        invalidate()
    }

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ---- emoji panel --------------------------------------------------------------------

    private val prefs = context.getSharedPreferences("foldkeys", Context.MODE_PRIVATE)
    private var cats: List<EmojiCategory> = Emoji.categories
    private var colStart = IntArray(0)
    private var totalCols = 0
    private var emojiScroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var emojiPointer = -1
    private var emojiOnTabs = false
    private var emojiDownX = 0f
    private var emojiDownY = 0f
    private var emojiLastX = 0f
    private var emojiMoved = false
    private var emojiPressed = -1   // flat index of the emoji under the finger, for the highlight
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val emojiRows = 4

    private val gridTop get() = popupSpace + topPad
    private val gridBottom get() = popupSpace + topPad + 3 * rowH
    private val headerH get() = dp(22f)
    private val cellW get() = dp(44f)
    private val cellH get() = (3 * rowH - headerH) / emojiRows
    private val gridLeft get() = dp(6f)
    private val maxScroll get() = max(0f, 2 * gridLeft + totalCols * cellW - width)
    private val tabRect: RectF? get() = rows.lastOrNull()?.left?.firstOrNull { it.type == KeyType.SPACER }?.rect

    private fun recents(): List<String> =
        prefs.getString("recent_emoji", "").orEmpty().split("\n").filter { it.isNotEmpty() }

    private fun loadEmoji() {
        val recent = recents()
        cats = if (recent.isEmpty()) Emoji.categories
        else listOf(EmojiCategory("🕘", "FREQUENTLY USED", recent)) + Emoji.categories
        colStart = IntArray(cats.size)
        var col = 0
        cats.forEachIndexed { i, c ->
            colStart[i] = col
            col += (c.items.size + emojiRows - 1) / emojiRows
        }
        totalCols = col
        emojiScroll = 0f
        scroller.forceFinished(true)
    }

    private fun rememberEmoji(e: String) {
        val list = (listOf(e) + recents().filter { it != e }).take(32)
        prefs.edit().putString("recent_emoji", list.joinToString("\n")).apply()
    }

    /** (category, item) at a point in the grid, or null. */
    private fun emojiAt(x: Float, y: Float): Pair<Int, Int>? {
        val col = ((x + emojiScroll - gridLeft) / cellW).toInt()
        val row = ((y - gridTop - headerH) / cellH).toInt()
        if (row !in 0 until emojiRows || col < 0) return null
        val c = colStart.indices.lastOrNull { colStart[it] <= col } ?: return null
        val idx = (col - colStart[c]) * emojiRows + row
        return if (idx < cats[c].items.size) c to idx else null
    }

    private fun currentCategory(): Int =
        colStart.indices.lastOrNull { colStart[it] * cellW <= emojiScroll + cellW / 2 } ?: 0

    private fun flat(p: Pair<Int, Int>) = p.first * 10000 + p.second

    /** Returns true when the event belongs to the emoji grid or tabs rather than a key. */
    private fun emojiTouch(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (emojiPointer != -1) return true
                val x = e.getX(i)
                val y = e.getY(i)
                val tabs = tabRect
                val inGrid = y >= gridTop && y < gridBottom
                val onTabs = tabs != null && y >= gridBottom && x >= tabs.left && x <= tabs.right
                if (!inGrid && !onTabs) return false
                emojiPointer = e.getPointerId(i)
                emojiOnTabs = onTabs
                emojiDownX = x
                emojiDownY = y
                emojiLastX = x
                emojiMoved = false
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                emojiPressed = if (inGrid) emojiAt(x, y)?.let { flat(it) } ?: -1 else -1
                haptic()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (emojiPointer == -1) return false
                val pi = e.findPointerIndex(emojiPointer)
                if (pi < 0) return false
                velocity?.addMovement(e)
                val x = e.getX(pi)
                if (!emojiOnTabs) {
                    if (abs(x - emojiDownX) > slop) {
                        emojiMoved = true
                        emojiPressed = -1
                    }
                    if (emojiMoved) emojiScroll = (emojiScroll - (x - emojiLastX)).coerceIn(0f, maxScroll)
                }
                emojiLastX = x
                invalidate()
                return touches.isEmpty() // let held keys (like delete) keep their own tracking
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(i) != emojiPointer) return false
                if (emojiOnTabs) {
                    val tabs = tabRect
                    if (tabs != null && cats.isNotEmpty()) {
                        val t = ((emojiDownX - tabs.left) / (tabs.width() / cats.size)).toInt().coerceIn(0, cats.size - 1)
                        emojiScroll = (colStart[t] * cellW).coerceIn(0f, maxScroll)
                    }
                } else if (!emojiMoved) {
                    emojiAt(emojiDownX, emojiDownY)?.let { (c, idx) ->
                        val em = cats[c].items[idx]
                        listener.onCharacter(em)
                        rememberEmoji(em)
                    }
                } else {
                    velocity?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(emojiScroll.toInt(), 0, -it.xVelocity.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                }
                velocity?.recycle()
                velocity = null
                emojiPointer = -1
                emojiPressed = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                emojiPointer = -1
                emojiPressed = -1
                return false
            }
        }
        return false
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            emojiScroll = scroller.currX.toFloat()
            postInvalidateOnAnimation()
        }
    }

    private fun drawEmoji(c: Canvas, p: Palette) {
        val w = width.toFloat()
        // Category title
        text.color = p.dim
        text.textSize = sp(12f)
        text.textAlign = Paint.Align.LEFT
        text.isFakeBoldText = true
        c.drawText(cats.getOrNull(currentCategory())?.name.orEmpty(), gridLeft + dp(4f), gridTop + headerH * 0.7f, text)
        text.isFakeBoldText = false
        text.textAlign = Paint.Align.CENTER

        c.save()
        c.clipRect(0f, gridTop + headerH, w, gridBottom)
        text.textSize = sp(27f)
        val fm = text.fontMetrics
        val firstCol = ((emojiScroll - gridLeft) / cellW).toInt() - 1
        val lastCol = firstCol + (w / cellW).toInt() + 3
        cats.forEachIndexed { ci, cat ->
            val cols = (cat.items.size + emojiRows - 1) / emojiRows
            if (colStart[ci] + cols < firstCol || colStart[ci] > lastCol) return@forEachIndexed
            cat.items.forEachIndexed { idx, em ->
                val col = colStart[ci] + idx / emojiRows
                if (col < firstCol || col > lastCol) return@forEachIndexed
                val cx = gridLeft + col * cellW + cellW / 2 - emojiScroll
                val cy = gridTop + headerH + (idx % emojiRows) * cellH + cellH / 2
                if (flat(ci to idx) == emojiPressed) {
                    fill.color = p.func
                    c.drawRoundRect(cx - cellW / 2 + dp(2f), cy - cellH / 2 + dp(1f), cx + cellW / 2 - dp(2f),
                        cy + cellH / 2 - dp(1f), radius, radius, fill)
                }
                c.drawText(em, cx, cy - (fm.ascent + fm.descent) / 2, text)
            }
        }
        c.restore()

        // Category tabs
        val tabs = tabRect ?: return
        if (cats.isEmpty()) return
        val tw = tabs.width() / cats.size
        val cur = currentCategory()
        text.textSize = sp(17f)
        val tfm = text.fontMetrics
        cats.forEachIndexed { i, cat ->
            val cx = tabs.left + tw * i + tw / 2
            if (i == cur) {
                fill.color = p.func
                c.drawCircle(cx, tabs.centerY(), dp(15f), fill)
            }
            text.alpha = if (i == cur) 255 else 140
            c.drawText(cat.icon, cx, tabs.centerY() - (tfm.ascent + tfm.descent) / 2, text)
        }
        text.alpha = 255
    }

    // ---- drawing ------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val p = palette
        fill.color = p.bg
        c.drawRect(0f, popupSpace.toFloat(), width.toFloat(), height.toFloat(), fill)
        if (mode == Mode.EMOJI) drawEmoji(c, p)
        val pressed = touches.values.map { it.key }.toSet()
        for (row in rows) for (k in row.all) {
            if (k.type != KeyType.SPACER) drawKey(c, k, k in pressed, p)
        }
        bubbleKey?.let { drawBubble(c, it, p) }
        if (accentTouch != null) drawAccents(c, p)
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
            KeyType.CHAR -> drawText(c, display(k.label), r.centerX(), r.centerY(), sp(if (mode == Mode.LETTERS) 23f else 21f))
            KeyType.SPACE -> if (k === rows.last().left.lastOrNull { it.type == KeyType.SPACE } || !isSplit) {
                drawText(c, "space", r.centerX(), r.centerY(), sp(16f))
            }
            KeyType.RETURN -> drawText(c, returnLabel, r.centerX(), r.centerY(), sp(16f))
            KeyType.TO_NUMBERS, KeyType.TO_SYMBOLS, KeyType.TO_LETTERS -> drawText(c, k.label, r.centerX(), r.centerY(), sp(16f))
            KeyType.SHIFT -> drawShift(c, r)
            KeyType.DELETE -> drawDelete(c, r, down, p)
            KeyType.GLOBE -> drawGlobe(c, r)
            KeyType.TO_EMOJI -> drawSmiley(c, r)
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

    private fun drawDelete(c: Canvas, r: RectF, down: Boolean, p: Palette) {
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
        val fg = stroke.color
        stroke.color = if (down) p.key else fg
        c.drawLine(xc - d, yc - d, xc + d, yc + d, stroke)
        c.drawLine(xc - d, yc + d, xc + d, yc - d, stroke)
        stroke.color = fg
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

    private fun drawSmiley(c: Canvas, r: RectF) {
        val rad = dp(10f)
        val cx = r.centerX()
        val cy = r.centerY()
        stroke.strokeWidth = dp(1.5f)
        c.drawCircle(cx, cy, rad, stroke)
        c.drawCircle(cx - rad * 0.35f, cy - rad * 0.25f, dp(1.3f), fill)
        c.drawCircle(cx + rad * 0.35f, cy - rad * 0.25f, dp(1.3f), fill)
        c.drawArc(cx - rad * 0.5f, cy - rad * 0.3f, cx + rad * 0.5f, cy + rad * 0.55f, 20f, 140f, false, stroke)
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

        text.color = p.text
        drawText(c, display(k.label), bl + bw / 2, bt + bh / 2, sp(32f))
    }

    /** The strip of accented letters, with the one under the finger highlighted in blue. */
    private fun drawAccents(c: Canvas, p: Palette) {
        val s = accentStrip
        bubble.color = p.key
        bubble.setShadowLayer(dp(3f), 0f, dp(1f), 0x55000000)
        c.drawRoundRect(s, radius * 1.6f, radius * 1.6f, bubble)
        accentOptions.forEachIndexed { i, opt ->
            val l = s.left + dp(4f) + i * accentCellW
            val cell = RectF(l, s.top + dp(4f), l + accentCellW, s.bottom - dp(4f))
            if (i == accentSel) {
                fill.color = p.blue
                c.drawRoundRect(cell, radius, radius, fill)
                text.color = 0xFFFFFFFF.toInt()
            } else {
                text.color = p.text
            }
            drawText(c, display(opt), cell.centerX(), cell.centerY(), sp(24f))
        }
    }

    private fun display(s: String): String {
        val upper = mode == Mode.LETTERS && shift != Shift.OFF
        return if (upper && s != "ß") s.uppercase() else s
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
