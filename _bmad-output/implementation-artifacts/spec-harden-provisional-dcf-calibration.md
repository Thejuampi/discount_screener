---
title: 'Harden provisional DCF calibration semantics and verification'
type: 'bugfix'
created: '2026-07-30'
status: 'in-review'
review_loop_iteration: 0
baseline_commit: '271873e6ea8dd6cc074e19073886aab378ea643f'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/valuation-model-family-architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Policy/2 currently calibrates provisional FCFF rates and normalizes FCF, but its diagnostics relabel the run-rate as the latest fiscal value, Android adoption is weakly verified, the shared T fixture is documentation-only, and operating caches can retain an older policy. A reported AMZN runtime case also proves raw endpoint CAGR can invert bear/base/bull ($11.59/$1.39/$2.48). The research and SPEC overstate the breadth and runtime role of the Street evidence.

**Approach:** Preserve the no-clamp, model-family design while making latest FCF and the valuation run-rate separate additive fields, executing the shared calibration contract on Windows and Android, rejecting stale engine/policy results, and making the evidence and supported-surface scope explicit.

## Boundaries & Constraints

**Always:** Keep weighted analyst mean as a development-time bias metric only; preserve the recent-window average as policy/2's named mid-cycle run-rate; keep ACGL-class financials on residual income; keep fixed-point public values and additive serialization; expose provisional provenance and policy versions.

**Ask First:** Changing the 175 bps maximum uplift, replacing recent-window normalization with a different economic policy, or declaring desktop policy/2 parity requires new multi-name evidence and user approval.

**Never:** Read analyst targets inside valuation compute; emit a runtime `calibration_target` reason; cap intrinsic value against price/Street; silently label a normalized average as latest; claim desktop contract adoption before it is implemented.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Normalized FCFF | Positive recent annual window | Preserve true latest and expose separate run-rate plus normalized flag | Sparse/non-positive input remains unavailable |
| Fiscal gap | Missing year inside history | Average only the latest contiguous positive suffix | Fewer than required usable points follows existing unavailable path |
| Provisional leverage | Default CoD with low/mid/capped debt weights | Uplift scales monotonically to the versioned maximum | No analyst/price output assignment |
| Android high leverage | Interest-derived CoD with extreme market weights | Structure guard prevents circular debt dominance even without uplift | Mark inputs provisional |
| Stale analysis | Engine or model-policy mismatch | Remove stale value; financials recompute from fundamentals and operating names become demand-recompute eligible | Never serve the stale intrinsic |
| Volatile FCF trough | AMZN-like positive contiguous window ending in a CapEx-driven trough | Robustify endpoint growth around dynamic stable growth; preserve bear≤base≤bull and expose latest/run-rate separately | Keep analyst disagreement visible; never blend or clamp output |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/dcf_model.rs` -- Owns policy/2 FCFF run-rate, WACC uplift, diagnostics, and T regression.
- `apps/windows/src-tauri/src/engine.rs` / `commands.rs` -- Cache admission/reconciliation and demand-driven recompute gate.
- `apps/windows/src-tauri/src/quant_lens.rs`, `apps/windows/src/api.ts`, `apps/windows/src/components/QuantLensPanel.tsx` -- Diagnostic transport, formatting, and labels.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt` and `model/Models.kt` -- Kotlin policy mirror and additive diagnostics.
- `apps/android/core/src/test/.../DcfAnalysisEngineTest.kt` and `contracts/ContractFixtureTest.kt` -- Android behavior and shared-contract consumers.
- `shared/contracts/valuation-model-family.json` -- Typed T inputs, numeric acceptance, and explicit Windows/Android adoption with desktop deferred.
- `_bmad-output/planning-artifacts/research-dcf-vs-street-gap-T-2026-07-30.md`, existing calibration SPEC, and project context -- Evidence claims, development/runtime distinction, and surface scope.
- `apps/desktop/src/workstation/app_core.rs` -- Read-only evidence for the declared policy/1 deferral.

## Tasks & Acceptance

**Execution:**
- [x] Add dual latest/run-rate diagnostics, contiguous-window handling, honest reasons, tighter T assertions, leverage controls, and policy-version cache invalidation in Windows.
- [x] Update Quant Lens transport/UI so both FCF concepts are distinctly named and formatted.
- [x] Add Android diagnostics, structure guard parity, policy/2 unit coverage, and executable shared-contract coverage.
- [x] Make the shared fixture machine-checkable and edit research/SPEC/context to state T-first evidence, external metric semantics, and desktop deferral.

**Acceptance Criteria:**
- Given the T shared fixture, when both engines compute policy/2, then each selects FCFF/WACC, applies a provisional uplift, preserves latest versus normalized run-rate, lands inside the pinned honest residual band, and never equals Street/price by assignment.
- Given low-, mid-, and cap-leverage controls, when default CoD is used, then uplift is monotonic and debt-scaled.
- Given a cached operating analysis from policy/1, when detail or Quant Lens is requested, then it is cleared and demand recomputation is allowed.
- Given ACGL-like fundamentals, when valuation runs, then residual income remains primary.
- Given the shared AMZN trough fixture, when Windows and Android compute FCFF, then latest FCF remains $7.695B, normalized run-rate remains $24.263B, endpoint growth is robustified, and bear≤base≤bull.

## Spec Change Log

## Design Notes

The 175 bps maximum remains explicitly provisional and T-first. Tests may prove scaling and non-clamping, but documentation must not turn synthetic leverage controls into multi-name empirical validation. AMZN adds a distinct structural scenario-ordering regression, not another calibration target.

## Verification

**Commands:**
- `cargo fmt --manifest-path apps/windows/src-tauri/Cargo.toml -- --check` -- expected: clean formatting.
- `cargo test` from `apps/windows/src-tauri` -- expected: Rust valuation, cache, contract, and Quant Lens tests pass.
- `npm test -- --run` from `apps/windows` -- expected: frontend tests pass.
- `scripts/validate-android.ps1` -- expected: Android core tests pass; app tasks pass when SDK is configured.
- `cargo test` from `apps/desktop` -- expected: declared deferred surface remains regression-free.
