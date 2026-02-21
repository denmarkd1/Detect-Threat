package com.realyn.watchdog

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class FamilyControlAuditEntry(
    val recordedAtIso: String,
    val eventType: String,
    val actionCode: String,
    val outcome: String,
    val actorRole: String,
    val guardianContact: String,
    val reasonCode: String,
    val tokenExpiresAtIso: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("recordedAtIso", recordedAtIso)
            .put("eventType", eventType)
            .put("actionCode", actionCode)
            .put("outcome", outcome)
            .put("actorRole", actorRole)
            .put("guardianContact", guardianContact)
            .put("reasonCode", reasonCode)
            .put("tokenExpiresAtIso", tokenExpiresAtIso)
    }
}

object FamilyControlAuditLog {

    fun appendGuardianOverrideEvent(
        context: Context,
        actionCode: String,
        outcome: String,
        reasonCode: String,
        tokenExpiresAtEpochMs: Long = 0L
    ) {
        val profile = PrimaryIdentityStore.readProfile(context)
        val role = PrimaryIdentityStore.normalizeFamilyRole(profile.familyRole)
        val entry = FamilyControlAuditEntry(
            recordedAtIso = toIsoUtc(System.currentTimeMillis()),
            eventType = "guardian_override",
            actionCode = actionCode,
            outcome = outcome,
            actorRole = role,
            guardianContact = profile.guardianEmail,
            reasonCode = reasonCode,
            tokenExpiresAtIso = if (tokenExpiresAtEpochMs > 0L) toIsoUtc(tokenExpiresAtEpochMs) else ""
        )

        val file = File(context.filesDir, WatchdogConfig.FAMILY_CONTROL_AUDIT_FILE)
        file.appendText(entry.toJson().toString() + "\n")
    }

    private fun toIsoUtc(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMs))
    }
}
