package com.realyn.watchdog

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class RootRiskTier(val raw: String) {
    TRUSTED("trusted"),
    ELEVATED("elevated"),
    COMPROMISED("compromised");

    companion object {
        fun fromRaw(value: String?): RootRiskTier {
            return when (value?.trim()?.lowercase(Locale.US)) {
                ELEVATED.raw -> ELEVATED
                COMPROMISED.raw -> COMPROMISED
                else -> TRUSTED
            }
        }
    }
}

data class PlayIntegritySignal(
    val source: String,
    val evaluatedAtEpochMs: Long,
    val deviceRecognitionVerdicts: Set<String>,
    val appRecognitionVerdict: String,
    val accountLicensingVerdict: String
) {
    fun hasDeviceIntegrity(): Boolean = "MEETS_DEVICE_INTEGRITY" in deviceRecognitionVerdicts

    fun hasStrongIntegrity(): Boolean = "MEETS_STRONG_INTEGRITY" in deviceRecognitionVerdicts

    fun isAppRecognized(): Boolean = appRecognitionVerdict == "PLAY_RECOGNIZED"

    fun isLicensed(): Boolean = accountLicensingVerdict == "LICENSED"

    fun toJson(): JSONObject {
        return JSONObject()
            .put("source", source)
            .put("evaluatedAtEpochMs", evaluatedAtEpochMs)
            .put("deviceRecognitionVerdict", JSONArray(deviceRecognitionVerdicts.sorted()))
            .put("appRecognitionVerdict", appRecognitionVerdict)
            .put("accountLicensingVerdict", accountLicensingVerdict)
    }

    companion object {
        fun fromJson(root: JSONObject): PlayIntegritySignal? {
            val nestedDevice = root.optJSONObject("deviceIntegrity")
                ?.optJSONArray("deviceRecognitionVerdict")
            val deviceVerdicts = parseStringSet(
                root.optJSONArray("deviceRecognitionVerdict") ?: nestedDevice
            )

            val nestedAppVerdict = root.optJSONObject("appIntegrity")
                ?.optString("appRecognitionVerdict")
            val appVerdict = root.optString("appRecognitionVerdict")
                .ifBlank { nestedAppVerdict.orEmpty() }
                .trim()
                .uppercase(Locale.US)

            val nestedLicensing = root.optJSONObject("accountDetails")
                ?.optString("appLicensingVerdict")
            val licensing = root.optString("accountLicensingVerdict")
                .ifBlank { nestedLicensing.orEmpty() }
                .trim()
                .uppercase(Locale.US)

            if (deviceVerdicts.isEmpty() && appVerdict.isBlank() && licensing.isBlank()) {
                return null
            }

            val source = root.optString("source").trim().ifBlank { "local_file" }
            val now = System.currentTimeMillis()
            val evaluatedAt = root.optLong("evaluatedAtEpochMs", now).let { value ->
                if (value <= 0L || value > now + (24L * 60L * 60L * 1000L)) now else value
            }

            return PlayIntegritySignal(
                source = source,
                evaluatedAtEpochMs = evaluatedAt,
                deviceRecognitionVerdicts = deviceVerdicts,
                appRecognitionVerdict = appVerdict,
                accountLicensingVerdict = licensing
            )
        }

        private fun parseStringSet(array: JSONArray?): Set<String> {
            if (array == null) {
                return emptySet()
            }
            val values = linkedSetOf<String>()
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim().uppercase(Locale.US)
                if (value.isNotBlank()) {
                    values += value
                }
            }
            return values
        }
    }
}

data class RootPosture(
    val evaluatedAtEpochMs: Long,
    val riskTier: RootRiskTier,
    val reasonCodes: Set<String>,
    val suBinaryDetected: Boolean,
    val rootManagerPackages: Set<String>,
    val buildTags: String,
    val systemProperties: Map<String, String>,
    val playIntegritySignal: PlayIntegritySignal?
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
            .put("evaluatedAtEpochMs", evaluatedAtEpochMs)
            .put("riskTier", riskTier.raw)
            .put("reasonCodes", JSONArray(reasonCodes.sorted()))
            .put("suBinaryDetected", suBinaryDetected)
            .put("rootManagerPackages", JSONArray(rootManagerPackages.sorted()))
            .put("buildTags", buildTags)

        val systemProps = JSONObject()
        systemProperties.keys.sorted().forEach { key ->
            systemProps.put(key, systemProperties[key])
        }
        root.put("systemProperties", systemProps)

        if (playIntegritySignal != null) {
            root.put("playIntegrity", playIntegritySignal.toJson())
        }

        return root
    }

    companion object {
        fun fromJson(root: JSONObject): RootPosture {
            val now = System.currentTimeMillis()
            val reasonCodes = linkedSetOf<String>()
            val reasonArray = root.optJSONArray("reasonCodes") ?: JSONArray()
            for (index in 0 until reasonArray.length()) {
                val code = reasonArray.optString(index).trim().lowercase(Locale.US)
                if (code.isNotBlank()) {
                    reasonCodes += code
                }
            }

            val rootManagerPackages = linkedSetOf<String>()
            val packageArray = root.optJSONArray("rootManagerPackages") ?: JSONArray()
            for (index in 0 until packageArray.length()) {
                val value = packageArray.optString(index).trim()
                if (value.isNotBlank()) {
                    rootManagerPackages += value
                }
            }

            val systemProperties = mutableMapOf<String, String>()
            val props = root.optJSONObject("systemProperties") ?: JSONObject()
            props.keys().forEach { key ->
                systemProperties[key] = props.optString(key).trim()
            }

            val playIntegrity = root.optJSONObject("playIntegrity")?.let { PlayIntegritySignal.fromJson(it) }
            val evaluatedAt = root.optLong("evaluatedAtEpochMs", now).coerceAtLeast(0L)

            return RootPosture(
                evaluatedAtEpochMs = if (evaluatedAt <= 0L) now else evaluatedAt,
                riskTier = RootRiskTier.fromRaw(root.optString("riskTier")),
                reasonCodes = reasonCodes,
                suBinaryDetected = root.optBoolean("suBinaryDetected", false),
                rootManagerPackages = rootManagerPackages,
                buildTags = root.optString("buildTags"),
                systemProperties = systemProperties,
                playIntegritySignal = playIntegrity
            )
        }

        fun trustedFallback(nowEpochMs: Long = System.currentTimeMillis()): RootPosture {
            return RootPosture(
                evaluatedAtEpochMs = nowEpochMs,
                riskTier = RootRiskTier.TRUSTED,
                reasonCodes = setOf("no_root_indicators_detected"),
                suBinaryDetected = false,
                rootManagerPackages = emptySet(),
                buildTags = Build.TAGS.orEmpty(),
                systemProperties = emptyMap(),
                playIntegritySignal = null
            )
        }
    }
}

data class RootHardeningPolicy(
    val riskTier: RootRiskTier,
    val allowContinuousMode: Boolean,
    val allowOverlayAssistant: Boolean,
    val allowClipboardActions: Boolean,
    val requireSensitiveActionConfirmation: Boolean,
    val summary: String
)

internal data class RootRiskEvidence(
    val suBinaryDetected: Boolean,
    val rootManagerPackages: Set<String>,
    val testKeysDetected: Boolean,
    val roDebuggable: String,
    val roSecure: String,
    val verifiedBootState: String,
    val playIntegritySignal: PlayIntegritySignal?
)

internal data class RootRiskEvaluation(
    val riskTier: RootRiskTier,
    val reasonCodes: Set<String>
)

object RootDefense {

    private const val CACHE_TTL_MS = 30_000L
    @Volatile
    private var cachedPosture: RootPosture? = null

    private val knownSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/app/Superuser.apk",
        "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su-backup"
    )

    private val knownRootManagers = setOf(
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
        "com.yellowes.su",
        "com.kingroot.kinguser",
        "com.kingo.root",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext"
    )

    fun evaluate(context: Context, installedPackages: Set<String>): RootPosture {
        cachedPosture?.let { cached ->
            if ((System.currentTimeMillis() - cached.evaluatedAtEpochMs) <= CACHE_TTL_MS) {
                return cached
            }
        }

        val now = System.currentTimeMillis()
        val suDetected = knownSuPaths.any { path -> File(path).exists() }
        val rootPackages = installedPackages.filter { pkg ->
            pkg in knownRootManagers
        }.toSet()

        val buildTags = Build.TAGS.orEmpty().trim()
        val roDebuggable = readSystemProperty("ro.debuggable")
        val roSecure = readSystemProperty("ro.secure")
        val verifiedBootState = readSystemProperty("ro.boot.verifiedbootstate")
            .ifBlank { readSystemProperty("ro.boot.vbmeta.device_state") }
            .ifBlank { "unknown" }
            .lowercase(Locale.US)
        val testKeysDetected = buildTags.contains("test-keys", ignoreCase = true)
        val playIntegritySignal = readPlayIntegritySignal(context)

        val evidence = RootRiskEvidence(
            suBinaryDetected = suDetected,
            rootManagerPackages = rootPackages,
            testKeysDetected = testKeysDetected,
            roDebuggable = roDebuggable,
            roSecure = roSecure,
            verifiedBootState = verifiedBootState,
            playIntegritySignal = playIntegritySignal
        )
        val evaluation = evaluateRisk(evidence)

        val posture = RootPosture(
            evaluatedAtEpochMs = now,
            riskTier = evaluation.riskTier,
            reasonCodes = evaluation.reasonCodes,
            suBinaryDetected = suDetected,
            rootManagerPackages = rootPackages,
            buildTags = buildTags,
            systemProperties = linkedMapOf(
                "ro.debuggable" to roDebuggable,
                "ro.secure" to roSecure,
                "ro.boot.verifiedbootstate" to verifiedBootState
            ),
            playIntegritySignal = playIntegritySignal
        )
        cachedPosture = posture
        return posture
    }

    fun evaluate(context: Context): RootPosture {
        cachedPosture?.let { cached ->
            if ((System.currentTimeMillis() - cached.evaluatedAtEpochMs) <= CACHE_TTL_MS) {
                return cached
            }
        }
        val installedPackages = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(
                        android.content.pm.PackageManager.GET_META_DATA.toLong()
                    )
                ).map { it.packageName }.toSet()
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    .map { it.packageName }
                    .toSet()
            }
        }.getOrDefault(emptySet())
        return evaluate(context, installedPackages)
    }

    fun resolveHardeningPolicy(posture: RootPosture): RootHardeningPolicy {
        return when (posture.riskTier) {
            RootRiskTier.TRUSTED -> RootHardeningPolicy(
                riskTier = posture.riskTier,
                allowContinuousMode = true,
                allowOverlayAssistant = true,
                allowClipboardActions = true,
                requireSensitiveActionConfirmation = false,
                summary = "Device posture trusted for standard watchdog actions."
            )

            RootRiskTier.ELEVATED -> RootHardeningPolicy(
                riskTier = posture.riskTier,
                allowContinuousMode = true,
                allowOverlayAssistant = true,
                allowClipboardActions = true,
                requireSensitiveActionConfirmation = true,
                summary = "Root-risk posture elevated. Extra confirmation is required for sensitive actions."
            )

            RootRiskTier.COMPROMISED -> RootHardeningPolicy(
                riskTier = posture.riskTier,
                allowContinuousMode = false,
                allowOverlayAssistant = false,
                allowClipboardActions = false,
                requireSensitiveActionConfirmation = true,
                summary = "Root-risk posture compromised. Continuous mode, overlay, and clipboard actions are blocked."
            )
        }
    }

    fun readPlayIntegritySignal(context: Context): PlayIntegritySignal? {
        val file = File(context.filesDir, WatchdogConfig.PLAY_INTEGRITY_VERDICT_FILE)
        if (!file.exists()) {
            return null
        }
        val raw = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (raw.isBlank()) {
            return null
        }
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return PlayIntegritySignal.fromJson(payload)
    }

    fun appendRootAuditEntry(
        context: Context,
        posture: RootPosture,
        scannedAtIso: String,
        baselineCreated: Boolean
    ) {
        val reasonSummary = posture.reasonCodes.sorted().joinToString(",")
        val playSource = posture.playIntegritySignal?.source ?: "none"
        val playDevice = posture.playIntegritySignal?.deviceRecognitionVerdicts?.sorted()?.joinToString("+") ?: "none"
        val rootPackages = if (posture.rootManagerPackages.isEmpty()) "none" else posture.rootManagerPackages.sorted().joinToString("+")
        val line = "$scannedAtIso tier=${posture.riskTier.raw} reasons=\"$reasonSummary\" su=${posture.suBinaryDetected} root_packages=\"$rootPackages\" play_source=$playSource play_device=\"$playDevice\" baseline_created=$baselineCreated"
        File(context.filesDir, WatchdogConfig.ROOT_AUDIT_LOG_FILE).appendText(line + "\n")
    }

    internal fun evaluateRisk(evidence: RootRiskEvidence): RootRiskEvaluation {
        val reasons = linkedSetOf<String>()

        if (evidence.suBinaryDetected) {
            reasons += "su_binary_detected"
        }
        if (evidence.rootManagerPackages.isNotEmpty()) {
            reasons += "root_manager_package_detected"
        }
        if (evidence.testKeysDetected) {
            reasons += "build_test_keys_detected"
        }

        val debuggableEnabled = evidence.roDebuggable == "1"
        val secureDisabled = evidence.roSecure == "0"
        if (debuggableEnabled) {
            reasons += "system_debuggable_enabled"
        }
        if (debuggableEnabled && secureDisabled) {
            reasons += "system_secure_disabled"
        }

        val verifiedBootCompromised = evidence.verifiedBootState in setOf("orange", "red")
        val verifiedBootUncertain = evidence.verifiedBootState in setOf("yellow", "unknown", "")
        if (verifiedBootCompromised) {
            reasons += "verified_boot_compromised"
        } else if (verifiedBootUncertain) {
            reasons += "verified_boot_uncertain"
        }

        val play = evidence.playIntegritySignal
        if (play == null) {
            reasons += "play_integrity_not_ingested"
        } else {
            if (!play.hasDeviceIntegrity()) {
                reasons += "play_device_integrity_missing"
            }
            if (!play.hasStrongIntegrity()) {
                reasons += "play_strong_integrity_missing"
            }
            if (!play.isAppRecognized()) {
                reasons += "play_app_not_recognized"
            }
            if (!play.isLicensed()) {
                reasons += "play_account_unlicensed"
            }
        }

        val localCompromise = evidence.suBinaryDetected || evidence.rootManagerPackages.isNotEmpty() || verifiedBootCompromised
        val playCompromise = play?.let { !it.isAppRecognized() || (!it.hasDeviceIntegrity() && (evidence.testKeysDetected || debuggableEnabled)) } ?: false
        val elevated = evidence.testKeysDetected ||
            debuggableEnabled ||
            verifiedBootUncertain ||
            (play?.hasDeviceIntegrity() == false) ||
            (play?.hasStrongIntegrity() == false) ||
            (play?.isLicensed() == false)

        val tier = when {
            localCompromise || playCompromise -> RootRiskTier.COMPROMISED
            elevated -> RootRiskTier.ELEVATED
            else -> RootRiskTier.TRUSTED
        }

        if (tier == RootRiskTier.TRUSTED) {
            reasons.clear()
            reasons += "no_root_indicators_detected"
        }

        return RootRiskEvaluation(
            riskTier = tier,
            reasonCodes = reasons
        )
    }

    private fun readSystemProperty(name: String): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "getprop $name"))
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrDefault("")
    }
}
