# Shared Contracts

## Evidence-routed operating valuation

`operating-valuation-router-v1.json` freezes the provider-independent,
fixed-point `ForwardEarningsPower` candidate and operating-model router. Its
`executableFixtures`, `routerGoldenCases`, and `arithmeticGoldenCases` are exact
arithmetic/parity goldens. `executableSyntheticCases` covers refusal/fail-closed
boundaries. The reported and holdout cohorts under `validationCohorts` carry
durable normalized engine inputs and run in the normal Rust/Kotlin test gates;
their analyst anchors remain nested under `validationOnly` and are forbidden
from engine DTOs, routing, fingerprints, and runtime acceptance logic.

The contract remains provider-independent and is also the arithmetic authority
for the Windows production adapter. Provider/runtime behavior lives outside the
JSON: demand-only Yahoo normalization in `quote_summary.rs`, orchestration in
`operating_valuation_runtime.rs`, atomic lifecycle in `engine.rs`, and
selected/disputed/unavailable presentation in Detail and Quant Lens. Provider
payloads, cache state, UI strings, analyst targets, and market prices are never
added to this engine contract.

This directory holds language-neutral fixtures, golden cases, and behavior notes that both apps validate.

## Files

- `portfolio-ranking.json`:
  candidate ranking, watchlist filtering, query filtering, opportunity ranking, and symbol-detail projection
- `chart-ranges.json`:
  canonical chart-range order and display labels used in the product surface
- `dcf-source-selection.json`:
  golden resolver-state cases for selected, unavailable, disabled/absent, and uncertain DCF source decisions
- `valuation-model-family.json`:
  business-class classifier and model-selection goldens (FCFF vs residual income); forbids price-multiple hard caps as acceptance; ACGL-class regression notes
- `industry-beta-policy-v1.json`:
  versioned sector/industry levered-beta priors for CoE shrink; through-cycle commodity flags (DVN-class); unmapped default is provisional; Windows/Android exact fixed-point goldens
- `valuation-evidence-sotp.json`:
  point-in-time evidence replay, closed-world component routing, evidence-backed SOTP bridge, refusal states, and historical driver-validation goldens; Windows and Android compare fixed-point outputs and fingerprints exactly
- `source-continuity-v1.json`:
  pure SEC vs Yahoo cash continuity gate (SNDK-class); Continuous / Discontinuous / InsufficientEvidence with versioned scale thresholds; no price/target and no absolute year walls
- `persistence-semantics.md`:
  storage behavior that must stay aligned even though Rust and Kotlin use different persistence formats

## Scope

These files are intentionally behavior-focused. They are not shared runtime code, a shared engine, or an FFI boundary.

## Related agent docs

- Root [Agents.md](../Agents.md) — valuation model family and Quant Lens conventions for implementers
- [Valuation Model Family Architecture](../_bmad-output/planning-artifacts/valuation-model-family-architecture.md) — ADRs and phased delivery
- [project-context.md](../_bmad-output/project-context.md) — lean AI rules including dynamic parameters and forbidden output clamps
