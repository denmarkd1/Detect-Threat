"""Wide research orchestration primitives for Manus-style agent swarms."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

from .prompt_registry import PromptRegistry
from .swarm_manager import HiveExecutionPlan, SwarmManager


@dataclass(slots=True)
class ResearchJob:
    """Represents a wide research request received from a satellite workspace."""

    job_id: str
    query: str
    guidance: str
    max_agents: int
    group_mode: str  # "solo", "hive", or "adaptive"
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class ResearchResult:
    """Aggregated outcome returned to the requesting workspace."""

    job_id: str
    findings: List[Dict[str, Any]]
    artifacts: Dict[str, Path]
    timeline: List[Dict[str, Any]]
    completion_time: datetime
    notes: str = ""


@dataclass(slots=True)
class WideResearchConfig:
    """Configuration for the Manus wide research profile."""

    profile_name: str
    max_parallel_agents: int
    hive_default_size: int
    hive_max_size: int
    control_loop_interval: float
    prompt_registry: PromptRegistry
    storage_root: Path

    @classmethod
    def from_profile(cls, profile_root: Path) -> "WideResearchConfig":
        """Build configuration by inspecting the provisioned profile bundle."""

        prompt_registry = PromptRegistry.load(profile_root / "prompts")
        storage_root = profile_root / "runtime"
        storage_root.mkdir(parents=True, exist_ok=True)

        return cls(
            profile_name="manus_wide_research",
            max_parallel_agents=24,
            hive_default_size=6,
            hive_max_size=24,
            control_loop_interval=1.0,
            prompt_registry=prompt_registry,
            storage_root=storage_root,
        )


class WideResearchOrchestrator:
    """Coordinates Manus-style wide research operations across Puter agents.

    The orchestrator maintains three responsibilities:
    1. Interpret incoming research jobs and derive execution plans.
    2. Delegate execution to :class:`SwarmManager`, which handles agent lifecycle.
    3. Normalise findings into a reusable artifact structure for the hub.
    """

    def __init__(
        self,
        *,
        workspace_root: Path,
        profile_root: Path,
        config: WideResearchConfig,
        swarm_manager: Optional[SwarmManager] = None,
    ) -> None:
        self.workspace_root = workspace_root
        self.profile_root = profile_root
        self.config = config
        self.logger = logging.getLogger("WideResearchOrchestrator")

        self.swarm_manager = swarm_manager or SwarmManager(
            workspace_root=workspace_root,
            profile_root=profile_root,
            config=config,
        )

    async def run_wide_research(self, job: ResearchJob) -> ResearchResult:
        """Process a wide research job using Manus-inspired orchestration.

        Steps performed:
            1. Build a high-level plan and convert it into swarm assignments.
            2. Execute assignments concurrently via :class:`SwarmManager`.
            3. Collate and normalise the artifacts and findings.
        """

        self.logger.info("🚀 Starting wide research job %s", job.job_id)

        plan = await self._build_execution_plan(job)
        self.logger.debug("Derived execution plan for %s: %s", job.job_id, plan)

        swarm_result = await self.swarm_manager.execute_plan(plan)
        aggregated = self._aggregate_results(job, swarm_result)

        self.logger.info("✅ Completed wide research job %s", job.job_id)
        return aggregated

    async def coordinate_research_task(
        self,
        query: str,
        context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """Compatibility wrapper expected by legacy Manus orchestrators."""

        context_data: Dict[str, Any] = dict(context or {})
        guidance = str(context_data.pop("guidance", "") or "")
        group_mode = str(context_data.pop("group_mode", "adaptive"))
        if group_mode not in {"solo", "hive", "adaptive"}:
            group_mode = "adaptive"
        max_agents_value = context_data.pop("max_agents", self.config.max_parallel_agents)
        try:
            max_agents = int(max_agents_value)
        except (TypeError, ValueError):
            max_agents = self.config.max_parallel_agents
        max_agents = max(1, min(max_agents, self.config.max_parallel_agents))
        job_identifier = str(
            context_data.pop(
                "job_id",
                f"wide_research_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}",
            )
        )

        job = ResearchJob(
            job_id=job_identifier,
            query=query,
            guidance=guidance,
            max_agents=max_agents,
            group_mode=group_mode,
            metadata=context_data,
        )

        research_result = await self.run_wide_research(job)
        artifacts = {name: str(path) for name, path in research_result.artifacts.items()}

        return {
            "status": "success",
            "job_id": research_result.job_id,
            "query": query,
            "guidance": guidance,
            "group_mode": job.group_mode,
            "max_agents": job.max_agents,
            "metadata": job.metadata,
            "findings": research_result.findings,
            "artifacts": artifacts,
            "timeline": research_result.timeline,
            "notes": research_result.notes,
            "completion_time": research_result.completion_time.isoformat() + "Z",
        }

    async def _build_execution_plan(self, job: ResearchJob) -> HiveExecutionPlan:
        """Translate a job into a hive execution plan."""

        base_prompt = self.config.prompt_registry.get_prompt("wide_research_base")
        guidance_prompt = self.config.prompt_registry.render_guidance(job.guidance)

        agent_count = self._select_agent_count(job)

        return HiveExecutionPlan(
            job_id=job.job_id,
            query=job.query,
            guidance=guidance_prompt,
            base_prompt=base_prompt,
            target_agent_count=agent_count,
            mode=job.group_mode,
            metadata=job.metadata,
        )

    def _select_agent_count(self, job: ResearchJob) -> int:
        """Pick an appropriate agent count based on job metadata."""

        if job.group_mode == "solo":
            return 1
        if job.group_mode == "hive":
            return min(self.config.hive_default_size, job.max_agents, self.config.max_parallel_agents)

        urgency = int(job.metadata.get("urgency", 5))
        complexity = int(job.metadata.get("complexity", 5))
        base = self.config.hive_default_size + (urgency + complexity) // 4
        return min(
            max(base, 2),
            job.max_agents,
            self.config.hive_max_size,
            self.config.max_parallel_agents,
        )

    def _aggregate_results(
        self,
        job: ResearchJob,
        swarm_result: Dict[str, Any],
    ) -> ResearchResult:
        """Normalise swarm output into a :class:`ResearchResult`."""

        findings: List[Dict[str, Any]] = swarm_result.get("findings", [])
        artifacts: Dict[str, Path] = {
            name: Path(path) for name, path in swarm_result.get("artifacts", {}).items()
        }
        timeline: List[Dict[str, Any]] = swarm_result.get("timeline", [])
        notes = swarm_result.get("notes", "")

        return ResearchResult(
            job_id=job.job_id,
            findings=findings,
            artifacts=artifacts,
            timeline=timeline,
            completion_time=datetime.utcnow(),
            notes=notes,
        )

    async def close(self) -> None:
        """Shutdown long-lived resources owned by the orchestrator."""

        await self.swarm_manager.close()

    async def __aenter__(self) -> "WideResearchOrchestrator":
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        await self.close()

    # Convenience synchronous wrapper -------------------------------------------------

    def run(self, job: ResearchJob) -> ResearchResult:
        """Run the research job synchronously (wrapper for legacy entry-points)."""

        return asyncio.run(self.run_wide_research(job))
