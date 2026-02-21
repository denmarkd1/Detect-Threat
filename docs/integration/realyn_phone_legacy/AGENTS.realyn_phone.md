# AGENTS.md

## Scope
This file applies to the entire `/home/danicous/Realyn Phone` workspace.

## Mission
Build and maintain a defensive Android watchdog workflow that helps detect risky access patterns, stalkerware indicators, and suspicious security configuration changes on Realyn's device.

## Global Rules
- Defensive security only. Do not add offensive or persistence mechanisms.
- Prefer reproducible CLI workflows over ad-hoc manual steps.
- Log every scan result with a timestamp for auditability.
- Treat this workspace as a D_T satellite and follow memory-first + code-review-first workflow for non-trivial changes.

## D_T Satellite Files
- `D_T_System/satellite_config.json`: identity and scope of this satellite.
- `D_T_System/integration_policy.json`: workflow and safety constraints.

## Module Map
- `android-watchdog/`: Android app (`DT Scanner`) with one-time and active scan modes.
- `docs/`: GitHub Pages-ready support, donation, privacy, and terms pages.
- `scripts/`: shell entrypoints for setup and operations.
- `tools/`: local dependencies installed without system-wide package changes.
- `watchdog/`: Python scanner and detection logic.
- `state/`: generated baselines and scan snapshots.
- `alerts/`: generated human-readable alerts.
- `logs/`: execution logs.

## Verification Commands
- `bash scripts/setup/install_platform_tools.sh`
- `bash scripts/ops/check_adb_connection.sh`
- `python3 watchdog/watchdog.py --help`
- `python3 watchdog/watchdog.py scan`
