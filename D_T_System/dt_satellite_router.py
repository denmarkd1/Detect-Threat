#!/usr/bin/env python3
"""Satellite interface for routing issues to the main D_T hub."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from collections import Counter
from dataclasses import asdict, is_dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Dict, Optional

CONFIG_FILENAME = "satellite_config.json"
MAX_SCAN_FILES = 2000
MAX_SCAN_DEPTH = 3
SKIP_DIRS = {
    ".git",
    ".venv",
    "__pycache__",
    ".mypy_cache",
    ".pytest_cache",
    "node_modules",
    "dist",
    "build",
    "venv",
    "runtimes",
}
PROFILE_RUNTIME_REL = Path("D_T_System") / "profiles" / "manus_wide_research" / "runtime"


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
        except Exception:
            return str(value)
    return str(value)


def _load_config(config_path: Optional[Path] = None) -> Dict[str, Any]:
    path = Path(config_path) if config_path else Path(__file__).resolve().parent / CONFIG_FILENAME
    if not path.exists():
        raise FileNotFoundError(f"Satellite configuration missing: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def _resolve_workspace_root(config: Dict[str, Any], *, config_path: Path) -> Path:
    value = str(config.get("workspace_path", "") or "").strip()
    if not value or value.upper() == "AUTO_DETECT":
        return config_path.parent.parent.expanduser().resolve()
    return Path(value).expanduser().resolve()


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


def _ensure_hub_path(hub_root: Path) -> None:
    if str(hub_root) not in sys.path:
        sys.path.insert(0, str(hub_root))
    hub_pkg = hub_root / "D_T_System"
    if not hub_pkg.exists():
        raise FileNotFoundError(f"Hub package directory not found: {hub_pkg}")
    if str(hub_pkg) not in sys.path:
        sys.path.insert(0, str(hub_pkg))
    hub_src = hub_pkg / "src"
    if not hub_src.exists():
        raise FileNotFoundError(f"Hub src directory not found: {hub_src}")
    if str(hub_src) not in sys.path:
        sys.path.insert(0, str(hub_src))


def _ensure_local_profile_paths(workspace_root: Path) -> None:
    satellite_dir = workspace_root / "D_T_System"
    if satellite_dir.exists() and str(satellite_dir) not in sys.path:
        sys.path.insert(0, str(satellite_dir))
    profiles_root = satellite_dir / "profiles"
    if profiles_root.exists():
        for src_dir in profiles_root.glob("*/src"):
            src_path = str(src_dir)
            if src_path not in sys.path:
                sys.path.insert(0, src_path)


def _reset_profile_runtime_dir(workspace_root: Path) -> None:
    runtime_root = workspace_root / PROFILE_RUNTIME_REL
    if not runtime_root.exists():
        return

    for entry in runtime_root.iterdir():
        if entry.name == ".keep":
            continue
        if entry.is_dir():
            shutil.rmtree(entry)
        else:
            entry.unlink()


def _collect_workspace_snapshot(workspace_root: Path) -> Dict[str, Any]:
    workspace_root = Path(workspace_root).expanduser().resolve()
    top_dirs: list[str] = []
    top_files: list[str] = []
    notable_files = {
        "README.md",
        "requirements.txt",
        "pyproject.toml",
        "package.json",
        "Pipfile",
        "poetry.lock",
        "Cargo.toml",
        "go.mod",
    }
    top_level_notables: list[str] = []

    for entry in sorted(workspace_root.iterdir(), key=lambda p: p.name.lower()):
        if entry.is_dir():
            top_dirs.append(entry.name)
        elif entry.is_file():
            top_files.append(entry.name)
            if entry.name in notable_files:
                top_level_notables.append(entry.name)

    file_ext_counts: Counter[str] = Counter()
    total_files = 0
    max_files_hit = False

    for root, dirs, files in os.walk(workspace_root):
        if max_files_hit:
            break
        rel_parts = Path(root).relative_to(workspace_root).parts
        if len(rel_parts) > MAX_SCAN_DEPTH:
            dirs[:] = []
            continue
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
        for fname in files:
            total_files += 1
            if total_files > MAX_SCAN_FILES:
                max_files_hit = True
                break
            ext = Path(fname).suffix.lower() or "NO_EXT"
            file_ext_counts[ext] += 1

    tags = set()
    name_lower = workspace_root.name.lower()
    if "cyberpunk" in name_lower:
        tags.add("cyberpunk")
        tags.add("game_workspace")
    if any("mo2" in d.lower() or "modorganizer" in d.lower() for d in top_dirs + top_files):
        tags.add("modding")
    if ".py" in file_ext_counts or "requirements.txt" in top_level_notables or "pyproject.toml" in top_level_notables:
        tags.add("python")
    if "package.json" in top_level_notables:
        tags.add("node")
    if "D_T_System" in top_dirs:
        tags.add("dt_system")
    if "systems" in top_dirs:
        tags.add("systems_dir")

    tech_stack = sorted(t for t in tags if t in {"python", "node"})
    summary = f"{len(top_dirs)} top-level dirs, {len(top_files)} top-level files"
    shared_update_files: list[str] = []
    update_dirs = [
        workspace_root / "systems" / "D_T_System" / "hub" / "updates",
        workspace_root / "D_T_System" / "hub" / "updates",
    ]
    for update_dir in update_dirs:
        if not update_dir.exists():
            continue
        for update_file in sorted(update_dir.glob("*.json")):
            rel_name = str(update_file.relative_to(workspace_root))
            if rel_name not in shared_update_files:
                shared_update_files.append(rel_name)

    return {
        "workspace_name": workspace_root.name,
        "workspace_path": str(workspace_root),
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "summary": summary,
        "top_level_dirs": top_dirs[:60],
        "top_level_files": top_files[:60],
        "notable_files": top_level_notables,
        "file_ext_counts": dict(file_ext_counts.most_common(25)),
        "total_files_scanned": total_files,
        "scan_limit_hit": max_files_hit,
        "tags": sorted(tags),
        "tech_stack": tech_stack,
        "shared_update_files": shared_update_files[:60],
        "shared_update_count": len(shared_update_files),
    }


def collect_workspace_snapshot(workspace_root: Path) -> Dict[str, Any]:
    """Public wrapper for collecting workspace snapshot data."""
    return _collect_workspace_snapshot(workspace_root)


def _update_required_profiles(config_path: Optional[Path], profiles: list[str]) -> None:
    if not config_path:
        return
    path = Path(config_path)
    if not path.exists():
        return
    config = json.loads(path.read_text(encoding="utf-8"))
    if config.get("required_profiles") == profiles:
        return
    config["required_profiles"] = profiles
    path.write_text(json.dumps(config, indent=2), encoding="utf-8")


def _infer_actionable_profile(issue_text: str, user_notes: Optional[str] = None) -> Dict[str, int | bool]:
    combined = f"{issue_text} {user_notes or ''}".strip().lower()
    actionable_tokens = (
        "review",
        "audit",
        "assess",
        "scan",
        "inspect",
        "analyze",
        "analysis",
        "fix",
        "patch",
        "address",
        "resolve",
        "implement",
        "harden",
        "update",
        "refactor",
        "improve",
        "normalize",
        "canonicalize",
        "align",
        "sync",
        "synchronize",
        "rename",
        "document",
        "migrate",
    )
    scope_patterns = (
        r"\bphase\s*\d+\b",
        r"\bmodule(?:s)?\b",
        r"\bcomponent(?:s)?\b",
        r"\bdirector(?:y|ies)\b",
        r"\bbackend\b",
        r"\bfrontend\b",
        r"\bconfig(?:uration)?\b",
        r"\bdocs?\b",
        r"\bandroid\b",
        r"\bpython\b",
        r"\bhub\b",
        r"\bsatellite\b",
        r"\blyra\b",
        r"\bdark[_\s-]*coder\b",
        r"\bwatchdog\b",
        r"\bcredential\b",
        r"[/\\][\w./\\-]+\.[a-z0-9]{1,8}\b",
        r"[/\\][\w./\\-]+[/\\][\w./\\-]+\b",
        r"\b[\w.-]+\.(py|js|ts|kt|java|go|rs|json|yaml|yml|toml|md)\b",
        r"\((?:\s*[\w./\\-]+\s*,){1,}\s*[\w./\\-]+\s*\)",
        r"\b(systems|d_t_system|analysis|scripts|tests|gui|docs)\b",
    )
    constraint_tokens = (
        "read-only",
        "read only",
        "no code changes",
        "without code changes",
        "no destructive",
        "no destructive git",
        "do not modify",
    )

    actionable_signal_count = sum(1 for token in actionable_tokens if token in combined)
    scope_signal_count = sum(
        1 for pattern in scope_patterns if re.search(pattern, combined, flags=re.IGNORECASE)
    )
    constraints_signal_count = sum(1 for token in constraint_tokens if token in combined)
    assumption_ready = actionable_signal_count > 0 and scope_signal_count > 0
    return {
        "actionable_signal_count": actionable_signal_count,
        "scope_signal_count": scope_signal_count,
        "constraints_signal_count": constraints_signal_count,
        "assumption_ready": assumption_ready,
    }


def upgrade_actionable_clarification_payload(
    payload: Dict[str, Any],
    issue_input: str,
    user_notes: Optional[str] = None,
    auto_execute: bool = False,
) -> Dict[str, Any]:
    if auto_execute or not isinstance(payload, dict):
        return payload

    resolution = payload.get("resolution")
    if not isinstance(resolution, dict):
        return payload

    completion = resolution.get("completion_status")
    if not isinstance(completion, dict) or completion.get("status") != "clarification_needed":
        return payload

    profile = _infer_actionable_profile(issue_input, user_notes)
    if not bool(profile.get("assumption_ready")):
        return payload

    updated_payload = dict(payload)
    updated_resolution = dict(resolution)
    updated_completion = dict(completion)

    existing_questions = completion.get("clarifying_questions", [])
    questions = [str(item).strip() for item in existing_questions if str(item).strip()] if isinstance(existing_questions, list) else []
    acceptance_question = "What are the required acceptance checks after implementation?"
    if acceptance_question not in questions:
        questions.append(acceptance_question)

    updated_completion.update(
        {
            "source_status": "clarification_needed",
            "status": "ready_for_execution_assumptions",
            "message": (
                "Hub requested clarification, but the request is actionable and scoped. "
                "Proceed with memory-first assumptions and document unresolved questions."
            ),
            "assumption_mode": True,
            "clarifying_questions": questions[:3],
            "source_mode": "satellite_actionable_scope_upgrade",
        }
    )
    updated_resolution["completion_status"] = updated_completion

    certainty = resolution.get("certainty_assessment")
    if isinstance(certainty, dict):
        updated_certainty = dict(certainty)
        current_confidence = float(updated_certainty.get("confidence_percentage") or 0.0)
        if current_confidence < 68.0:
            updated_certainty["confidence_percentage"] = 68.0
        if str(updated_certainty.get("level") or "").strip().lower() == "low":
            updated_certainty["level"] = "moderate"
        reasoning = str(updated_certainty.get("assessment_reasoning") or "").strip()
        note = (
            "Satellite actionable-scope upgrade applied because the request includes explicit action "
            "and target-scope signals."
        )
        if note not in reasoning:
            updated_certainty["assessment_reasoning"] = f"{reasoning}\n\n{note}".strip()
        updated_resolution["certainty_assessment"] = updated_certainty

    handoff = str(updated_resolution.get("code_review_handoff") or "").strip()
    assumption_note = (
        "## Assumption Mode\n"
        "Hub requested clarification, but this request is actionable and scoped. "
        "Proceed with memory-first assumptions and document verification criteria."
    )
    if assumption_note not in handoff:
        updated_resolution["code_review_handoff"] = (
            f"{handoff}\n\n{assumption_note}".strip() if handoff else assumption_note
        )

    updated_payload["resolution"] = updated_resolution
    return updated_payload


def route_issue(
    issue_input: str,
    *,
    user_notes: Optional[str] = None,
    auto_execute: bool = False,
    config_path: Optional[Path] = None,
) -> Dict[str, Any]:
    config_path = Path(config_path) if config_path else None
    resolved_config_path = config_path or Path(__file__).resolve().parent / CONFIG_FILENAME
    config = _load_config(resolved_config_path)
    hub_root = _resolve_hub_root(config, config_path=resolved_config_path)
    workspace_root = _resolve_workspace_root(config, config_path=resolved_config_path)
    if os.environ.get("DT_ENFORCE_AGENTS_STANDARD", "").lower() in {"1", "true", "yes"}:
        try:
            from agents_standard import ensure_agents_standard
            ensure_agents_standard(workspace_root, hub_root=hub_root)
        except Exception:
            pass
    _ensure_hub_path(hub_root)
    snapshot = collect_workspace_snapshot(workspace_root)
    os.environ["DT_WORKSPACE_TAGS"] = ",".join(snapshot.get("tags", []))

    from dt_hub_routing import get_router  # pylint: disable=import-error

    router = get_router()
    skip_sync = os.environ.pop("DT_SKIP_HUB_SYNC", "").lower() in {"1", "true", "yes"}
    if not skip_sync:
        sync_payload = router.sync_workspace_assets(workspace_root, snapshot)
        asset_plan = sync_payload.get("asset_plan", {}) if isinstance(sync_payload, dict) else {}
        if asset_plan.get("profiles"):
            _update_required_profiles(config_path, list(asset_plan["profiles"]))
        _reset_profile_runtime_dir(workspace_root)
    _ensure_local_profile_paths(workspace_root)
    payload = router.route_issue(
        issue_input,
        workspace_path=workspace_root,
        user_notes=user_notes,
        auto_execute=auto_execute,
    )
    _reset_profile_runtime_dir(workspace_root)
    return upgrade_actionable_clarification_payload(
        payload,
        issue_input,
        user_notes=user_notes,
        auto_execute=auto_execute,
    )


def _main() -> int:
    parser = argparse.ArgumentParser(description="Route an issue to the D_T hub")
    parser.add_argument("issue", help="Issue or chat request to route")
    parser.add_argument("--notes", help="Optional notes for the hub", default=None)
    parser.add_argument(
        "--auto-execute",
        help="Request full automation if supported",
        action="store_true",
    )
    parser.add_argument(
        "--config",
        help="Alternate path to a satellite configuration JSON file",
        default=None,
    )
    args = parser.parse_args()

    payload = route_issue(
        args.issue,
        user_notes=args.notes,
        auto_execute=args.auto_execute,
        config_path=Path(args.config) if args.config else None,
    )
    print(json.dumps(payload, indent=2, default=_json_default))
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
