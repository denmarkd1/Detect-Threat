#!/usr/bin/env python3
"""Ensure workspace-level AGENTS.md enforces the D_T memory-first protocol."""
from __future__ import annotations

import json
import textwrap
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional

STANDARD_HEADER = "## D_T + memory-first standard"
STANDARD_BLOCK = textwrap.dedent(
    """
    ## D_T + memory-first standard
    - Memory-first: use MCP memory tools if available; otherwise search local memory logs.
    - D_T triggers: DT_FULL_AUTO | AUTO_RESOLVE | FULL_DT_PROCESS | DT_AUTORUN | COMPLETE_DT.
    - D_T entrypoint: `python3 -c \"from systems.D_T_System.src.dt_system_core import process_issue_with_dt; process_issue_with_dt('ISSUE_HERE')\"`
    - Code review: prioritize bugs, regressions, and missing tests; follow D_T handoff guidance.
    - Skills: run `dt-orchestrator` first when present, then the minimal specialized skill(s).
    """
).strip()


def _default_agents_template(workspace_root: Path) -> str:
    return textwrap.dedent(
        f"""
        # Agent instructions (scope: this directory and subdirectories)

        ## Scope and layout
        - This AGENTS.md applies to: `{workspace_root}` and below.
        - Keep instructions concise; link to docs for details.

        {STANDARD_BLOCK}

        ## Global conventions
        - Use `rg` for search.
        - Avoid destructive git commands unless explicitly requested.
        """
    ).strip() + "\n"


def _insert_standard_block(existing: str) -> str:
    lines = existing.splitlines()
    insert_idx = 0
    for i, line in enumerate(lines):
        if line.startswith("#"):
            insert_idx = i + 1
            break
    block_lines = ["", STANDARD_BLOCK, ""]
    updated = lines[:insert_idx] + block_lines + lines[insert_idx:]
    return "\n".join(updated).rstrip() + "\n"


def _push_protocol_update(
    workspace_root: Path,
    action: str,
    agents_path: Path,
    hub_root: Optional[Path] = None,
) -> None:
    payload = {
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "protocol": "AGENTS_STANDARD",
        "workspace": str(workspace_root),
        "action": action,
        "agents_path": str(agents_path),
    }
    update_name = f"update_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}_agents_standard.json"

    candidates = []
    if hub_root:
        candidates.append(hub_root / "D_T_System" / "hub" / "updates")
    candidates.append(workspace_root / "systems" / "D_T_System" / "hub" / "updates")
    candidates.append(workspace_root / "D_T_System" / "hub" / "updates")

    for target_root in candidates:
        try:
            target_root.mkdir(parents=True, exist_ok=True)
            target_file = target_root / update_name
            target_file.write_text(json.dumps(payload, indent=2), encoding="utf-8")
            return
        except Exception:
            continue


def ensure_agents_standard(
    workspace_root: Path,
    *,
    hub_root: Optional[Path] = None,
    logger=None,
    update_hub: bool = True,
) -> Dict[str, Any]:
    """Ensure AGENTS.md exists with the D_T standard block."""
    root = Path(workspace_root)
    agents_path = root / "AGENTS.md"
    action = "present"

    if not agents_path.exists():
        agents_path.write_text(_default_agents_template(root), encoding="utf-8")
        action = "created"
    else:
        existing = agents_path.read_text(encoding="utf-8")
        if STANDARD_HEADER not in existing:
            agents_path.write_text(_insert_standard_block(existing), encoding="utf-8")
            action = "updated"

    if logger:
        logger.info("AGENTS standard check: %s (%s)", action, agents_path)

    if update_hub and action in {"created", "updated"}:
        _push_protocol_update(root, action, agents_path, hub_root=hub_root)

    return {"status": action, "path": str(agents_path)}


__all__ = ["ensure_agents_standard", "STANDARD_HEADER", "STANDARD_BLOCK"]
