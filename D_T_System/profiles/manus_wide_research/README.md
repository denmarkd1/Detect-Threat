# Manus Wide Research Profile

This profile packages the orchestration logic required to run Manus-style wide
research workflows from the D_T system hub. It provides:

- a configurable `WideResearchOrchestrator` entry point for the hub,
- a `SwarmManager` abstraction that coordinates Puter-backed agents in parallel,
- prompt templates inspired by Manus "Wide Research" operating procedures.

The swarm manager now connects to the existing `PuterZenMCPBridge`, allowing
parallel Manus-style agents to execute real research assignments and emit
structured findings. Further enhancements (e.g., advanced aggregation and
post-processing) can build on this foundation.
