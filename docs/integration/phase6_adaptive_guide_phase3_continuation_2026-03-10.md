# Phase 6 - Adaptive Guide Phase 3 Continuation

Date: 2026-03-10
Status: Phase 2 shipped locally in `security` commit `cc5b3b9`; Phase 3 is the next implementation slice.
Workspace: `/home/danicous/security`

## Purpose

This document is the continuation handoff for the next adaptive foundation guide slice in `android-watchdog`.

Use it to resume in a new chat session without rebuilding the current implementation context.

## Current shipped state

Relevant commits:

1. `security` commit `9cd5df7`
   - `feat(android-watchdog): add adaptive foundation guide overlay`
2. `security` commit `cc5b3b9`
   - `feat(android-watchdog): add retail-safe adaptive guide OCR analysis`

## Phase 2 outcome

The app now ships a retail-safe adaptive overlay that can analyze one user-requested Settings screen locally, then feed the result back into the deterministic guide engine.

Implemented behavior:

1. The adaptive overlay exposes `Analyze current screen`.
2. The app requests explicit Android screen-capture consent for each analysis attempt.
3. Capture is one-shot only and runs through `MediaProjection` in a foreground service.
4. OCR runs on-device via bundled ML Kit text recognition.
5. OCR results are normalized and matched against the local adaptive rule pack.
6. The overlay presents exact, ambiguous, or no-match outcomes while preserving manual anchor buttons.
7. The user still controls route advancement; the guide does not autonomously tap or navigate.
8. Audit log entries are written for requested, completed, failed, and applied analysis events.
9. Tutorial, QA, beta-tester, and Lyra trainer surfaces were synced to the shipped Phase 2 behavior.

Primary files now in place:

1. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideCaptureActivity.kt`
2. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideCaptureService.kt`
3. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideOcrMatcher.kt`
4. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideScreenAnalyzer.kt`
5. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideScreenContext.kt`
6. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideOverlayService.kt`
7. `android-watchdog/app/src/test/java/com/realyn/watchdog/AdaptiveGuideOcrMatcherTest.kt`

## Known limitation after Phase 2

Phase 2 improves screen recognition inside the overlay, but the host Credential Defense flow still loses too much context when the user returns from Settings.

Current gap:

1. `AdaptiveGuideAnalysisStore` is in-memory only and does not survive normal overlay/activity lifecycle loss.
2. Returning from Settings shows a generic recheck dialog, not the last detected or last confirmed guide step.
3. The guided foundation dialog cannot resume the last adaptive state or explain what the OCR/manual path last found.
4. The app does not persist a minimal local session record for the active adaptive route.

## Phase 3 target

Phase 3 should add retail-safe session continuity and return-path guidance around the existing adaptive overlay.

Target behavior:

1. When the user returns from Settings after using the adaptive overlay, the app shows the last known guide context in the return prompt.
2. The user can choose to resume the last guided step, restart the route, or reopen Settings.
3. The app persists a minimal local session record for the active adaptive flow across overlay/service/activity restart.
4. Persisted session data stays limited to structured guide metadata:
   - flow ID
   - current state ID
   - last confirmed state ID
   - last applied anchor ID
   - summary label
   - confidence
   - source (`manual` or `ocr`)
   - update timestamp
5. The app does not persist screenshot bytes or full OCR text.
6. Manual-only fallback remains available when there is no session data or the session is stale/invalid.
7. `Reset route` and successful foundation completion clear the stored session.
8. Audit log entries cover session resume, restart, and clear decisions.

Recommended implementation goal:

- Make re-entry less lossy without adding any new capture permission, autonomous action, or background behavior.

## Current restraint recommendations

These retail-safe constraints remain active for Phase 3 unless explicitly re-approved.

1. Keep the deterministic guide engine as the controller.
2. Keep rule packs local and editable.
3. Do not make AI the primary routing authority.
4. Do not stream screenshots to remote services.
5. Do not upload raw Settings screenshots by default.
6. Do not add autonomous taps, gestures, or background UI control.
7. Do not enable continuous or silent capture.
8. Do not add `AccessibilityService`.
9. Do not add `MediaProjection` background capture loops.
10. Require an explicit user action for every snapshot capture.
11. Run OCR on-device.
12. Keep any route change explainable and user-confirmable when confidence is not exact.
13. Preserve auditability for every adaptive guide decision.
14. Keep this phase compatible with current Google Play retail expectations for narrow, consented, non-autonomous guidance.
15. Do not store screenshots on disk.
16. Do not persist full OCR text; persist only minimal structured session metadata.
17. Do not auto-advance on return to the app; always ask the user to confirm resume or restart.
18. Keep session persistence scoped to the active foundation guide flow.

## Recommended Phase 3 design

### Scope

Add one new capability layer:

1. Local adaptive-guide session persistence
2. Return-prompt enrichment
3. Resume/restart controls in the foundation guidance flow

### Preferred technical direction

1. Add an `AdaptiveGuideSessionStore` backed by the app's existing local storage patterns (`SharedPreferences` is acceptable for minimal-risk integration).
2. Persist only structured guide state metadata; do not reuse the session store for raw OCR text.
3. Update the overlay to write session updates on:
   - start
   - manual anchor confirmation
   - OCR match application
   - previous
   - reset
   - completion
4. Update `CredentialDefenseActivity.showFoundationGuideDialog(...)` and `showFoundationReturnPrompt(...)` to read the stored session and surface the last known state to the user.
5. Clear stale or invalid session data when:
   - the flow ID changes
   - the stored state no longer exists in the current rule pack
   - the user restarts the route
   - the foundation target is confirmed complete

### Recommended UX

1. When a valid session exists, the foundation dialog should show lines such as:
   - `Last guided step: Autofill service`
   - `Last detected: Google Password Manager (exact)`
   - `Updated: just now`
2. The return prompt should offer:
   - `Resume last guided step`
   - `Restart guided route`
   - `Open settings again`
3. If the last result was ambiguous, describe it as a suggestion and return the user to manual confirmation rather than auto-applying anything.
4. If the session is stale or invalid, fall back cleanly to the current Phase 2 behavior.

## Phase 3 files likely to change

Likely new files:

1. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideSessionStore.kt`
2. `android-watchdog/app/src/test/java/com/realyn/watchdog/AdaptiveGuideSessionStoreTest.kt`

Likely existing files to update:

1. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideOverlayService.kt`
2. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideScreenContext.kt`
3. `android-watchdog/app/src/main/java/com/realyn/watchdog/CredentialDefenseActivity.kt`
4. `android-watchdog/app/src/main/java/com/realyn/watchdog/WatchdogConfig.kt`
5. `android-watchdog/app/src/main/res/values/strings.xml`
6. `android-watchdog/app/src/main/res/values-es/strings.xml`

Expected doc/test sync if Phase 3 is implemented:

1. `docs/integration/phase6_tutorial_overlay_2026-03-04.md`
2. `docs/integration/phase6_lyra_qa_trainer_2026-03-04.md`
3. `docs/integration/beta tester`
4. `scripts/ops/lyra_beta_trainer.py`

## Acceptance criteria for Phase 3

1. Returning from Settings after an adaptive-guide session shows the last known guide context in-app.
2. The user can resume from the last stored state without being forced back to the first step.
3. The user can explicitly restart the route, which clears the stored session and returns to the flow start.
4. Only structured guide metadata persists locally; no screenshot bytes or full OCR text are written to disk.
5. If there is no valid session, the current Phase 2 behavior still works unchanged.
6. Session state clears on route reset and on successful foundation completion.
7. Audit log entries exist for resume, restart, and session clear decisions.
8. Tests cover persist, restore, clear, and stale-session mismatch cases.
9. Tutorial, QA, beta-tester, and Lyra trainer surfaces are updated if the user-visible return flow changes.

## Validation checklist for the next session

1. Add unit tests for session persistence and stale-session invalidation.
2. Verify the app still passes:
   - `cd android-watchdog && ./gradlew testDebugUnitTest`
   - `cd android-watchdog && ./gradlew lintDebug assembleDebug`
   - `python3 -m py_compile scripts/ops/lyra_beta_trainer.py`
   - `git diff --check`
   - `bash scripts/ops/precommit_guard.sh`
3. Manually test:
   - start adaptive overlay, confirm a manual anchor, return to the app, and verify the last step summary appears
   - use `Analyze current screen`, apply an exact match, return to the app, and verify resume uses the stored state
   - use `Reset route`, return to the app, and verify the session is cleared
   - complete the target foundation setup and verify the session is cleared or treated as complete

## Known constraints from this session

1. `security` still has unrelated local changes in:
   - `D_T_System/satellite_config.json`
   - `systems/D_T_System/tasks/active_tasks.json`
2. Advisory `zen/precommit` could not complete in this session because its external transport hit funding/timeout limits.
3. The blocking local commit gate remains `bash scripts/ops/precommit_guard.sh`, which passed for the Phase 2 commit.
4. Live screen-capture consent and OCR UX still require manual device verification.

## Suggested new-chat prompt

Use this in the next session:

```text
Continue Phase 3 of the adaptive foundation guide in /home/danicous/security.

Current shipped state:
- Phase 1 manual adaptive overlay is committed in security commit 9cd5df7.
- Phase 2 retail-safe OCR analysis is committed in security commit cc5b3b9.
- The current OCR flow is user-triggered, one-shot, on-device only, and keeps the deterministic rule-pack engine in control.
- Use docs/integration/phase6_adaptive_guide_phase3_continuation_2026-03-10.md as the handoff source.

Phase 3 goal:
- add local adaptive-guide session persistence
- show last known guide context when the user returns from Settings
- allow resume or restart of the current adaptive route
- keep storage limited to structured metadata only
- do not persist screenshots or full OCR text

If the implementation changes user-visible guidance, also update:
- docs/integration/phase6_tutorial_overlay_2026-03-04.md
- docs/integration/phase6_lyra_qa_trainer_2026-03-04.md
- docs/integration/beta tester
- scripts/ops/lyra_beta_trainer.py
```
