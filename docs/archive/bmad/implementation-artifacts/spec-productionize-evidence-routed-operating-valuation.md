---
title: 'Productionize evidence-routed operating valuation on Windows'
type: 'feature'
created: '2026-07-31'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'dd7290438de09447db5b1f9f6bff8b2d6304f713'
context:
  - 'AGENTS.md'
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/spec-evidence-routed-operating-valuation-core.md'
  - '_bmad-output/planning-artifacts/valuation-model-family-architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The fixed-point operating router produces credible headless candidates for the reported cohort, but Windows production still fetches only trailing FCFF/residual-income inputs, stores one DCF-shaped result, and gives unavailable states little actionable context. Consequently the app can keep showing the original order-of-magnitude errors or generic failures even though the corrected model exists.

**Approach:** Add a demand-driven, source-fingerprinted Yahoo forward-forecast boundary; orchestrate FCFF, resolved cost of equity, structural-distortion evidence, and the existing router through one runtime path; expose the resulting selected/disputed/unavailable decision additively to Detail and Quant Lens; and provide an accessible diagnostic tooltip.

## Boundaries & Constraints

**Always:** Preserve FCFF and forward candidates with typed provenance; keep price and analyst target outside model/router inputs; fetch forward evidence only on demand; reconcile forecast/reporting currency and dates; fail closed for non-operating classes; make cache validity depend on engine/policy/source fingerprints; keep forward quality soft and analyst-correlated; use the same orchestration for Detail and the periodic worker; retain backward-compatible Detail fields temporarily.

**Ask First:** Persisting routes in SQLite, changing the 5000-bps dispute policy, changing forecast coverage/freshness policy, or expanding this slice to Android app/runtime or desktop terminal.

**Never:** Add `earningsTrend` to the full-universe fetch, overwrite FCFF provenance with a forward value, invent bear/bull scenarios for earnings-power, choose a winner in `Disputed`, clear a valid FCFF merely because forward fetch failed, use target/price proximity to route, or embed volatile source line numbers in user diagnostics.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Valid distorted operating name | Current complete Yahoo `+1y` forecast, usable or failed FCFF, resolved CoE, typed distortion | Router decision stored atomically; selected forward value shown with honest model label | Preserve both candidates and complete fingerprints |
| Material model disagreement | Valid FCFF and forward candidates differ above policy threshold | `Disputed`; show both anchors and no single value/upside | Never flatten into generic unavailable or average |
| Sparse/stale/provider failure | Missing range/count/date/currency, Yahoo 429/network error | Usable FCFF remains selectable; forward refusal is diagnostic | Bounded retry policy inherited from Yahoo session; no stale forward selection |
| Total refusal | Neither candidate usable, or class invalid | No selected value; structured provider/model/period/reason/locator tooltip | Clear stale selected value while retaining refusal evidence |
| Changed source/policy | Existing route fingerprint/version no longer matches | Demand recomputation replaces or clears stale route | Never serve a prior selected value with new diagnostics |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/fetcher.rs`, `quote_summary.rs` -- reuse YahooSession/429 behavior; add a separate demand-only earnings-trend fetch and pure normalized parser, not a global module.
- `apps/windows/src-tauri/src/operating_valuation.rs` -- existing arithmetic/router authority; add provider-independent distortion extraction only where evidence is already normalized.
- `apps/windows/src-tauri/src/commands.rs` -- `compute_demand_valuation_once` is the sole operating orchestration; the periodic EDGAR worker must not publish operating FCFF outside it.
- `apps/windows/src-tauri/src/engine.rs` -- atomically retain DCF candidate, operating decision, optional selected value, errors, and invalidation state; project them through `SymbolDetail`.
- `apps/windows/src-tauri/src/dcf_model.rs` -- make extreme fixed-point CoE inputs return a typed refusal without altering normal outputs.
- `apps/windows/src-tauri/src/quant_lens.rs` -- consume a unified model anchor; forward plus Yahoo target is one correlated family and never Strong.
- `apps/windows/src/api.ts`, `detailValuationPresentation.ts`, `components/DetailPanel.tsx`, `i18n.tsx` -- additive DTOs, selected/disputed/unavailable presentation, accessible `(i)` diagnostics, and stable code locators.
- `apps/windows/src-tauri/tests/fixtures/yahoo/earningsTrend/` -- at least five real complete provider captures plus sparse/currency/date mutations; no invented live payloads.

## Tasks & Acceptance

**Execution:**
- [x] `apps/windows/src-tauri/src/fetcher.rs`, `quote_summary.rs`, `tests/fixtures/yahoo/earningsTrend/` -- add typed demand forecast, observed-at/source fingerprint, currency/date/coverage validation, and fixture tests.
- [x] `apps/windows/src-tauri/src/commands.rs`, `engine.rs`, `operating_valuation.rs` -- TDD one orchestration and atomic route lifecycle, including stale invalidation and partial-failure preservation.
- [x] `dcf_model.rs` -- replace legacy extreme-rate overflow behavior with typed checked resolution and boundary tests.
- [x] `apps/windows/src-tauri/src/quant_lens.rs` -- add model-aware correlated-family and disputed-anchor tests.
- [x] `apps/windows/src/api.ts`, `detailValuationPresentation.ts`, `components/DetailPanel.tsx`, `i18n.tsx` -- presenter-first UI integration and keyboard-accessible diagnostic tooltip tests.
- [x] `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`, `shared/contracts/README.md`, `docs/valuation-live-qa-checklist.md` -- record runtime boundary, diagnostic schema, and live QA expectations.

**Acceptance Criteria:**
- Given the reported 15-name inputs obtained through the production normalization path, when headless runtime orchestration runs, then all receive a numeric candidate or explicit non-operating refusal, route outputs reproduce the durable contract, and no target/price mutation changes them.
- Given selected, disputed, partial-provider-failure, and total-refusal states, when `get_symbol_detail` and Quant Lens project them, then no stale/synthetic primary appears and every failure exposes provider, period, reason codes, policy/fingerprint, and stable owning-code locator.
- Given Yahoo target plus Yahoo-derived forward evidence, when evidence strength is computed, then they count as one family, agreement adds no independent bonus, and forward cannot produce Strong.

## Design Notes

`DcfAnalysis` remains the cash/residual candidate. A separate `OperatingRouteDecision` is the source of truth for operating selection. Compatibility fields may mirror a selected legacy DCF, but forward and disputed states must be read from the additive decision contract.

## Verification

**Commands:**
- `cargo fmt --check; cargo test --lib quote_summary::; cargo test --lib operating_valuation::; cargo test --lib engine::; cargo test --lib quant_lens::; cargo test --lib dcf_model::; cargo test --lib valuation_baseline::` from `apps/windows/src-tauri` -- parser, lifecycle, model, and merge bars green.
- `npm test; npm run lint` from `apps/windows` -- presenter/API/component checks green or unrelated baseline lint failures documented precisely.
- Headless live audit over at least five distinct Yahoo samples and the reported cohort -- bounded demand calls, current fingerprints, no UI process.
- `npm run tauri:dev:qa` only after automated integration passes -- one long-lived QA process, checklist names one-shot only.

**Result (2026-07-31):**
- Windows merge bar: `valuation_baseline::` 10 passed / 1 live ignored; `dcf_model::` 35 passed; `quant_lens::` 8 passed; full library 328 passed / 5 ignored (333 total).
- Provider/runtime integration: five reported Yahoo captures passed parser -> currency/date/coverage normalization -> runtime router; successful-cache age, horizon, fundamentals fingerprint, and policy-version boundaries passed.
- Headless live PoC: `valuation_baseline::live_headless_current_engine_poc --ignored --nocapture` passed against current Yahoo/SEC without opening the UI.
- Windows client: 122 tests passed and production build passed. Scoped lint has only the three existing `DetailPanel.tsx` baseline findings (`set-state-in-effect` at the detail/provenance effects and render-time `Date.now`); no new-file lint finding remains.
- Cross-platform safety: Android validation succeeded (`core:test`, app unit tests, debug assemble); desktop `cargo test` succeeded.
- Review: three proactive BMAD review passes; round-one and round-two code findings were patched and regression-tested. The UI process was intentionally not launched in this headless phase.

## Suggested Review Order

**Runtime routing authority**

- Start here: production evidence becomes one typed selected, disputed, or unavailable envelope.
  [`operating_valuation_runtime.rs:270`](../../apps/windows/src-tauri/src/operating_valuation_runtime.rs#L270)

- Pure fixed-point router preserves both candidates and refuses material disagreement.
  [`operating_valuation.rs:291`](../../apps/windows/src-tauri/src/operating_valuation.rs#L291)

- Demand orchestration combines Yahoo, SEC, rates, fingerprints, and stale-input protection.
  [`commands.rs:722`](../../apps/windows/src-tauri/src/commands.rs#L722)

**Provider and lifecycle boundaries**

- Yahoo parser enforces complete annual evidence, reconciled currency, dates, and fingerprints.
  [`quote_summary.rs:48`](../../apps/windows/src-tauri/src/quote_summary.rs#L48)

- Demand-only fetch reuses bounded Yahoo session and typed provider failures.
  [`fetcher.rs:355`](../../apps/windows/src-tauri/src/fetcher.rs#L355)

- Cache currentness binds fundamentals, policy versions, observation age, and forecast horizon.
  [`engine.rs:2322`](../../apps/windows/src-tauri/src/engine.rs#L2322)

- Ranking accepts only genuinely selected FCFF, never correlated forward evidence.
  [`engine.rs:2381`](../../apps/windows/src-tauri/src/engine.rs#L2381)

**Projection and diagnostics**

- Quant Lens computes disagreement safely and keeps conflicting anchors separate.
  [`quant_lens.rs:183`](../../apps/windows/src-tauri/src/quant_lens.rs#L183)

- Detail presenter maps routed states without resurrecting stale legacy DCF.
  [`detailValuationPresentation.ts:120`](../../apps/windows/src/detailValuationPresentation.ts#L120)

- Diagnostic tooltip exposes actionable provenance with keyboard and pinned-click behavior.
  [`DetailPanel.tsx:56`](../../apps/windows/src/components/DetailPanel.tsx#L56)

- Quant Lens polls demand results with response-generation ordering through bounded timeout.
  [`QuantLensPanel.tsx:11`](../../apps/windows/src/components/QuantLensPanel.tsx#L11)

**Regression evidence**

- Reported provider captures traverse parser, normalization, and runtime routing end-to-end.
  [`operating_valuation_runtime.rs:632`](../../apps/windows/src-tauri/src/operating_valuation_runtime.rs#L632)

- Selected Yahoo forward value cannot masquerade as independent DCF ranking evidence.
  [`engine.rs:2950`](../../apps/windows/src-tauri/src/engine.rs#L2950)

- Live Yahoo/SEC PoC exercises the current engine without launching application UI.
  [`valuation_baseline.rs:805`](../../apps/windows/src-tauri/src/valuation_baseline.rs#L805)
