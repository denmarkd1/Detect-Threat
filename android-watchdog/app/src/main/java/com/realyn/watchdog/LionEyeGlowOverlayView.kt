package com.realyn.watchdog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.min

class LionEyeGlowOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @ColorInt
    private var glowColor: Int = Color.WHITE
    private var intensity: Float = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePath = Path()
    private val coreOval = RectF()

    fun setGlowColor(@ColorInt color: Int) {
        glowColor = color
        invalidate()
    }

    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (intensity <= 0f) {
            return
        }

        val eyeY = height * 0.445f
        drawEye(canvas, width * 0.430f, eyeY)
        drawEye(canvas, width * 0.570f, eyeY)
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float) {
        val eyeWidth = min(width, height) * (0.074f + (0.010f * intensity))
        val eyeHeight = eyeWidth * (0.34f + (0.03f * intensity))
        val left = cx - eyeWidth / 2f
        val right = cx + eyeWidth / 2f
        val top = cy - eyeHeight * 0.95f
        val bottom = cy + eyeHeight * 0.74f
        val upperLift = eyeHeight * 1.08f
        val lowerDrop = eyeHeight * 0.78f

        eyePath.reset()
        eyePath.moveTo(left, cy)
        eyePath.cubicTo(
            cx - eyeWidth * 0.26f,
            cy - upperLift,
            cx + eyeWidth * 0.26f,
            cy - upperLift,
            right,
            cy
        )
        eyePath.cubicTo(
            cx + eyeWidth * 0.21f,
            cy + lowerDrop,
            cx - eyeWidth * 0.21f,
            cy + lowerDrop,
            left,
            cy
        )
        eyePath.close()

        val shellAlphaTop = (170 + (58 * intensity)).toInt().coerceIn(0, 255)
        val shellAlphaBottom = (42 + (40 * intensity)).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cx,
            top,
            cx,
            bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(glowColor, shellAlphaTop),
                ColorUtils.setAlphaComponent(glowColor, shellAlphaBottom),
                ColorUtils.setAlphaComponent(glowColor, 0)
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(eyePath, paint)

        val coreAlpha = (128 + (82 * intensity)).toInt().coerceIn(0, 255)
        coreOval.set(
            cx - eyeWidth * 0.085f,
            cy - eyeHeight * 0.52f,
            cx + eyeWidth * 0.085f,
            cy + eyeHeight * 0.52f
        )
        paint.shader = null
        paint.color = ColorUtils.setAlphaComponent(glowColor, coreAlpha)
        canvas.drawOval(coreOval, paint)

        paint.style = Paint.Style.FILL
        paint.shader = null
    }
}
