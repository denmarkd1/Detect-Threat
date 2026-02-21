#!/usr/bin/env python3
"""Compatibility D_T core for satellite-style workspaces.

This module provides the legacy import entrypoint:
`from systems.D_T_System.src.dt_system_core import process_issue_with_dt`

Behavior:
- tries satellite routing first (`D_T_System/bootstrap_router.py`, then
  `D_T_System/dt_satellite_router.py`)
- falls back to local memory/context/resolution logging when hub routing is
  unavailable in the current environment
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional, Union

TRIGGER_PHRASES = (
    "DT_FULL_AUTO",
    "AUTO_RESOLVE",
    "FULL_DT_PROCESS",
    "DT_AUTORUN",
    "COMPLETE_DT",
)


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _utc_now_iso() -> str:
    return _utc_now().isoformat().replace("+00:00", "Z")


def _legacy_timestamp() -> str:
    return datetime.utcnow().isoformat()


def _resolve_workspace_root(workspace_root: Optional[Union[str, Path]] = None) -> Path:
    if workspace_root is not None:
        return Path(workspace_root).expanduser().resolve()

    env_root = os.environ.get("DT_WORKSPACE_ROOT")
    if env_root:
        return Path(env_root).expanduser().resolve()

    return Path.cwd().resolve()


def _extract_trigger(issue_input: str) -> tuple[str, bool]:
    text = issue_input.strip()
    if not text:
        return text, False

    upper = text.upper()
    for trigger in TRIGGER_PHRASES:
        if trigger in upper:
            cleaned = text.replace(trigger, "").replace(trigger.lower(), "").strip(" -,:")
            return cleaned or issue_input.strip(), True

    return text, False


def _guess_issue_type(issue_text: str) -> str:
    lower = issue_text.lower()
    if any(token in lower for token in ("merge", "merged", "integration", "integrate")):
        return "integration"
    if any(token in lower for token in ("config", "setting", "policy", "profile")):
        return "configuration"
    if any(token in lower for token in ("error", "traceback", "exception", "crash", "fail")):
        return "error_bug"
    if any(token in lower for token in ("security", "breach", "watchdog", "credential", "android")):
        return "security"
    return "general"


def _load_module_from_path(module_path: Path, module_name: str):
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    if spec is None or spec.loader is None:
        raise ImportError(f"Unable to load module spec from {module_path}")

    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _try_satellite_routing(
    workspace_root: Path,
    issue_input: str,
    user_notes: Optional[str],
    auto_execute: bool,
) -> tuple[Optional[dict[str, Any]], Optional[str]]:
    candidates = (
        workspace_root / "D_T_System" / "bootstrap_router.py",
        workspace_root / "D_T_System" / "dt_satellite_router.py",
    )

    errors: list[str] = []

    for module_path in candidates:
        if not module_path.exists():
            errors.append(f"{module_path.name}: missing")
            continue

        try:
            module_name = f"_dt_router_{module_path.stem}"
            module = _load_module_from_path(module_path, module_name)
            route_issue = getattr(module, "route_issue", None)
            if not callable(route_issue):
                errors.append(f"{module_path.name}: route_issue() not found")
                continue

            payload = route_issue(
                issue_input,
                user_notes=user_notes,
                auto_execute=auto_execute,
            )
            if isinstance(payload, dict):
                return payload, None
            return {"result": payload}, None
        except Exception as exc:  # pragma: no cover - runtime-dependent path
            errors.append(f"{module_path.name}: {type(exc).__name__}: {exc}")

    return None, " | ".join(errors) if errors else "No satellite router available"


def _runtime_root(workspace_root: Path) -> Path:
    return workspace_root / "systems" / "D_T_System"


def _ensure_runtime_dirs(base: Path) -> None:
    (base / "data" / "context").mkdir(parents=True, exist_ok=True)
    (base / "resolutions").mkdir(parents=True, exist_ok=True)
    (base / "logs").mkdir(parents=True, exist_ok=True)
    (base / "phase_tracking").mkdir(parents=True, exist_ok=True)


def _phase_tracking_path(base: Path) -> Path:
    return base / "phase_tracking" / "phase_progress.json"


def _ensure_phase_tracking(base: Path) -> None:
    path = _phase_tracking_path(base)
    if path.exists():
        payload = json.loads(path.read_text(encoding="utf-8"))
    else:
        payload = {
            "phases": {
                "triage": {"completion_percentage": 0.0, "status": "not_started"},
                "implementation": {"completion_percentage": 0.0, "status": "not_started"},
                "verification": {"completion_percentage": 0.0, "status": "not_started"},
                "documentation": {"completion_percentage": 0.0, "status": "not_started"},
            }
        }
    payload["updated_at"] = _utc_now_iso()
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def _append_log(base: Path, message: str) -> None:
    log_file = base / "logs" / f"dt_system_{_utc_now().strftime('%Y%m%d')}.log"
    with log_file.open("a", encoding="utf-8") as handle:
        handle.write(f"{_utc_now_iso()} {message}\n")


@dataclass
class CertaintyAssessment:
    level: str
    confidence_percentage: float
    missing_information: list[str]
    clarifying_questions: list[str]
    assessment_reasoning: str


@dataclass
class DTResolution:
    issue_id: str
    issue_context: dict[str, Any]
    certainty_assessment: CertaintyAssessment
    is_existing_problem: bool
    memory_references: list[str]
    required_resources: list[str]
    todo_list: list[str]
    task_steps: list[str]
    code_review_handoff: str
    testing_requirements: list[str]
    phase_impact: dict[str, Any]
    completion_status: dict[str, Any]
    zen_council: Optional[dict[str, Any]] = None
    automation_executed: bool = False
    automation_result: Optional[dict[str, Any]] = None

    @property
    def resolution_id(self) -> str:
        return self.issue_id

    @property
    def status(self) -> str:
        if isinstance(self.completion_status, dict):
            return str(self.completion_status.get("status", "unknown"))
        return "unknown"

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["certainty_assessment"] = asdict(self.certainty_assessment)
        return payload


def _build_handoff(issue_input: str, issue_type: str, route_error: Optional[str]) -> str:
    lines = [
        "# D_T SYSTEM - CODE REVIEW HANDOFF",
        "",
        "## Issue Analysis",
        f"**Type**: {issue_type}",
        "",
        "## Problem Description",
        issue_input.strip() or "(none)",
        "",
    ]
    if route_error:
        lines.extend(
            [
                "## Routing Note",
                "Satellite routing was unavailable; local fallback context/resolution was recorded.",
                f"Details: {route_error}",
                "",
            ]
        )

    lines.extend(
        [
            "## Code Review Instructions",
            "1. Memory-first analysis using local D_T memory artifacts.",
            "2. Validate required resources and runtime constraints.",
            "3. Implement fixes and run verification commands.",
            "4. Update D_T memory/context after completion.",
        ]
    )

    return "\n".join(lines)


def _write_memory_entity(
    base: Path,
    *,
    issue_id: str,
    issue_type: str,
    context_path: Path,
) -> None:
    memory_file = base / "data" / "mcp_memory.jsonl"
    memory_file.parent.mkdir(parents=True, exist_ok=True)

    entry = {
        "type": "entity",
        "name": f"dt_resolution_{issue_id}",
        "entityType": "dt_resolution",
        "observations": [
            f"Issue ID: {issue_id}",
            f"Issue Type: {issue_type}",
            "Certainty: low (0.0%)",
            f"Workspace: {base.parent.parent}",
            f"Context: {context_path}",
            f"Updated: {_utc_now_iso()}",
        ],
    }
    with memory_file.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry) + "\n")


def _persist_local_resolution(
    workspace_root: Path,
    issue_input: str,
    user_notes: Optional[str],
    *,
    automation_requested: bool,
    route_error: Optional[str],
) -> DTResolution:
    runtime = _runtime_root(workspace_root)
    _ensure_runtime_dirs(runtime)
    _ensure_phase_tracking(runtime)

    issue_id = f"DT_{_utc_now().strftime('%Y%m%d_%H%M%S')}"
    issue_type = _guess_issue_type(issue_input)

    clarifying_questions = [
        "What exact action or result should happen after autorun?",
        "What is the expected output format for the D_T handoff in this workspace?",
    ]

    issue_context = {
        "original_input": issue_input,
        "user_notes": user_notes,
        "issue_type": issue_type,
        "affected_components": [],
        "error_signatures": [],
        "reproduction_steps": [],
        "expected_behavior": "",
        "actual_behavior": "",
        "environment_info": {},
        "constraints": [],
    }

    certainty = CertaintyAssessment(
        level="low",
        confidence_percentage=0.0,
        missing_information=[
            "Detailed expected behavior",
            "Exact failure signature or logs",
            "Module-specific validation criteria",
        ],
        clarifying_questions=clarifying_questions,
        assessment_reasoning="Fallback mode used because hub routing is unavailable from this workspace runtime.",
    )

    completion_status = {
        "status": "clarification_needed",
        "message": "D_T local fallback completed; provide more details for deeper automated resolution.",
        "clarifying_questions": clarifying_questions,
    }

    resolution = DTResolution(
        issue_id=issue_id,
        issue_context=issue_context,
        certainty_assessment=certainty,
        is_existing_problem=False,
        memory_references=[],
        required_resources=[],
        todo_list=[],
        task_steps=[],
        code_review_handoff=_build_handoff(issue_input, issue_type, route_error),
        testing_requirements=[],
        phase_impact={},
        completion_status=completion_status,
        zen_council=None,
        automation_executed=automation_requested,
        automation_result={
            "status": "deferred",
            "mode": "local_fallback",
            "reason": route_error or "satellite routing unavailable",
        }
        if automation_requested
        else None,
    )

    context_payload = {
        "automation_executed": automation_requested,
        "created_at": _utc_now_iso(),
        "issue_id": issue_id,
        "issue_type": issue_type,
        "todo_count": 0,
        "updated_at": _utc_now_iso(),
        "workspace_root": str(workspace_root),
    }

    context_path = runtime / "data" / "context" / f"{issue_id}.context.json"
    context_path.write_text(json.dumps(context_payload, indent=2), encoding="utf-8")

    resolution_payload = {
        "issue_id": issue_id,
        "timestamp": _legacy_timestamp(),
        "issue_context": issue_context,
        "certainty_level": resolution.certainty_assessment.level,
        "confidence_percentage": resolution.certainty_assessment.confidence_percentage,
        "is_existing_problem": resolution.is_existing_problem,
        "memory_references": resolution.memory_references,
        "required_resources": resolution.required_resources,
        "todo_list": resolution.todo_list,
        "task_steps": resolution.task_steps,
        "testing_requirements": resolution.testing_requirements,
        "phase_impact": resolution.phase_impact,
        "completion_status": resolution.completion_status,
        "zen_council": resolution.zen_council,
        "automation_executed": resolution.automation_executed,
        "automation_result": resolution.automation_result,
    }
    resolution_path = runtime / "resolutions" / f"{issue_id}.json"
    resolution_path.write_text(json.dumps(resolution_payload, indent=2), encoding="utf-8")

    _write_memory_entity(runtime, issue_id=issue_id, issue_type=issue_type, context_path=context_path)
    _append_log(runtime, f"Fallback local D_T resolution recorded: {issue_id}")

    print(f"[DT] Fallback local resolution recorded: {issue_id}")
    print(f"[DT] Resolution file: {resolution_path}")

    return resolution


def _resolution_from_route_payload(
    issue_input: str,
    user_notes: Optional[str],
    payload: dict[str, Any],
    *,
    automation_requested: bool,
) -> DTResolution:
    issue_id = str(
        payload.get("issue_id")
        or payload.get("resolution_id")
        or f"DT_{_utc_now().strftime('%Y%m%d_%H%M%S')}"
    )

    issue_type = _guess_issue_type(issue_input)
    completion = payload.get("completion_status")
    if not isinstance(completion, dict):
        completion = {"status": payload.get("status", "routed")}

    certainty = CertaintyAssessment(
        level=str(payload.get("certainty_level", "moderate")),
        confidence_percentage=float(payload.get("confidence_percentage", 70.0)),
        missing_information=[],
        clarifying_questions=[],
        assessment_reasoning="Resolution routed via D_T satellite router.",
    )

    handoff = payload.get("code_review_handoff")
    if not isinstance(handoff, str):
        handoff = _build_handoff(issue_input, issue_type, None)

    return DTResolution(
        issue_id=issue_id,
        issue_context={
            "original_input": issue_input,
            "user_notes": user_notes,
            "issue_type": issue_type,
            "route_payload": payload,
        },
        certainty_assessment=certainty,
        is_existing_problem=bool(payload.get("is_existing_problem", False)),
        memory_references=list(payload.get("memory_references", [])),
        required_resources=list(payload.get("required_resources", [])),
        todo_list=list(payload.get("todo_list", [])),
        task_steps=list(payload.get("task_steps", [])),
        code_review_handoff=handoff,
        testing_requirements=list(payload.get("testing_requirements", [])),
        phase_impact=dict(payload.get("phase_impact", {})),
        completion_status=completion,
        zen_council=payload.get("zen_council") if isinstance(payload.get("zen_council"), dict) else None,
        automation_executed=automation_requested,
        automation_result=payload.get("automation_result") if isinstance(payload.get("automation_result"), dict) else None,
    )


def process_issue_with_dt(
    issue_input: str,
    user_notes: Optional[str] = None,
    workspace_root: Optional[Union[str, Path]] = None,
) -> DTResolution:
    """Primary workspace entrypoint for D_T issue processing."""
    workspace = _resolve_workspace_root(workspace_root)
    os.environ["DT_WORKSPACE_ROOT"] = str(workspace)

    cleaned_issue, trigger_detected = _extract_trigger(issue_input)
    if trigger_detected:
        print("[DT] Autorun trigger phrase detected.")

    payload, route_error = _try_satellite_routing(
        workspace,
        cleaned_issue,
        user_notes,
        auto_execute=trigger_detected,
    )

    if payload is not None:
        print("[DT] Routed via satellite router.")
        return _resolution_from_route_payload(
            cleaned_issue,
            user_notes,
            payload,
            automation_requested=trigger_detected,
        )

    print("[DT] Satellite routing unavailable; using local fallback.")
    if route_error:
        print(f"[DT] Routing details: {route_error}")
    return _persist_local_resolution(
        workspace,
        cleaned_issue,
        user_notes,
        automation_requested=trigger_detected,
        route_error=route_error,
    )


def _main() -> int:
    parser = argparse.ArgumentParser(description="D_T compatibility entrypoint")
    parser.add_argument("issue", help="Issue description to process")
    parser.add_argument("--notes", default=None, help="Optional notes")
    parser.add_argument("--json", action="store_true", help="Print full resolution payload as JSON")
    args = parser.parse_args()

    resolution = process_issue_with_dt(args.issue, user_notes=args.notes)
    if args.json:
        print(json.dumps(resolution.to_dict(), indent=2))
    else:
        print(f"Issue ID: {resolution.issue_id}")
        print(f"Status: {resolution.status}")

    return 0


# Keep compatibility with legacy import lookups.
sys.modules.setdefault("systems.D_T_System.src.dt_system_core", sys.modules[__name__])


if __name__ == "__main__":
    raise SystemExit(_main())
