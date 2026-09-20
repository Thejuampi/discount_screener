---
title: 'Correct FCFF QA outliers without price calibration'
type: 'bugfix'
created: '2026-07-31'
status: 'in-review'
review_loop_iteration: 0
baseline_commit: 'dd7290438de09447db5b1f9f6bff8b2d6304f713'
context:
  - 'AGENTS.md'
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md'
  - '_bmad-output/implementation-artifacts/technical-decision-2026-07-31-acquisition-growth-refusal.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The uncommitted SEC/FCFF fix still misstates several live QA cases: MU is reported as missing FCF although SEC supplies aligned drivers; a single material acquisition can erase all growth evidence for five years; provisional WACC remains knowingly soft; and Detail visually attaches the analyst gap to the adjacent DCF. Together these produce unjustified refusals, extreme high/low anchors, and confusing below-market estimates for DVN, MU, GDDY, BR, BSX, ADSK, AVGO, JBL, and HPE.

**Approach:** Harden the shared Windows/Android FCFF policy around aligned annual observations: estimate the base margin jointly from observed FCFF rows, exclude only acquisition-contaminated growth transitions, restore the documented debt-scaled provisional-rate uplift, and present analyst and model relations independently. Freeze the nine reported cases as an evidence corpus without making market price or analyst targets model inputs.

## Boundaries & Constraints

**Always:** Preserve `OCF + after-tax interest - recurring development CapEx`; retain negative years and acquisition evidence; use current split-consistent shares; bump policy/fingerprints and invalidate stale caches; keep Windows/Android exact parity; distinguish missing drivers, non-positive normalized FCFF, and valid DCF downside.

**Ask First:** A new valuation family, acquisition-inclusive CapEx, live non-SEC inputs, or a change to ranking/Quant Lens decision thresholds.

**Never:** Ticker exceptions, intrinsic/price caps, assignment or calibration to analyst targets, sector haircuts, silent FCFF fallback, dropping adverse years, or asserting that every DCF must exceed market price.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Cyclical MU | Majority-positive aligned rows plus one deep negative year | Robust observed-row base remains available; negative year stays in scenarios/provenance | If the robust base is still non-positive, refuse as `non_positive_normalized_fcff`, not missing history |
| Acquisition transition | Material acquisition in one fiscal growth transition | Exclude that transition only; use at least two clean recent transitions | Latest contaminated or insufficient clean evidence uses zero near-term growth with explicit provenance |
| Provisional rates | Default market parameters or provisional rate evidence with debt | Apply `round(175 × min(debt_weight / 40%, 1))` bps | Solid live parameters and solid rate evidence receive no uplift |
| DCF below market | Positive ordered model below current price | Show model downside separately from analyst upside | Do not label it a model error solely from direction |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/dcf_model.rs:driver_model_inputs` -- currently recombines independent component medians and blanket-zeroes growth through `acquisition_normalized`; owns WACC and policy version.
- `apps/windows/src-tauri/src/edgar.rs:fetch_fcf_history` -- canonical SEC rows and acquisition amounts; preserve real evidence and current-share fallback.
- `apps/windows/src-tauri/src/valuation_baseline.rs` -- current MU test checks only an absurdity helper, never computes the MU driver fixture.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt` -- parity implementation for margin, growth filtering, uplift, diagnostics, and policy version.
- `shared/contracts/valuation-model-family.json` and `sec-driver-normalization*.json` -- executable policy/goldens and fingerprints.
- `apps/windows/src/components/DetailPanel.tsx:valuationUnavailableI18nKey` -- maps non-positive FCFF to the false “missing history” message; header gap is analyst-only.
- `apps/windows/src/i18n.tsx` -- distinct non-positive-cycle and model-vs-market copy.
- `_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md` and `technical-decision-2026-07-31-acquisition-growth-refusal.md` -- amend the uncommitted fix to the corrected policy.

## Tasks & Acceptance

**Execution:**
- [ ] `shared/contracts/valuation-fcff-qa-2026-07-31.json`, `valuation-model-family.json`, and `sec-driver-normalization.json` -- freeze the nine SEC/provenance cases and policy constants; targets remain validation metadata only.
- [ ] `apps/windows/src-tauri/src/dcf_model.rs`, `edgar.rs`, and `valuation_baseline.rs` -- add red MU, acquisition-transition, provisional-WACC, share-scale, ordered-scenario, and stale-cache regressions; implement the structural fix.
- [ ] `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt` and provider/contract tests -- mirror policy and exact contract outputs.
- [ ] `apps/windows/src/components/DetailPanel.tsx`, `src/i18n.tsx`, and `tests/detailValuationPresentation.test.ts` -- label analyst gap, expose DCF premium/downside, and map non-positive FCFF accurately.
- [ ] `_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md`, `technical-decision-2026-07-31-acquisition-growth-refusal.md`, `AGENTS.md`, and `_bmad-output/project-context.md` -- replace the blanket acquisition rule and record the invariants.

**Acceptance Criteria:**
- Given the frozen nine-name corpus, when both engines run, then drivers, clean/contaminated growth years, WACC uplift, cents, reason codes, and fingerprints match exactly with ordered scenarios or a truthful structural refusal.
- Given MU's 2023-2025 rows, when normalization runs, then the test executes the engine and cannot pass through an unused helper-only fixture.
- Given AVGO/ADSK/BR-style historical acquisitions followed by clean periods, when growth is estimated, then clean observations survive; given CRGY/latest contaminated growth, then near-term growth remains zero.
- Given DVN/GDDY provisional rates, when WACC is derived, then debt-scaled uplift is nonzero and provenance-visible without reading price targets.
- Given BSX/ADSK/AVGO/JBL/HPE below-market DCFs, when Detail renders, then analyst upside and DCF downside cannot be mistaken for each other.

## Spec Change Log

- 2026-07-31: Human paused application integration and requested headless local PoCs over an expanded 15-symbol cohort (DVN, GDDY, WYNN, SNDK, BR, BSX, AMZN, AVGO, HPE, MU, ORCL, AAPL, CPRT, CEG, ALB). The experiment is documented in `poc-forward-owner-earnings-2026-07-31.md`. No PoC model is wired into an application runtime; integrating a forward owner-earnings family remains a separately approved next step.

## Design Notes

Base FCFF margin is the median of aligned observed annual FCFF margins (the mean of the two central margins for an even count), not independently selected component medians that synthesize an unobserved business state. Every annual margin still satisfies the FCFF identity and component diagnostics remain visible. Acquisition cash in fiscal year Y contaminates only revenue growth from Y-1 to Y; use clean recent transitions only when at least two exist and the latest transition is clean, otherwise use zero near-term growth.

## Verification

**Commands:**
- `cargo fmt --check; cargo test --lib dcf_model::; cargo test --lib valuation_baseline::; cargo test --lib quant_lens::; cargo test --lib edgar::` from `apps/windows/src-tauri` -- all valuation and provider regressions green, zero quarantine.
- `scripts/validate-android.ps1` -- core/provider/parity tests green.
- `npm test` from `apps/windows` -- Detail labeling and refusal-copy tests green.
- `npm run tauri:dev:qa` from `apps/windows` -- reuse one locked QA process and verify all nine reported names plus T/AMZN/CI/JPM/AAPL checklist cases.
