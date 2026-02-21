"""Helpers for loading Manus-style prompt templates."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict


@dataclass(slots=True)
class PromptRegistry:
    """Stores prompt templates used by the profile."""

    prompts: Dict[str, str]

    @classmethod
    def load(cls, directory: Path) -> "PromptRegistry":
        """Load prompt templates from the given directory."""

        prompts: Dict[str, str] = {}
        if not directory.exists():
            return cls(prompts)

        for path in directory.glob("*.md"):
            prompts[path.stem] = path.read_text(encoding="utf-8")

        metadata_path = directory / "prompts.json"
        if metadata_path.exists():
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            prompts.update(metadata.get("inline_prompts", {}))

        return cls(prompts)

    def get_prompt(self, key: str) -> str:
        """Return a prompt template by key."""

        if key not in self.prompts:
            raise KeyError(f"Prompt '{key}' not found in registry")
        return self.prompts[key]

    def render_guidance(self, guidance: str) -> str:
        """Wrap guidance into a standard format."""

        template = self.prompts.get("guidance_wrapper")
        if not template:
            return guidance
        return template.format(guidance=guidance)
