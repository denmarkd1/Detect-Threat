from __future__ import annotations

import json
from pathlib import Path
from typing import Any


WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
CONFIG_DIR = WORKSPACE_ROOT / "config"
DATA_DIR = WORKSPACE_ROOT / "data"
IMPORTS_DIR = WORKSPACE_ROOT / "imports"

SETTINGS_PATH = CONFIG_DIR / "workspace_settings.json"
SITE_PROFILES_PATH = CONFIG_DIR / "site_profiles.json"

VAULT_PATH = DATA_DIR / "vault.enc"
VAULT_META_PATH = DATA_DIR / "vault_meta.json"
ACTION_QUEUE_PATH = DATA_DIR / "action_queue.json"
LOCAL_BREACH_CACHE_PATH = DATA_DIR / "local_breach_cache.json"
SESSION_JOURNAL_PATH = DATA_DIR / "session_journal.jsonl"
WATCHDOG_STATUS_PATH = DATA_DIR / "runtime_watchdog_status.json"

SUPPORT_STATE_DIR = WORKSPACE_ROOT / "state" / "support"
SUPPORT_LOG_DIR = WORKSPACE_ROOT / "logs" / "support"
SUPPORT_TICKETS_PATH = SUPPORT_STATE_DIR / "tickets.json"
SUPPORT_TICKET_EVENTS_PATH = SUPPORT_LOG_DIR / "ticket_events.jsonl"
SUPPORT_CHAT_LOG_PATH = SUPPORT_LOG_DIR / "chat_sessions.jsonl"
SUPPORT_SUMMARY_PATH = SUPPORT_STATE_DIR / "summary.json"
SUPPORT_FEEDBACK_PATH = SUPPORT_STATE_DIR / "feedback.json"
SUPPORT_FEEDBACK_LOG_PATH = SUPPORT_LOG_DIR / "feedback_events.jsonl"


def default_settings() -> dict[str, Any]:
    return {
        "owners": [
            {
                "id": "parent",
                "display_name": "Parent",
                "email_patterns": [],
            },
            {
                "id": "son",
                "display_name": "Son",
                "email_patterns": [],
            },
        ],
        "priority_categories": ["email", "banking", "social", "developer", "other"],
        "require_confirm_before_delete": True,
        "allow_online_breach_checks": True,
        "auto_queue_rotation_when_compromised": False,
        "systems": {
            "D_T_System": {
                "src": "systems.D_T_System.src",
                "dt_autorun_if_available": True,
            }
        },
        "pricing": {
            "currency": "USD",
            "free_trial_days": 7,
            "target_discount_vs_competitor_avg_percent": 15.0,
            "competitor_average_monthly_usd": 3.21,
            "competitor_reference": [
                {
                    "name": "Bitwarden",
                    "monthly_usd": 1.65,
                    "source_url": "https://bitwarden.com/pricing/",
                    "observed_at": "2026-02-21",
                },
                {
                    "name": "1Password",
                    "monthly_usd": 2.99,
                    "source_url": "https://start.1password.com/sign-up/family?currency=USD",
                    "observed_at": "2026-02-21",
                },
                {
                    "name": "Dashlane",
                    "monthly_usd": 4.99,
                    "source_url": "https://support.dashlane.com/hc/en-us/articles/25851560554258-How-a-plan-change-affects-your-invoice",
                    "observed_at": "2026-02-21",
                },
            ],
            "plans": {
                "weekly_usd": 0.63,
                "monthly_usd": 2.73,
                "yearly_usd": 27.3,
                "yearly_months_charged": 10,
            },
            "referral_offer": {
                "enabled": True,
                "recommend_discount_percent": 10.0,
                "requires_recommendation": True,
            },
            "feature_access": {
                "free": {
                    "credential_records_limit": 40,
                    "queue_actions_limit": 5,
                    "breach_scans_per_day": 2,
                    "continuous_scan_enabled": False,
                    "overlay_assistant_enabled": False,
                    "rotation_queue_enabled": True,
                    "ai_hotline_enabled": False,
                },
                "paid": {
                    "credential_records_limit": -1,
                    "queue_actions_limit": -1,
                    "breach_scans_per_day": -1,
                    "continuous_scan_enabled": True,
                    "overlay_assistant_enabled": True,
                    "rotation_queue_enabled": True,
                    "ai_hotline_enabled": True,
                },
            },
            "lifetime_pro": {
                "enabled": False,
                "allowlisted_android_id_sha256": [],
            },
            "regional_pricing": [
                {
                    "region": "US",
                    "label": "United States",
                    "currency": "USD",
                    "usd_to_local_rate": 1.0,
                    "affordability_factor": 1.0,
                },
                {
                    "region": "GB",
                    "label": "United Kingdom",
                    "currency": "GBP",
                    "usd_to_local_rate": 0.79,
                    "affordability_factor": 1.2,
                },
                {
                    "region": "TH",
                    "label": "Thailand",
                    "currency": "THB",
                    "usd_to_local_rate": 35.8,
                    "affordability_factor": 0.55,
                },
                {
                    "region": "PH",
                    "label": "Philippines",
                    "currency": "PHP",
                    "usd_to_local_rate": 56.0,
                    "affordability_factor": 0.6,
                },
                {
                    "region": "EU",
                    "label": "European Union",
                    "currency": "EUR",
                    "usd_to_local_rate": 0.92,
                    "affordability_factor": 1.05,
                    "countries": [
                        "AT",
                        "BE",
                        "BG",
                        "CY",
                        "CZ",
                        "DE",
                        "DK",
                        "EE",
                        "ES",
                        "FI",
                        "FR",
                        "GR",
                        "HR",
                        "HU",
                        "IE",
                        "IT",
                        "LT",
                        "LU",
                        "LV",
                        "MT",
                        "NL",
                        "PL",
                        "PT",
                        "RO",
                        "SE",
                        "SI",
                        "SK",
                    ],
                },
                {
                    "region": "AU",
                    "label": "Australia",
                    "currency": "AUD",
                    "usd_to_local_rate": 1.53,
                    "affordability_factor": 1.05,
                },
                {
                    "region": "CA",
                    "label": "Canada",
                    "currency": "CAD",
                    "usd_to_local_rate": 1.36,
                    "affordability_factor": 1.03,
                },
                {
                    "region": "SG",
                    "label": "Singapore",
                    "currency": "SGD",
                    "usd_to_local_rate": 1.35,
                    "affordability_factor": 1.02,
                },
                {
                    "region": "JP",
                    "label": "Japan",
                    "currency": "JPY",
                    "usd_to_local_rate": 150.0,
                    "affordability_factor": 0.95,
                },
            ],
        },
        "watchdog": {
            "enable_windows_mount_scan_on_linux": True,
            "windows_mount_candidates": ["/mnt", "/media"],
            "windows_user_hints": [],
            "windows_profiles": [],
            "heartbeat_interval_seconds": 60,
        },
        "support": {
            "api_base_url": "http://127.0.0.1:8787",
            "feedback": {
                "create_ticket_for_feedback": True,
            },
        },
    }


def default_site_profiles() -> dict[str, Any]:
    return {
        "profiles": {
            "google.com": {
                "change_password_url": "https://myaccount.google.com/signinoptions/password",
                "delete_account_url": "https://myaccount.google.com/delete-services-or-account",
                "automation": {
                    "enabled": False,
                    "selectors": {
                        "current_password": "input[type='password'][name='Passwd']",
                        "new_password": "input[type='password'][name='password']",
                        "confirm_password": "input[type='password'][name='confirmation_password']",
                        "submit_button": "button[type='submit']",
                        "delete_confirm_button": "button[type='submit']",
                    },
                },
            },
            "facebook.com": {
                "change_password_url": "https://www.facebook.com/settings?tab=security",
                "delete_account_url": "https://www.facebook.com/help/delete_account",
                "automation": {
                    "enabled": False,
                    "selectors": {
                        "new_password": "",
                        "confirm_password": "",
                        "submit_button": "",
                        "delete_confirm_button": "",
                    },
                },
            },
            "github.com": {
                "change_password_url": "https://github.com/settings/security",
                "delete_account_url": "https://github.com/settings/admin",
                "automation": {
                    "enabled": False,
                    "selectors": {
                        "current_password": "input#password",
                        "new_password": "input#new_password",
                        "confirm_password": "input#confirm_password",
                        "submit_button": "button[type='submit']",
                        "delete_confirm_button": "button[type='submit']",
                    },
                },
            },
        }
    }


def ensure_workspace_files() -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    IMPORTS_DIR.mkdir(parents=True, exist_ok=True)
    SUPPORT_STATE_DIR.mkdir(parents=True, exist_ok=True)
    SUPPORT_LOG_DIR.mkdir(parents=True, exist_ok=True)

    if not SETTINGS_PATH.exists():
        SETTINGS_PATH.write_text(json.dumps(default_settings(), indent=2), encoding="utf-8")
    if not SITE_PROFILES_PATH.exists():
        SITE_PROFILES_PATH.write_text(json.dumps(default_site_profiles(), indent=2), encoding="utf-8")
    if not ACTION_QUEUE_PATH.exists():
        ACTION_QUEUE_PATH.write_text("[]\n", encoding="utf-8")
    if not LOCAL_BREACH_CACHE_PATH.exists():
        LOCAL_BREACH_CACHE_PATH.write_text("{}\n", encoding="utf-8")
    if not WATCHDOG_STATUS_PATH.exists():
        WATCHDOG_STATUS_PATH.write_text("{}\n", encoding="utf-8")
    if not SUPPORT_TICKETS_PATH.exists():
        SUPPORT_TICKETS_PATH.write_text("[]\n", encoding="utf-8")
    if not SUPPORT_SUMMARY_PATH.exists():
        SUPPORT_SUMMARY_PATH.write_text("{}\n", encoding="utf-8")
    if not SUPPORT_FEEDBACK_PATH.exists():
        SUPPORT_FEEDBACK_PATH.write_text("[]\n", encoding="utf-8")


def load_json(path: Path, fallback: Any) -> Any:
    if not path.exists():
        return fallback
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return fallback


def save_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def load_settings() -> dict[str, Any]:
    return load_json(SETTINGS_PATH, default_settings())


def load_site_profiles() -> dict[str, Any]:
    return load_json(SITE_PROFILES_PATH, default_site_profiles())
