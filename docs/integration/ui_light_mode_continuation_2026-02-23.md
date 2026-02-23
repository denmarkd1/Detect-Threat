# UI Continuation (Paused) - February 23, 2026

## Current pause state
- Work is paused by request.
- Last completed build/install succeeded on device.
- The app is functional, but light mode visual quality is not acceptable yet.

## User feedback to carry forward (source of truth)
- Dark mode looks professional.
- Light mode currently looks washed out and not production quality.
- Light mode is currently a blocker for acceptance.
- Preferred direction:
  - Hero lion in light mode can be fully grayscale (clean, intentional look).
  - Center nav lion button should remain full color.
  - Center lion should sit on a clean white circular background for clarity.
  - Overall light mode should match dark mode polish/professional feel.

## What was recently changed
- Added centralized settings activity and routed top-right `Settings` button there.
- Added theme variant system (free/pro variants + tone mode support).
- Added light-mode compensation changes (palette rebalance + lion rendering tweaks + system bar icon contrast).
- Result still not at required quality for light mode.

## Recommended next-session execution plan
1. Lock a strict light-mode visual spec first (before tuning):
   - Hero: grayscale only, controlled alpha/contrast.
   - Cards/nav: stronger tonal hierarchy (not near-white on white).
   - Center lion button: full-color icon + white circular base with deliberate contrast.
2. Implement a dedicated light-mode hero pipeline in `LionHeroView`:
   - Separate matrix/preset for light mode instead of blended compromise.
3. Tighten light palette in `LionThemeCatalog`:
   - Increase separation between `background`, `panel`, `panelAlt`, and text tones.
4. Verify on-device with side-by-side captures:
   - Home (light vs dark), settings screen, credential screen.
5. Only then continue additional style variants.

## Primary files to revisit next
- `android-watchdog/app/src/main/java/com/realyn/watchdog/theme/LionThemeCatalog.kt`
- `android-watchdog/app/src/main/java/com/realyn/watchdog/LionHeroView.kt`
- `android-watchdog/app/src/main/java/com/realyn/watchdog/MainActivity.kt`
- `android-watchdog/app/src/main/java/com/realyn/watchdog/GuardianSettingsActivity.kt`
- `android-watchdog/app/src/main/java/com/realyn/watchdog/CredentialDefenseActivity.kt`

