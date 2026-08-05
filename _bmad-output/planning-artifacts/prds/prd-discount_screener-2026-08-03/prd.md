---
title: Valuation Quant Core
status: draft
created: 2026-08-03
updated: 2026-08-03
---

# PRD: Valuation Quant Core
*Working title — confirm.*

## 0. Document Purpose

This PRD specifies a replacement valuation engine for `discount_screener`: a **pure functional core** that turns typed evidence into a valuation posterior, and the **contract its imperative shell must honour**. It is written for Juan as sole product owner and for the implementation agent that will build it. Vocabulary is fixed in §3 Glossary and used verbatim throughout; features are grouped in §4 with globally-numbered FRs nested beneath them; inferences I made without confirmation carry inline `[ASSUMPTION]` tags and are indexed in §11.

It builds on, and does not duplicate, three existing artifacts:

- `_bmad-output/implementation-artifacts/quant-method-mathematical-specification-2026-08-03.md` — the current engine's math in closed form, its 62 hand-fitted constants, and the mathematical basis for the redesign. **§4 assumes that document; the math is not restated here except where it is a requirement.**
- `_bmad-output/implementation-artifacts/valuation-agent-failure-modes.md` — 19 recorded ways this work has previously gone wrong. §5 Guardrails encodes the ones that are structural.
- `_bmad-output/project-context.md` — durable repository rules that this PRD must not contradict.

UI rendering, Android parity, and module/crate layout are **out of scope** and land downstream; technical-how and rejected alternatives live in `addendum.md` beside this file.

---

## 1. Vision

The screener's valuation engine currently disagrees with the market by a median of 44.5%, refuses to publish on 9 of 26 names, and is wrong in a direction anyone can compute from two observable characteristics: it is too high on levered companies and too low on growing ones. That is not a model with an opinion. That is a broken lever.

The cause is structural, not a data problem. The engine runs **two estimators that cannot agree by construction** — one that observes only trailing SEC history and one that observes only forward consensus — and then suppresses the answer when they diverge. Underneath them sit 62 numeric constants: an equity premium of 450 bps, a perpetual growth rate of 300 bps, a 20% ceiling on how fast any company may grow, an eight-branch decision tree over sector strings, and a three-item hardcoded list of which industries count as cyclical. Every one was defensible the day it was added. Jointly they are 62 free parameters fitted by hand to a cohort that no longer exists, and they will be more wrong every year without anyone touching the code.

The Valuation Quant Core replaces both. **One estimator, two evidence channels, zero hand-fitted constants.** Growth is a latent state estimated from trailing history and forward consensus weighted by their own measured dispersion. Mean reversion runs at a speed measured from each company's own growth autocorrelation, so cyclicality is something a company's history demonstrates rather than something an industry string asserts. Cost of capital responds to capital structure because the cost of debt comes from a credit spread rather than a trailing accounting coupon. Where a prior is genuinely needed, it is estimated from the current cross-section at run time — so it re-derives itself every run instead of aging in a source file.

The core is a **total, deterministic, pure function**. It performs no I/O, reads no clock, and touches no network. Everything it knows arrives as typed evidence carrying its own uncertainty and provenance. Everything it returns is a distribution, not a point — because "we do not know" is an answer this engine should be able to give with a number attached rather than by publishing nothing.

Its behaviour is specified by executable example: Gherkin scenario outlines over enforced data tables, where adding a case to an existing table is the norm and writing a new scenario is the exception that has to be justified.

---

## 2. Target User

### 2.1 Jobs To Be Done

- **Functional.** Find companies trading materially below what their cash flows are worth, and be able to trust the number enough to act on it.
- **Functional.** Understand *why* a given valuation came out where it did — which lever moved it, on what evidence — without reading Rust.
- **Functional.** Distinguish "this company is genuinely cheap" from "the model broke on this company."
- **Epistemic.** Know how confident the model is. A wide interval on a hard name is useful; a confident-looking point estimate on the same name is dangerous.
- **Contextual.** Operate as a single user on a workstation, against free/rate-limited public data sources, with no team to review outputs.
- **Emotional.** Stop re-litigating the same defects. Every round of this work has ended in a declaration of victory over numbers that were still wrong; the goal is a model whose correctness is decided by a gate rather than by an agent's judgement.

### 2.2 Non-Users (v1)

- Anyone consuming this as a service, API, or shared tool. It is single-operator software.
- Android users — Android stays on the current engine until the follow-on port lands (§6.2).
- Anyone needing sub-second interactive revaluation. The gate run is a batch operation.

### 2.3 Key User Journeys

Single-operator tooling, so journeys are scoped light per the template's scope dial — one line each, except UJ-1 and UJ-4 which are load-bearing for §4.

- **UJ-1. Juan opens a name and reads a valuation he can argue with.** He selects a symbol in the Windows workstation, sees a fair-value **interval** rather than a single number, and beneath it the **Provenance** record: which evidence channels were available, what weight each carried and *why* that weight, the estimated reversion speed, and the cost-of-capital build-up. When the interval is wide he can see which input's dispersion made it wide. He can decide whether he believes the inputs without believing the output first.

- **UJ-2. Juan runs the Calibration Gate after a change and gets a verdict, not an opinion.** He runs the gate against the pinned universe snapshot; it reports A1–A5 pass/fail with the residual correlations, and refuses to call an unimproved run an improvement.

- **UJ-3. Juan hits a company with no analyst coverage.** The Growth Posterior falls back to the trailing channel alone, the interval widens accordingly, and the Provenance says so explicitly — rather than a constant being substituted for the missing channel.

- **UJ-4. Juan changes a behaviour and adds a row, not a file.** He wants the engine to treat negative-book-equity issuers differently. He finds the existing scenario outline covering equity-bridge cases, adds one row to its Examples table with the expected outputs, and watches it fail. Writing a whole new scenario would have required him to justify why no existing table covered it — the harness asks for that justification in the commit.

- **UJ-5. Juan refreshes the universe snapshot.** He deliberately triggers a re-fetch on a stated cadence, sees which names changed materially, and re-pins.

---

## 3. Glossary

*Downstream workflows and readers must use these terms exactly. FRs, UJs and SMs use them verbatim; a synonym anywhere in this document is a discipline violation.*

- **Valuation Quant Core** (or **Core**) — the pure functional module. Takes an Evidence Bundle, returns a Valuation Posterior. No I/O, no clock, no network, no global state. One per Issuer per run.
- **Imperative Shell** (or **Shell**) — everything outside the Core: SEC/Yahoo acquisition, caching, persistence, snapshot management, presentation. Supplies Evidence Bundles and consumes Valuation Posteriors. Never contains valuation rules.
- **Issuer** — one valued entity, identified by CIK where available and by symbol otherwise. Cardinality: one Issuer to many Observations, one Issuer to one Evidence Bundle per run.
- **Observation** — a single measured input with three parts: a value, an **Uncertainty**, and a **Provenance** record. The atomic unit of everything the Core consumes. An Observation with no value is **Absent**, which is distinct from an Observation whose value is zero.
- **Uncertainty** — the dispersion attached to an Observation, expressed as a variance on the Observation's own scale. Measured, never assigned. An Absent Observation has infinite Uncertainty by definition.
- **Provenance** — the record of where an Observation came from and what was done to it: source, fiscal period, taxonomy concept, transformations applied, and any refusal encountered. Travels with the Observation into the Valuation Posterior.
- **Evidence Bundle** — the complete typed input to the Core for one Issuer: all Observations, the Market Frame, and the Cross-Section Priors. Self-contained; the Core cannot fetch anything it lacks.
- **Market Frame** — the run-wide market inputs shared by every Issuer: risk-free term structure, Implied Equity Risk Premium, breakeven inflation, and the credit-spread curve. One per run.
- **Cross-Section Priors** — hyperparameters estimated from the Universe Snapshot during the run, used to shrink thinly-observed Issuers: pooled Growth Persistence, between-Issuer beta variance, and the credit-spread fit. Estimated, never typed in.
- **Evidence Channel** — one independent source of information about a latent quantity. Two exist for growth: the **Trailing Channel** (realized SEC revenue history) and the **Forward Channel** (analyst consensus). A channel is either present with a measured Uncertainty or Absent.
- **Growth Posterior** — the fused estimate of near-term growth: a point estimate plus a variance, produced by inverse-variance combination of the available Evidence Channels.
- **Growth Persistence** — the measured rate at which an Issuer's growth reverts toward Terminal Growth, derived from the lag-1 autocorrelation of its own realized growth series. Expressed as a half-life in years. Replaces every hardcoded horizon, fade exponent, and cyclicality flag.
- **Terminal Growth** — perpetual nominal growth, derived from the Market Frame's term structure (real yield plus breakeven inflation). One value per run, shared across Issuers.
- **Valuation Posterior** — the Core's output: a distribution over intrinsic value per share, published as percentiles, plus the Provenance of every input that shaped it, plus any Refusal.
- **Refusal** — a typed statement that the Core will not value this Issuer, with a reason. Distinct from a wide Valuation Posterior. Two kinds only: **Eligibility Refusal** (the Issuer is outside the model's closed world) and **Evidence Refusal** (a structurally required Observation is Absent). Uncertainty is never a Refusal.
- **Business Class** — the closed-world classification determining which valuation model applies: `OperatingNonFinancial`, `FinancialServices`, `NotEligible`, `Unclassified`. Unknown input fails closed to `Unclassified`.
- **Universe Snapshot** — the pinned, point-in-time Evidence Bundle set for the full S&P 500, used to estimate Cross-Section Priors and to run the Calibration Gate. Refreshed deliberately, never implicitly.
- **Calibration Gate** — the acceptance test over the Universe Snapshot. Evaluates criteria A1–A5 (§4.9) on the Residual distribution. The sole arbiter of whether a change is an improvement.
- **Residual** — `ln(Valuation Posterior median / market price)` for one Issuer. The quantity the Calibration Gate tests for structure.
- **Street** — external analyst price targets. A **diagnostic** quantity only. Never an input to the Core, never a target, never an optimand, never an acceptance criterion.
- **Scenario Outline** — a Gherkin behaviour specification parameterised over an Examples table. The unit of specification for Core behaviour.
- **Examples Table** — the enforced tabular data attached to a Scenario Outline. Every row is one **Case**, uniquely named in a mandatory `case` column.
- **Case** — one row of an Examples Table: a complete set of inputs and expected outputs for one Scenario Outline. Adding a Case is the normal way to specify new behaviour.

---

## 4. Features

### 4.1 Pure Core Boundary

**Description:** The Core is a total function from Evidence Bundle to Valuation Posterior. It cannot fetch, cannot read a clock, cannot log, and cannot fail with a side effect — every failure is a value in the returned Valuation Posterior. This is what makes the Scenario Outlines a complete specification rather than a partial one: if the Core reads nothing outside its argument, a table of inputs and expected outputs fully determines it.

The current engine violates this everywhere — `industry_beta_policy()` reads a static file at valuation time, market parameters carry an `as_of_epoch`, and refusals are `Result::Err` strings mixed with sentinel values. Realizes UJ-4.

**Functional Requirements:**

#### FR-1: Core purity

The Core computes a Valuation Posterior from an Evidence Bundle with no access to any other state.

**Consequences (testable):**
- The Core module declares no dependency permitting I/O, time, randomness, environment access, or filesystem access; a build-time dependency lint fails the build otherwise.
- Calling the Core twice with an identical Evidence Bundle returns byte-identical output, in the same process and across processes.
- The Core contains no `static`/`lazy_static` mutable state and no reads of any file, including policy tables. Policy that was previously file-resident arrives inside the Evidence Bundle.

#### FR-2: Totality

The Core returns a Valuation Posterior for every Evidence Bundle it is given, including malformed and empty ones.

**Consequences (testable):**
- The Core's signature returns a Valuation Posterior directly, not a `Result` and not an `Option`.
- Every failure path is expressed as a typed Refusal inside the returned value.
- The Core cannot panic: a scenario suite of degenerate Evidence Bundles (all-Absent, zero shares, negative equity, single-year history, infinite Uncertainty on every channel) returns Refusals rather than aborting.

#### FR-3: Numeric boundary

Internal computation uses IEEE-754 double precision; all published values are quantized to fixed-point at the Core's output boundary.

**Consequences (testable):**
- The Valuation Posterior exposes only fixed-point fields (`*_cents`, `*_bps`, `*_hundredths`, `*_millis`), consistent with the repository's fixed-point rule.
- No `f64` crosses the Core's public boundary in either direction.
- Quantization is half-up and applied once, at the boundary, not per intermediate step.

*This is the documented exception the repository's fixed-point rule requires. The Growth Persistence kernel and the inverse-variance fusion need `exp`, `ln` and real-valued division; expressing them in fixed point would introduce error larger than the precision it protects. The protection is relocated to the boundary rather than removed.*

#### FR-4: Cross-platform reproducibility

Transcendental functions produce results within a declared tolerance across platforms, and that tolerance is part of the specification.

**Consequences (testable):**
- Every Scenario Outline asserting a numeric output declares an explicit tolerance in its Then step.
- Declared tolerances are tight enough that a sign error, a unit error, or a swapped-argument error fails the Case.
- `[ASSUMPTION: 1 bp on rates and 1 cent on per-share values is a sufficient tolerance. If platform libm divergence exceeds this, a pinned implementation is adopted rather than the tolerance being widened — widening a tolerance to pass is a §5 violation.]`

#### FR-5: Shell exclusion

No valuation rule exists outside the Core.

**Consequences (testable):**
- The Shell contains no arithmetic on valuation quantities beyond serialization and display formatting.
- Business Class classification, refusal decisions, weighting, and every threshold live in the Core.
- A change to valuation behaviour that compiles without touching the Core module is a defect.

---

### 4.2 Evidence Intake Contract

**Description:** This is the Shell's obligation. Every input reaching the Core is an Observation carrying a value, a measured Uncertainty and a Provenance record. The Shell may not substitute a default for a missing value, and may not express absence as zero.

This directly targets §II.3(c) of the mathematical specification and failure mode §14: today, `acquisition_normalized` sets near-term growth to literal zero, so "we cannot measure organic growth through this merger" and "this company will never grow again" are stored in the same field. HPE loses 77% to that type error. Realizes UJ-3.

**Functional Requirements:**

#### FR-6: Observation triple

Every input the Shell supplies is an Observation carrying value, Uncertainty and Provenance.

**Consequences (testable):**
- The Evidence Bundle type makes a bare value unrepresentable — there is no constructor accepting a value without Uncertainty and Provenance.
- Provenance records source, fiscal period, taxonomy concept where applicable, and every transformation applied.

#### FR-7: Absence is not zero

An unmeasurable quantity is Absent, and Absent is a distinct inhabitant of the Observation type.

**Consequences (testable):**
- Absent and `value = 0` are distinguishable in every consumer, and the Core branches differently on them.
- An acquisition-contaminated growth transition is supplied Absent, not as zero.
- No Observation may be constructed with a Shell-chosen default value; there is no default-value constructor.

#### FR-8: Uncertainty is measured

Uncertainty is derived from the data, never assigned by the Shell.

**Consequences (testable):**
- Trailing Channel Uncertainty is the standard error of the realized growth series over its observation count.
- Forward Channel Uncertainty is derived from the provider's analyst high/low spread and analyst count.
- Absent Observations carry infinite Uncertainty, and the Core's weighting drives their weight to exactly zero.
- `[ASSUMPTION: Yahoo returns high/low/count on revenue and earnings estimates for enough of the S&P 500 to make the Forward Channel viable. This is unverified and is Open Question 1 — the highest-risk assumption in this document.]`

#### FR-9: Evidence Bundle self-containment

The Evidence Bundle contains everything the Core needs, including all policy.

**Consequences (testable):**
- Industry beta priors, credit-spread curves and every other former policy table arrive inside the Evidence Bundle.
- The Core has no fallback for a missing Evidence Bundle field: it is Absent, and Absent is handled as such.

#### FR-10: Provenance survives to output

Every Observation that materially shaped the Valuation Posterior is identifiable in it.

**Consequences (testable):**
- The Valuation Posterior names each Evidence Channel used, its weight, and the reason for that weight. Realizes UJ-1.
- A Refusal names the specific Absent Observation that caused it.

#### FR-11: Contaminated periods are Absent

Evidence the Shell cannot cleanly attribute is supplied Absent with the contamination recorded in Provenance.

**Consequences (testable):**
- A fiscal-year revenue growth transition spanning a material acquisition is Absent.
- A reporting-basis break (spin-off, discontinued-operations restatement) makes the affected transitions Absent, not zero and not pooled across the break.
- The resulting Valuation Posterior is wider, not lower — contamination increases Uncertainty rather than reducing the estimate.

---

### 4.3 Growth Posterior

**Description:** One estimator, two Evidence Channels. Near-term growth is a latent quantity measured independently by the Trailing Channel and the Forward Channel; the Core fuses them by inverse-variance weighting, which is the minimum-variance unbiased combination when the channels are independent.

This single mechanism replaces six in the current engine: the 67/33 consensus blend, the 20% growth ceiling, the 40% ceiling, the `Disputed` refusal, the `acquisition_normalized` zero, and the deviation-decay weighting function with its three constants. A wildly optimistic consensus is downweighted **automatically**, because a wild consensus has wide analyst dispersion — the 20% truncation was a crude proxy for exactly this. Realizes UJ-1, UJ-3.

**Functional Requirements:**

#### FR-12: Inverse-variance fusion

The Growth Posterior is the inverse-variance-weighted combination of the available Evidence Channels, with its variance.

**Consequences (testable):**
- With both channels present, the point estimate lies strictly between them and nearer the lower-Uncertainty channel.
- With one channel Absent, the posterior equals the present channel exactly and its variance is that channel's variance.
- With both Absent, the result is an Evidence Refusal, not a default growth rate.
- Posterior variance is strictly less than either input variance when both are present.

#### FR-13: No growth ceiling or floor

The Growth Posterior is not clamped to any constant bound.

**Consequences (testable):**
- A well-supported 26% consensus with tight dispersion produces a posterior near 26%, not 20%.
- A 40% consensus with wide dispersion produces a posterior pulled substantially toward the Trailing Channel — by weighting, not by truncation.
- No constant appears in the growth path other than the Gordon arithmetic guard of FR-27.

#### FR-14: Channel independence is asserted, not assumed silently

The fusion's independence assumption is stated in Provenance and monitored.

**Consequences (testable):**
- Provenance records both channel estimates, both Uncertainties, and both resulting weights. Realizes UJ-1.
- `[ASSUMPTION: analyst consensus and trailing SEC history are sufficiently independent for inverse-variance fusion. They are not fully independent — analysts read the same filings. This biases the posterior variance downward, i.e. makes intervals slightly too narrow, which is the conservative direction to be wrong in for a screener but must be stated.]`

#### FR-15: Disagreement is variance, never suppression

Channel disagreement widens the Valuation Posterior; it never suppresses publication.

**Consequences (testable):**
- No threshold on channel disagreement produces a Refusal.
- Two channels 3× apart produce a published posterior with a wide interval, and Provenance names the disagreement.
- The `Disputed` route status does not exist in the Core.

---

### 4.4 Growth Persistence and the Projection Kernel

**Description:** Growth decays toward Terminal Growth at a rate measured from the Issuer's own history, not from a sector string. A company **is** cyclical if its growth series mean-reverts fast, and **is** a secular compounder if it does not — which is a testable property of the data rather than a claim in a lookup table.

This retires `PROJECTION_YEARS` (5/10), `SECULAR_GROWTH_FADE_EXPONENT` (1.5), the eight-branch `derive_hold_years` tree, `derive_fade_years` (5/10), and `through_cycle_business()` — the three-string industry list that is a ticker special-case wearing a taxonomy as a disguise. Because the decay is continuous, the revenue path has a closed form and the explicit-horizon/terminal-value seam disappears entirely.

**Functional Requirements:**

#### FR-16: Persistence is measured

Growth Persistence is derived from the lag-1 autocorrelation of the Issuer's own realized growth series.

**Consequences (testable):**
- A series with high positive autocorrelation yields a long half-life; a series alternating in sign yields a short one.
- Persistence is expressed as a half-life in years and reported in Provenance. Realizes UJ-1.
- No sector, industry or symbol string participates in the calculation. A Case differing only in industry label produces an identical half-life.

#### FR-17: Thin histories shrink toward the cross-section

An Issuer with too few observations to estimate Persistence shrinks toward the pooled Cross-Section Prior.

**Consequences (testable):**
- Shrinkage weight rises with the Issuer's observation count and falls with its within-Issuer variance.
- The pooled prior is estimated from the Universe Snapshot in the same run, not read from a file or a constant.
- An Issuer with a single growth observation receives approximately the pooled prior; one with a long stable series receives approximately its own estimate.

#### FR-18: Continuous projection

Value is computed by integrating the discounted cash-flow path, with no explicit-horizon boundary.

**Consequences (testable):**
- The result is continuous in Growth Persistence — no discontinuity at any horizon value.
- Two Issuers with adjacent half-lives produce adjacent valuations; today's 5-vs-10-year switch produces a jump.
- Quadrature error is bounded below the FR-4 tolerance.

#### FR-19: No horizon constants

No projection-horizon, hold-period or fade-period constant exists in the Core.

**Consequences (testable):**
- `PROJECTION_YEARS`, `PROJECTION_YEARS_SECULAR`, `SECULAR_GROWTH_FADE_EXPONENT`, `derive_hold_years`, `derive_fade_years` and `through_cycle_business` have no analogue in the Core.
- The only surviving horizon quantity is measured Growth Persistence.

---

### 4.5 Cost of Capital

**Description:** The single largest source of error in the current engine. Across 17 selected names the discount rate spans 806–1315 bps — 509 bps of total dispersion — so a 4.5×-levered cable operator prices at 837 bps while a net-cash semiconductor prices at 925. The mechanism is that cost of debt is computed as `interest expense / total debt`, a **trailing accounting coupon**: CHTR's debt was issued cheap and has not repriced, so the model discounts it at a rate its balance sheet no longer justifies. This is the entire +254% / +252% / +162% cluster.

**Functional Requirements:**

#### FR-20: Cost of debt responds to capital structure

Cost of debt is a market-implied credit spread over the risk-free rate, derived from Issuer fundamentals.

**Consequences (testable):**
- Cost of debt is strictly monotonically increasing in leverage, holding all else equal. This is the defining test.
- Two Issuers identical but for leverage produce different costs of debt; today they do not.
- Where the Issuer has observable traded debt, the observed yield is used in preference to the fitted spread, and Provenance says which was used.
- Trailing `interest expense / total debt` is not a cost-of-debt input. It may appear in Provenance as a diagnostic.

#### FR-21: Spread curve is fitted cross-sectionally

The mapping from fundamentals to credit spread is estimated from the Universe Snapshot each run.

**Consequences (testable):**
- The fit is part of Cross-Section Priors and enters the Core through the Evidence Bundle.
- `FALLBACK_AFTER_TAX_COST_OF_DEBT_BPS` has no analogue.
- An Issuer whose fundamentals fall outside the fitted range widens its Uncertainty rather than being clipped to the range edge.

#### FR-22: Equity risk premium is implied, not assumed

The equity risk premium is solved from the index level against its own consensus cash flows.

**Consequences (testable):**
- The premium is part of the Market Frame, recomputed per run.
- `DEFAULT_ERP_BPS = 450` has no analogue.
- The premium co-moves with the risk-free rate rather than being additive to it.

#### FR-23: Beta shrinkage by measured precision

Beta shrinks toward the peer prior in proportion to its own estimation precision.

**Consequences (testable):**
- Weight on the Issuer's own beta is a function of that beta's regression standard error and the between-Issuer variance in its peer group.
- A precisely-estimated beta receives more weight than a noisy one; the 67/33 split cannot express this.
- `BETA_COMPANY_WEIGHT_PCT` / `BETA_INDUSTRY_WEIGHT_PCT` have no analogue.

#### FR-24: Market value of debt

Debt enters the discount-rate weights and the equity bridge at market value.

**Consequences (testable):**
- Book debt is used only when market value is Absent, and Provenance records the substitution and widens Uncertainty.

#### FR-25: Terminal Growth from the term structure

Terminal Growth is derived from observable real yield plus breakeven inflation.

**Consequences (testable):**
- It is a Market Frame quantity, identical for every Issuer in a run, and changes between runs as the term structure moves.
- `MACRO_STABLE_GROWTH_BPS = 300` has no analogue.

#### FR-26: Discount rate uncertainty propagates

Cost of capital carries an Uncertainty that reaches the Valuation Posterior.

**Consequences (testable):**
- An Issuer with an imprecise beta produces a wider Valuation Posterior than an otherwise identical Issuer with a precise one.

#### FR-27: Arithmetic guards are labelled as such

The few surviving numeric bounds are division guards, explicitly distinguished from economic claims.

**Consequences (testable):**
- Terminal Growth is bounded strictly below the discount rate; the guard is named as arithmetic, carries no economic justification, and appears in Provenance when it binds.
- The return-on-capital floor keeps the retention charge of FR-28 bounded and is likewise labelled arithmetic.
- No other numeric bound exists in the Core, and a lint enumerates the permitted set.

---

### 4.6 Terminal Value and the Retention Charge

**Description:** The one piece of economics in the current engine that is correct and survives intact. A business growing perpetually at `g` must retain `g / ROIC` of earnings to fund the capital that growth consumes; only the remainder reaches the owner. Capitalizing full earnings while also granting perpetual growth is free-lunch growth, and it is why the forward lane priced the cohort at a median 1.5× market with error rising monotonically as return on capital fell.

**Functional Requirements:**

#### FR-28: Growth is charged for its capital

Terminal value applies the retention charge derived from return on capital and Terminal Growth.

**Consequences (testable):**
- Two Issuers with identical earnings and growth but different returns on capital produce different terminal values, monotonically ordered.
- The charge approaches zero as return on capital approaches Terminal Growth, bounded by the FR-27 guard.

#### FR-29: An absent return on capital refuses rather than valuing at the neutral line

An Absent return on capital produces a Refusal, named `EstimatorUnavailable`, rather than being substituted with the cost of capital. Earning exactly the cost of capital is a measurement an Issuer can make; it is not the default value for an Issuer this Core failed to measure.

**Consequences (testable):**
- Absent return on capital is a Refusal (`kind() == "evidence"`, `detail() == "estimator_unavailable"`), never a value.
- An *observed* return equal to the discount rate still collapses terminal value to earnings over the discount rate — that identity is unchanged, and it remains a real measurement rather than a default.
- An *observed* low return is used as observed and is never floored at the cost of capital — flooring observed returns is what erased differentiation between sub-cost-of-capital issuers.
- The residual-income form (FR-31) refuses on the same rule: an absent return on equity refuses rather than valuing the Issuer at book.

---

### 4.7 Business Class Routing and Refusal

**Description:** A closed-world classification that fails closed. Unknown sector/industry text produces `Unclassified` and a Refusal, never a silent default to the operating model — the ACGL/CI class of defect. All four classes live in the new Core; nothing remains in the retired engine.

**Functional Requirements:**

#### FR-30: Closed-world classification

Every Issuer resolves to exactly one Business Class, with unknown input failing closed.

**Consequences (testable):**
- Unrecognized sector/industry text yields `Unclassified` and an Eligibility Refusal.
- Managed-care and health-plan issuers classify as `FinancialServices`; pharmaceutical issuers classify as `OperatingNonFinancial`. Matching on bare "health" is a defect.
- No Business Class defaults to `OperatingNonFinancial`.

#### FR-31: Financial services in the Core

`FinancialServices` Issuers are valued by residual income on book with a cost of equity, inside the Core.

**Consequences (testable):**
- The operating cash-flow model never runs for a `FinancialServices` Issuer.
- Return on equity enters as an Observation with measured Uncertainty, so a single contaminated year (COF's day-one CECL provision) widens the interval rather than collapsing value to book.
- An absent return on equity refuses (`EstimatorUnavailable`) rather than valuing the Issuer at book — the same rule FR-29 states for return on capital, applied to its residual-income form.
- `[ASSUMPTION: normalizing return on equity through a provision event is in scope for v1. This is the open COF decision; if deferred, COF remains wrong and FR-31 reduces to a port of current behaviour.]`

#### FR-32: Two refusal kinds only

Refusals are Eligibility or Evidence. Uncertainty is never a Refusal.

**Consequences (testable):**
- No Refusal is produced by any threshold on interval width, channel disagreement, or distance from market price.
- Every Refusal names a specific missing or ineligible input.
- The nine Issuers currently `Disputed` publish a Valuation Posterior under the new Core.

---

### 4.8 Publication and Provenance

**Description:** The Core returns a distribution. A wide interval is an honest answer; a refusal is the same claim with the uncertainty deleted and the estimate thrown away. Realizes UJ-1.

**Functional Requirements:**

#### FR-33: Interval publication

The Valuation Posterior is published as percentiles, not a point.

**Consequences (testable):**
- Median, 5th and 95th percentiles are published for every non-refused Issuer. `[ASSUMPTION: 5/50/95 is the right triple. 10/50/90 would be less alarming on hard names; 5/95 is the more honest bound.]`
- Percentiles are monotonically ordered by construction.
- The interval widens monotonically as any input Uncertainty rises, holding others fixed.

#### FR-34: Uncertainty is attributable

The Valuation Posterior identifies which inputs drove its width.

**Consequences (testable):**
- The variance contribution of the Growth Posterior, the discount rate and the margin is reported separately and sums to the total within the FR-4 tolerance. Realizes UJ-1.

#### FR-35: Street is absent from the Core

No analyst price target reaches the Core in any form.

**Consequences (testable):**
- The Evidence Bundle type has no field capable of carrying a price target.
- Market price enters only the Shell's Residual computation, never the Core.
- A repository scan enforcing this is part of the build, extending the existing gap-attribution enforcement scan.

#### FR-36: Shell renders, never decides

The Shell presents the Valuation Posterior without reinterpreting it.

**Consequences (testable):**
- The Shell applies no threshold to intrinsic value, and computes no valuation quantity not present in the Valuation Posterior.

---

### 4.9 Cross-Sectional Calibration and the Gate

**Description:** The part that has to be right, because "agree with the market" and "never clamp to the market" are only compatible under a specific definition of accuracy.

The criterion is **not** that Residuals are small. A value model that agrees with price everywhere is worthless — it has no opinion. The criterion is that Residuals carry **no exploitable structure**. Today they do: the three worst overvaluations are the three most levered names, and the four worst undervaluations are the four fastest growers. That is not disagreement with the market; it is a broken lever, and it is detectable without ever looking at a price target. Realizes UJ-2.

**Functional Requirements:**

#### FR-37: Residual structure is the gate

Acceptance is decided by the absence of correlation between Residuals and observable Issuer characteristics.

**Consequences (testable):**
- **A1** — correlation of Residual with leverage is statistically indistinguishable from zero.
- **A2** — correlation of Residual with forward growth is statistically indistinguishable from zero.
- **A3** — correlations with margin, log market cap and return on capital are likewise indistinguishable from zero.
- Each reports coefficient, confidence interval and n. `[ASSUMPTION: |ρ| < 0.15 on the S&P 500 snapshot is the pass bound. It is a starting value to be re-set from the first full baseline, and once set it may not be loosened to pass a change.]`

#### FR-38: Dispersion is bounded, level is not

Residual dispersion is gated; Residual level is reported and never gated.

**Consequences (testable):**
- **A4** — standard deviation of Residuals does not exceed the cross-sectional dispersion of analyst targets for the same universe.
- **A5** — median Residual is reported in every gate run and is **diagnostic only**. No gate, no threshold, no optimization target.
- A change moving median Residual toward zero while worsening A1–A3 **fails**. This is the test that distinguishes calibration from clamping.

#### FR-39: Cross-Section Priors are estimated per run

All pooled hyperparameters are re-estimated from the Universe Snapshot on every run.

**Consequences (testable):**
- Pooled Growth Persistence, between-Issuer beta variance and the credit-spread fit are outputs of the run, recorded with it.
- No pooled quantity is read from a checked-in constant or policy file.
- Re-running against an unchanged snapshot reproduces identical priors.

#### FR-40: Baseline before motion

The gate refuses to report improvement without a prior baseline on the same snapshot.

**Consequences (testable):**
- A gate run against a snapshot with no recorded baseline reports absolute values and explicitly declines to characterize direction. Realizes UJ-2.
- Comparisons across different snapshots are rejected, not silently performed.

*This exists because the failure mode it prevents has occurred repeatedly: without a fixed baseline there is no way to distinguish improvement from motion, and that is what produced several rounds of premature victory declarations.*

#### FR-41: Snapshot is pinned and versioned

The Universe Snapshot is an explicit, versioned, deliberately-refreshed artifact.

**Consequences (testable):**
- Gate runs execute offline against the pinned snapshot; no gate run performs network I/O.
- Refresh is an explicit operation reporting which Issuers moved materially. Realizes UJ-5.
- Each gate result records the snapshot version it ran against.
- `[ASSUMPTION: quarterly refresh, plus on demand after any 10-K season. Cadence is a judgement call, not derived.]`

---

### 4.10 Specification by Example

**Description:** The Core's behaviour is specified by Gherkin Scenario Outlines over enforced Examples Tables, executed by cucumber-rs. **Adding a Case to an existing table is the normal way to specify behaviour; writing a new Scenario Outline is an exception that must be justified.** The discipline exists because a table of Cases is reviewable as a whole — a reader can see the boundary between behaviours by reading down a column — whereas fifty individually-written scenarios hide their own coverage gaps. Realizes UJ-4.

**Functional Requirements:**

#### FR-42: Outlines only

Behaviour is specified by `Scenario Outline` with an `Examples` table. Bare `Scenario` blocks are rejected.

**Consequences (testable):**
- A lint fails the build on any `Scenario:` block in the Core's feature files.
- Every `Scenario Outline` has at least one `Examples` table with at least two Cases — a table with one row is a scenario in disguise.

#### FR-43: Enforced table schema

Every Examples Table conforms to a declared schema.

**Consequences (testable):**
- Every table has a `case` column whose values are unique within the table and descriptive rather than numeric.
- Every column is declared with a type and a unit; a value not parsing to its declared type fails the build.
- Absent is expressed by a reserved token distinct from zero and from empty.

#### FR-44: New outlines require justification

Creating a new Scenario Outline is an explicit act, not a default.

**Consequences (testable):**
- Each feature file carries a manifest of its Scenario Outlines with a one-line statement of the behaviour each covers.
- Adding a Scenario Outline requires adding its manifest entry stating why no existing table covers the case; the lint fails on a manifest-less outline.
- `[ASSUMPTION: a manifest entry is sufficient enforcement. A stricter option — requiring the diff to show a search of existing tables — was considered and rejected as unenforceable; see addendum.]`

#### FR-45: Outlines are the specification

Core behaviour not covered by a Case is unspecified behaviour.

**Consequences (testable):**
- Every FR in §4 maps to at least one Scenario Outline, and the mapping is machine-checked.
- Branch coverage of the Core under the scenario suite alone — with no supplementary unit tests — is reported per gate run.
- `[ASSUMPTION: the scenario suite is the primary specification, with conventional unit tests permitted only for internal helpers with no FR of their own.]`

#### FR-46: Tables are language-neutral

Examples Tables express Core behaviour without reference to any implementation language.

**Consequences (testable):**
- Steps reference Glossary terms and Core inputs/outputs only — no Rust type names, module paths or function names.
- A Kotlin implementation could be held to the same tables without editing them. This is what "parity contracted" means in §6.2.

---

## 5. Constraints and Guardrails

*These are not preferences. Each has been violated before, at documented cost, and each has a recorded failure mode.*

### 5.1 Street is a diagnostic, permanently

Analyst price targets never enter the Core (FR-35), never appear in an acceptance criterion, and are never minimized. The Calibration Gate uses **market price**, and only to test Residuals for *structure* (A1–A3), never for *level* (A5 is reported and ungated). A change that improves agreement with Street while worsening A1–A3 is a regression.

### 5.2 No ticker special-cases, and no disguised ones

No branch on a symbol. Equally: no branch on a hand-maintained list of industry strings standing in for a measurable property. `through_cycle_business()` matching three industry keys is the disguised form and is retired by FR-16. The test is: *could this branch be replaced by a measurement of the Issuer's own data?* If yes, it must be.

### 5.3 No constant without a derivation

Every numeric quantity in the Core is an observable market value, a statistic of the Issuer's own history, or a Cross-Section Prior estimated in the run. The only exceptions are arithmetic division guards, which must be labelled as such and enumerated in a lint (FR-27).

### 5.4 Never gain ground by weakening a check

No test threshold, tolerance, refusal path or gate bound may be relaxed to make a change pass. This includes widening an FR-4 tolerance, loosening the A1–A3 bound once baselined, and converting a Refusal to a published value to raise a coverage count. If a change cannot pass the existing bar, the change is wrong or the bar was wrong — and re-setting the bar is a separate, argued, logged decision, never a side effect.

### 5.5 No output clamps

Valuation noise is fixed in routing, driver definitions, parameter dynamics and evidence quality. Never with a cap on intrinsic value, a ratio to market price, or a sector haircut.

### 5.6 Refusal is not a risk-management tool

Suppressing a value because it looks wrong is not conservatism — it discards the estimate and the uncertainty together. Uncertainty is published as an interval (FR-33). Refusal is reserved for ineligibility and missing structural evidence (FR-32).

### 5.7 A constant is not part of the model until its use site is reachable

Established at cost: the ±1200 bps growth clamp cited in the 2026-08-03 cohort review sits inside a comment block and has never executed. A whole causal group and a proposed work sequence were built on a dead line. `grep` proves a string exists, not that it runs. (Failure mode §19.)

---

## 6. MVP Scope

### 6.1 In Scope

- The pure Core: all four Business Classes, Growth Posterior, Growth Persistence kernel, cost of capital, terminal value, routing, refusal, interval publication.
- The Evidence Intake Contract, and the Shell changes required to satisfy it.
- The Universe Snapshot mechanism and the Calibration Gate with A1–A5.
- The Gherkin scenario suite, the table-schema lint, and the outline manifest.
- Retirement of the current operating and financial valuation paths on Windows once the gate passes.

### 6.2 Out of Scope for MVP

- **Android parity.** The Core's contract and Examples Tables are written language-neutrally (FR-46) so Kotlin can be held to them, but no Kotlin work happens in v1. Android stays on the current engine. `[NOTE FOR PM: this means the two platforms report materially different valuations for a period. Acceptable for single-operator use; revisit before any second user.]`
- **UI rendering of intervals.** The Valuation Posterior carries percentiles and variance attribution; how the workstation draws them is a downstream UX concern.
- **Desktop (`apps/desktop`) valuation.** Already deferred; stays deferred.
- **Ranking integration.** `RANKING_INCLUDES_QUANT_ENGINE` stays false. Valuation quality must be trustworthy before it moves scores; the gate is the arbiter of when that conversation opens.
- **Live gate runs.** Gate runs offline against the pinned snapshot (FR-41).
- **Multi-currency.** USD issuers only, consistent with current behaviour.
- **The AMZN owner-earnings item** in the policy/16 backlog — untouched, per standing instruction.

---

## 7. Cross-Cutting NFRs

- **Determinism.** Identical Evidence Bundle ⇒ identical Valuation Posterior, across processes and machines within the FR-4 tolerance.
- **Auditability.** Every published number is traceable to the Observations that produced it without re-running the Core.
- **Gate runtime.** A full-universe gate run completes fast enough to run on every valuation-affecting change. `[ASSUMPTION: under 5 minutes on the pinned snapshot, offline. If the OU quadrature makes this infeasible, the constraint is runtime, not accuracy — reduce quadrature resolution within the FR-4 tolerance.]`
- **Provider courtesy.** Snapshot refresh respects rate limits and backs off rather than retry-looping. No exploratory full-universe fetches. (Failure mode §17.)
- **No silent degradation.** Any substitution — book debt for market debt, pooled prior for own estimate — appears in Provenance and widens Uncertainty.

---

## 8. Success Metrics

**Primary**

- **SM-1: Residual structure eliminated.** A1–A3 correlations statistically indistinguishable from zero on the pinned S&P 500 snapshot. Validates FR-20, FR-12, FR-16, FR-37. *This is the definition of done.*
- **SM-2: Publication coverage without suppression.** Every Issuer in the snapshot receives either a Valuation Posterior or a Refusal naming a specific missing input. Zero refusals attributable to uncertainty or disagreement. Validates FR-32, FR-15.
- **SM-3: Constants retired.** Zero numeric constants in the Core outside the enumerated arithmetic-guard set. Validates FR-19, FR-27, §5.3. Measured by lint, not by inspection.

**Secondary**

- **SM-4: Interval honesty.** Realized market price falls inside the published 5–95 interval for approximately 90% of the snapshot. Materially above indicates intervals too wide to be useful; materially below indicates false precision. Validates FR-33.
- **SM-5: Specification completeness.** Every FR maps to at least one Scenario Outline; Core branch coverage under the scenario suite alone is reported and trends up. Validates FR-45.
- **SM-6: Table discipline holds.** Ratio of Cases added to Scenario Outlines added stays high over time. Validates FR-42, FR-44.

**Counter-metrics (do not optimize)**

- **SM-C1: Median Residual (A5).** Reported every run, never targeted. A model that agrees with market price everywhere has no opinion and no value as a screener. Counterbalances SM-1 — the whole point is that SM-1 can pass while the model sits systematically below market, and that is a legitimate outcome.
- **SM-C2: Interval width.** Narrowing intervals is not an objective. A wide interval on a genuinely hard Issuer is correct output. Counterbalances SM-4.
- **SM-C3: Agreement with Street.** Explicitly not a metric, listed here so it is never mistaken for one. Counterbalances any pressure arising from SM-1.

---

## 9. Non-Goals (Explicit)

- **Not a market-timing or price-prediction model.** It estimates intrinsic value from fundamentals. Divergence from price is the output, not the error.
- **Not a replacement for judgement.** It produces an interval and its provenance so a human can disagree with the inputs.
- **Not a general-purpose valuation library.** Single operator, single workstation, USD equities, no API.
- **Not a real-time system.** Valuation is demand-driven and batch-tolerant.
- **Not a machine-learning model.** Cross-sectional estimation is used to set priors and fit a spread curve, not to learn a price mapping. A model fitted to reproduce prices would fail SM-C1 by construction.

---

## 10. Open Questions

1. **Does Yahoo actually return analyst high/low/count on revenue and earnings estimates for enough of the S&P 500?** The entire Forward Channel Uncertainty mechanism (FR-8) rests on this. Highest-risk item in the document. Must be answered by live probe against ≥30 names before FR-12 is designed in detail. If dispersion is unavailable, a fallback must be designed and it will be worse.
2. **Is a credit-spread curve fittable from data already in hand?** FR-21 requires a mapping from fundamentals to spread. Fitting it needs observed spreads for a training set. Whether those are obtainable from current sources, or need a new one, is unresolved.
3. **What is the observable proxy for market value of debt (FR-24)?** For most issuers no traded quote is accessible. A duration-and-coupon approximation may be the practical answer, and its error should be understood before it is adopted.
4. **How should the implied ERP solve (FR-22) be anchored?** It needs index-level consensus cash flows. Source and update cadence unresolved.
5. **Does the COF return-on-equity normalization belong in v1?** FR-31's assumption. This is an economic decision — how to treat a day-one provision — not a code fact.
6. **What is the A1–A3 pass bound?** FR-37 proposes |ρ| < 0.15 as a starting value. It should be re-set from the first full baseline. Setting it before seeing the baseline risks setting it where the current engine happens to sit.
7. **Does the Growth Persistence estimator have enough data?** Lag-1 autocorrelation on 5–10 annual observations is a noisy statistic. FR-17's shrinkage is the mitigation, but whether the resulting estimate carries usable signal needs measurement before FR-16 is built.
8. **Retirement sequencing.** "Old engine retired" is the decided end state; whether the two coexist behind a flag during construction, or the old one is deleted at cutover, is unresolved and affects how the gate baseline is captured.

---

## 11. Assumptions Index

*Every `[ASSUMPTION]` in this document, surfaced for explicit confirmation:*

- **§4.1 / FR-4** — 1 bp and 1 cent tolerances suffice for cross-platform reproducibility; if exceeded, pin the implementation rather than widen the tolerance.
- **§4.2 / FR-8** — Yahoo supplies analyst dispersion for enough of the universe to make the Forward Channel viable. **Unverified; see Open Question 1.**
- **§4.3 / FR-14** — Trailing and Forward Channels are independent enough for inverse-variance fusion. They are not fully independent; the bias makes intervals slightly too narrow.
- **§4.7 / FR-31** — Return-on-equity normalization through a provision event is in v1 scope. Open Question 5.
- **§4.8 / FR-33** — 5/50/95 is the right percentile triple.
- **§4.9 / FR-37** — |ρ| < 0.15 is the A1–A3 pass bound, to be re-set from the first baseline and never loosened afterward. Open Question 6.
- **§4.9 / FR-41** — Quarterly snapshot refresh plus on-demand after 10-K season.
- **§4.10 / FR-44** — A manifest entry is sufficient enforcement for new-outline justification.
- **§4.10 / FR-45** — The scenario suite is the primary specification; conventional unit tests are permitted only for internal helpers with no FR of their own.
- **§7** — A full-universe offline gate run completes in under 5 minutes.
