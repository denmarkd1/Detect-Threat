# Phase 6 - Adaptive Guide Phase 2 Continuation

Date: 2026-03-10
Status: Historical planning handoff. Phase 2 was implemented locally on 2026-03-10; keep this document as the original planning context for that slice.
Workspace: `/home/danicous/security`

## Purpose

This document is the continuation handoff for the adaptive foundation guide work in `android-watchdog`.

Use it to resume in a new chat session without rebuilding context from scratch.

## Current shipped state

Phase 1 is complete in the local workspaces.

Committed changes:

1. `security` commit `9cd5df7`
   - `feat(android-watchdog): add adaptive foundation guide overlay`
2. `security` commit `135924e`
   - `fix(ops): tighten zen fallback timeout budget`
3. `D_T_Zen_MCP_Workspace` commit `ad6590e`
   - `fix(zen-mcp): fail over before client deadlines`
4. `Dark_Coder` commit `549f814`
   - `fix(backend): shorten zen bridge fallback budgets`

## Phase 1 outcome

The app now has a deterministic adaptive overlay for autofill/passkey foundation flows.

Implemented behavior:

1. Editable local rule packs drive the overlay flow.
2. The overlay asks the user what screen they see and advances only after manual anchor confirmation.
3. Xiaomi/MIUI autofill flow no longer dead-ends on repeated instructions.
4. `Hide` / `Show` works in place and preserves readability of the active tap target.
5. `Reset route` returns the active OEM flow to the first state.
6. Lyra/tutorial/QA surfaces were synced to the current retail-safe Phase 1 behavior.

Primary files already in place:

1. `config/android_guide_rules.json`
2. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideOverlayService.kt`
3. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideRules.kt`
4. `android-watchdog/app/src/main/java/com/realyn/watchdog/CredentialDefenseActivity.kt`
5. `android-watchdog/app/src/main/java/com/realyn/watchdog/IncidentGuideOverlayLayout.kt`
6. `android-watchdog/app/src/test/java/com/realyn/watchdog/AdaptiveGuideEngineTest.kt`

## Phase 2 target

Phase 2 should add user-triggered snapshot analysis with on-device OCR, while preserving the deterministic guide engine as the controller.

Target behavior:

1. The user taps an explicit `Analyze current screen` action from the adaptive overlay or foundation dialog.
2. The app captures one current screen snapshot only for that user-triggered action.
3. OCR runs on-device and extracts visible text anchors.
4. The deterministic guide engine maps OCR anchors to the current rule pack.
5. The overlay updates in place with:
   - detected screen summary
   - best matching anchor/state
   - alternate anchor options if confidence is ambiguous
6. The user remains in control of advancing the route.

Recommended implementation goal:

- Phase 2 should reduce manual anchor selection effort.
- Phase 2 should not replace the deterministic rule engine with AI reasoning.

## Current restraint recommendations

These are the current retail-safe constraints and should remain in force for Phase 2 unless explicitly re-approved.

1. Keep the deterministic guide engine as the controller.
2. Keep rule packs local and editable.
3. Do not make AI the primary routing authority.
4. Do not stream screenshots to remote services.
5. Do not upload raw Settings screenshots by default.
6. Do not add autonomous taps, gestures, or background UI control.
7. Do not enable continuous or silent capture.
8. Do not add `AccessibilityService` in Phase 2.
9. Do not add `MediaProjection` background capture loops.
10. Require an explicit user action for every snapshot capture.
11. Run OCR on-device.
12. Keep any route change explainable and user-confirmable when confidence is not exact.
13. Preserve auditability for every adaptive guide decision.
14. Keep this phase compatible with current Google Play retail expectations for narrow, consented, non-autonomous guidance.

## Recommended Phase 2 design

### Scope

Add one new path:

1. User-triggered screen snapshot
2. On-device OCR extraction
3. Deterministic anchor matching
4. In-place overlay refresh

### Preferred technical direction

1. Use ML Kit Text Recognition v2 on-device.
2. Keep OCR results in a `ScreenContext` or equivalent model, separate from UI code.
3. Feed only normalized anchors into the guide engine.
4. Preserve the existing rule-pack architecture.
5. Add confidence scoring for:
   - exact match
   - partial match
   - ambiguous match
   - no match

### Recommended UX

1. Add `Analyze current screen` to the adaptive overlay.
2. After capture, show one of:
   - `Detected: Autofill service`
   - `Detected: Google Password Manager`
   - `Possible matches: Preferences, Google Password Manager`
   - `No reliable match found`
3. If confidence is exact, preselect the detected anchor but still allow manual correction.
4. If confidence is ambiguous, present 2 to 3 candidate anchors instead of auto-advancing.
5. If no match is found, keep the current manual anchor buttons available.

## Phase 2 files likely to change

Likely new files:

1. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideScreenAnalyzer.kt`
2. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideOcrMatcher.kt`
3. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideScreenContext.kt`

Likely existing files to update:

1. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideOverlayService.kt`
2. `android-watchdog/app/src/main/java/com/realyn/watchdog/AdaptiveGuideRules.kt`
3. `android-watchdog/app/src/main/java/com/realyn/watchdog/CredentialDefenseActivity.kt`
4. `android-watchdog/app/build.gradle.kts`
5. `android-watchdog/app/src/main/res/values/strings.xml`
6. `android-watchdog/app/src/main/res/values-es/strings.xml`
7. `android-watchdog/app/src/test/java/com/realyn/watchdog/AdaptiveGuideEngineTest.kt`

Expected doc/test sync if Phase 2 is implemented:

1. `docs/integration/phase6_tutorial_overlay_2026-03-04.md`
2. `docs/integration/phase6_lyra_qa_trainer_2026-03-04.md`
3. `docs/integration/beta tester`
4. `scripts/ops/lyra_beta_trainer.py`

## Acceptance criteria for Phase 2

1. Snapshot capture is user-triggered only.
2. OCR runs locally on-device.
3. No screenshots are sent to connected AI or external APIs.
4. The deterministic guide engine still decides valid next states.
5. Overlay continues to work if OCR returns no usable anchors.
6. User can override OCR-suggested matches manually.
7. OCR flow works without requiring `AccessibilityService`.
8. Existing Phase 1 manual-only path still works if OCR is unavailable.
9. Tests cover exact-match, ambiguous-match, and no-match outcomes.
10. Tutorial/QA/Lyra surfaces are updated if user-visible flow changes.

## Validation checklist for the next session

1. Add the OCR dependency in a minimal, local-only way.
2. Add unit tests for OCR-to-anchor normalization.
3. Verify the app still passes:
   - `./gradlew testDebugUnitTest`
   - `python3 -m py_compile scripts/ops/lyra_beta_trainer.py`
   - `git diff --check`
   - `bash scripts/ops/precommit_guard.sh`
4. Manually test one Xiaomi/MIUI path and one non-MIUI path.
5. Confirm the overlay remains usable when OCR fails or misreads the screen.

## Known constraints from this session

1. `security` still has unrelated local changes in:
   - `D_T_System/satellite_config.json`
   - `systems/D_T_System/tasks/active_tasks.json`
2. `Dark_Coder` still has a large unrelated dirty worktree outside the timeout-budget commit.
3. A local D_T bootstrap fell back to local-first because the Puter bridge endpoint at `127.0.0.1:8899` was unavailable.

## Suggested new-chat prompt

Use this in the next session:

```text
Continue Phase 2 of the adaptive foundation guide in /home/danicous/security.

Current state:
- Phase 1 deterministic adaptive overlay is already committed in security commit 9cd5df7.
- Retail-safe constraints are locked:
  - deterministic guide engine remains the controller
  - local editable rule packs
  - user-triggered snapshot only
  - on-device OCR only
  - no screenshot upload
  - no autonomous taps
  - no AccessibilityService in Phase 2
  - no continuous capture
- Use docs/integration/phase6_adaptive_guide_phase2_continuation_2026-03-10.md as the handoff source.

Phase 2 goal:
- add user-triggered snapshot analysis with on-device OCR
- map OCR text into the existing deterministic adaptive guide engine
- update the overlay in place
- preserve the manual fallback path

If the implementation changes user-visible guidance, also update:
- docs/integration/phase6_tutorial_overlay_2026-03-04.md
- docs/integration/phase6_lyra_qa_trainer_2026-03-04.md
- docs/integration/beta tester
- scripts/ops/lyra_beta_trainer.py
```
