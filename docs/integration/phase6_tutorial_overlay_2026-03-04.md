# Phase 6 - Embedded Tutorial Overlay

Date: 2026-03-05
Last updated: 2026-03-10
APK surface: `android-watchdog` home dashboard (`MainActivity`) + scan-results remediation flow (`ScanResultsActivity`)

## Goal

Provide an in-app onboarding layer that highlights critical widgets/buttons and supports two learning styles:

1. Guided walkthrough (Next/Back flow).
2. Learn-by-doing (tap-required on interactive steps, with informational steps for sub-window context).

## Implementation summary

- Overlay view: `HomeTutorialOverlayView` (scrim + pulsing highlight ring).
- Controller wiring: `MainActivity.startHomeTutorial(...)` and tutorial step engine.
- Entry points:
  - first-run popup after intro sequence,
  - Lion navigation action: `Tutorial overlay`.

## ADHD-friendly behavior

- Short, plain-language hint line per step.
- Learn-by-doing mode disables Next on tap-required steps, while informational sub-window steps can advance without extra taps.
- High-contrast spotlight and pulse animation for visual focus.
- Close anytime from tutorial card (`Close`).

## Covered controls/features

Tutorial now covers both:

1. Home controls (widgets, nav buttons, plan, settings, lion button).
2. Destination sub-windows those controls open, including key options/actions in each window.

Coverage includes these destination windows:

1. Sweep workflow summary
2. Threat triage dialog flow
3. Credential Defense Center
4. Security Details summary dialog
5. Plan and billing dialog
6. Guardian Settings routes (tutorial, billing, AI, language, locator, Home Risk setup, VPN setup, credential center)
7. Home Risk status/setup windows
8. VPN status/setup windows
9. Digital Key guardrails/setup guidance windows
10. Timeline/report dialog actions
11. Lion quick-navigation menu actions
12. Bottom-nav page 2 route map (Home Risk, VPN, Digital Key, Timeline)
13. Incident Assistant remediation screen (dedicated overlay step)

## Completion behavior

- Completion persists locally (`home_tutorial_completed_v1`) and suppresses auto-popup replay.
- User can still reopen the tutorial from Lion navigation at any time.

## 2026-03-06 update: scan-results and incident-assistant tutorial coverage

Tutorial guidance is now aligned to the latest remediation UX:

0. Samsung-first limitation note
   - On Samsung One UI devices, the tutorial now starts with a dedicated note before the normal widget walkthrough.
   - It explains that protected `App info` / `Permissions` screens can hide the floating guide even when
     `Appear on top` is enabled.
   - It also tells users to keep following the exact tap list and use the pinned incident-guide notification if the
     overlay disappears.

1. Dedicated page split between scan results and incident assistant
   - `Start incident` from scan results now opens Incident assistant on its own screen.
   - Scan summary/details and Incident assistant no longer share one visual scroll surface.

2. Incident assistant action order and decision branch
   - Primary action is `Apply fix for me`.
   - Secondary action is `Guide me step-by-step`.
   - `Skip for now` remains the tertiary action.
   - All three actions now render with the same full-width button treatment.
   - Both `Apply fix for me` and `Guide me step-by-step` now ask first:
     `Apply recommended best settings?`
   - This keeps guidance consistent while allowing users to skip directly when needed.

3. Recommended best settings flow
   - `Apply fix for me` path:
     - If user accepts recommended settings, app attempts non-destructive recommended actions first.
     - If Android permission is required (for example Wi-Fi posture runtime permissions), app asks for permission before continuing.
     - After recommended actions, containment actions are still offered, including uninstall for higher-risk findings.
   - `Guide me step-by-step` path:
     - If user accepts recommended settings, app shows manual recommended-settings guidance first.
     - First-time overlay permission grant now returns to the manual dialog so the user can explicitly choose
       `Open with overlay guide` again (prevents dead-end flow after permission toggle).
     - Then app continues to the full step-by-step incident guide.

4. Skip behavior clarification
   - `Skip for now` now advances to the next unresolved incident in-place.
   - It no longer drops users back to the main scan-results page when additional incidents remain.

5. Progressive-disclosure sections in incident assistant
   - `Work on this now`
   - `Why this needs attention`
   - `Choose one option below`
   - `Recommended best settings` (new)
   - Each section expands/collapses on demand so users can focus only on what they need.

6. Progressive-disclosure in scan summary/details card
   - Long report text is now broken into expandable sections:
     - `What happened`
     - `What to do now`
     - `Technical details (optional)`
     - `Detailed findings (optional)`
   - This reduces cognitive load and supports ADHD-friendly flow control.

7. Validation notes for QA/tutorial checks
   - Start incident flow from scan results.
   - Confirm `Start incident` opens a separate Incident assistant page (not layered over scan results).
   - Confirm `Work on this now` shows app identity consistently for high/medium/low incidents:
     app icon + bold common app name when package metadata is available.
   - Confirm both actions (`Apply fix for me`, `Guide me step-by-step`) first show the `Apply recommended best settings?` decision.
   - Confirm decision copy states manual-only behavior when Android blocks direct auto-changes.
   - Confirm Apply path requests Android permission when required before recommended auto actions.
   - Confirm manual recommended-settings dialog shows `Path pack:` with the detected OEM profile
     (MIUI, Samsung One UI, Google Pixel, or Generic Android).
   - Confirm tap-target list wording matches OEM pack for the active device.
   - Confirm startup/core tap targets use explicit labels:
     `App permissions` vs `Permissions` by OEM, and explicit risky permissions
     (`Camera`, `Microphone`, `Location`, `Contacts`, `Phone`, `SMS`, `Files/Media`).
   - Confirm `Open with overlay guide` appears in manual flow.
   - If overlay permission is not granted, confirm app requests `display over other apps`; after user grants it, confirm
     the app re-opens manual dialog so user can tap `Open with overlay guide` again.
   - On Samsung/MIUI, confirm manual dialog warns that some native security/settings screens can temporarily hide overlays.
     Validate user can still continue via exact tap targets even when overlay is hidden.
   - On Samsung One UI, confirm `Open with overlay guide` now shows an extra warning before launch and that the
     current tap target is pinned in an ongoing notification while the guide is active (when notifications are allowed).
   - On Samsung One UI, confirm the tutorial begins with a dedicated note about the overlay limitation and
     notification fallback before the standard walkthrough steps.
   - Confirm compact overlay guide shows one current target at a time, includes an in-place `Hide` link that collapses the card while keeping the current tap target readable, and uses `Show` to restore:
     `Previous`, `Done this step`, and `Finish guide`.
   - Confirm compact overlay advances step-by-step and closes cleanly at completion.
   - Confirm uninstall remains available as a containment action in the automatic flow for high-risk findings.
   - Confirm Guide path can show manual recommended-settings guidance before full step-by-step flow.
   - Confirm `Skip for now` keeps user in assistant flow and loads next incident.
   - Confirm `Back to scan results` returns to scan results page cleanly.
   - Confirm section toggles open/close correctly in both incident assistant and report card.
   - Confirm the dedicated `Incident assistant screen` tutorial step appears in sequence.

## 2026-03-08 update: Home Risk wording revalidation

Tutorial guidance now reflects the current Home Risk implementation scope:

1. Mixed live/local smart-device umbrella
   - Home Risk should be described as a posture snapshot layer that can sync live inventory for supported providers.
   - SmartThings and Home Assistant can use locally stored provider tokens for live inventory sync in this build.
   - Google Home and smart-fob providers remain non-live advisory/setup surfaces in this build.

2. Setup wording expectations
   - Setup flow should describe provider selection, token-based connection where supported, device import, and protection selection.
   - Provider CTAs should be framed as connecting the provider or opening the selected provider app/setup path on-device.
   - Smart-fob and digital-key providers may appear under the same umbrella as local advisory/setup surfaces, not as direct key control.

3. QA checks for this wording
   - Confirm Home Risk tutorial copy says `live inventory sync for supported providers`, `snapshot`, or equivalent mixed-scope wording.
   - Confirm Home Risk dialogs refer to choosing a provider, connecting a provider token where supported, importing devices, selecting protection, or running a scan.
   - Confirm no tutorial surface claims live Google Home cloud telemetry or direct smart-fob control.

## 2026-03-10 update: Credential Defense breach-first flow

Credential Defense Center tutorial copy now matches the current retail flow:

1. Guided breach-first sequence
   - `Scan breaches` is the primary driver for the screen.
   - The guided path should move in this order:
     identity, device-email link, autofill/passkey foundation, breach sweep, then service actions.

2. Autofill/passkey guidance behavior
   - `Open autofill settings` and `Open passkey settings` should no longer feel like blind jumps.
   - The app now presents OEM-aware path guidance first, then opens root device Settings for the user.
   - The manual dialog now offers `Open with overlay guide` so the user can keep one current tap target visible while moving through Settings, then collapse the card with `Hide` until `Show` is needed again.
   - When the user returns, the app asks for a recheck instead of silently leaving them at a dead end.
   - On current Xiaomi/MIUI builds, the guidance must explicitly warn that search can dead-end on `Accounts & sync` or `Android Auto`, route first through `Passwords & accounts > Autofill service > Google`, and if Xiaomi redirects into Google services fall back to `Google Password Manager` or `Autofill with Google`.
   - The final autofill step must send the user back to `Recheck now`, not `Scan breaches`.

3. Service-action gating behavior
   - Before the first linked sweep finishes, service-action entry should clearly say the user must start with Scan breaches.
   - After a sweep with zero matched linked records, service actions should unlock so the user can save the first credential.
   - After a sweep with compromised linked records, the next prompt should route into the highest-priority service action instead of leaving the user with a blank choice.

4. QA checks for this flow
   - Confirm Credential Defense Center content is not obscured by system nav buttons on gesture and three-button navigation.
   - Confirm tapping `Scan breaches` with no primary email opens identity setup instead of only showing a toast.
   - Confirm tapping `Scan breaches` with an unlinked email opens the link-primary-email flow.
   - Confirm tapping `Scan breaches` before autofill/passkey foundation is ready opens the guided foundation dialog.
   - Confirm the guided foundation dialog exposes `Open with overlay guide` and `Open settings now`.
   - On MIUI/Xiaomi devices, confirm autofill/passkey actions warn about the `Accounts & sync` / `Android Auto` dead-end search results and route toward `Google > All services`.
   - On MIUI/Xiaomi devices, confirm autofill guidance uses `Passwords & accounts > Autofill service > Google` as the primary path, with `Google Password Manager` or `Autofill with Google` only as the fallback when Xiaomi redirects into Google services.
   - Confirm the final autofill step tells the user to return for `Recheck now` instead of `Scan breaches`.
   - Confirm returning from Settings triggers a recheck prompt.
   - Confirm service-action CTA text changes to a breach-first message before the first linked sweep completes.
   - Confirm a no-record sweep offers a direct path into saving the first linked credential.
   - Confirm a compromised sweep offers a direct path into the highest-priority service action.

## 2026-03-07 update: Canonical family-role language

Tutorial and onboarding copy should use `parent/child` as the canonical family-role pair.

- Keep legacy alias `son` only for backward-compatible handling of older imports, historical records, or existing local state.
- Do not require or advertise `son` as a primary owner role in new tutorial copy, QA instructions, or onboarding flows.
