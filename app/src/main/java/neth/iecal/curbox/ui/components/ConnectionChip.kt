package neth.iecal.curbox.ui.components

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ViewConnectionChipBinding

/** Pill with a colored dot that tells whether this phone is in touch with its admin. */
class ConnectionChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class State { CONNECTED, SYNCING, OFFLINE, WAITING, ERROR }

    private val binding: ViewConnectionChipBinding
    private var pulse: ObjectAnimator? = null

    var state: State = State.OFFLINE
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_curbox_pill)
        setPaddingRelative(context.dpInt(10), context.dpInt(5), context.dpInt(12), context.dpInt(5))
        binding = ViewConnectionChipBinding.inflate(LayoutInflater.from(context), this)

        val a = context.obtainStyledAttributes(attrs, R.styleable.ConnectionChip)
        val index = try {
            a.getInt(R.styleable.ConnectionChip_connectionState, State.OFFLINE.ordinal)
        } finally {
            a.recycle()
        }
        setState(State.values().getOrElse(index) { State.OFFLINE })
    }

    /** Shows [newState]; [label] replaces the default text, for example "Synced 2 min ago". */
    fun setState(newState: State, label: CharSequence? = null) {
        state = newState
        val accent = ContextCompat.getColor(
            context,
            when (newState) {
                State.CONNECTED -> R.color.curbox_success
                State.SYNCING -> R.color.curbox_primary
                State.OFFLINE, State.WAITING -> R.color.curbox_warning
                State.ERROR -> R.color.curbox_error
            }
        )
        val textColor = if (newState == State.OFFLINE || newState == State.WAITING) {
            ContextCompat.getColor(context, R.color.curbox_warning_text)
        } else {
            accent
        }
        backgroundTintList = ColorStateList.valueOf(accent.withAlpha(0.14f))
        binding.chipDot.backgroundTintList = ColorStateList.valueOf(accent)
        binding.chipText.setTextColor(textColor)
        binding.chipText.text = label ?: context.getString(
            when (newState) {
                State.CONNECTED -> R.string.connection_connected
                State.SYNCING -> R.string.connection_syncing
                State.OFFLINE -> R.string.connection_offline
                State.WAITING -> R.string.connection_waiting
                State.ERROR -> R.string.connection_error
            }
        )
        updatePulse()
    }

    private fun updatePulse() {
        val shouldPulse = state == State.SYNCING && isAttachedToWindow
        if (shouldPulse && pulse == null) {
            pulse = ObjectAnimator.ofFloat(binding.chipDot, View.ALPHA, 1f, 0.3f).apply {
                duration = 700L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!shouldPulse) {
            pulse?.cancel()
            pulse = null
            binding.chipDot.alpha = 1f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulse()
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }
}
