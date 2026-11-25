package neth.iecal.curbox.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import neth.iecal.curbox.R

/** Small rounded label that says whether something is allowed, blocked, limited or scheduled. */
class StatusPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    enum class State { ALLOWED, BLOCKED, TIME_LIMITED, SCHEDULED }

    var state: State = State.ALLOWED
        private set

    init {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Curbox_Caption)
        setTypeface(typeface, Typeface.BOLD)
        setBackgroundResource(R.drawable.bg_curbox_pill)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        compoundDrawablePadding = context.dpInt(4)
        setPaddingRelative(context.dpInt(8), context.dpInt(4), context.dpInt(10), context.dpInt(4))

        val a = context.obtainStyledAttributes(attrs, R.styleable.StatusPill)
        val index = try {
            a.getInt(R.styleable.StatusPill_pillState, 0)
        } finally {
            a.recycle()
        }
        setState(State.values().getOrElse(index) { State.ALLOWED })
    }

    /** Shows [newState]; [label] replaces the default word, for example "30m a day". */
    fun setState(newState: State, label: CharSequence? = null) {
        state = newState
        val accent = color(
            when (newState) {
                State.ALLOWED -> R.color.curbox_primary
                State.BLOCKED -> R.color.curbox_error
                State.TIME_LIMITED -> R.color.curbox_warning
                State.SCHEDULED -> R.color.curbox_secondary
            }
        )
        val textColor = when (newState) {
            State.ALLOWED -> color(R.color.curbox_on_primary_container)
            State.TIME_LIMITED -> color(R.color.curbox_warning_text)
            else -> accent
        }
        backgroundTintList = ColorStateList.valueOf(
            if (newState == State.ALLOWED) color(R.color.curbox_primary_container) else accent.withAlpha(0.14f)
        )
        setTextColor(textColor)

        val iconRes = when (newState) {
            State.ALLOWED -> R.drawable.ic_curbox_check
            State.BLOCKED -> R.drawable.ic_curbox_block
            State.TIME_LIMITED -> R.drawable.ic_curbox_clock
            State.SCHEDULED -> R.drawable.ic_curbox_event
        }
        val size = context.dpInt(14)
        val icon = AppCompatResources.getDrawable(context, iconRes)?.mutate()?.apply {
            setBounds(0, 0, size, size)
            setTint(textColor)
        }
        setCompoundDrawablesRelative(icon, null, null, null)

        text = label ?: context.getString(
            when (newState) {
                State.ALLOWED -> R.string.pill_allowed
                State.BLOCKED -> R.string.pill_blocked
                State.TIME_LIMITED -> R.string.pill_time_limited
                State.SCHEDULED -> R.string.pill_scheduled
            }
        )
    }

    private fun color(res: Int): Int = ContextCompat.getColor(context, res)
}
