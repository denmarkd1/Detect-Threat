package com.realyn.watchdog

object WatchdogConfig {
    const val PREFS_FILE = "dt_scanner_prefs"
    const val KEY_CONTINUOUS_MODE = "continuous_mode_enabled"
    const val KEY_LAST_ALERT_FINGERPRINT = "last_alert_fingerprint"
    const val KEY_LAST_GUARDIAN_ALERT_FINGERPRINT = "last_guardian_alert_fingerprint"

    const val BASELINE_FILE = "dt_scanner_baseline.json"
    const val HISTORY_FILE = "dt_scanner_history.log"
    const val AUDIT_LOG_FILE = "dt_scanner_audit.log"
    const val ROOT_AUDIT_LOG_FILE = "dt_root_defense.log"
    const val PLAY_INTEGRITY_VERDICT_FILE = "dt_play_integrity_verdict.json"
    const val GUARDIAN_ALERT_FEED_FILE = "dt_guardian_alert_feed.log"
    const val INCIDENT_STATE_FILE = "dt_incident_state.json"
    const val INCIDENT_EVENT_LOG_FILE = "dt_incident_events.log"
    const val COPILOT_AUDIT_LOG_FILE = "dt_copilot_audit.log"
    const val FAMILY_CONTROL_AUDIT_FILE = "dt_family_control_audit.log"
    const val WIFI_POSTURE_HISTORY_FILE = "dt_wifi_posture_history.log"
    const val PHISHING_TRIAGE_HISTORY_FILE = "dt_phishing_triage.log"
    const val CREDENTIAL_SECRET_VAULT_FILE = "credential_secret_vault.enc"
    const val CREDENTIAL_ACTION_QUEUE_FILE = "credential_action_queue.json"
    const val MEDIA_VAULT_INDEX_FILE = "media_vault_index.enc"
    const val MEDIA_VAULT_STORAGE_DIR = "media_vault"
    const val MEDIA_VAULT_PREVIEW_DIR = "media_vault_preview"

    const val ACTION_START_CONTINUOUS = "com.realyn.watchdog.action.START_CONTINUOUS"
    const val ACTION_STOP_CONTINUOUS = "com.realyn.watchdog.action.STOP_CONTINUOUS"
    const val ACTION_SHOW_OVERLAY = "com.realyn.watchdog.action.SHOW_OVERLAY"
    const val ACTION_HIDE_OVERLAY = "com.realyn.watchdog.action.HIDE_OVERLAY"
    const val EXTRA_OVERLAY_PASSWORD = "com.realyn.watchdog.extra.OVERLAY_PASSWORD"
    const val EXTRA_OVERLAY_TARGET_URL = "com.realyn.watchdog.extra.OVERLAY_TARGET_URL"

    const val FOREGROUND_CHANNEL_ID = "dt_scanner_foreground"
    const val ALERT_CHANNEL_ID = "dt_scanner_alerts"

    const val FOREGROUND_NOTIFICATION_ID = 4101
    const val ALERT_NOTIFICATION_ID = 4102

    const val SCAN_INTERVAL_MS = 5 * 60 * 1000L
}
