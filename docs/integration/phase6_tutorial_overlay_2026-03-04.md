# Phase 6 - Embedded Tutorial Overlay

Date: 2026-03-04  
APK surface: `android-watchdog` home dashboard (`MainActivity`)

## Goal

Provide an in-app onboarding layer that highlights critical widgets/buttons and supports two learning styles:

1. Guided walkthrough (Next/Back flow).
2. Learn-by-doing (user must tap highlighted control before advancing).

## Implementation summary

- Overlay view: `HomeTutorialOverlayView` (scrim + pulsing highlight ring).
- Controller wiring: `MainActivity.startHomeTutorial(...)` and tutorial step engine.
- Entry points:
  - first-run popup after intro sequence,
  - Lion navigation action: `Tutorial overlay`.

## ADHD-friendly behavior

- Short, plain-language hint line per step.
- Learn-by-doing mode disables Next until the highlighted control is tapped.
- High-contrast spotlight and pulse animation for visual focus.
- Close anytime from tutorial card (`Close`).

## Covered controls/features

Tutorial currently covers these home controls:

1. Sweep control
2. Threats widget
3. Credentials widget
4. Services widget
5. Plan and billing button
6. Guardian settings button
7. Home Risk widget (page 2)
8. VPN guard widget (page 2)
9. Digital key guardrails widget (page 2)
10. Timeline/report widget (page 2)
11. Lion quick navigation button
12. Bottom nav Scan
13. Bottom nav Guard
14. Bottom nav Vault
15. Bottom nav Support

## Completion behavior

- Completion persists locally (`home_tutorial_completed_v1`) and suppresses auto-popup replay.
- User can still reopen the tutorial from Lion navigation at any time.
