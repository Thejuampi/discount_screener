# Valuation divergence review — 2026-07-30

## Scope and acceptance boundary

This review was run through the live Windows audit command with the launch-locked `qa` profile. The profile contained 20 loaded symbols; no Russell, S&P 500, or other broad-universe process was started. The comparison is a validation diagnostic only: analyst targets are not valuation inputs and there is no price/analyst cap.

The audit ran with `business-class-policy/6-regime-driver-fcff` and produced 17 positive-base comparable models plus 3 explicit unavailable results. A row is comparable only when both the analyst anchor and the model base equity value are positive. The gap is the symmetric disagreement relative to the midpoint of the two anchors.

## All comparable cases at or above 40%

| Symbol | Analyst | DCF base | Gap | Regime | FCFF bridge / normalized run-rate | Review result |
| --- | ---: | ---: | ---: | --- | --- | --- |
| MU | $1,507.38 | $32.31 | 191.61% | cyclical/transition | OCF 42.77% + after-tax interest 0.00% - CapEx 40.59% = 2.18%; $814.8M | No arithmetic contradiction remains. The model is explicitly rebased to the current annual bridge; the remaining divergence is dominated by the semiconductor cycle and missing forward cycle-state data. |
| HAL | $43.52 | $7.18 | 143.35% | cyclical/transition | 13.35% + 0.00% - 10.59% = 2.76%; $569.1M | Base is positive; only the bear scenario reaches the limited-liability floor. The remaining gap is leverage plus cyclicality, with provisional discount inputs. |
| MPWR | $1,776.92 | $303.07 | 141.72% | secular expansion | 30.04% + 0.00% - 6.16% = 23.88%; $666.4M | Uses the generic persistent-growth regime and slower 1.5 fade. Remaining gap is a model-vs-consensus forecast disagreement, not a ticker calibration. |
| VRT | $376.15 | $71.29 | 136.27% | secular expansion | 13.12% + 0.00% - 1.86% = 11.26%; $1.152B | Uses the same secular regime policy. The only CapEx imputation is historical (`recent=false`); it is not used as a recent-data exception. |
| HPE | $64.34 | $13.92 | 128.85% | stable operating | 15.20% + 0.99% - 9.01% = 7.18%; $2.462B | Stable regime and explicit bridge are internally consistent; the gap is not caused by an inverted scenario or FCF endpoint CAGR. |
| NVDA | $302.83 | $132.14 | 78.48% | secular expansion | 46.11% + 0.00% - 2.80% = 43.31%; $93.523B | Generic persistence classification and 1.5 fade materially raised the base from the prior run. Remaining gap is the independent forecast versus analyst expectation; historical CapEx imputations are flagged as historical-only. |
| DVN | $59.38 | $94.22 | 45.36% | cyclical/transition | 45.78% + 2.38% - 31.21% = 16.95%; $2.845B | DCF is above consensus, but scenarios remain ordered and the bridge is auditable. The difference is cyclical commodity economics plus provisional rates. |
| TYL | $435.36 | $284.12 | 42.04% | stable operating | 23.35% + 1.01% - 1.05% = 23.31%; $543.7M | Stable driver path and explicit bridge are consistent; remaining difference is a forecast/discount-input disagreement, not a company-specific branch. |

All eight rows carry `model_quality=soft` because the current live market parameter set still uses policy-default cost of debt/tax inputs and therefore marks the point estimate provisional. That status is now visible in the audit evidence instead of being presented as precision. The next data-quality improvement is a real market-rate/credit-input source; it must be implemented as a shared input layer, not as per-symbol calibration.

## Explicitly unavailable results

- `ORCL`: base equity is zero after modeled net debt consumes enterprise value; no positive common-equity estimate.
- `ALB`: same explicit common-equity refusal reason.
- `SNDK`: insufficient positive free cash flow for a run-rate.

These are refusals with reasons, not zero-dollar comparable estimates.

## Structural changes applied

- Annual FCFF is built from aligned OCF, after-tax interest, and CapEx; reported FCF remains separate from normalized FCFF.
- CapEx spikes are detected from prior-history intensity and excluded from the normalized recent baseline when the evidence supports it.
- Revenue growth uses a robust recent driver window and a generic regime classifier: secular expansion, stable operating, or cyclical/transition.
- Only a statistically persistent secular regime gets the slower five-year growth fade; no symbol-specific branch exists.
- A zero common-equity base is unavailable rather than comparable; a bear-only floor is reported as scenario evidence.
- Windows and Android share policy 6 and the same bridge/regime semantics.

## Verification

- Windows: `dcf_model` 30 passed; `valuation_divergence` 4; `valuation_baseline` 9; `quant_lens` 5; `edgar` 12; `cross_platform_parity` 2; frontend production build passed; `cargo fmt -- --check` and `git diff --check` passed.
- Android: `:core:test`, `:app:testDebugUnitTest`, and `scripts/validate-android.ps1` passed. `make android-run` was attempted with the mandated `qa` bootstrap, but the local emulator never registered in `adb devices`; no Android app session or Yahoo fetch was started, so live Android UI QA remains an infrastructure follow-up.
