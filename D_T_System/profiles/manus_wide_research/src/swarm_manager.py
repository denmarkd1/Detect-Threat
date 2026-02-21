"""Support for coordinating Manus-style agent swarms."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import json
import uuid

from D_T_System.src.puter_zen_mcp_bridge import create_puter_bridge


@dataclass(slots=True)
class HiveExecutionPlan:
    """Defines how a wide-research job should be executed by the swarm."""

    job_id: str
    query: str
    guidance: str
    base_prompt: str
    target_agent_count: int
    mode: str
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class SwarmExecutionSummary:
    """Summary produced after the swarm completes an execution plan."""

    findings: List[Dict[str, Any]]
    artifacts: Dict[str, str]
    timeline: List[Dict[str, Any]]
    notes: str


class SwarmManager:
    """Handles lifecycle of Puter-backed agents working in concert."""

    def __init__(
        self,
        *,
        workspace_root: Path,
        profile_root: Path,
        config: Any,
    ) -> None:
        self.workspace_root = workspace_root
        self.profile_root = profile_root
        self.config = config
        self.logger = logging.getLogger("SwarmManager")

        self._active_sessions: Dict[str, Any] = {}
        self._lock = asyncio.Lock()
        self._semaphore = asyncio.Semaphore(config.max_parallel_agents)

        self._bridge = create_puter_bridge(str(workspace_root))

    async def execute_plan(self, plan: HiveExecutionPlan) -> Dict[str, Any]:
        """Execute the supplied plan and return a serialisable summary."""

        async with self._lock:
            self.logger.info(
                "🧪 Launching Manus-style swarm for job %s with %s agents",
                plan.job_id,
                plan.target_agent_count,
            )
            self._active_sessions[plan.job_id] = {
                "started": datetime.utcnow().isoformat(),
                "plan": plan,
            }

        try:
            summary = await self._run_swarm(plan)
            result = {
                "findings": summary.findings,
                "artifacts": summary.artifacts,
                "timeline": summary.timeline,
                "notes": summary.notes,
            }
            return result
        finally:
            async with self._lock:
                self._active_sessions.pop(plan.job_id, None)

    async def _run_swarm(self, plan: HiveExecutionPlan) -> SwarmExecutionSummary:
        """Execute the plan via the Puter bridge."""

        job_dir = self.profile_root / "runtime" / plan.job_id
        job_dir.mkdir(parents=True, exist_ok=True)

        start_event = {
            "timestamp": datetime.utcnow().isoformat(),
            "event": "swarm_initialised",
            "details": {
                "mode": plan.mode,
                "agents": plan.target_agent_count,
            },
        }

        tasks = [
            asyncio.create_task(
                self._run_agent(
                    index=index,
                    total=plan.target_agent_count,
                    plan=plan,
                    job_dir=job_dir,
                )
            )
            for index in range(plan.target_agent_count)
        ]

        agent_results = await asyncio.gather(*tasks, return_exceptions=True)

        findings: List[Dict[str, Any]] = []
        artifacts: Dict[str, str] = {}
        timeline: List[Dict[str, Any]] = [start_event]

        for result in agent_results:
            if isinstance(result, Exception):
                self.logger.error("Agent execution failed: %s", result)
                timeline.append(
                    {
                        "timestamp": datetime.utcnow().isoformat(),
                        "event": "agent_failed",
                        "details": {"error": str(result)},
                    }
                )
                continue

            findings.append(result["finding"])
            artifacts.update(result["artifacts"])
            timeline.extend(result["timeline"])

        notes = "Aggregated results from Manus wide-research swarm."

        findings.sort(key=lambda item: item.get("confidence", 0), reverse=True)

        return SwarmExecutionSummary(
            findings=findings,
            artifacts=artifacts,
            timeline=timeline,
            notes=notes,
        )

    async def _run_agent(
        self,
        *,
        index: int,
        total: int,
        plan: HiveExecutionPlan,
        job_dir: Path,
    ) -> Dict[str, Any]:
        agent_id = f"agent_{index + 1:02d}"
        request_id = uuid.uuid4().hex[:8]

        prompt = self._build_agent_prompt(
            plan=plan,
            agent_id=agent_id,
            agent_index=index,
            total_agents=total,
        )

        timeline: List[Dict[str, Any]] = [
            {
                "timestamp": datetime.utcnow().isoformat(),
                "event": "agent_started",
                "details": {
                    "agent_id": agent_id,
                    "request_id": request_id,
                },
            }
        ]

        async with self._semaphore:
            response = await self._bridge.process_request(
                request_type=self._select_request_type(plan),
                content=prompt,
                priority=self._select_priority(plan),
                system_prompt=plan.base_prompt,
                max_tokens=4096,
            )

        model_used = response.get("model_used") or response.get("puter_model")
        completion_event = {
            "timestamp": datetime.utcnow().isoformat(),
            "event": "agent_completed",
            "details": {
                "agent_id": agent_id,
                "request_id": request_id,
                "model": model_used,
                "processing_time": response.get("processing_time"),
            },
        }
        timeline.append(completion_event)

        raw_output = response.get("content", "")
        artifact_path = job_dir / f"{agent_id}_{request_id}.json"
        artifact_path.write_text(raw_output, encoding="utf-8")

        finding = self._parse_agent_output(
            agent_id=agent_id,
            raw_output=raw_output,
            artifact_path=artifact_path,
        )
        finding.setdefault("model", model_used)
        finding.setdefault("processing_time", response.get("processing_time"))

        return {
            "finding": finding,
            "artifacts": {artifact_path.name: str(artifact_path)},
            "timeline": timeline,
        }

    def _build_agent_prompt(
        self,
        *,
        plan: HiveExecutionPlan,
        agent_id: str,
        agent_index: int,
        total_agents: int,
    ) -> str:
        focus_hint = self._derive_focus_hint(plan, agent_index, total_agents)
        return (
            f"You are {agent_id} in a Manus wide-research swarm (agent {agent_index + 1} of {total_agents}).\n"
            f"Primary query: {plan.query}\n"
            f"Specific focus: {focus_hint}\n"
            f"Additional guidance: {plan.guidance}\n"
            "Deliver unique findings that complement the swarm."
        )

    def _derive_focus_hint(
        self,
        plan: HiveExecutionPlan,
        agent_index: int,
        total_agents: int,
    ) -> str:
        keywords: List[str] = plan.metadata.get("subtopics", [])
        if keywords:
            return keywords[agent_index % len(keywords)]
        if plan.mode == "solo":
            return "Provide a comprehensive overview."
        quadrant = agent_index % 4
        return {
            0: "focus on quantitative data and statistics",
            1: "focus on qualitative insights and expert commentary",
            2: "focus on historical context and comparable cases",
            3: "focus on emerging trends and forward-looking analysis",
        }[quadrant]

    def _select_request_type(self, plan: HiveExecutionPlan) -> str:
        if plan.mode == "solo":
            return "general"
        if plan.metadata.get("urgency", 5) >= 8:
            return "critical"
        if plan.metadata.get("priority", 5) >= 7:
            return "priority"
        return "general"

    def _select_priority(self, plan: HiveExecutionPlan) -> int:
        return int(plan.metadata.get("priority", plan.metadata.get("urgency", 5)))

    def _parse_agent_output(
        self,
        *,
        agent_id: str,
        raw_output: str,
        artifact_path: Path,
    ) -> Dict[str, Any]:
        try:
            data = json.loads(raw_output)
        except json.JSONDecodeError:
            self.logger.warning(
                "Agent %s returned non-JSON output; storing as plain text", agent_id
            )
            data = {
                "summary": raw_output[:4000],
                "key_points": [],
                "sources": [],
                "confidence": 0.3,
            }

        data.setdefault("agent_id", agent_id)
        data.setdefault("summary", "No summary provided")
        data.setdefault("key_points", [])
        data.setdefault("sources", [])
        confidence = data.get("confidence")
        if not isinstance(confidence, (int, float)):
            confidence = 0.5
        data["confidence"] = max(0.0, min(float(confidence), 1.0))
        data["artifact"] = str(artifact_path)
        return data

    async def close(self) -> None:
        """Release any long-lived resources."""

        async with self._lock:
            self._active_sessions.clear()
