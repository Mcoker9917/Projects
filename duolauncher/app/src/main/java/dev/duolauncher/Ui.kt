package dev.duolauncher

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView

/** Small helpers for building the iOS 26-style screens in code. */

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = dp(v).toInt()

val Context.isDark: Boolean
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Frosted card colour over the wallpaper. */
fun Context.cardBg(radius: Float = 26f): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(radius)
    setColor(if (isDark) 0xB31C1C1E.toInt() else 0xD9F2F2F7.toInt())
}

/** Rounded pill (search fields, buttons). */
fun Context.pillBg(solid: Int? = null): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(22f)
    setColor(solid ?: if (isDark) 0x99303033.toInt() else 0xB3FFFFFF.toInt())
}

/** The iOS 26 dock: a translucent rounded bar. */
fun Context.dockBg(): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(32f)
    setColor(if (isDark) 0x66303033 else 0x66FFFFFF)
}

val Context.cardText: Int get() = if (isDark) Color.WHITE else Color.BLACK
val Context.dimText: Int get() = if (isDark) 0xFFB4B4B9.toInt() else 0xFF5E5E63.toInt()
const val BLUE = 0xFF0A84FF.toInt()

fun Context.text(
    s: CharSequence,
    sizeSp: Float,
    color: Int = cardText,
    bold: Boolean = false,
    onWallpaper: Boolean = false,
): TextView = TextView(this).apply {
    text = s
    textSize = sizeSp
    setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.END
    // Text straight on the wallpaper gets a soft shadow so it reads on any picture.
    if (onWallpaper) setShadowLayer(dp(3f), 0f, dp(1f), 0x80000000.toInt())
}

fun Context.pillButton(label: String, onClick: () -> Unit): TextView = text(label, 15f, BLUE, bold = true).apply {
    background = pillBg(if (isDark) 0x33FFFFFF else 0x14000000)
    gravity = Gravity.CENTER
    setPadding(dpi(16f), dpi(9f), dpi(16f), dpi(9f))
    setOnClickListener { onClick() }
}
