from __future__ import annotations

import getpass
import os
import platform
import socket
import time
from pathlib import Path
from typing import Any

from .config import WATCHDOG_STATUS_PATH, default_settings, save_json
from .utils import utc_now_iso


KNOWN_BROWSERS = ("chrome", "chromium", "brave", "edge", "firefox")
_WINDOWS_IGNORED_USERS = {"all users", "default", "default user", "defaultaccount", "public"}


def detect_os_family() -> str:
    system = platform.system().lower()
    if system.startswith("win"):
        return "windows"
    if system == "linux":
        return "linux"
    if system == "darwin":
        return "macos"
    return "other"


def _linux_browser_paths(home: Path) -> dict[str, Path]:
    return {
        "chrome": home / ".config" / "google-chrome",
        "chromium": home / ".config" / "chromium",
        "brave": home / ".config" / "BraveSoftware" / "Brave-Browser",
        "edge": home / ".config" / "microsoft-edge",
        "firefox": home / ".mozilla" / "firefox",
    }


def _windows_browser_paths(user_profile: Path) -> dict[str, Path]:
    local = user_profile / "AppData" / "Local"
    roaming = user_profile / "AppData" / "Roaming"
    return {
        "chrome": local / "Google" / "Chrome" / "User Data",
        "chromium": local / "Chromium" / "User Data",
        "brave": local / "BraveSoftware" / "Brave-Browser" / "User Data",
        "edge": local / "Microsoft" / "Edge" / "User Data",
        "firefox": roaming / "Mozilla" / "Firefox",
    }


def _collect_user_dirs(users_root: Path, user_hints: list[str]) -> list[Path]:
    if not users_root.exists() or not users_root.is_dir():
        return []
    hints = [hint.strip().lower() for hint in user_hints if str(hint).strip()]
    results: list[Path] = []
    try:
        for child in users_root.iterdir():
            if not child.is_dir():
                continue
            lowered = child.name.lower()
            if lowered in _WINDOWS_IGNORED_USERS:
                continue
            if hints and not any(hint in lowered for hint in hints):
                continue
            results.append(child)
    except (PermissionError, OSError):
        return []
    return results


def _discover_windows_profiles_from_linux(settings: dict[str, Any]) -> list[Path]:
    watchdog_cfg = settings.get("watchdog", {})
    if not watchdog_cfg.get("enable_windows_mount_scan_on_linux", True):
        return []

    user_hints = watchdog_cfg.get("windows_user_hints", []) or []
    mount_candidates = watchdog_cfg.get("windows_mount_candidates", ["/mnt", "/media"]) or []
    profiles: list[Path] = []
    seen: set[str] = set()

    for mount_root_str in mount_candidates:
        mount_root = Path(str(mount_root_str)).expanduser()
        if not mount_root.exists():
            continue

        direct_candidates = [
            mount_root / "c" / "Users",
            mount_root / "C" / "Users",
            mount_root / "Users",
        ]
        for candidate in direct_candidates:
            for profile_dir in _collect_user_dirs(candidate, user_hints):
                key = str(profile_dir.resolve())
                if key not in seen:
                    seen.add(key)
                    profiles.append(profile_dir.resolve())

        try:
            for child in mount_root.iterdir():
                users_dir = child / "Users"
                for profile_dir in _collect_user_dirs(users_dir, user_hints):
                    key = str(profile_dir.resolve())
                    if key not in seen:
                        seen.add(key)
                        profiles.append(profile_dir.resolve())
        except PermissionError:
            continue

    for explicit in watchdog_cfg.get("windows_profiles", []) or []:
        profile = Path(str(explicit)).expanduser()
        if profile.exists() and profile.is_dir():
            key = str(profile.resolve())
            if key not in seen:
                seen.add(key)
                profiles.append(profile.resolve())

    return profiles


def _build_browser_path_records(settings: dict[str, Any]) -> list[dict[str, Any]]:
    os_family = detect_os_family()
    records: list[dict[str, Any]] = []

    if os_family == "windows":
        user_profile = Path(os.environ.get("USERPROFILE", str(Path.home()))).expanduser()
        for browser, browser_path in _windows_browser_paths(user_profile).items():
            records.append(
                {
                    "browser": browser,
                    "path": str(browser_path),
                    "present": browser_path.exists(),
                    "source": "windows_local",
                    "user_profile": str(user_profile),
                }
            )
        for explicit in settings.get("watchdog", {}).get("windows_profiles", []) or []:
            candidate = Path(str(explicit)).expanduser()
            if not candidate.exists() or not candidate.is_dir():
                continue
            if candidate == user_profile:
                continue
            for browser, browser_path in _windows_browser_paths(candidate).items():
                records.append(
                    {
                        "browser": browser,
                        "path": str(browser_path),
                        "present": browser_path.exists(),
                        "source": "windows_extra_profile",
                        "user_profile": str(candidate),
                    }
                )
        return records

    if os_family == "linux":
        home = Path.home()
        for browser, browser_path in _linux_browser_paths(home).items():
            records.append(
                {
                    "browser": browser,
                    "path": str(browser_path),
                    "present": browser_path.exists(),
                    "source": "linux_local",
                    "user_profile": str(home),
                }
            )
        for profile_dir in _discover_windows_profiles_from_linux(settings):
            for browser, browser_path in _windows_browser_paths(profile_dir).items():
                records.append(
                    {
                        "browser": browser,
                        "path": str(browser_path),
                        "present": browser_path.exists(),
                        "source": "windows_mount",
                        "user_profile": str(profile_dir),
                    }
                )
        return records

    return records


def summarize_browser_presence(status: dict[str, Any]) -> dict[str, bool]:
    summary = {browser: False for browser in KNOWN_BROWSERS}
    for item in status.get("browser_paths", []):
        browser = item.get("browser")
        if browser in summary and bool(item.get("present")):
            summary[browser] = True
    return summary


def build_runtime_status(settings: dict[str, Any] | None = None) -> dict[str, Any]:
    cfg = settings or default_settings()
    os_family = detect_os_family()
    browser_records = _build_browser_path_records(cfg)
    status = {
        "timestamp": utc_now_iso(),
        "os_family": os_family,
        "platform": platform.platform(),
        "hostname": socket.gethostname(),
        "active_user": getpass.getuser(),
        "workspace_root": str(Path(__file__).resolve().parents[2]),
        "service_available": os_family in {"linux", "windows"},
        "browser_paths": browser_records,
    }
    status["browser_presence"] = summarize_browser_presence(status)
    return status


def write_runtime_status(status: dict[str, Any]) -> Path:
    save_json(WATCHDOG_STATUS_PATH, status)
    return WATCHDOG_STATUS_PATH


def watchdog_loop(
    settings: dict[str, Any] | None = None,
    *,
    interval_seconds: int = 60,
    max_cycles: int = 0,
    print_updates: bool = True,
) -> int:
    cfg = settings or default_settings()
    cycles = 0
    while True:
        status = build_runtime_status(cfg)
        path = write_runtime_status(status)
        cycles += 1
        if print_updates:
            print(
                f"[{status['timestamp']}] os={status['os_family']} "
                f"service_available={status['service_available']} status={path}"
            )
        if max_cycles > 0 and cycles >= max_cycles:
            return 0
        time.sleep(max(interval_seconds, 1))
