# Phase 6 - MASVS Verification Sweep

Date: 2026-03-04
Scope: Android app (`android-watchdog`) + local watchdog/security tooling

## Goal

Create a repeatable, auditable launch gate aligned to OWASP MASVS themes for this product profile.

## Command

```bash
bash scripts/ops/phase6_masvs_sweep.sh
```

Optional fast path:

```bash
bash scripts/ops/phase6_masvs_sweep.sh --skip-gradle
```

## Output artifact

- Report path: `logs/phase6/masvs_sweep_<UTC_TIMESTAMP>.md`
- Report format: control-by-control PASS/FAIL table with evidence snippets.
- Exit code:
  - `0` = all controls passed.
  - non-zero = one or more controls failed and must be remediated.

## Control set used in this sweep

- `MASVS-STORAGE-1`: local encrypted credential/media storage paths present.
- `MASVS-NETWORK-1`: credential breach checks use k-anonymity range endpoint (`/range/<prefix>`).
- `MASVS-PRIVACY-1`: no obvious raw secret/password logging patterns in app code.
- `MASVS-AUTH-1`: guardian override enforcement in sensitive flows.
- `MASVS-RESILIENCE-1`: root posture and Play Integrity gating present.
- `MASVS-PLATFORM-1`: modern SDK targeting configured (API 35+ floor for new Play submissions).
- `MASVS-CODE-1`: Android lint + unit tests pass.
- `MASVS-OPERATIONS-1`: watchdog logging includes UTC timestamps.
- `MASVS-PRIVACY-2`: public privacy + terms artifacts present.
- `PLAY-RELEASE-1`: release build + rollout scripts documented and available.

## Release gate policy

A Play production candidate is blocked if any required control fails.

## Reviewer workflow

1. Run the sweep script.
2. Attach the generated markdown artifact to release evidence.
3. If failures exist, fix and re-run until green.
4. Carry the final artifact into rollout approval.

## Latest passing run (this workspace)

- `logs/phase6/masvs_sweep_20260304T102443Z.md`
