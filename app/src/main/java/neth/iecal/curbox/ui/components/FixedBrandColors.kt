package neth.iecal.curbox.ui.components

import android.content.Context
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Marks an activity that uses Theme.Curbox.User. Curbox skips Material dynamic
 * colors for these, otherwise the wallpaper palette would replace the design
 * system colors on Android 12 and newer.
 */
interface FixedBrandColors

internal fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

internal fun Context.dpInt(value: Int): Int = dp(value.toFloat()).roundToInt()

internal fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

/** The same color with [fraction] (0 to 1) opacity, for soft tinted backgrounds. */
@ColorInt
internal fun Int.withAlpha(fraction: Float): Int =
    ColorUtils.setAlphaComponent(this, (fraction * 255).roundToInt().coerceIn(0, 255))
