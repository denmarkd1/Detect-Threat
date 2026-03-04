package com.realyn.watchdog

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import java.util.Locale
import java.util.UUID

enum class DigitalKeyHighRiskAction {
    KEY_SHARE,
    REMOTE_COMMAND
}

data class DigitalKeySetupCapability(
    val categoryLabel: String,
    val provider: IntegrationMeshDigitalKeyGuidanceProvider,
    val installedPackage: String,
    val appInstalled: Boolean,
    val appLaunchReady: Boolean
)

data class DigitalKeyPrerequisiteReport(
    val lockScreenSecure: Boolean,
    val biometricReady: Boolean,
    val appLockEnabled: Boolean,
    val rootPosture: RootPosture,
    val playIntegrityIngested: Boolean,
    val playDeviceIntegrityReady: Boolean,
    val playStrongIntegrityReady: Boolean,
    val blockedReasonCodes: List<String>,
    val warningReasonCodes: List<String>
) {
    val highRiskAllowed: Boolean
        get() = blockedReasonCodes.isEmpty()
}

data class DigitalKeyGuardrailReport(
    val enabled: Boolean,
    val adapterId: String,
    val adapterLabel: String,
    val assessment: DigitalKeyRiskAssessment,
    val prerequisites: DigitalKeyPrerequisiteReport,
    val walletCapabilities: List<DigitalKeySetupCapability>,
    val manufacturerCapabilities: List<DigitalKeySetupCapability>
)

internal data class DigitalKeyRiskInput(
    val ownerRole: String,
    val lockScreenSecure: Boolean,
    val biometricReady: Boolean,
    val rootTier: RootRiskTier,
    val playDeviceIntegrityReady: Boolean,
    val playStrongIntegrityReady: Boolean,
    val activeConsentCount: Int,
    val staleConsentCount: Int,
    val maxPostureRiskScore: Int
)

internal object DigitalKeyRiskScorer {

    data class Result(
        val score: Int,
        val findings: List<DigitalKeyRiskFinding>
    )

    fun assess(
        input: DigitalKeyRiskInput,
        supportedRiskCategories: Set<String>
    ): Result {
        val categories = supportedRiskCategories.map { it.trim().lowercase(Locale.US) }.toSet()
        val findings = mutableListOf<DigitalKeyRiskFinding>()
        var score = 0

        fun addFinding(type: String, severity: String, message: String) {
            if (categories.isNotEmpty() && type !in categories) {
                return
            }
            findings += DigitalKeyRiskFinding(
                findingType = type,
                severity = severity,
                message = message
            )
            score += when (severity.lowercase(Locale.US)) {
                "high" -> 34
                "medium" -> 18
                "low" -> 8
                else -> 4
            }
        }

        if (!input.lockScreenSecure) {
            addFinding(
                type = "unverified_remote_unlock",
                severity = "high",
                message = "Device lock screen is not secured, which weakens digital-key protection."
            )
        }
        if (!input.biometricReady) {
            addFinding(
                type = "sudden_privilege_change",
                severity = "medium",
                message = "Biometric or device credential is not ready for high-risk key actions."
            )
        }
        when (input.rootTier) {
            RootRiskTier.COMPROMISED -> addFinding(
                type = "prerequisite_gap",
                severity = "high",
                message = "Integrity posture is compromised. High-risk digital-key actions should stay blocked."
            )
            RootRiskTier.ELEVATED -> addFinding(
                type = "prerequisite_gap",
                severity = "medium",
                message = "Integrity posture is elevated. Require extra verification before key actions."
            )
            RootRiskTier.TRUSTED -> Unit
        }
        if (!input.playDeviceIntegrityReady) {
            addFinding(
                type = "prerequisite_gap",
                severity = "high",
                message = "Play device integrity verdict is missing."
            )
        } else if (!input.playStrongIntegrityReady) {
            addFinding(
                type = "prerequisite_gap",
                severity = "medium",
                message = "Play strong integrity verdict is missing."
            )
        }
        if (input.activeConsentCount == 0 || input.staleConsentCount > 0) {
            addFinding(
                type = "stale_consents",
                severity = if (input.activeConsentCount == 0) "medium" else "low",
                message = "Connector consent artifacts are inactive or stale; re-verify linked services."
            )
        }

        when {
            input.maxPostureRiskScore >= 70 -> addFinding(
                type = "location_restriction_violation",
                severity = "high",
                message = "Connected-home posture indicates high-risk conditions for remote lock actions."
            )
            input.maxPostureRiskScore >= 45 -> addFinding(
                type = "location_restriction_violation",
                severity = "medium",
                message = "Connected-home posture is elevated; apply tighter digital-key restrictions."
            )
        }

        if (input.ownerRole.equals("child", ignoreCase = true) || input.ownerRole.equals("son", ignoreCase = true)) {
            addFinding(
                type = "social_engineering_exposure",
                severity = "medium",
                message = "Minor profile detected. Guardian confirmation is recommended for key sharing and remote commands."
            )
        }

        if (findings.isEmpty()) {
            addFinding(
                type = "prerequisite_gap",
                severity = "low",
                message = "No major local digital-key risk findings detected."
            )
        }

        return Result(
            score = score.coerceIn(0, 100),
            findings = findings
        )
    }
}

class LocalDigitalKeyRiskAdapter(
    private val adapterConfig: IntegrationMeshDigitalKeyConfig,
    override val adapterId: String = "local_digital_key_guardrails",
    override val adapterLabel: String = "Local digital-key guardrails adapter"
) : DigitalKeyRiskAdapter {

    override suspend fun assessRisk(
        context: Context,
        ownerRole: String,
        consentArtifacts: List<ConnectorConsentArtifact>,
        postureSnapshots: List<SmartHomePostureSnapshot>
    ): DigitalKeyRiskAssessment {
        val prerequisites = DigitalKeyGuardrailEngine.evaluatePrerequisites(context, adapterConfig)
        val now = System.currentTimeMillis()
        val activeConsentCount = consentArtifacts.count { artifact ->
            isConsentActive(artifact, now)
        }
        val staleConsentCount = consentArtifacts.count { artifact ->
            !isConsentActive(artifact, now)
        }
        val input = DigitalKeyRiskInput(
            ownerRole = normalizeRole(ownerRole),
            lockScreenSecure = prerequisites.lockScreenSecure,
            biometricReady = prerequisites.biometricReady,
            rootTier = prerequisites.rootPosture.riskTier,
            playDeviceIntegrityReady = prerequisites.playDeviceIntegrityReady,
            playStrongIntegrityReady = prerequisites.playStrongIntegrityReady,
            activeConsentCount = activeConsentCount,
            staleConsentCount = staleConsentCount,
            maxPostureRiskScore = postureSnapshots.maxOfOrNull { it.riskScore } ?: 0
        )
        val scored = DigitalKeyRiskScorer.assess(
            input = input,
            supportedRiskCategories = adapterConfig.supportedRiskCategories.toSet()
        )
        val overallRiskLevel = when {
            scored.score >= 70 -> "high"
            scored.score >= 35 -> "medium"
            else -> "low"
        }

        return DigitalKeyRiskAssessment(
            assessmentId = UUID.randomUUID().toString(),
            ownerRole = normalizeRole(ownerRole),
            totalRiskScore = scored.score,
            overallRiskLevel = overallRiskLevel,
            findings = scored.findings,
            assessedAtEpochMs = now
        )
    }

    private fun isConsentActive(artifact: ConnectorConsentArtifact, now: Long): Boolean {
        return artifact.status.equals("active", ignoreCase = true) &&
            artifact.revokedAtEpochMs == 0L &&
            artifact.grantedAtEpochMs <= now &&
            (artifact.expiresAtEpochMs == 0L || artifact.expiresAtEpochMs >= now)
    }

    private fun normalizeRole(value: String): String {
        return value.trim().lowercase(Locale.US).ifBlank { "parent" }
    }
}

object DigitalKeyGuardrailEngine {

    suspend fun resolveReport(
        context: Context,
        ownerRole: String,
        enabled: Boolean,
        adapterId: String,
        adapter: DigitalKeyRiskAdapter?,
        config: IntegrationMeshDigitalKeyConfig,
        consentArtifacts: List<ConnectorConsentArtifact>,
        postureSnapshots: List<SmartHomePostureSnapshot>
    ): DigitalKeyGuardrailReport {
        val normalizedOwnerRole = ownerRole.trim().lowercase(Locale.US).ifBlank { "parent" }
        val prerequisites = evaluatePrerequisites(context, config)
        val assessment = when {
            enabled && adapter != null -> adapter.assessRisk(
                context = context,
                ownerRole = normalizedOwnerRole,
                consentArtifacts = consentArtifacts,
                postureSnapshots = postureSnapshots
            )
            else -> DigitalKeyRiskAssessment(
                assessmentId = UUID.randomUUID().toString(),
                ownerRole = normalizedOwnerRole,
                totalRiskScore = 52,
                overallRiskLevel = "medium",
                findings = listOf(
                    DigitalKeyRiskFinding(
                        findingType = "prerequisite_gap",
                        severity = "medium",
                        message = "Digital-key adapter rollout is not configured for this profile."
                    )
                ),
                assessedAtEpochMs = System.currentTimeMillis()
            )
        }

        return DigitalKeyGuardrailReport(
            enabled = enabled,
            adapterId = adapterId,
            adapterLabel = adapter?.adapterLabel.orEmpty(),
            assessment = assessment,
            prerequisites = prerequisites,
            walletCapabilities = resolveSetupCapabilities(
                context = context,
                providers = config.walletSetupGuidance,
                categoryLabel = "wallet"
            ),
            manufacturerCapabilities = resolveSetupCapabilities(
                context = context,
                providers = config.manufacturerSetupGuidance,
                categoryLabel = "manufacturer"
            )
        )
    }

    fun evaluatePrerequisites(
        context: Context,
        config: IntegrationMeshDigitalKeyConfig
    ): DigitalKeyPrerequisiteReport {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val lockScreenSecure = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager?.isDeviceSecure == true
        } else {
            keyguardManager?.isKeyguardSecure == true
        }

        val biometrics = BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        val biometricReady = biometrics == BiometricManager.BIOMETRIC_SUCCESS
        val appLockEnabled = readAppLockEnabled(context)
        val rootPosture = SecurityScanner.currentRootPosture(context)
        val playIntegrity = rootPosture.playIntegritySignal
        val playIntegrityIngested = playIntegrity != null
        val playDeviceIntegrityReady = playIntegrity?.hasDeviceIntegrity() == true
        val playStrongIntegrityReady = playIntegrity?.hasStrongIntegrity() == true

        val blocked = linkedSetOf<String>()
        val warning = linkedSetOf<String>()

        if (config.requireLockScreenForHighRiskActions && !lockScreenSecure) {
            blocked += "lock_screen_not_secure"
        }
        if (config.requireBiometricForHighRiskActions && !biometricReady) {
            blocked += "biometric_not_ready"
        }
        if (config.requireStrongIntegrityForHighRiskActions) {
            when (rootPosture.riskTier) {
                RootRiskTier.COMPROMISED -> blocked += "integrity_compromised"
                RootRiskTier.ELEVATED -> {
                    if (config.blockHighRiskActionsOnElevatedIntegrity) {
                        blocked += "integrity_elevated"
                    } else {
                        warning += "integrity_elevated"
                    }
                }
                RootRiskTier.TRUSTED -> Unit
            }
            if (!playDeviceIntegrityReady) {
                blocked += "play_device_integrity_missing"
            }
            if (!playStrongIntegrityReady) {
                blocked += "play_strong_integrity_missing"
            }
        } else if (rootPosture.riskTier == RootRiskTier.COMPROMISED) {
            blocked += "integrity_compromised"
        }

        if (!playIntegrityIngested) {
            warning += "play_integrity_not_ingested"
        }
        if (!appLockEnabled) {
            warning += "app_lock_disabled"
        }

        return DigitalKeyPrerequisiteReport(
            lockScreenSecure = lockScreenSecure,
            biometricReady = biometricReady,
            appLockEnabled = appLockEnabled,
            rootPosture = rootPosture,
            playIntegrityIngested = playIntegrityIngested,
            playDeviceIntegrityReady = playDeviceIntegrityReady,
            playStrongIntegrityReady = playStrongIntegrityReady,
            blockedReasonCodes = blocked.toList(),
            warningReasonCodes = warning.toList()
        )
    }

    fun resolveSetupCapabilities(
        context: Context,
        providers: List<IntegrationMeshDigitalKeyGuidanceProvider>,
        categoryLabel: String
    ): List<DigitalKeySetupCapability> {
        return providers.map { provider ->
            val launchPackage = provider.packageNames.firstOrNull { packageName ->
                context.packageManager.getLaunchIntentForPackage(packageName) != null
            }.orEmpty()
            val installedPackage = provider.packageNames.firstOrNull { packageName ->
                isPackageInstalled(context, packageName)
            }.orEmpty()
            DigitalKeySetupCapability(
                categoryLabel = categoryLabel,
                provider = provider,
                installedPackage = installedPackage,
                appInstalled = installedPackage.isNotBlank(),
                appLaunchReady = launchPackage.isNotBlank()
            )
        }
    }

    private fun readAppLockEnabled(context: Context): Boolean {
        val payload = WorkspaceSettingsStore.readPayload(context)
        val appLock = payload?.optJSONObject("app_lock")
        return appLock?.optBoolean("enabled", true) ?: true
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        val packageManager = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }
}
