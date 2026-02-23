package com.realyn.watchdog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.hypot

class LionFillImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var fillMode: LionFillMode = LionFillMode.LEFT_TO_RIGHT
        set(value) {
            field = value
            invalidate()
        }

    var fillProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (drawable == null) {
            return
        }
        if (fillProgress <= 0f) {
            return
        }
        if (fillProgress >= 1f) {
            super.onDraw(canvas)
            return
        }

        val checkpoint = canvas.save()
        when (fillMode) {
            LionFillMode.LEFT_TO_RIGHT -> {
                val right = width * fillProgress
                canvas.clipRect(0f, 0f, right, height.toFloat())
            }
            LionFillMode.RADIAL -> {
                clipRadialSector(canvas)
            }
        }
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    private fun clipRadialSector(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = hypot(width.toFloat(), height.toFloat())
        val sweep = 360f * fillProgress
        val arcBounds = RectF(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius
        )
        val path = Path().apply {
            moveTo(cx, cy)
            arcTo(arcBounds, -90f, sweep, true)
            close()
        }
        canvas.clipPath(path)
    }
}
