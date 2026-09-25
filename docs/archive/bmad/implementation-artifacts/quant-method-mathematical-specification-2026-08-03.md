# The Quant Method — Mathematical Specification and Redesign

**Date:** 2026-08-03
**Status:** Part I and II describe shipped code. Part III is a proposal, not implemented.
**Scope:** `BusinessClass::OperatingNonFinancial`. Financial services (`ResidualIncomeEquity`) is Part IV.

---

## Part 0 — Notation

| Symbol | Meaning | Unit |
|---|---|---|
| $R_t$ | revenue in fiscal year $t$ | USD |
| $g_t$ | revenue growth, $R_t/R_{t-1} - 1$ | rate |
| $g_0$ | near-term growth used to start the projection | rate |
| $g_\infty$ | perpetual (terminal) growth | rate |
| $m$ | FCFF margin, $\mathrm{FCFF}/R$ | rate |
| $r_e, r_d, r$ | cost of equity, cost of debt, WACC | rate |
| $\beta$ | equity beta | — |
| $\pi$ | equity risk premium | rate |
| $\kappa$ | capital intensity, $\mathrm{CapEx}/R$ | rate |
| $\delta$ | asset renewal rate | rate |
| $N$ | explicit projection horizon | years |
| $D, C$ | total debt, total cash | USD |
| $s$ | diluted shares | count |
| $E_t$ | forecast EPS | USD |

Engine arithmetic is fixed-point in basis points ($1\text{ bps} = 10^{-4}$). This spec writes rates as decimals.

---

# Part I — What the model does today

The engine runs **two independent estimators of the same quantity** and then arbitrates between them. This structure is the origin of most of what follows.

## I.1 The FCFF lane (`dcf_model.rs::fcff_driver_wacc`)

### I.1.1 The cash flow identity

Per aligned fiscal year, from SEC XBRL:

$$\mathrm{FCFF}_t = \mathrm{OCF}_t + I_t(1 - \tau_t) - \mathrm{CapEx}_t$$

Margins are taken as ratios to revenue: $m_t = \mathrm{FCFF}_t / R_t$, $\;o_t = \mathrm{OCF}_t/R_t$, $\;\kappa_t = \mathrm{CapEx}_t/R_t$.

### I.1.2 Normalization

Let $W$ be the last $5$ fiscal years (`DRIVER_RECENT_WINDOW`), $W'$ the $5$ before it. Baseline years exclude CapEx spikes, where a spike is $\kappa_t > 1.40\,\tilde\kappa$ **and** $\kappa_t - \tilde\kappa > 500\ \text{bps}$.

$$\bar o = \mathrm{med}_{t \in W} \, o_t, \qquad \bar\kappa = \mathrm{med}_{t \in W}\, \kappa_t, \qquad \bar\iota = \mathrm{med}_{t \in W}\, \iota_t$$

Under the cyclical regime these blend with the prior window at a **fixed 60/40**:

$$\bar x = 0.6\,\mathrm{med}_{W}(x) + 0.4\,\mathrm{med}_{W'}(x)$$

### I.1.3 Growth — **the critical fact**

$$\boxed{g_0 = \mathrm{med}_{t \in W}\, g_t}$$

the median of the last five *realized* revenue growth rates. Scenario bounds are the $25^{\text{th}}$ and $75^{\text{th}}$ percentiles of the same sample.

**This lane never observes a forecast.** Not analyst consensus, not company guidance, not order backlog. Its entire forward view is an unweighted median of five backward-looking accounting ratios.

*Correction to a claim made in the 2026-08-03 cohort review:* the clamp $g_0 \in [g_\infty - 1200\,\text{bps},\, g_\infty + 1200\,\text{bps}]$ (`MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS`, `dcf_model.rs:2366`) sits inside a `/* */` block and is unreachable. The FCFF lane has **no** growth ceiling. The reason it reads low on AI-cycle names is not truncation — it is that a trailing median cannot see an acceleration.

### I.1.4 Owner earnings and the sustaining-CapEx identity

Under steady state $\kappa = c(\delta + g)$, so the share of CapEx that merely holds the asset base is

$$\kappa_{\text{maint}} = \bar\kappa \cdot \frac{\delta}{\delta + \max(g_0, 0)}, \qquad \delta = 0.10 \text{ (fixed)}$$

floored at $\min(200\,\text{bps}, \bar\kappa)$. When an investment wave is detected, the base margin switches to owner earnings $\bar m = \bar o + \bar\iota - \kappa_{\text{maint}}$, but only if that exceeds the annual-FCFF median.

### I.1.5 The projection

$N = 5$, or $10$ under `SecularExpansion` / owner-earnings base. With fade exponent $\phi \in \{1.0, 1.5\}$ and $f(t) = (t/N)^{\phi}$:

$$g(t) = g_0\,(1 - f(t)) + g_\infty f(t), \qquad m(t) = m_{\text{scenario}}(1-f(t)) + \bar m f(t)$$

$$R_t = R_{t-1}\,(1 + g(t)), \qquad
V_{\text{EV}} = \sum_{t=1}^{N} \frac{R_t\, m(t)}{(1+r)^t} + \frac{1}{(1+r)^N}\cdot\frac{R_N (1+g_\infty)\,\bar m}{r - g_\infty}$$

$$V_{\text{eq}}/s = \frac{\max(V_{\text{EV}} - (D - C),\, 0)}{s}$$

Terminal growth: $g_\infty = \max\big(\min(0.03,\; r_f - 0.01,\; r - 0.005),\; 0.005\big)$.

### I.1.6 The discount rate

$$r_e = r_f + \beta^{*}\pi, \qquad \beta^{*} = 0.67\,\beta_{\text{own}} + 0.33\,\beta_{\text{ind}}$$

$$r = r_e \frac{E}{E+D} + r_d(1-\tau)\frac{D}{E+D}, \qquad r_d = \frac{I_t}{D_t}\ \text{(trailing accounting ratio)}$$

with $\pi = 450\ \text{bps}$ fixed, $\beta_{\text{ind}}$ from a static JSON table, and $\beta^{*}$ floored at $1.0$ when $D/E > 5$.

## I.2 The forward lane (`operating_valuation.rs::project_forward_value`)

Consensus EPS $E_0$, hold $H$, fade $F$:

$$V = \sum_{t=0}^{H}\frac{E_0 (1+g_0)^t}{(1+r_e)^{t+1}} \;+\; \sum_{t=H+1}^{H+F}\frac{E_0\prod(1+g(t))}{(1+r_e)^{t+1}} \;+\; \frac{E_{H+F}}{(1+r_e)^{H+F+1}}\cdot\left(1 - \frac{g_\infty}{\mathrm{ROIC}}\right)\frac{1+g_\infty}{r_e - g_\infty}$$

with linear fade $g(t) = g_0 + (g_\infty - g_0)\frac{t-H}{F}$.

Growth input (`derive_near_growth_bps`, production path):

$$g_0 = \mathrm{clip}\Big(\tfrac{1}{2}\big(g^{\text{rev}}_{\text{cons}} + g^{\text{eps}}_{\text{cons}}\big),\; -0.02,\; \mathbf{0.20}\Big)$$

**This ceiling is live and binding.** MPWR's consensus is 25.8%/27.0%; it is truncated to 20%.

$H$ and $F$ come from hardcoded decision trees — $H$ from an eight-branch cascade on sector string, ROE and leverage thresholds; $F \in \{5, 10\}$ from `through_cycle_business()`, a string match against exactly three industry keys.

The terminal payout $1 - g_\infty/\mathrm{ROIC}$ is **correct and should survive the redesign.** It is the one place the model charges growth for the capital it consumes.

## I.3 The router (`route_operating_models`)

$$\Delta = \frac{|V_{\text{fcff}} - V_{\text{fwd}}|}{\min(V_{\text{fcff}}, V_{\text{fwd}})}, \qquad
\Delta > 0.50 \;\Rightarrow\; \texttt{Disputed} \;\Rightarrow\; \text{publish nothing}$$

---

# Part II — What is wrong, derived from the above

## II.1 The architecture is a disagreement detector, not an estimator

Lane A sees only the past. Lane B sees only the future. They are then compared, and a 50% divergence suppresses the output.

**The engine manufactures the disagreement it refuses on.** For any company whose growth rate has changed, a trailing median and a forward consensus *must* diverge. That is not model risk; it is the definition of the two inputs. On the 2026-08-02 cohort, nine names are `Disputed`, and in **9 of 9** the forward lane is closer to street. The refusal is not protecting against error — it is discarding the better estimate.

## II.2 Sixty-two constants

Forty-two named numeric constants across the six valuation modules, plus roughly twenty more as inline literals in decision trees. The load-bearing ones:

| Constant | Value | What it silently asserts |
|---|---|---|
| `DEFAULT_ERP_BPS` | 450 | the equity premium is a constant of nature |
| `MACRO_STABLE_GROWTH_BPS` | 300 | perpetual nominal growth, set in 2025, forever |
| `LEGACY_NEAR_GROWTH_CEILING_BPS` | 2000 | no firm grows faster than 20% |
| `ASSET_RENEWAL_RATE_BPS` | 1000 | every asset base on earth depreciates at 10%/yr |
| `BETA_COMPANY_WEIGHT_PCT` | 67 | own-vs-industry credibility is 2:1 for every issuer |
| `PROJECTION_YEARS` | 5 / 10 | competitive advantage lasts 5 years, or 10 if a flag is set |
| `SECULAR_GROWTH_FADE_EXPONENT` | 1.50 | growth decays on a fixed convex curve |
| `DISPUTED_DIFFERENCE_BPS` | 5000 | 50% disagreement is the boundary of knowledge |
| `derive_hold_years` | 0/3/5/7/10 | an eight-branch tree over sector strings |
| `through_cycle_business` | 3 strings | cyclicality is a property of an industry label |

The last two are the most damaging. `through_cycle_business()` matching `"oil-gas-e-p" | "oil-gas-integrated" | "specialty-chemicals"` is a ticker special-case wearing an industry taxonomy as a disguise: it is a hand-maintained list that will be wrong for the next cyclical sector and cannot learn.

Each constant was individually defensible when added. Jointly they are 62 free parameters fitted by hand to a cohort that no longer exists.

## II.3 The three defects the cohort evidence localizes

**(a) Cost of capital does not respond to capital structure.** Across 17 selected names $r$ spans 806–1315 bps — 509 bps of total dispersion, median 937. CHTR at 4.5× leverage prices at 837 bps; MPWR with net cash prices at 925 bps. Mechanically: $r_d = I_t/D_t$ is a *trailing accounting coupon*. CHTR's debt was issued cheap and has not repriced, so the model discounts a 4.5×-levered cable operator at a AA rate. Gap attribution puts \$89.81 of CHTR's \$468 error in the rates bucket. This is the whole $\{+254\%, +252\%, +162\%\}$ cluster: CHTR, T, DVN.

**(b) Growth is a five-year backward median in one lane and a 20% truncation in the other.** Neither is an estimate of $g_0$. This is the $\{-89\%, -85\%, -80\%, -70\%\}$ cluster: TER, APH, AVGO, MPWR.

**(c) Missing evidence is coded as the number zero.** Under `acquisition_normalized`, $g_0 := 0$ *forever* — a perpetuity on a company whose consensus is +31.1% then +11.3% (HPE). "We cannot measure organic growth through this merger" and "this company will never grow again" are entered into the same field. That is a type error, and it costs HPE 77%.

## II.4 The residuals have structure

Let $\varepsilon_i = \ln(V_i/P_i)$. Today $\varepsilon$ correlates strongly and *predictably*:

- positively with leverage (CHTR, T, DVN — all three of the worst overvaluations are the three most levered)
- negatively with forward growth (MPWR, AVGO, TER, APH — all four of the worst undervaluations are the four fastest growers)

A model whose errors are predictable from two observable characteristics is not noisy. It is **biased in a direction anyone can compute**, which is the strongest available evidence that the defect is structural rather than data quality.

---

# Part III — The redesign

## III.0 Organizing principle: no constant survives that is not measured

Every number in Part II.2 is replaced by one of three things:

1. **An observable market quantity** re-read each run (implied ERP, term structure, credit spreads).
2. **A statistic of the issuer's own history** (growth persistence, dispersion, depreciation rate).
3. **A cross-sectional hyperparameter estimated from the universe at run time** — empirical Bayes.

Category 3 is the general answer to *"a value that works today and breaks in two years."* Where a prior is genuinely needed, it is **estimated from the current cross-section rather than typed in**, so it re-derives itself every run. The 67/33 beta split becomes a shrinkage weight computed from the ratio of within-issuer to between-issuer variance. That number will be 67/33 when the data say so and something else when they do not, without anyone editing a file.

## III.1 One estimator, two evidence channels

Delete the two-lane architecture. Growth is a **latent state** with a posterior, and the trailing history and the forward consensus are two *noisy measurements of the same state*, combined by inverse-variance weighting:

$$\hat g_0 = \frac{\sigma^{-2}_{\text{trail}}\, g_{\text{trail}} + \sigma^{-2}_{\text{fwd}}\, g_{\text{fwd}}}{\sigma^{-2}_{\text{trail}} + \sigma^{-2}_{\text{fwd}}}, \qquad
\mathrm{Var}(\hat g_0) = \big(\sigma^{-2}_{\text{trail}} + \sigma^{-2}_{\text{fwd}}\big)^{-1}$$

Both variances are **observable, not chosen**:

$$\sigma^2_{\text{trail}} = \frac{1}{n-1}\sum_{t\in W}(g_t - \bar g)^2 \Big/ n
\qquad
\sigma^2_{\text{fwd}} = \left(\frac{g^{\text{hi}} - g^{\text{lo}}}{2\,z}\right)^2 \Big/ n_{\text{analysts}}$$

using the analyst high/low spread and count the provider already returns.

This single change subsumes six separate mechanisms:

- The **67/33 blend** disappears — the weight is now derived from dispersion.
- The **20% ceiling** disappears — a wild consensus is *automatically* downweighted because a wild consensus has wide dispersion. Truncation was a crude proxy for exactly this.
- The **`Disputed` refusal** disappears — disagreement between channels is no longer a routing decision, it is $\mathrm{Var}(\hat g_0)$, published as an interval.
- The **`acquisition_normalized` zero** disappears — a contaminated year is an observation with $\sigma^2 \to \infty$, hence weight $\to 0$. Missing evidence becomes missing, not zero. This is the type error in II.3(c) fixed structurally.
- `through_cycle_business()`'s string list disappears (see III.2).
- `derive_hold_years`' eight-branch tree disappears (see III.2).

## III.2 Growth as mean reversion, with the reversion speed measured

Replace the fixed horizon $N$, the fade exponent $\phi$, the hold tree $H$ and the fade tree $F$ with one continuous-time Ornstein–Uhlenbeck decay:

$$g(t) = g_\infty + (\hat g_0 - g_\infty)\, e^{-\kappa t}$$

$\kappa$ is **estimated from the issuer's own growth autocorrelation**. With $\rho_1$ the lag-1 autocorrelation of $\{g_t\}$ and annual spacing:

$$\rho_1 = e^{-\kappa} \;\Longrightarrow\; \kappa = -\ln \rho_1, \qquad t_{1/2} = \frac{\ln 2}{\kappa}$$

This is the "EMA curve" in the request, and it is the correct object: a company *is* cyclical if its growth series mean-reverts fast, and *is* a secular compounder if it does not. No sector string, no flag. DVN and CHTR will get short half-lives because their own history says so; MSFT will get a long one for the same reason.

Revenue has a closed form, so the projection is an integral rather than a loop:

$$R(t) = R_0 \exp\!\left[g_\infty t + \frac{\hat g_0 - g_\infty}{\kappa}\left(1 - e^{-\kappa t}\right)\right]$$

$$V_{\text{EV}} = \int_0^{\infty} R(t)\, m(t)\, e^{-rt}\,dt$$

evaluated by quadrature, with no explicit-horizon/terminal-value seam at all. The discontinuity at $t = N$ — and with it the entire `PROJECTION_YEARS` question — ceases to exist.

Short histories are handled by hierarchical shrinkage toward a pooled $\bar\rho_1$ **estimated across the universe in the same run**:

$$\hat\rho_i = \frac{n_i\,\rho_i/\sigma^2_w + \bar\rho/\sigma^2_b}{n_i/\sigma^2_w + 1/\sigma^2_b}$$

with $\sigma^2_w, \sigma^2_b$ the within- and between-issuer variance components. This is category 3: the prior exists, but nobody types it.

## III.3 Cost of capital that responds to capital structure

Three replacements, in order of impact:

**(a) Cost of debt from a credit spread, not a trailing coupon.** This is the fix for the +250% cluster.

$$r_d = r_f + s(\text{coverage},\, \text{leverage},\, \sigma_{\text{EBIT}})$$

where $s(\cdot)$ is fitted **cross-sectionally each run** against observable corporate spreads. The essential property is $\partial r_d/\partial(D/E) > 0$, which the current $I_t/D_t$ does not have. Where the issuer has traded bonds, use the observed yield directly — evidence beats a fit.

**(b) Implied ERP, not 450 bps.** Solve for the discount rate that equates the index level to its own consensus cash flows:

$$P_{\text{index}} = \sum_t \frac{\mathrm{CF}_t}{(1+r_m)^t} + \frac{\mathrm{CF}_T(1+g_\infty)}{(r_m - g_\infty)(1+r_m)^T} \;\Longrightarrow\; \pi = r_m - r_f$$

Observable, recomputed on every run, and correctly co-moves with rates.

**(c) Beta shrinkage by measured precision.** $\beta_{\text{own}}$ comes from a regression, so its standard error $\mathrm{se}(\beta)$ is already available:

$$\beta^{*} = \frac{\mathrm{se}(\beta)^{-2}\beta_{\text{own}} + \tau^{-2}\beta_{\text{ind}}}{\mathrm{se}(\beta)^{-2} + \tau^{-2}}$$

with $\tau^2$ the between-issuer beta variance in the peer group, measured. A well-estimated beta gets more weight than a noisy one — which 67/33 cannot express.

**(d)** Market value of debt in both the WACC weights and the equity bridge. Book debt in a repriced-rate environment is the wrong number in both places.

**(e)** Terminal growth $g_\infty$ from the observable term structure — nominal long yield decomposed into real yield and breakeven inflation — not the constant `300`. It then moves with the world instead of with a commit.

## III.4 Terminal value — keep what is right

$$V_T = \frac{E_T\left(1 - g_\infty/\mathrm{ROIC}\right)(1 + g_\infty)}{r - g_\infty}$$

The retention charge $1 - g_\infty/\mathrm{ROIC}$ is correct and stays, as does the separation between *missing* ROIC evidence (growth value-neutral) and the arithmetic guard $\mathrm{ROIC} > g_\infty$. This is the strongest piece of economics in the current engine.

## III.5 The acceptance criterion — how to be accurate without fitting to street

This is the part that has to be got right, because "make it agree with street" and "never clamp to street" are only compatible under a specific definition of accuracy.

Define the residual against **market price** (not analyst targets):

$$\varepsilon_i = \ln\!\left(V_i^{\text{model}} / P_i^{\text{market}}\right)$$

The acceptance criterion is **not** $|\varepsilon_i|$ small. A value model that agrees with price everywhere is worthless — it has no opinion. The criterion is that the residuals carry **no exploitable structure**:

| | Test | Today |
|---|---|---|
| **A1** | $\mathrm{corr}(\varepsilon,\, D/E) \approx 0$ | strongly positive — fails |
| **A2** | $\mathrm{corr}(\varepsilon,\, g_{\text{fwd}}) \approx 0$ | strongly negative — fails |
| **A3** | $\mathrm{corr}(\varepsilon,\, m),\ \mathrm{corr}(\varepsilon,\ln \text{cap}),\ \mathrm{corr}(\varepsilon, \mathrm{ROIC}) \approx 0$ | untested |
| **A4** | $\mathrm{sd}(\varepsilon)$ finite and comparable to analyst target dispersion | today's tail is 250% |
| **A5** | $\mathrm{med}(\varepsilon)$ reported, **diagnostic only** | −44.5% |

**A1–A3 are the gate. A5 is never a gate.** The distinction is the whole point: A1–A3 say *the model must not be predictably wrong as a function of things anyone can observe*. They can be satisfied while the model sits uniformly 30% below market — which is a legitimate output for a value screener and exactly what you would want it to be free to say. What they forbid is being 254% high on the levered names and 89% low on the growing ones, which is not disagreement with the market; it is a broken lever, and it is visible without ever looking at a price target.

This preserves the standing constraint — street stays a diagnostic, never an optimand — while giving accuracy a definition that can actually be tested and can actually fail.

## III.6 What the model publishes

An interval, not a point, propagated from the posterior variances that are now first-class:

$$\text{publish } \big(V^{\text{med}},\ V^{5\%},\ V^{95\%}\big) \text{ from } \mathrm{Var}(\hat g_0),\ \mathrm{Var}(\hat m),\ \mathrm{Var}(\hat r)$$

A wide band is an honest answer. A refusal is not — it is the same claim with the uncertainty deleted and the estimate thrown away. This converts today's nine `Disputed` blanks into nine published values with visible error bars.

---

# Part IV — Sequencing

Ordered by (measured impact) / (blast radius). Each step is independently shippable and independently testable against A1–A5.

| # | Change | Fixes | Risk |
|---|---|---|---|
| 1 | Credit-spread cost of debt (III.3a) | CHTR, T, DVN — the three worst errors | isolated to `derive_wacc` |
| 2 | Inverse-variance growth blend (III.1) | 9 `Disputed` + MPWR/AVGO/TER/APH; deletes 4 mechanisms | touches the routing contract |
| 3 | Contaminated year = missing, not zero (III.1) | HPE, APH, CRM, SW, EXE | small, well-scoped |
| 4 | OU fade with measured $\kappa$ (III.2) | deletes $N$, $\phi$, $H$, $F$, `through_cycle_business` | rewrites the projection kernel |
| 5 | Implied ERP + market-value debt + term-structure $g_\infty$ (III.3b,d,e) | level bias; removes 3 constants | global, revalues everything |
| 6 | Interval publication (III.6) | output contract | UI + Android parity |

Steps 1–3 are mechanical and testable in isolation. Step 4 is the largest rewrite and the one that retires the most hand-fitted parameters. Step 5 must come after 1–4 because it moves every name at once and would mask the others' effects.

**A1–A5 must be computed on the full universe before step 1 and after every step.** Without that baseline there is no way to tell improvement from motion — which is the failure mode that produced the last several rounds of premature victory declarations.

---

## Appendix — Constants retired by this design

| Retired | Replaced by |
|---|---|
| `DEFAULT_ERP_BPS` 450 | implied ERP solved from the index (III.3b) |
| `MACRO_STABLE_GROWTH_BPS` 300 | real yield + breakeven inflation (III.3e) |
| `LEGACY_NEAR_GROWTH_CEILING_BPS` 2000 | inverse-variance downweighting (III.1) |
| `NEAR_GROWTH_CEILING_BPS` 4000 | same |
| `CONSENSUS_WEIGHT_{ON,OFF}_TREND_BPS` 6700/3300 | measured dispersion ratio (III.1) |
| `FULL_DEVIATION_BPS` 7500 | same |
| `BETA_{COMPANY,INDUSTRY}_WEIGHT_PCT` 67/33 | $\mathrm{se}(\beta)$ shrinkage (III.3c) |
| `PROJECTION_YEARS` 5 / `_SECULAR` 10 | closed-form integral (III.2) |
| `SECULAR_GROWTH_FADE_EXPONENT` 1.50 | measured $\kappa$ (III.2) |
| `derive_hold_years` tree | same |
| `derive_fade_years` 5/10 | same |
| `through_cycle_business` string list | measured $\rho_1$ (III.2) |
| `DISPUTED_DIFFERENCE_BPS` 5000 | posterior variance (III.6) |
| `FALLBACK_AFTER_TAX_COST_OF_DEBT_BPS` 400 | cross-sectional spread fit (III.3a) |
| `blend_recent_prior` 60/40 | inverse-variance (III.1) |
| `ASSET_RENEWAL_RATE_BPS` 1000 | reported D&A / gross PP&E, per issuer |

Surviving by design: `MIN_TERMINAL_ROIC_SPREAD_BPS`, `GORDON_RATE_EPSILON_BPS`, `MIN_STABLE_GROWTH_BPS`. These are arithmetic guards on division, not economic claims, and they should stay explicitly labelled as such.
