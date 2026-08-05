# Governing Brief — Juan's binding decisions (E2E session `valuation-pit-contract`)

Session root: `G:\dev\repos\discount_screener\.agents\workspace\tmp\e2e\valuation-pit-contract\`
Repo: `G:\dev\repos\discount_screener` · Branch: `valuation/wave1-integration`
Platform: Windows 11, PowerShell primary. Rust workspace at `apps/windows/src-tauri`
(Shell crate `discount_screener_windows_lib`), dependency-free functional core at
`apps/windows/src-tauri/valuation-core`.

Overarching goal, in Juan's words: make the valuation model coherent with street WITHOUT
clamping to street. *"no soy un experto en esto. solo se que los numeros no dan acorde al
street y eso es lo que está mal de nuestro modelo."*

---

## 0. Measured facts (inputs, NOT open questions)

A committed measurement probe produced these. Do not re-litigate.

- `return_on_capital` in `apps/windows/src-tauri/src/valuation_core_adapter.rs:557` is hardcoded
  `Observation::absent` for EVERY issuer. FR-29 then substitutes `r := w`, collapsing the
  valuation to `E_0 / w`. Growth is credited nothing, for everyone.
- ROIC coverage is 25/28 issuers with >=3 complete years. MPWR has 0 (no debt tag ever filed);
  EPAM has 0 after the `InterestPaidNet` removal.
- Median NOPAT/FCFF ~ 0.85x. `b = (NOPAT-FCFF)/NOPAT` is negative for 13 of 25 issuers.
- The OLS "levels slope" estimator (NOPAT regressed on invested capital over annual level
  series) is negative for FIS, DAL, WDC, OMC, PG. Diagnosed as spurious regression
  (Granger-Newbold) on trending, autocorrelated level series with 10-19 observations; its
  conventional standard error is unreliable. **Permanently deleted as a candidate — including
  as a refusal signal or a derived quality flag.**
- `InterestIncomeExpenseNet` currently resolves as an *expense* for COF (19 yrs), DAL (15),
  CHTR (12), BKR (11). For a cash-rich issuer filing net interest *income*, `pretax + interest`
  double-adds income that pretax already contains. Measured, not yet fixed.

---

## 1. Juan's decisions (binding)

### Decision 1 — Coverage is diagnostic only
Coverage will not be a promotion gate and will not have an arbitrary floor. A candidate that
abstains when evidence is insufficient is preferable to one that publishes more unsupported
estimates. Report coverage, abstention rate, prior-only rate, and selective error-versus-coverage
behaviour — but none of those may elect or disqualify an estimator. The primary comparison uses
the **common issuer-cutoff set**, so a candidate cannot look better merely by abstaining on hard
cases. If the promoted estimator cannot support an issuer, the Core must return an explicit
unavailable/incomplete state for that issuer.

### Decision 2 — The new Core goes dark rather than retain FR-29 scaffolding
FR-29 will NOT remain as temporary scaffolding. When `r := w` is removed, ROIC-dependent
valuations in the new Core return an explicit unavailable state until a validated estimator is
promoted. The old engine may remain live in the Shell as a separate legacy module during
module-by-module replacement, but **the new Core must not publish a number it cannot justify.**
This is an intentional product and specification decision, not accidental loss of coverage.

The removal must land **atomically** with: the specification change; the rationale; the explicit
unavailable-state contract; and replacement tests asserting that absent return-on-capital
evidence does not produce a valuation.

The existing FR-29 feature rows and the test `an_absent_return_on_capital_values_at_the_neutral_line`
(`valuation_core_adapter.rs:1057`) must NOT simply disappear. They must be **replaced** by tests
for the new required behaviour, so history records a *changed contract* rather than a
*weakened check*.

FR-29 removal is therefore not blocked on a promoted estimator. It is blocked only on defining
and implementing the explicit unavailable state correctly.

### Decision 3 — The growth-engine rebuild is mandatory, same programme
Growth work is in scope and will not be deferred. It is a peer workstream with its own research
charter, but belongs to the same economic model and the same definition of done. The
ROIC/reinvestment model cannot be promoted until the growth workstream is complete and the units
are aligned.

Two candidate directions, **neither approved in advance**, both to be evaluated against the
economic contract and point-in-time evidence: (1) estimate and validate NOPAT-growth persistence
directly; (2) project revenue and margins separately and derive NOPAT growth through an explicit
margin bridge.

The existing revenue-growth coefficient cannot be reused as though it were NOPAT growth. Today
`fit_growth_path` at `valuation_core_adapter.rs:275` fits annual REVENUE growth, pooled
cross-sectionally, de-meaned by a naked mean, persistence fitted through the origin — the
`0.1709` persistence everything rests on is a revenue number.

The naked means at `valuation_core_adapter.rs:280` and `:536` are known correctness violations
and should be fixed **immediately**. They do not wait for the larger growth redesign.

### Pre-registration structure
Listing many metrics is not a decision rule. Exactly ONE primary endpoint and ONE promotion rule,
written before the final harness runs:

> **Primary endpoint:** cross-sectional median absolute error between predicted and realized
> incremental return over the three-year horizon, evaluated on the common issuer-cutoff set.

The comparison against `prior_only` must be **paired**. Because rolling cutoffs from the same
issuer are not independent, uncertainty must be estimated with resampling **clustered at the
issuer level**, not treating each issuer-cutoff observation as independent.

`improvement = MAE(prior_only) - MAE(candidate)`

Promote only when: (1) improvement exceeds a pre-registered minimum economically meaningful
threshold, AND (2) the pre-registered cluster-bootstrap confidence interval for the improvement
remains above zero.

The economically meaningful threshold must be **derived from how return-on-capital estimation
error propagates into reinvestment and valuation**, written before observing which candidate
wins, never chosen from empirical results.

All remaining metrics are secondary diagnostics. **Secondary diagnostics may veto a candidate
that passed the primary endpoint; they may never promote a candidate that failed it.** Potential
vetoes: material signed bias; materially miscalibrated intervals; unacceptable tail failures;
temporal instability; evidence leakage; dependence on a small number of issuers; failure in
economically important cohorts. **Coverage is excluded from the veto set** per Decision 1.

### Point-in-time data foundation
`AnnualValue { year, value_dollars }` (`apps/windows/src-tauri/src/edgar.rs:72`) is insufficient.
Calendar year alone cannot establish what was knowable at a historical cutoff. The `filed` date IS
already read inside the extractor (`edgar.rs:196` `let filed = entry["filed"]...`, used at `:226`
and `:232` `&& candidate.filed > existing.filed` to resolve re-filings) and then **discarded**.

The annual observation must at minimum retain: `filed`; `end`; `fy`; `fp`; source form; source
accession or equivalent source identity when available; the original unit and fact identity where
needed for provenance. The exact struct may differ, but **no layer may discard information
required to answer**: *"Was this observation available at cutoff `t`, from which filing, and under
which period interpretation?"*

PIT metadata must survive the whole chain:
`companyfacts -> extraction -> normalization -> driver construction -> probe -> holdout`.
No upper layer may reconstruct filing availability from `year` alone.

### Economic contract (gating artifact, not post-hoc documentation)
Must formally define: NOPAT; invested capital; reinvestment; organic investment; acquisitions and
divestitures; capital-consumption treatment; `g`; `r`; expected timing between investment and
return; valid units; valid absence states; the relationship between growth, return and
reinvestment. It must also define intended semantics for financial companies and any other issuer
classes where ordinary invested-capital definitions may not apply. **No estimator comparison or
target pre-registration is valid until this contract exists.**

Economic identity being formalized:
`FCFF = NOPAT - Reinvestment`; `ReinvestmentRate = g_NOPAT / r`; `FCFF = NOPAT x (1 - g_NOPAT/r)`.
The Core's retention charge is FR-28: `C(t) = E(t) x (1 - g(t)/r)`, `V = int C(t) e^{-wt} dt`.
Sequencing fact already established: NOPAT base alone -> reinvestment charged ZERO times ->
overvalues; ROIC alone with FCFF base -> charged TWICE; both together -> charged exactly once.

### Target specification
`ΔNOPAT / ΔIC` is not yet a complete target definition. Before the harness runs, the
pre-registration must specify: the exact three-year windows; whether changes use beginning, ending
or average capital; lag treatment; organic versus acquired capital; acquisitions; divestitures;
impairments; restructurings; currency effects; restatements; `ΔIC = 0`; small denominators;
negative invested capital; negative changes in invested capital; negative NOPAT; issuer-class
exclusions; all data-quality exclusion rules. **Written before candidate results are inspected.
Any subsequent change to the target or exclusions is a NEW experiment requiring a new untouched
holdout.**

### Work order (Juan's)
Items 1-4 can begin immediately and in parallel:
1. Thread PIT metadata through `AnnualValue` and every driver path.
2. Fix `InterestIncomeExpenseNet`, including COF, DAL, CHTR and BKR.
3. Replace the naked means at `:280` and `:536` and audit adjacent load-bearing averages.
4. Write the economic contract.

After the economic contract is complete:
5. Write and commit the target specification and pre-registration: primary endpoint; paired
   decision rule; economic materiality threshold; diagnostic vetoes; coverage policy; leakage
   controls.
6. Build the rolling PIT harness.
7. Implement candidates, benchmarks and ablations.
8. Complete the growth-engine research workstream in parallel.
9. Integrate only after both the growth and return-on-capital contracts have passed their
   validation gates.

FR-29 removal can occur once the explicit unavailable-state specification and tests are ready.

### Repository artifacts (required deliverables — must not live only in a chat log)
- `docs/valuation-economic-contract.md`
- `docs/roic-research-charter.md`
- `docs/roic-preregistration.md`
- a growth research charter (suggest `docs/growth-research-charter.md`)
- an ADR covering FR-29 removal and the explicit unavailable-state behaviour

The research charter defines what must be established. The pre-registration defines how candidates
will be judged. The ADR records the intentional behaviour change and its operational consequence.

### Juan's closing instruction, verbatim
> **Do not select an estimator, preserve an unsupported fallback, or narrow the economic contract
> to keep the Core publishing numbers.**

---

## 2. Scope for this E2E run

**IN SCOPE (deliver in full):**
- Work-order items **1, 2, 3, 4** — parallelizable waves.
- Item **5** — the target specification and pre-registration, written after the economic contract
  is complete.
- All five **repository artifacts** listed above.
- **FR-29 removal + explicit unavailable-state contract**, as its own final wave, landing
  atomically with the spec change, ADR, and replacement tests per Decision 2. If the explicit
  unavailable-state contract cannot be defined cleanly from the economic contract in this run,
  deliver everything else in full, land the ADR describing the intended change, and report FR-29
  removal as deferred with the specific blocker named — do NOT half-land it.

**EXPLICITLY OUT OF SCOPE (do not start, do not pre-empt):**
- Item 6 (rolling PIT harness implementation), item 7 (candidates/benchmarks/ablations), item 9
  (integration).
- Item 8 beyond writing the growth research charter — do NOT rebuild the growth engine in this
  run; the charter defines what must be established, and both candidate directions stay
  unapproved.
- **Selecting or promoting any return-on-capital estimator.** Book ROIC, `prior_only`, and
  shrinkage remain research candidates only.
- Reusing `valuation-core/src/posterior.rs::fuse` for the ROIC channel. Its statistical semantics
  must be audited first (its own module doc already warns the minimum-variance property holds only
  for *unbiased* channels, and that low variance can signal herding/correlated bias which the
  estimator would then weight *up*). If the audit is worth doing now, write it as a finding in the
  research charter — do not wire `fuse` to anything.
- The adapter change (NOPAT base + measured ROIC landing together).
- AMZN policy/16, Android parity, the ROIC fixture, `docs/project-context.md`, re-capturing
  `core_driver_data_deep.json` under policy `/8`.
- Running the PRD Finalize workflow. `prd.md` status stays `draft`, deliberately parked.

---

## 3. Hard constraints — every one is a blocking review criterion

1. **No ticker special-cases.** Never `if symbol == "CHTR"`. Policy-level fixes only. The
   `InterestIncomeExpenseNet` fix must be a change to `shared/contracts/sec-driver-normalization.json`
   (bumping the fingerprint past `sec-driver-normalization/8`) and/or the taxonomy semantics — not
   a per-issuer branch.
2. **Street/market price is an external diagnostic** — never a clamp target, never an optimand,
   never an acceptance criterion. It is not reachable from inside the value function (FR-35) and
   must stay that way.
3. **Forbidden outright:** min-WACC-as-truth, price caps as valuation truth, output clamps, sector
   FCF haircuts.
4. **Do not move a test threshold to make a change pass.** Standing memory rule: never gain
   valuation-gate ground by relaxing a test, threshold, or refusal path. A *spec change* (FR-29) is
   permitted ONLY when it lands with rationale + replacement tests per Decision 2; anything else is
   a weakened check and will be rejected.
5. **Absence never becomes a fabricated zero or a floor.** A missing tag drops the year; it does
   not read as zero earnings or debt-free capital.
6. **No naked averages.** Never write a bare `sum / n` or a hand-rolled mean over a measured
   series. Use `valuation_core::robust_mean(&sample, MAX_ABSOLUTE_Z)` and
   `valuation_core::standardize(&sample).outliers(..)` from `valuation-core/src/numerics.rs`. Do
   NOT add a second implementation — extend that one with tests. Scores are median/MAD, not
   mean/sd, because with mean/sd the outlier inflates its own scale and `max|z| = (n-1)/sqrt(n)`
   (2.85 at n=10), so a 3-sigma rule cannot fire on 10-19 year histories.
   **`MAX_ABSOLUTE_Z = 3.0` does not move** — treat lowering it exactly like relaxing a test
   threshold. Standing rule is in `AGENTS.md` under "Aggregation — no naked averages".
   - Note the existing `mean()` at `valuation_core_adapter.rs:745`. Call site `:781`
     (`least_squares` centering) is estimator arithmetic and is defensible; `:280` and `:536` are
     not. `:536` is the worse of the two — `let (Some(mean), Some(variance)) = (mean(&growth),
     sample_variance(&growth))` supplies both the trailing-growth channel's point estimate AND its
     precision, so contamination silently reassigns weight to the forward channel inside `fuse`.
7. **Never `git add -A`.** The repo carries long-lived uncommitted work. Stage files explicitly,
   every time.
8. **Leave `apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json`
   unstaged.** It is long-lived uncommitted work.
9. **Anchors are PG, GOOGL, AMZN, MSFT.** AAPL was deliberately dropped: *"lo que busco es que AAPL
   se arregle 'solo' como consecuencia de tener un buen modelo de estimación y fuente de datos."*
   Anchors are diagnostics only — never a calibration target.
10. **Global code style (`~/.claude/CLAUDE.md`):** NEVER use fully-qualified names — always import.
    **1 assert per test** (use soft-assert grouping if 2+ needed). KISS: if it feels "smart", pause
    — can it be simpler? DRY: if you copy-paste twice, pause — can it be reused? (`var` rule is
    Java-specific, N/A in Rust.)
11. **The specification is the Gherkin outlines.**
    `apps/windows/src-tauri/valuation-core/tests/features/*.feature` is the contract, not
    documentation of it (FR-45). Behaviour is added by adding a **row** to an existing `Examples`
    table; a new `Scenario Outline` requires a manifest entry stating what no existing table covers
    (FR-44). `tests/schema.rs` enforces six rules on the tables. cucumber-rs runs with
    `fail_on_skipped()`.
12. **`valuation-core` has an empty dependency list (FR-1)** and performs no I/O, reads no clock,
    reaches no network, observes no market price. Adding a dependency capable of I/O is the one
    reviewable event that would break it.
13. **`Observation<T>`** (`valuation-core/src/evidence.rs`) is a sum type over `Measured`/`Absent`
    — no `Default`, no `unwrap_or(0.0)`.

---

## 4. Known state — do not "fix" these, do not be surprised by them

**Pre-existing test failures that must NOT be repaired by this work and must still be exactly
these three at the end:**
- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

Baseline: Shell `cargo test --lib` = 518 passing, 22 ignored, those 3 failing.
Core crate = 89 + 7 passing.

**Generated policy pipeline:** `shared/contracts/sec-driver-normalization.json` ->
`scripts/generate-sec-driver-normalization-policy.ps1` -> generated Rust
(`apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs`) + generated Kotlin.
The generator's `-Check` mode is now wired into `scripts/validate-contracts.ps1`, which CI's
`contracts` job runs. Current fingerprint: `sec-driver-normalization/8`. Any policy change must bump
the fingerprint, regenerate both targets, and update the fingerprint assertion in
`apps/windows/src-tauri/src/sec_normalization.rs` and
`shared/contracts/sec-driver-normalization-fixtures.json`. The generator supports `-OutputRoot` for
non-destructive regeneration into a scratchpad for hash comparison.

**`select_one_equivalent` semantics:** merges qnames in declared order and only fills gaps. That
means a wrong-statement concept placed late in the list silently splices into an accrual series —
relevant to the `InterestIncomeExpenseNet` fix. Precedence order is load-bearing.

**Stale data note:** `core_driver_data_deep.json` was captured before the `InterestPaidNet` removal
and is stale relative to policy `/8`. Parked — do not re-capture in this run.

**Probes convention:** research probes live in `apps/windows/src-tauri/src/valuation_probes.rs`,
marked `#[ignore = "network: ...; diagnostic only"]`, print a table, and **assert nothing**. Follow
`probe_growth_persistence_rho1` structurally.

**Scratchpad for temp files:**
`G:\dev\caches\tmp\claude\G--dev-repos-discount-screener\a5b7ed32-8a2d-4c54-ba78-5568775587f2\scratchpad`

**Repo docs convention (verified by orchestrator, 2026-08-04):** `docs/` is FLAT. There is no
`docs/adr/`, `docs/adrs/`, or `docs/decisions/` directory and no existing ADR file anywhere in the
tracked tree. `docs/index.md` exists and indexes the flat docs.

---

## 5. Working protocol with Juan

Act autonomously on any fix that passes a documented sniff test. Pause and ask ONLY for:
(a) two reasonable designs with materially different economic results and no test that decides
between them;
(b) an anchor (PG, GOOGL, AMZN, MSFT) moving more than +/-5% or changing side of a gate;
(c) a fix-versus-refusal choice.

Juan's words on that last point: *"no seas vos el que decide si 'vale la pena' alejarse de un
anchor."* Do not unilaterally decide an anchor deviation is acceptable.

He is not a valuation expert and is explicit about it; he is a strong engineer. Explain economic
reasoning plainly, show the measurement rather than asserting the conclusion, and never declare
victory on a number you have not verified.

**Report faithfully.** If a stage is blocked, say so with the evidence. If tests fail, show the
output. Do not report completion for anything not fully done. Communicate in English.
