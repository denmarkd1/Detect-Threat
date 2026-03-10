# Phase 6 - Staged Rollout and Rollback Playbook

Date: 2026-03-04
Last updated: 2026-03-07
Package: `com.realyn.watchdog`

## Release tracks

1. Internal testing.
2. Closed testing.
3. Production staged rollout.

## Production rollout ladder

Use this default ladder after internal/closed signoff:

1. 5%
2. 20%
3. 50%
4. 100%

Hold each step until vitals/support checks are reviewed.

## Go/No-Go gate per stage

Advance only if all are true:

- `bash scripts/ops/phase6_masvs_sweep.sh` passes.
- `python3 scripts/ops/lyra_beta_trainer.py --serial <DEVICE_SERIAL>` passes, including the retail-readiness honesty checks.
- `cd android-watchdog && ./gradlew lintDebug testDebugUnitTest` passes.
- No unresolved high-severity regressions in guardian timeline/support queue.
- Crash and ANR vitals stay within acceptable threshold for the stage.
- Policy/disclosure review remains valid for this exact build.

## Rollback triggers

Trigger rollback immediately when any condition is met:

- New crash loop or severe startup regression detected post-rollout.
- High-severity security policy regression (for example, disclosure mismatch, broken guardian gate).
- Data corruption risk in credential/vault state.
- Elevated incident volume indicating broad functional break.

## Rollback actions

1. Pause production rollout in Play Console.
2. Keep last known-good release active for current users.
3. Create hotfix branch from last production tag.
4. Apply minimal corrective patch.
5. Bump `versionCode` and `versionName` in `android-watchdog/app/build.gradle.kts`.
6. Re-run Phase 6 gate:
   - `bash scripts/ops/install_precommit_hook.sh`
   - `bash scripts/ops/phase6_masvs_sweep.sh`
   - `python3 scripts/ops/lyra_beta_trainer.py --serial <DEVICE_SERIAL>`
   - `bash scripts/ops/precommit_guard.sh --include-unstaged`
   - `cd android-watchdog && ./gradlew lintDebug testDebugUnitTest`
7. Build signed artifacts:
   - `bash scripts/ops/build_play_release.sh`
8. Upload hotfix to Internal, then Closed, then staged Production.

Commit-validation note:
- The blocking commit gate is the local git `pre-commit` hook wired to `scripts/ops/precommit_guard.sh`.
- `zen/precommit` is optional advisory review only until the upstream timeout behavior is corrected.

## Hotfix communication template (internal)

- Incident ID:
- Affected versions:
- User impact summary:
- Rollback action timestamp (UTC):
- Hotfix version:
- Verification evidence links:
- Re-rollout decision and approver:

## Post-incident hardening

After a rollback event:

1. Add regression test coverage for the failure mode.
2. Add or tighten a Phase 6 sweep check if the failure could be detected earlier.
3. Record remediation in release notes and internal runbook.
