# Android Execution Backlog (DT Guardian)

Date: 2026-02-21
Derived from: `docs/integration/competitor_gap_analysis_2026-02-21.md`

## Completed in current pass

1. Primary identity sign-in baseline (local profile)
- Added one-time primary email profile input and persistence.
- Primary email can drive focused breach scanning flow.

2. Local encrypted credential lineage
- Added encrypted vault storage for current password, pending rotation password, and history.
- Added direct recovery actions: copy current, previous, and queued next password.

3. Keep vs Change workflow
- "Keep current password" saves encrypted baseline.
- "Change + queue rotation" stores pending password and queues execution action.
- "I changed it" confirms rotation and promotes pending to current.

4. Breach scan integration (k-anonymity)
- Added Pwned Passwords hash-prefix checks for stored accounts tied to the primary identity.
- Stores breach count and last-check timestamp per credential record.

5. Adaptive password generation
- Added policy-aware password generation from `site_profiles.json` domain rules with category fallbacks.
- Added in-screen policy preview so users can see active constraints before generating.

6. Free vs paid feature gating (runtime policy)
- Added `pricing.feature_access` policy model for free and paid tiers.
- Enforced gates for continuous scan, overlay assistant, breach scan quota, queue limits, and credential-record limits.
- Added in-app tier visibility so users can see active entitlements and limits.

7. Approved expansion Phase 2 (Wi-Fi posture + anti-phishing triage)
- Added Wi-Fi posture module (`WifiPermissionGate`, `WifiPostureScanner`, `WifiRiskEvaluator`, `WifiScanSnapshotStore`) with timestamped local history.
- Added anti-phishing triage module (`PhishingHeuristics`, `PhishingTriageEngine`, `PhishingIntakeStore`, `PhishingGuardianBridge`) for user-initiated link/text analysis.
- Added guardian escalation hooks for high-risk child-profile phishing and Wi-Fi findings via `GuardianAlertStore`.
- Integrated dashboard Wi-Fi card + phishing intake/remediation flow and updated security-hero weighting.

8. Approved expansion Phase 3 (encrypted media vault + parent override actions)
- Added encrypted media vault modules (`MediaVaultCrypto`, `MediaVaultIndexStore`, `MediaVaultFileStore`, `MediaVaultPolicyGate`) with Android Keystore-backed at-rest encryption.
- Added dashboard media-vault card with `Add to vault` and `Open vault` flows, item retention handling, secure-open preview, and export support.
- Added guardian override enforcement for child-sensitive vault export/delete actions and extended policy/config with `guardian_override.require_for_vault_delete`.

9. Approved expansion Phase 4 (find-provider quick links + hero weighting + UX polish)
- Added device-locator provider modules (`DeviceLocatorProviderRegistry`, `DeviceLocatorLinkLauncher`, `LocatorCapabilityViewModel`) with safe launch + fallback behavior.
- Integrated dashboard device-locator card with `Open provider` and `Provider setup` flows and capability/status guidance.
- Updated Security Hero weighting and action routing for locator readiness, account-link status, and web-only fallback posture.
- Expanded translated copy coverage for the new locator surface and synced `device_locator_links` config into workspace + Android assets.

## Next high-priority Android items

1. Official Play entitlement enforcement (publish gate)
- Replace local selected-plan trust with Play Billing purchase-state validation.
- Bind free/paid feature gates to signed store entitlement status.

2. Official Play referral discounts (publish gate)
- Map in-app recommendation flow to Google Play billing offers/promo codes.
- Enforce store-side discount eligibility and redemption audit trail.

3. Completion receipts and incident linkage
- Write non-secret completion receipts whenever a rotation is confirmed.
- Link receipt IDs to incident lifecycle records (`open`, `in_progress`, `resolved`).

4. Queue deadlines and owner views
- Add due date, risk reason, and owner filter in credential queue UI.
- Highlight overdue high-priority account classes (`email`, `banking`) first.

5. Recovery hardening
- Add local encrypted export/import for credential lineage backup (device-to-device transfer).
- Require local confirmation gate for destructive queue actions.

6. Threat-context tie-in
- Add optional posture checks before sensitive rotation flows (overlay abuse indicators, unknown-sources state, accessibility risk).

## Approved expansion scope (locked 2026-02-21)

Design-ready architecture and UI flow:
- `docs/integration/approved_scope_architecture_ui_flow_2026-02-21.md`

Implementation phase order:
1. Phase 1: App lock (biometric/PIN) + guardian override foundation (completed)
2. Phase 2: Wi-Fi posture scanner + anti-phishing triage (completed)
3. Phase 3: Encrypted media vault + parent override actions (completed)
4. Phase 4: Find-provider quick links + hero weighting + UX polish (completed)

## Validation checklist for connected device

1. Save primary email and verify persistence after app restart.
2. Save current password for one account and confirm vault summary updates.
3. Queue a rotation and verify queued-next password can be copied.
4. Confirm rotation applied and verify queue status changes to completed.
5. Run breach scan and verify summary/flags are updated without app crash.
