package com.realyn.watchdog.theme

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import kotlin.math.max
import kotlin.math.roundToInt

object LionThemeViewStyler {

    private enum class DepthLevel {
        SUBTLE,
        MEDIUM,
        STRONG
    }

    private data class DepthRecipe(
        val topBlendBase: Float,
        val topBlendLift: Float,
        val topAlpha: Int,
        val bottomBlendBase: Float,
        val bottomBlendLift: Float,
        val bottomAlpha: Int,
        val strokeAlpha: Int,
        val glossAlpha: Int,
        val shadowAlpha: Int,
        val innerStrokeAlpha: Int,
        val textBlendBase: Float,
        val textBlendLift: Float,
        val elevationBaseDp: Float,
        val elevationLiftDp: Float,
        val restingTranslationZDp: Float,
        val pressScale: Float,
        val pressShiftDp: Float,
        val pressZDp: Float,
        val releaseDurationMs: Long
    )

    fun applyMaterialButtonPalette(
        root: View,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle = LionIdentityAccentStyle()
    ) {
        when (root) {
            is MaterialButton -> styleMaterialButton(root, palette, accentStyle)
            is ViewGroup -> {
                for (index in 0 until root.childCount) {
                    applyMaterialButtonPalette(root.getChildAt(index), palette, accentStyle)
                }
            }
        }
    }

    private fun styleMaterialButton(
        button: MaterialButton,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle
    ) {
        val density = button.resources.displayMetrics.density
        val defaultCornerPx = max((12f * density).roundToInt(), 1)
        val baseCornerPx = if (button.cornerRadius > 0) {
            button.cornerRadius
        } else {
            defaultCornerPx
        }
        val cornerPx = max((baseCornerPx * accentStyle.cornerScale).roundToInt(), 1)
        val cornerRadiusPx = cornerPx.toFloat()
        val strokeWidthPx = max((1f * density).roundToInt(), 1)
        val accentLift = (
            accentStyle.navButtonFillLift.coerceIn(0f, 0.24f) * 0.9f +
                accentStyle.buttonStrokeAccentBlend.coerceIn(0f, 0.58f) * 0.20f
            ).coerceIn(0f, 0.24f)
        val depthRecipe = resolveDepthRecipe(resolveDepthLevel(accentStyle))

        val topColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(
                palette.panelAlt,
                Color.WHITE,
                (depthRecipe.topBlendBase + (accentLift * depthRecipe.topBlendLift))
                    .coerceIn(0.07f, 0.32f)
            ),
            depthRecipe.topAlpha
        )
        val bottomColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(
                palette.panelAlt,
                palette.backgroundEnd,
                (depthRecipe.bottomBlendBase + (accentLift * depthRecipe.bottomBlendLift))
                    .coerceIn(0.18f, 0.46f)
            ),
            depthRecipe.bottomAlpha
        )
        val strokeColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(
                palette.stroke,
                palette.accent,
                accentStyle.buttonStrokeAccentBlend.coerceIn(0f, 1f)
            ),
            depthRecipe.strokeAlpha
        )

        button.backgroundTintList = null
        button.background = createDepthSurfaceDrawable(
            topColor = topColor,
            bottomColor = bottomColor,
            strokeColor = strokeColor,
            cornerRadiusPx = cornerRadiusPx,
            strokeWidthPx = strokeWidthPx,
            glossAlpha = depthRecipe.glossAlpha,
            shadowAlpha = depthRecipe.shadowAlpha,
            innerStrokeAlpha = depthRecipe.innerStrokeAlpha
        )
        val selectableBackground = TypedValue().also {
            button.context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true
            )
        }
        button.foreground = if (selectableBackground.resourceId != 0) {
            ContextCompat.getDrawable(button.context, selectableBackground.resourceId)
        } else {
            null
        }

        button.cornerRadius = cornerPx
        button.strokeWidth = max((accentStyle.strokeWidthDp * density).roundToInt(), 1)
        val textColor = ColorUtils.blendARGB(
            palette.accent,
            palette.textPrimary,
            (depthRecipe.textBlendBase + (accentLift * depthRecipe.textBlendLift)).coerceIn(0.04f, 0.30f)
        )
        button.setTextColor(textColor)
        button.iconTint = ColorStateList.valueOf(textColor)
        button.elevation = (depthRecipe.elevationBaseDp + (accentLift * depthRecipe.elevationLiftDp)) * density
        button.translationZ = max(depthRecipe.restingTranslationZDp * density, 1f)
    }

    fun installMaterialButtonTouchFeedback(
        root: View,
        accentStyle: LionIdentityAccentStyle = LionIdentityAccentStyle()
    ) {
        val depthRecipe = resolveDepthRecipe(resolveDepthLevel(accentStyle))
        when (root) {
            is MaterialButton -> installTouchFeedback(root, depthRecipe)
            is ViewGroup -> {
                for (index in 0 until root.childCount) {
                    installMaterialButtonTouchFeedback(root.getChildAt(index), accentStyle)
                }
            }
        }
    }

    private fun installTouchFeedback(button: MaterialButton, depthRecipe: DepthRecipe) {
        button.setOnTouchListener { view, event ->
            if (!view.isEnabled) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> applyTouchDown(view, event.x, event.y, depthRecipe)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> clearTouchFeedback(view, depthRecipe)
            }
            false
        }
    }

    private fun applyTouchDown(
        view: View,
        touchX: Float,
        touchY: Float,
        depthRecipe: DepthRecipe
    ) {
        if (view.width <= 0 || view.height <= 0) {
            return
        }
        val normalizedX = ((touchX / view.width) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        val normalizedY = ((touchY / view.height) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        val density = view.resources.displayMetrics.density
        val shift = depthRecipe.pressShiftDp * density
        view.animate().cancel()
        view.scaleX = depthRecipe.pressScale
        view.scaleY = depthRecipe.pressScale
        view.translationX = normalizedX * shift
        view.translationY = normalizedY * shift
        view.translationZ = max(depthRecipe.pressZDp * density, 2f)
    }

    private fun clearTouchFeedback(view: View, depthRecipe: DepthRecipe) {
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(depthRecipe.releaseDurationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.translationZ = 0f
            }
            .start()
    }

    private fun resolveDepthLevel(accentStyle: LionIdentityAccentStyle): DepthLevel {
        val strokeDelta = (accentStyle.strokeWidthDp - 1f).coerceIn(0f, 0.6f)
        val score = (
            accentStyle.buttonStrokeAccentBlend.coerceIn(0f, 0.60f) * 0.58f +
                accentStyle.navButtonFillLift.coerceIn(0f, 0.18f) * 1.35f +
                strokeDelta * 0.28f
            ).coerceIn(0f, 1f)
        return when {
            score < 0.18f -> DepthLevel.SUBTLE
            score < 0.30f -> DepthLevel.MEDIUM
            else -> DepthLevel.STRONG
        }
    }

    private fun resolveDepthRecipe(level: DepthLevel): DepthRecipe {
        return when (level) {
            DepthLevel.SUBTLE -> DepthRecipe(
                topBlendBase = 0.08f,
                topBlendLift = 0.07f,
                topAlpha = 228,
                bottomBlendBase = 0.20f,
                bottomBlendLift = 0.46f,
                bottomAlpha = 212,
                strokeAlpha = 198,
                glossAlpha = 44,
                shadowAlpha = 68,
                innerStrokeAlpha = 28,
                textBlendBase = 0.05f,
                textBlendLift = 0.25f,
                elevationBaseDp = 3.6f,
                elevationLiftDp = 4.6f,
                restingTranslationZDp = 0.8f,
                pressScale = 0.992f,
                pressShiftDp = 0.95f,
                pressZDp = 2.2f,
                releaseDurationMs = 160L
            )

            DepthLevel.MEDIUM -> DepthRecipe(
                topBlendBase = 0.11f,
                topBlendLift = 0.12f,
                topAlpha = 234,
                bottomBlendBase = 0.27f,
                bottomBlendLift = 0.68f,
                bottomAlpha = 220,
                strokeAlpha = 214,
                glossAlpha = 56,
                shadowAlpha = 84,
                innerStrokeAlpha = 36,
                textBlendBase = 0.08f,
                textBlendLift = 0.46f,
                elevationBaseDp = 4.2f,
                elevationLiftDp = 7f,
                restingTranslationZDp = 1.0f,
                pressScale = 0.987f,
                pressShiftDp = 1.35f,
                pressZDp = 3f,
                releaseDurationMs = 180L
            )

            DepthLevel.STRONG -> DepthRecipe(
                topBlendBase = 0.14f,
                topBlendLift = 0.14f,
                topAlpha = 242,
                bottomBlendBase = 0.31f,
                bottomBlendLift = 0.78f,
                bottomAlpha = 228,
                strokeAlpha = 226,
                glossAlpha = 66,
                shadowAlpha = 102,
                innerStrokeAlpha = 42,
                textBlendBase = 0.10f,
                textBlendLift = 0.56f,
                elevationBaseDp = 5f,
                elevationLiftDp = 8.8f,
                restingTranslationZDp = 1.3f,
                pressScale = 0.983f,
                pressShiftDp = 1.85f,
                pressZDp = 4.1f,
                releaseDurationMs = 200L
            )
        }
    }

    private fun createDepthSurfaceDrawable(
        topColor: Int,
        bottomColor: Int,
        strokeColor: Int,
        cornerRadiusPx: Float,
        strokeWidthPx: Int,
        glossAlpha: Int,
        shadowAlpha: Int,
        innerStrokeAlpha: Int
    ): LayerDrawable {
        val resolvedCornerRadiusPx = cornerRadiusPx.coerceAtLeast(0f)
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resolvedCornerRadiusPx
        }
        val shadow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.BLACK, shadowAlpha.coerceIn(0, 255))
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resolvedCornerRadiusPx
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, glossAlpha.coerceIn(0, 255)),
                Color.TRANSPARENT
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resolvedCornerRadiusPx
        }
        val outerRim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resolvedCornerRadiusPx
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidthPx, strokeColor)
        }
        val innerRim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (resolvedCornerRadiusPx - strokeWidthPx.toFloat()).coerceAtLeast(0f)
            setColor(Color.TRANSPARENT)
            setStroke(
                strokeWidthPx,
                ColorUtils.setAlphaComponent(Color.WHITE, innerStrokeAlpha.coerceIn(0, 255))
            )
        }
        return LayerDrawable(arrayOf(base, shadow, gloss, outerRim, innerRim)).apply {
            setLayerInset(4, strokeWidthPx, strokeWidthPx, strokeWidthPx, strokeWidthPx)
        }
    }
}
