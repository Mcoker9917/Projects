package dev.duolauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/** One app: rounded icon with its name underneath. Tap opens it; long-press gives options. */
@SuppressLint("ViewConstructor")
class IconView(
    context: Context,
    val app: AppInfo,
    private val iconDp: Float,
    private val showLabel: Boolean,
    private val labelColor: Int,
    onLong: (IconView) -> Unit,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(12f)
        color = labelColor
        if (labelColor == Color.WHITE) setShadowLayer(context.dp(2f), 0f, context.dp(1f), 0x99000000.toInt())
    }
    private val dst = RectF()

    init {
        isClickable = true
        isLongClickable = true
        contentDescription = app.label
        setOnClickListener { AppsRepo.launch(context, app, this) }
        setOnLongClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLong(this)
            true
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        alpha = if (isPressed) 0.6f else 1f
    }

    override fun onDraw(c: Canvas) {
        val s = context.dp(iconDp)
        val left = (width - s) / 2
        val top = if (showLabel) context.dp(6f) else (height - s) / 2
        dst.set(left, top, left + s, top + s)
        c.drawBitmap(app.icon, null, dst, paint)
        if (showLabel) {
            val name = TextUtils.ellipsize(app.label, label, width - context.dp(6f), TextUtils.TruncateAt.END).toString()
            c.drawText(name, width / 2f, dst.bottom + context.dp(17f), label)
        }
    }
}

/** Lays children out in a grid of equal cells. */
class AppGrid(context: Context, var columns: Int, private val cellHeightDp: Float) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cellW = w / columns
        val cellH = context.dpi(cellHeightDp)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY),
            )
        }
        val rows = (childCount + columns - 1) / columns
        setMeasuredDimension(w, rows * cellH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cellW = (r - l) / columns
        val cellH = context.dpi(cellHeightDp)
        for (i in 0 until childCount) {
            val x = (i % columns) * cellW
            val y = (i / columns) * cellH
            getChildAt(i).layout(x, y, x + cellW, y + cellH)
        }
    }
}

/**
 * A frame that notices a vertical swipe (up or down) and hands it off, without stealing
 * taps or normal scrolling from what's inside.
 */
class SwipeFrame(
    context: Context,
    private val up: Boolean,
    private val canSwipe: () -> Boolean,
    private val onSwipe: () -> Unit,
) : FrameLayout(context) {

    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                fired = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                val dx = e.x - downX
                val rightWay = if (up) dy < -slop * 3 else dy > slop * 3
                if (!fired && rightWay && abs(dy) > abs(dx) * 1.5f && canSwipe()) {
                    fired = true
                    onSwipe()
                    return true
                }
            }
        }
        return false
    }

    // Swipes that start on empty space (nothing inside took the touch) come here directly.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = e.x
            downY = e.y
            fired = false
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE && !fired) onInterceptTouchEvent(e)
        return true
    }
}
