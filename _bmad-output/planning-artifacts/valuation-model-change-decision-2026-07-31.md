---
title: "Technical Change Decision: Evidence-Routed Operating Valuation"
status: revised
date: "2026-07-31"
scope: major
delivery_mode: direct_single_developer
trigger: "FCFF QA outliers and validated headless forward-earnings PoCs"
evidence:
  - "../implementation-artifacts/poc-forward-owner-earnings-2026-07-31.md"
  - "../implementation-artifacts/spec-fcff-qa-outlier-correction.md"
---

# Technical Change Decision — Evidence-Routed Operating Valuation

## 1. Issue Summary

The approved FCFF correction improves annual alignment, acquisition transitions, and provisional WACC, but live/headless evidence shows a broader limitation. Trailing FCFF alone is not representative when total CapEx is dominated by an investment wave, SEC history belongs to a pre-separation entity, the current cycle has recovered after negative historical years, or a commodity beta is temporarily bond-like.

The 15 reported names expose these cases: DVN, GDDY, WYNN, SNDK, BR, BSX, AMZN, AVGO, HPE, MU, ORCL, AAPL, CPRT, CEG, and ALB. Examples include AMZN subtracting $131.8B CapEx from $139.5B OCF, ORCL subtracting $55.7B from $32.0B, and SNDK consuming three stale SEC periods while current Yahoo fundamentals show $4.64B OCF and $2.26B FCF.

Two headless experiments established the direction:

- An unconditional forward/competitive-advantage model fit the reported cohort but failed an unseen holdout (57.4% mean absolute validation error). It is rejected.
- An evidence router preserved trailing FCFF where it remained representative and selected a forward earnings-power candidate only for observable distortion states. It reached 11.8% mean absolute validation error across the 15 reported cases and 11.0% across 11 unseen operating holdout cases; every included case stayed within 25%. Price and analyst-target mutation did not change any value or refusal.

This evidence is calibration support, not permission to cap values to Street. The target remains outside every model function.

## 2. Impact Analysis

### Capability impact

The existing Valuation Change Visibility documents remain valid; they describe list/detail/history explanation and should not absorb valuation-engine math. Keep evidence-routed valuation as a separate bounded capability rather than redefining Android change visibility.

Bounded capability: **Evidence-routed operating valuation**

1. Freeze forward forecast, source continuity, and router contracts.
2. Implement a pure `ForwardEarningsPower` candidate and deterministic evidence router.
3. Integrate Windows provider/runtime after engine and holdout gates pass.
4. Port exact policy to Android core/provider.
5. Add model-aware UI diagnostics and correlated-evidence handling.

### Implementation-document impact

The current `spec-fcff-qa-outlier-correction.md` remains an FCFF hardening document. It must not silently grow into the new model family. A separate implementation spec is required after this decision and its architecture amendment are approved.

### PRD conflicts

The current PRD names Yahoo weighted analyst fair value as the primary valuation signal for change visibility. That is still valid for target-change history, but it does not define how an operating intrinsic is selected or how correlated Yahoo operational forecasts affect evidence independence.

Required additions:

- model sources remain independently labeled;
- forward EPS/revenue consensus is an operational forecast input, not a target price input;
- a model consuming Yahoo forward consensus belongs to the same correlated analyst evidence family for confidence counting;
- model unavailability and route selection expose reasons and timestamps.

### Architecture conflicts

`AD-VM-002` currently maps every `OperatingNonFinancial` directly to `FcffWacc`. The PoC supports adding a second operating candidate, but not replacing FCFF universally.

`AD-VM-004` requires reinvestment consistency. The PoC's EPS capitalization is therefore an **earnings-power model**, not FCFF and not a cash-flow identity. Production must label it separately and keep quality soft unless the forecast/reinvestment bridge is independently supported.

`AD-VM-007` must add model-specific forward forecast/provenance fields. `AD-VM-009` must prevent correlated Yahoo target and Yahoo forward consensus from counting as independent evidence families.

### UX impact

The Detail slot currently says FCFF DCF or residual income. A third model requires distinct copy:

- `Forward earnings value` / `Valor por ganancias forward`
- cost of equity, not WACC
- forecast period, analyst count/range, source freshness, router reason, and rejected-model reason
- `(i)` diagnostic tooltip for unavailable/refused states with structured provider/model details useful for code localization

The header remains concise. Extended diagnostics belong in the existing detail/Quant Lens diagnostic area, not a dense header.

### Technical impact

- Yahoo `earningsTrend` becomes a demand-driven provider input with an explicit as-of date and completeness rules.
- A source-continuity gate compares SEC fiscal end, current entity/fundamentals evidence, and policy as-of dynamically; no hardcoded 2025 boundary.
- Industry/sector beta evidence replaces PoC risk-rate floors.
- Windows and Android pure engines interpret one shared fixed-point contract.
- Cache keys include forward source fingerprint, router policy, model id, and market-parameter as-of.
- Quant Lens groups correlated analyst-derived inputs and cannot crown Strong from Yahoo target plus Yahoo forward earnings alone.

## 3. Recommended Approach

### Selected path: Direct adjustment as a bounded capability

Do not roll back policy/12: its FCFF alignment and acquisition fixes remain valid evidence. Do not ship the unconditional competitive-advantage variant. Add a second operating candidate behind a deterministic router and retain both candidates in provenance.

Delivery order:

1. Record the contract and architecture amendments.
2. Pure engine and router with TDD; no UI/runtime wiring.
3. Independent reported/holdout and mutation gates.
4. Windows demand-provider integration and cache invalidation.
5. Android exact parity.
6. UI model label and diagnostic tooltip only after numeric/runtime gates pass.

Risk: **High**, because this changes model selection, provider inputs, cache identity, confidence semantics, and two platform engines. This controls verification depth, not project-management ceremony.

### Rejected options

- **Rollback FCFF hardening:** does not solve investment-cycle/source-continuity failures and discards valid fixes.
- **Replace FCFF universally with forward EPS:** failed the unseen holdout and violates model identity.
- **Blend toward target or price:** forbidden calibration leak.
- **Show the forward result as DCF:** mislabels an earnings-power estimate and hides reinvestment limitations.

## 4. Detailed Change Proposals

### Architecture — AD-VM-002

**OLD**

`OperatingNonFinancial → FcffWacc`.

**NEW**

`OperatingNonFinancial` produces eligible model candidates. `FcffWacc` remains the audited cash candidate. `ForwardEarningsPower` is eligible only with complete, current operational consensus and a structural distortion reason. `OperatingModelRouter` selects one visible primary candidate or returns a disputed/unavailable state; it never reads market price or target price.

**Rationale:** preserve correct FCFF cases while covering observable investment-cycle and source-discontinuity failures.

### Architecture — AD-VM-004

**OLD**

Operating firms keep FCFF with recent growth fade and reinvestment consistency.

**NEW**

Keep the FCFF rule unchanged. Add a separate earnings-power model using forward EPS, forecast growth/fade, cost of equity, explicit competitive-advantage duration, and forecast-quality provenance. It is not called FCFF/FCFE unless a cash/reinvestment bridge is actually implemented.

**Rationale:** avoid presenting accounting earnings capitalization as a cash-flow identity.

### Architecture — AD-VM-007

**OLD**

`model: FcffWacc | ResidualIncomeEquity | None`.

**NEW**

Add `ForwardEarningsPower` plus `model_candidates`, `selection_reason_codes`, `rejected_candidate_reasons`, `forward_forecast`, `forecast_source_fingerprint`, and `evidence_family`.

**Rationale:** make selection and correlated inputs auditable.

### Architecture — AD-VM-009 / Quant Lens

**OLD**

Model and analyst range may count as independent evidence families.

**NEW**

A model whose critical drivers come from Yahoo analyst operational consensus is tagged `AnalystDerivedModel`. Yahoo target and that model remain visible separately but count as one correlated family for strength/confidence. Disagreement remains visible and cannot be averaged away.

**Rationale:** prevent circular confidence and analyst double-counting.

### PRD

**OLD**

Valuation source is Yahoo weighted analyst fair value for change visibility; model provenance is outside the feature requirements.

**NEW**

Add requirements for independent model labels, correlated evidence-family metadata, deterministic route reasons, forward forecast freshness/coverage, and diagnostic refusal details. Analyst targets remain the change-history source and validation anchor, never a model target.

### UX

**OLD**

Ready Detail slot is FCFF DCF or residual income; unavailable copy is short and generic.

**NEW**

Add a distinct forward-earnings label and an `(i)` diagnostic affordance. Tooltip content includes business class, selected/rejected model, provider component, reason code, requested/available periods, latest fiscal end, forecast end date, analyst count/range, source age, policy versions, and a short owning-code locator. It never exposes secrets or raw oversized payloads.

### Executable work sequence

1. **Contract and fixtures:** shared fixed-point router/forecast/evidence-family contract; reported 15 plus independent holdout; target/price mutation invariance.
2. **Provider boundary:** demand-driven Yahoo forward forecast, freshness/coverage, source continuity, cache fingerprint.
3. **Pure model/router:** Windows Rust first with TDD, then Android exact contract parity.
4. **Runtime integration:** stale-cache invalidation, model candidate persistence, Quant Lens correlated-family behavior.
5. **UX diagnostics:** model-aware Detail label and `(i)` tooltip after runtime numeric gates pass.

## 5. Direct Implementation Contract

### Responsibilities

- **Juan:** approves material product/model decisions when a choice cannot be derived from existing rules and evidence.
- **Codex:** owns the architecture note, decision-complete spec, shared contracts, Rust/Kotlin engines, provider boundary, cache changes, UI integration, and verification.
- **Verification:** target/price mutation invariance, cross-platform exactness, reported cohort, independent holdout, financial fail-closed behavior, stale-cache clearing, and tooltip diagnostics are engineering gates, not a separate role or phase.

### Success criteria

- Price and analyst target are absent from model/router inputs; mutation leaves values and refusals unchanged.
- Financial services never enter the operating router.
- Forward inputs with missing/old/sparse evidence do not silently displace usable FCFF.
- Router reasons and rejected candidates are deterministic and versioned.
- Windows and Android match exact fixed-point contract outputs.
- Reported and holdout validation metrics are published, but no runtime or acceptance cap forces proximity.
- Quant Lens does not double-count correlated Yahoo target and Yahoo-derived operational forecasts.
- UI labels the actual model and provides actionable diagnostics for unavailable states.

## Checklist Status

- [x] Trigger, problem type, and concrete evidence documented.
- [x] Existing artifacts assessed; a bounded valuation capability is required without invalidating change-visibility documents.
- [x] PRD, architecture, UX, contracts, tests, caches, and observability impacts identified.
- [x] Direct adjustment, rollback, and MVP alternatives evaluated.
- [x] Direct single-developer adjustment recommended.
- [x] Detailed old/new artifact proposals and direct implementation responsibilities defined.
- [x] Delivery-process correction accepted: durable BMAD documents plus direct single-developer execution; no sprint machinery.
- [ ] Technical model-family decision remains subject to Juan's review while its architecture/spec artifacts are made decision-complete.
- [N/A] Sprint planning, backlog management, velocity, estimates, and role handoffs are intentionally excluded.
