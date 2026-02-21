"""Entry points for the Manus Wide Research profile."""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from .orchestrator import (
    WideResearchOrchestrator,
    WideResearchConfig,
    ResearchJob,
    ResearchResult,
)

__all__ = [
    "WideResearchOrchestrator",
    "WideResearchConfig",
    "ResearchJob",
    "ResearchResult",
    "create_orchestrator",
]


def create_orchestrator(
    workspace_root: Path,
    *,
    profile_root: Optional[Path] = None,
    config: Optional[WideResearchConfig] = None,
) -> WideResearchOrchestrator:
    """Factory used by the hub when bootstrapping the profile.

    Args:
        workspace_root: Root of the workspace requesting the profile.
        profile_root: Optional path to the provisioned profile bundle.
        config: Optional pre-built configuration.

    Returns:
        Configured :class:`WideResearchOrchestrator` instance.
    """

    resolved_profile_root = profile_root or Path(__file__).resolve().parents[1]
    effective_config = config or WideResearchConfig.from_profile(resolved_profile_root)
    return WideResearchOrchestrator(
        workspace_root=workspace_root,
        profile_root=resolved_profile_root,
        config=effective_config,
    )
