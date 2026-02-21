# Realyn Phone Security Watchdog

This workspace is an official D_T satellite focused on defensive Android monitoring.

## Android App (Local On-Device)
- App name: `DT Scanner`
- Mode label: `DT Scanner (Detect Treat)`
- Package: `com.realyn.watchdog`
- Branding in app: Danicous Technologies attribution and D_T foundation statement
- Launcher icon: lion head asset imported from trading2 workspace
- Support center button in app opens: `https://denmarkd1.github.io/Detect-Threat/`

### User Controls
- `Run one-time scan`: immediate local device scan
- `Start active scan mode`: continuous foreground scanning every 5 minutes
- `Stop active scan mode`: stops continuous service
- `Support DT Scanner`: opens donation/legal/support pages

## Monetization Guardrails
- Version 1 is fully free.
- Donations are optional support only and do not unlock digital features.
- Future paid digital features must use official store billing channels.

## GitHub Pages Docs
- `docs/index.html`: support + donation links + QR layout
- `docs/privacy.html`: privacy policy
- `docs/terms.html`: terms of use
- `docs/patreon-startup.html`: Patreon setup walkthrough
- `docs/README.md`: GitHub Pages publishing steps
- `docs/donation-links-template.md`: placeholders to replace with live donation links

## Linux-Side Tooling
- Local ADB install: `tools/android/platform-tools/adb`
- Connection check: `bash scripts/ops/check_adb_connection.sh`
- Legacy CLI scanner remains available: `python3 watchdog/watchdog.py`

## Build + Install
1. `cd android-watchdog`
2. `./gradlew assembleDebug`
3. `../tools/android/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Limits
- This is a defensive watchdog, not a full forensic guarantee.
- Non-root Android APIs cannot detect every advanced compromise.
