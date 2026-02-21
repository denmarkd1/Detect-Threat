# Security Workspace - Unified Credential Defense + Android Watchdog

This workspace merges credential protection workflows and Android watchdog monitoring into one defensive-security codebase.

## 1) Workspace modules
- `src/credential_defense/`: local-first credential defense CLI and workflows.
- `android-watchdog/`: Android app (`DT Guardian`) for one-time scanning, active watchdog mode, credential-defense actions, overlay assistant, and update checks.
- `watchdog/`: Python watchdog scanner and detection logic.
- `scripts/`: operational scripts for credential defense and ADB/watchdog operations.
- `config/`: credential-defense configuration.
- `data/`: encrypted vault and credential-defense runtime state.
- `state/`, `alerts/`, `logs/`: watchdog baselines, alerts, and execution logs.
- `docs/`: GitHub Pages support/donation/privacy/terms pages.
- `D_T_System/`: D_T satellite configuration and routing policy.

## 2) D_T satellite
The local D_T satellite is configured in:
- `D_T_System/satellite_config.json`
- `D_T_System/integration_policy.json`

## 3) Credential defense quick start
Install dependencies:

```bash
cd /home/danicous/security
python3 -m pip install -e .
```

Initialize secure local vault:

```bash
credential-defense init
```

Import browser exports from `imports/`:

```bash
credential-defense import-exports --imports-dir imports
```

Run guided triage:

```bash
credential-defense session --online-password-check --online-email-check
```

Run queued actions (rotate/delete workflows):

```bash
credential-defense run-actions
```

Dual-boot watchdog helpers:

```bash
credential-defense watchdog-status --show-paths
credential-defense watchdog-daemon --interval 60
```

Linux helper script:
- `scripts/run_watchdog_linux.sh`

Windows helper script:
- `scripts/windows_watchdog.ps1`

## 4) Android watchdog quick start
Install local Android platform-tools (ADB):

```bash
bash scripts/setup/install_platform_tools.sh
source scripts/setup/adb_env.sh
```

Verify Android connection:

```bash
bash scripts/ops/check_adb_connection.sh
```

Run CLI watchdog scan:

```bash
python3 watchdog/watchdog.py scan
```

Run continuous CLI watchdog mode:

```bash
bash scripts/ops/run_watchdog.sh
```

Build and install Android app:

```bash
source scripts/setup/java_env.sh
cd android-watchdog
./gradlew assembleDebug
../tools/android/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install local JDK 17 for Android builds (non-root, workspace-local):

```bash
bash scripts/setup/install_jdk17_local.sh
source scripts/setup/java_env.sh
```

Run on-device validation flow for overlay + queued credential rotation UX:

```bash
bash scripts/ops/validate_android_overlay_queue.sh
```

Grant/revoke/test lifetime entitlement on connected debug device:

```bash
bash scripts/ops/manage_lifetime_entitlement.sh grant
bash scripts/ops/manage_lifetime_entitlement.sh revoke
bash scripts/ops/manage_lifetime_entitlement.sh status
```

Build signed release artifacts for Play upload (requires signing env vars):

```bash
bash scripts/ops/build_play_release.sh
```

Run local support hub (AI chat hotline + FIFO ticket queue + embedded support page):

```bash
bash scripts/ops/run_support_hub.sh
```

If testing feedback API from a USB-connected physical Android device, map device port 8787 to local hub:

```bash
tools/android/platform-tools/adb reverse tcp:8787 tcp:8787
```

Then open:

```text
http://localhost:8787/
```

In-app Android capabilities:
- Watchdog one-time and continuous baseline-diff scanning
- Credential Defense Center for local password generation and rotation action queueing
- Overlay assistant (display-over-other-apps permission required) for copy/open support during password changes
- Update check against a remote JSON manifest (`docs/android/latest.json` sample schema)

## 5) Docs and support pages
GitHub Pages-ready files are in `docs/`:
- `docs/index.html`
- `docs/privacy.html`
- `docs/terms.html`
- `docs/patreon-startup.html`
- `docs/donation-links-template.md`

Local support hub API routes (served by `credential-defense support-server`):
- `GET /api/support/health`
- `POST /api/support/chat`
- `POST /api/support/feature-request`
- `POST /api/support/problem-report`
- `POST /api/support/feedback`
- `GET /api/support/feedback`
- `GET /api/support/tickets`

## 6) Security model
- Credential data is encrypted at rest and kept local.
- Raw passwords are not sent to online APIs.
- Online password checks are k-anonymity prefix based.
- Android watchdog workflows are defensive and non-root by design.

## 7) Important constraints
- This is defensive monitoring and guided remediation, not a full forensic guarantee.
- Fully automatic cross-site delete/reset flows remain limited by MFA/CAPTCHA/site-specific controls.
- Keep vault passphrases private and offline.
