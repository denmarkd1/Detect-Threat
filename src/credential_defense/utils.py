from __future__ import annotations

import hashlib
from datetime import datetime, timezone
from urllib.parse import urlparse


DEFAULT_CATEGORY_ORDER = ["email", "banking", "social", "developer", "other"]


def utc_now_iso() -> str:
    return datetime.now(tz=timezone.utc).isoformat()


def stable_record_id(owner: str, service: str, username: str) -> str:
    digest = hashlib.sha256(f"{owner}|{service}|{username}".encode("utf-8")).hexdigest()
    return digest[:24]


def normalize_url(value: str) -> str:
    raw = (value or "").strip()
    if not raw:
        return ""
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw
    return f"https://{raw}"


def domain_from_url(url: str) -> str:
    if not url:
        return ""
    parsed = urlparse(normalize_url(url))
    return parsed.netloc.lower().removeprefix("www.")


def classify_category(domain: str, service: str = "") -> str:
    label = f"{domain} {service}".lower()
    if any(token in label for token in ["gmail", "outlook", "yahoo", "proton", "mail", "email"]):
        return "email"
    if any(token in label for token in ["bank", "chase", "wellsfargo", "capitalone", "paypal", "amex"]):
        return "banking"
    if any(token in label for token in ["facebook", "instagram", "x.com", "twitter", "reddit", "tiktok", "snapchat"]):
        return "social"
    if any(token in label for token in ["github", "gitlab", "bitbucket", "aws", "azure", "cloudflare"]):
        return "developer"
    return "other"

