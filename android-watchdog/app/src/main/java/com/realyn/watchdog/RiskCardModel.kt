package com.realyn.watchdog

enum class RiskCardTier {
    STABLE,
    GUARDED,
    ELEVATED,
    HIGH
}

data class RiskCardActionModel(
    val id: String,
    val label: String,
    val route: String
)

data class RiskCardModel(
    val cardId: String,
    val title: String,
    val score: Int,
    val tier: RiskCardTier,
    val summaryLines: List<String>,
    val actions: List<RiskCardActionModel>,
    val updatedAtEpochMs: Long
)
