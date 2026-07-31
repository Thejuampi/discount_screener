---
title: 'PR #24 Android valuation model-family parity'
type: 'feature'
created: '2026-07-30'
status: 'in-review'
review_loop_iteration: 0
baseline_commit: '8bcf758a96d15e7add1af19f3aca8648c8434d81'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/valuation-model-family-architecture.md'
  - '{project-root}/shared/contracts/valuation-model-family.json'
  - '{project-root}/apps/windows/src-tauri/src/dcf_model.rs'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** PR #24 shipped the Windows valuation model family (policy/2–3: residual-income routing, closed-world refuse, provisional WACC uplift, FCF run-rate normalization, asymmetric provisional scenario stress, stale-policy rejection, refuse reasons in Detail). Android got a partial core mirror; live FCFF scenarios, recovery blend, CoD/tax policy, cache invalidation, and refuse/model labels still diverge from Windows `dcf_model.rs` / `engine.rs`.

**Approach:** Make Android the executable peer of Windows for PR #24 valuation behavior: pure engine parity first, then repository admission/recompute gates, then Detail/projection copy so refuse and model kind are user-visible. TipRanks, Windows `qa` profile, and Advisor regime intel stay Windows-only.

## Boundaries & Constraints

**Always:** Windows `dcf_model.rs` + `shared/contracts/valuation-model-family.json` are the numeric/semantic source of truth; residual income for financials/managed care; Unclassified/NotEligible refuse (no silent FCFF); dual latest FCF vs run-rate fields; provisional WACC uplift only as debt-scaled input when CoD is policy default; fixed-point public values; tests before behavior edits (TDD); keep business rules in `apps/android/core`.

**Ask First:** Changing the 175 bps uplift cap, reintroducing InterestOverDebt as the default FCFF CoD path against Windows policy, or expanding scope to TipRanks/desktop.

**Never:** Intrinsic/price clamps; sector FCF haircuts; silent FCFF for financials or unclassified; reading analyst targets inside valuation compute; claiming desktop policy/2 parity; shipping one-name greens without shared-contract / multi-path engine tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Levered provisional FCFF (T-class) | Debt present; policy default CoD/tax | Debt-scaled uplift; base in shared T band; latest vs run-rate distinct | No Street assignment |
| Recovery FCF step-up | Latest > 1.25× window mean | Run-rate = 50/50 latest+avg | Sparse window still contiguous-suffix only |
| Provisional scenarios | `point_estimate_unreliable` | Bear WACC +150 bps; bull WACC +0; growth still stressed; ordered bear≤base≤bull | Reason `wacc_stress=asymmetric_provisional…` |
| Market-solid rates (if both CoD+tax non-default) | Future solid path | Symmetric ±100 bps WACC bands | N/A today if Windows still always defaults CoD/tax |
| Structure guard | Default CoD and debt weight > 0.40 | Cap debt weight; mark provisional | Do not apply guard solely because InterestOverDebt existed historically |
| Financials / CI / ACGL | Insurance or Healthcare Plans | Residual income primary | Missing book/ROE → unavailable reason |
| Unclassified | Unknown sector/industry | No intrinsic; refuse reason string | Clear any stale FCFF cache |
| Stale policy cache | Cached analysis policy/1 or wrong engine | Drop / demand recompute before serving Detail | Never show stale absurd FCFF |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/dcf_model.rs` — **Source of truth.** Policy CoD always Default when debt>0 (no InterestOverDebt in `derive_wacc`); tax Default; structure guard only under Default CoD; `fcf_run_rate_dollars` recovery blend; FCFF asymmetric WACC scenario bands (`WACC_SCENARIO_*`); `point_estimate_unreliable`; diagnostics + reason codes; `classification_unavailable_reason`.
- `apps/windows/src-tauri/src/engine.rs` (~2249–2340) — `ingest_dcf_analysis` rejects wrong engine/policy and FCFF-on-financials; `ensure_model_routed_valuation`; `valuation_unavailable_reason`.
- `apps/android/core/.../DcfAnalysisEngine.kt` — Partial mirror: classifier/RI/uplift/contiguous window present; **gaps:** no WACC scenario stress; no recovery blend; still InterestOverDebt+reported tax; structure guard always; private version constants; missing asymmetric reason codes / diagnostics fields.
- `apps/android/core/.../model/Models.kt` (`DcfAnalysis`, `WaccInputProvenance`) — Add `pointEstimateUnreliable`, scenario WACC/diagnostics fields needed for UI/tests if not already present.
- `apps/android/core/.../engine/ScreenDataProjectionEngine.kt` — Labels still generic “DCF model”; need FCFF vs residual-income wording + refuse reason projection.
- `apps/android/core/.../model/ScreenDataProjectionModels.kt` / `ProjectedDetailData` — Surface `valuationUnavailableReason`, model label, dual FCF diagnostics if Detail needs them.
- `apps/android/app/.../DefaultDashboardRepository.kt` — `needsDcfResolutionLocked` / cache write must reject stale `engineVersion`/`modelPolicyVersion` and clear wrong-model FCFF for financials/unclassified (Windows `ensure_model_routed` parity).
- `apps/android/app/.../ui/dashboard/DetailScreen.kt` — Show refuse reason when model unavailable; distinguish residual income vs FCFF; provisional + dual FCF honesty (no silent dash).
- `apps/android/core/src/test/.../DcfAnalysisEngineTest.kt` + `ContractFixtureTest.kt` — Extend for recovery blend, asymmetric stress, T/AMZN contracts, RI/unclassified; update obsolete InterestOverDebt expectations to Windows policy.
- `shared/contracts/valuation-model-family.json` — Executable goldens; keep android in `policy2Adoption.executableSurfaces`.

## Tasks & Acceptance

**Execution:**
- [x] `DcfAnalysisEngine.kt` + `Models.kt` — Port remaining Windows FCFF policy: default CoD/tax for WACC path, structure-guard condition, recovery run-rate blend, asymmetric provisional scenario WACC stress, `pointEstimateUnreliable`, public engine/policy versions, `classificationUnavailableReason`, full reason codes/diagnostics.
- [x] `DcfAnalysisEngineTest.kt` + `ContractFixtureTest.kt` — TDD failing tests first for matrix rows; execute shared T + AMZN fixtures; ACGL/CI residual; unclassified refuse; recovery blend; asymmetric stress; remove/replace InterestOverDebt-as-default asserts.
- [x] `DefaultDashboardRepository.kt` (+ focused unit tests if present) — Stale policy/engine drop; refuse to serve FCFF for reclassified financials/unclassified; expose unavailable reason into projection/detail path.
- [x] `ScreenDataProjectionEngine.kt` + models + `DetailScreen.kt` — Model-kind labels (FCFF DCF vs residual income); surface `valuationUnavailableReason`; dual latest/run-rate and provisional honesty where Detail already shows WACC.
- [x] Run `scripts/validate-android.ps1` (at least `:core:test`).

**Acceptance Criteria:**
- Given the shared T fixture, when Android computes, then FCFF+WACC, provisional uplift, dual FCF fields, base in contract band, no Street assignment, policy version `business-class-policy/4-driver-fcff`.
- Given AMZN trough FCF, when Android computes, then latest $7.695B, run-rate uses recovery blend toward contract, bear≤base≤bull with robustified growth and WACC stress.
- Given provisional rates, when scenarios build, then bear WACC is base+150 bps, bull WACC equals base, reasons include asymmetric provisional stress.
- Given CI/ACGL-class sector/industry, when valuation runs, then residual income (or unavailable for missing book/ROE), never FCFF-primary.
- Given Unclassified fundamentals or stale policy cache, when Detail is projected, then no invented intrinsic and refuse/stale reason is visible.
- Given financials with old FCFF cached, when resolve/reconcile runs, then cache is cleared or replaced by RI/unavailable.

## Spec Change Log

## Design Notes

Windows intentionally **does not** use InterestOverDebt in `derive_wacc` today (comment + tests: rates remain provisional; asymmetric stress stands until live CoD+tax exist). Android still does — that is the largest numeric divergence for levered names with interest series. Parity means matching Windows: policy CoD default + uplift + asymmetric stress. Do not invent a hybrid without Ask First.

Recovery blend (Windows): if `latest > avg * 1.25` then `0.5*latest + 0.5*avg`, else avg; multi-year window still sets normalized=true.

## Verification

**Commands:**
- `scripts/validate-android.ps1` — expected: `:core:test` green (app tasks if SDK configured).
- Targeted: Android Gradle `:core:test` filtering `DcfAnalysisEngineTest` / `ContractFixtureTest`.

**Manual checks (if no CLI):**
- After install: Detail for a financial name shows residual-income (or refuse), not absurd FCFF; unclassified shows reason text; operating provisional shows provisional WACC label and ordered scenarios.
