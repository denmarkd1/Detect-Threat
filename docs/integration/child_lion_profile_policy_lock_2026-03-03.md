# Child Lion Profile Policy Lock (2026-03-03)

## Purpose
Lock the free/pro and guardian override behavior for child lion profile assets so future UI/profile changes do not drift from approved plan rules.

## Source Assets (Verified)
- Single child gold source: `ChatGPT Image Feb 22, 2026, 07_44_19 AM.png`
- Child trio source (blue/pink/rainbow): `ChatGPT Image Feb 22, 2026, 11_34_47 AM.png`

## Profile Mapping
- `child_gold_default`
  - Audience: `male`, `female`
  - Plan access: free + pro
  - Intended use: default child profile (male/female)
- `child_blue_male_alt`
  - Audience: `male`
  - Plan access: pro only
- `child_pink_female_alt`
  - Audience: `female`
  - Plan access: pro only
- `child_rainbow_female2_nonbinary`
  - Audience: `female`, `non_binary`
  - Plan access: pro only

## Visibility Rules
- Child role (`profileControl.roleCode == child`): child profiles are visible by default, subject to plan gating.
- Parent/adult roles: child profiles are hidden from main profile lists by default.
- Parent/adult roles can expose child profiles in main lists only when all conditions are true:
  - Pro access is active.
  - Guardian-only override is enabled.

## Guardian Override Rules
- Setting key: `allow_child_profiles_as_main`
- Control surface: guardian settings only.
- Non-guardian attempts to change the setting are rejected.
- Enabling override without Pro is rejected and reverted.

## Cycle Order Lock
When child profiles are visible for an audience, child profile cycle order is deterministic:
1. `child_gold_default`
2. `child_blue_male_alt`
3. `child_pink_female_alt`
4. `child_rainbow_female2_nonbinary`

Non-child profiles keep their existing order and are listed before child profiles.

## Implementation Touchpoints
- `android-watchdog/app/src/main/assets/lion_profiles.json`
- `android-watchdog/app/src/main/java/com/realyn/watchdog/LionThemePrefs.kt`
- `android-watchdog/app/src/main/java/com/realyn/watchdog/GuardianSettingsActivity.kt`
- `android-watchdog/app/src/main/java/com/realyn/watchdog/theme/LionProfileDesignCatalog.kt`
- `android-watchdog/app/src/main/res/layout/activity_guardian_settings.xml`
- `android-watchdog/app/src/main/res/values/strings.xml`
