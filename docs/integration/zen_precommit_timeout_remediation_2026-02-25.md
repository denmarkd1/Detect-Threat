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

## Implemented fix
- Added deterministic local fallback script:
  - `scripts/ops/precommit_guard.sh`
- Added workspace policy metadata:
  - `D_T_System/integration_policy.json` (`precommit_review` section)
- Added workspace references:
  - `README.md` usage commands
  - `AGENTS.md` verification command list

## Fallback workflow
1. Run baseline dependency health check:
   - `bash scripts/ops/ensure_zen_redis.sh`
2. Run fallback precommit checks:
   - `bash scripts/ops/precommit_guard.sh`
3. If needed, include unstaged changes:
   - `bash scripts/ops/precommit_guard.sh --include-unstaged`

## What `precommit_guard.sh` validates
- Secret-assignment safety scan for changed text files (token names only, no values printed).
- Python syntax check (`python3 -m py_compile`) for changed `.py` files.
- Android compile check (`./gradlew :app:compileDebugKotlin`) when Android source/config files changed.
- Staged binary size audit with configurable warn/fail thresholds.

## Notes
- This remediation is defensive and local-first.
- Raw secret values are not printed by the fallback scanner.
