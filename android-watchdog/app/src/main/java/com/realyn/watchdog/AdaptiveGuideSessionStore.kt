package com.realyn.watchdog

import android.content.Context
import java.util.Locale

private const val ADAPTIVE_GUIDE_SESSION_STALE_AFTER_MS = 30L * 60L * 1000L

enum class AdaptiveGuideSessionSource {
    MANUAL,
    OCR
}

data class AdaptiveGuideSessionRecord(
    val flowId: String,
    val currentStateId: String,
    val lastConfirmedStateId: String,
    val lastAppliedAnchorId: String,
    val summaryLabel: String,
    val confidence: AdaptiveGuideAnalysisConfidence?,
    val source: AdaptiveGuideSessionSource?,
    val updatedAtEpochMs: Long
)

data class AdaptiveGuideSessionSnapshot(
    val record: AdaptiveGuideSessionRecord,
    val currentState: AdaptiveGuideResolvedState,
    val lastConfirmedState: AdaptiveGuideResolvedState?,
    val lastAppliedAnchor: AdaptiveGuideAnchorOption?
)

object AdaptiveGuideSessionStore {
    private const val KEY_FLOW_ID = "adaptive_guide_session_flow_id"
    private const val KEY_CURRENT_STATE_ID = "adaptive_guide_session_current_state_id"
    private const val KEY_LAST_CONFIRMED_STATE_ID = "adaptive_guide_session_last_confirmed_state_id"
    private const val KEY_LAST_APPLIED_ANCHOR_ID = "adaptive_guide_session_last_applied_anchor_id"
    private const val KEY_SUMMARY_LABEL = "adaptive_guide_session_summary_label"
    private const val KEY_CONFIDENCE = "adaptive_guide_session_confidence"
    private const val KEY_SOURCE = "adaptive_guide_session_source"
    private const val KEY_UPDATED_AT = "adaptive_guide_session_updated_at_epoch_ms"

    internal val storageKeys: Set<String> = setOf(
        KEY_FLOW_ID,
        KEY_CURRENT_STATE_ID,
        KEY_LAST_CONFIRMED_STATE_ID,
        KEY_LAST_APPLIED_ANCHOR_ID,
        KEY_SUMMARY_LABEL,
        KEY_CONFIDENCE,
        KEY_SOURCE,
        KEY_UPDATED_AT
    )

    fun read(
        context: Context,
        pack: AdaptiveGuideRulePack,
        flowId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): AdaptiveGuideSessionSnapshot? {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val record = decodeRecord(prefs.all) ?: return null
        val snapshot = resolveRecord(
            pack = pack,
            expectedFlowId = flowId,
            record = record,
            nowEpochMs = nowEpochMs
        ) ?: run {
            clear(context)
            return null
        }
        return snapshot
    }

    fun start(
        context: Context,
        initialState: AdaptiveGuideResolvedState,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        store(
            context = context,
            record = AdaptiveGuideSessionRecord(
                flowId = initialState.flowId,
                currentStateId = initialState.stateId,
                lastConfirmedStateId = initialState.stateId,
                lastAppliedAnchorId = "",
                summaryLabel = "",
                confidence = null,
                source = null,
                updatedAtEpochMs = nowEpochMs
            )
        )
    }

    fun recordState(
        context: Context,
        currentState: AdaptiveGuideResolvedState,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        store(
            context = context,
            record = AdaptiveGuideSessionRecord(
                flowId = currentState.flowId,
                currentStateId = currentState.stateId,
                lastConfirmedStateId = currentState.stateId,
                lastAppliedAnchorId = "",
                summaryLabel = "",
                confidence = null,
                source = null,
                updatedAtEpochMs = nowEpochMs
            )
        )
    }

    fun recordManualTransition(
        context: Context,
        confirmedState: AdaptiveGuideResolvedState,
        anchor: AdaptiveGuideAnchorOption,
        nextState: AdaptiveGuideResolvedState,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        store(
            context = context,
            record = AdaptiveGuideSessionRecord(
                flowId = nextState.flowId,
                currentStateId = nextState.stateId,
                lastConfirmedStateId = confirmedState.stateId,
                lastAppliedAnchorId = anchor.id,
                summaryLabel = anchor.label,
                confidence = AdaptiveGuideAnalysisConfidence.EXACT,
                source = AdaptiveGuideSessionSource.MANUAL,
                updatedAtEpochMs = nowEpochMs
            )
        )
    }

    fun recordAnalysisTransition(
        context: Context,
        confirmedState: AdaptiveGuideResolvedState,
        candidate: AdaptiveGuideAnalysisCandidate,
        nextState: AdaptiveGuideResolvedState,
        confidence: AdaptiveGuideAnalysisConfidence,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        store(
            context = context,
            record = AdaptiveGuideSessionRecord(
                flowId = nextState.flowId,
                currentStateId = nextState.stateId,
                lastConfirmedStateId = confirmedState.stateId,
                lastAppliedAnchorId = candidate.anchorId,
                summaryLabel = candidate.anchorLabel,
                confidence = confidence,
                source = AdaptiveGuideSessionSource.OCR,
                updatedAtEpochMs = nowEpochMs
            )
        )
    }

    fun store(context: Context, record: AdaptiveGuideSessionRecord) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val encoded = encodeRecord(record)
        prefs.edit().apply {
            storageKeys.forEach(::remove)
            encoded.forEach { (key, value) ->
                when (value) {
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                }
            }
        }.apply()
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().apply {
            storageKeys.forEach(::remove)
        }.apply()
    }

    internal fun clearValues(target: MutableMap<String, Any?>) {
        storageKeys.forEach(target::remove)
    }

    internal fun encodeRecord(record: AdaptiveGuideSessionRecord): Map<String, Any> {
        val encoded = linkedMapOf<String, Any>(
            KEY_FLOW_ID to record.flowId.trim(),
            KEY_CURRENT_STATE_ID to record.currentStateId.trim(),
            KEY_LAST_CONFIRMED_STATE_ID to record.lastConfirmedStateId.trim().ifBlank {
                record.currentStateId.trim()
            },
            KEY_LAST_APPLIED_ANCHOR_ID to record.lastAppliedAnchorId.trim(),
            KEY_SUMMARY_LABEL to record.summaryLabel.trim(),
            KEY_UPDATED_AT to record.updatedAtEpochMs.coerceAtLeast(0L)
        )
        record.confidence?.let { confidence ->
            encoded[KEY_CONFIDENCE] = confidence.name.lowercase(Locale.US)
        }
        record.source?.let { source ->
            encoded[KEY_SOURCE] = source.name.lowercase(Locale.US)
        }
        return encoded
    }

    internal fun decodeRecord(values: Map<String, *>): AdaptiveGuideSessionRecord? {
        val flowId = values.readString(KEY_FLOW_ID)
        val currentStateId = values.readString(KEY_CURRENT_STATE_ID)
        if (flowId.isBlank() || currentStateId.isBlank()) {
            return null
        }
        return AdaptiveGuideSessionRecord(
            flowId = flowId,
            currentStateId = currentStateId,
            lastConfirmedStateId = values.readString(KEY_LAST_CONFIRMED_STATE_ID)
                .ifBlank { currentStateId },
            lastAppliedAnchorId = values.readString(KEY_LAST_APPLIED_ANCHOR_ID),
            summaryLabel = values.readString(KEY_SUMMARY_LABEL),
            confidence = parseConfidence(values.readString(KEY_CONFIDENCE)),
            source = parseSource(values.readString(KEY_SOURCE)),
            updatedAtEpochMs = values.readLong(KEY_UPDATED_AT)
        )
    }

    internal fun resolveRecord(
        pack: AdaptiveGuideRulePack,
        expectedFlowId: String,
        record: AdaptiveGuideSessionRecord,
        nowEpochMs: Long = System.currentTimeMillis()
    ): AdaptiveGuideSessionSnapshot? {
        if (expectedFlowId.isBlank() || record.flowId != expectedFlowId) {
            return null
        }
        if (record.updatedAtEpochMs <= 0L || nowEpochMs - record.updatedAtEpochMs > ADAPTIVE_GUIDE_SESSION_STALE_AFTER_MS) {
            return null
        }
        val currentState = AdaptiveGuideEngine.resolve(pack, record.flowId, record.currentStateId) ?: return null
        val lastConfirmedState = AdaptiveGuideEngine.resolve(
            pack,
            record.flowId,
            record.lastConfirmedStateId.ifBlank { record.currentStateId }
        ) ?: return null
        val lastAppliedAnchor = record.lastAppliedAnchorId
            .takeIf { it.isNotBlank() }
            ?.let { anchorId ->
                lastConfirmedState.anchors.firstOrNull { it.id == anchorId }
            }
        if (record.lastAppliedAnchorId.isNotBlank() && lastAppliedAnchor == null) {
            return null
        }
        if (
            lastAppliedAnchor != null &&
            lastConfirmedState.stateId != currentState.stateId &&
            lastAppliedAnchor.nextStateId != currentState.stateId
        ) {
            return null
        }
        if (record.source == AdaptiveGuideSessionSource.OCR && record.summaryLabel.isBlank()) {
            return null
        }
        return AdaptiveGuideSessionSnapshot(
            record = record,
            currentState = currentState,
            lastConfirmedState = lastConfirmedState,
            lastAppliedAnchor = lastAppliedAnchor
        )
    }

    private fun parseConfidence(raw: String): AdaptiveGuideAnalysisConfidence? {
        return when (raw.trim().lowercase(Locale.US)) {
            "exact" -> AdaptiveGuideAnalysisConfidence.EXACT
            "ambiguous" -> AdaptiveGuideAnalysisConfidence.AMBIGUOUS
            "none" -> AdaptiveGuideAnalysisConfidence.NONE
            else -> null
        }
    }

    private fun parseSource(raw: String): AdaptiveGuideSessionSource? {
        return when (raw.trim().lowercase(Locale.US)) {
            "manual" -> AdaptiveGuideSessionSource.MANUAL
            "ocr" -> AdaptiveGuideSessionSource.OCR
            else -> null
        }
    }

    private fun Map<String, *>.readString(key: String): String {
        return when (val value = this[key]) {
            is String -> value.trim()
            else -> ""
        }
    }

    private fun Map<String, *>.readLong(key: String): Long {
        return when (val value = this[key]) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
