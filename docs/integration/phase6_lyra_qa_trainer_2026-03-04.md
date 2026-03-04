# Phase 6 - Lyra QA Trainer (Device-Backed)

Date: 2026-03-04

## Purpose

`lyra_beta_trainer.py` runs a deterministic, device-backed QA sweep before Play submission.

It validates:

- local Python and watchdog CLI readiness,
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
3. Fix any failed checks and re-run until clean.
4. Attach the latest markdown report to release evidence.

## Latest passing run (this workspace)

- `logs/lyra_qa/lyra_qa_report_20260304T102544Z.md`
- `logs/lyra_qa/lyra_qa_report_20260304T102544Z.json`
