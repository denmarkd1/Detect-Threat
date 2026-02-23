package com.realyn.watchdog

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class IntroCelebrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Spark(
        val originXFactor: Float,
        val originYFactor: Float,
        val angleRad: Float,
        val distancePx: Float,
        val radiusPx: Float,
        val startDelayMs: Long,
        val lifeMs: Long,
        @ColorInt val color: Int
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparks = mutableListOf<Spark>()
    private var timelineAnimator: ValueAnimator? = null
    private var progress: Float = 0f
    private var totalDurationMs: Long = 0L
    private val random = Random(System.currentTimeMillis())

    fun startCelebration(@ColorInt accentColor: Int, durationMs: Long = 2500L) {
        if (width == 0 || height == 0) {
            post { startCelebration(accentColor, durationMs) }
            return
        }
        stopCelebration()
        totalDurationMs = durationMs
        buildSparks(accentColor)
        progress = 0f
        timelineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { animator ->
                progress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopCelebration() {
        timelineAnimator?.cancel()
        timelineAnimator = null
        progress = 0f
        sparks.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopCelebration()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f || sparks.isEmpty() || totalDurationMs <= 0L) {
            return
        }
        val elapsedMs = progress * totalDurationMs
        sparks.forEach { spark ->
            val local = (elapsedMs - spark.startDelayMs) / spark.lifeMs
            if (local <= 0f || local >= 1f) {
                return@forEach
            }
            val eased = 1f - (1f - local) * (1f - local)
            val x = width * spark.originXFactor + cos(spark.angleRad) * spark.distancePx * eased
            val gravity = dpToPx(22f) * local * local
            val y = height * spark.originYFactor + sin(spark.angleRad) * spark.distancePx * eased + gravity
            val alpha = (255f * (1f - local) * (1f - local)).toInt().coerceIn(0, 255)
            val radius = spark.radiusPx * (1f - (0.35f * local))
            paint.color = ColorUtils.setAlphaComponent(spark.color, alpha)
            canvas.drawCircle(x, y, radius, paint)
        }
    }

    private fun buildSparks(@ColorInt accentColor: Int) {
        sparks.clear()
        val palette = intArrayOf(
            accentColor,
            ColorUtils.blendARGB(accentColor, 0xFFFFD779.toInt(), 0.52f),
            ColorUtils.blendARGB(accentColor, 0xFFFFA057.toInt(), 0.38f),
            0xFFF7EBD5.toInt()
        )
        val burstOrigins = listOf(
            0.50f to 0.44f,
            0.34f to 0.38f,
            0.66f to 0.38f,
            0.50f to 0.28f,
            0.26f to 0.52f,
            0.74f to 0.52f
        )
        burstOrigins.forEachIndexed { index, origin ->
            val sparkCount = if (index == 0) 26 else 18
            repeat(sparkCount) {
                sparks += Spark(
                    originXFactor = origin.first,
                    originYFactor = origin.second,
                    angleRad = random.nextFloat() * (Math.PI * 2.0).toFloat(),
                    distancePx = dpToPx(randomRange(50f, 146f)),
                    radiusPx = dpToPx(randomRange(1.8f, 3.8f)),
                    startDelayMs = random.nextLong(90L, 1250L),
                    lifeMs = random.nextLong(560L, 1220L),
                    color = palette[random.nextInt(palette.size)]
                )
            }
        }
    }

    private fun randomRange(min: Float, max: Float): Float {
        return min + random.nextFloat() * (max - min)
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
