# Phase 6 - Embedded Tutorial Overlay

Date: 2026-03-05  
Last updated: 2026-03-06  
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

1. Incident assistant action order
   - Primary action is `Apply fix for me`.
   - Secondary action is `Guide me step-by-step`.
   - `Skip for now` is de-emphasized and positioned lower-left.

2. Skip behavior clarification
   - `Skip for now` now advances to the next unresolved incident in-place.
   - It no longer drops users back to the main scan-results page when additional incidents remain.

3. Progressive-disclosure sections in incident assistant
   - `Work on this now`
   - `Why this needs attention`
   - `Choose one option below`
   - `Recommended best settings` (new)
   - Each section expands/collapses on demand so users can focus only on what they need.

4. Progressive-disclosure in scan summary/details card
   - Long report text is now broken into expandable sections:
     - `What happened`
     - `What to do now`
     - `Technical details (optional)`
     - `Detailed findings (optional)`
   - This reduces cognitive load and supports ADHD-friendly flow control.

5. Validation notes for QA/tutorial checks
   - Start incident flow from scan results.
   - Confirm `Skip for now` keeps user in assistant flow and loads next incident.
   - Confirm section toggles open/close correctly in both incident assistant and report card.
   - Confirm the dedicated `Incident assistant screen` tutorial step appears in sequence.
