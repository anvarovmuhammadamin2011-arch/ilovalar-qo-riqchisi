package neth.iecal.curbox.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ViewAppRowBinding
import kotlin.math.roundToInt

/**
 * One row: an app icon (or a symbol in a tinted circle), a title, an optional
 * subtitle and usage bar, and one trailing item: a value, a StatusPill or a chevron.
 */
class AppRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding: ViewAppRowBinding

    var title: CharSequence
        get() = binding.rowTitle.text
        set(text) {
            binding.rowTitle.text = text
        }

    /** Empty hides the subtitle. */
    var subtitle: CharSequence
        get() = binding.rowSubtitle.text
        set(text) {
            binding.rowSubtitle.text = text
            binding.rowSubtitle.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpInt(64)
        val vertical = context.dpInt(10)
        setPadding(paddingLeft, vertical, paddingRight, vertical)
        binding = ViewAppRowBinding.inflate(LayoutInflater.from(context), this)

        val a = context.obtainStyledAttributes(attrs, R.styleable.AppRowView)
        try {
            val icon = a.getDrawable(R.styleable.AppRowView_rowIcon)
            if (a.getBoolean(R.styleable.AppRowView_rowIconInCircle, false)) {
                setSymbol(icon)
            } else {
                setAppIcon(icon)
            }
            title = a.getString(R.styleable.AppRowView_rowTitle).orEmpty()
            subtitle = a.getString(R.styleable.AppRowView_rowSubtitle).orEmpty()
            if (a.getBoolean(R.styleable.AppRowView_rowShowChevron, false)) showChevron()
        } finally {
            a.recycle()
        }
    }

    /** A full color app icon, shown as is. */
    fun setAppIcon(icon: Drawable?) {
        binding.rowIconBg.background = null
        binding.rowIcon.imageTintList = null
        binding.rowIcon.updateLayoutParams { width = context.dpInt(40); height = context.dpInt(40) }
        binding.rowIcon.setImageDrawable(icon)
    }

    /** A one color symbol inside a softly tinted circle. */
    fun setSymbol(icon: Drawable?, @ColorInt tint: Int = ContextCompat.getColor(context, R.color.curbox_primary)) {
        binding.rowIconBg.setBackgroundResource(R.drawable.bg_curbox_circle)
        binding.rowIconBg.backgroundTintList = ColorStateList.valueOf(tint.withAlpha(0.14f))
        binding.rowIcon.imageTintList = ColorStateList.valueOf(tint)
        binding.rowIcon.updateLayoutParams { width = context.dpInt(22); height = context.dpInt(22) }
        binding.rowIcon.setImageDrawable(icon)
    }

    fun setSymbol(@DrawableRes icon: Int, @ColorInt tint: Int = ContextCompat.getColor(context, R.color.curbox_primary)) {
        setSymbol(ContextCompat.getDrawable(context, icon), tint)
    }

    /** A thin bar under the text, [fraction] from 0 to 1. Null hides it. */
    fun setBar(fraction: Float?) {
        if (fraction == null) {
            binding.rowBar.visibility = View.GONE
            return
        }
        binding.rowBar.visibility = View.VISIBLE
        binding.rowBar.setProgressCompat((fraction.coerceIn(0f, 1f) * binding.rowBar.max).roundToInt(), false)
    }

    fun showValue(value: CharSequence) {
        clearTrailing()
        binding.rowValue.text = value
        binding.rowValue.visibility = View.VISIBLE
    }

    fun showPill(state: StatusPill.State, label: CharSequence? = null) {
        clearTrailing()
        binding.rowPill.setState(state, label)
        binding.rowPill.visibility = View.VISIBLE
    }

    fun showChevron() {
        clearTrailing()
        binding.rowChevron.visibility = View.VISIBLE
    }

    fun clearTrailing() {
        binding.rowValue.visibility = View.GONE
        binding.rowPill.visibility = View.GONE
        binding.rowChevron.visibility = View.GONE
    }
}
