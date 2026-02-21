package com.realyn.watchdog

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class BreachCheckResult(
    val recordId: String,
    val service: String,
    val username: String,
    val pwnedCount: Int,
    val error: String?
)

object CredentialBreachChecker {

    private const val RANGE_ENDPOINT = "https://api.pwnedpasswords.com/range/"

    fun checkRecord(record: StoredCredential): BreachCheckResult {
        return try {
            val count = pwnedPasswordCount(record.currentPassword)
            BreachCheckResult(
                recordId = record.recordId,
                service = record.service,
                username = record.username,
                pwnedCount = count,
                error = null
            )
        } catch (exc: Exception) {
            BreachCheckResult(
                recordId = record.recordId,
                service = record.service,
                username = record.username,
                pwnedCount = 0,
                error = exc.message ?: exc.javaClass.simpleName
            )
        }
    }

    fun pwnedPasswordCount(password: String): Int {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .uppercase()

        val prefix = digest.take(5)
        val suffix = digest.drop(5)

        val connection = (URL("$RANGE_ENDPOINT$prefix").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Add-Padding", "true")
            setRequestProperty("User-Agent", "dt-guardian-android")
        }

        connection.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { row ->
                val parts = row.split(":", limit = 2)
                if (parts.size != 2) {
                    return@forEach
                }
                if (parts[0].trim().uppercase() == suffix) {
                    return parts[1].trim().toIntOrNull() ?: 0
                }
            }
        }

        return 0
    }
}
