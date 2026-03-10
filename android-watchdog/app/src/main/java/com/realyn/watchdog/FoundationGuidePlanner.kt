package com.realyn.watchdog

enum class FoundationGuideTarget {
    AUTOFILL,
    PASSKEY
}

data class FoundationGuidePlan(
    val searchTermRes: Int,
    val stepResIds: List<Int>
)

object FoundationGuidePlanner {

    fun plan(
        target: FoundationGuideTarget,
        oemPack: AutofillPasskeyFoundation.OemPack
    ): FoundationGuidePlan {
        val searchTermRes = when (target) {
            FoundationGuideTarget.AUTOFILL -> when (oemPack) {
                AutofillPasskeyFoundation.OemPack.MIUI -> R.string.autofill_guide_search_autofill_term_miui
                AutofillPasskeyFoundation.OemPack.SAMSUNG,
                AutofillPasskeyFoundation.OemPack.PIXEL,
                AutofillPasskeyFoundation.OemPack.GENERIC -> R.string.autofill_guide_search_autofill_term
            }

            FoundationGuideTarget.PASSKEY -> when (oemPack) {
                AutofillPasskeyFoundation.OemPack.MIUI -> R.string.autofill_guide_search_passkey_term_miui
                AutofillPasskeyFoundation.OemPack.SAMSUNG,
                AutofillPasskeyFoundation.OemPack.PIXEL,
                AutofillPasskeyFoundation.OemPack.GENERIC -> R.string.autofill_guide_search_passkey_term
            }
        }
        val stepResIds = when (target) {
            FoundationGuideTarget.AUTOFILL -> when (oemPack) {
                AutofillPasskeyFoundation.OemPack.MIUI -> listOf(
                    R.string.autofill_guide_autofill_path_miui,
                    R.string.autofill_guide_autofill_path_miui_google_fallback,
                    R.string.autofill_guide_step_select_provider_miui,
                    R.string.autofill_guide_step_return
                )

                AutofillPasskeyFoundation.OemPack.SAMSUNG -> listOf(
                    R.string.autofill_guide_autofill_path_samsung,
                    R.string.autofill_guide_step_select_provider,
                    R.string.autofill_guide_step_return
                )

                AutofillPasskeyFoundation.OemPack.PIXEL -> listOf(
                    R.string.autofill_guide_autofill_path_pixel,
                    R.string.autofill_guide_step_select_provider,
                    R.string.autofill_guide_step_return
                )

                AutofillPasskeyFoundation.OemPack.GENERIC -> listOf(
                    R.string.autofill_guide_autofill_path_generic,
                    R.string.autofill_guide_step_select_provider,
                    R.string.autofill_guide_step_return
                )
            }

            FoundationGuideTarget.PASSKEY -> when (oemPack) {
                AutofillPasskeyFoundation.OemPack.MIUI -> listOf(
                    R.string.autofill_guide_step_miui_passkey_google_hub,
                    R.string.autofill_guide_passkey_path_miui,
                    R.string.autofill_guide_step_confirm_passkey,
                    R.string.autofill_guide_step_return
                )

                AutofillPasskeyFoundation.OemPack.SAMSUNG -> listOf(
                    R.string.autofill_guide_passkey_path_samsung,
                    R.string.autofill_guide_step_confirm_passkey,
                    R.string.autofill_guide_step_return
                )

                AutofillPasskeyFoundation.OemPack.PIXEL -> listOf(
                    R.string.autofill_guide_passkey_path_pixel,
                    R.string.autofill_guide_step_confirm_passkey,
                    R.string.autofill_guide_step_return
                )

                AutofillPasskeyFoundation.OemPack.GENERIC -> listOf(
                    R.string.autofill_guide_passkey_path_generic,
                    R.string.autofill_guide_step_confirm_passkey,
                    R.string.autofill_guide_step_return
                )
            }
        }
        return FoundationGuidePlan(
            searchTermRes = searchTermRes,
            stepResIds = stepResIds
        )
    }
}
