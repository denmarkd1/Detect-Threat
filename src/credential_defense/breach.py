from __future__ import annotations

import hashlib
from typing import Any

import requests

from .config import LOCAL_BREACH_CACHE_PATH, load_json, save_json
from .models import CredentialRecord
from .passwords import is_weak_password
from .utils import utc_now_iso


PWNED_PASSWORDS_RANGE_ENDPOINT = "https://api.pwnedpasswords.com/range/"
HIBP_BREACHED_ACCOUNT_ENDPOINT = "https://haveibeenpwned.com/api/v3/breachedaccount/"


def pwned_password_count(password: str, timeout: int = 15) -> int:
    digest = hashlib.sha1(password.encode("utf-8")).hexdigest().upper()
    prefix = digest[:5]
    suffix = digest[5:]
    response = requests.get(
        f"{PWNED_PASSWORDS_RANGE_ENDPOINT}{prefix}",
        headers={"Add-Padding": "true", "User-Agent": "credential-defense-local"},
        timeout=timeout,
    )
    response.raise_for_status()
    for line in response.text.splitlines():
        if ":" not in line:
            continue
        line_suffix, count = line.strip().split(":", 1)
        if line_suffix.upper() == suffix:
            return int(count)
    return 0


def hibp_breaches_for_email(email: str, api_key: str, timeout: int = 15) -> list[dict[str, Any]]:
    response = requests.get(
        f"{HIBP_BREACHED_ACCOUNT_ENDPOINT}{email}",
        headers={
            "hibp-api-key": api_key,
            "user-agent": "credential-defense-local",
            "accept": "application/json",
        },
        timeout=timeout,
    )
    if response.status_code == 404:
        return []
    response.raise_for_status()
    payload = response.json()
    if isinstance(payload, list):
        return payload
    return []


def assess_record_risk(
    record: CredentialRecord,
    *,
    online_password_check: bool,
    online_email_check: bool,
    hibp_api_key: str | None,
) -> dict[str, Any]:
    risk_level = "low"
    reasons: list[str] = []
    pwned_count = 0
    email_breaches: list[dict[str, Any]] = []

    if is_weak_password(record.password):
        risk_level = "medium"
        reasons.append("Password policy weakness detected")

    if online_password_check and record.password:
        try:
            pwned_count = pwned_password_count(record.password)
            if pwned_count > 0:
                risk_level = "high"
                reasons.append(f"Password hash appears in {pwned_count} breach records")
        except requests.RequestException as exc:
            reasons.append(f"Password breach API unavailable: {exc}")

    if online_email_check and hibp_api_key and "@" in record.username:
        try:
            email_breaches = hibp_breaches_for_email(record.username, hibp_api_key)
            if email_breaches:
                risk_level = "high"
                reasons.append(f"Email found in {len(email_breaches)} breach events")
        except requests.RequestException as exc:
            reasons.append(f"Email breach API unavailable: {exc}")

    record.compromised = pwned_count > 0 or bool(email_breaches)
    record.breach_count = pwned_count + len(email_breaches)
    record.last_checked_at = utc_now_iso()

    cache = load_json(LOCAL_BREACH_CACHE_PATH, {})
    cache[record.record_id] = {
        "service": record.service,
        "username": record.username,
        "owner": record.owner,
        "checked_at": record.last_checked_at,
        "pwned_password_count": pwned_count,
        "email_breaches": [item.get("Name", "unknown") for item in email_breaches],
        "risk_level": risk_level,
        "reasons": reasons,
    }
    save_json(LOCAL_BREACH_CACHE_PATH, cache)

    return {
        "risk_level": risk_level,
        "reasons": reasons,
        "pwned_password_count": pwned_count,
        "email_breach_count": len(email_breaches),
        "email_breach_names": [item.get("Name", "unknown") for item in email_breaches],
    }

