package com.realyn.watchdog

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DeepScanOptions(
    val includeStartupPersistenceSweep: Boolean,
    val includeStorageArtifactSweep: Boolean,
    val includeEmbeddedPathProbe: Boolean
) {
    fun enabledModules(): Set<DeepScanModule> {
        val modules = linkedSetOf<DeepScanModule>()
        if (includeStartupPersistenceSweep) {
            modules += DeepScanModule.STARTUP_PERSISTENCE
        }
        if (includeStorageArtifactSweep) {
            modules += DeepScanModule.STORAGE_ARTIFACTS
        }
        if (includeEmbeddedPathProbe) {
            modules += DeepScanModule.EMBEDDED_PATH_PROBE
        }
        return modules
    }
}

enum class DeepScanModule(val id: String, val label: String) {
    STARTUP_PERSISTENCE("startup_persistence", "Startup persistence"),
    STORAGE_ARTIFACTS("storage_artifacts", "Storage/media artifacts"),
    EMBEDDED_PATH_PROBE("embedded_path_probe", "Embedded path probe")
}

data class DeepScanFinding(
    val module: DeepScanModule,
    val severity: Severity,
    val score: Int,
    val title: String,
    val details: String
)

data class DeepScanResult(
    val scannedAtEpochMs: Long,
    val durationMs: Long,
    val modules: Set<DeepScanModule>,
    val findings: List<DeepScanFinding>,
    val packageRecordsScanned: Int,
    val storageRecordsScanned: Int
) {
    fun highCount(): Int = findings.count { it.severity == Severity.HIGH }
    fun mediumCount(): Int = findings.count { it.severity == Severity.MEDIUM }
    fun lowCount(): Int = findings.count { it.severity == Severity.LOW }
    fun infoCount(): Int = findings.count { it.severity == Severity.INFO }

    fun summaryLine(): String {
        if (findings.isEmpty()) {
            return "Deep scan: no additional threat indicators detected."
        }
        return "Deep scan findings: high=${highCount()} medium=${mediumCount()} low=${lowCount()} info=${infoCount()}"
    }

    fun formatReport(): String {
        val sb = StringBuilder()
        sb.appendLine("Deep Threat Sweep Report")
        sb.appendLine("Scanned: ${formatDisplayTime(scannedAtEpochMs)}")
        sb.appendLine("Duration: ${formatDuration(durationMs)}")
        sb.appendLine(
            "Modules: ${
                if (modules.isEmpty()) {
                    "none selected"
                } else {
                    modules.joinToString(", ") { it.label }
                }
            }"
        )
        sb.appendLine("Packages inspected: $packageRecordsScanned")
        sb.appendLine("Storage artifacts inspected: $storageRecordsScanned")
        sb.appendLine("Findings: high=${highCount()} medium=${mediumCount()} low=${lowCount()} info=${infoCount()}")
        sb.appendLine()

        if (findings.isEmpty()) {
            sb.appendLine("No additional deep-scan findings.")
        } else {
            findings.forEachIndexed { index, finding ->
                sb.appendLine("${index + 1}. [${finding.severity}] ${finding.title}")
                sb.appendLine("Module: ${finding.module.label} | Score: ${finding.score}")
                if (finding.details.isNotBlank()) {
                    sb.appendLine(finding.details)
                }
                sb.appendLine()
            }
        }
        return sb.toString().trim()
    }

    private fun formatDisplayTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
    }

    private fun formatDuration(elapsedMs: Long): String {
        val normalized = elapsedMs.coerceAtLeast(0L)
        val seconds = normalized / 1000.0
        return if (seconds < 10.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            "${normalized / 1000L}s"
        }
    }
}

fun interface DeepScanProgressCallback {
    fun onProgress(progress: Float, detail: String)
}

object DeepThreatScanner {
    private const val MAX_FINDINGS = 120
    private const val MAX_STORAGE_ROWS = 7000
    private const val MAX_ALL_FILES_ROWS = 3200
    private const val MAX_ALL_FILES_DEPTH = 4

    private val startupRiskPermissions = setOf(
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    )

    private val persistenceNameKeywords = setOf(
        "spy",
        "stalker",
        "monitor",
        "track",
        "keylog",
        "rat",
        "inject",
        "payload",
        "dropper"
    )

    private val suspiciousStorageExtensions = setOf(
        "apk",
        "xapk",
        "apkm",
        "dex",
        "jar",
        "so",
        "elf",
        "bin",
        "sh",
        "js"
    )

    private val packageArchiveExtensions = setOf(
        "apk",
        "xapk",
        "apkm"
    )

    private val allFilesSkipDirectoryNames = setOf(
        "android",
        "obb",
        ".thumbnails",
        ".trash",
        "trash",
        "cache",
        ".cache",
        "tmp",
        "temp"
    )

    private val suspiciousStorageKeywords = setOf(
        "keylog",
        "stalker",
        "spy",
        "rat",
        "payload",
        "dropper",
        "frida",
        "xposed",
        "magisk",
        "inject",
        "banklogin",
        "hook"
    )

    private data class StartupScanOutput(
        val packagesScanned: Int,
        val findings: List<DeepScanFinding>
    )

    private data class StorageScanOutput(
        val recordsScanned: Int,
        val findings: List<DeepScanFinding>
    )

    fun hasAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return Environment.isExternalStorageManager()
    }

    fun canOfferAllFilesAccessOnboarding(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        val pm = context.packageManager
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        }.getOrNull()
        val requested = packageInfo?.requestedPermissions?.toSet().orEmpty()
        return Manifest.permission.MANAGE_EXTERNAL_STORAGE in requested
    }

    fun run(
        context: Context,
        options: DeepScanOptions,
        progressCallback: DeepScanProgressCallback? = null
    ): DeepScanResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val scannedAtEpochMs = System.currentTimeMillis()
        val findings = mutableListOf<DeepScanFinding>()
        var packagesScanned = 0
        var storageRecordsScanned = 0
        val modules = options.enabledModules()
        val moduleSequence = buildList {
            if (options.includeStartupPersistenceSweep) {
                add(DeepScanModule.STARTUP_PERSISTENCE)
            }
            if (options.includeStorageArtifactSweep) {
                add(DeepScanModule.STORAGE_ARTIFACTS)
            }
            if (options.includeEmbeddedPathProbe) {
                add(DeepScanModule.EMBEDDED_PATH_PROBE)
            }
        }

        emitProgress(progressCallback, 0.02f, "deep: preparing module pipeline")
        if (moduleSequence.isEmpty()) {
            emitProgress(progressCallback, 0.92f, "deep: no optional modules selected")
        } else {
            val moduleCount = moduleSequence.size.toFloat()
            moduleSequence.forEachIndexed { index, module ->
                val segmentStart = index.toFloat() / moduleCount
                val segmentEnd = (index + 1).toFloat() / moduleCount
                val span = segmentEnd - segmentStart
                val segmentProgress: (Float) -> Unit = { local ->
                    val normalized = segmentStart + (span * local.coerceIn(0f, 1f))
                    emitProgress(
                        progressCallback,
                        0.04f + (normalized * 0.88f),
                        "deep.${module.id}: ${module.label}"
                    )
                }
                segmentProgress(0f)
                when (module) {
                    DeepScanModule.EMBEDDED_PATH_PROBE -> {
                        findings += scanEmbeddedPathIndicators()
                        segmentProgress(1f)
                    }
                    DeepScanModule.STARTUP_PERSISTENCE -> {
                        val startup = scanStartupPersistence(context) { local, detail ->
                            segmentProgress(local)
                            if (detail.isNotBlank()) {
                                emitProgress(
                                    progressCallback,
                                    0.04f + ((segmentStart + (span * local.coerceIn(0f, 1f))) * 0.88f),
                                    detail
                                )
                            }
                        }
                        packagesScanned = startup.packagesScanned
                        findings += startup.findings
                    }
                    DeepScanModule.STORAGE_ARTIFACTS -> {
                        val storage = scanStorageArtifacts(context) { local, detail ->
                            segmentProgress(local)
                            if (detail.isNotBlank()) {
                                emitProgress(
                                    progressCallback,
                                    0.04f + ((segmentStart + (span * local.coerceIn(0f, 1f))) * 0.88f),
                                    detail
                                )
                            }
                        }
                        storageRecordsScanned = storage.recordsScanned
                        findings += storage.findings
                    }
                }
            }
        }
        emitProgress(progressCallback, 0.96f, "deep: consolidating findings")

        val normalizedFindings = findings
            .distinctBy { "${it.module.id}|${it.title}|${it.details}" }
            .sortedWith(
                compareByDescending<DeepScanFinding> { severityRank(it.severity) }
                    .thenByDescending { it.score }
            )
            .take(MAX_FINDINGS)

        val result = DeepScanResult(
            scannedAtEpochMs = scannedAtEpochMs,
            durationMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
            modules = modules,
            findings = normalizedFindings,
            packageRecordsScanned = packagesScanned,
            storageRecordsScanned = storageRecordsScanned
        )
        appendHistory(context, result)
        emitProgress(progressCallback, 1f, "deep: complete")
        return result
    }

    private fun scanEmbeddedPathIndicators(): List<DeepScanFinding> {
        val findings = mutableListOf<DeepScanFinding>()
        val pathSignals = listOf(
            Triple("/data/adb/magisk", Severity.HIGH, "Magisk root runtime path"),
            Triple("/data/adb/modules", Severity.HIGH, "Magisk modules directory"),
            Triple("/system/framework/XposedBridge.jar", Severity.HIGH, "Xposed framework artifact"),
            Triple("/data/local/tmp/frida-server", Severity.HIGH, "Frida server artifact"),
            Triple("/system/bin/frida-server", Severity.HIGH, "Frida server binary"),
            Triple("/system/bin/su", Severity.HIGH, "su binary"),
            Triple("/system/xbin/su", Severity.HIGH, "su binary"),
            Triple("/sbin/su", Severity.HIGH, "su binary"),
            Triple("/su/bin/su", Severity.HIGH, "su binary"),
            Triple("/system/bin/busybox", Severity.MEDIUM, "busybox toolchain")
        )

        pathSignals.forEach { (path, severity, reason) ->
            if (File(path).exists()) {
                findings += DeepScanFinding(
                    module = DeepScanModule.EMBEDDED_PATH_PROBE,
                    severity = severity,
                    score = if (severity == Severity.HIGH) 92 else 58,
                    title = "Embedded threat indicator path detected",
                    details = "Path: $path\nSignal: $reason"
                )
            }
        }

        val suFromPath = runCatching {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "which su"))
                .inputStream
                .bufferedReader()
                .use { it.readText() }
                .trim()
        }.getOrDefault("")
        if (suFromPath.isNotBlank()) {
            findings += DeepScanFinding(
                module = DeepScanModule.EMBEDDED_PATH_PROBE,
                severity = Severity.HIGH,
                score = 90,
                title = "su binary resolved in shell PATH",
                details = "Resolved path: $suFromPath"
            )
        }

        return findings
    }

    private fun scanStartupPersistence(
        context: Context,
        progressCallback: DeepScanProgressCallback? = null
    ): StartupScanOutput {
        val pm = context.packageManager
        val apps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        }.getOrDefault(emptyList())
            .filter { app ->
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                !isSystem && !isUpdatedSystem && app.packageName != context.packageName
            }
        emitProgress(progressCallback, 0f)

        val findings = mutableListOf<DeepScanFinding>()
        if (apps.isEmpty()) {
            emitProgress(progressCallback, 1f, "deep.startup_persistence: no eligible third-party apps")
        }
        val emitEvery = (apps.size / 24).coerceAtLeast(1)
        apps.forEachIndexed { index, app ->
            val packageInfo = getPackageInfoForPersistence(pm, app.packageName)
            val requestedPermissions = packageInfo?.requestedPermissions
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()

            val hasBootReceiver = "android.permission.RECEIVE_BOOT_COMPLETED" in requestedPermissions
            val hasOverlay = "android.permission.SYSTEM_ALERT_WINDOW" in requestedPermissions
            val riskyPermissions = requestedPermissions.intersect(startupRiskPermissions - "android.permission.RECEIVE_BOOT_COMPLETED")
            val hasAccessibilityService = packageInfo?.services?.any { service ->
                service.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
            } == true
            val hasDeviceAdminReceiver = packageInfo?.receivers?.any { receiver ->
                receiver.permission == "android.permission.BIND_DEVICE_ADMIN"
            } == true
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(app.packageName).installingPackageName.orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(app.packageName).orEmpty()
                }
            }.getOrDefault("")
            val unknownInstaller = installer.isBlank() || installer == "com.android.shell"
            val packageLower = app.packageName.lowercase(Locale.US)
            val keywordHit = persistenceNameKeywords.firstOrNull { packageLower.contains(it) }

            var score = 0
            if (hasBootReceiver) {
                score += 28
            }
            if (hasAccessibilityService) {
                score += 32
            }
            if (hasDeviceAdminReceiver) {
                score += 26
            }
            if (hasOverlay) {
                score += 16
            }
            score += (riskyPermissions.size * 10).coerceAtMost(30)
            if (unknownInstaller) {
                score += 8
            }
            if (keywordHit != null) {
                score += 22
            }

            val severity = when {
                score >= 72 -> Severity.HIGH
                score >= 48 -> Severity.MEDIUM
                score >= 34 -> Severity.LOW
                else -> null
            }
            if (severity != null) {
                val appLabel = runCatching {
                    pm.getApplicationLabel(app).toString().trim().ifBlank { app.packageName }
                }.getOrDefault(app.packageName)

                val signalNotes = mutableListOf<String>()
                if (hasBootReceiver) {
                    signalNotes += "boot persistence permission"
                }
                if (hasAccessibilityService) {
                    signalNotes += "accessibility service component"
                }
                if (hasDeviceAdminReceiver) {
                    signalNotes += "device-admin receiver component"
                }
                if (hasOverlay) {
                    signalNotes += "overlay permission"
                }
                if (riskyPermissions.isNotEmpty()) {
                    signalNotes += "high-risk permissions=${riskyPermissions.take(6).joinToString(", ")}"
                }
                if (unknownInstaller) {
                    signalNotes += "installer source unavailable/unknown"
                }
                if (keywordHit != null) {
                    signalNotes += "suspicious package keyword='$keywordHit'"
                }

                findings += DeepScanFinding(
                    module = DeepScanModule.STARTUP_PERSISTENCE,
                    severity = severity,
                    score = score.coerceIn(0, 100),
                    title = "Persistence-capable app profile: $appLabel",
                    details = "Package: ${app.packageName}\nSignals: ${signalNotes.joinToString("; ")}"
                )
            }
            val processed = index + 1
            if (processed % emitEvery == 0 || processed == apps.size) {
                emitProgress(
                    progressCallback,
                    processed.toFloat() / apps.size.toFloat(),
                    "deep.startup_persistence: ${app.packageName}"
                )
            }
        }

        return StartupScanOutput(
            packagesScanned = apps.size,
            findings = findings
        )
    }

    private fun getPackageInfoForPersistence(pm: PackageManager, packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val flags = (
                    PackageManager.GET_PERMISSIONS.toLong() or
                        PackageManager.GET_SERVICES.toLong() or
                        PackageManager.GET_RECEIVERS.toLong()
                    )
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_PERMISSIONS or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scanStorageArtifacts(
        context: Context,
        progressCallback: DeepScanProgressCallback? = null
    ): StorageScanOutput {
        val findings = mutableListOf<DeepScanFinding>()
        var recordsScanned = 0
        var permissionIssueNoted = false
        emitProgress(progressCallback, 0f)
        val allFilesGranted = hasAllFilesAccess(context)

        if (!hasStorageReadCapability(context)) {
            findings += DeepScanFinding(
                module = DeepScanModule.STORAGE_ARTIFACTS,
                severity = Severity.INFO,
                score = 0,
                title = "Storage sweep limited by permission",
                details = "Media/storage read permission is not fully granted. Deep storage visibility is reduced."
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !allFilesGranted &&
            canOfferAllFilesAccessOnboarding(context)
        ) {
            findings += DeepScanFinding(
                module = DeepScanModule.STORAGE_ARTIFACTS,
                severity = Severity.INFO,
                score = 0,
                title = "Optional all-files storage coverage is not enabled",
                details = "Enable All files access to increase deep storage sweep coverage across more directories."
            )
        }

        val uris = buildList {
            add(MediaStore.Files.getContentUri("external"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }

        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE
        )

        val inspectedKeys = linkedSetOf<String>()
        uris.forEachIndexed { uriIndex, uri ->
            if (recordsScanned >= MAX_STORAGE_ROWS) {
                return@forEachIndexed
            }
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext() && recordsScanned < MAX_STORAGE_ROWS) {
                        val fileName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                        val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                        val relativePath = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                        val sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex).coerceAtLeast(0L) else 0L
                        val dedupeKey = "${relativePath.lowercase(Locale.US)}|${fileName.lowercase(Locale.US)}|$sizeBytes"
                        if (!inspectedKeys.add(dedupeKey)) {
                            continue
                        }
                        recordsScanned += 1
                        if (recordsScanned % 240 == 0) {
                            emitProgress(
                                progressCallback,
                                ((recordsScanned.toFloat() / MAX_STORAGE_ROWS.toFloat()) * 0.56f)
                                    .coerceIn(0.03f, 0.56f),
                                "deep.storage_artifacts: ${relativePath.trim()}/${fileName.trim()}"
                            )
                        }
                        val finding = analyzeStorageArtifact(fileName, mimeType, relativePath, sizeBytes)
                        if (finding != null) {
                            findings += finding
                        }
                    }
                }
            } catch (_: SecurityException) {
                if (!permissionIssueNoted) {
                    findings += DeepScanFinding(
                        module = DeepScanModule.STORAGE_ARTIFACTS,
                        severity = Severity.INFO,
                        score = 0,
                        title = "Storage/media query blocked by platform permission",
                        details = "One or more storage providers denied query access. Grant media permissions for broader sweep coverage."
                    )
                    permissionIssueNoted = true
                }
            }
            val coarse = (uriIndex + 1).toFloat() / uris.size.toFloat()
            emitProgress(
                progressCallback,
                (coarse * 0.56f).coerceIn(0.03f, 0.56f),
                "deep.storage_artifacts: media source ${uri.lastPathSegment.orEmpty()}"
            )
        }
        emitProgress(progressCallback, 0.58f, "deep.storage_artifacts: optional all-files sweep check")

        if (allFilesGranted) {
            val allFilesSweep = scanAllFilesStorageArtifacts { local, detail ->
                emitProgress(
                    progressCallback,
                    0.58f + (local.coerceIn(0f, 1f) * 0.40f),
                    if (detail.isBlank()) "deep.storage_artifacts: all-files traversal" else detail
                )
            }
            recordsScanned += allFilesSweep.recordsScanned
            findings += allFilesSweep.findings
        }
        emitProgress(progressCallback, 1f, "deep.storage_artifacts: complete")

        return StorageScanOutput(
            recordsScanned = recordsScanned,
            findings = findings
        )
    }

    private fun scanAllFilesStorageArtifacts(
        progressCallback: DeepScanProgressCallback? = null
    ): StorageScanOutput {
        val findings = mutableListOf<DeepScanFinding>()
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        )
            .distinctBy { it.absolutePath }
            .filter { directory ->
                runCatching { directory.exists() && directory.canRead() }.getOrDefault(false)
            }

        if (roots.isEmpty()) {
            emitProgress(progressCallback, 1f, "deep.storage_artifacts: no readable all-files roots")
            return StorageScanOutput(recordsScanned = 0, findings = findings)
        }

        val queue = ArrayDeque<Pair<File, Int>>()
        roots.forEach { root -> queue.addLast(root to 0) }
        val visited = mutableSetOf<String>()
        var recordsScanned = 0

        while (queue.isNotEmpty() && recordsScanned < MAX_ALL_FILES_ROWS) {
            val (node, depth) = queue.removeFirst()
            val pathKey = node.absolutePath.lowercase(Locale.US)
            if (!visited.add(pathKey)) {
                continue
            }
            val existsAndReadable = runCatching { node.exists() && node.canRead() }.getOrDefault(false)
            if (!existsAndReadable) {
                continue
            }
            val isDirectory = runCatching { node.isDirectory }.getOrDefault(false)
            if (isDirectory) {
                if (depth >= MAX_ALL_FILES_DEPTH) {
                    continue
                }
                val dirName = node.name.lowercase(Locale.US)
                if (dirName in allFilesSkipDirectoryNames) {
                    continue
                }
                val children = runCatching {
                    node.listFiles()
                        ?.sortedBy { child -> child.name.lowercase(Locale.US) }
                        .orEmpty()
                }.getOrDefault(emptyList())
                children.forEach { child ->
                    if (recordsScanned >= MAX_ALL_FILES_ROWS) {
                        return@forEach
                    }
                    queue.addLast(child to (depth + 1))
                }
                continue
            }

            recordsScanned += 1
            if (recordsScanned % 120 == 0 || recordsScanned == MAX_ALL_FILES_ROWS) {
                emitProgress(
                    progressCallback,
                    (recordsScanned.toFloat() / MAX_ALL_FILES_ROWS.toFloat()).coerceIn(0f, 1f),
                    "deep.storage_artifacts: ${node.absolutePath}"
                )
            }
            val fileName = node.name.orEmpty()
            val relativePath = node.parentFile?.absolutePath.orEmpty()
            val sizeBytes = runCatching { node.length() }.getOrDefault(0L).coerceAtLeast(0L)
            val finding = analyzeStorageArtifact(
                fileName = fileName,
                mimeType = "",
                relativePath = relativePath,
                sizeBytes = sizeBytes
            )
            if (finding != null) {
                findings += finding
            }
        }
        emitProgress(progressCallback, 1f, "deep.storage_artifacts: all-files traversal complete")
        return StorageScanOutput(recordsScanned = recordsScanned, findings = findings)
    }

    private fun hasStorageReadCapability(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val mediaPerms = listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            mediaPerms.any { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun analyzeStorageArtifact(
        fileName: String,
        mimeType: String,
        relativePath: String,
        sizeBytes: Long
    ): DeepScanFinding? {
        val normalizedName = fileName.trim()
        if (normalizedName.isBlank()) {
            return null
        }

        val lowerName = normalizedName.lowercase(Locale.US)
        val lowerPath = relativePath.trim().lowercase(Locale.US)
        val ext = lowerName.substringAfterLast('.', "")
        val keywordHit = suspiciousStorageKeywords.firstOrNull { keyword ->
            lowerName.contains(keyword) || lowerPath.contains(keyword)
        }
        val inDownloadPath = lowerPath.contains("download")
        val hiddenName = normalizedName.startsWith(".")
        val isSuspiciousExt = ext in suspiciousStorageExtensions
        val isPackageArchive = ext in packageArchiveExtensions
        val mimeLower = mimeType.trim().lowercase(Locale.US)
        val apkMime = mimeLower.contains("android.package-archive")
        val scriptMime = mimeLower.contains("shellscript") || mimeLower.contains("javascript")
        val extensionWeight = when {
            ext.isBlank() -> 0
            isPackageArchive -> 8
            isSuspiciousExt -> 24
            else -> 0
        }
        val suspiciousDownloadPlacement = inDownloadPath &&
            (keywordHit != null || hiddenName || (isSuspiciousExt && !isPackageArchive) || scriptMime)
        val installerOnlySignal = isPackageArchive &&
            keywordHit == null &&
            !hiddenName &&
            !scriptMime &&
            !lowerPath.contains("/android/data/")

        if (installerOnlySignal) {
            return null
        }

        var score = extensionWeight
        if (keywordHit != null) {
            score += 36
        }
        if (hiddenName) {
            score += 14
        }
        if (suspiciousDownloadPlacement) {
            score += 8
        }
        if (apkMime) {
            score += 8
        }
        if (scriptMime) {
            score += 20
        }
        if (lowerPath.contains("/android/data/") && (keywordHit != null || hiddenName || scriptMime)) {
            score += 10
        }
        if (sizeBytes > 75L * 1024L * 1024L && (keywordHit != null || scriptMime || (isSuspiciousExt && !isPackageArchive))) {
            score += 8
        }

        val severity = when {
            score >= 78 -> Severity.HIGH
            score >= 56 -> Severity.MEDIUM
            score >= 40 -> Severity.LOW
            else -> null
        } ?: return null

        val reasons = mutableListOf<String>()
        if (isSuspiciousExt) {
            reasons += if (isPackageArchive) {
                "package_archive_extension=$ext"
            } else {
                "suspicious_extension=$ext"
            }
        }
        if (keywordHit != null) {
            reasons += "keyword_hit=$keywordHit"
        }
        if (hiddenName) {
            reasons += "hidden_filename"
        }
        if (suspiciousDownloadPlacement) {
            reasons += "download_path"
        }
        if (apkMime) {
            reasons += "apk_mime"
        }
        if (scriptMime) {
            reasons += "script_mime"
        }

        return DeepScanFinding(
            module = DeepScanModule.STORAGE_ARTIFACTS,
            severity = severity,
            score = score.coerceIn(0, 100),
            title = "Suspicious storage artifact: $normalizedName",
            details = listOf(
                "Path: ${relativePath.ifBlank { "unknown_path" }}",
                "Mime: ${mimeType.ifBlank { "unknown" }}",
                "Size: ${formatBytes(sizeBytes)}",
                "Signals: ${reasons.joinToString(", ")}"
            ).joinToString("\n")
        )
    }

    private fun appendHistory(context: Context, result: DeepScanResult) {
        val file = File(context.filesDir, WatchdogConfig.DEEP_SCAN_HISTORY_FILE)
        val row = JSONObject()
            .put("scannedAtEpochMs", result.scannedAtEpochMs)
            .put("scannedAtIso", formatIsoTime(result.scannedAtEpochMs))
            .put("durationMs", result.durationMs)
            .put("modules", JSONArray(result.modules.map { it.id }.sorted()))
            .put("packageRecordsScanned", result.packageRecordsScanned)
            .put("storageRecordsScanned", result.storageRecordsScanned)
            .put("summary", result.summaryLine())
            .put("findingCount", result.findings.size)
            .put("highCount", result.highCount())
            .put("mediumCount", result.mediumCount())
            .put("lowCount", result.lowCount())
            .put("infoCount", result.infoCount())

        val findingsArray = JSONArray()
        result.findings.forEach { finding ->
            findingsArray.put(
                JSONObject()
                    .put("module", finding.module.id)
                    .put("severity", finding.severity.name)
                    .put("score", finding.score)
                    .put("title", finding.title)
                    .put("details", finding.details)
            )
        }
        row.put("findings", findingsArray)
        file.appendText(row.toString() + "\n")
    }

    private fun severityRank(severity: Severity): Int {
        return when (severity) {
            Severity.HIGH -> 4
            Severity.MEDIUM -> 3
            Severity.LOW -> 2
            Severity.INFO -> 1
        }
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L).toDouble()
        return when {
            value >= 1024.0 * 1024.0 * 1024.0 ->
                String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))

            value >= 1024.0 * 1024.0 ->
                String.format(Locale.US, "%.2f MB", value / (1024.0 * 1024.0))

            value >= 1024.0 ->
                String.format(Locale.US, "%.2f KB", value / 1024.0)

            else -> "${bytes.coerceAtLeast(0L)} B"
        }
    }

    private fun formatIsoTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
    }

    private fun emitProgress(
        progressCallback: DeepScanProgressCallback?,
        progress: Float,
        detail: String = ""
    ) {
        progressCallback?.onProgress(progress.coerceIn(0f, 1f), detail.trim())
    }
}
