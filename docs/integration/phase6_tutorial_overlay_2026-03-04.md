# Phase 6 - Embedded Tutorial Overlay

Date: 2026-03-05  
APK surface: `android-watchdog` home dashboard (`MainActivity`)

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

## Completion behavior

- Completion persists locally (`home_tutorial_completed_v1`) and suppresses auto-popup replay.
- User can still reopen the tutorial from Lion navigation at any time.
