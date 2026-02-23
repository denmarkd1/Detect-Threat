# Android Design Lock (DT Guardian)

Date: 2026-02-23  
Status: Approved lock for implementation freeze

## Approval record
- Approved on: 2026-02-23
- Approved by: Product owner
- Effective from: next implementation change set

## Purpose
Freeze the Android UI/UX baseline so feature work does not keep shifting layout and styling.

## Source of truth
- `android-watchdog/app/src/main/res/layout/activity_main.xml`
- `android-watchdog/app/src/main/res/layout/activity_credential_defense.xml`
- `android-watchdog/app/src/main/res/layout/view_lion_hero.xml`
- `android-watchdog/app/src/main/res/values/colors.xml`
- `android-watchdog/app/src/main/res/values/themes.xml`
- `android-watchdog/app/src/main/res/values/dimens.xml`
- `docs/integration/android_style_decision_2026-02-21.md`
- `docs/integration/approved_scope_architecture_ui_flow_2026-02-21.md`

## Locked visual system (v1)

### Palette
- Background: `brand_bg_dark #110C08`, `brand_bg #1A120C`
- Panels: `brand_panel #231710`, `brand_panel_alt #2C1D14`
- Stroke: `brand_stroke #7A5B39`
- Accent/action: `brand_accent #D6A545`
- Alert: `brand_alert #C14830`, `nav_alert_dot #D64A33`
- Text: `text_primary #F7EBD5`, `text_secondary #D8BE92`, `text_muted #A78E6A`

### Theme
- App theme: `Theme.RealynWatchdog` (`Theme.Material3.Dark.NoActionBar`)
- Primary button style: `Widget.RealynWatchdog.Button.RaisedAction`
- Two-column action style: `Widget.RealynWatchdog.Button.RaisedUniformPair`

### Core spacing and size
- Hero height: `home_lion_hero_height = 224dp`
- Intro lion size: `intro_lion_hero_size = 320dp`
- Intro welcome bottom margin: `home_intro_welcome_bottom_margin = 172dp`
- Standard card paddings remain 14dp or 16dp as currently defined in layout files.

## Locked home screen information architecture
Order remains:
1. Hero card (lion, score, status, urgent actions)
2. Wi-Fi posture card
3. Anti-phishing card
4. Media vault card
5. Device locator card
6. Translation card
7. Pricing card
8. Hygiene/scam shield card
9. Advanced controls section
10. Incident and readiness cards
11. Scan/continuous/credential/update controls
12. Result card + attribution
13. Bottom nav shell with centered lion action

## Locked navigation model
- Bottom nav uses five actions:
  - Scan
  - Guard
  - Lion home (center)
  - Vault
  - Services/support
- Red-dot alert indicators remain part of nav button state model.

## Locked motion model
- Home intro overlay remains enabled:
  - centered lion intro
  - welcome text fade-in
  - transition into normal home state
- Lion hero supports fill mode and eye-glow accent behavior.

## Accessibility and readability constraints
- Maintain high contrast text against dark panels.
- Do not reduce primary body text below current defaults for key summaries.
- Keep button labels readable at small widths (current max two-line paired button approach stays).

## Design non-goals for this lock
- No new visual theme direction change.
- No major navigation paradigm changes.
- No major card reordering unless tied to a documented scope decision.

## Change-control rule after lock
Any UI change that affects one of these must include a short note in this file:
- color token changes
- major layout hierarchy changes
- nav model changes
- hero/intro motion behavior changes

## Sign-off checklist
- [x] Palette locked
- [x] Theme/component styles locked
- [x] Home IA/card order locked
- [x] Bottom nav model locked
- [x] Intro/lion motion locked
- [x] Accessibility constraints accepted
