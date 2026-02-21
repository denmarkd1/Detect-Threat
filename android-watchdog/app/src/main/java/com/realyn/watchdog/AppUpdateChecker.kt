package com.realyn.watchdog

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateStatus {
    DISABLED,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    ERROR
}

data class UpdateCheckResult(
    val status: UpdateStatus,
    val message: String,
    val downloadUrl: String? = null,
    val releaseNotes: String? = null
)

object AppUpdateChecker {

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val manifestUrl = context.getString(R.string.update_manifest_url).trim()
        if (manifestUrl.isBlank()) {
            return@withContext UpdateCheckResult(
                status = UpdateStatus.DISABLED,
                message = "Update manifest URL is not configured."
            )
        }

        val manifest = runCatching { fetchManifest(manifestUrl) }.getOrElse { exc ->
            return@withContext UpdateCheckResult(
                status = UpdateStatus.ERROR,
                message = "Update check failed: ${exc.message ?: "unknown error"}"
            )
        }

        val latestVersionCode = manifest.optLong("latestVersionCode", -1)
        val latestVersionName = manifest.optString("latestVersionName", "unknown")
        val downloadUrl = manifest.optString("downloadUrl", "").ifBlank { null }
        val releaseNotes = manifest.optString("releaseNotes", "").ifBlank { null }

        if (latestVersionCode < 0) {
            return@withContext UpdateCheckResult(
                status = UpdateStatus.ERROR,
                message = "Invalid update manifest: latestVersionCode is missing."
            )
        }

        val installed = installedVersionCode(context)
        if (latestVersionCode > installed) {
            return@withContext UpdateCheckResult(
                status = UpdateStatus.UPDATE_AVAILABLE,
                message = "Update available: v$latestVersionName (code=$latestVersionCode).",
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes
            )
        }

        return@withContext UpdateCheckResult(
            status = UpdateStatus.UP_TO_DATE,
            message = "App is up to date (installed code=$installed)."
        )
    }

    private fun fetchManifest(manifestUrl: String): JSONObject {
        val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun installedVersionCode(context: Context): Long {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}
