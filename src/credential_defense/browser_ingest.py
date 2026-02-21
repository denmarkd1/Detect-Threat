from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .models import CredentialRecord
from .platform_watchdog import KNOWN_BROWSERS, build_runtime_status
from .utils import canonical_owner_id, classify_category, domain_from_url, stable_record_id, utc_now_iso


FIELD_ALIASES = {
    "url": ["url", "website", "login_uri", "origin", "hostname"],
    "username": ["username", "user", "email", "login"],
    "password": ["password", "pass"],
    "service": ["name", "title", "service", "site"],
    "notes": ["note", "notes"],
}


@dataclass
class ImportSummary:
    total_files: int
    parsed_rows: int
    imported_records: int
    skipped_rows: int
    sources: dict[str, int]


def discover_installed_browsers(settings: dict[str, Any] | None = None) -> dict[str, bool]:
    status = build_runtime_status(settings)
    summary = {name: False for name in KNOWN_BROWSERS}
    for browser, present in status.get("browser_presence", {}).items():
        if browser in summary:
            summary[browser] = bool(present)
    return summary


def _normalized_key(row: dict[str, Any], logical_key: str) -> str:
    aliases = FIELD_ALIASES[logical_key]
    lowered = {str(k).strip().lower(): str(v).strip() for k, v in row.items() if k is not None}
    for alias in aliases:
        if alias in lowered and lowered[alias]:
            return lowered[alias]
    return ""


def _owner_for_username(username: str, settings: dict[str, Any]) -> str:
    value = (username or "").lower()
    owners = settings.get("owners", [])
    for owner in owners:
        owner_id = canonical_owner_id(owner.get("id"))
        for pattern in owner.get("email_patterns", []):
            pattern_l = str(pattern).lower()
            if pattern_l and pattern_l in value:
                return owner_id
    return canonical_owner_id(owners[0]["id"]) if owners else "parent"


def _source_from_file(path: Path) -> str:
    stem = path.stem.lower()
    for browser in KNOWN_BROWSERS:
        if browser in stem:
            return browser
    return "csv_import"


def import_csv_exports(imports_dir: Path, settings: dict[str, Any]) -> tuple[list[CredentialRecord], ImportSummary]:
    records: list[CredentialRecord] = []
    parsed_rows = 0
    skipped_rows = 0
    source_counts: dict[str, int] = {}
    csv_files = sorted(imports_dir.glob("*.csv"))
    for csv_file in csv_files:
        source = _source_from_file(csv_file)
        source_counts[source] = source_counts.get(source, 0) + 1
        with csv_file.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                parsed_rows += 1
                url = _normalized_key(row, "url")
                username = _normalized_key(row, "username")
                password = _normalized_key(row, "password")
                service = _normalized_key(row, "service")
                notes = _normalized_key(row, "notes")
                if not (url and username and password):
                    skipped_rows += 1
                    continue
                domain = domain_from_url(url)
                normalized_service = service or (domain or "unknown_service")
                category = classify_category(domain, normalized_service)
                owner = _owner_for_username(username, settings)
                record_id = stable_record_id(owner, normalized_service, username)
                records.append(
                    CredentialRecord(
                        record_id=record_id,
                        owner=owner,
                        service=normalized_service,
                        url=url,
                        username=username,
                        password=password,
                        source=source,
                        category=category,
                        notes=notes,
                        updated_at=utc_now_iso(),
                    )
                )
    summary = ImportSummary(
        total_files=len(csv_files),
        parsed_rows=parsed_rows,
        imported_records=len(records),
        skipped_rows=skipped_rows,
        sources=source_counts,
    )
    return records, summary
