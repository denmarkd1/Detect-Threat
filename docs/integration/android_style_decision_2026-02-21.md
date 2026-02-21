# Android Style Direction (2026-02-21)

## Source review
- Trading2 GUI token source: `/home/danicous/trading2/Danicus_Gold_Logs/modules/utilities/display_utilities/display_config.py`
- Live paper dashboard source: `/home/danicous/trading2/scripts/run_phase3_paper_dashboard.py`

## Decision
Use the shared trading2/live-paper visual language for DT Guardian:
- dark console background (`#08080D` to `#171727` range)
- glass-like panel surfaces (`#141420`, `#1B1B2A`)
- lionhead gold accent (`#D4AF37`) for primary actions and lifecycle controls
- low-contrast border strokes (`#4E4E78`)
- high legibility body text (`#F3EBCB` primary, `#C8B782` secondary)

## Applied in Android app
- Theme and color tokens updated in `android-watchdog/app/src/main/res/values/themes.xml` and `android-watchdog/app/src/main/res/values/colors.xml`.
- Gradient workspace background added in `android-watchdog/app/src/main/res/drawable/bg_console_gradient.xml`.
- Main and credential screens restyled:
  - `android-watchdog/app/src/main/res/layout/activity_main.xml`
  - `android-watchdog/app/src/main/res/layout/activity_credential_defense.xml`

## Rationale
Both reference UIs are consistent around a defensive-console aesthetic; using this palette and structure in the APK keeps brand continuity with the lionhead identity while improving contrast and focus for security workflows.
