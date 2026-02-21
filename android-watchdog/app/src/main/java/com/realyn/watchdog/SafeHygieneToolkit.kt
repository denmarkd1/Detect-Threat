package com.realyn.watchdog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class HygienePolicy(
    val enabled: Boolean,
    val logRetentionDays: Int,
    val completedQueueRetentionDays: Int,
    val storagePressureThresholdMb: Int,
    val cacheCleanupThresholdMb: Int
)

data class HygieneAuditResult(
    val generatedAtEpochMs: Long,
    val policy: HygienePolicy,
    val appFilesBytes: Long,
    val appCacheBytes: Long,
    val freeInternalBytes: Long,
    val staleArtifactCount: Int,
    val staleArtifactBytes: Long,
    val staleCompletedQueueCount: Int,
    val overlayPermissionEnabled: Boolean,
    val notificationsReady: Boolean,
    val topActions: List<String>,
    val playbook: List<String>
) {
    fun summaryLine(): String {
        return "Storage used=${SafeHygieneToolkit.formatBytes(appFilesBytes)} | cache=${SafeHygieneToolkit.formatBytes(appCacheBytes)} | stale artifacts=$staleArtifactCount | stale completed queue actions=$staleCompletedQueueCount"
    }
}

data class HygieneCleanupResult(
    val reclaimedCacheBytes: Long,
    val reclaimedArtifactBytes: Long,
    val removedArtifactCount: Int,
    val removedCompletedQueueActions: Int
)

private data class StaleArtifact(
    val file: File,
    val bytes: Long
)

object SafeHygieneToolkit {

    private const val SETTINGS_FILE_NAME = "workspace_settings.json"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MB = 1024L * 1024L
    private const val DEFAULT_LOG_RETENTION_DAYS = 30
    private const val DEFAULT_COMPLETED_QUEUE_RETENTION_DAYS = 30
    private const val DEFAULT_STORAGE_PRESSURE_MB = 2048
    private const val DEFAULT_CACHE_CLEANUP_MB = 64

    fun runAudit(context: Context, nowEpochMs: Long = System.currentTimeMillis()): HygieneAuditResult {
        val policy = loadPolicy(context)
        val filesDir = context.filesDir
        val cacheDir = context.cacheDir
        val appFilesBytes = sizeOfPath(filesDir)
        val appCacheBytes = sizeOfPath(cacheDir)
        val freeInternalBytes = runCatching {
            StatFs(filesDir.absolutePath).availableBytes
        }.getOrDefault(0L)

        val staleArtifacts = collectStaleArtifacts(context, policy, nowEpochMs)
        val staleCompletedQueue = countStaleCompletedQueueActions(context, policy, nowEpochMs)
        val overlayEnabled = Settings.canDrawOverlays(context)
        val notificationsReady = notificationsReady(context)
        val storagePressure = freeInternalBytes in 1L until policy.storagePressureThresholdMb.toLong() * MB
        val cacheHeavy = appCacheBytes >= policy.cacheCleanupThresholdMb.toLong() * MB

        val actions = mutableListOf<String>()
        if (staleArtifacts.isNotEmpty()) {
            actions += "Remove outdated local logs and reports older than ${policy.logRetentionDays} days."
        }
        if (staleCompletedQueue > 0) {
            actions += "Trim completed queue actions older than ${policy.completedQueueRetentionDays} days."
        }
        if (cacheHeavy) {
            actions += "Clear app cache sweep (${formatBytes(appCacheBytes)} can be reclaimed)."
        }
        if (storagePressure) {
            actions += "Internal storage is low; run safe cleanup to reduce pressure."
        }
        if (overlayEnabled) {
            actions += "Review overlay permission if you are not actively using overlay assistant."
        }
        if (!notificationsReady) {
            actions += "Enable notifications so scan alerts are visible in real time."
        }
        if (actions.isEmpty()) {
            actions += "No urgent hygiene remediation required."
        }

        val playbook = mutableListOf<String>()
        playbook += "1. Audit storage, logs, and queue residue before cleanup."
        if (staleArtifacts.isNotEmpty()) {
            playbook += "2. Remove outdated local artifacts (${staleArtifacts.size} file(s), ${formatBytes(staleArtifacts.sumOf { it.bytes })})."
        } else {
            playbook += "2. Outdated local artifacts are already within retention policy."
        }
        if (staleCompletedQueue > 0) {
            playbook += "3. Remove stale completed queue items to keep queue views focused."
        } else {
            playbook += "3. Completed queue hygiene is already clean."
        }
        if (cacheHeavy) {
            playbook += "4. Clear app cache (safe, non-destructive) to reclaim ${formatBytes(appCacheBytes)}."
        } else {
            playbook += "4. Cache size is within policy threshold."
        }
        if (storagePressure) {
            playbook += "5. Low storage detected (${formatBytes(freeInternalBytes)} free). Prioritize cleanup now."
        } else {
            playbook += "5. Free internal storage is healthy (${formatBytes(freeInternalBytes)} free)."
        }
        playbook += "6. Run one-time scan after cleanup to refresh baseline signals."

        return HygieneAuditResult(
            generatedAtEpochMs = nowEpochMs,
            policy = policy,
            appFilesBytes = appFilesBytes,
            appCacheBytes = appCacheBytes,
            freeInternalBytes = freeInternalBytes,
            staleArtifactCount = staleArtifacts.size,
            staleArtifactBytes = staleArtifacts.sumOf { it.bytes },
            staleCompletedQueueCount = staleCompletedQueue,
            overlayPermissionEnabled = overlayEnabled,
            notificationsReady = notificationsReady,
            topActions = actions.take(3),
            playbook = playbook
        )
    }

    fun runSafeCleanup(context: Context, nowEpochMs: Long = System.currentTimeMillis()): HygieneCleanupResult {
        val policy = loadPolicy(context)
        val staleArtifacts = collectStaleArtifacts(context, policy, nowEpochMs)
        val beforeCacheBytes = sizeOfPath(context.cacheDir)

        var reclaimedArtifacts = 0L
        var removedArtifacts = 0
        staleArtifacts.forEach { artifact ->
            if (runCatching { artifact.file.delete() }.getOrDefault(false)) {
                reclaimedArtifacts += artifact.bytes
                removedArtifacts += 1
            }
        }

        clearDirectoryChildren(context.cacheDir)
        val afterCacheBytes = sizeOfPath(context.cacheDir)
        val reclaimedCacheBytes = (beforeCacheBytes - afterCacheBytes).coerceAtLeast(0L)

        val removedCompleted = pruneStaleCompletedQueueActions(context, policy, nowEpochMs)

        return HygieneCleanupResult(
            reclaimedCacheBytes = reclaimedCacheBytes,
            reclaimedArtifactBytes = reclaimedArtifacts,
            removedArtifactCount = removedArtifacts,
            removedCompletedQueueActions = removedCompleted
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) {
            return "0 B"
        }
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.2f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun loadPolicy(context: Context): HygienePolicy {
        val defaults = HygienePolicy(
            enabled = true,
            logRetentionDays = DEFAULT_LOG_RETENTION_DAYS,
            completedQueueRetentionDays = DEFAULT_COMPLETED_QUEUE_RETENTION_DAYS,
            storagePressureThresholdMb = DEFAULT_STORAGE_PRESSURE_MB,
            cacheCleanupThresholdMb = DEFAULT_CACHE_CLEANUP_MB
        )
        val payload = readSettingsPayload(context) ?: return defaults
        val hygiene = payload.optJSONObject("hygiene") ?: return defaults
        return HygienePolicy(
            enabled = hygiene.optBoolean("enabled", defaults.enabled),
            logRetentionDays = hygiene.optInt("log_retention_days", defaults.logRetentionDays).coerceIn(7, 120),
            completedQueueRetentionDays = hygiene.optInt(
                "completed_queue_retention_days",
                defaults.completedQueueRetentionDays
            ).coerceIn(7, 120),
            storagePressureThresholdMb = hygiene.optInt(
                "storage_pressure_threshold_mb",
                defaults.storagePressureThresholdMb
            ).coerceIn(256, 16384),
            cacheCleanupThresholdMb = hygiene.optInt(
                "cache_cleanup_threshold_mb",
                defaults.cacheCleanupThresholdMb
            ).coerceIn(8, 4096)
        )
    }

    private fun collectStaleArtifacts(
        context: Context,
        policy: HygienePolicy,
        nowEpochMs: Long
    ): List<StaleArtifact> {
        if (!policy.enabled) {
            return emptyList()
        }
        val threshold = nowEpochMs - policy.logRetentionDays.toLong() * DAY_MS
        val stale = mutableListOf<StaleArtifact>()
        staleFileCandidates(context).forEach { file ->
            val exists = runCatching { file.exists() }.getOrDefault(false)
            if (!exists) {
                return@forEach
            }
            val isFile = runCatching { file.isFile }.getOrDefault(false)
            if (!isFile) {
                return@forEach
            }
            val modified = runCatching { file.lastModified() }.getOrDefault(0L)
            if (modified <= 0L || modified > threshold) {
                return@forEach
            }
            val bytes = runCatching { file.length() }.getOrDefault(0L).coerceAtLeast(0L)
            stale += StaleArtifact(file = file, bytes = bytes)
        }
        return stale
    }

    private fun staleFileCandidates(context: Context): List<File> {
        val dir = context.filesDir
        return listOf(
            File(dir, WatchdogConfig.HISTORY_FILE),
            File(dir, WatchdogConfig.AUDIT_LOG_FILE),
            File(dir, WatchdogConfig.ROOT_AUDIT_LOG_FILE),
            File(dir, WatchdogConfig.GUARDIAN_ALERT_FEED_FILE),
            File(dir, WatchdogConfig.INCIDENT_EVENT_LOG_FILE),
            File(dir, WatchdogConfig.COPILOT_AUDIT_LOG_FILE)
        )
    }

    private fun countStaleCompletedQueueActions(
        context: Context,
        policy: HygienePolicy,
        nowEpochMs: Long
    ): Int {
        val threshold = nowEpochMs - policy.completedQueueRetentionDays.toLong() * DAY_MS
        return CredentialActionStore.loadQueue(context).count { action ->
            action.status.equals("completed", ignoreCase = true) && action.updatedAtEpochMs <= threshold
        }
    }

    private fun pruneStaleCompletedQueueActions(
        context: Context,
        policy: HygienePolicy,
        nowEpochMs: Long
    ): Int {
        val threshold = nowEpochMs - policy.completedQueueRetentionDays.toLong() * DAY_MS
        val queue = CredentialActionStore.loadQueue(context)
        if (queue.isEmpty()) {
            return 0
        }
        val kept = queue.filterNot { action ->
            action.status.equals("completed", ignoreCase = true) && action.updatedAtEpochMs <= threshold
        }
        val removed = queue.size - kept.size
        if (removed > 0) {
            CredentialActionStore.saveQueue(context, kept)
        }
        return removed.coerceAtLeast(0)
    }

    private fun notificationsReady(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun clearDirectoryChildren(directory: File?) {
        if (directory == null || !directory.exists()) {
            return
        }
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                clearDirectoryChildren(child)
            }
            runCatching { child.delete() }
        }
    }

    private fun sizeOfPath(path: File?): Long {
        if (path == null || !path.exists()) {
            return 0L
        }
        if (path.isFile) {
            return path.length().coerceAtLeast(0L)
        }
        var total = 0L
        path.listFiles()?.forEach { child ->
            total += sizeOfPath(child)
        }
        return total.coerceAtLeast(0L)
    }

    private fun readSettingsPayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, SETTINGS_FILE_NAME)
        val content = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(SETTINGS_FILE_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null
        return runCatching { JSONObject(content) }.getOrNull()
    }
}
