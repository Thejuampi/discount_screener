---
status: implemented-phase-1-4
quant_lens: model_version_4_snr_policy
agent_docs:
  - Agents.md
  - _bmad-output/project-context.md
workflowType: architecture
project_name: discount_screener
feature: Valuation Model Family
user_name: Juan
date: 2026-07-25
related:
  - ../implementation-artifacts/dcf-source-consistency-architecture.md
  - shared/contracts/dcf-source-selection.json
inputDocuments:
  - Live ACGL detail UI (DCF $875 vs market ~$103 / analyst ~$110)
  - apps/windows/src-tauri/src/dcf_model.rs
  - apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt
  - apps/desktop/src/workstation/app_core.rs (DCF path)
---

# Valuation Model Family Architecture

## Mission

Replace the single “FCFF + static WACC” pipeline with a **model-family valuation engine** that:

1. selects a valuation model from the **economic identity of the business**, not from output aesthetics;
2. defines cash flows / value drivers **per model**, so insurance float is never capitalized as industrial free cash flow;
3. derives discount rates and long-run growth from **market parameters and firm fundamentals**, not frozen magic numbers;
4. carries **model + input provenance** into scoring, UI, persistence, and revisions;
5. remains correct across rate regimes and insurance cycles **without hard caps on intrinsic/price**.

This document is the authoritative design for the valuation math layer. It complements (does not replace) [DCF Source Consistency Architecture](../implementation-artifacts/dcf-source-consistency-architecture.md), which owns **which provider data** enters the engine. This document owns **what model runs on that data**.

## Motivating Failure (ACGL)

| Signal | Value | Reading |
| --- | --- | --- |
| Market | ~$103 | Live clearing price |
| Analyst target | ~$110 | Street equity anchor |
| Book value / share | ~$65 | Accounting equity anchor |
| App “DCF” | **~$875** | Wrong model + static CAPM |

Reconstruction (Windows `dcf_model.rs` path):

- EDGAR FCF ≈ OCF − PPE CapEx ≈ **$6.17B** (CapEx nearly zero for a P&C insurer)
- Shares ≈ **349M**
- Historical FCF CAGR ≈ **10.5%**
- WACC ≈ **5.46%** (`rf=4%` + low β × `ERP=5%`, floor `MIN_WACC=5%`)
- Terminal g ≈ **2.5%** → Gordon multiple \((1+g)/(r-g) \approx 35\times\) year-5 FCF
- Result ≈ **$874–$875 / share**

**Root cause:** treating a financial-services firm with an industrial FCFF enterprise model.  
**Non-root-cause:** “the number is large.” A `intrinsic/price < 3` gate would hide ACGL today and mis-fire on legitimately cheap cyclicals tomorrow.

## Goals

- **G1 — Model correctness by business class.** Financial services never run FCFF+WACC as the primary intrinsic.
- **G2 — Dynamic parameters.** Risk-free rate, equity risk premium, industry betas, and stable growth move with market/fundamentals policy inputs.
- **G3 — Structural identities only.** Allowed constraints are mathematical or economic identities (e.g. \(g_{stable} < r\)), not product hard caps on output multiples.
- **G4 — Transparent provenance.** Every intrinsic carries model id, business class, discount-rate construction, driver series, and reason codes.
- **G5 — Cross-platform pure core.** Same rules on Android `core`, Windows Tauri engine, and desktop workstation; shells only supply inputs and render results.
- **G6 — Scoring honesty.** Opportunity / forecast scores consume model-aware intrinsics and must not treat `NotEligible` as zero value or silent bullish gap.

## Non-Goals

- Full actuarial capital models (RBC/BSCR) for insurers in v1.
- Bank regulatory CET1 / RWA modeling in v1.
- User-editable CAPM assumptions in v1 UI.
- Replacing analyst targets; they remain a **parallel anchor**, not a DCF substitute.
- Hard output clamps such as “reject if DCF/price > N.”
- One-off insurance hacks (`FCF × 0.3`, sector haircuts).

## Constraints and Existing Foundations

- Windows: `dcf_model.rs` multi-scenario FCFF + WACC provenance; EDGAR OCF/CapEx FCF; sector/industry on fundamentals; ROE used in scoring; price-to-book parsed in quote summary but **book equity not first-class for valuation**.
- Android: `DcfAnalysisEngine` is the same FCFF math family with slightly richer interest/tax timeseries.
- Desktop: same FCFF path in `workstation/app_core.rs`.
- Source selection / provider policy: planned in DCF source-consistency architecture; this design assumes a **source-resolved financial snapshot + timeseries** boundary.
- Product is a **screener**, not a sell-side full model: prefer robust multi-stage closed forms over fragile 30-line item forecasts.

## Decision Priority

**Critical**

1. Business-class → model routing
2. Financial-services residual income (equity) as primary model
3. Dynamic discount-rate engine (no eternal `rf=4%` / `ERP=5%` as sole truth)
4. Growth fade + reinvestment consistency for operating FCFF
5. Result type + provenance + scoring/UI consumption

**Important**

6. Classifier rules for sector/industry (versioned policy)
7. Beta shrinkage to industry
8. Required new fundamentals fields (book equity, payout/retention when available)
9. Engine version fingerprint for cache/revision invalidation
10. Golden fixtures (ACGL, a bank, a classic industrial, an ETF)

**Deferred**

- REIT / pass-through specialized model
- Explicit dividend discount as alternate financial model when residual income inputs missing
- Live implied ERP estimation from full market (may start with versioned published ERP table + refresh policy)
- Cross-provider residual-income driver disagreement policy (extends source-consistency later)

---

## Core Architectural Decisions

### AD-VM-001: Valuation Is a Model Family, Not a Single DCF Function

**Decision:** Introduce an explicit domain pipeline:

```text
BusinessClassClassifier
  → ValuationModelSelector
    → DiscountRateEngine        (WACC | CostOfEquity)
    → FirmDriversEngine         (FCFF series | Book + ROE path | …)
    → GrowthPolicy              (near-term → stable fade)
    → ScenarioEngine            (bear / base / bull)
    → ValuationResult + Provenance
```

**Rationale:** ACGL failed because one function assumed industrial economics for every ticker. Routing is the durable fix; output clamps are not.

**Consequences:**

- `compute_dcf(...)` becomes a **legacy name**; public API is `value_equity(...)` / `ValuationEngine::compute`.
- UI copy must name the model (“FCFF DCF” vs “Residual income”) instead of a generic “VALOR DCF” for all symbols.
- Persistence and cache keys include `model_id` + `engine_version`.

### AD-VM-002: Business Class Is Economic Identity

**Decision:** Classify each symbol into a versioned `BusinessClass` before any intrinsic math.

| BusinessClass | Primary model (v1) | Discount rate |
| --- | --- | --- |
| `OperatingNonFinancial` | `FcffWacc` | WACC |
| `FinancialServices` | `ResidualIncomeEquity` | Cost of equity only |
| `NotEligible` | none | — |

**Classifier inputs (priority order):**

1. `industry_key` / `industry_name` (when present)
2. `sector_key` / `sector_name`
3. Asset-class overrides already in fetcher (crypto/ETF) → `NotEligible` for equity intrinsic
4. If classification confidence is low → `OperatingNonFinancial` **only if** FCFF drivers pass quality **and** sector is not financial; otherwise `NotEligible` with `BusinessClassUncertain`

**v1 financial-services matching (policy table, not hardcoded UI strings scattered in engines):**

- Sector contains / keys for: Financial Services, Financials, Insurance, Banks, …
- Industry contains: Insurance—Property & Casualty, Life Insurance, Reinsurance, Banks—Diversified, Banks—Regional, Capital Markets, Asset Management, Credit Services, Financial Conglomerates, Mortgage Finance, …

Policy lives in pure core as data (`BusinessClassPolicy v1`), unit-tested, versioned. Adding a SIC/NAICS map later is an extension, not a redesign.

**Anti-patterns rejected:**

- “If DCF/price > 3 → reclassify as financial”
- “If FCF yield > 15% → haircut FCF”

### AD-VM-003: Financial Services Use Residual Income / Excess Return on Equity

**Decision:** For `FinancialServices`, primary intrinsic is **equity residual income**, not FCFF.

**Why this model (not “disable DCF”):**

- Damodaran: debt and reinvestment are poorly defined for banks/insurers; equity models are appropriate.
- Book equity is economically meaningful under regulatory capital regimes.
- Excess ROE over cost of equity is the natural value driver; high underwriting ROE does not imply OCF is free to distribute.
- Residual income **equals** a correctly specified equity DCF under clean-surplus; it does not require inventing PPE capex.

**Base multi-stage form (screening-grade):**

\[
V_0 = B_0 + \sum_{t=1}^{T} \frac{(ROE_t - r_e)\, B_{t-1}}{(1+r_e)^t} + \frac{TV_T}{(1+r_e)^T}
\]

Book evolves with clean surplus:

\[
B_t = B_{t-1} \times (1 + ROE_t \times retention_t)
\]

**ROE path (base):**

- \(ROE_0\) from fundamentals (reported ROE), winsorized only by **data quality** (non-finite / absurd accounting errors → reject input), not by valuation preference.
- Fade \(ROE_t\) linearly (or exponential) from \(ROE_0\) toward \(ROE_{stable}\) over \(T\) years (default \(T=5\) explicit stage, configurable in policy).
- \(ROE_{stable} = r_e\) in base case for mature competitive sectors **or** sector long-run ROE median when a stable sector benchmark exists **and** is ≤ \(r_e +\) small policy premium.  
  **Default v1 for insurance/banks:** fade to \(r_e\) (no perpetual excess returns). This is an economic assumption of long-run competition, not an output cap.

**Retention / growth of book:**

- Prefer reported payout / retention when available.
- Else derive retention from clean-surplus identity using net income and change in book when timeseries exist.
- Else use sector-default retention policy (versioned), marked provisional in provenance.
- Near-term book growth \(g_t = ROE_t \times retention_t\), **not** 17-year FCF CAGR.

**Terminal value (base):**

When \(ROE_{stable} = r_e\), continuing residual income is zero and:

\[
TV_T = B_T
\]

(discounted as above). If policy allows \(ROE_{stable} > r_e\) with perpetual premium:

\[
TV_T = B_T + \frac{(ROE_{stable} - r_e)\, B_T}{r_e - g_{stable}}, \quad g_{stable} < r_e
\]

**Scenarios:**

| Scenario | ROE path | retention | \(r_e\) |
| --- | --- | --- | --- |
| Bear | faster fade / lower \(ROE_0\) haircut from recent trough | higher payout (less book growth) | +Δ cost of equity band |
| Base | policy fade to \(ROE_{stable}\) | base retention | base \(r_e\) |
| Bull | slower fade / higher near-term ROE | lower payout | −Δ cost of equity band |

Δ bands come from policy (e.g. ±50–100 bps on \(r_e\)), versioned—not ACGL-specific.

**Eligibility for ResidualIncomeEquity:**

- positive book equity (common equity attributable to shareholders)
- positive diluted shares
- usable ROE (or reconstructable NI / average equity)
- usable cost of equity inputs (see AD-VM-005)

Missing PPE CapEx or “FCF” is **irrelevant** and must not fail the model.

**Explicitly forbidden for this class in v1 primary path:**

- OCF − PPE CapEx as free cash flow
- Enterprise WACC with Yahoo `totalDebt` / `totalCash` capital structure

### AD-VM-004: Operating Non-Financials Keep FCFF, but With Economic Growth

**Decision:** `OperatingNonFinancial` continues as enterprise FCFF DCF, with structural fixes.

**Cash flow definition:**

- Prefer source-resolved annual FCFF / free cash flow series (Yahoo and/or SEC OCF − capex under source policy).
- Latest annual FCF must be positive for v1 base path (unchanged quality gate).
- Net debt = interest-bearing debt − excess cash (existing fields), subtracted after enterprise PV.

**Growth policy (replaces full-history CAGR + hard max 18%):**

1. **Near-term growth signal** \(g_{near}\): robust estimate from last 3–5 annual FCF (or FCF/share) points—e.g. log-regression slope or trimmed CAGR—not first-ever positive year to latest.
2. **Fundamental growth check:** when ROC/ROIC and reinvestment can be estimated:

   \[
   g_{fund} \approx reinvestment\_rate \times ROC
   \]

   Blend or bound \(g_{near}\) toward \(g_{fund}\) when both exist (policy weights), with provenance.
3. **Stable growth** \(g_{stable}\):

   \[
   g_{stable} = f(r_f,\ policy) \le r_f - real\_rate\_floor\_policy
   \]

   Practically: long-run nominal economy growth derived from market params (see AD-VM-005), always \(g_{stable} < WACC\).
4. **Fade:** years 1…T grow at path from \(g_{near}\) → \(g_{stable}\) (linear fade default). Terminal Gordon uses \(g_{stable}\).

**Reinvestment consistency:**

- Projecting high growth without reinvestment is a free lunch (same bug class as ACGL).
- When using a pure FCF series that is already after reinvestment, **do not grow FCF at a rate that implies ROC × reinvestment far above historical without adjusting the FCF base**.
- v1 practical rule: if only FCF history exists (no ROC), use faded growth with \(g_{near}\) from recent window and \(g_{stable}\) from macro; mark `GrowthPath=FcfHistoryFade`. If ROC+reinvestment exist, prefer `GrowthPath=FundamentalConsistency`.

**Remove as primary controls:**

- `BASE_GROWTH_MAX_BPS = 1800` as the main safety
- Fixed terminal 2.00/2.50/3.00% independent of rate regime

Retain only **Gordon identity**: if \(g_{stable} \ge WACC\), reject scenario or pull \(g_{stable}\) to \(WACC - \epsilon\) **as math repair with reason code**, not as a valuation opinion.

### AD-VM-005: Discount Rates Are Market- and Firm-Derived

**Decision:** Introduce `MarketParams` + `FirmRiskParams` resolved outside frozen constants.

#### Cost of equity

\[
r_e = r_f + \beta_{shrunk} \times ERP
\]

| Input | Source policy (v1) |
| --- | --- |
| \(r_f\) | Nominal long bond proxy: configurable market series (e.g. US 10Y). Shell supplies latest observation; core does not hardcode 4%. Fallback: last known cached params with `MarketParamsStale` provenance—not silent 400 bps. |
| \(ERP\) | Versioned published ERP (Damodaran-style table or internal policy file) with `as_of` date, refreshable. Not eternal 500 bps. |
| \(\beta_{raw}\) | Company beta from fundamentals when present. |
| \(\beta_{ind}\) | Sector/industry median beta from current universe or static industry table in policy. |
| \(\beta_{shrunk}\) | \(w \beta_{raw} + (1-w)\beta_{ind}\) (Blume/Bayes style). Default \(w\) policy e.g. 0.67/0.33 or pure industry when company beta missing. |

**No `MIN_WACC` / `MAX_WACC` as valuation truth.** Optional **display** clamps are forbidden for stored intrinsics. Extreme rates remain possible if inputs say so; confidence/provenance flags them.

#### WACC (operating firms only)

\[
WACC = E/V \cdot r_e + D/V \cdot k_d (1 - t)
\]

- \(E\) = equity market cap (reported or price × shares, with provenance already designed in source-consistency).
- \(D\) = net financial debt \(\max(debt - cash, 0)\) for weight base (existing approach), with debt/cash provenance.
- \(k_d\) = interest / debt when available, else policy default **only with provisional flag**.
- \(t\) = tax rate from timeseries/fundamentals when available, else provisional default.

Financial services **do not compute WACC for primary valuation**.

#### Stable growth link to rates

\[
g_{stable}^{default} = \min(g_{macro}, r_f - \delta)
\]

with \(\delta\) a small real-rate buffer from policy (e.g. 50–100 bps), ensuring Gordon headroom without inventing “DCF must be near price.”

### AD-VM-006: Structural Constraints Only — Hard Output Caps Forbidden

**Allowed**

| Constraint | Type |
| --- | --- |
| \(g_{stable} < r\) | Gordon identity |
| FinancialServices ↛ FCFF primary | Cash-flow definition |
| Terminal ROE → competitive long-run | Economic assumption in model policy |
| Missing book/ROE → `NotEligible` / `Unavailable` | Input completeness |
| Non-finite / non-positive book → reject input | Data validation |
| Beta missing → industry shrink | Estimation |

**Forbidden**

| Constraint | Why forbidden |
| --- | --- |
| `intrinsic/price ∈ [L, U]` reject | Anchors to market, kills discovery |
| `MIN_WACC = 8%` because ACGL looked wrong | Hides bad β/rf policy |
| Sector FCF haircut tables | Undiagnosed model error |
| “Insurance DCF disabled” without replacement model | Loses signal |

**Confidence is not a cap:** wide bear–bull span, provisional market params, or faded high ROE lower **trust / score weight**, never silently rewrite base intrinsic to market.

### AD-VM-007: ValuationResult Replaces Bare DCF Triple

**Decision:** Canonical result type (names illustrative; fixed-point style preserved):

```text
ValuationResult {
  symbol
  business_class: OperatingNonFinancial | FinancialServices | NotEligible | Uncertain
  model: FcffWacc | ResidualIncomeEquity | None
  status: Selected | Unavailable | NotEligible | ProviderUncertain | …

  bear_intrinsic_value_cents
  base_intrinsic_value_cents
  bull_intrinsic_value_cents

  discount_rate_bps              // WACC or r_e depending on model
  discount_rate_kind: Wacc | CostOfEquity
  base_driver_growth_bps         // near-term g or ROE path summary
  stable_growth_bps

  // model-specific drivers (optional blocks)
  fcff: { net_debt_dollars, latest_fcf_dollars, growth_path, ... }
  residual_income: {
    book_equity_dollars,
    book_value_per_share_cents,
    roe0_bps,
    roe_stable_bps,
    retention_bps,
    fade_years
  }

  market_params: { rf_bps, erp_bps, as_of, sources }
  risk_params: { beta_raw_millis, beta_shrunk_millis, beta_source }
  wacc_inputs / coe_inputs provenance (extend existing WaccInputProvenance)

  engine_version: "valuation-model-family/1"
  model_policy_version: "business-class-policy/1"
  source_fingerprint                 // from source-consistency layer
  reason_codes: [...]
}
```

**Serialization:**

- Additive evolution of stored `DcfAnalysis` / analysis cache: old payloads without `model` deserialize as `model=FcffWacc` + `engine_version=legacy` and may recompute on next live refresh.
- UI and scoring must branch on `model` + `status`.

### AD-VM-008: Pure Core Ownership, Multi-App Shells

**Decision:**

| Concern | Owner |
| --- | --- |
| Classifier, models, rates, growth, scenarios | Pure core (Android `core`; Rust modules mirrored on Windows/desktop) |
| Fetch rf, ERP file, fundamentals, EDGAR | Imperative shells / providers |
| Source selection Yahoo vs SEC | Existing/planned source coordinator |
| Labels, colors, “VALOR DCF” copy | UI only, driven by result fields |

**Parity:** formula + policy versions are shared contracts under `shared/contracts/` (golden cases for classifier + numeric fixtures).

Recommended Rust module split (Windows/desktop):

```text
valuation/
  business_class.rs
  market_params.rs
  discount_rate.rs
  growth_policy.rs
  models/
    fcff_wacc.rs
    residual_income.rs
  engine.rs          // orchestrates
  provenance.rs
```

Android: package `com.discountscreener.core.valuation` (or evolve `DcfAnalysisEngine` into a facade).

### AD-VM-009: Scoring and UI Consume Model-Aware Intrinsics

**Decision:**

- Forecast / opportunity components that use intrinsic vs price must read `ValuationResult.status` and `model`.
- `NotEligible` / `Unavailable` → **no synthetic gap**, no treating missing as zero; score path already has sparse handling—extend it.
- Detail header:
  - Operating: “FCFF DCF” + WACC line
  - Financial: “Residual income” + \(r_e\) line + BVPS / ROE summary
  - Never show a single green mega-number from the wrong model.
- Provisional market params or default retention → keep existing provisional UX language.

### AD-VM-010: Engine Versioning and Invalidation

**Decision:**

- `engine_version` bumps when math or default policy changes.
- Cache key includes: symbol, source fingerprint, engine_version, model_policy_version, market_params as_of (day granularity).
- Warm restore may show last result with `RestoredOnly` + engine version badge; live refresh recomputes under new engine.

---

### AD-VM-011: Operating Valuation May Route Between Audited Cash and Forward Earnings Power

**Decision:** `OperatingNonFinancial` may produce two separately identified
candidates: the existing `FcffWacc` cash model and a soft
`ForwardEarningsPower` equity model. The operating router may select the
forward candidate only when it receives explicit, versioned structural
distortion evidence and a complete/current normalized forecast. It retains
both candidates and exposes disagreement; it never averages the conflict away
or publishes a singular selected value while status is `Disputed`.

`ForwardEarningsPower` discounts a provider-normalized forward EPS path with
cost of equity. It is neither FCFF nor FCFE, and its Yahoo consensus evidence is
tagged `AnalystDerivedModel`. A Yahoo target and this candidate therefore remain
visually distinct but are one correlated family for confidence counting.

The pure contract is
`shared/contracts/operating-valuation-router-v1.json`: cents/bps/epoch-day
inputs, checked integer recurrence, half-up rounding after named steps, dynamic
stable growth, canonical reason ordering, and exact Rust/Kotlin equality.
Each forward candidate carries the complete typed normalized
`ForwardEarningsInput` as provenance. The router accepts it only when an exact
recomputation reproduces the candidate, preventing post-compute mutation of
quality, values, refusals, rate evidence, or forecast evidence.
Market price, analyst target/range, price multiples, ranking gaps, and Quant
Lens scores are absent from engine/router inputs. `FinancialServices`,
`NotEligible`, and `Unclassified` fail closed before operating routing.

**Windows runtime integration:** `quote_summary.rs` owns a separate demand-only
Yahoo `earningsTrend` parser and fingerprint; it is deliberately absent from
the full-universe module list. `operating_valuation_runtime.rs` normalizes the
provider row, resolves cost of equity with checked fixed-point arithmetic,
derives typed structural evidence, and calls the pure router. `ScreenerState`
retains the FCFF candidate, complete route envelope, and optional selected
value separately. The periodic EDGAR worker does not compute or publish
operating FCFF: doing so would bypass the Yahoo-aware router, race a newer
demand result, and repeatedly refetch unopened names. Detail/Quant Lens demand
orchestration is the sole operating valuation producer; the periodic worker
continues residual-income financials and insider evidence only.

Detail and Quant Lens consume `Selected`, `Disputed`, and `Unavailable`
directly. `Disputed` has no single value/upside. The forward candidate remains
soft and analyst-correlated, so Yahoo target plus Yahoo forward consensus count
as one evidence family. The Detail `(i)` trail exposes provider state, forecast
period, refusal/reason codes, policy versions, fingerprints, and stable owning
function locators (never volatile line numbers).

---

### AD-VM-012: An Absent Return on Capital Refuses Rather Than Valuing at the Neutral Line (FR-29)

**Context.** `valuation-core`'s FCFF and residual-income forms both express the
same identity: the retention charge `C(t) = E(t)(1 − g/r)` collapses to
`E_0 / w` — earnings capitalized at the discount rate, with no growth term at
all — exactly when the return on capital `r` equals the discount rate `w`.
That value is **both** value-neutral (growth adds nothing) **and**
growth-independent (the fade path stops mattering) at the same point. FR-29
originally substituted `r := w` whenever the return arrived absent, so every
issuer with no measured return was valued as though it had *measured* that
exact break-even economics.

Value-neutrality is not neutrality of *belief*. "This issuer earns exactly its
cost of capital" is a measurement claim, and a screener's job is to
distinguish issuers that make it from issuers that do not. Substituting the
discount rate for an absent return does not withhold a claim — it manufactures
one, and manufactures the specific claim that happens to make growth
arithmetically disappear rather than admitting growth could not be priced.

**Decision.** The substitution is removed from both forms
(`projection.rs::intrinsic_value`, `residual_income.rs::residual_income_value`).
An absent return on capital or return on equity now refuses, carrying a new
`AbsenceReason::EstimatorUnavailable`, rather than reusing `NotReported`.

The new variant exists rather than reusing `NotReported` for a load-bearing
reason: a `FinancialServices` issuer already refuses for an absent book value
with reason `provider_unavailable`, an honest statement about the *provider*.
Reusing `NotReported` for an absent return on capital would have made that
bank's refusal and an operating issuer's refusal indistinguishable by reason,
voiding the exhaustive population test
(`every_operating_issuer_in_the_pinned_cohort_refuses_for_an_absent_return_on_capital`,
`valuation_core_adapter.rs`) that exists specifically to prove the two are
different facts. `EstimatorUnavailable` is also a true statement distinct from
`NotReported`: the provider is not at fault and nothing is missing from the
filing — the gap is in this Core's own evidence chain.

**Consequences.** The new Core now refuses every operating issuer it is asked
about, because `valuation_core_adapter::return_on_capital` is hard-coded
absent — invested capital is not yet in the evidence the Shell assembles.
This is intended, not a regression: `valuation_core_adapter::value()` has no
production caller (F1, proved by compiling it behind `#[cfg(test)]`), so
nothing published moves. Measured on the pinned 20-name market cohort, all 20
issuers refuse post-removal; 18 do so with the new `estimator_unavailable`
reason and 2 (MH, BWMN) with a pre-existing, unrelated `not_reported` gap that
predates this decision.

**The equivalent substitution remains live in the production path, and this
run does not fix it.** Stated verbatim, per this decision's own completion
requirement: *"FR-29 removed from `valuation-core`; the equivalent
substitution remains live in the production path
(`operating_valuation.rs:223`, `terminal_payout_bps`) and is unaddressed by
this run."* A characterization test,
`the_legacy_engine_still_substitutes_the_cost_of_equity_for_an_absent_return`
(`valuation_core_measurement.rs`), pins that live substitution so a silent
change to it fails loudly rather than passing unnoticed. This is item **LD-3**
of the latent-defect register below.

**Alternatives considered.**

- *Keep the substitution.* Rejected — it is the defect this decision exists to
  remove: a fabricated measurement dressed as an absence.
- *Reuse `AbsenceReason::NotReported` for the absent return.* Rejected — see
  Decision, above; it destroys the audit trail and the bank/operating
  discrimination.
- *Propagate the input observation's own absence reason
  (`ProviderUnavailable`) through to the refusal.* Rejected —
  `intrinsic_value` and `residual_income_value` refuse with
  `EstimatorUnavailable` regardless of the input's own reason, because the
  Core's statement is about its own inability to value without a return
  estimate, which is true whatever the provider said.

**Status:** Accepted and implemented (Wave 5 of the valuation PIT & contract
run, `valuation/wave1-integration`). FR-29 keeps its PRD identifier with
inverted, retitled content, so the record reads as a changed contract rather
than a new one.

**Latent-defect register.** This decision closes no defect it did not
introduce, and it inherits the standing register of defects this run
knowingly defers. The full register — id, defect, why not now, trigger and
detector for each — lives in `docs/valuation-economic-contract.md`, not here,
because it is a *living* document whose ownership extends past this run and
keeps growing; embedding it in a point-in-time decision record would force
every future entry to edit this file. In summary: eleven items are tracked
(LD-1 through LD-11); **LD-1 and LD-8 are both closed** — LD-1 by Wave 2's
removal of the blanket `.abs()` on interest expense, LD-8 by commit `f38fe2c`
("fix(cost-of-debt): a netted interest year is not a measurement of gross
interest"), which implemented the per-field concept provenance LD-8's original
entry named as its precondition for a fix. The remaining nine — LD-2, LD-3,
LD-4, LD-5, LD-6, LD-7, LD-9, LD-10, LD-11 — are open, each with a named owner
(the valuation quant workstream) and either a mechanical detector or an
explicit human-review checkpoint.

## End-to-End Data Flow

```text
Providers (Yahoo, SEC, treasury/ERP feed)
  → SourceResolvedFinancialSnapshot + timeseries + MarketParams
  → BusinessClassClassifier (policy v1)
  → if NotEligible: ValuationResult(status=NotEligible, reasons=…)
  → else model compute:
        FinancialServices → ResidualIncomeEquity(book, ROE, retention, r_e)
        OperatingNonFinancial → FcffWacc(fcf, shares, net debt, WACC, growth fade)
  → ScenarioEngine
  → ValuationResult
  → persistence / revision / opportunity scoring / detail UI
```

Source-consistency architecture remains the gate for **which FCF series** enters FCFF. Residual income primarily needs **book, ROE, shares, payout**—often from the fundamentals snapshot path, not EDGAR FCF.

---

## New / Extended Inputs

| Field | Needed by | Notes |
| --- | --- | --- |
| Book equity ($) and/or BVPS | Residual income | From Yahoo key stats / balance sheet; SEC equity concepts as secondary |
| ROE | Residual income | Already partially present (`return_on_equity_bps`) |
| Payout / retention | Residual income book growth | Yahoo reported `payoutRatio` from `financialData` or canonical `summaryDetail`; derive retention as `1 − payout` with reported provenance |
| Sector & industry keys | Classifier | Already on Windows fundamentals |
| Industry/sector median beta | Shrinkage | Compute from universe or ship policy table |
| Risk-free observation | All models | New small market-params provider |
| ERP policy document | All models | Versioned JSON/YAML under shared or profile_data |
| FCF history | FCFF only | Existing |
| Shares, debt, cash, tax, interest | FCFF WACC | Existing |

---

## Acceptance Scenarios (Design-Level)

### ACGL (P&C insurance) — primary regression

**Given** Arch Capital classified as `FinancialServices`  
**When** valuation runs  
**Then**

- `model = ResidualIncomeEquity`
- FCFF OCF−CapEx path is **not** used for primary intrinsic
- base intrinsic is order-of-magnitude consistent with book + finite excess ROE (not ~8× market from float FCF)
- UI labels residual income / cost of equity, not generic industrial DCF
- No hard `price` multiple gate is required for the test to pass

### Large bank (e.g. JPM or peer in universe)

**Then** same model family; missing book → `Unavailable` with `MissingBookEquity`, not FCFF fallback unless explicitly policy-allowed (v1: **no silent FCFF fallback** for financials).

### Operating industrial with clean FCF (e.g. classic manufacturer)

**Then** `FcffWacc` with faded growth; changing \(r_f\) moves WACC and value without code change.

### ETF / index fund

**Then** `NotEligible` (existing asset-class direction), no fake intrinsic.

### Rate regime shift

**Given** \(r_f\) rises 200 bps in MarketParams  
**Then** all else equal, discount rates rise and terminal values fall for both models—no recompile of constants.

### Forbidden patch test

Unit tests must **not** encode `assert!(dcf/price < 3.0)` as the ACGL acceptance criterion.  
Acceptance is **model selection + driver definitions + finite excess-return economics**.

---

## Explicit Relationship to Source-Consistency Work

| Layer | Owner doc |
| --- | --- |
| Which provider’s FCF/book/ROE wins | DCF source-consistency architecture |
| Which valuation model runs | **This document** |
| How UI shows source trust | Both (source provenance + model provenance) |

Implement model family **even if** multi-provider FCF selection is incomplete: residual income unblocks financials using fundamentals snapshot fields; FCFF keeps current single-source behavior until source coordinator lands.

---

## Phased Delivery (Implementation Plan)

### Phase 0 — Contracts and fixtures (no UX promise)

- `shared/contracts/valuation-model-family.json` golden cases: classifier + numeric residual income + FCFF fade.
- Document ERP/rf injection interface.
- ACGL fixture from real sampled fundamentals (book, ROE, shares, sector)—**live sample, not invented**.

### Phase 1 — Domain engine (pure)

- `BusinessClass` + policy table + tests.
- `MarketParams` + discount rate + beta shrink.
- `ResidualIncomeEquity` complete with scenarios.
- `FcffWacc` growth fade refactor; remove reliance on MIN_WACC as truth (keep temporary compatibility only if needed for golden migration, then delete).
- `ValuationResult` type; map legacy `DcfAnalysis` fields for transition.

### Phase 2 — Windows first (where ACGL bug was observed)

- Wire classifier + residual income into analysis path.
- Ingest book equity / BVPS into fundamentals snapshot.
- Market params provider (treasury + ERP file).
- Detail UI model labels + provisional reasons.
- Opportunity/forecast scoring respects status/model.

### Phase 3 — Android core + app parity

- Port pure engine to Kotlin core (or share via contract tests if dual implementation).
- Repository/cache schema additive fields.
- Detail + list surfaces.

### Phase 4 — Desktop terminal parity + cleanup

- Same engine API.
- Delete legacy single-path assumptions and dead constants.
- Mutation tests around classifier and residual income identities.

### Phase 5 — Hardening

- Sector beta medians from live universe.
- Better retention estimation from clean-surplus timeseries.
- Optional dividend-discount fallback for financials when book missing but dividends stable (explicit secondary model, not silent).

---

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Dual implementation drift (Kotlin vs Rust) | Shared contract goldens; same engine_version string; CI contract tests |
| Mis-classification of fintech / Berkshire-like conglomerates | Industry-first rules; `Uncertain` → NotEligible rather than wrong FCFF; later multi-segment |
| High cyclical ROE still overstates near-term residual income | Mandatory fade to \(r_e\); scenarios; confidence from span |
| Stale rf/ERP | Provenance `MarketParamsStale`; scoring trust downshift—not frozen 4%/5% forever |
| Book equity accounting noise (AOCI, preferred) | Prefer common equity attributable to parent; document field choice; reason codes |
| Product expectation “one DCF number” | UX education via model labels; scoring already multi-dimensional |

---

## What We Are Explicitly Not Doing

1. Hard cap on intrinsic vs price.  
2. Haircutting insurance FCF by a constant.  
3. Disabling valuation for financials without a replacement model.  
4. Leaving `rf=400bps` / `ERP=500bps` as permanent sole market truth.  
5. Using full-history FCF CAGR from 2008 as \(g_{near}\).  
6. Treating Yahoo totalDebt WACC as meaningful for banks/insurers.

---

## Success Metrics

- **Correctness:** ACGL and a bank peer never emit FCFF-primary intrinsics when classified financial.
- **Economics:** Residual income base for a mature insurer with ROE fading to \(r_e\) sits in a **book-relative** range; not multi-bagger vs market solely from float OCF.
- **Dynamics:** Snapshot test where only \(r_f\) changes moves values monotonically as theory predicts.
- **Honesty:** Zero scoring paths that invent upside from `NotEligible` intrinsics.
- **Durability:** No acceptance test depends on a fixed max price multiple.

---

## Open Questions (to resolve before Phase 1 code freeze)

1. **ERP source of truth:** ship a quarterly-updated static policy file vs fetch Damodaran CSV vs implied ERP from index—**recommendation:** versioned policy file + manual/automated refresh job (simplest, testable).
2. **rf series:** US 10Y only for USD names in v1; multi-currency later.
3. **Conglomerates with large insurance + industrial ops:** v1 industry-first; accept known limitation.
4. **Preferred equity / minority interest** in book: exclude from common book for residual income when identifiable.
5. **Dual-write period:** how long to keep legacy `DcfAnalysis` field names for Flutter/Android JSON—**recommendation:** additive fields, one release cycle, then prefer `ValuationResult`.

---

## Recommended Immediate Next Steps

1. Accept this architecture (or annotate AD open questions).  
2. Author `shared/contracts/valuation-model-family.json` with ACGL + industrial goldens (live-sampled inputs).  
3. Story breakdown: Phase 0–2 first (Windows + pure Rust engine), then Android.  
4. Do **not** land interim hard caps while this is in flight; if a temporary product guard is demanded, prefer **hide financial FCFF** as `NotEligible` until residual income ships—not a price multiple clamp.

---

## Appendix A — Why Residual Income Matches the Product

Discount Screener ranks opportunities with incomplete data. Residual income needs **book, ROE, shares, r_e**—already close to available fundamentals—and degrades cleanly. Full P&C free-cash-flow-to-equity with required capital schedules is more accurate for a single-name deep dive but fails the screener constraint (data + complexity). Residual income is the **right abstraction level**: economically valid for financials, implementable, testable, dynamic.

## Appendix B — Mapping Old Constants → New Policy

| Old constant | Fate |
| --- | --- |
| `RISK_FREE_RATE_BPS = 400` | Replaced by `MarketParams.rf_bps` |
| `EQUITY_RISK_PREMIUM_BPS = 500` | Replaced by versioned ERP policy |
| `MIN_WACC_BPS` / `MAX_WACC_BPS` | Removed as valuation truth |
| `BASE_GROWTH_MAX_BPS = 1800` | Replaced by fade + fundamental consistency |
| Fixed terminal 200/250/300 bps | Replaced by \(g_{stable}(r_f)\) |
| `SCENARIO_GROWTH_SPREAD_BPS = 400` | Replaced by scenario parameter bands per model |
| Full-series FCF CAGR | Replaced by recent-window signal + fade |

## Appendix C — ACGL Worked Sketch (Illustrative, Not Engine Output)

Inputs (order-of-magnitude, for design intuition only):

- \(B_0/S \approx \$65\)
- \(ROE_0 \approx 20\%\) (cyclically elevated)
- \(r_e \approx r_f + \beta_{shrunk} ERP\) (with industry shrink, not 5.5% from raw micro-β alone)
- Fade ROE → \(r_e\) over 5 years, terminal \(TV = B_T\)

Value = book + PV of finite excess returns during fade.  
That structure **cannot** produce a ~$875 float-FCF enterprise mirage; any remaining premium to market is an economic claim about ROE persistence, visible in ROE path provenance—not a hidden WACC floor bug.
