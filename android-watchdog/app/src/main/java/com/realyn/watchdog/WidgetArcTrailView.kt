package com.realyn.watchdog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import java.util.ArrayDeque
import kotlin.math.max

class WidgetArcTrailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class TrailPoint(
        val x: Float,
        val y: Float,
        val radius: Float,
        val color: Int,
        val bornAtMs: Long,
        val lifeMs: Long
    )

    private val points = ArrayDeque<TrailPoint>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val viewLocation = IntArray(2)
    private var active: Boolean = false

    init {
        visibility = GONE
        alpha = 1f
    }

    fun begin() {
        animate().cancel()
        active = true
        alpha = 1f
        visibility = VISIBLE
        points.clear()
        invalidate()
    }

    fun emitTrailAtScreen(
        screenX: Float,
        screenY: Float,
        progress: Float,
        accentColor: Int
    ) {
        if (!active || width <= 0 || height <= 0) {
            return
        }
        getLocationOnScreen(viewLocation)
        val localX = screenX - viewLocation[0]
        val localY = screenY - viewLocation[1]
        val t = progress.coerceIn(0f, 1f)
        val radius = lerp(dp(2.2f), dp(13.5f), t)
        val lifeMs = lerp(620f, 1080f, t).toLong()
        val color = ColorUtils.blendARGB(accentColor, Color.WHITE, 0.12f * (1f - t))
        points.addLast(
            TrailPoint(
                x = localX,
                y = localY,
                radius = radius,
                color = color,
                bornAtMs = SystemClock.uptimeMillis(),
                lifeMs = lifeMs
            )
        )
        while (points.size > 540) {
            points.removeFirst()
        }
        if (visibility != VISIBLE) {
            visibility = VISIBLE
        }
        invalidate()
    }

    fun endWithFade(durationMs: Long = 900L) {
        if (!active && points.isEmpty()) {
            clear()
            return
        }
        active = false
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                clear()
            }
            .start()
    }

    fun clear() {
        active = false
        points.clear()
        animate().cancel()
        alpha = 1f
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) {
            return
        }
        val now = SystemClock.uptimeMillis()
        val iterator = points.iterator()
        while (iterator.hasNext()) {
            val point = iterator.next()
            val elapsed = now - point.bornAtMs
            if (elapsed >= point.lifeMs) {
                iterator.remove()
                continue
            }
            val remaining = 1f - (elapsed.toFloat() / max(1L, point.lifeMs).toFloat())
            val alphaChannel = (remaining * remaining * 180f).toInt().coerceIn(0, 255)
            paint.color = ColorUtils.setAlphaComponent(point.color, alphaChannel)
            canvas.drawCircle(point.x, point.y, point.radius, paint)
        }
        if (points.isNotEmpty()) {
            postInvalidateOnAnimation()
        } else if (!active) {
            visibility = GONE
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float {
        return start + ((end - start) * amount)
    }
}
