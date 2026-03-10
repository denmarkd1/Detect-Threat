# Phase 6 - Lyra QA Trainer (Device-Backed)

Date: 2026-03-04
Last updated: 2026-03-10

## Purpose

`lyra_beta_trainer.py` runs a deterministic, device-backed QA sweep before Play submission.

It validates:

- local Python and watchdog CLI readiness,
- local D_T memory sweep integrity for phase/fix entities,
- D_T scope-confidence ladder coverage across the hub, security workspace runtime, security satellite, and the embedded Dark_Coder backend/satellite path,
- roadmap and audit coverage for phases 0 to 6,
- smart-home retail-readiness honesty checks (simulation-backed connector detection, rollout/config drift, current Wallet setup guidance),
- family-role canonicalization (`parent`/`child`) with `son` preserved only as a legacy alias for compatibility,
- Credential Defense guided breach-first flow markers (identity/link/foundation gating, service-action lock wording, tutorial/doc sync),
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
   - Confirm compact overlay mode displays one current target at a time, supports an in-place `Hide` link that collapses the card while keeping the tap target readable, and uses `Show` to restore:
     `Previous`, `Done this step`, `Finish guide`.
   - Confirm high-risk containment still exposes uninstall in automatic flow after recommended settings.
   - Use `Back to scan results` and confirm return path is clean.
4. Manually verify Credential Defense breach-first UX:
   - Open Credential Defense Center on a device using gesture navigation and three-button navigation if available.
   - Confirm the autofill/passkey card and lower controls stay above the system nav area.
   - Confirm `Scan breaches` drives setup in this order: identity, linked email, autofill/passkey foundation, then sweep.
   - Confirm the guided foundation dialog exposes `Open with overlay guide` and `Open settings now`.
   - If overlay permission is off, confirm the app asks for `display over other apps`; after granting it, confirm the dialog returns and lets the tester tap `Open with overlay guide` again.
   - On MIUI/Xiaomi, confirm autofill guidance warns that search can dead-end on `Accounts & sync` / `Android Auto` and routes the user through `Google > All services > Autofill with Google`, with the older `Additional settings > Languages and input > Autofill service` path treated as legacy fallback only.
   - Confirm returning from Settings triggers a recheck prompt.
   - Confirm service actions stay locked behind Scan breaches until the first linked sweep finishes.
   - Confirm a sweep with no matching linked records offers a direct path to save the first credential.
   - Confirm a sweep with compromised linked records offers a direct path into the highest-priority service action.
5. Manually verify Home Risk wording honesty:
   - Open Home Risk setup and Home Risk posture from the dashboard.
   - Confirm the UI describes live inventory sync for supported providers and local advisory/setup mode for unsupported providers.
   - Confirm SmartThings and Home Assistant use token-linked/live-inventory wording, while Google Home and smart-fob providers do not.
   - Confirm no screen claims live Google Home cloud telemetry or direct smart-fob control.
6. Fix any failed checks and re-run until clean.
7. Attach the latest markdown report to release evidence.

## 2026-03-08 revalidation rule

Lyra now treats retail smart-home readiness as a release gate:

- If `SmartThingsConnector.kt` falls back to the legacy simulation-backed pattern, Lyra will fail the build until Home Risk wording and release claims are re-scoped.
- If Home Risk copy stops matching the shipped mixed scope, Lyra will fail. Supported providers must be described with live inventory wording; unsupported providers must stay clearly advisory/local.
- If `google_home` appears in active connector rollout IDs or is presented as live cloud telemetry without a real connector implementation, Lyra will fail.
- If Google Wallet setup guidance points to a stale/non-car-key support page, Lyra will fail.
- If roadmap/audit docs overstate Phase 2 or Phase 5 as retail-pass, Lyra will fail.

This is intentional. The release package should not pass while connected-home scope is misstated, even when some providers are already live.

Lyra also treats family-role terminology as a compatibility gate:

- Runtime config and docs must use `parent`/`child` as the canonical family-role pair.
- Legacy `son` values may remain only as compatibility aliases for older imports or historical records.
- Active owner allowlists, rollout roles, and new UX copy should not require or advertise `son` as a primary role.

Lyra now treats D_T routing drift as a staged confidence ladder:

- `dt_scope_ladder_basic_clarification`: vague backend asks must stay in strict clarification mode.
- `dt_scope_ladder_hub_scoped_review`: the hub must accept scoped read-only review requests without forcing clarification.
- `dt_scope_ladder_hub_normalization_alignment`: the hub must accept normalization/alignment work when the scope is explicit.
- `dt_scope_ladder_security_local_core_upgrade`: the security workspace fallback core must upgrade actionable clarification payloads into assumption-ready execution.
- `dt_scope_ladder_security_satellite_upgrade`: the security satellite router must convert actionable scoped clarification payloads into assumption mode with bounded questions.
- `dt_scope_ladder_dark_coder_backend_alignment`: the embedded Dark_Coder backend codereview contract must classify cross-system alignment work as actionable assumption-first.
- `dt_scope_ladder_dark_coder_satellite_upgrade`: the embedded Dark_Coder satellite router must perform the same assumption-mode upgrade for scoped actionable requests.

## Latest passing run (this workspace)

- `logs/lyra_qa/lyra_qa_report_20260304T102544Z.md`
- `logs/lyra_qa/lyra_qa_report_20260304T102544Z.json`
