# Phase 6 - First Play Store Submission Guide (Step-by-Step)

Date: 2026-03-04  
Package: `com.realyn.watchdog`

This guide is for a first-time Play submission and is aligned to current Play Console onboarding and review flow.

## 1) Verify Play developer account readiness

1. Complete developer account setup and policy acceptance.
2. Complete Play Console identity/device verification when prompted.
3. If account type is personal, complete mandatory testing track requirements before requesting production access.

## 2) Confirm technical release readiness in this workspace

Run and archive these checks:

```bash
python3 -m pip install -e .
credential-defense --help
python3 watchdog/watchdog.py --help
bash scripts/ops/precommit_guard.sh --include-unstaged
bash scripts/ops/phase6_masvs_sweep.sh
python3 scripts/ops/lyra_beta_trainer.py --serial <DEVICE_SERIAL>
```

Required pass artifacts:
- `logs/phase6/masvs_sweep_<UTC_TIMESTAMP>.md`
- `logs/lyra_qa/lyra_qa_report_<UTC_TIMESTAMP>.md`

## 3) Build Play upload artifacts

```bash
bash scripts/ops/build_play_release.sh
```

Expected outputs:
- `android-watchdog/app/build/outputs/bundle/release/app-release.aab` (upload to Play)
- `android-watchdog/app/build/outputs/apk/release/app-release.apk` (local validation)

## 4) Prepare Play listing and policy surfaces

1. Store listing: app name, short/full descriptions, screenshots, category.
2. Privacy policy URL: publish `docs/privacy.html` and use that URL in Play.
3. Complete App content forms (ads, target audience, sensitive declarations as applicable).
4. Complete Data safety form based on actual app data handling behavior.

## 5) Meet current Android/API compatibility gate

For new app submissions in 2026, keep `targetSdk` and `compileSdk` at API 35 or higher.

Current workspace status: set to API 35 in `android-watchdog/app/build.gradle.kts`.

## 6) Complete required testing tracks before production

1. Upload AAB to **Internal testing** first.
2. Promote to **Closed testing** and complete account-specific required test period.
3. Validate pre-launch reports, vitals, and tester feedback.
4. Only after test requirements are satisfied, request production access.

## 7) Production rollout execution

Use staged rollout ladder:
1. 5%
2. 20%
3. 50%
4. 100%

At each step, enforce go/no-go from:
- `docs/integration/phase6_staged_rollout_rollback_playbook_2026-03-04.md`

## 8) Rollback readiness (must be pre-approved)

Before first production release:
1. Pre-assign owner for rollback decisions.
2. Keep previous known-good release details documented.
3. Pre-agree hotfix workflow: patch -> version bump -> rebuild -> internal -> closed -> staged production.

## 9) Submission packet checklist

Attach this evidence in your release packet:
- MASVS sweep report
- Lyra QA report
- Signed artifact checksums
- Privacy/terms links
- Policy and disclosure review doc:
  - `docs/integration/phase6_policy_play_disclosure_review_2026-03-04.md`

## 10) Final go/no-go rule

Do not submit for production review unless all of the following are true:
- No failing checks in MASVS or Lyra reports.
- Listing, privacy policy, app content, and data safety forms are complete and consistent.
- Required testing-track conditions are complete for your account type.
- Rollback owner and hotfix path are documented.

## Official references

- App testing requirements: https://support.google.com/googleplay/android-developer/answer/14151465
- Publish your app: https://support.google.com/googleplay/android-developer/answer/9859152
- Pre-review checks / prepare for review: https://support.google.com/googleplay/android-developer/answer/9859455
- Data safety declarations: https://support.google.com/googleplay/android-developer/answer/10787469
- Target API requirements: https://support.google.com/googleplay/android-developer/answer/11926878
- Play App Signing: https://support.google.com/googleplay/android-developer/answer/9842756
- Staged roll-outs: https://support.google.com/googleplay/android-developer/answer/6346149
- Developer account verification and setup links: https://support.google.com/googleplay/android-developer/answer/10841920
