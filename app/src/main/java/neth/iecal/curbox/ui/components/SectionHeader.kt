package neth.iecal.curbox.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ViewSectionHeaderBinding

/** Section title with an optional action on the right, such as "See all". */
class SectionHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding: ViewSectionHeaderBinding

    var title: CharSequence
        get() = binding.sectionTitle.text
        set(text) {
            binding.sectionTitle.text = text
        }

    /** Empty hides the action. */
    var actionText: CharSequence
        get() = binding.sectionAction.text
        set(text) {
            binding.sectionAction.text = text
            binding.sectionAction.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        binding = ViewSectionHeaderBinding.inflate(LayoutInflater.from(context), this)
        ViewCompat.setAccessibilityHeading(binding.sectionTitle, true)

        val a = context.obtainStyledAttributes(attrs, R.styleable.SectionHeader)
        try {
            title = a.getString(R.styleable.SectionHeader_sectionTitle).orEmpty()
            actionText = a.getString(R.styleable.SectionHeader_sectionAction).orEmpty()
        } finally {
            a.recycle()
        }
    }

    fun setOnActionClickListener(listener: (View) -> Unit) {
        binding.sectionAction.setOnClickListener(listener)
    }
}
