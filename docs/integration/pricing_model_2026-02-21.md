# DT Guardian Pricing Model (Android-first)

Date: 2026-02-21
Rule requested: 7-day free trial, then opt-in weekly/monthly/yearly plans, with DT pricing set to 15% below top-competitor average and region-adjusted amounts.

## Competitor monthly references (USD)

1. Bitwarden Premium: $1.65/month equivalent (billed annually).
- Source: https://bitwarden.com/pricing/

2. 1Password Personal: $2.99/month equivalent (billed annually).
- Source: https://start.1password.com/sign-up/family?currency=USD

3. Dashlane Premium reference: $4.99/month.
- Source: https://support.dashlane.com/hc/en-us/articles/25851560554258-How-a-plan-change-affects-your-invoice
- Note: official support billing example references a $4.99 monthly plan.

## Base calculation (USD benchmark)

- Competitor average monthly:
  - (1.65 + 2.99 + 4.99) / 3 = 3.21
- DT monthly target at 15% lower:
  - 3.21 x 0.85 = 2.7285 -> $2.73
- DT weekly target derived from monthly annualized:
  - (2.73 x 12) / 52 = 0.63 -> $0.63
- DT yearly target with "12 for price of 10" rule:
  - 2.73 x 10 = $27.30/year

## Region-based pricing model

Local monthly amount formula:
- local_monthly = base_monthly_usd x usd_to_local_rate x affordability_factor

Then:
- local_weekly = local_monthly x 12 / 52
- local_yearly = local_monthly x 10

Policy examples currently configured:
- US: USD, rate 1.0, factor 1.0
- UK (GB): GBP, rate 0.79, factor 1.20 (relatively higher than US benchmark)
- Thailand (TH): THB, rate 35.8, factor 0.55 (relatively lower)
- Philippines (PH): PHP, rate 56.0, factor 0.60 (relatively lower)
- European Union (EU): EUR, rate 0.92, factor 1.05 (country-group mapped)
- Australia (AU): AUD, rate 1.53, factor 1.05
- Canada (CA): CAD, rate 1.36, factor 1.03
- Singapore (SG): SGD, rate 1.35, factor 1.02
- Japan (JP): JPY, rate 150.0, factor 0.95

## Pro Lifetime entitlement (device-scoped)

- `pricing.lifetime_pro` now supports device allowlist grants using SHA-256 hashes.
- App resolves entitlement locally:
  - first: persisted local entitlement (`SharedPreferences`)
  - second: `pricing.lifetime_pro.allowlisted_android_id_sha256`
- This is intended for owner/founder devices while keeping official store billing for regular users.

## Feedback + referral discount

- Android app now includes:
  - performance rating (1-5)
  - "would you recommend to friends" capture
- If recommendation is `yes`, pricing UI applies `pricing.referral_offer.recommend_discount_percent`.
- Current workspace policy default: 10% recurring-plan discount.
- Official recurring discount enforcement should be migrated to Play billing offers after publish.
- Feedback submissions are now posted to local support hub API (`/api/support/feedback`) with local queue retry on-device.
- For USB-local validation on physical devices, use `adb reverse tcp:8787 tcp:8787` so app submissions reach the workstation hub.

## Free vs Paid feature policy (Android runtime)

- Source of truth: `pricing.feature_access` in `config/workspace_settings.json`
- Free tier (default):
  - credential records: 40
  - pending queue actions: 5
  - breach scans: 2/day
  - continuous active scan: locked
  - overlay assistant: locked
  - AI hotline: locked
- Paid-equivalent tier (trial, paid plan, lifetime):
  - all limits set to unlimited
  - continuous active scan: enabled
  - overlay assistant: enabled
  - AI hotline: enabled

## Current output examples from configured policy

- US: weekly $0.63, monthly $2.73, yearly $27.30
- UK: weekly GBP0.60, monthly GBP2.59, yearly GBP25.90
- Thailand: weekly THB12.40, monthly THB53.75, yearly THB537.50
- Philippines: weekly PHP21.17, monthly PHP91.73, yearly PHP917.30
- EU: weekly EUR0.61, monthly EUR2.64, yearly EUR26.40
- Australia: weekly AUD1.01, monthly AUD4.39, yearly AUD43.90
- Canada: weekly CAD0.88, monthly CAD3.82, yearly CAD38.20
- Singapore: weekly SGD0.87, monthly SGD3.76, yearly SGD37.60
- Japan: weekly JPY90, monthly JPY389, yearly JPY3890

## Implementation targets

- Workspace policy source of truth:
  - `config/workspace_settings.json`
- Android pricing/trial rendering:
  - `android-watchdog/app/src/main/java/com/realyn/watchdog/PricingPolicy.kt`
  - `android-watchdog/app/src/main/java/com/realyn/watchdog/MainActivity.kt`
- Public support/pricing page:
  - `docs/index.html`
  - `docs/terms.html`
