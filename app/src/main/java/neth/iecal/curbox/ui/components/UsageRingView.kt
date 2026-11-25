package neth.iecal.curbox.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import neth.iecal.curbox.R

/**
 * Round progress ring with a big time in the middle and a caption under it.
 *
 * [progress] is the share of a limit that is used, 0 to 1. Null means there is no
 * limit: only the track is drawn, so the ring never suggests a limit that does not exist.
 */
class UsageRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var thickness = context.dp(14f)
    private val arc = RectF()
    private var drawnProgress = 0f
    private var animator: ValueAnimator? = null
    private var hasAnimatedIn = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.curbox_surface_variant)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.curbox_primary)
    }
    private val centerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.curbox_text)
        typeface = interTypeface(Typeface.BOLD)
    }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.curbox_text_secondary)
        textSize = context.sp(13f)
        typeface = interTypeface(Typeface.NORMAL)
    }

    var progress: Float? = null
        set(value) {
            field = value?.takeIf { it >= 0f }?.coerceAtMost(1f)
            animateTo(field ?: 0f)
            updateDescription()
        }

    var centerText: String = ""
        set(value) {
            field = value
            updateDescription()
            invalidate()
        }

    var caption: String = ""
        set(value) {
            field = value
            updateDescription()
            invalidate()
        }

    /** Ring color, for example warning or error when the limit is close. */
    @ColorInt
    var progressColor: Int = progressPaint.color
        set(value) {
            field = value
            progressPaint.color = value
            invalidate()
        }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.UsageRingView)
        try {
            thickness = a.getDimension(R.styleable.UsageRingView_ringThickness, thickness)
            centerText = a.getString(R.styleable.UsageRingView_ringCenterText).orEmpty()
            caption = a.getString(R.styleable.UsageRingView_ringCaption).orEmpty()
            val initial = a.getFloat(R.styleable.UsageRingView_ringProgress, -1f)
            progress = if (initial < 0f) null else initial
        } finally {
            a.recycle()
        }
        trackPaint.strokeWidth = thickness
        progressPaint.strokeWidth = thickness
    }

    private fun interTypeface(style: Int): Typeface {
        val base = try {
            ResourcesCompat.getFont(context, R.font.inter)
        } catch (e: Exception) {
            null
        } ?: Typeface.DEFAULT
        return Typeface.create(base, style)
    }

    private fun updateDescription() {
        contentDescription = listOf(centerText, caption).filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (!isAttachedToWindow) return // onAttachedToWindow animates in.
        val from = if (hasAnimatedIn) drawnProgress else 0f
        hasAnimatedIn = true
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = if (from == 0f) 900L else 400L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawnProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!hasAnimatedIn) animateTo(progress ?: 0f)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        drawnProgress = progress ?: 0f
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val preferred = context.dpInt(220)
        val size = minOf(resolveSize(preferred, widthMeasureSpec), resolveSize(preferred, heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = thickness / 2f + 1f
        arc.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arc, 0f, 360f, false, trackPaint)
        if (progress != null && drawnProgress > 0f) {
            canvas.drawArc(arc, -90f, 360f * drawnProgress, false, progressPaint)
        }

        val cx = width / 2f
        val cy = height / 2f
        // Shrink the time until it fits inside the ring.
        val maxTextWidth = (width - thickness * 2) * 0.78f
        centerPaint.textSize = context.sp(40f)
        while (centerText.isNotEmpty() && centerPaint.measureText(centerText) > maxTextWidth && centerPaint.textSize > context.sp(16f)) {
            centerPaint.textSize -= context.sp(2f)
        }
        val gap = context.dp(6f)
        val captionHeight = if (caption.isEmpty()) 0f else captionPaint.textSize + gap
        val baseline = cy + centerPaint.textSize * 0.35f - captionHeight / 2f
        canvas.drawText(centerText, cx, baseline, centerPaint)
        if (caption.isNotEmpty()) {
            canvas.drawText(caption, cx, baseline + gap + captionPaint.textSize, captionPaint)
        }
    }
}
