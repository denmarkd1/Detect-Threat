# AGENTS.md - `/home/danicous/security`

## Workspace purpose
- Operate a unified defensive-security workspace that combines:
  - local-first credential defense for family account security
  - Android watchdog scanning for risky access patterns and suspicious security configuration changes

## Hard rules
- Defensive security only. Do not add offensive tooling or persistence mechanisms.
- Keep sensitive credential data local to this workspace.
- Never print or persist raw passwords in logs, markdown, or git-tracked files.
- Never send raw passwords to online services; only k-anonymity hash-prefix checks are allowed.
- Require explicit user confirmation for destructive account actions (closure/deletion/reset flows).
- Log every watchdog scan result with timestamps for auditability.
- Prefer reproducible CLI workflows over ad-hoc manual steps.

## Priority order (credential defense)
- Process account classes in this order:
  1. email
  2. banking
  3. social
  4. developer
  5. other

## Family coverage
- Support at least two owners: `parent` and `son`.
- Owner detection should use configurable email patterns in `config/workspace_settings.json`.

## D_T integration
- Use local satellite under `D_T_System/`.
- Keep workspace category/capabilities tuned for cybersecurity.
- Follow memory-first + code-review discipline for non-trivial changes.

## Key paths
- Credential defense source: `src/credential_defense/`
- Android app source: `android-watchdog/`
- Android CLI watchdog: `watchdog/`
- Ops scripts: `scripts/ops/`, `scripts/setup/`
- Security configs: `config/workspace_settings.json`, `config/site_profiles.json`
- Credential state: `data/`
- Android watchdog state and alerts: `state/`, `alerts/`, `logs/`
- Support website docs: `docs/`

## Verification commands
- `python3 -m pip install -e .`
- `credential-defense --help`
- `python3 watchdog/watchdog.py --help`
- `bash scripts/setup/install_platform_tools.sh`
- `bash scripts/ops/check_adb_connection.sh`
