# DT Guardian Approved Scope Architecture + UI Flow

Date: 2026-02-21  
Status: Approved by product owner  
Scope type: Policy-safe defensive expansion (Android consumer app)

## 1) Scope lock (approved)

### In scope for design and build
1. Wi-Fi posture scanner
2. Anti-phishing triage (non-invasive)
3. Encrypted media vault (photos/files)
4. Parent overrides for child profiles
5. Biometric/PIN app access lock
6. Find-provider quick links (Google + other provider launch links)

### Explicitly deferred / hard-gated
1. App hiding of third-party apps (consumer mode)
2. Deep provider account control for device finding ecosystems
3. Full VPN interception / full traffic inspection
4. Remote wipe/remote lock claims unless moved to managed-device architecture

## 2) Architecture goals

1. Keep defensive-only posture and policy-safe implementation.
2. Keep local-first default for secrets, risk state, and vault content.
3. Preserve parent/child controls and guardian auditability.
4. Keep all sensitive actions explainable via guided remediation UX.
5. Reuse existing DT Guardian cards, incident model, and support flows.

## 3) Component architecture (build-ready)

### A) Wi-Fi posture scanner
- Package target: `android-watchdog/app/src/main/java/com/realyn/watchdog/`
- New components:
  1. `WifiPostureScanner.kt`: pulls active connection + nearby posture indicators where permitted.
  2. `WifiRiskEvaluator.kt`: scores posture (open network, weak encryption, suspicious captive portal signals, repeated SSID changes).
  3. `WifiScanSnapshotStore.kt`: local snapshots and timestamped trend history.
  4. `WifiPermissionGate.kt`: central handling for `NEARBY_WIFI_DEVICES` and location permission requirements by API level.
- Output contract:
  - `WifiPostureSnapshot(score, tier, findings, recommendations, scannedAtIso)`
- Integration:
  - feeds Security Hero urgent actions
  - can create guardian alerts when profile role requires guardian reporting

### B) Anti-phishing triage (non-invasive)
- New components:
  1. `PhishingTriageEngine.kt`: evaluates URLs/text artifacts from user-initiated inputs.
  2. `PhishingHeuristics.kt`: local checks (punycode, lookalike domains, insecure links, suspicious query payloads).
  3. `PhishingIntakeStore.kt`: recent triage items + remediation decisions.
  4. `PhishingGuardianBridge.kt`: emits guardian feed entries for high-risk child findings.
- Intake channels (policy-safe):
  1. explicit user paste/share into app
  2. optional clipboard check on user action only
  3. suspicious link scan button from incident details
- Output contract:
  - `PhishingTriageResult(riskScore, severity, reasons, suggestedActions, sourceRef, triagedAtIso)`

### C) Encrypted media vault
- New components:
  1. `MediaVaultCrypto.kt`: envelope encryption using Android Keystore-backed keys.
  2. `MediaVaultIndexStore.kt`: encrypted metadata index (no plaintext filenames in logs).
  3. `MediaVaultFileStore.kt`: scoped-storage file import/export handlers.
  4. `MediaVaultPolicyGate.kt`: profile-based access checks + guardian override requirements.
- Features:
  - import photo/file into vault
  - preview metadata and secure-open flow after app unlock
  - delete/restore within local retention window
- Output contract:
  - `VaultItem(id, type, ownerRole, createdAtIso, lastAccessIso, retentionState)`

### D) Parent overrides + family control bridge
- New components:
  1. `GuardianOverridePolicy.kt`: defines which child actions require parent approval.
  2. `GuardianOverrideTokenStore.kt`: short-lived local approval tokens.
  3. `FamilyControlAuditLog.kt`: immutable local audit trail of override approvals.
- Behavior:
  - child actions that can impact security posture ask for guardian confirmation path
  - parent can approve on parent profile device flow
  - all approvals logged with timestamp and reason code

### E) Biometric/PIN app lock
- New components:
  1. `AppAccessGate.kt`: gatekeeper for app entry + inactivity timeout re-auth.
  2. `BiometricAuthController.kt`: `BiometricPrompt` + device credential fallback.
  3. `PinFallbackStore.kt`: hashed/salted PIN verifier stored locally.
  4. `SessionUnlockState.kt`: in-memory unlock state with idle expiry.
- Behavior:
  - lock on app launch
  - relock after timeout or app background interval
  - guardian can enforce stricter timeout for child profiles

### F) Find-provider quick links
- New components:
  1. `DeviceLocatorProviderRegistry.kt`: provider list and deep/web link templates.
  2. `DeviceLocatorLinkLauncher.kt`: safe `Intent` launcher with fallback handling.
  3. `LocatorCapabilityViewModel.kt`: reflects linked-account hints stored locally (no account takeover controls).
- Providers:
  - Google / Samsung / manufacturer or platform provider quick links
- Constraints:
  - launcher and guidance only; no claim of remote control beyond provider-native flows

## 4) Data and config additions (proposed)

Target: `config/workspace_settings.json` (and synced Android asset)

1. `wifi_posture`:
  - `enabled`
  - `scan_interval_minutes`
  - `guardian_alert_threshold`
2. `anti_phishing`:
  - `enabled`
  - `allow_manual_clipboard_intake`
  - `high_risk_auto_alert`
3. `media_vault`:
  - `enabled`
  - `max_items_free`
  - `retention_days_deleted`
4. `app_lock`:
  - `enabled`
  - `allow_biometric`
  - `allow_pin_fallback`
  - `idle_relock_seconds`
5. `device_locator_links`:
  - `enabled`
  - `providers` (label + uri + fallback uri)
6. `guardian_override`:
  - `enabled`
  - `require_for_vault_export`
  - `require_for_high_risk_phishing_actions`

## 5) UI flow (design-ready)

### Entry flow
1. App launch
2. `AppAccessGate` screen (biometric/PIN)
3. Main dashboard after unlock

### Main dashboard card order (proposed)
1. Security Hero (existing): score + top 3 urgent actions
2. Wi-Fi posture card
3. Anti-phishing triage card
4. Media vault card
5. Family overrides status card (parent and child variants)
6. Device locator quick links card
7. Existing advanced controls block (progressive disclosure)

### Primary CTA pairs (uniform raised buttons)
1. Wi-Fi card:
  - `Run posture scan`
  - `View findings`
2. Anti-phishing card:
  - `Scan link/text`
  - `Open remediation`
3. Media vault card:
  - `Add to vault`
  - `Open vault`
4. Family overrides card:
  - `Review approvals`
  - `Guardian settings`
5. Device locator card:
  - `Open provider`
  - `Provider setup`

### Parent vs child behavior in UI
1. Parent profile:
  - full controls
  - guardian feed visible
  - override approvals available
2. Child profile:
  - restricted destructive actions
  - guardian-confirm required on protected actions
  - clear explanation labels on locked actions

## 6) Permission and policy map

1. Wi-Fi posture:
  - `NEARBY_WIFI_DEVICES` (and location where required by API behavior)
2. Networking:
  - existing `INTERNET` for optional reputation checks and provider links
3. Biometrics:
  - no special manifest permission for `BiometricPrompt` use pattern
4. Storage:
  - scoped storage and `MediaStore` pathways only
5. Guardrails:
  - no Accessibility abuse
  - no broad storage manager requirement
  - no full interception claims

## 7) Implementation phases

### Phase 1 (Foundational access + policy)
1. App lock (biometric/PIN + idle relock)
2. Guardian override policy primitives + audit log
3. Shared risk-card model for new modules

### Phase 2 (Network risk modules)
1. Wi-Fi posture scanner + findings store
2. Anti-phishing triage engine + intake UI
3. Guardian escalation hooks for child profiles

### Phase 3 (Vault module)
1. Encrypted media vault core
2. Vault UI and retention/delete flow
3. Guardian-controlled export/delete controls

### Phase 4 (Locator links + polish)
1. Provider quick link registry + launcher
2. Dashboard integration and hero-score weighting updates
3. UX polish pass and translated copy expansion

## 8) Acceptance criteria (per feature)

1. Wi-Fi posture scanner:
  - generates a score and actionable findings on supported devices
  - writes timestamped snapshot to local state
2. Anti-phishing triage:
  - accepts user-provided URL/text
  - returns severity + remediation path
3. Media vault:
  - imported items encrypted at rest
  - vault inaccessible without app unlock
4. Parent override:
  - child protected actions blocked pending guardian approval
  - approval event written to local audit log
5. App lock:
  - biometric or PIN unlock works
  - relock triggers after configured idle timeout
6. Locator links:
  - provider launches correctly or fallback web link opens

## 9) Recommended next implementation step

Start Phase 1 first (App lock + Guardian override foundation), because all later modules depend on these policy and session controls.
