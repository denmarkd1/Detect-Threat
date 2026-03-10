package com.realyn.watchdog

import android.content.Context
import org.json.JSONObject
import java.io.File

private const val ADAPTIVE_GUIDE_RULES_FILE = "android_guide_rules.json"

data class AdaptiveGuideAnchorOption(
    val id: String,
    val label: String,
    val nextStateId: String,
    val matchAny: List<String>
)

data class AdaptiveGuideState(
    val id: String,
    val stepNumber: Int,
    val currentTarget: String,
    val assistantHint: String,
    val complete: Boolean,
    val anchors: List<AdaptiveGuideAnchorOption>
)

data class AdaptiveGuideFlow(
    val id: String,
    val title: String,
    val totalSteps: Int,
    val initialStateId: String,
    val states: Map<String, AdaptiveGuideState>
)

data class AdaptiveGuideRulePack(
    val version: Int,
    val disclosure: String,
    val flows: Map<String, AdaptiveGuideFlow>
)

data class AdaptiveGuideResolvedState(
    val flowId: String,
    val title: String,
    val stateId: String,
    val stepNumber: Int,
    val totalSteps: Int,
    val currentTarget: String,
    val assistantHint: String,
    val complete: Boolean,
    val anchors: List<AdaptiveGuideAnchorOption>
)

data class AdaptiveGuideValidationIssue(
    val flowId: String,
    val detail: String
)

object AdaptiveGuideRulePackStore {

    fun load(context: Context): AdaptiveGuideRulePack {
        val localOverride = File(context.filesDir, ADAPTIVE_GUIDE_RULES_FILE)
        val raw = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(ADAPTIVE_GUIDE_RULES_FILE).bufferedReader().use { it.readText() }
            }.getOrNull()
        }
        return AdaptiveGuideRulePackParser.parse(raw)
    }
}

object AdaptiveGuideRulePackParser {

    fun parse(raw: String?): AdaptiveGuideRulePack {
        if (raw.isNullOrBlank()) {
            return AdaptiveGuideRulePack(
                version = 0,
                disclosure = "",
                flows = emptyMap()
            )
        }
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return AdaptiveGuideRulePack(version = 0, disclosure = "", flows = emptyMap())
        val flowsArray = root.optJSONArray("flows")
        val flows = linkedMapOf<String, AdaptiveGuideFlow>()
        if (flowsArray != null) {
            for (index in 0 until flowsArray.length()) {
                val item = flowsArray.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) {
                    continue
                }
                val statesArray = item.optJSONArray("states")
                val states = linkedMapOf<String, AdaptiveGuideState>()
                if (statesArray != null) {
                    for (stateIndex in 0 until statesArray.length()) {
                        val stateItem = statesArray.optJSONObject(stateIndex) ?: continue
                        val stateId = stateItem.optString("id").trim()
                        if (stateId.isBlank()) {
                            continue
                        }
                        val anchors = mutableListOf<AdaptiveGuideAnchorOption>()
                        val anchorArray = stateItem.optJSONArray("anchors")
                        if (anchorArray != null) {
                            for (anchorIndex in 0 until anchorArray.length()) {
                                val anchorItem = anchorArray.optJSONObject(anchorIndex) ?: continue
                                val anchorId = anchorItem.optString("id").trim()
                                val label = anchorItem.optString("label").trim()
                                val nextStateId = anchorItem.optString("next_state_id").trim()
                                if (anchorId.isBlank() || label.isBlank() || nextStateId.isBlank()) {
                                    continue
                                }
                                val matchAny = mutableListOf<String>()
                                val matchArray = anchorItem.optJSONArray("match_any")
                                if (matchArray != null) {
                                    for (matchIndex in 0 until matchArray.length()) {
                                        val value = matchArray.optString(matchIndex).trim()
                                        if (value.isNotBlank()) {
                                            matchAny += value
                                        }
                                    }
                                }
                                anchors += AdaptiveGuideAnchorOption(
                                    id = anchorId,
                                    label = label,
                                    nextStateId = nextStateId,
                                    matchAny = matchAny
                                )
                            }
                        }
                        states[stateId] = AdaptiveGuideState(
                            id = stateId,
                            stepNumber = stateItem.optInt("step_number", 1).coerceAtLeast(1),
                            currentTarget = stateItem.optString("current_target").trim(),
                            assistantHint = stateItem.optString("assistant_hint").trim(),
                            complete = stateItem.optBoolean("complete", false),
                            anchors = anchors
                        )
                    }
                }
                val flow = AdaptiveGuideFlow(
                    id = id,
                    title = item.optString("title").trim(),
                    totalSteps = item.optInt("total_steps", states.size).coerceAtLeast(1),
                    initialStateId = item.optString("initial_state_id").trim(),
                    states = states.mapValues { (_, state) ->
                        state.copy(
                            anchors = state.anchors.filter { anchor ->
                                states.containsKey(anchor.nextStateId)
                            }
                        )
                    }
                )
                if (flow.initialStateId.isNotBlank() && flow.states.containsKey(flow.initialStateId)) {
                    flows[id] = flow
                }
            }
        }
        return AdaptiveGuideRulePack(
            version = root.optInt("version", 0).coerceAtLeast(0),
            disclosure = root.optString("disclosure").trim(),
            flows = flows
        )
    }
}

object AdaptiveGuideRulePackValidator {

    fun validate(pack: AdaptiveGuideRulePack): List<AdaptiveGuideValidationIssue> {
        val issues = mutableListOf<AdaptiveGuideValidationIssue>()
        pack.flows.values.forEach { flow ->
            val stepNumbers = flow.states.values.map { it.stepNumber }.distinct().sorted()
            if (flow.title.isBlank()) {
                issues += AdaptiveGuideValidationIssue(
                    flowId = flow.id,
                    detail = "missing_title"
                )
            }
            if (!flow.states.containsKey(flow.initialStateId)) {
                issues += AdaptiveGuideValidationIssue(
                    flowId = flow.id,
                    detail = "missing_initial_state:${flow.initialStateId}"
                )
            }
            if (!stepNumbers.contains(1)) {
                issues += AdaptiveGuideValidationIssue(
                    flowId = flow.id,
                    detail = "missing_step:1"
                )
            }
            if (!stepNumbers.contains(flow.totalSteps)) {
                issues += AdaptiveGuideValidationIssue(
                    flowId = flow.id,
                    detail = "missing_step:${flow.totalSteps}"
                )
            }
            (1..flow.totalSteps).forEach { stepNumber ->
                if (!stepNumbers.contains(stepNumber)) {
                    issues += AdaptiveGuideValidationIssue(
                        flowId = flow.id,
                        detail = "missing_step:$stepNumber"
                    )
                }
            }
            flow.states.values.forEach { state ->
                if (state.currentTarget.isBlank()) {
                    issues += AdaptiveGuideValidationIssue(
                        flowId = flow.id,
                        detail = "blank_target:${state.id}"
                    )
                }
                if (!state.complete && state.anchors.isEmpty()) {
                    issues += AdaptiveGuideValidationIssue(
                        flowId = flow.id,
                        detail = "missing_resolution_anchor:${state.id}"
                    )
                }
                state.anchors.forEach { anchor ->
                    if (!flow.states.containsKey(anchor.nextStateId)) {
                        issues += AdaptiveGuideValidationIssue(
                            flowId = flow.id,
                            detail = "missing_next_state:${state.id}:${anchor.id}:${anchor.nextStateId}"
                        )
                    }
                }
            }
        }
        return issues
    }

    fun invalidFlowIds(pack: AdaptiveGuideRulePack): Set<String> {
        return validate(pack).mapTo(linkedSetOf()) { it.flowId }
    }
}

object AdaptiveGuideEngine {

    fun start(pack: AdaptiveGuideRulePack, flowId: String): AdaptiveGuideResolvedState? {
        val flow = pack.flows[flowId] ?: return null
        return resolve(flow = flow, stateId = flow.initialStateId)
    }

    fun resolve(pack: AdaptiveGuideRulePack, flowId: String, stateId: String): AdaptiveGuideResolvedState? {
        val flow = pack.flows[flowId] ?: return null
        return resolve(flow = flow, stateId = stateId)
    }

    fun transition(
        pack: AdaptiveGuideRulePack,
        flowId: String,
        stateId: String,
        anchorId: String
    ): AdaptiveGuideResolvedState? {
        val flow = pack.flows[flowId] ?: return null
        val state = flow.states[stateId] ?: return null
        val nextStateId = state.anchors.firstOrNull { it.id == anchorId }?.nextStateId ?: return null
        return resolve(flow = flow, stateId = nextStateId)
    }

    private fun resolve(flow: AdaptiveGuideFlow, stateId: String): AdaptiveGuideResolvedState? {
        val state = flow.states[stateId] ?: return null
        return AdaptiveGuideResolvedState(
            flowId = flow.id,
            title = flow.title,
            stateId = state.id,
            stepNumber = state.stepNumber,
            totalSteps = flow.totalSteps,
            currentTarget = state.currentTarget,
            assistantHint = state.assistantHint,
            complete = state.complete,
            anchors = state.anchors
        )
    }
}

object AdaptiveGuideAuditLog {

    fun append(
        context: Context,
        event: String,
        detail: String
    ) {
        val line = buildString {
            append(System.currentTimeMillis())
            append("|")
            append(event.trim())
            append("|")
            append(detail.trim())
            append("\n")
        }
        runCatching {
            File(context.filesDir, WatchdogConfig.ADAPTIVE_GUIDE_AUDIT_LOG_FILE).appendText(line)
        }
    }
}
