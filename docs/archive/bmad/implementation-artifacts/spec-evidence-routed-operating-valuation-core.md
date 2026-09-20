---
title: 'Build the evidence-routed operating valuation core'
type: 'feature'
created: '2026-07-31'
status: 'done'
review_loop_iteration: 1
baseline_commit: 'dd7290438de09447db5b1f9f6bff8b2d6304f713'
context:
  - 'AGENTS.md'
  - '_bmad-output/project-context.md'
  - '_bmad-output/planning-artifacts/valuation-model-family-architecture.md'
  - '_bmad-output/planning-artifacts/valuation-model-change-decision-2026-07-31.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The headless evidence-router PoC corrects the reported trailing-FCFF failures, but its float arithmetic, provisional thresholds, sparse-consensus acceptance, and incomplete closed-world routing are not production-safe. Copying it would preserve good-looking outputs while hiding unsupported assumptions.

**Approach:** Create a pure fixed-point `ForwardEarningsPower` candidate and `OperatingModelRouter` contract shared exactly by Rust and Kotlin. The core receives normalized forecast, resolved cost of equity, versioned projection policy, FCFF candidate, business class, and explicit structural-distortion evidence; provider derivation and UI/runtime integration remain outside this slice.

## Boundaries & Constraints

**Always:** Keep market price and analyst target absent from engine/router DTOs; route `FinancialServices`, `NotEligible`, and `Unclassified` fail-closed; retain FCFF and forward candidates with provenance; tag forward consensus `AnalystDerivedModel` and soft quality; use cents/bps/epoch dates, checked integer intermediates, half-up rounding, canonical reason ordering, dynamic stable growth, and exact Rust/Kotlin parity.

**Ask First:** Changing how providers derive structural-distortion signals, adding sector/industry beta policy, wiring Yahoo/SEC/runtime/cache/Quant Lens, or presenting the model in UI.

**Never:** Copy PoC sector rate floors, fixed calendar cutoffs, ticker exceptions, float/pow behavior, truthy missing-data fallbacks, financial-to-FCFF fallback, target/price calibration, runtime proximity clamps, or relabel earnings power as FCFF/FCFE.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Structural distortion | Operating class, current complete forward evidence, resolved rate/policy, usable forward candidate | Select forward primary; retain FCFF candidate and ordered route reasons | Candidate disagreement over the existing 5000-bps relative threshold is explicit `Disputed`, never averaged |
| Representative trailing cash | Operating class with usable FCFF and no structural switch reason | Retain FCFF primary | Forward remains visible evidence when eligible |
| Sparse or stale forward | Missing EPS/coverage, expired evidence, wrong currency, or invalid forecast period | Forward candidate unavailable and cannot displace usable FCFF | Emit typed candidate refusal details |
| Invalid model family | Financial, not-eligible, or unclassified business | No operating valuation | Emit deterministic family-specific refusal |
| Invalid economics | Non-positive EPS, invalid horizon, overflow, or cost of equity ≤ stable growth | No forward candidate | Refuse structurally; never clamp inputs |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/dcf_model.rs` -- reuse business class, market parameters, FCFF result/provenance, and extract a typed resolved cost-of-equity result without changing runtime behavior.
- `apps/windows/src-tauri/src/operating_valuation.rs` -- new pure fixed-point forward candidate, router, typed reasons, fingerprints, and unit tests.
- `apps/windows/src-tauri/src/lib.rs` -- expose the headless module only; no command or engine wiring.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/OperatingValuation.kt` -- exact Kotlin interpretation using integer recurrence and checked arithmetic.
- `shared/contracts/operating-valuation-router-v1.json` -- reported 15, independent holdout 12, synthetic boundaries, canonical outputs, and separate `validationOnly` anchors.
- `shared/contracts/valuation-model-family.json` -- reference the new candidate/router contract without changing current app routing.
- `.agents/workspace/tmp/poc_valuation_models.py` -- read-only experimental evidence; never a production dependency or arithmetic authority.

## Tasks & Acceptance

**Execution:**
- [x] `shared/contracts/operating-valuation-router-v1.json` -- freeze integer inputs, projection/routing policy, reason enums, reported/holdout fixtures, and anchor-mutation cases.
- [x] `apps/windows/src-tauri/src/operating_valuation.rs`, `dcf_model.rs`, and `lib.rs` -- red tests first, then implement checked fixed-point projection and routing without runtime integration.
- [x] `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/OperatingValuation.kt` plus contract tests -- reproduce every field and refusal exactly.
- [x] `_bmad-output/planning-artifacts/valuation-model-family-architecture.md` and contract docs -- record model identity, correlated evidence family, arithmetic, and provider boundary.

**Acceptance Criteria:**
- Given identical normalized inputs, when Rust and Kotlin value and route every fixture, then candidate cents, rates, statuses, reasons, quality, and fingerprints match exactly.
- Given mutated validation-only market and target anchors, when the engines recompute from source inputs, then serialized decisions remain byte-identical.
- Given any non-operating business class, when routing runs, then no FCFF or forward operating model is selected.
- Given the reported and holdout cohorts, when the contract report is generated, then coverage/error metrics are published separately while no production branch reads or enforces those anchors.

## Spec Change Log

- 2026-07-31 review loop 1: no frozen intent change. Hardened the implementation with Gordon headroom, bounded forecast/projection windows, signed cross-platform DTO widths, complete decision fingerprints, contradictory-candidate rejection, executable synthetic shared cases, and byte-identical anchor mutation checks. `Disputed` now retains both candidates but clears the singular selected model/value, consistent with the standing disagreement-honest product rule.
- 2026-07-31 review convergence: moved all 27 normalized cohort inputs into the durable shared contract and normal Rust/Kotlin gates; added exact multi-distortion, 5000/5001-bps dispute-boundary, and negative half-up goldens; added typed full-input candidate provenance and exact router recomputation so a mutated candidate cannot be selected.

## Design Notes

The contract uses integer recurrence rather than `pow`: year-one forward EPS is discounted directly; later earnings follow an explicit hold/fade path supplied by a versioned policy; terminal value requires `cost_of_equity_bps > stable_growth_bps`. The core validates freshness/coverage against values carried by the contract instead of inferring the current year. Structural reasons are inputs to this slice so provider heuristics cannot leak into valuation arithmetic.

Candidate disagreement is not a selection. When the relative candidate gap exceeds the versioned dispute threshold, the decision exposes both complete candidates and their difference but leaves `selected_model` and `selected_value_cents` empty.

## Verification

**Commands:**
- `cargo fmt --check; cargo test --lib operating_valuation::; cargo test --lib dcf_model::; cargo test --lib valuation_baseline::` from `apps/windows/src-tauri` -- exact contract, arithmetic boundaries, and existing merge bars green.
- `scripts/validate-android.ps1` -- Kotlin core/parity tests green.
- Headless contract report over reported and holdout cohorts -- no UI/Tauri process, zero anchor leakage, all structural refusals explained.

**Observed:** Rust operating 13/13 green (1 source-audit diagnostic ignored normally), DCF 34/34,
valuation baseline 10/10 (1 live diagnostic ignored), Android core/app validation
green, ten shared synthetic cases plus routing/arithmetic boundary goldens execute on both platforms, and shared JSON parity is exact. The normal test gate now recomputes all 27 names from durable normalized inputs and reproduces 15/15
reported values at 10.9% mean absolute validation error and 11/12 holdout values
at 11.3%; `V` was the intentional financial-model refusal. Results are recorded
in `poc-forward-owner-earnings-2026-07-31.md` and are not runtime acceptance caps.

## Suggested Review Order

**Core model and routing**

- Fixed-point candidate construction, validation, Gordon headroom, and typed provenance begin here.
  [`operating_valuation.rs:210`](../../apps/windows/src-tauri/src/operating_valuation.rs#L210)

- Disagreement-honest routing retains both candidates without publishing a disputed winner.
  [`operating_valuation.rs:291`](../../apps/windows/src-tauri/src/operating_valuation.rs#L291)

- Candidate identity is proven by exact recomputation from structured normalized provenance.
  [`operating_valuation.rs:603`](../../apps/windows/src-tauri/src/operating_valuation.rs#L603)

**Cross-platform contract**

- Kotlin mirrors fixed-point arithmetic, refusal semantics, routing, and provenance validation exactly.
  [`OperatingValuation.kt:218`](../../apps/android/core/src/main/kotlin/com/discountscreener/core/engine/OperatingValuation.kt#L218)

- Shared goldens freeze parity, threshold boundaries, distortion canonicalization, and arithmetic rounding.
  [`operating-valuation-router-v1.json:35`](../../shared/contracts/operating-valuation-router-v1.json#L35)

- Architecture records model identity, correlated evidence, and the headless integration boundary.
  [`valuation-model-family-architecture.md:444`](../planning-artifacts/valuation-model-family-architecture.md#L444)

**Regression evidence**

- Normal Rust gates recompute all 27 durable reported and holdout cases.
  [`operating_valuation.rs:1217`](../../apps/windows/src-tauri/src/operating_valuation.rs#L1217)

- Android independently recomputes the same 27-name cohort and validation metrics.
  [`OperatingValuationTest.kt:317`](../../apps/android/core/src/test/kotlin/com/discountscreener/core/engine/OperatingValuationTest.kt#L317)
