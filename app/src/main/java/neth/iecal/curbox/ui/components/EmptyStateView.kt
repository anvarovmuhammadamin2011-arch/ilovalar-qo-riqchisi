package neth.iecal.curbox.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ViewEmptyStateBinding

/** Friendly "nothing here yet" block: icon, title, one line of help and an optional action. */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding: ViewEmptyStateBinding

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val padding = context.dpInt(24)
        setPadding(padding, padding, padding, padding)
        binding = ViewEmptyStateBinding.inflate(LayoutInflater.from(context), this)

        val primary = ContextCompat.getColor(context, R.color.curbox_primary)
        binding.emptyIconBg.backgroundTintList = ColorStateList.valueOf(primary.withAlpha(0.12f))
        binding.emptyIcon.imageTintList = ColorStateList.valueOf(primary)

        val a = context.obtainStyledAttributes(attrs, R.styleable.EmptyStateView)
        try {
            binding.emptyIcon.setImageDrawable(
                a.getDrawable(R.styleable.EmptyStateView_emptyIcon)
                    ?: ContextCompat.getDrawable(context, R.drawable.ic_curbox_inbox)
            )
            binding.emptyTitle.text = a.getString(R.styleable.EmptyStateView_emptyTitle).orEmpty()
            setMessage(a.getString(R.styleable.EmptyStateView_emptyMessage).orEmpty())
            val action = a.getString(R.styleable.EmptyStateView_emptyAction).orEmpty()
            if (action.isNotEmpty()) setAction(action, null)
        } finally {
            a.recycle()
        }
    }

    fun setContent(@DrawableRes icon: Int, title: CharSequence, message: CharSequence) {
        binding.emptyIcon.setImageResource(icon)
        binding.emptyTitle.text = title
        setMessage(message)
    }

    private fun setMessage(message: CharSequence) {
        binding.emptyMessage.text = message
        binding.emptyMessage.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Shows a button under the text. Null [text] hides it. */
    fun setAction(text: CharSequence?, onClick: ((View) -> Unit)?) {
        binding.emptyAction.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.emptyAction.text = text
        binding.emptyAction.setOnClickListener(onClick)
    }
}
