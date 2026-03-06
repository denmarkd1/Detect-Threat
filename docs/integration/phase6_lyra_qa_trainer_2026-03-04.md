# Phase 6 - Lyra QA Trainer (Device-Backed)

Date: 2026-03-04
Last updated: 2026-03-06

## Purpose

`lyra_beta_trainer.py` runs a deterministic, device-backed QA sweep before Play submission.

It validates:

- local Python and watchdog CLI readiness,
- local D_T memory sweep integrity for phase/fix entities,
- roadmap and audit coverage for phases 0 to 6,
- phase hardening artifact presence (MASVS, policy/play, rollout, pricing, tutorial, trainer docs),
- memory-derived regression markers for key fixes (false-positive tuning, scan routing, incident assistant flow, vault hardening, startup hydration ordering),
- incident-assistant dedicated-screen split from scan-results surface,
- precommit guardrails,
- Android lint/unit/build checks,
- ADB device connection,
- debug APK install and launch,
- deterministic monkey stress events (configurable seed/event count),
- watchdog baseline/scan runs,
- full logcat fatal/ANR scan for the app package,
- final force-stop cleanup.

## Run

```bash
python3 scripts/ops/lyra_beta_trainer.py
```

Optional flags:

```bash
python3 scripts/ops/lyra_beta_trainer.py --serial <DEVICE_SERIAL>
python3 scripts/ops/lyra_beta_trainer.py --skip-monkey-events
python3 scripts/ops/lyra_beta_trainer.py --monkey-events 300 --monkey-seed 424242
python3 scripts/ops/lyra_beta_trainer.py --skip-python-bootstrap
```

## Output artifacts

Reports are written to `logs/lyra_qa/`:

- `lyra_qa_report_<UTC_TIMESTAMP>.json`
- `lyra_qa_report_<UTC_TIMESTAMP>.md`

Exit code:

- `0` = all checks passed.
- `1` = one or more checks failed.

## Operator workflow

1. Connect the target Android test device (USB debugging authorized).
2. Run the trainer command.
3. Manually verify scan-results UX split:
   - Open Scan results.
   - Tap `Start incident` and confirm Incident assistant opens as a separate screen.
   - Confirm `Work on this now` renders app identity for high/medium/low incidents as:
     compact app icon + bold common app name (when package metadata is available).
   - Confirm both `Apply fix for me` and `Guide me step-by-step` first ask whether to apply recommended best settings.
   - Confirm decision copy clearly states when Android blocks direct auto-change and manual taps are required.
   - Confirm Apply path can request required Android permission before recommended auto actions.
   - On manual recommended settings, confirm dialog shows `Path pack:` and device-appropriate OEM wording
     (MIUI/Samsung One UI/Google Pixel/Generic Android).
   - Confirm `Open with overlay guide` is available and requests overlay permission when needed.
   - After first-time overlay permission grant, confirm flow returns to manual dialog and user can tap
     `Open with overlay guide` again (no broken handoff).
   - Confirm tap-target wording is explicit by OEM (`App permissions` vs `Permissions`) and does not use vague
     "risky permissions" language.
   - Confirm risky permission list is explicit (`Camera`, `Microphone`, `Location`, `Contacts`, `Phone`, `SMS`,
     `Files/Media`) in the manual guidance.
   - On Samsung/MIUI devices, confirm manual dialog warns that some native security/settings surfaces may hide overlays
     temporarily and verify manual exact-tap fallback remains usable.
   - Confirm compact overlay mode displays one current target at a time and supports:
     `Previous`, `Done this step`, `Finish guide`.
   - Confirm high-risk containment still exposes uninstall in automatic flow after recommended settings.
   - Use `Back to scan results` and confirm return path is clean.
4. Fix any failed checks and re-run until clean.
5. Attach the latest markdown report to release evidence.

## Latest passing run (this workspace)

- `logs/lyra_qa/lyra_qa_report_20260304T102544Z.md`
- `logs/lyra_qa/lyra_qa_report_20260304T102544Z.json`
