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
- `valuation-policy.yaml`:
  single Android engine policy book (`valuation-policy/1`); industry path, DCF bands, coupons, ranking, dip/leftover. Industry beta stays in `industry-beta-policy-v1.json`
- `industry-beta-policy-v1.json`:
  versioned sector/industry levered-beta priors for CoE shrink; through-cycle commodity flags (DVN-class); unmapped default is provisional; Windows/Android exact fixed-point goldens
- `valuation-evidence-sotp.json`:
  point-in-time evidence replay, closed-world component routing, evidence-backed SOTP bridge, refusal states, and historical driver-validation goldens; Windows and Android compare fixed-point outputs and fingerprints exactly
- `valuation-evidence-observation-v2.json`:
  Foundation 0A envelope for analyst-method automation — partition keys, clocks, replay modes, lineage, SHA-256 canonical field order; does not replace SOTP v1 FNV rows
- `valuation-high-signal-screener-cohort-v1.json`:
  goal gate for the 26-name live screener cohort — recomputes Yahoo+SEC+live US 10Y; high-signal requires solid quality, OOM sanity vs market, correct class routing, and a Street disagreement band (diagnostic, not a clamp). Windows: `cargo test --lib valuation_high_signal::`
- `valuation-gap-attribution-v1.json`:
  Shapley policy-delta waterfall (`rates`, `horizon`, `path`, `g_terminal`); factor baselines are own-policy only (never Street reverse-engineering); Street gap is diagnostic only and must not be an acceptance/optimize target. Method diagnostic `v_naive_fcff_baseline` must discount firm FCFF at **WACC** and subtract **net debt** before per-share (not CoE-on-unlevered). CHTR owner-earnings FCFF/sh must pass external sniff (~$30–70) before EPS-vs-FCFF conclusions — see handover `handover-quant-valuation-engine-2026-08-02.md`. Windows: `cargo test --lib valuation_gap_attribution::`
- `valuation-forward-earnings-multiple-v1.json`:
  Slice 1A pure market-reference lane: `eps_cents × multiple_hundredths / 100` (half-up); `$13 × 28 = $364` transcription golden; refusals; market price/target mutation-invariant; not an intrinsic router branch
- `valuation-forward-earnings-import-v1.json`:
  Slice 1B typed JSON import document (observations V2 + FEM section); `fixture_transcription` / `manual_transcription_unverified`; unverified requires `transcription_claim`
- `source-continuity-v1.json`:
  pure SEC vs Yahoo cash continuity gate (SNDK-class); Continuous / Discontinuous / InsufficientEvidence with versioned scale thresholds; no price/target and no absolute year walls
- `market-regime-fit-v1.json`:
  cross-platform agreement for the fourth V3 scoring bucket — each case pins the policy derived from a market reading and the resulting regime fit. Expected values are the Rust output and Kotlin is the thing under test; regenerating them from Kotlin would make the Android test vacuous
- `market-universe-classification-v1.json`:
  the asset classification the regime engine's breadth pillar filters on. Breadth counts participating *stocks*, so ETFs and crypto are excluded on both platforms; this is the shared membership list Windows previously held as a Rust-only constant
- `tipranks-forecast-panel.json`:
  analyst-panel construction goldens — newest observation per eligible identity, canonical ordering, and the weighting/target units
- `valuation-decision-policy.json`:
  aligned / tension / wide-scenario classification from integer basis-point arithmetic; fixes the exact half-up rounding so the two platforms cannot drift at a threshold
- `street-implied-honesty.json`:
  dual-mode honesty. Working identity stays `Honest`. Street-implied one-knob inversions are a parallel `NonHonest` signal with implied bps, delta, and stretch. Street is the scoreboard only.
- `sec-driver-normalization.json`:
  the SEC XBRL normalization policy itself — taxonomy scope, concept precedence, investment categories, and the rejection reasons; carries the `sec-driver-normalization/11` fingerprint
- `sec-driver-normalization-fixtures.json`:
  captured `companyfacts` fixtures that exercise that policy, pinned to the same fingerprint so a policy change and its evidence cannot separate
- `valuation-fcff-qa-2026-07-31.json`:
  evidence ledger for nine user-reported QA cases under `business-class-policy/16`. Its market and analyst values are validation metadata and are forbidden from engine inputs
- `opportunity-v4.json`:
  the arithmetic unique to the `AggressiveV4` opportunity model — the agreement bonus (centre, spread, bonus, beta haircut, composite) and the sector-relative fundamentals rule, with a case for each of the four `SectorBenchmarks` fields and for the share-count term. **Android-only today**, and the one file here whose expected values are *not* a second implementation's output: they were hand-derived from the constants before the Kotlin validator ran, and regenerating any of them from Kotlin would destroy the only independence the contract has. Kotlin: `OpportunityV4ContractTest`
- `persistence-semantics.md`:
  storage behavior that must stay aligned even though Rust and Kotlin use different persistence formats
- `puml-runtime-v1.json`:
  four-layer runtime for diagram-backed models (`Model` → `PumlModel` → factory + engine). The `.puml` is the live model and holds its own functions. Load includes the Kotlin primitive lib. Architecture: `_bmad-output/planning-artifacts/puml-runtime-architecture.md`

## Scope

These files are intentionally behavior-focused. They are not shared runtime code, a shared engine, or an FFI boundary.

## Related agent docs

- Root [Agents.md](../Agents.md) — valuation model family and Quant Lens conventions for implementers
- [Valuation Model Family Architecture](../_bmad-output/planning-artifacts/valuation-model-family-architecture.md) — ADRs and phased delivery
- [project-context.md](../_bmad-output/project-context.md) — lean AI rules including dynamic parameters and forbidden output clamps
