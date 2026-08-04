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
- `persistence-semantics.md`:
  storage behavior that must stay aligned even though Rust and Kotlin use different persistence formats
- `sec-driver-normalization.json`:
  the XBRL driver policy — per-driver equivalence classes (`qnames`), the netted concepts filed
  under the opposite sign convention (`negatedQnames`), unit, period shape and operation. It is the
  single source both generated policy files are emitted from; hand-editing a generated file instead
  of this one is the drift the `policyFingerprint` exists to catch
- `sec-driver-normalization-fixtures.json`:
  frozen real filings that execute against that policy. `fixtures` carries investment-category cases
  and runs through the investment normalizer; `interestFixtures` carries interest cases and runs
  through the interest-expense driver, pinning the dollars the class yields per fiscal year. **Read
  by Rust only** — Kotlin's half of this contract's dual-lock runs through the generated policy
  constants, not through this corpus

## Equivalence-class rules

An equivalence class exists so a driver keeps reading when an issuer files the same line under a
different tag. Two rules bound what "the same line" may mean; they are distinct rules and a class
must satisfy both.

- **R1 — one statement's concept.** A class holds concepts from one financial statement. A
  cash-flow disclosure is not an equivalent of an income-statement accrual, however close the two
  numbers usually sit. *Example:* `InterestPaidNet` is cash interest paid, disclosed on the cash
  flow statement; it was removed from the `interestExpense` class, which measures the accrued
  income-statement charge.
- **R2 — one measurement basis.** A class holds concepts measured on one basis. A netted concept
  enters only through a **declared sign convention** that maps it onto that basis — declared on the
  concept in `negatedQnames`, never inferred from a filed value and never branched on an issuer.
  Absent a declared convention it reads **absent**, not equivalent. *Example:* LIN files
  `InterestIncomeExpenseNet` at −63/−200/−256/−255M for 2022-2025 against `InterestExpenseNonoperating`
  at +63/+200/+256/+255M — exact negations of one line under the opposite convention, so the net
  concept is declared negated. The same concept carries a lender's net interest *income*: BAC 2025
  files +60,096M, which the declared negation carries through as a negative expense rather than as
  the largest interest bill on the tape.

R2 binds every `select_one_equivalent` list, not only `interestExpense`; a class that mixes two
bases without a declared convention is a defect whether or not its numbers currently look
plausible.

## Scope

These files are intentionally behavior-focused. They are not shared runtime code, a shared engine, or an FFI boundary.

## Related agent docs

- Root [Agents.md](../Agents.md) — valuation model family and Quant Lens conventions for implementers
- [Valuation Model Family Architecture](../_bmad-output/planning-artifacts/valuation-model-family-architecture.md) — ADRs and phased delivery
- [project-context.md](../_bmad-output/project-context.md) — lean AI rules including dynamic parameters and forbidden output clamps
