# DT Guardian Release Signing + Play Rollout Checklist

Date: 2026-02-21
Target repo: https://github.com/denmarkd1/Detect-Threat
Package: `com.realyn.watchdog`

## 1) Pre-release invariants

- Keep package name unchanged: `com.realyn.watchdog`.
- Ensure `versionCode` strictly increases each release.
- Ensure `versionName` matches release notes and changelog.
- Verify privacy/terms/support URLs are final and public.
- Confirm no raw secrets/passwords are logged or bundled.

## 2) Configure release signing (local env)

This workspace already supports env-based signing in `android-watchdog/app/build.gradle.kts`.

Required environment variables:
- `DT_RELEASE_KEYSTORE_PATH`
- `DT_RELEASE_STORE_PASSWORD`
- `DT_RELEASE_KEY_ALIAS`
- `DT_RELEASE_KEY_PASSWORD`

Recommended shell setup (not committed):
```bash
export DT_RELEASE_KEYSTORE_PATH=/absolute/path/upload-keystore.jks
export DT_RELEASE_STORE_PASSWORD='...'
export DT_RELEASE_KEY_ALIAS='upload'
export DT_RELEASE_KEY_PASSWORD='...'
```

## 3) Build signed release artifacts

One-command build helper:
```bash
bash scripts/ops/build_play_release.sh
```

This builds:
- `android-watchdog/app/build/outputs/bundle/release/app-release.aab` (upload to Play)
- `android-watchdog/app/build/outputs/apk/release/app-release.apk` (local verification)

And prints:
- SHA256 checksums for artifacts
- signing cert fingerprints (SHA1/SHA256) from your keystore

## 4) Play Console setup

- Create app (if first release) and choose package `com.realyn.watchdog`.
- Enroll in Play App Signing.
- Upload AAB to **Internal testing** first.
- Add release notes and tester list.
- Validate:
  - install/update path from existing Play-installed build
  - entitlement flow
  - feature-tier gating (free vs paid)
  - support URL + AI hotline tier behavior

## 5) Staged rollout progression

- Internal test -> Closed test -> Production staged rollout.
- Suggested production ramp:
  1. 5%
  2. 20%
  3. 50%
  4. 100%
- Hold each phase long enough to review crash/ANR/vitals and support tickets.

## 6) Update chain correctness

- Official updates come from Play once users are on Play-signed installs.
- Sideload/debug installs are for QA only and are not the official update chain.
- Keep versionCode monotonic to avoid update blocks.

## 7) Go/No-Go gate

Go only if all are true:
- Build/lint/tests pass
- Store listing + policy forms complete
- Internal test pass signed off
- No unresolved high/critical incidents in support queue
- Rollback plan prepared (hotfix version bump path)
