package com.realyn.watchdog

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class HygienePolicy(
    val enabled: Boolean,
    val logRetentionDays: Int,
    val completedQueueRetentionDays: Int,
    val storagePressureThresholdMb: Int,
    val cacheCleanupThresholdMb: Int
)

data class HygieneHealthReport(
    val safeCleanupBytes: Long,
    val inactiveAppCandidateCount: Int,
    val inactiveAppExamples: List<String>,
    val usageAccessGranted: Boolean,
    val duplicateMediaGroupCount: Int,
    val duplicateMediaFileCount: Int,
    val duplicateMediaReclaimableBytes: Long,
    val duplicateMediaExamples: List<String>,
    val mediaReadAccessGranted: Boolean,
    val installerRemnantCount: Int,
    val installerRemnantBytes: Long,
    val installerRemnantExamples: List<String>
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
    val healthReport: HygieneHealthReport,
    val topActions: List<String>,
    val playbook: List<String>
) {
    fun summaryLine(): String {
        val storageLine = "Storage used=${SafeHygieneToolkit.formatBytes(appFilesBytes)} | cache=${SafeHygieneToolkit.formatBytes(appCacheBytes)} | stale artifacts=$staleArtifactCount | stale completed queue actions=$staleCompletedQueueCount"
        val healthLine = "Health report: inactive apps=${healthReport.inactiveAppCandidateCount} | duplicate files=${healthReport.duplicateMediaFileCount} | safe reclaimable=${SafeHygieneToolkit.formatBytes(healthReport.safeCleanupBytes)}"
        return "$storageLine\n$healthLine"
    }
}

data class HygieneCleanupSelection(
    val clearCache: Boolean,
    val removeStaleArtifacts: Boolean,
    val trimCompletedQueue: Boolean
) {
    fun hasAnySelection(): Boolean {
        return clearCache || removeStaleArtifacts || trimCompletedQueue
    }

    companion object {
        fun allEnabled(): HygieneCleanupSelection {
            return HygieneCleanupSelection(
                clearCache = true,
                removeStaleArtifacts = true,
                trimCompletedQueue = true
            )
        }
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

private data class InactiveAppSnapshot(
    val candidateCount: Int,
    val examples: List<String>,
    val usageAccessGranted: Boolean
)

private data class DuplicateMediaSnapshot(
    val groupCount: Int,
    val fileCount: Int,
    val reclaimableBytes: Long,
    val examples: List<String>,
    val mediaReadAccessGranted: Boolean
)

private data class DuplicateAggregate(
    val name: String,
    val sizeBytes: Long,
    var count: Int
)

private data class InstallerRemnantSnapshot(
    val count: Int,
    val bytes: Long,
    val examples: List<String>,
    val mediaReadAccessGranted: Boolean
)

object SafeHygieneToolkit {

    private const val SETTINGS_FILE_NAME = "workspace_settings.json"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MB = 1024L * 1024L
    private const val DEFAULT_LOG_RETENTION_DAYS = 30
    private const val DEFAULT_COMPLETED_QUEUE_RETENTION_DAYS = 30
    private const val DEFAULT_STORAGE_PRESSURE_MB = 2048
    private const val DEFAULT_CACHE_CLEANUP_MB = 64
    private const val INACTIVE_APP_THRESHOLD_DAYS = 45
    private const val INACTIVE_APP_FOREGROUND_MS_THRESHOLD = 2L * 60L * 1000L
    private const val DUPLICATE_MEDIA_MIN_SIZE_BYTES = 256L * 1024L
    private const val DUPLICATE_MEDIA_MAX_ROWS = 12000
    private const val INSTALLER_REMNANT_MAX_ROWS = 10000
    private const val INSTALLER_REMNANT_MIN_AGE_DAYS = 7
    private val INSTALLER_ARCHIVE_EXTENSIONS = setOf("apk", "xapk", "apkm")

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
        val staleArtifactBytes = staleArtifacts.sumOf { it.bytes }
        val staleCompletedQueue = countStaleCompletedQueueActions(context, policy, nowEpochMs)
        val overlayEnabled = Settings.canDrawOverlays(context)
        val notificationsReady = notificationsReady(context)
        val storagePressure = freeInternalBytes in 1L until policy.storagePressureThresholdMb.toLong() * MB
        val cacheHeavy = appCacheBytes >= policy.cacheCleanupThresholdMb.toLong() * MB

        val inactiveApps = collectInactiveAppSnapshot(context, nowEpochMs)
        val duplicateMedia = collectDuplicateMediaSnapshot(context)
        val installerRemnants = collectInstallerRemnantSnapshot(context, nowEpochMs)
        val safeCleanupBytes = appCacheBytes + staleArtifactBytes
        val healthReport = HygieneHealthReport(
            safeCleanupBytes = safeCleanupBytes,
            inactiveAppCandidateCount = inactiveApps.candidateCount,
            inactiveAppExamples = inactiveApps.examples,
            usageAccessGranted = inactiveApps.usageAccessGranted,
            duplicateMediaGroupCount = duplicateMedia.groupCount,
            duplicateMediaFileCount = duplicateMedia.fileCount,
            duplicateMediaReclaimableBytes = duplicateMedia.reclaimableBytes,
            duplicateMediaExamples = duplicateMedia.examples,
            mediaReadAccessGranted = duplicateMedia.mediaReadAccessGranted,
            installerRemnantCount = installerRemnants.count,
            installerRemnantBytes = installerRemnants.bytes,
            installerRemnantExamples = installerRemnants.examples
        )

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
        if (inactiveApps.candidateCount > 0) {
            actions += "Review ${"%d".format(Locale.US, inactiveApps.candidateCount)} inactive app candidate(s) for uninstall."
        } else if (!inactiveApps.usageAccessGranted) {
            actions += "Grant app usage access to detect inactive app candidates."
        }
        if (duplicateMedia.groupCount > 0) {
            actions += "Review duplicate media groups (${duplicateMedia.groupCount} groups, potential ${formatBytes(duplicateMedia.reclaimableBytes)} reclaim)."
        } else if (!duplicateMedia.mediaReadAccessGranted) {
            actions += "Grant media read permission to detect duplicate file candidates."
        }
        if (installerRemnants.count > 0) {
            actions += "Review stale installer remnants (${installerRemnants.count} file(s), ${formatBytes(installerRemnants.bytes)})."
        } else if (!installerRemnants.mediaReadAccessGranted) {
            actions += "Grant media read permission to detect stale installer remnants."
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
        playbook += "1. Review health summary and choose cleanup actions intentionally."
        if (staleArtifacts.isNotEmpty()) {
            playbook += "2. Remove outdated local artifacts (${staleArtifacts.size} file(s), ${formatBytes(staleArtifactBytes)})."
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
        if (inactiveApps.candidateCount > 0) {
            val examples = inactiveApps.examples.take(3).joinToString(", ")
            playbook += "5. Inactive app candidates (${inactiveApps.candidateCount}): $examples. Uninstall only what you no longer use."
        } else if (!inactiveApps.usageAccessGranted) {
            playbook += "5. Usage access not granted. Enable usage access for stronger inactive-app recommendations."
        } else {
            playbook += "5. No inactive app candidates exceeded the policy threshold."
        }
        if (duplicateMedia.groupCount > 0) {
            val examples = duplicateMedia.examples.take(3).joinToString(", ")
            playbook += "6. Duplicate media candidates (${duplicateMedia.groupCount} groups, ${formatBytes(duplicateMedia.reclaimableBytes)} potential): $examples."
        } else if (!duplicateMedia.mediaReadAccessGranted) {
            playbook += "6. Media read permission not granted. Enable it to detect duplicate media candidates."
        } else {
            playbook += "6. No duplicate media candidates crossed the risk threshold."
        }
        if (installerRemnants.count > 0) {
            val examples = installerRemnants.examples.take(3).joinToString(", ")
            playbook += "7. Installer remnants (${installerRemnants.count} file(s), ${formatBytes(installerRemnants.bytes)}): $examples. Remove only installers you no longer need."
        } else if (!installerRemnants.mediaReadAccessGranted) {
            playbook += "7. Media read permission not granted. Enable it to detect stale installer remnants."
        } else {
            playbook += "7. No stale installer remnant candidates crossed the policy threshold."
        }
        if (storagePressure) {
            playbook += "8. Low storage detected (${formatBytes(freeInternalBytes)} free). Prioritize cleanup now."
        } else {
            playbook += "8. Free internal storage is healthy (${formatBytes(freeInternalBytes)} free)."
        }
        playbook += "9. Run one-time or deep scan after cleanup to refresh security posture."

        return HygieneAuditResult(
            generatedAtEpochMs = nowEpochMs,
            policy = policy,
            appFilesBytes = appFilesBytes,
            appCacheBytes = appCacheBytes,
            freeInternalBytes = freeInternalBytes,
            staleArtifactCount = staleArtifacts.size,
            staleArtifactBytes = staleArtifactBytes,
            staleCompletedQueueCount = staleCompletedQueue,
            overlayPermissionEnabled = overlayEnabled,
            notificationsReady = notificationsReady,
            healthReport = healthReport,
            topActions = actions.take(4),
            playbook = playbook
        )
    }

    fun runSafeCleanup(
        context: Context,
        selection: HygieneCleanupSelection = HygieneCleanupSelection.allEnabled(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): HygieneCleanupResult {
        if (!selection.hasAnySelection()) {
            logMaintenanceAction(
                context = context,
                action = "safe_cleanup_skipped",
                detail = JSONObject().put("reason", "no_selection")
            )
            return HygieneCleanupResult(
                reclaimedCacheBytes = 0L,
                reclaimedArtifactBytes = 0L,
                removedArtifactCount = 0,
                removedCompletedQueueActions = 0
            )
        }

        val policy = loadPolicy(context)
        var reclaimedArtifacts = 0L
        var removedArtifacts = 0
        if (selection.removeStaleArtifacts) {
            val staleArtifacts = collectStaleArtifacts(context, policy, nowEpochMs)
            staleArtifacts.forEach { artifact ->
                if (runCatching { artifact.file.delete() }.getOrDefault(false)) {
                    reclaimedArtifacts += artifact.bytes
                    removedArtifacts += 1
                }
            }
        }

        var reclaimedCacheBytes = 0L
        if (selection.clearCache) {
            val beforeCacheBytes = sizeOfPath(context.cacheDir)
            clearDirectoryChildren(context.cacheDir)
            val afterCacheBytes = sizeOfPath(context.cacheDir)
            reclaimedCacheBytes = (beforeCacheBytes - afterCacheBytes).coerceAtLeast(0L)
        }

        val removedCompleted = if (selection.trimCompletedQueue) {
            pruneStaleCompletedQueueActions(context, policy, nowEpochMs)
        } else {
            0
        }

        val result = HygieneCleanupResult(
            reclaimedCacheBytes = reclaimedCacheBytes,
            reclaimedArtifactBytes = reclaimedArtifacts,
            removedArtifactCount = removedArtifacts,
            removedCompletedQueueActions = removedCompleted
        )
        logMaintenanceAction(
            context = context,
            action = "safe_cleanup_applied",
            detail = JSONObject()
                .put("clearCache", selection.clearCache)
                .put("removeStaleArtifacts", selection.removeStaleArtifacts)
                .put("trimCompletedQueue", selection.trimCompletedQueue)
                .put("reclaimedCacheBytes", result.reclaimedCacheBytes)
                .put("reclaimedArtifactBytes", result.reclaimedArtifactBytes)
                .put("removedArtifactCount", result.removedArtifactCount)
                .put("removedCompletedQueueActions", result.removedCompletedQueueActions)
        )
        return result
    }

    fun logMaintenanceAction(
        context: Context,
        action: String,
        detail: JSONObject = JSONObject()
    ) {
        val now = System.currentTimeMillis()
        val row = JSONObject()
            .put("event", "maintenance_action")
            .put("action", action.trim().ifBlank { "unknown" })
            .put("epochMs", now)
            .put("iso", formatIsoTime(now))
            .put("detail", detail)
        runCatching {
            val file = File(context.filesDir, WatchdogConfig.AUDIT_LOG_FILE)
            file.appendText(row.toString() + "\n")
        }
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
            File(dir, WatchdogConfig.DEEP_SCAN_HISTORY_FILE),
            File(dir, WatchdogConfig.GUARDIAN_ALERT_FEED_FILE),
            File(dir, WatchdogConfig.INCIDENT_EVENT_LOG_FILE),
            File(dir, WatchdogConfig.COPILOT_AUDIT_LOG_FILE)
        )
    }

    private fun collectInactiveAppSnapshot(
        context: Context,
        nowEpochMs: Long
    ): InactiveAppSnapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || !hasUsageStatsAccess(context)) {
            return InactiveAppSnapshot(
                candidateCount = 0,
                examples = emptyList(),
                usageAccessGranted = false
            )
        }

        val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return InactiveAppSnapshot(0, emptyList(), false)
        val windowStart = nowEpochMs - INACTIVE_APP_THRESHOLD_DAYS.toLong() * DAY_MS
        val usageStats = runCatching {
            usageManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                windowStart,
                nowEpochMs
            )
        }.getOrDefault(emptyList())

        val usageByPackage = mutableMapOf<String, Pair<Long, Long>>()
        usageStats.forEach { stat ->
            val pkg = stat.packageName.orEmpty().trim()
            if (pkg.isBlank()) {
                return@forEach
            }
            val existing = usageByPackage[pkg]
            val lastUsed = maxOf(existing?.first ?: 0L, stat.lastTimeUsed.coerceAtLeast(0L))
            val foreground = (existing?.second ?: 0L) + stat.totalTimeInForeground.coerceAtLeast(0L)
            usageByPackage[pkg] = lastUsed to foreground
        }

        val threshold = nowEpochMs - INACTIVE_APP_THRESHOLD_DAYS.toLong() * DAY_MS
        val pm = context.packageManager
        val candidates = mutableListOf<Pair<String, Long>>()
        thirdPartyApps(context).forEach { app ->
            if (app.packageName == context.packageName) {
                return@forEach
            }
            val usage = usageByPackage[app.packageName]
            val lastUsed = usage?.first ?: 0L
            val foreground = usage?.second ?: 0L
            val appearsInactive = (lastUsed <= threshold || lastUsed <= 0L) &&
                foreground <= INACTIVE_APP_FOREGROUND_MS_THRESHOLD
            if (!appearsInactive) {
                return@forEach
            }
            val label = runCatching {
                pm.getApplicationLabel(app).toString().trim().ifBlank { app.packageName }
            }.getOrDefault(app.packageName)
            candidates += label to lastUsed
        }

        val sorted = candidates.sortedBy { (_, lastUsed) ->
            if (lastUsed <= 0L) Long.MIN_VALUE else lastUsed
        }
        val examples = sorted.take(5).map { (label, lastUsed) ->
            if (lastUsed <= 0L) {
                "$label (no recent use data)"
            } else {
                "$label (last used > ${INACTIVE_APP_THRESHOLD_DAYS}d)"
            }
        }
        return InactiveAppSnapshot(
            candidateCount = sorted.size,
            examples = examples,
            usageAccessGranted = true
        )
    }

    private fun collectDuplicateMediaSnapshot(context: Context): DuplicateMediaSnapshot {
        if (!hasMediaReadPermission(context)) {
            return DuplicateMediaSnapshot(
                groupCount = 0,
                fileCount = 0,
                reclaimableBytes = 0L,
                examples = emptyList(),
                mediaReadAccessGranted = false
            )
        }

        val groups = mutableMapOf<String, DuplicateAggregate>()
        var scannedRows = 0
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )

        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                null,
                null,
                null
            )
        }.getOrNull()

        cursor?.use { reader ->
            val nameIndex = reader.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIndex = reader.getColumnIndex(MediaStore.MediaColumns.SIZE)
            while (reader.moveToNext() && scannedRows < DUPLICATE_MEDIA_MAX_ROWS) {
                val name = if (nameIndex >= 0) {
                    reader.getString(nameIndex).orEmpty().trim()
                } else {
                    ""
                }
                val size = if (sizeIndex >= 0) {
                    reader.getLong(sizeIndex).coerceAtLeast(0L)
                } else {
                    0L
                }
                if (name.isBlank() || size < DUPLICATE_MEDIA_MIN_SIZE_BYTES) {
                    continue
                }
                val key = "${name.lowercase(Locale.US)}|$size"
                val aggregate = groups[key]
                if (aggregate == null) {
                    groups[key] = DuplicateAggregate(name = name, sizeBytes = size, count = 1)
                } else {
                    aggregate.count += 1
                }
                scannedRows += 1
            }
        }

        val duplicates = groups.values
            .filter { it.count > 1 }
            .sortedByDescending { (it.count - 1).toLong() * it.sizeBytes }
        val duplicateFileCount = duplicates.sumOf { it.count }
        val reclaimable = duplicates.sumOf { (it.count - 1).toLong() * it.sizeBytes }
        val examples = duplicates.take(4).map { aggregate ->
            "${aggregate.name} x${aggregate.count}"
        }

        return DuplicateMediaSnapshot(
            groupCount = duplicates.size,
            fileCount = duplicateFileCount,
            reclaimableBytes = reclaimable,
            examples = examples,
            mediaReadAccessGranted = true
        )
    }

    private fun collectInstallerRemnantSnapshot(
        context: Context,
        nowEpochMs: Long
    ): InstallerRemnantSnapshot {
        if (!hasMediaReadPermission(context)) {
            return InstallerRemnantSnapshot(
                count = 0,
                bytes = 0L,
                examples = emptyList(),
                mediaReadAccessGranted = false
            )
        }

        val thresholdSeconds = (nowEpochMs - INSTALLER_REMNANT_MIN_AGE_DAYS.toLong() * DAY_MS) / 1000L
        var scannedRows = 0
        var count = 0
        var bytes = 0L
        val examples = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                null,
                null,
                null
            )
        }.getOrNull()

        cursor?.use { reader ->
            val nameIndex = reader.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeIndex = reader.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val pathIndex = reader.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val modifiedIndex = reader.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (reader.moveToNext() && scannedRows < INSTALLER_REMNANT_MAX_ROWS) {
                val name = if (nameIndex >= 0) reader.getString(nameIndex).orEmpty().trim() else ""
                val size = if (sizeIndex >= 0) reader.getLong(sizeIndex).coerceAtLeast(0L) else 0L
                val relativePath = if (pathIndex >= 0) reader.getString(pathIndex).orEmpty().trim() else ""
                val dateModifiedSeconds = if (modifiedIndex >= 0) {
                    reader.getLong(modifiedIndex).coerceAtLeast(0L)
                } else {
                    0L
                }
                scannedRows += 1

                if (name.isBlank()) {
                    continue
                }
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext !in INSTALLER_ARCHIVE_EXTENSIONS) {
                    continue
                }
                if (dateModifiedSeconds > 0L && dateModifiedSeconds > thresholdSeconds) {
                    continue
                }
                val key = "${name.lowercase(Locale.US)}|$size|${relativePath.lowercase(Locale.US)}"
                if (!seen.add(key)) {
                    continue
                }
                count += 1
                bytes += size
                if (examples.size < 5) {
                    val pathLabel = relativePath.ifBlank { "unknown_path" }
                    examples += "$name ($pathLabel)"
                }
            }
        }

        return InstallerRemnantSnapshot(
            count = count,
            bytes = bytes.coerceAtLeast(0L),
            examples = examples,
            mediaReadAccessGranted = true
        )
    }

    private fun hasUsageStatsAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasMediaReadPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ).any { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun thirdPartyApps(context: Context): List<ApplicationInfo> {
        val pm = context.packageManager
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        }.getOrDefault(emptyList())
        return installed.filter { app ->
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystem && !isUpdatedSystem
        }
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

    private fun formatIsoTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
    }
}
