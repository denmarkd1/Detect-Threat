package com.realyn.watchdog

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils

class HomeTutorialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onHighlightTapped: (() -> Unit)? = null

    private val scrimPath = Path()
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(0xFF000000.toInt(), 176)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = 0xFFD6A545.toInt()
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = ColorUtils.setAlphaComponent(0xFFD6A545.toInt(), 130)
    }

    private var highlightRect: RectF? = null
    private var highlightCornerRadius: Float = dp(14f)
    private var pulseFraction: Float = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulseFraction = it.animatedValue as Float
            if (visibility == VISIBLE) {
                postInvalidateOnAnimation()
            }
        }
    }

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    fun setHighlight(rect: RectF, cornerRadius: Float = dp(14f)) {
        highlightRect = RectF(rect)
        highlightCornerRadius = cornerRadius
        visibility = VISIBLE
        alpha = 1f
        if (!pulseAnimator.isStarted) {
            pulseAnimator.start()
        }
        invalidate()
    }

    fun clearHighlight() {
        highlightRect = null
        if (pulseAnimator.isStarted) {
            pulseAnimator.cancel()
        }
        pulseFraction = 0f
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = highlightRect ?: return
        if (width <= 0 || height <= 0) {
            return
        }

        scrimPath.reset()
        scrimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        scrimPath.addRoundRect(rect, highlightCornerRadius, highlightCornerRadius, Path.Direction.CCW)
        scrimPath.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(scrimPath, scrimPaint)

        canvas.drawRoundRect(rect, highlightCornerRadius, highlightCornerRadius, borderPaint)

        val pulseInset = dp(3f + (pulseFraction * 8f))
        val alphaFactor = (1f - pulseFraction).coerceIn(0f, 1f)
        pulsePaint.color = ColorUtils.setAlphaComponent(0xFFD6A545.toInt(), (30 + (alphaFactor * 100f)).toInt())
        canvas.drawRoundRect(
            RectF(
                rect.left - pulseInset,
                rect.top - pulseInset,
                rect.right + pulseInset,
                rect.bottom + pulseInset
            ),
            highlightCornerRadius + pulseInset,
            highlightCornerRadius + pulseInset,
            pulsePaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = highlightRect
        if (rect != null && rect.contains(event.x, event.y)) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                onHighlightTapped?.invoke()
            }
            // Let taps inside the highlight pass through to the target control.
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
