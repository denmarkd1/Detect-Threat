# Phase 6 - Final Pricing and Packaging Update

Date: 2026-03-04

## Objective

Lock pricing/packaging language for launch so in-app messaging, website copy, and release notes describe the same offer.

## Commercial model (locked)

- Trial: 7 days.
- Post-trial options:
  - Free tier (limited usage).
  - Weekly / Monthly / Yearly paid plans.
- Yearly value rule: 12 months of service for the price of 10 months.
- Target pricing strategy: ~15% below selected competitor monthly average baseline, region-adjusted.

Source of truth: `config/workspace_settings.json` (`pricing` block).

## Packaging (customer-facing)

1. Free Guardian
- Core watchdog scans.
- Limited credential queue and breach checks.
- Local timeline visibility.

2. Pro Guardian
- Unlimited credential records/queue usage.
- Continuous scan support.
- Full advanced guardrails and premium support surfaces.

3. Family Guardian
- Parent/child profile governance.
- Guardian override enforcement on sensitive operations.
- Family-safe progression and accountability timeline.

## Top-feature marketing pillars (launch)

- Local-first credential defense with owner-aware triage order.
- Connected-home risk posture (SmartThings-first) with audit timeline.
- VPN broker status guardrails for high-risk account classes.
- Digital key guardrails with integrity + guardian checkpoints.
- Unified non-secret evidence export and KPI telemetry.

## Copy alignment checklist

Ensure these surfaces use the same package language before upload:

- `docs/index.html` (plans + support page)
- `docs/privacy.html`
- `docs/terms.html`
- in-app plan dialog text and disclosure strings (`android-watchdog/app/src/main/res/values/strings.xml`)
- Play Store listing short/full descriptions

## Operator note

If prices are changed in `workspace_settings.json`, rerun all screenshots/listing copy checks before submission so storefront, app, and docs remain synchronized.
