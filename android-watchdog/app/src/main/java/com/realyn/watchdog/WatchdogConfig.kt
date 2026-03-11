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
    const val DEEP_SCAN_HISTORY_FILE = "dt_deep_scan_history.log"
    const val PLAY_INTEGRITY_VERDICT_FILE = "dt_play_integrity_verdict.json"
    const val GUARDIAN_ALERT_FEED_FILE = "dt_guardian_alert_feed.log"
    const val INCIDENT_STATE_FILE = "dt_incident_state.json"
    const val INCIDENT_EVENT_LOG_FILE = "dt_incident_events.log"
    const val COPILOT_AUDIT_LOG_FILE = "dt_copilot_audit.log"
    const val ADAPTIVE_GUIDE_AUDIT_LOG_FILE = "dt_adaptive_guide_audit.log"
    const val FAMILY_CONTROL_AUDIT_FILE = "dt_family_control_audit.log"
    const val WIFI_POSTURE_HISTORY_FILE = "dt_wifi_posture_history.log"
    const val PHISHING_TRIAGE_HISTORY_FILE = "dt_phishing_triage.log"
    const val INTEGRATION_MESH_AUDIT_FILE = "dt_integration_mesh_audit.log"
    const val INTEGRATION_MESH_CONSENT_ARTIFACT_FILE = "dt_integration_mesh_consent.log"
    const val KPI_TELEMETRY_FILE = "dt_kpi_telemetry.log"
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
    const val ACTION_SHOW_INCIDENT_OVERLAY = "com.realyn.watchdog.action.SHOW_INCIDENT_OVERLAY"
    const val ACTION_HIDE_INCIDENT_OVERLAY = "com.realyn.watchdog.action.HIDE_INCIDENT_OVERLAY"
    const val ACTION_SHOW_GUIDE_FALLBACK = "com.realyn.watchdog.action.SHOW_GUIDE_FALLBACK"
    const val ACTION_HIDE_GUIDE_FALLBACK = "com.realyn.watchdog.action.HIDE_GUIDE_FALLBACK"
    const val ACTION_ADAPTIVE_GUIDE_ANALYSIS_EVENT = "com.realyn.watchdog.action.ADAPTIVE_GUIDE_ANALYSIS_EVENT"
    const val EXTRA_INCIDENT_OVERLAY_TITLE = "com.realyn.watchdog.extra.INCIDENT_OVERLAY_TITLE"
    const val EXTRA_INCIDENT_OVERLAY_STEPS = "com.realyn.watchdog.extra.INCIDENT_OVERLAY_STEPS"
    const val EXTRA_INCIDENT_OVERLAY_COMPACT_MODE = "com.realyn.watchdog.extra.INCIDENT_OVERLAY_COMPACT_MODE"
    const val EXTRA_INCIDENT_OVERLAY_RETURN_ACTIVITY = "com.realyn.watchdog.extra.INCIDENT_OVERLAY_RETURN_ACTIVITY"
    const val EXTRA_INCIDENT_OVERLAY_ADAPTIVE_FLOW_ID = "com.realyn.watchdog.extra.INCIDENT_OVERLAY_ADAPTIVE_FLOW_ID"
    const val EXTRA_GUIDE_FALLBACK_NOTIFICATION_ID = "com.realyn.watchdog.extra.GUIDE_FALLBACK_NOTIFICATION_ID"
    const val EXTRA_GUIDE_FALLBACK_TITLE = "com.realyn.watchdog.extra.GUIDE_FALLBACK_TITLE"
    const val EXTRA_GUIDE_FALLBACK_CURRENT_TARGET = "com.realyn.watchdog.extra.GUIDE_FALLBACK_CURRENT_TARGET"
    const val EXTRA_GUIDE_FALLBACK_RETURN_ACTIVITY = "com.realyn.watchdog.extra.GUIDE_FALLBACK_RETURN_ACTIVITY"
    const val EXTRA_GUIDE_FALLBACK_SCREEN_MODE = "com.realyn.watchdog.extra.GUIDE_FALLBACK_SCREEN_MODE"
    const val EXTRA_ADAPTIVE_GUIDE_ANALYSIS_FLOW_ID = "com.realyn.watchdog.extra.ADAPTIVE_GUIDE_ANALYSIS_FLOW_ID"
    const val EXTRA_ADAPTIVE_GUIDE_ANALYSIS_STATE_ID = "com.realyn.watchdog.extra.ADAPTIVE_GUIDE_ANALYSIS_STATE_ID"
    const val EXTRA_ADAPTIVE_GUIDE_ANALYSIS_STATUS = "com.realyn.watchdog.extra.ADAPTIVE_GUIDE_ANALYSIS_STATUS"
    const val EXTRA_ADAPTIVE_GUIDE_ANALYSIS_DETAIL = "com.realyn.watchdog.extra.ADAPTIVE_GUIDE_ANALYSIS_DETAIL"
    const val EXTRA_ADAPTIVE_GUIDE_CAPTURE_RESULT_CODE = "com.realyn.watchdog.extra.ADAPTIVE_GUIDE_CAPTURE_RESULT_CODE"
    const val EXTRA_ADAPTIVE_GUIDE_CAPTURE_DATA_INTENT = "com.realyn.watchdog.extra.ADAPTIVE_GUIDE_CAPTURE_DATA_INTENT"

    const val FOREGROUND_CHANNEL_ID = "dt_scanner_foreground"
    const val ALERT_CHANNEL_ID = "dt_scanner_alerts"
    const val INCIDENT_GUIDE_CHANNEL_ID = "dt_incident_guide"
    const val GUIDE_FALLBACK_CHANNEL_ID = "dt_guide_fallback"
    const val ADAPTIVE_GUIDE_CAPTURE_CHANNEL_ID = "dt_adaptive_guide_capture"

    const val FOREGROUND_NOTIFICATION_ID = 4101
    const val ALERT_NOTIFICATION_ID = 4102
    const val INCIDENT_GUIDE_NOTIFICATION_ID = 4103
    const val ADAPTIVE_GUIDE_CAPTURE_NOTIFICATION_ID = 4104
    const val FOUNDATION_GUIDE_FALLBACK_NOTIFICATION_ID = 4105
    const val INCIDENT_GUIDE_FALLBACK_NOTIFICATION_ID = 4106

    const val SCAN_INTERVAL_MS = 5 * 60 * 1000L
}
