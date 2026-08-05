# ROIC research charter

What must be established before a return-on-capital estimator can be proposed as a candidate at
all: the question, the population, the data, and the ways this kind of research goes wrong. This
charter defines what must be established; `docs/roic-preregistration.md` defines how candidates will
be judged once it is. Neither document selects, favors, or promotes any estimator — book ROIC,
`prior_only`, and any shrinkage variant remain research candidates only (brief §2).

---

## 1. The question

Can a validated estimator predict an issuer's realized incremental return on capital,
`ΔNOPAT / ΔIC` over a three-year window (`docs/roic-target-specification.md`), better than a model
that carries no return-on-capital channel at all (`prior_only`, `docs/roic-preregistration.md` §2.1)
— measured on evidence that was actually available at the point the prediction would have been made?

This is deliberately narrower than "what is the best way to estimate return on capital." It is a
comparison against a named, concrete alternative, on a target that is completely pinned before any
estimator is written, because a research programme that starts from "find a good estimator" invites
exactly the post-hoc target-shopping the pre-registration exists to prevent.

---

## 2. The target quantity

`ΔNOPAT / ΔIC` over the rolling, fiscal-year-aligned three-year window defined in full in
`docs/roic-target-specification.md`. This charter does not restate the seventeen rows; it names the
target and points at its specification, because a research charter that quietly redefines the target
its own pre-registration pins is exactly the drift `docs/roic-target-specification.md`'s standing
rule ("any subsequent change to the target or exclusions is a NEW experiment...") exists to catch.

---

## 3. The cross-section

**Which issuers.** `BusinessClass::OperatingNonFinancial` issuers with SEC CIK coverage, domestic,
consolidated, USD-reporting 10-K/10-K-A filers — the same population the SEC FCFF driver
normalization boundary already restricts every driver to (`AGENTS.md`). This is not a small
population: a prior wide scan of this run (R-10, `ORCHESTRATOR-RULINGS.md`) found 496 of 501 S&P 500
issuers reachable by CIK, so the eligible universe for this research programme is on that order of
magnitude before any per-window exclusion is applied — cited here only to size the population, not
as a result of this charter's own research.

**Which years.** Every fiscal year for which an issuer has a complete `AnnualObservation` — pretax
income, stockholders' equity, total debt, interest expense, and (filed or statutory-default)
marginal tax rate all present for the same filed year, per `docs/valuation-economic-contract.md` §1.
A year missing any one of those terms is not a partial observation to interpolate; it is absent for
that year (constraint 5).

**What makes an issuer eligible.** At minimum three usable years surviving `docs/roic-target-specification.md`'s
exclusions, because `docs/valuation-economic-contract.md` §12's `robust_centre` over an annual return
series refuses below three retained observations by construction — an issuer that cannot clear this
floor cannot supply a book-centre candidate at all, and is recorded as `InsufficientObservations`
(economic contract §9), not silently dropped from the reported population.

---

## 4. The point-in-time discipline, and why a non-PIT backtest of this quantity is worthless

Wave 1 of this run built exactly the machinery this research programme depends on:
`AnnualObservation`/`AnnualProvenance`/`AnnualSeries` retain `filed`, `end`, `fy`, `fp` and source
identity per fact, and `extract_driver_vintages` resolves a driver **as of a cutoff date**, not just
by calendar year. This exists because `AnnualValue { year, value_dollars }` alone cannot answer the
question a backtest of a *predictive* quantity actually needs answered: *"was this observation
available at cutoff `t`, from which filing, under which period interpretation?"*

**Why a non-PIT backtest is worthless, not merely weaker.** A prediction made "for" fiscal year `t`
that is silently constructed from data filed after `t` — a later restatement, a subsequent year's
10-K that revises a prior figure, or simply reading `latest()` instead of `as_of(t)` — is not
predicting anything. It is curve-fitting a known outcome and reporting the fit as foresight. Every
number such a backtest produces is contaminated by information the issuer itself did not yet
possess, filed, or in some cases even generate, at the cutoff the backtest claims to be predicting
from. This is not a matter of degree: an estimator validated this way could look arbitrarily good and
still tell you nothing about whether it would have been useful at the time, which is the only thing
"predicted return" can mean.

`AnnualSeries::as_of` is what makes the PIT-honest version of this backtest constructible at all; the
harness that will eventually use it (work-order item 6) is out of this run's scope, but the charter
exists so that whoever builds it inherits the discipline rather than reinventing — or skipping — it.

---

## 5. Named failure modes

Four are named explicitly, because each has already burned other research programmes and each is
silent until specifically checked for:

**Survivorship.** Restricting the population (§3) to issuers that are *currently* covered, currently
liquid, or currently in the S&P 500 would silently drop every issuer that failed, was acquired, or
was delisted between a historical cutoff and today — precisely the issuers whose realized return was
worst. A cross-section built this way overstates how predictable "success" is, because failure has
been defined out of the sample. The eligibility rule in §3 is stated in terms of what an issuer
*filed*, at each historical cutoff, not in terms of the issuer's status today — but this charter
records the risk explicitly because the point-in-time universe construction (which issuers were
covered *as of* each cutoff, not which issuers are covered now) is itself work-order item 6 and has
not been built yet. A harness that silently restricts to today's coverage list would reintroduce
this failure mode even while using PIT-correct financial data.

**Restatement leakage.** The same failure §4 describes at the mechanism level, restated as a named
risk: a predicted value that is (even partially, even indirectly through a derived driver) built
from a figure restated after the prediction's cutoff is not a prediction. `docs/roic-target-specification.md`
row 10 is the specific, mechanical control against this for the target's own construction
(predictions use `as_of`; realized outcomes use `latest`); this charter names the general failure
mode so any future extension of the data pipeline is checked against it, not only the target
specification's current seventeen rows.

**The "what did we know then" trap.** The precise failure the brief's own PIT-foundation section
names: treating `year` alone as sufficient to establish availability, when a fact filed for fiscal
year `t` may not have been *known* — filed, accepted, public — until well into `t+1`. A backtest that
aligns "prediction for year `t`" with "all data whose `year` field is `<= t`" without checking `filed`
dates against the cutoff commits this trap even while nominally respecting fiscal-year boundaries.
Wave 1's `AnnualProvenance::filed` field, and `as_of`'s use of it rather than of `year`, is the
concrete defense; this charter names the trap so a future harness is reviewed against it explicitly,
not merely assumed correct because it "uses `AnnualSeries`."

**A ratio's denominator can sit near zero.** `ΔNOPAT / ΔIC` is a ratio of two flow quantities, either
of which can be small relative to the noise in its own measurement in a given three-year window. A
denominator near zero does not make the ratio moderately noisy; it can make it arbitrarily large or
sign-flip on a rounding-level difference in the underlying evidence. `docs/roic-target-specification.md`
rows 11-12 are the mechanical exclusion rules already pinned against this for the target itself
(`ΔIC = 0` excluded outright; `|ΔIC| < 1%` of beginning capital excluded as a small-denominator
floor). This charter names the general failure mode because any research candidate's *own* internal
arithmetic (a `g/r` implied estimator, for instance — already shown in the T2.0 probe to measure
nothing by construction when its denominator degenerates to the very quantity being tested against)
can reintroduce the same fragility even after the target's own denominator has been protected.

---

## 6. What this charter does not do

It does not implement the rolling PIT harness (work-order item 6), does not implement or select any
candidate estimator (item 7), and does not run any comparison. It states what a research programme
against this target must establish and must guard against, so that whoever builds the harness and
the candidates inherits a checked foundation rather than an implicit one.
