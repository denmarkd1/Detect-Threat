#!/usr/bin/env python3
"""Realyn Android watchdog.

Defensive scanner that uses ADB to collect snapshots, compare against baselines,
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
from typing import Any, Dict, List, Set, Tuple


ROOT_DIR = Path(__file__).resolve().parents[1]
STATE_DIR = ROOT_DIR / "state"
ALERT_DIR = ROOT_DIR / "alerts"
LOG_DIR = ROOT_DIR / "logs"
BASELINE_PATH = STATE_DIR / "baseline.json"
LAST_SCAN_PATH = STATE_DIR / "last_scan.json"
LOG_PATH = LOG_DIR / "watchdog.log"
DEVICE_STATE_DIR = STATE_DIR / "devices"
UMBRELLA_STATE_PATH = STATE_DIR / "device_umbrella.json"
UMBRELLA_LAST_SCAN_PATH = STATE_DIR / "umbrella_last_scan.json"
UMBRELLA_SCHEMA_VERSION = 1


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


@dataclass
class DeviceScanResult:
    serial: str
    manufacturer: str
    model: str
    captured_at: str
    baseline_created: bool
    high_count: int
    medium_count: int
    low_count: int
    report_path: Path | None

    @property
    def exit_code(self) -> int:
        if self.high_count > 0:
            return 2
        if (self.medium_count + self.low_count) > 0:
            return 1
        return 0


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def ensure_dirs() -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    ALERT_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    DEVICE_STATE_DIR.mkdir(parents=True, exist_ok=True)


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


def run_adb(args: List[str], timeout: int = 60, serial: str = "") -> str:
    adb_bin = resolve_adb()
    command = [adb_bin]
    if serial.strip():
        command.extend(["-s", serial.strip()])
    command.extend(args)
    process = subprocess.run(
        command,
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


def list_detected_devices() -> List[Dict[str, str]]:
    run_adb(["start-server"])
    return parse_devices(run_adb(["devices", "-l"]))


def list_connected_devices() -> List[Dict[str, str]]:
    return [device for device in list_detected_devices() if device["state"] == "device"]


def require_connected_device(serial: str = "") -> Dict[str, str]:
    live = list_connected_devices()
    if not live:
        raise ADBError("No authorized Android device connected.")

    requested = serial.strip()
    if not requested:
        return live[0]

    for device in live:
        if device["serial"] == requested:
            return device

    known = ", ".join(device["serial"] for device in live)
    raise ADBError(
        f"Requested device serial '{requested}' is not connected/authorized. Connected: {known}"
    )


def run_shell(command: str, serial: str = "") -> str:
    normalized = command.strip()
    if not normalized:
        raise ADBError("empty shell command")
    # Use a single adb-shell argument to preserve command tokenization.
    # `adb shell sh -c <cmd>` can break argument parsing on some devices.
    return run_adb(["shell", normalized], serial=serial).strip()


def getprop(name: str, serial: str = "") -> str:
    return run_shell(f"getprop {name}", serial=serial)


def getprop_soft(name: str, serial: str = "") -> str:
    try:
        return getprop(name, serial=serial)
    except ADBError:
        return ""


def parse_pm_packages(output: str) -> List[str]:
    packages: List[str] = []
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith("package:"):
            continue
        packages.append(line.removeprefix("package:"))
    return sorted(set(packages))


def list_third_party_packages(serial: str = "") -> List[str]:
    commands = [
        "pm list packages -3",
        "cmd package list packages -3",
        "pm list packages",
        "cmd package list packages",
    ]
    errors: List[str] = []
    for command in commands:
        try:
            packages = parse_pm_packages(run_shell(command, serial=serial))
        except ADBError as exc:
            errors.append(f"{command}: {exc}")
            continue
        if packages:
            return packages
        errors.append(f"{command}: empty package list")
    detail = "; ".join(errors) if errors else "no package commands attempted"
    raise ADBError(f"unable to collect package list ({detail})")


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


def get_security_settings(serial: str = "") -> Dict[str, str]:
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
            data[key] = run_shell(cmd, serial=serial)
        except ADBError:
            data[key] = "unavailable"
    return data


def collect_snapshot(serial: str = "") -> Dict[str, object]:
    device = require_connected_device(serial)
    selected_serial = device["serial"]

    third_party_packages = list_third_party_packages(serial=selected_serial)

    accessibility = parse_accessibility_services(
        run_shell(
            "settings get secure enabled_accessibility_services",
            serial=selected_serial,
        )
    )

    try:
        admins_raw = run_shell("dpm list active-admins", serial=selected_serial)
        device_admins = parse_active_admins(admins_raw)
    except ADBError:
        # Fallback for devices that don't expose "dpm list active-admins" to shell.
        admins_raw = run_shell("dumpsys device_policy", serial=selected_serial)
        device_admins = parse_active_admins(admins_raw)

    perms_dump = run_shell("dumpsys package", serial=selected_serial)
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
            "serial": selected_serial,
            "model": getprop_soft("ro.product.model", serial=selected_serial),
            "manufacturer": getprop_soft("ro.product.manufacturer", serial=selected_serial),
            "android_release": getprop_soft("ro.build.version.release", serial=selected_serial),
            "android_sdk": getprop_soft("ro.build.version.sdk", serial=selected_serial),
            "adb_state": device.get("state", ""),
            "adb_extras": device.get("extras", ""),
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
        "security_settings": get_security_settings(serial=selected_serial),
        "high_risk_permissions": high_risk_permissions,
        "suspicious_named_packages": suspicious_packages,
    }
    return snapshot


def read_json(path: Path) -> Dict[str, object]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, data: Dict[str, object]) -> None:
    ensure_dirs()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)


def safe_serial_token(serial: str) -> str:
    token = re.sub(r"[^A-Za-z0-9._-]", "_", serial.strip())
    return token if token else "device"


def state_paths_for_serial(serial: str = "") -> Tuple[Path, Path]:
    normalized = serial.strip()
    if not normalized:
        return BASELINE_PATH, LAST_SCAN_PATH
    device_dir = DEVICE_STATE_DIR / safe_serial_token(normalized)
    return device_dir / "baseline.json", device_dir / "last_scan.json"


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
            if ignore_adb_debug and key in {
                "adb_enabled",
                "adb_wifi_enabled",
                "adb_tcp_port",
            }:
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


def count_alerts(alerts: List[Alert]) -> Tuple[int, int, int]:
    high_count = sum(1 for alert in alerts if alert.severity == "high")
    medium_count = sum(1 for alert in alerts if alert.severity == "medium")
    low_count = sum(1 for alert in alerts if alert.severity == "low")
    return high_count, medium_count, low_count


def write_alert_report(alerts: List[Alert], snapshot: Dict[str, object], serial: str = "") -> Path:
    ensure_dirs()
    ts = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    suffix = f"-{safe_serial_token(serial)}" if serial.strip() else ""
    report_path = ALERT_DIR / f"alert-{ts}{suffix}.md"
    high_count, medium_count, low_count = count_alerts(alerts)

    device = snapshot.get("device", {})
    report_serial = device.get("serial", serial).strip()
    device_label = f"{device.get('manufacturer', '')} {device.get('model', '')}".strip()

    lines = [
        "# Android Watchdog Alert Report",
        "",
        f"- Generated at: {snapshot.get('captured_at')}",
        f"- Device serial: {report_serial}",
        f"- Device: {device_label}",
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


def scan_device(
    ignore_adb_debug: bool,
    serial: str = "",
    auto_create_baseline: bool = True,
) -> DeviceScanResult:
    normalized_serial = serial.strip()
    baseline_path, last_scan_path = state_paths_for_serial(normalized_serial)

    if not baseline_path.exists():
        if not auto_create_baseline:
            raise ADBError(
                f"No baseline found for serial '{normalized_serial}'. Run baseline first."
            )
        target = normalized_serial if normalized_serial else "default-device"
        log(f"[watchdog] no baseline found for {target}, creating baseline first")
        snapshot = collect_snapshot(serial=normalized_serial)
        write_json(baseline_path, snapshot)
        write_json(last_scan_path, snapshot)
        device = snapshot.get("device", {})
        log(f"[watchdog] baseline saved to {baseline_path}")
        return DeviceScanResult(
            serial=str(device.get("serial", normalized_serial)).strip(),
            manufacturer=str(device.get("manufacturer", "")).strip(),
            model=str(device.get("model", "")).strip(),
            captured_at=str(snapshot.get("captured_at", now_utc())),
            baseline_created=True,
            high_count=0,
            medium_count=0,
            low_count=0,
            report_path=None,
        )

    baseline = read_json(baseline_path)
    target = normalized_serial if normalized_serial else "default-device"
    log(f"[watchdog] collecting current snapshot for {target}")
    current = collect_snapshot(serial=normalized_serial)
    write_json(last_scan_path, current)

    alerts = make_alerts(baseline, current, ignore_adb_debug=ignore_adb_debug)
    high_count, medium_count, low_count = count_alerts(alerts)

    report_path: Path | None = None
    if alerts:
        report_path = write_alert_report(alerts, current, serial=normalized_serial)
        log(
            "[watchdog] alerts generated "
            f"(serial={current.get('device', {}).get('serial', normalized_serial)}, "
            f"high={high_count}, medium={medium_count}, low={low_count}): {report_path}"
        )
    else:
        log(
            "[watchdog] scan completed: no suspicious changes against baseline "
            f"(serial={current.get('device', {}).get('serial', normalized_serial)})"
        )

    device = current.get("device", {})
    return DeviceScanResult(
        serial=str(device.get("serial", normalized_serial)).strip(),
        manufacturer=str(device.get("manufacturer", "")).strip(),
        model=str(device.get("model", "")).strip(),
        captured_at=str(current.get("captured_at", now_utc())),
        baseline_created=False,
        high_count=high_count,
        medium_count=medium_count,
        low_count=low_count,
        report_path=report_path,
    )


def create_baseline(serial: str = "") -> int:
    target = serial.strip() if serial.strip() else "default-device"
    log(f"[watchdog] collecting baseline snapshot ({target})")
    snapshot = collect_snapshot(serial=serial)
    baseline_path, last_scan_path = state_paths_for_serial(serial)
    write_json(baseline_path, snapshot)
    write_json(last_scan_path, snapshot)
    log(f"[watchdog] baseline saved to {baseline_path}")
    return 0


def run_scan(ignore_adb_debug: bool, serial: str = "") -> int:
    ensure_dirs()
    result = scan_device(
        ignore_adb_debug=ignore_adb_debug,
        serial=serial,
        auto_create_baseline=True,
    )
    return result.exit_code


def run_watch(interval: int, ignore_adb_debug: bool, serial: str = "") -> int:
    target = serial.strip() if serial.strip() else "default-device"
    log(f"[watchdog] watch mode started (interval={interval}s, target={target})")
    while True:
        try:
            run_scan(ignore_adb_debug=ignore_adb_debug, serial=serial)
        except Exception as exc:  # noqa: BLE001
            log(f"[watchdog] scan failed: {exc}")
        time.sleep(interval)


def read_optional_json(path: Path) -> Dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(payload, dict):
        return None
    return payload


def normalize_owner(raw: str) -> str:
    owner = raw.strip().lower()
    if owner in {"parent", "child", "son"}:
        return owner
    return owner if owner else "parent"


def default_umbrella_name() -> str:
    return "family-protection"


def create_umbrella_state(name: str, owner: str) -> Dict[str, Any]:
    now = now_utc()
    normalized_name = name.strip() or default_umbrella_name()
    normalized_owner = normalize_owner(owner)
    umbrella_id = f"umbrella-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
    return {
        "schema_version": UMBRELLA_SCHEMA_VERSION,
        "umbrella_id": umbrella_id,
        "name": normalized_name,
        "owner": normalized_owner,
        "created_at": now,
        "updated_at": now,
        "members": [],
    }


def read_umbrella_state() -> Dict[str, Any] | None:
    payload = read_optional_json(UMBRELLA_STATE_PATH)
    if payload is None:
        return None
    members = payload.get("members", [])
    if not isinstance(members, list):
        payload["members"] = []
    return payload


def write_umbrella_state(state: Dict[str, Any]) -> None:
    state["updated_at"] = now_utc()
    write_json(UMBRELLA_STATE_PATH, state)


def device_alias(identity: Dict[str, str]) -> str:
    manufacturer = identity.get("manufacturer", "").strip()
    model = identity.get("model", "").strip()
    if manufacturer or model:
        return f"{manufacturer} {model}".strip()
    return identity.get("serial", "device").strip() or "device"


def fetch_device_identity(serial: str) -> Dict[str, str]:
    connected = require_connected_device(serial)
    selected_serial = connected["serial"]
    return {
        "serial": selected_serial,
        "manufacturer": getprop_soft("ro.product.manufacturer", serial=selected_serial),
        "model": getprop_soft("ro.product.model", serial=selected_serial),
        "android_release": getprop_soft("ro.build.version.release", serial=selected_serial),
        "android_sdk": getprop_soft("ro.build.version.sdk", serial=selected_serial),
    }


def upsert_umbrella_member(state: Dict[str, Any], identity: Dict[str, str]) -> Tuple[Dict[str, Any], bool]:
    now = now_utc()
    members = state.setdefault("members", [])
    serial = identity.get("serial", "").strip()
    if not serial:
        raise ADBError("cannot register umbrella member without serial")

    existing: Dict[str, Any] | None = None
    for item in members:
        if isinstance(item, dict) and item.get("serial", "").strip() == serial:
            existing = item
            break

    alias = device_alias(identity)
    if existing is not None:
        if not str(existing.get("alias", "")).strip():
            existing["alias"] = alias
        manufacturer = identity.get("manufacturer", "").strip()
        model = identity.get("model", "").strip()
        android_release = identity.get("android_release", "").strip()
        android_sdk = identity.get("android_sdk", "").strip()
        if manufacturer:
            existing["manufacturer"] = manufacturer
        if model:
            existing["model"] = model
        if android_release:
            existing["android_release"] = android_release
        if android_sdk:
            existing["android_sdk"] = android_sdk
        existing["last_seen_at"] = now
        existing["enabled"] = bool(existing.get("enabled", True))
        return existing, False

    member = {
        "serial": serial,
        "alias": alias,
        "manufacturer": identity.get("manufacturer", "").strip(),
        "model": identity.get("model", "").strip(),
        "android_release": identity.get("android_release", "").strip(),
        "android_sdk": identity.get("android_sdk", "").strip(),
        "linked_at": now,
        "last_seen_at": now,
        "enabled": True,
    }
    members.append(member)
    return member, True


def select_target_serials(
    connected: List[Dict[str, str]],
    requested_serials: List[str],
) -> List[str]:
    connected_serials = [device["serial"] for device in connected]
    if not requested_serials:
        return connected_serials

    targets: List[str] = []
    for serial in requested_serials:
        selected = serial.strip()
        if not selected:
            continue
        if selected not in connected_serials:
            raise ADBError(
                f"Requested serial '{selected}' is not connected/authorized. "
                f"Connected: {', '.join(connected_serials)}"
            )
        if selected not in targets:
            targets.append(selected)
    if not targets:
        raise ADBError("No valid serials selected for umbrella-link.")
    return targets


def run_umbrella_link(
    name: str,
    owner: str,
    serials: List[str],
    create_baselines: bool,
) -> int:
    connected = list_connected_devices()
    if not connected:
        raise ADBError("No authorized Android device connected for umbrella linking.")

    state = read_umbrella_state() or create_umbrella_state(name=name, owner=owner)
    if name.strip():
        state["name"] = name.strip()
    state["owner"] = normalize_owner(owner)

    targets = select_target_serials(connected, serials)
    linked_new = 0

    for serial in targets:
        identity = fetch_device_identity(serial)
        member, created = upsert_umbrella_member(state, identity)
        linked_new += 1 if created else 0

        if create_baselines:
            create_baseline(serial=serial)

        log(
            "[watchdog] umbrella member linked "
            f"(serial={serial}, alias={member.get('alias', '')}, created={created})"
        )

    write_umbrella_state(state)
    log(
        "[watchdog] umbrella link state updated "
        f"(umbrella={state.get('name', default_umbrella_name())}, "
        f"members={len(state.get('members', []))}, newly_linked={linked_new})"
    )
    return 0


def run_umbrella_status() -> int:
    state = read_umbrella_state()
    if state is None:
        log(f"[watchdog] no umbrella state found at {UMBRELLA_STATE_PATH}")
        return 1

    connected = {device["serial"] for device in list_connected_devices()}
    members = [item for item in state.get("members", []) if isinstance(item, dict)]

    log(
        "[watchdog] umbrella status "
        f"(umbrella={state.get('name', default_umbrella_name())}, members={len(members)})"
    )
    for member in members:
        serial = str(member.get("serial", "")).strip()
        alias = str(member.get("alias", "")).strip() or serial
        status = "connected" if serial in connected else "offline"
        enabled = bool(member.get("enabled", True))
        log(
            "[watchdog] umbrella member "
            f"serial={serial} alias={alias} status={status} enabled={enabled}"
        )
    return 0


def write_umbrella_alert_report(
    umbrella_name: str,
    umbrella_id: str,
    results: List[DeviceScanResult],
    skipped_serials: List[str],
) -> Path:
    ts = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    report_path = ALERT_DIR / f"umbrella-alert-{ts}.md"

    lines = [
        "# Android Watchdog Umbrella Alert Report",
        "",
        f"- Generated at: {now_utc()}",
        f"- Umbrella: {umbrella_name}",
        f"- Umbrella ID: {umbrella_id}",
        f"- Scanned devices: {len(results)}",
        "",
    ]

    if skipped_serials:
        lines.extend(
            [
                "## Skipped Linked Devices",
                "",
                *[f"- {serial}" for serial in skipped_serials],
                "",
            ]
        )

    for item in results:
        severity = "NONE"
        if item.high_count > 0:
            severity = "HIGH"
        elif item.medium_count > 0:
            severity = "MEDIUM"
        elif item.low_count > 0:
            severity = "LOW"

        lines.extend(
            [
                f"## [{severity}] {item.manufacturer} {item.model}".strip(),
                "",
                f"- Serial: {item.serial}",
                f"- Captured at: {item.captured_at}",
                f"- Baseline created during scan: {str(item.baseline_created).lower()}",
                f"- Alerts: high={item.high_count}, medium={item.medium_count}, low={item.low_count}",
                f"- Device report: {item.report_path if item.report_path else 'none'}",
                "",
            ]
        )

    report_path.write_text("\n".join(lines), encoding="utf-8")
    return report_path


def run_umbrella_scan(ignore_adb_debug: bool, all_connected: bool) -> int:
    connected = list_connected_devices()
    if not connected:
        raise ADBError("No authorized Android devices connected for umbrella scan.")

    connected_serials = {device["serial"] for device in connected}
    state = read_umbrella_state()

    if all_connected:
        if state is None:
            state = create_umbrella_state(name=default_umbrella_name(), owner="parent")
        target_serials = sorted(connected_serials)
        skipped_serials: List[str] = []
    else:
        if state is None:
            raise ADBError(
                "No linked umbrella state found. Run 'watchdog.py umbrella-link' first "
                "or use --all-connected."
            )
        linked_serials = [
            str(item.get("serial", "")).strip()
            for item in state.get("members", [])
            if isinstance(item, dict) and bool(item.get("enabled", True))
        ]
        target_serials = [serial for serial in linked_serials if serial in connected_serials]
        skipped_serials = [serial for serial in linked_serials if serial not in connected_serials]

    if not target_serials:
        raise ADBError(
            "No eligible devices to scan. Connect linked devices or run with --all-connected."
        )

    results: List[DeviceScanResult] = []
    for serial in target_serials:
        result = scan_device(
            ignore_adb_debug=ignore_adb_debug,
            serial=serial,
            auto_create_baseline=True,
        )
        results.append(result)

        if state is not None:
            identity = {
                "serial": result.serial,
                "manufacturer": result.manufacturer,
                "model": result.model,
                "android_release": "",
                "android_sdk": "",
            }
            member, _ = upsert_umbrella_member(state, identity)
            member["last_scan_at"] = result.captured_at
            member["last_scan_high"] = result.high_count
            member["last_scan_medium"] = result.medium_count
            member["last_scan_low"] = result.low_count
            member["last_report_path"] = str(result.report_path) if result.report_path else ""

    total_high = sum(item.high_count for item in results)
    total_medium = sum(item.medium_count for item in results)
    total_low = sum(item.low_count for item in results)

    if state is not None:
        write_umbrella_state(state)
        umbrella_name = str(state.get("name", default_umbrella_name()))
        umbrella_id = str(state.get("umbrella_id", ""))
    else:
        umbrella_name = default_umbrella_name()
        umbrella_id = ""

    summary: Dict[str, Any] = {
        "captured_at": now_utc(),
        "umbrella_id": umbrella_id,
        "umbrella_name": umbrella_name,
        "scanned_device_count": len(results),
        "skipped_linked_device_serials": skipped_serials,
        "totals": {
            "high": total_high,
            "medium": total_medium,
            "low": total_low,
        },
        "devices": [
            {
                "serial": item.serial,
                "manufacturer": item.manufacturer,
                "model": item.model,
                "captured_at": item.captured_at,
                "baseline_created": item.baseline_created,
                "high": item.high_count,
                "medium": item.medium_count,
                "low": item.low_count,
                "report_path": str(item.report_path) if item.report_path else "",
            }
            for item in results
        ],
    }
    write_json(UMBRELLA_LAST_SCAN_PATH, summary)

    report_path: Path | None = None
    if total_high > 0 or total_medium > 0 or total_low > 0 or skipped_serials:
        report_path = write_umbrella_alert_report(
            umbrella_name=umbrella_name,
            umbrella_id=umbrella_id,
            results=results,
            skipped_serials=skipped_serials,
        )

    log(
        "[watchdog] umbrella scan complete "
        f"(umbrella={umbrella_name}, devices={len(results)}, high={total_high}, "
        f"medium={total_medium}, low={total_low}, skipped={len(skipped_serials)}, "
        f"summary={UMBRELLA_LAST_SCAN_PATH}, report={report_path if report_path else 'none'})"
    )

    if total_high > 0:
        return 2
    if total_medium > 0 or total_low > 0:
        return 1
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Realyn Android watchdog")
    subparsers = parser.add_subparsers(dest="command", required=True)

    baseline_parser = subparsers.add_parser("baseline", help="collect and save baseline snapshot")
    baseline_parser.add_argument(
        "--serial",
        default="",
        help="target adb serial (default: first connected authorized device)",
    )

    scan_parser = subparsers.add_parser("scan", help="scan and compare to baseline")
    scan_parser.add_argument(
        "--ignore-adb-debug",
        action="store_true",
        help="ignore changes to adb_* settings when comparing baseline",
    )
    scan_parser.add_argument(
        "--serial",
        default="",
        help="target adb serial (default: first connected authorized device)",
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
    watch_parser.add_argument(
        "--serial",
        default="",
        help="target adb serial (default: first connected authorized device)",
    )

    umbrella_link_parser = subparsers.add_parser(
        "umbrella-link",
        help="link one or more connected devices into a shared protection umbrella",
    )
    umbrella_link_parser.add_argument(
        "--name",
        default=default_umbrella_name(),
        help="umbrella name label for local state",
    )
    umbrella_link_parser.add_argument(
        "--owner",
        default="parent",
        help="umbrella owner id (default: parent)",
    )
    umbrella_link_parser.add_argument(
        "--serial",
        action="append",
        default=[],
        help="specific connected serial to link (repeatable). Defaults to all connected devices.",
    )
    umbrella_link_parser.add_argument(
        "--create-baselines",
        action="store_true",
        help="create per-device baselines immediately while linking",
    )

    subparsers.add_parser(
        "umbrella-status",
        help="show linked umbrella devices and their current connection status",
    )

    umbrella_scan_parser = subparsers.add_parser(
        "umbrella-scan",
        help="scan all linked umbrella devices in one run",
    )
    umbrella_scan_parser.add_argument(
        "--ignore-adb-debug",
        action="store_true",
        help="ignore changes to adb_* settings when comparing baseline",
    )
    umbrella_scan_parser.add_argument(
        "--all-connected",
        action="store_true",
        help="scan all currently connected authorized devices (not just linked members)",
    )

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    try:
        if args.command == "baseline":
            return create_baseline(serial=args.serial)
        if args.command == "scan":
            return run_scan(ignore_adb_debug=args.ignore_adb_debug, serial=args.serial)
        if args.command == "watch":
            return run_watch(
                interval=args.interval,
                ignore_adb_debug=args.ignore_adb_debug,
                serial=args.serial,
            )
        if args.command == "umbrella-link":
            return run_umbrella_link(
                name=args.name,
                owner=args.owner,
                serials=args.serial,
                create_baselines=args.create_baselines,
            )
        if args.command == "umbrella-status":
            return run_umbrella_status()
        if args.command == "umbrella-scan":
            return run_umbrella_scan(
                ignore_adb_debug=args.ignore_adb_debug,
                all_connected=args.all_connected,
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
