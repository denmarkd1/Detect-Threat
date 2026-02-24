package com.realyn.watchdog

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

class LionHeroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val auraView: View
    private val idleImageView: ImageView
    private val fillImageView: LionFillImageView
    private val eyeGlowView: LionEyeGlowOverlayView
    private var eyeGlowAnimator: ValueAnimator? = null
    private var darkSurfaceTone: Boolean = true
    private var imageOffsetYPx: Float = 0f
    private val darkIdlePreset = IdleTonePreset(
        saturation = 0f,
        contrast = 1.08f,
        lift = 16f,
        baseAlpha = 0.40f,
        dropAlpha = 0.18f,
        minAlpha = 0.18f,
        scanCompleteAlpha = 0.18f
    )
    private val lightIdlePreset = IdleTonePreset(
        saturation = 0f,
        contrast = 1.16f,
        lift = -6f,
        baseAlpha = 0.90f,
        dropAlpha = 0.26f,
        minAlpha = 0.22f,
        scanCompleteAlpha = 0.18f
    )

    init {
        LayoutInflater.from(context).inflate(R.layout.view_lion_hero, this, true)
        auraView = findViewById(R.id.lionAuraView)
        idleImageView = findViewById(R.id.lionIdleImage)
        fillImageView = findViewById(R.id.lionFillImage)
        eyeGlowView = findViewById(R.id.lionEyeGlowOverlay)
        setLionDrawable()
        setIdleState()
    }

    fun setFillMode(mode: LionFillMode) {
        fillImageView.fillMode = mode
    }

    fun setLionDrawable(@DrawableRes drawableRes: Int = R.drawable.lion_icon_non_binary) {
        idleImageView.setImageResource(drawableRes)
        fillImageView.setImageResource(drawableRes)
    }

    fun setImageOffsetY(offsetPx: Float) {
        if (abs(imageOffsetYPx - offsetPx) < 0.5f) {
            return
        }
        imageOffsetYPx = offsetPx
        idleImageView.translationY = offsetPx
        fillImageView.translationY = offsetPx
        eyeGlowView.translationY = offsetPx * 0.92f
        auraView.translationY = offsetPx * 0.88f
    }

    fun setLionBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            setLionDrawable()
            return
        }
        idleImageView.setImageBitmap(bitmap)
        fillImageView.setImageBitmap(bitmap)
    }

    fun setSurfaceTone(isDarkTone: Boolean) {
        if (darkSurfaceTone == isDarkTone) {
            return
        }
        darkSurfaceTone = isDarkTone
        if (fillImageView.fillProgress < 0.999f) {
            applyIdleMatrix()
            idleImageView.alpha = resolveIdleAlpha(fillImageView.fillProgress)
        }
    }

    fun setIdleState() {
        stopEyeGlowPulse()
        applyIdleMatrix()
        idleImageView.alpha = resolveIdleAlpha(0f)
        fillImageView.fillProgress = 0f
        eyeGlowView.setIntensity(0f)
    }

    fun setScanProgress(progress: Float) {
        stopEyeGlowPulse()
        applyIdleMatrix()
        val normalized = progress.coerceIn(0f, 1f)
        idleImageView.alpha = resolveIdleAlpha(normalized)
        fillImageView.fillProgress = normalized
        eyeGlowView.setIntensity(0f)
    }

    fun setScanComplete() {
        fillImageView.fillProgress = 1f
        idleImageView.colorFilter = null
        idleImageView.alpha = activeIdlePreset().scanCompleteAlpha
        startEyeGlowPulse()
    }

    fun setAccentColor(@ColorInt color: Int) {
        val auraColor = ColorUtils.setAlphaComponent(color, 96)
        val eyeColor = ColorUtils.blendARGB(color, 0xFFFFCF67.toInt(), 0.72f)
        auraView.background?.mutate()?.setTint(auraColor)
        eyeGlowView.setGlowColor(eyeColor)
    }

    override fun onDetachedFromWindow() {
        stopEyeGlowPulse()
        super.onDetachedFromWindow()
    }

    private fun applyIdleMatrix() {
        val preset = activeIdlePreset()
        val saturation = ColorMatrix().apply { setSaturation(preset.saturation) }
        val contrastAndLift = ColorMatrix(
            floatArrayOf(
                preset.contrast, 0f, 0f, 0f, preset.lift,
                0f, preset.contrast, 0f, 0f, preset.lift,
                0f, 0f, preset.contrast, 0f, preset.lift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        saturation.postConcat(contrastAndLift)
        idleImageView.colorFilter = ColorMatrixColorFilter(saturation)
    }

    private fun resolveIdleAlpha(progress: Float): Float {
        val preset = activeIdlePreset()
        val normalized = progress.coerceIn(0f, 1f)
        return (preset.baseAlpha - (normalized * preset.dropAlpha))
            .coerceIn(preset.minAlpha, 0.95f)
    }

    private fun activeIdlePreset(): IdleTonePreset {
        return if (darkSurfaceTone) darkIdlePreset else lightIdlePreset
    }

    private fun startEyeGlowPulse() {
        if (eyeGlowAnimator?.isRunning == true) {
            return
        }
        eyeGlowAnimator = ValueAnimator.ofFloat(0.20f, 0.72f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                eyeGlowView.setIntensity(animator.animatedValue as Float)
            }
            start()
        }
    }

    private fun stopEyeGlowPulse() {
        eyeGlowAnimator?.cancel()
        eyeGlowAnimator = null
        eyeGlowView.setIntensity(0f)
    }

    private data class IdleTonePreset(
        val saturation: Float,
        val contrast: Float,
        val lift: Float,
        val baseAlpha: Float,
        val dropAlpha: Float,
        val minAlpha: Float,
        val scanCompleteAlpha: Float
    )
}
