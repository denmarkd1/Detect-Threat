#!/usr/bin/env python3
"""Bootstrap launcher for D_T satellite workspaces aligned with Manus routing."""
from __future__ import annotations

import argparse
import importlib
import json
import logging
import os
import shutil
import subprocess
import sys
from dataclasses import asdict, is_dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Dict, Optional

CONFIG_FILENAME = "satellite_config.json"
INSTALL_MARKER = ".dt_satellite_installed"
INSTALL_LOG = "bootstrap_install.log"
AUTO_SYNC_ENV = "DT_BOOTSTRAP_SYNC"
AUTO_TRACKING_HOOKS_ENV = "DT_BOOTSTRAP_TRACKING_HOOKS"
TRACKING_INSTALLER_REL = Path("D_T_System") / "scripts" / "install_dt_workspace_tracking_hooks.sh"
TRACKING_SCRIPT_REL = Path("D_T_System") / "scripts" / "dt_workspace_change_track.sh"

LOGGER = logging.getLogger("DTSatelliteBootstrap")
logging.basicConfig(level=logging.INFO, format="%(levelname)s - %(message)s")


def _json_default(value: Any) -> Any:
    if is_dataclass(value):
        return asdict(value)
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        return value.isoformat() + ("Z" if value.tzinfo is None else "")
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, set):
        return sorted(value)
    if hasattr(value, "to_dict"):
        try:
            return value.to_dict()
        except Exception:  # pragma: no cover - defensive fallback
            return str(value)
    return str(value)


def _load_config(config_path: Optional[Path] = None) -> Dict[str, Any]:
    path = Path(config_path) if config_path else Path(__file__).resolve().parent / CONFIG_FILENAME
    if not path.exists():
        raise FileNotFoundError(f"Satellite configuration not found: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def _ensure_sys_paths(hub_root: Path, local_dir: Path) -> None:
    candidates = (
        local_dir.parent,
        local_dir,
        hub_root,
        hub_root / "D_T_System",
        hub_root / "D_T_System" / "src",
    )
    for candidate in candidates:
        if candidate.exists():
            str_path = str(candidate)
            if str_path not in sys.path:
                sys.path.insert(0, str_path)

    profiles_dir = local_dir / "profiles"
    if profiles_dir.exists():
        for src_dir in profiles_dir.glob("*/src"):
            src_path = str(src_dir)
            if src_path not in sys.path:
                sys.path.insert(0, src_path)


def _update_required_profiles(config_path: Path, profiles: list[str]) -> None:
    if not config_path.exists():
        return
    config = json.loads(config_path.read_text(encoding="utf-8"))
    if config.get("required_profiles") == profiles:
        return
    config["required_profiles"] = profiles
    config_path.write_text(json.dumps(config, indent=2), encoding="utf-8")


def _should_auto_sync() -> bool:
    return os.environ.get(AUTO_SYNC_ENV, "1").lower() not in {"0", "false", "no"}


def _should_install_tracking_hooks() -> bool:
    return os.environ.get(AUTO_TRACKING_HOOKS_ENV, "1").lower() not in {"0", "false", "no"}


def _write_tracking_hook(
    hook_path: Path,
    *,
    hook_name: str,
    workspace_root: Path,
    hub_root: Path,
    tracker_script: Path,
) -> None:
    hook_content = f"""#!/usr/bin/env bash
set -euo pipefail
TRACKER_SCRIPT="{tracker_script}"
EVENT_NAME="{hook_name}"
EVENT_SOURCE="$*"
HUB_ROOT="${{DT_HUB_ROOT:-${{D_T_HUB_ROOT:-{hub_root}}}}}"
WORKSPACE_ROOT="{workspace_root}"

if [[ -x "${{TRACKER_SCRIPT}}" ]]; then
  "${{TRACKER_SCRIPT}}" "${{EVENT_NAME}}" "${{EVENT_SOURCE}}" >/dev/null 2>&1 || true
  exit 0
fi

AUTO_LINK_SCRIPT="${{HUB_ROOT}}/D_T_System/scripts/dt_workspace_auto_link.py"
if command -v python3 >/dev/null 2>&1 && [[ -f "${{AUTO_LINK_SCRIPT}}" ]]; then
  DT_AUTO_LINK_ENABLE=1 \
  DT_AUTO_LINK_APPLY=1 \
  DT_AUTO_LINK_INSTALL_SATELLITE=1 \
  DT_AUTO_LINK_SYNC_ASSETS=1 \
  python3 "${{AUTO_LINK_SCRIPT}}" --workspace "${{WORKSPACE_ROOT}}" --quiet >/dev/null 2>&1 || true
fi

exit 0
"""
    hook_path.write_text(hook_content, encoding="utf-8")
    hook_path.chmod(0o755)


def _install_tracking_hooks_fallback(workspace_root: Path, hub_root: Path) -> Dict[str, Any]:
    try:
        hooks_path_raw = subprocess.check_output(
            ["git", "-C", str(workspace_root), "rev-parse", "--git-path", "hooks"],
            text=True,
        ).strip()
    except Exception as exc:
        return {"status": "skipped", "reason": f"hooks_path_unavailable: {exc}"}

    hooks_dir = Path(hooks_path_raw)
    if not hooks_dir.is_absolute():
        hooks_dir = workspace_root / hooks_dir
    hooks_dir.mkdir(parents=True, exist_ok=True)

    tracker_script = workspace_root / TRACKING_SCRIPT_REL
    installed: list[str] = []
    for hook_name in ("post-commit", "post-merge", "post-checkout"):
        hook_path = hooks_dir / hook_name
        _write_tracking_hook(
            hook_path,
            hook_name=hook_name,
            workspace_root=workspace_root,
            hub_root=hub_root,
            tracker_script=tracker_script,
        )
        installed.append(str(hook_path))

    return {"status": "installed_fallback", "hooks": installed}


def _install_tracking_hooks(workspace_root: Path, hub_root: Path) -> Dict[str, Any]:
    try:
        in_git_repo = subprocess.run(
            ["git", "-C", str(workspace_root), "rev-parse", "--git-dir"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        ).returncode == 0
    except Exception:
        in_git_repo = False

    if not in_git_repo:
        return {"status": "skipped", "reason": "workspace_not_git_repo"}

    installer_path = workspace_root / TRACKING_INSTALLER_REL
    if installer_path.exists():
        try:
            proc = subprocess.run(
                ["bash", str(installer_path)],
                cwd=str(workspace_root),
                check=False,
                capture_output=True,
                text=True,
            )
            if proc.returncode == 0:
                return {"status": "installed_via_installer", "installer": str(installer_path)}
            LOGGER.debug(
                "Tracking hook installer returned non-zero (%s): %s",
                proc.returncode,
                (proc.stderr or proc.stdout or "").strip(),
            )
        except Exception as exc:
            LOGGER.debug("Tracking hook installer failed: %s", exc)

    return _install_tracking_hooks_fallback(workspace_root, hub_root)


def _sync_workspace_with_hub(hub_root: Path, workspace_root: Path, config_path: Path) -> None:
    _ensure_sys_paths(hub_root, config_path.parent)
    try:
        module = importlib.import_module("dt_satellite_router")
        snapshot = module.collect_workspace_snapshot(workspace_root)  # type: ignore[attr-defined]
    except Exception:
        snapshot = {
            "workspace_name": workspace_root.name,
            "workspace_path": str(workspace_root),
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "summary": "bootstrap snapshot",
            "tags": [],
            "tech_stack": [],
        }

    from dt_hub_routing import get_router  # type: ignore

    router = get_router()
    sync_payload = router.sync_workspace_assets(workspace_root, snapshot)
    asset_plan = sync_payload.get("asset_plan", {}) if isinstance(sync_payload, dict) else {}
    if asset_plan.get("profiles"):
        _update_required_profiles(config_path, list(asset_plan["profiles"]))
    os.environ["DT_SKIP_HUB_SYNC"] = "1"


def _copy_full_package(source: Path, destination: Path) -> None:
    LOGGER.info("Copying full satellite package from %s", source)
    shutil.copytree(source, destination, dirs_exist_ok=True)


def _sync_monitoring_dashboard(hub_root: Path, workspace_root: Path) -> None:
    """Ensure the Zen Manus monitoring dashboard ships with every satellite."""
    source = hub_root / "D_T_System" / "templates" / "satellite_package" / "monitoring" / "agent_monitor_dashboard.py"
    if not source.exists():
        return

    target = workspace_root / "systems" / "Zen_Manus_System" / "agent_monitor_dashboard.py"
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    except Exception as exc:  # pragma: no cover - best effort for optional tooling
        LOGGER.debug("Skipping monitoring dashboard sync: %s", exc)


def _update_config(config_path: Path, workspace_path: Path) -> Dict[str, Any]:
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config.update(
        {
            "workspace_name": workspace_path.name,
            "workspace_path": str(workspace_path.resolve()),
            "last_bootstrap": datetime.utcnow().isoformat() + "Z",
        }
    )
    config_path.write_text(json.dumps(config, indent=2), encoding="utf-8")
    return config


def _resolve_hub_root(config: Dict[str, Any], *, config_path: Path) -> Path:
    hub_path = str(config.get("hub_path", "") or "").strip()
    if not hub_path or hub_path.upper() == "AUTO_DETECT":
        env = os.environ.get("DT_HUB_ROOT") or os.environ.get("D_T_HUB_ROOT")
        if env:
            return Path(env).expanduser().resolve()

        home_candidate = Path.home() / "D_T_SYSTEM_HUB"
        if home_candidate.exists():
            return home_candidate.expanduser().resolve()

        for candidate in [config_path.parent.parent, *config_path.parent.parent.parents]:
            expected = candidate / "D_T_System" / "src" / "dt_hub_routing.py"
            if expected.exists():
                return candidate.resolve()

        raise FileNotFoundError(
            "Unable to auto-detect hub root. Set `hub_path` in `satellite_config.json` "
            "or export `DT_HUB_ROOT=/path/to/hub`."
        )

    return Path(hub_path).expanduser().resolve()


def _register_with_hub(hub_root: Path, workspace_path: Path, config: Dict[str, Any]) -> Dict[str, Any]:
    _ensure_sys_paths(hub_root, Path(__file__).resolve().parent)
    from dt_hub_routing import get_router  # type: ignore

    router = get_router()
    metadata = dict(config.get("metadata", {}))
    profiles = list(config.get("required_profiles", []) or [])
    if profiles:
        metadata.setdefault("required_profiles", profiles)
    capabilities = config.get("capabilities")
    if capabilities is not None:
        metadata.setdefault("capabilities", capabilities)
    category = config.get("category")
    if category:
        metadata.setdefault("category", category)
    return router.register_workspace(
        workspace_path,
        overwrite=True,
        metadata=metadata,
        profiles=profiles,
    )


def _write_install_marker(destination: Path, payload: Dict[str, Any]) -> None:
    marker_path = destination / INSTALL_MARKER
    marker_path.write_text(json.dumps(payload, indent=2, default=_json_default), encoding="utf-8")

    log_path = destination / INSTALL_LOG
    message = json.dumps(payload, default=_json_default)
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write(message + "\n")


def _ensure_full_install(config: Dict[str, Any], config_path: Path) -> None:
    local_dir = config_path.parent
    marker_path = local_dir / INSTALL_MARKER
    if marker_path.exists():
        return

    hub_root = _resolve_hub_root(config, config_path=config_path)
    package_source = hub_root / "D_T_System" / "templates" / "satellite_package"
    if not package_source.exists():
        raise FileNotFoundError(f"Hub satellite package not found: {package_source}")

    _copy_full_package(package_source, local_dir)
    updated_config = _update_config(config_path, local_dir.parent)
    registration = _register_with_hub(hub_root, local_dir.parent, updated_config)
    _write_install_marker(
        local_dir,
        {
            "installed_at": datetime.utcnow().isoformat() + "Z",
            "workspace": registration,
        },
    )

    LOGGER.info("Satellite package installed for workspace %s", registration.get("name"))


def _delegate_route_issue(
    issue_input: str,
    *,
    user_notes: Optional[str],
    auto_execute: bool,
    config_path: Path,
) -> Dict[str, Any]:
    if "dt_satellite_router" in sys.modules:
        del sys.modules["dt_satellite_router"]
    module = importlib.import_module("dt_satellite_router")
    return module.route_issue(  # type: ignore[attr-defined]
        issue_input,
        user_notes=user_notes,
        auto_execute=auto_execute,
        config_path=config_path,
    )


def route_issue(
    issue_input: str,
    *,
    user_notes: Optional[str] = None,
    auto_execute: bool = False,
    config_override: Optional[Path] = None,
) -> Dict[str, Any]:
    config_path = Path(config_override) if config_override else Path(__file__).resolve().parent / CONFIG_FILENAME
    config = _load_config(config_path)
    hub_root = _resolve_hub_root(config, config_path=config_path)
    workspace_root = config_path.parent.parent
    _ensure_full_install(config, config_path)
    if os.environ.get("DT_ENFORCE_AGENTS_STANDARD", "").lower() in {"1", "true", "yes"}:
        try:
            from agents_standard import ensure_agents_standard
            ensure_agents_standard(workspace_root, hub_root=hub_root, logger=LOGGER)
        except Exception as exc:  # pragma: no cover - keep bootstrap resilient
            LOGGER.debug("AGENTS standard enforcement skipped: %s", exc)
    _sync_monitoring_dashboard(hub_root, workspace_root)
    _ensure_sys_paths(hub_root, config_path.parent)
    if _should_install_tracking_hooks():
        try:
            hook_state = _install_tracking_hooks(workspace_root, hub_root)
            LOGGER.debug("Tracking hooks bootstrap state: %s", hook_state)
        except Exception as exc:  # pragma: no cover - keep bootstrap resilient
            LOGGER.debug("Skipping tracking hook install: %s", exc)
    try:
        _register_with_hub(hub_root, workspace_root, config)
    except Exception as exc:  # pragma: no cover - keep routing resilient
        LOGGER.debug("Skipping workspace re-registration: %s", exc)
    if _should_auto_sync():
        try:
            _sync_workspace_with_hub(hub_root, workspace_root, config_path)
        except Exception as exc:  # pragma: no cover - keep routing resilient
            LOGGER.debug("Skipping hub sync: %s", exc)
    return _delegate_route_issue(
        issue_input,
        user_notes=user_notes,
        auto_execute=auto_execute,
        config_path=config_path,
    )


def _main() -> int:
    parser = argparse.ArgumentParser(description="Bootstrap D_T satellite routing")
    parser.add_argument("issue", help="Issue or chat request text")
    parser.add_argument("--notes", help="Optional notes for the hub", default=None)
    parser.add_argument("--auto-execute", action="store_true", help="Request automated execution")
    parser.add_argument("--config", help="Alternate config path", default=None)
    args = parser.parse_args()

    config_path = Path(args.config) if args.config else Path(__file__).resolve().parent / CONFIG_FILENAME
    payload = route_issue(
        args.issue,
        user_notes=args.notes,
        auto_execute=args.auto_execute,
        config_override=config_path,
    )
    print(json.dumps(payload, indent=2, default=_json_default))
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
