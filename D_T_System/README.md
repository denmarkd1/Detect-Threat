# D_T Satellite Package

This payload lets any workspace act as a D_T “satellite” and route requests to a central D_T hub.

## Contents
- `dt_satellite_router.py` – CLI/API helper that forwards issues or chat requests to the hub router.
- `satellite_config.json` – Workspace metadata (hub path, tags, required profiles).
- `integration_policy.json` – Optional council tuning (confidence floors, escalation triggers).
- `tools/manus_runtime_reducer.py` – Summaries Manus runtime artifacts into `summary.md` per job.
- `monitoring/agent_monitor_dashboard.py` – Optional Zen Manus monitoring script copied beside `systems/`.

## First Run
1. Place the `D_T_System` directory in your workspace root.
2. Call `python3 D_T_System/bootstrap_router.py "Describe your issue"`.
3. The bootstrap copies this package, registers the workspace, and syncs the monitoring dashboard.

After installation you may call `python3 D_T_System/dt_satellite_router.py` directly for routing, and run the runtime reducer whenever Manus escalations occur.

## Hub Path
`satellite_config.json` supports `"hub_path": "AUTO_DETECT"` which resolves:
- `DT_HUB_ROOT` / `D_T_HUB_ROOT` env vars, or
- `~/D_T_SYSTEM_HUB` if present
