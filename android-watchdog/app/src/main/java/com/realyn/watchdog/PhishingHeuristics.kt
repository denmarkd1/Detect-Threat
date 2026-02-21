package com.realyn.watchdog

import java.util.Locale

data class PhishingHeuristicResult(
    val riskScore: Int,
    val reasons: List<String>,
    val extractedUrls: List<String>
)

object PhishingHeuristics {

    private val suspiciousKeywordWeights = linkedMapOf(
        "urgent" to 10,
        "verify" to 14,
        "suspended" to 16,
        "gift" to 10,
        "airdrop" to 14,
        "wallet" to 12,
        "crypto" to 12,
        "password reset" to 12,
        "2fa code" to 16,
        "login now" to 12,
        "click" to 8
    )

    private val urlPattern = Regex(
        pattern = "(?i)(https?://[^\\s]+|(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/[^\\s]*)?)"
    )

    fun evaluate(input: String): PhishingHeuristicResult {
        val normalizedInput = input.trim()
        if (normalizedInput.isBlank()) {
            return PhishingHeuristicResult(0, emptyList(), emptyList())
        }

        var score = 0
        val reasons = linkedSetOf<String>()
        val urls = extractUrls(normalizedInput)

        urls.forEach { url ->
            val finding = ScamShield.analyzeUrl(url, "manual_intake")
            if (finding != null) {
                score += finding.score
                reasons += finding.reasonCodes
            }
        }

        val lower = normalizedInput.lowercase(Locale.US)
        suspiciousKeywordWeights.forEach { (keyword, weight) ->
            if (lower.contains(keyword)) {
                score += weight
                reasons += "keyword:$keyword"
            }
        }

        if (lower.contains("password=") || lower.contains("token=") || lower.contains("otp=") || lower.contains("code=")) {
            score += 20
            reasons += "sensitive_query_payload"
        }

        if (lower.count { it == '@' } > 0 && lower.contains("http")) {
            score += 15
            reasons += "embedded_at_symbol"
        }

        if (urls.isEmpty()) {
            score += 8
            reasons += "no_explicit_url_detected"
        }

        return PhishingHeuristicResult(
            riskScore = score.coerceIn(0, 100),
            reasons = reasons.toList(),
            extractedUrls = urls
        )
    }

    private fun extractUrls(input: String): List<String> {
        return urlPattern.findAll(input)
            .map { it.value.trim().trimEnd('.', ',', ';', ')', ']', '}') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
            .toList()
    }
}
