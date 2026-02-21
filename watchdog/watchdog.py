#!/usr/bin/env python3
"""Realyn Android watchdog.

Defensive scanner that uses ADB to collect snapshots, compare against a baseline,
and produce alerts for suspicious changes.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Set


ROOT_DIR = Path(__file__).resolve().parents[1]
STATE_DIR = ROOT_DIR / "state"
ALERT_DIR = ROOT_DIR / "alerts"
LOG_DIR = ROOT_DIR / "logs"
BASELINE_PATH = STATE_DIR / "baseline.json"
LAST_SCAN_PATH = STATE_DIR / "last_scan.json"
LOG_PATH = LOG_DIR / "watchdog.log"


WATCHED_PERMISSIONS = {
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.BODY_SENSORS",
    "android.permission.QUERY_ALL_PACKAGES",
}

SUSPICIOUS_PACKAGE_KEYWORDS = {
    "spy",
    "stalker",
    "track",
    "monitor",
    "stealth",
    "keylog",
    "mspy",
    "flexispy",
    "hoverwatch",
    "truthspy",
    "mobiletracker",
    "thetruthspy",
}


class ADBError(RuntimeError):
    """Raised when ADB interactions fail."""


@dataclass
class Alert:
    severity: str
    title: str
    details: str


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def ensure_dirs() -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    ALERT_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)


def log(message: str) -> None:
    ensure_dirs()
    line = f"{now_utc()} {message}"
    print(line)
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def resolve_adb() -> str:
    env_adb = os.environ.get("ADB_BIN")
    candidates = [
        env_adb if env_adb else "",
        str(ROOT_DIR / "tools/android/platform-tools/adb"),
        shutil.which("adb") or "",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).exists() and os.access(candidate, os.X_OK):
            return candidate
    raise ADBError(
        "adb not found. Run scripts/setup/install_platform_tools.sh first or set ADB_BIN."
    )


def run_adb(args: List[str], timeout: int = 60) -> str:
    adb_bin = resolve_adb()
    process = subprocess.run(
        [adb_bin, *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout,
    )
    if process.returncode != 0:
        stderr = process.stderr.strip()
        stdout = process.stdout.strip()
        msg = stderr if stderr else stdout
        raise ADBError(f"adb {' '.join(args)} failed: {msg}")
    return process.stdout


def parse_devices(output: str) -> List[Dict[str, str]]:
    devices: List[Dict[str, str]] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices attached"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial = parts[0]
        state = parts[1]
        extras = " ".join(parts[2:])
        devices.append({"serial": serial, "state": state, "extras": extras})
    return devices


def require_connected_device() -> Dict[str, str]:
    run_adb(["start-server"])
    devices = parse_devices(run_adb(["devices", "-l"]))
    live = [d for d in devices if d["state"] == "device"]
    if not live:
        raise ADBError("No authorized Android device connected.")
    return live[0]


def run_shell(command: str) -> str:
    return run_adb(["shell", "sh", "-c", command]).strip()


def getprop(name: str) -> str:
    return run_shell(f"getprop {name}")


def parse_pm_packages(output: str) -> List[str]:
    packages: List[str] = []
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith("package:"):
            continue
        packages.append(line.removeprefix("package:"))
    return sorted(set(packages))


def parse_accessibility_services(raw: str) -> List[str]:
    raw = raw.strip()
    if not raw or raw == "null":
        return []
    return sorted({x.strip() for x in raw.split(":") if x.strip()})


def parse_active_admins(raw: str) -> List[str]:
    # Matches "ComponentInfo{com.example/.AdminReceiver}" style tokens.
    pkgs = set(re.findall(r"ComponentInfo\{([A-Za-z0-9_.]+)\/", raw))
    if pkgs:
        return sorted(pkgs)
    # Fallback for dumpsys output that may include "package=com.example".
    pkgs = set(re.findall(r"package=([A-Za-z0-9_.]+)", raw))
    return sorted(pkgs)


def parse_granted_permissions_from_dumpsys(raw: str) -> Dict[str, Set[str]]:
    package_re = re.compile(r"^\s*Package\s+\[([A-Za-z0-9_.]+)\]")
    perm_re = re.compile(
        r"^\s*(android\.permission\.[A-Z0-9_]+):\s+granted=(true|false)"
    )
    current_pkg = ""
    result: Dict[str, Set[str]] = {}

    for line in raw.splitlines():
        pkg_match = package_re.match(line)
        if pkg_match:
            current_pkg = pkg_match.group(1)
            result.setdefault(current_pkg, set())
            continue

        if not current_pkg:
            continue

        perm_match = perm_re.match(line)
        if not perm_match:
            continue

        permission = perm_match.group(1)
        granted = perm_match.group(2) == "true"
        if granted:
            result[current_pkg].add(permission)

    return result


def get_security_settings() -> Dict[str, str]:
    checks = {
        "adb_enabled": "settings get global adb_enabled",
        "adb_wifi_enabled": "settings get global adb_wifi_enabled",
        "adb_tcp_port": "settings get global adb_tcp_port",
        "package_verifier_enable": "settings get global package_verifier_enable",
        "verifier_verify_adb_installs": "settings get global verifier_verify_adb_installs",
        "install_non_market_apps": "settings get secure install_non_market_apps",
    }
    data: Dict[str, str] = {}
    for key, cmd in checks.items():
        try:
            data[key] = run_shell(cmd)
        except ADBError:
            data[key] = "unavailable"
    return data


def collect_snapshot() -> Dict[str, object]:
    device = require_connected_device()
    third_party_packages = parse_pm_packages(run_shell("pm list packages -3"))

    accessibility = parse_accessibility_services(
        run_shell("settings get secure enabled_accessibility_services")
    )

    try:
        admins_raw = run_shell("dpm list active-admins")
        device_admins = parse_active_admins(admins_raw)
    except ADBError:
        # Fallback for devices that don't expose "dpm list active-admins" to shell.
        admins_raw = run_shell("dumpsys device_policy")
        device_admins = parse_active_admins(admins_raw)

    perms_dump = run_shell("dumpsys package")
    granted_perms = parse_granted_permissions_from_dumpsys(perms_dump)

    high_risk_permissions: Dict[str, List[str]] = {}
    for pkg in third_party_packages:
        pkg_perms = granted_perms.get(pkg, set())
        risky = sorted(pkg_perms.intersection(WATCHED_PERMISSIONS))
        if risky:
            high_risk_permissions[pkg] = risky

    suspicious_packages = sorted(
        [
            pkg
            for pkg in third_party_packages
            if any(keyword in pkg.lower() for keyword in SUSPICIOUS_PACKAGE_KEYWORDS)
        ]
    )

    snapshot: Dict[str, object] = {
        "captured_at": now_utc(),
        "device": {
            "serial": device["serial"],
            "model": getprop("ro.product.model"),
            "manufacturer": getprop("ro.product.manufacturer"),
            "android_release": getprop("ro.build.version.release"),
            "android_sdk": getprop("ro.build.version.sdk"),
        },
        "counts": {
            "third_party_packages": len(third_party_packages),
            "enabled_accessibility_services": len(accessibility),
            "active_device_admin_packages": len(device_admins),
            "packages_with_high_risk_permissions": len(high_risk_permissions),
        },
        "third_party_packages": third_party_packages,
        "enabled_accessibility_services": accessibility,
        "active_device_admin_packages": device_admins,
        "security_settings": get_security_settings(),
        "high_risk_permissions": high_risk_permissions,
        "suspicious_named_packages": suspicious_packages,
    }
    return snapshot


def read_json(path: Path) -> Dict[str, object]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, data: Dict[str, object]) -> None:
    ensure_dirs()
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)


def make_alerts(
    baseline: Dict[str, object], current: Dict[str, object], ignore_adb_debug: bool
) -> List[Alert]:
    alerts: List[Alert] = []

    base_pkgs = set(baseline.get("third_party_packages", []))
    curr_pkgs = set(current.get("third_party_packages", []))
    added = sorted(curr_pkgs - base_pkgs)
    removed = sorted(base_pkgs - curr_pkgs)

    if added:
        alerts.append(
            Alert(
                severity="medium",
                title="New third-party apps detected",
                details="\n".join(f"- {pkg}" for pkg in added),
            )
        )
    if removed:
        alerts.append(
            Alert(
                severity="low",
                title="Third-party apps removed since baseline",
                details="\n".join(f"- {pkg}" for pkg in removed),
            )
        )

    base_access = set(baseline.get("enabled_accessibility_services", []))
    curr_access = set(current.get("enabled_accessibility_services", []))
    new_access = sorted(curr_access - base_access)
    if new_access:
        alerts.append(
            Alert(
                severity="high",
                title="New accessibility services enabled",
                details="\n".join(f"- {svc}" for svc in new_access),
            )
        )

    base_admins = set(baseline.get("active_device_admin_packages", []))
    curr_admins = set(current.get("active_device_admin_packages", []))
    new_admins = sorted(curr_admins - base_admins)
    if new_admins:
        alerts.append(
            Alert(
                severity="high",
                title="New device admin apps enabled",
                details="\n".join(f"- {pkg}" for pkg in new_admins),
            )
        )

    baseline_settings = baseline.get("security_settings", {})
    current_settings = current.get("security_settings", {})
    for key, new_value in current_settings.items():
        old_value = baseline_settings.get(key)
        if old_value != new_value:
            if ignore_adb_debug and key in {"adb_enabled", "adb_wifi_enabled", "adb_tcp_port"}:
                continue
            alerts.append(
                Alert(
                    severity="medium",
                    title=f"Security setting changed: {key}",
                    details=f"- baseline: {old_value}\n- current: {new_value}",
                )
            )

    suspicious = current.get("suspicious_named_packages", [])
    if suspicious:
        alerts.append(
            Alert(
                severity="high",
                title="Potential stalkerware package names detected",
                details="\n".join(f"- {pkg}" for pkg in suspicious),
            )
        )

    baseline_risk = baseline.get("high_risk_permissions", {})
    current_risk = current.get("high_risk_permissions", {})
    for pkg, permissions in current_risk.items():
        old_permissions = set(baseline_risk.get(pkg, []))
        new_permissions = sorted(set(permissions) - old_permissions)
        if not new_permissions:
            continue
        severity = "high" if pkg in added else "medium"
        alerts.append(
            Alert(
                severity=severity,
                title=f"High-risk permissions newly observed: {pkg}",
                details="\n".join(f"- {perm}" for perm in new_permissions),
            )
        )

    return alerts


def write_alert_report(alerts: List[Alert], snapshot: Dict[str, object]) -> Path:
    ensure_dirs()
    ts = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    report_path = ALERT_DIR / f"alert-{ts}.md"
    high_count = sum(1 for a in alerts if a.severity == "high")
    medium_count = sum(1 for a in alerts if a.severity == "medium")
    low_count = sum(1 for a in alerts if a.severity == "low")

    lines = [
        "# Android Watchdog Alert Report",
        "",
        f"- Generated at: {snapshot.get('captured_at')}",
        f"- Device: {snapshot.get('device', {}).get('manufacturer', '')} "
        f"{snapshot.get('device', {}).get('model', '')}".strip(),
        f"- High: {high_count}, Medium: {medium_count}, Low: {low_count}",
        "",
    ]

    for alert in alerts:
        lines.extend(
            [
                f"## [{alert.severity.upper()}] {alert.title}",
                "",
                alert.details if alert.details else "- no details",
                "",
            ]
        )

    report_path.write_text("\n".join(lines), encoding="utf-8")
    return report_path


def create_baseline() -> int:
    log("[watchdog] collecting baseline snapshot")
    snapshot = collect_snapshot()
    write_json(BASELINE_PATH, snapshot)
    write_json(LAST_SCAN_PATH, snapshot)
    log(f"[watchdog] baseline saved to {BASELINE_PATH}")
    return 0


def run_scan(ignore_adb_debug: bool) -> int:
    ensure_dirs()
    if not BASELINE_PATH.exists():
        log("[watchdog] no baseline found, creating baseline first")
        return create_baseline()

    baseline = read_json(BASELINE_PATH)
    log("[watchdog] collecting current snapshot")
    current = collect_snapshot()
    write_json(LAST_SCAN_PATH, current)

    alerts = make_alerts(baseline, current, ignore_adb_debug=ignore_adb_debug)
    if not alerts:
        log("[watchdog] scan completed: no suspicious changes against baseline")
        return 0

    report_path = write_alert_report(alerts, current)
    high_count = sum(1 for a in alerts if a.severity == "high")
    medium_count = sum(1 for a in alerts if a.severity == "medium")
    low_count = sum(1 for a in alerts if a.severity == "low")
    log(
        "[watchdog] alerts generated "
        f"(high={high_count}, medium={medium_count}, low={low_count}): {report_path}"
    )
    return 2 if high_count > 0 else 1


def run_watch(interval: int, ignore_adb_debug: bool) -> int:
    log(f"[watchdog] watch mode started (interval={interval}s)")
    while True:
        try:
            run_scan(ignore_adb_debug=ignore_adb_debug)
        except Exception as exc:  # noqa: BLE001
            log(f"[watchdog] scan failed: {exc}")
        time.sleep(interval)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Realyn Android watchdog")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("baseline", help="collect and save baseline snapshot")

    scan_parser = subparsers.add_parser("scan", help="scan and compare to baseline")
    scan_parser.add_argument(
        "--ignore-adb-debug",
        action="store_true",
        help="ignore changes to adb_* settings when comparing baseline",
    )

    watch_parser = subparsers.add_parser("watch", help="run scan in a loop")
    watch_parser.add_argument(
        "--interval",
        type=int,
        default=300,
        help="seconds between scans (default: 300)",
    )
    watch_parser.add_argument(
        "--ignore-adb-debug",
        action="store_true",
        help="ignore changes to adb_* settings when comparing baseline",
    )
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    try:
        if args.command == "baseline":
            return create_baseline()
        if args.command == "scan":
            return run_scan(ignore_adb_debug=args.ignore_adb_debug)
        if args.command == "watch":
            return run_watch(
                interval=args.interval,
                ignore_adb_debug=args.ignore_adb_debug,
            )
    except ADBError as exc:
        log(f"[watchdog] adb error: {exc}")
        return 3
    except subprocess.TimeoutExpired:
        log("[watchdog] adb command timed out")
        return 4
    except KeyboardInterrupt:
        log("[watchdog] interrupted")
        return 130

    parser.print_help()
    return 1


if __name__ == "__main__":
    sys.exit(main())

