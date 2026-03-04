# Phase 6 - Policy and Play Disclosure Review

Date: 2026-03-04
Package: `com.realyn.watchdog`

## Scope

This review aligns three disclosure surfaces:

1. In-app disclosure language.
2. Public policy pages (`docs/privacy.html`, `docs/terms.html`, `docs/index.html`).
3. Google Play Console policy declarations (Data safety + app content forms).

## Product-positioning guardrails

- Defensive security utility only.
- VPN flow is broker/status orchestration only in this phase (not native `VpnService` tunneling).
- No raw password transmission to external services.
- Breach checks use k-anonymity hash-prefix model.
- Destructive account actions remain explicit-user-confirmation flows.

## Disclosure matrix

| Area | Required disclosure | Repository evidence | Status |
| --- | --- | --- | --- |
| Credential breach checks | Passwords are never sent raw; SHA-1 prefix k-anonymity check only | `CredentialBreachChecker.kt` + policy pages | PASS |
| VPN language | Broker + provider-status model; no interception/tunnel claim in this phase | `workspace_settings.json` (`integration_mesh.connectors.vpn_brokers.disclosures`) + updated policy pages | PASS |
| Smart-home connectors | Connected-home posture uses consented connector state and local risk evaluation | `SmartThingsConnector.kt`, `IntegrationMeshAuditStore`, updated privacy page | PASS |
| Digital key guardrails | High-risk actions require prerequisites and guardian controls for minor profiles | `LocalDigitalKeyRiskAdapter.kt`, `GuardianOverridePolicy.kt` | PASS |
| Data handling | Local-first storage/audit with explicit support/contact channels | updated `docs/privacy.html`, `docs/terms.html`, `docs/index.html` | PASS |
| Family controls | Parent/guardian override and revocation controls disclosed | app flow + updated terms/privacy copy | PASS |

## Play Console declaration checklist (operator)

Before submission, verify these forms in Play Console:

1. Data safety
- Declare data categories used by runtime features (if any network transfer beyond Play Billing is enabled in your release flavor).
- If optional connected AI is exposed, classify as optional and user-provided, and disclose session-only key handling.
- Declare security practices accurately (data encrypted in transit when applicable, deletion behavior for local-only stores).

2. App content
- Privacy policy URL must point to the updated published `docs/privacy.html` page.
- Ads declaration must match actual build behavior.
- Target audience/families declarations must match guardian/minor-profile capabilities and your intended distribution.

3. Sensitive permissions and claims
- Keep permission declarations consistent with current app manifest.
- Do not claim full device compromise prevention or guaranteed threat elimination.

## Evidence package to attach per release

- Latest `logs/phase6/masvs_sweep_<timestamp>.md` report.
- Lint + unit test pass output.
- Signed artifact checksums (`build_play_release.sh` output).
- This disclosure review doc with any per-release delta notes.
