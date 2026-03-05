package com.realyn.watchdog

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.method.ScrollingMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.CompoundButtonCompat
import com.google.android.material.textfield.TextInputLayout
import com.realyn.watchdog.theme.LionIdentityAccentStyle
import com.realyn.watchdog.theme.LionThemeCatalog
import com.realyn.watchdog.theme.LionThemePalette
import com.realyn.watchdog.theme.LionThemeViewStyler

class LionAlertDialogBuilder(
    private val activity: AppCompatActivity
) : AlertDialog.Builder(activity) {

    override fun show(): AlertDialog {
        val dialog = super.show()
        LionDialogStyler.applyForActivity(activity, dialog)
        return dialog
    }
}

object LionDialogStyler {

    fun applyForActivity(activity: AppCompatActivity, dialog: AlertDialog) {
        val access = PricingPolicy.resolveFeatureAccess(activity)
        val selectedBitmap = LionThemePrefs.resolveSelectedLionBitmap(activity)
        val themeState = LionThemeCatalog.resolveState(
            context = activity,
            paidAccess = access.paidAccess,
            selectedLionBitmap = selectedBitmap
        )
        apply(
            dialog = dialog,
            palette = themeState.palette,
            accentStyle = themeState.accentStyle
        )
    }

    fun apply(
        dialog: AlertDialog,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle = LionIdentityAccentStyle()
    ) {
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(dialog.context, 16f).toFloat()
                setColor(palette.panelAlt)
                setStroke(dp(dialog.context, 1f), palette.stroke)
            }
        )

        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(palette.textPrimary)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(palette.textSecondary)

        styleActionButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), palette)
        styleActionButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), palette)
        styleActionButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), palette)

        val decor = dialog.window?.decorView ?: return
        LionThemeViewStyler.applyMaterialButtonPalette(
            root = decor,
            palette = palette,
            accentStyle = accentStyle
        )
        LionThemeViewStyler.installMaterialButtonTouchFeedback(
            root = decor,
            accentStyle = accentStyle
        )
        styleInputs(decor, palette)
        enforceDialogViewportBounds(dialog)
    }

    private fun styleActionButton(button: Button?, palette: LionThemePalette) {
        button ?: return
        button.setTextColor(palette.accent)
    }

    private fun styleInputs(view: View, palette: LionThemePalette) {
        if (view is TextInputLayout) {
            val hintColors = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf()
                ),
                intArrayOf(
                    palette.textSecondary,
                    palette.textMuted
                )
            )
            view.defaultHintTextColor = hintColors
            view.hintTextColor = hintColors
            view.setBoxStrokeColorStateList(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf()
                    ),
                    intArrayOf(
                        palette.accent,
                        palette.stroke
                    )
                )
            )
        }

        when (view) {
            is EditText -> {
                view.setTextColor(palette.textPrimary)
                view.setHintTextColor(palette.textMuted)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    view.backgroundTintList = ColorStateList.valueOf(palette.stroke)
                }
            }
            is RadioButton -> {
                view.setTextColor(palette.textPrimary)
                CompoundButtonCompat.setButtonTintList(view, ColorStateList.valueOf(palette.accent))
            }
            is TextView -> {
                if (view !is Button) {
                    val textColor = when (view.id) {
                        android.R.id.message,
                        R.id.planReadOnlyHintLabel -> palette.textSecondary
                        else -> palette.textPrimary
                    }
                    view.setTextColor(textColor)
                }
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                styleInputs(view.getChildAt(index), palette)
            }
        }
    }

    private fun enforceDialogViewportBounds(dialog: AlertDialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        decor.post {
            val parentPanel = decor.findViewById<View>(androidx.appcompat.R.id.parentPanel) ?: return@post
            val topPanel = decor.findViewById<View>(androidx.appcompat.R.id.topPanel)
            val buttonPanel = decor.findViewById<View>(androidx.appcompat.R.id.buttonPanel)
            val contentPanel = decor.findViewById<View>(androidx.appcompat.R.id.contentPanel)
            val scrollView = decor.findViewById<View>(androidx.appcompat.R.id.scrollView)

            val safeScreenMargin = dp(dialog.context, 24f)
            val maxDialogHeight = (dialog.context.resources.displayMetrics.heightPixels - (safeScreenMargin * 2))
                .coerceAtLeast(1)

            if (parentPanel.height > maxDialogHeight) {
                parentPanel.layoutParams = parentPanel.layoutParams.apply {
                    height = maxDialogHeight
                }
                parentPanel.requestLayout()
            }

            val reservedPanelsHeight = (topPanel?.height ?: 0) + (buttonPanel?.height ?: 0) + dp(dialog.context, 20f)
            val maxBodyHeight = (maxDialogHeight - reservedPanelsHeight).coerceAtLeast(0)

            val bodyPanel = scrollView ?: contentPanel
            if (bodyPanel != null && maxBodyHeight > 0 && bodyPanel.height > maxBodyHeight) {
                bodyPanel.layoutParams = bodyPanel.layoutParams.apply {
                    height = maxBodyHeight
                }
                bodyPanel.requestLayout()
            }

            dialog.findViewById<TextView>(android.R.id.message)?.let { message ->
                if (maxBodyHeight <= 0) {
                    return@let
                }
                message.maxHeight = maxBodyHeight
                message.isVerticalScrollBarEnabled = true
                message.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                val hasClickableSpans = (message.text as? Spanned)
                    ?.getSpans(0, message.length(), ClickableSpan::class.java)
                    ?.isNotEmpty() == true
                if (hasClickableSpans) {
                    message.linksClickable = true
                    message.movementMethod = LinkMovementMethod.getInstance()
                } else {
                    message.movementMethod = ScrollingMovementMethod.getInstance()
                }
            }
        }
    }

    private fun dp(context: android.content.Context, value: Float): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
