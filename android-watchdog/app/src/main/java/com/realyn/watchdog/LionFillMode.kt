package com.realyn.watchdog

import androidx.annotation.StringRes

enum class LionFillMode(
    val raw: String,
    @StringRes val labelRes: Int
) {
    LEFT_TO_RIGHT("left_to_right", R.string.lion_mode_left_to_right),
    RADIAL("radial", R.string.lion_mode_radial);

    fun next(): LionFillMode {
        return if (this == LEFT_TO_RIGHT) RADIAL else LEFT_TO_RIGHT
    }

    companion object {
        fun fromRaw(raw: String?): LionFillMode {
            val normalized = raw?.trim().orEmpty()
            return entries.firstOrNull { it.raw == normalized } ?: LEFT_TO_RIGHT
        }
    }
}
