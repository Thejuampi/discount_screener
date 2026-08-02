# Fix: AMZN FCFF CapEx-trough median collapse (2026-08-01)

## Symptom (live qa)

| | Before | After |
| --- | --- | --- |
| AMZN base | **$5.15** | **$50.30** |
| FCFF run-rate | ~$9.5B | ~$39.3B |
| Margin reason | `median_aligned_annual:133` | `median_nonneg_aligned_annual:548` |
| Policy | `…/13-industry-beta-policy-v1` | `…/14-fcff-nonneg-base-margin` |

UI still correctly shows **disputed** vs forward ~$195 when model and Street diverge — that is not a license for a multi-billion franchise to price as single-digit equity.

## Root cause

1. Live SEC history supplies full OCF/CapEx/tax drivers for trough years (2021–2022).
2. Those years enter `DRIVER_RECENT_WINDOW` (5).
3. Base FCFF margin used a plain median of **all** annual identity margins → mid-point collapsed to ~1.3% (2025 trough recovery year).
4. Offline fixtures “passed” because 2020–2021 often **lacked** operating drivers, so trough years never entered the driver window.

## Policy fix (Windows + Android)

- **Base run-rate margin:** median of **non-negative** annual FCFF margins when ≥2 exist; else full median.
- **Scenario bear/bull margins:** still full distribution (negatives retained — MU path).
- Reason code: `fcff_margin=median_nonneg_aligned_annual:…`
- `MODEL_POLICY_VERSION` → `business-class-policy/14-fcff-nonneg-base-margin` (cache invalidation).

## Tests / contracts

- `baseline_megacap_amzn_class_not_penny_intrinsic` now uses **full drivers on 2020–2021** and asserts base ≥ $50 and multi-ten-B run-rate.
- Shared contract AMZN `fcfRunRateDollars` → `39287435200`.
- Android `DcfAnalysisEngine` + tests updated for parity.

## Live verification

After `tauri:dev:qa` rebuild:

```text
AMZN policy=14 base=$50.30 run≈$39.3B margin=548 bps ordered scenarios
UI: disputed DCF $50.30 vs forward $195.29
```
