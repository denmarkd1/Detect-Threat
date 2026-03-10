# Zen Precommit Timeout Remediation - February 25, 2026

## Issue summary
- `zen precommit` timed out repeatedly from this workspace.
- Timeout also reproduced on a tiny test repository, so the problem is not specific to project diff size.

## Reproduction snapshot
- Date: February 25, 2026
- Result: `tools/call` timeout at ~60 seconds for `zen/precommit`
- Verified healthy prerequisites:
  - Zen MCP server responds via `get_version`
  - Redis dependency is running (`bash scripts/ops/ensure_zen_redis.sh`)

## Root cause assessment
- Current transport deadline between client and `zen/precommit` is 60 seconds.
- `zen/precommit` can exceed that deadline and return a timeout even when environment dependencies are healthy.

## 2026-03-10 follow-up
- The same failure mode was reproduced again for `zen/codereview`, with the current MCP client cutting the call at about 120 seconds.
- Shared Zen provider inspection found a second-layer timeout mismatch:
  - `PuterBridgeProvider` waited 120 seconds before failing.
  - `CodexCLIProvider` fallback then allowed up to 180 seconds.
- Result: the primary provider could consume the whole client deadline before fallback even started, so the MCP client timed out first and never received a review result.

## 2026-03-10 mitigation
- Reduced the shared Zen provider budget so fallback can complete inside typical MCP deadlines:
  - `ZEN_PUTER_BRIDGE_REQUEST_TIMEOUT_SECONDS` defaults to `45`
  - `ZEN_CODEX_TIMEOUT_SECONDS` defaults to `60`
- Added workspace launcher exports in `scripts/ops/run_zen_mcp_with_dt_hub.sh` so local Zen launches inherit those safer defaults.
- Mirrored the same timeout env handling in the shared `puter_proven.py` path so alternate Puter bridge launches do not keep the older 120-second wait.

## Implemented fix
- Added deterministic local fallback script:
  - `scripts/ops/precommit_guard.sh`
- Added tracked git hook installer so commits do not depend on manual fallback:
  - `D_T_System/scripts/install_dt_workspace_tracking_hooks.sh`
  - `scripts/ops/install_precommit_hook.sh`
- Added workspace policy metadata:
  - `D_T_System/integration_policy.json` (`precommit_review` section)
- Added workspace references:
  - `README.md` usage commands
  - `AGENTS.md` verification command list

## Fallback workflow
1. Install the tracked hook once per clone:
   - `bash scripts/ops/install_precommit_hook.sh`
2. `git commit` now runs `scripts/ops/precommit_guard.sh` automatically on staged changes.
3. If you need the same checks before staging or against unstaged work:
   - `bash scripts/ops/precommit_guard.sh --include-unstaged`
4. `zen/precommit` remains optional/advisory until the upstream transport timeout is raised.

## What `precommit_guard.sh` validates
- Secret-assignment safety scan for changed text files (token names only, no values printed).
- Python syntax check (`python3 -m py_compile`) for changed `.py` files.
- Android compile check (`./gradlew :app:compileDebugKotlin`) when Android source/config files changed.
- Staged binary size audit with configurable warn/fail thresholds.

## Notes
- This remediation is defensive and local-first.
- Raw secret values are not printed by the fallback scanner.
- `zen/precommit` remains advisory in this workspace because the local fallback is still the enforced commit gate, but the shared Zen timeout path is now less likely to exhaust the MCP client deadline before fallback can run.
