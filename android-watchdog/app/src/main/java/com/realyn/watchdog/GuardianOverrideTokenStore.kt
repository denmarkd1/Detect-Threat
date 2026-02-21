package com.realyn.watchdog

import android.content.Context
import org.json.JSONObject
import java.util.Locale

data class GuardianOverrideToken(
    val actionCode: String,
    val reasonCode: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("actionCode", actionCode)
            .put("reasonCode", reasonCode)
            .put("issuedAtEpochMs", issuedAtEpochMs)
            .put("expiresAtEpochMs", expiresAtEpochMs)
    }

    companion object {
        fun fromJson(payload: JSONObject): GuardianOverrideToken {
            return GuardianOverrideToken(
                actionCode = payload.optString("actionCode").trim(),
                reasonCode = payload.optString("reasonCode").trim(),
                issuedAtEpochMs = payload.optLong("issuedAtEpochMs", 0L).coerceAtLeast(0L),
                expiresAtEpochMs = payload.optLong("expiresAtEpochMs", 0L).coerceAtLeast(0L)
            )
        }
    }
}

object GuardianOverrideTokenStore {

    private const val KEY_TOKEN_PREFIX = "guardian_override_token_"

    fun issueToken(
        context: Context,
        actionCode: String,
        reasonCode: String,
        ttlSeconds: Int
    ): GuardianOverrideToken {
        val now = System.currentTimeMillis()
        val safeTtlSeconds = ttlSeconds.coerceIn(30, 15 * 60)
        val token = GuardianOverrideToken(
            actionCode = actionCode,
            reasonCode = reasonCode,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + safeTtlSeconds * 1000L
        )
        saveToken(context, token)
        return token
    }

    fun readValidToken(context: Context, actionCode: String): GuardianOverrideToken? {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(tokenKey(actionCode), null).orEmpty()
        if (raw.isBlank()) {
            return null
        }

        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val token = runCatching { GuardianOverrideToken.fromJson(payload) }.getOrNull() ?: return null
        if (token.actionCode != actionCode) {
            return null
        }
        if (token.expiresAtEpochMs <= System.currentTimeMillis()) {
            clearToken(context, actionCode)
            return null
        }
        return token
    }

    fun clearToken(context: Context, actionCode: String) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().remove(tokenKey(actionCode)).apply()
    }

    private fun saveToken(context: Context, token: GuardianOverrideToken) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(tokenKey(token.actionCode), token.toJson().toString())
            .apply()
    }

    private fun tokenKey(actionCode: String): String {
        return KEY_TOKEN_PREFIX + actionCode.trim().lowercase(Locale.US)
    }
}
