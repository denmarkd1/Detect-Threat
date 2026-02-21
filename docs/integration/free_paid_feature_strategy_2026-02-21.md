# DT Guardian Free vs Paid Strategy (Competitor-Aligned)

Date: 2026-02-21

## External benchmark snapshot

1. Bitwarden
- Public pricing page shows a free tier and paid tiers, with free positioned as broad baseline access and premium adding advanced reports/features.
- Source: https://bitwarden.com/pricing/

2. 1Password
- Public signup/pricing flow is trial-first with paid subscription afterward, not a permanent broad free tier model.
- Source: https://start.1password.com/sign-up/family?currency=USD

3. Dashlane
- Dashlane announced ending free-plan onboarding in 2025 and moving users toward paid/trial plans.
- Source: https://www.dashlane.com/blog/updates-to-dashlane-free
- Dashlane also has referral mechanics tied to premium benefits.
- Source: https://support.dashlane.com/hc/en-us/articles/202699111-Refer-friends-and-earn-free-Dashlane-premium

## DT positioning decision

DT should keep a meaningful free tier (to outperform trial-only competitors) while reserving high-cost/high-risk automation features for paid users.

## Feature split implemented in Android

### Free
- One-time device scan
- Incident visibility and manual controls
- Credential vault local storage (up to 40 records)
- Rotation queue (up to 5 pending actions)
- Breach scans (up to 2/day)
- Update checks and basic support access

### Paid-equivalent (trial, paid plan, lifetime)
- Continuous active scan mode
- Overlay assistant for guided password rotation
- Unlimited credential records
- Unlimited queue actions
- Unlimited breach scans
- AI hotline eligibility flag for support routing

## Gap-added advantages vs common competitors

1. Family-owner-aware prioritization
- Built-in owner and category triage (`email` -> `banking` -> `social` -> `developer` -> `other`).

2. Local-first remediation workflow
- Queue and credential lineage remain local and encrypted.

3. Security operations framing
- Watchdog + credential queue + incident lifecycle in one mobile workflow.

## Notes for official publish

- Current Android gating is implemented client-side for runtime UX and local policy enforcement.
- For production billing integrity, the final paid entitlement source must be Play Billing purchase state (and corresponding iOS StoreKit on iOS).
- Referral discounts for recurring subscriptions should be enforced through official store offer mechanisms at publish time.
