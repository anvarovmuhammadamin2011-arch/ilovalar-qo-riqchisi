package neth.iecal.curbox.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ViewStatTileBinding

/** Card with an icon in a tinted circle, a big number and a caption. */
class StatTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val binding = ViewStatTileBinding.inflate(LayoutInflater.from(context), this)

    var value: CharSequence
        get() = binding.tileValue.text
        set(text) {
            binding.tileValue.text = text
        }

    var caption: CharSequence
        get() = binding.tileCaption.text
        set(text) {
            binding.tileCaption.text = text
        }

    @ColorInt
    var tint: Int = ContextCompat.getColor(context, R.color.curbox_primary)
        set(color) {
            field = color
            binding.tileIcon.imageTintList = ColorStateList.valueOf(color)
            binding.tileIconBg.backgroundTintList = ColorStateList.valueOf(color.withAlpha(0.14f))
        }

    init {
        radius = resources.getDimension(R.dimen.curbox_radius_tile)
        val a = context.obtainStyledAttributes(attrs, R.styleable.StatTileView)
        try {
            a.getDrawable(R.styleable.StatTileView_tileIcon)?.let { binding.tileIcon.setImageDrawable(it) }
            value = a.getString(R.styleable.StatTileView_tileValue).orEmpty()
            caption = a.getString(R.styleable.StatTileView_tileCaption).orEmpty()
            tint = a.getColor(R.styleable.StatTileView_tileTint, tint)
        } finally {
            a.recycle()
        }
    }

    fun setIcon(@DrawableRes icon: Int) {
        binding.tileIcon.setImageResource(icon)
    }
}
