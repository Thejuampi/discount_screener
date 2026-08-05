# `plan.v1.md` — Valuation PIT & Contract (E2E session `valuation-pit-contract`)

Branch: `valuation/wave1-integration` · Repo: `G:\dev\repos\discount_screener`
Supersedes `plan.v0.md` in full. This is a replacement plan, not a patch.

Inputs, in precedence order: `brief.md` (Juan's decisions 1–3, 13 binding constraints) →
`plan-review/r1-consolidated-directives.md` (every P0/P1 adopted; every RESOLUTION binding) →
`refine.md` (Q2/Q4–Q8 resolved; Q1 open) → `plan.v0.md` (preserved where unfaulted).

Everything asserted below was re-verified against the working tree on 2026-08-04. Where a
claim could not be verified without running live code, it says **unverified** and names what
would verify it. Line numbers are given only where I read them; task text prefers symbol names.

---

## 0. Q1 — the one open decision, and what it blocks

**Question for Juan.** `InterestIncomeExpenseNet` and `InterestIncomeExpenseNonoperatingNet`
sit at positions 7 and 8 of `drivers.interestExpense.qnames` in
`shared/contracts/sec-driver-normalization.json`, where `select_one_equivalent` gap-fills them
into a gross-expense accrual series. `refine.md` put two options to you. **The answer is now a
third option that was not on the table when you were asked**, and it is materially better than
both, so the escalation is reopened rather than closed by the planner.

### The three options

| | Option | What it does | Cost |
|---|---|---|---|
| (i) | Delete both qnames | Follows the `InterestPaidNet` precedent | Discards **57 issuer-years** of interest expense: COF 19, DAL 15, CHTR 12, BKR 11. Of those, **38 belong to operating non-financial issuers** (DAL 15, CHTR 12, BKR 11) — the class this contract's `scope.businessClass` actually governs. COF's 19 belong to a `FinancialServices` issuer the Core refuses on other grounds anyway. |
| (ii) | Admit the net concepts under a runtime value predicate | Not expressible; would be the driver language's first value-conditional rule | Rejected. A predicate on the filed *value* is a per-issuer branch wearing a policy costume. |
| **(iii)** | **Declarative per-qname sign convention** | A static constant in the contract that maps a netted concept onto the class's measurement basis. No value predicate, no ticker branch. | Recovers all 57 issuer-years with their sign corrected. Requires new contract vocabulary, a generator change, and both targets regenerated. |

### Why (iii) is right and why it was not visible before

The measured filed data in the directives is decisive. For LIN:

| LIN | 2022 | 2023 | 2024 | 2025 |
|---|---|---|---|---|
| `InterestIncomeExpenseNet` | −63M | −200M | −256M | −255M |
| `InterestExpenseNonoperating` | +63M | +200M | +256M | +255M |

Exact negations. And BAC 2025 `InterestIncomeExpenseNet` = **+60,096M** against pretax 37,695M.
That is not an equivalence-class defect; it is a **sign-convention** defect. The two net concepts
are the same line of the same statement, filed with the opposite sign convention. Deleting them
throws away correct data because we were reading it upside down.

`P0-H` is the reason the deletion argument does not carry: the committed rule at `AGENTS.md`'s
anti-pattern table and in `sec-driver-normalization.json`'s `interestExpense.rationale` is a
**cross-statement** rule — *"equivalence classes hold one statement's concept only … not a
substitute from another statement"*. `InterestPaidNet` is a cash-flow-statement disclosure inside
an income-statement class. Both net concepts are income-statement concepts. The existing rule,
read literally, does not forbid them, so the precedent does not reach this case.

### The cost of being wrong is measured, not asserted

Under (iii), does the published intrinsic value move? **No, and Wave 2 proves it rather than
claiming it.** Verified call chain:

- `edgar.rs:1093` passes `interest_expense_dollars` into `FcfPoint::with_operating_drivers`.
- `dcf_model.rs:907`, the body of that setter, is
  `self.interest_expense_dollars = interest_expense_dollars.map(f64::abs);`
- That setter is the **only** writer of the field outside its `None` initialiser (verified: the
  only other occurrences of `interest_expense_dollars =` in `src/` are the field declaration, the
  `None` default, and the parameter).

So every production consumer reads a pre-absolute-valued number:

| Site | Reads | Effect of a sign change |
|---|---|---|
| `dcf_model.rs:550` (`let interest = interest.abs();`) | post-abs field | none — second `abs` |
| `dcf_model.rs:1586-1591` (`.filter(is_finite).zip(tax).map(|(i,_)| i.abs())`) | post-abs field | none — second `abs` |
| `dcf_model.rs:795` (audit table print) | post-abs field | none |
| `driver_resolution.rs:81` (`interest.abs() > f64::EPSILON`) | post-abs field | none |
| `driver_resolution.rs:117-119` (`|| interest < 0.0 { return None }`) | post-abs field | **guard cannot fire on the production path** |
| `valuation_baseline.rs:900`, `valuation_fixture_capture.rs:131`, `valuation_probes.rs:344,354` | post-abs field | none |

**One correction to the directives' table, found while verifying.** The `interest < 0.0` guard at
`driver_resolution.rs:117-119` is dead *on the production path only*. It is still reachable from
`driver_resolution.rs`'s own `#[cfg(test)] mod tests` (the `fn point(year, debt, interest, tax)`
helper at `:326` builds an `FcfPoint` by literal and can set a negative interest directly). Wave 2
must not delete that guard, and must not describe it as unreachable without that qualification.

### What is blocked, and what is not

- **Round 2 (Wave 2) does not start until Juan answers.** `AGENTS.md`'s craft stance and brief §5
  both say an escalated fix-versus-refusal choice is not the agent's to close, and the option now
  proposed is one Juan has never seen.
- **Rounds 1, 3 and 4 proceed regardless.** Waves 1, 3, 5 and 4 contain no dependency on the
  interest-qname decision. If Juan chooses (i) after all, only Wave 2's T2.1/T2.2 change shape;
  the fingerprint bump, the regeneration, the five-site update and the R1/R2 documentation rules
  are identical under either answer.
- If Juan does not answer before Round 4 completes, the run ships Waves 1/3/5/4 and reports
  Wave 2 as **blocked on an escalated decision**, with this section as the evidence. It is not
  half-landed (brief §2).

---

## 1. Summary

### 1.1 Goal

Replace the fabricated return-on-capital path with an honest one:

1. make the annual evidence **point-in-time correct** — retain vintages, not just the latest
   belief, and stop re-deriving years by slicing strings (Wave 1);
2. make the interest-expense equivalence class read one **measurement basis**, by declaring the
   sign convention that maps a netted concept onto it (Wave 2);
3. make the growth channel's **arithmetic non-contaminated**, in the centre *and* in the fit that
   consumes it (Wave 3);
4. remove **FR-29**'s value-neutral substitution and land a named, distinguishable unavailable
   state in its place (Wave 5);
5. write the **economic contract, two research charters and the pre-registration** that define
   what is being measured and how a candidate could ever be promoted (Wave 4).

### 1.2 Non-goals

Selecting or promoting any estimator. The rolling PIT harness (item 6), candidates/benchmarks/
ablations (item 7), integration (item 9). Wiring `posterior::fuse` to a ROIC channel. The adapter
change (NOPAT base + measured ROIC together). Rebuilding the growth engine. Touching the legacy
engine's own substitution.

### 1.3 Current-state findings (each re-verified for v1)

| # | Finding | Status |
| --- | --- | --- |
| F1 | **The new Core is not wired to production.** `valuation_core_adapter::value()` has no non-test caller; the diagnostics in `valuation_core_measurement.rs` are all `#[ignore]`. | verified (grep) |
| F2 | FR-29's blast radius is six assertions: `projection.rs` `an_absent_return_on_capital_is_value_neutral_rather_than_floored`; `residual_income.rs` `an_absent_return_on_equity_values_the_issuer_at_book`; `intrinsic-value.feature` row `return-absent`; `residual-income.feature` row `return-absent`; adapter `a_complete_issuer_publishes_a_posterior` (`:1047`) and `an_absent_return_on_capital_values_at_the_neutral_line` (`:1057`). | verified |
| F3 | `AnnualValue` (`edgar.rs:71-75`) derives only `Debug, Clone`. No serde, no IPC, no persisted format. All 31 construction sites are in `edgar.rs` (9 production, 22 test). | verified |
| F4 | The extractor holds full provenance in `AnnualCandidate` and collapses it at `edgar.rs:262-265`. It reads `filed` at `:196` via `entry["filed"].as_str().unwrap_or("")` — **a fact with no filing date currently survives with an empty string** and participates in the `candidate.filed > existing.filed` precedence. It never reads `accn`. | verified |
| F5 | **Four** sites re-derive a year by slicing: `edgar.rs:204` (`annual_candidates_with_shape`), `:417` (`extract_annual_percent_any`), `:495` (`extract_recurring_development`), `:516` (`extract_acquisition_investments`). **v0 named three and missed `:417`.** | verified |
| F6 | `sec_normalization::SecFact` (`:36`) carries `qname, taxonomy, value_dollars, start, end, unit, form, accession, filed, consolidated`, all fields `pub`; `edgar.rs:548` already imports it and `:570` already constructs one. | verified |
| F7 | Both net interest concepts sit at positions 7 and 8 of `interestExpense.qnames`. `extract_annual_any_with_shape` (`edgar.rs:317-322`) is `by_year.entry(year).or_insert(...)`, so later qnames gap-fill. The two strings appear in exactly **three tracked files**: the contract, the generated Rust (`:85-86`), the generated Kotlin (`:96-97`). *(v0 said "4 files"; the fourth was a `.memlog.md`.)* | verified |
| F8 | `Refusal::Evidence(AbsenceReason)` exists (`publication.rs:59`); `kind()`/`detail()` already render it; `equity_value` propagates a firm value's absence reason (`projection.rs:373-380`). | verified |
| F9 | `AbsenceReason::as_str` (`evidence.rs:36-44`) is the **only exhaustive match on `AbsenceReason` in the workspace**. Adding a variant costs exactly one new arm. `operating_valuation.rs:1289,:1774` match on `reason.as_str()` inside a `filter_map` and are not exhaustive. | verified |
| F10 | `schema.rs` defines **seven** `#[test]`s (`:139, :158, :171, :196, :228, :244, :279`), matching the "Core = 89 + 7" baseline. The brief's "six rules" is wrong and v0 repeated it. | verified |
| F11 | `every_examples_row_is_rectangular` (`schema.rs:171`) compares `row.len()` against **that table's** header. There are 7 Examples tables, each with its own header. A `reason` column on two outlines imposes nothing on the other five. | verified — P0-B is spec-legal |
| F12 | `robust_mean` (`numerics.rs:179`) returns only the centre; `standardize` (`:136`) refuses `n<3`, non-finite input and zero middle spread, and already exposes `scores()` and `outliers(z)` **by index**. `MAX_ABSOLUTE_Z = 3.0` at `:29`. | verified |
| F13 | The adapter's averaging call sites are `:280` (pooled growth mean), `:295-296` (through-origin persistence fit), `:335` (`fit_beta_dispersion`), `:485-491` (residual scatter on `n−2` df), `:536` (trailing growth mean **and** variance), `:631` and `:637` (leverage / coverage sample variances), `:781` (least-squares centering). **v0's audit table omitted `:295-296`, `:631` and `:637`, and mislabelled the `sample_variance` body lines `:753/:758` as call sites.** | verified |
| F14 | `CrossSectionDiagnostics` (`valuation_core_adapter.rs:162-172`) derives `Debug, Clone, Default` — no serde. Adding fields is safe. | verified |
| F15 | The legacy substitution is `operating_valuation.rs:223`, inside `terminal_payout_bps`, production-live. | verified |
| F16 | `docs/` is flat, contains no ADR, and `docs/index.md` has a "Maintenance Rules" section requiring it be kept current. `sec-driver-normalization.json` and its fixtures appear in **neither** `docs/index.md` **nor** `shared/contracts/README.md`'s `## Files` list. | verified |
| F17 | `manifest.toml` entry `residual-income-on-book` has `frs = ["FR-30","FR-31","FR-32"]` — **no FR-29**. `intrinsic-value-from-fading-path` does carry FR-29. v0's T5.5 claim that "`frs` keeps FR-29" for both entries was false. | verified |
| F18 | `robust_mean`/`standardize` have exactly one caller outside `numerics.rs`: `valuation_probes.rs:465-466`, which calls both in sequence purely to recover the discarded count, using **fully-qualified** `valuation_core::robust_mean` against constraint 10. | verified |
| F19 | The offline diagnostic `core_versus_current_engine_on_the_pinned_cohort` reads `DEEP_DRIVER_FIXTURE` (`core_driver_data_deep.json`) and `load_cohort()` — both files, no network. It is builder-runnable. **But that fixture was captured before the `InterestPaidNet` removal and is stale relative to policy `/8`**, so it cannot show Wave 2's effect at all. | verified |
| F20 | `sec-driver-normalization-fixtures.json` is read by **Rust only** (`sec_normalization.rs:397-430`). Kotlin does not read it. Dual-lock for this contract runs through the *generated policy*, not the fixture corpus. | verified |
| F21 | Android mirrors the gap-filling exactly: `SecEdgarTimeseriesProvider.kt:170-176` iterates `operator.qnames` with `putIfAbsent`. A sign convention that is not applied there is a parity defect. | verified |
| F22 | `docs/index.md`, `shared/contracts/README.md`, `AGENTS.md` `## Documentation Map` and `AGENTS.md` "Manual procedures" are all as the directives describe. `AGENTS.md:573` does require *"a row to the anti-pattern table **and** a step to the manual procedures"*. `AGENTS.md` currently carries uncommitted working-tree edits. | verified |

### 1.4 Approach

Four **serial rounds**, one to two waves each. Waves inside a round are file-disjoint and may run
in parallel; waves in different rounds have a stated **semantic** dependency, not merely a file
collision. §6.1 gives the schedule and the justification for each edge.

### 1.5 Binding design decisions (builders do not re-open these)

---

**D1 — The point-in-time carrier retains vintages; `known_from` is a report, not the mechanism.**

`known_from = max(sources.filed)` alone answers *"when did we first know what we now believe"*.
It does **not** answer *"what did we believe on date D"* (P0-C). Wave 1 therefore delivers both.

```rust
/// An ISO calendar date, parsed once at the extraction boundary so that no
/// upper layer ever slices a string to get a year or compares dates as text.
/// Ordering is chronological because the fields are ordered most-significant
/// first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct IsoDate { year: i32, month: u8, day: u8 }

impl IsoDate {
    pub fn parse(text: &str) -> Option<Self>;   // strict YYYY-MM-DD, calendar-valid
    pub fn year(&self) -> i32;
    pub fn iso(&self) -> String;
}

/// One filed observation of one concept for one period. Nothing is collapsed.
#[derive(Debug, Clone)]
pub struct AnnualObservation {
    pub fact: SecFact,   // reused unchanged; carries qname/form/accession/filed/end
    pub end: IsoDate,
    pub filed: IsoDate,
}

/// Every vintage of one driver, in filing order. The cutoff-aware view.
#[derive(Debug, Clone)]
pub struct AnnualSeries { observations: Vec<AnnualObservation> }

impl AnnualSeries {
    /// What a reader could have believed on `cutoff`: only observations filed
    /// STRICTLY before it, resolved by the standing precedence (consolidated
    /// over segment, then latest filed, then latest accession).
    pub fn as_of(&self, cutoff: IsoDate) -> Vec<AnnualValue>;
    /// Everything filed to date. Defined as `as_of` with no upper bound, so
    /// there is exactly one resolution implementation.
    pub fn latest(&self) -> Vec<AnnualValue>;
}

pub struct AnnualValue {
    pub year: i32,
    pub value_dollars: i64,
    pub provenance: AnnualProvenance,
}

/// Everything needed to answer: was this knowable at cutoff `t`, from which
/// filing, and under which period interpretation?
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnnualProvenance {
    /// The period end exactly as filed. `AnnualValue::year` is derived from
    /// this and from nothing else.
    pub end: IsoDate,
    /// The date this observation became knowable: the LATEST `filed` among
    /// `sources`. A composite is knowable only once its last input was filed.
    pub known_from: IsoDate,
    /// Every fact that contributed, in combination order.
    pub sources: Vec<SecFact>,
    /// True when `sources` do not share one filing date. Real compositions
    /// legitimately mix vintages (OCF and CapEx can arrive in different
    /// filings); the flag makes that visible instead of silent.
    pub mixed_vintage: bool,
}
```

Decisions inside D1, each with the alternative it rejects:

- *Reuse `SecFact` as the annual carrier* — **rejected.** A `SecFact` is one raw fact; a
  fiscal-year observation is often a **composition** (total debt = current + non-current; FCF =
  OCF − CapEx; development = tangible + software). A composition has no single `filed`.
- *Invent a third provenance struct* — **rejected on DRY.** `SecFact` already has every field and
  `edgar.rs` already imports and constructs it.
- *Retain `fy` and `fp`* — **rejected deliberately, and this is substantive.** `fy` is a property
  of the **filing**, not of the fact's period; the comment at `edgar.rs:197-203` already records
  that keying by it "discards valid comparative years and creates a broken driver history" (NVDA
  files FY2025 and FY2026 revenue both carrying `fy=2026`). `fp` is `"FY"` by construction for
  every fact this extractor admits. `accession` + `form` + `filed` identifies the filing exactly,
  from which `fy`/`fp` are recoverable. The brief permits this: *"The exact struct may differ, but
  no layer may discard information required to answer…"* — all three questions stay answerable.
- **`accession` is now captured** from `entry["accn"]`, which the annual extractor never reads.
- **Fail-closed.** A fact whose `filed` is missing or unparseable, or whose `end` will not parse
  to a calendar date, produces **no** observation. The current `unwrap_or("")` fabricates an
  availability date, which is constraint 5 in date form. This removes the `fy` fallback at
  `edgar.rs:205-209`. Its live cost is **measured** by T1.7's probe before the wave is called done.
- **Strictly before.** `as_of(cutoff)` admits `filed < cutoff`, never `<=`. A fact filed on the
  cutoff date was not knowable at the start of that day, and a boundary that admits it is a
  one-day leak repeated at every cutoff.
- **Fiscal-year semantics, decided and pinned.** `AnnualValue::year` is the **calendar year of the
  period end**, and explicitly **not** the issuer's own fiscal-year designation. A retailer with a
  2025-02-01 close is labelled 2025 even though the issuer calls it FY2024. Rationale: the label
  is used only to align drivers *within one issuer* and to order them; every driver of an issuer
  derives its label from the same `end`, so alignment is exact, and re-labelling would shift
  published years on the live FCFF path for no analytic gain. `provenance.end` is the
  authoritative period identity; anyone needing the issuer's own label reads `end` and the
  filing. **Named limitation:** an issuer that changes its fiscal year end can file two period
  ends in one calendar year; the existing "keep the later `end` for a year" rule
  (`edgar.rs:238-247`) drops the earlier one. That is status quo, it is now pinned by a test, and
  it is recorded in the Wave 1 doc.
- **Boundary, named not implicit.** `dcf_model::FcfPoint` is **not** extended in this run. It has
  no PIT consumer (item 6 is out of scope), it is a production type, and changing it would move
  published valuations. The PIT-capable path is `fetch_company_facts` + `extract_driver_vintages`
  → `AnnualSeries::as_of`. Wave 1's doc states this.
- **I6 — Wave 1 does not modify `sec_normalization.rs`.** `SecFact.value_dollars` is `i64` while
  `extract_annual_percent_any` handles unit `"pure"`. **Pre-decided:** a non-dollar fact stores
  the filed integer in `value_dollars` with its true `unit` string. Do not retype the field; that
  file belongs to Wave 2.

---

**D2 — One aggregation primitive, extended once, with no public threshold knob.**

```rust
/// A robust centre together with the width of that centre and the observations
/// it could not use.
pub struct RobustCentre { /* private */ }

impl RobustCentre {
    pub fn centre(&self) -> f64;
    /// The SQUARED STANDARD ERROR of `centre`, not a variance of the sample.
    /// Named for what it is so no caller reads it as a dispersion.
    pub fn variance_of_centre(&self) -> f64;
    pub fn retained(&self) -> usize;
    pub fn discarded(&self) -> usize;
    /// Indices into the INPUT sample, in input order, of the observations the
    /// centre excluded. Callers that must exclude the same observations from a
    /// downstream fit read this rather than re-deriving it.
    pub fn outliers(&self) -> &[usize];
}

/// The robust centre at the standing threshold. There is no threshold
/// parameter, on purpose: `MAX_ABSOLUTE_Z` is a boundary between populations
/// and a call site that could pass 4.0 would be relaxing a threshold without
/// touching the constant (constraint 6).
pub fn robust_centre(sample: &[f64]) -> Result<RobustCentre, AbsenceReason>;
```

- Implementation: one private
  `fn trimmed(sample: &[f64], max_absolute_z: f64) -> Result<RobustCentre, AbsenceReason>`
  built on the existing `standardize`. `robust_centre(s) = trimmed(s, MAX_ABSOLUTE_Z)`.
  `robust_mean(s, z) = trimmed(s, z).map(RobustCentre::centre)`. **One trimming implementation in
  the workspace**, which is literally what `AGENTS.md`'s aggregation rule demands.
- `robust_mean` **keeps its existing signature.** Changing it would drag `valuation_probes.rs`
  (F18) into Wave 3's file set for no benefit. Its threshold parameter is legacy surface; the
  audit doc states that no new call site may pass anything but `MAX_ABSOLUTE_Z`, and a test pins
  `robust_mean(s, MAX_ABSOLUTE_Z) == robust_centre(s).centre()`.
- **`variance_of_centre` is an approximation and it errs in the dangerous direction.** Computing
  it from the retained sample understates the estimator's uncertainty, because the retained
  sample is narrower than the population the estimator is estimating. Under inverse-variance
  fusion a channel that reports too tight a precision gets weighted **up** — the opposite of what
  a trimmed channel deserves. Both reviewers found this independently and it is adopted as a
  stated limitation, not silently shipped:
  - the direction ("narrower than truth") is written in `docs/valuation-aggregation-audit.md`;
  - the audit records that this changes **nothing economically today**, because the forward
    channel at `valuation_core_adapter.rs:545-548` is always `Observation::absent` and `fuse`
    gives an absent channel precision exactly zero, so no weight is reassigned — and that it
    **will** matter the day a forward channel exists;
  - the alternative (a scale over the contaminated sample) is rejected because it would describe
    a *different estimator* than the one that produced the point.
- **Degenerate cases are handled by refusal, not by a special branch.** `standardize` already
  refuses `n < 3`, non-finite input, and zero middle spread; `trimmed` refuses when fewer than 3
  observations survive. Retained `n = 1` (infinite weight) and `n = 2` are therefore
  **unreachable**, and that is asserted by tests rather than left as a claim.

---

**D3 — The explicit unavailable state is a new variant: `AbsenceReason::EstimatorUnavailable`.**

```rust
/// A required estimate exists as a quantity, but no validated estimator can
/// supply it for this issuer. Distinct from `NotReported`: the provider is not
/// at fault and nothing is missing from the filing — the gap is in this Core's
/// own evidence chain.
EstimatorUnavailable,
```

v0 reused `NotReported`. That is rejected, per P0-B, for three reasons that survive scrutiny:

1. It replaces a fabricated *value* with a fabricated *cause*. MSFT's return on capital is not
   "not reported"; it is not estimated.
2. It destroys the changed-contract audit trail Decision 2 exists to create.
3. It **voids Wave 5's own discriminating test.** A bank refusing on book value surfaces as
   `kind()=="evidence"`, `detail()=="not_reported"` — and so would every operating issuer under
   the reused variant. The regression that proves the bank path still works would pass for the
   wrong reason.

Consequences, all verified:

- **Cost of the variant is one match arm** (F9). `AbsenceReason::as_str` gains
  `Self::EstimatorUnavailable => "estimator_unavailable"`.
- **Existing Core tests stay green.** `projection.rs:590,653,672` assert `Some(NotReported)` and
  are constructed with `NotReported` inputs at `:433`; none of them exercises the
  return-on-capital path.
- **`intrinsic_value` and `residual_income_value` refuse with `EstimatorUnavailable` regardless of
  the input observation's own reason.** They do **not** propagate it (Advisor's option (a)),
  because the Core's statement is about its own inability to value without a return estimate, and
  that is true whatever the provider said. `valuation_core_adapter`'s `return_on_capital` keeps
  returning `ProviderUnavailable` — an honest description of the *provider* — and the bank path
  keeps `ProviderUnavailable` on `book_value`. That is the discrimination, by construction.
- **A `reason` column is added to the two converted outlines.** Verified spec-legal (F11).
  Exact cell values are given in Wave 5 T5.4 so no builder derives them under time pressure.

---

**D4 — `residual_income.rs`'s `unwrap_or(cost_of_equity)` (at `:111`, inside
`residual_income_value`) is removed together with `projection.rs`'s.** Same fabrication, same new
Core, unreachable from the adapter today (a `FinancialServices` issuer refuses on `book_value`
first at `valuation_core_adapter.rs:349-364`), so removal has zero production consequence and pure
specification benefit. Leaving it would be exactly "preserving an unsupported fallback".
`manifest.toml`'s `residual-income-on-book` entry gains `FR-29` to its `frs` list (F17), and the
rewritten FR-29 prose gains one sentence naming the residual-income form. **`FR-31` is not
opened** — `prd.md:437` carries an assumption and an open question that would pull COF provision
normalization into scope.

---

**D5 — `operating_valuation.rs`'s `terminal_payout_bps` substitution stays, and every completion
statement says so.** It is legacy, production-live, affects four rows of
`shared/contracts/operating-valuation-router-v1.json` (GDDY, WYNN, BSX, ALB), and feeds one of the
three known-failing tests. Decision 2 explicitly allows the old engine to remain live. Required
(P0-A), and none of it is optional:

- **Every** completion statement about Wave 5 reads: *"FR-29 removed from `valuation-core`; the
  equivalent substitution remains live in the production path (`operating_valuation.rs:223`,
  `terminal_payout_bps`) and is unaddressed by this run."*
- A **characterization test in the Shell** naming the live fabrication (Wave 5, T5.7).
- A tracked identifier, owner and **trigger condition** in the latent-defect register (D7).
- **Sensei's Gherkin-row suggestion is not implementable and must not be attempted.** Verified:
  the feature files cover `valuation-core` only; the legacy engine is Shell code with no Gherkin
  surface. A characterization test is the correct instrument.

---

**D6 — Wave 2 is a declarative per-qname sign convention, not a deletion.**

Contract vocabulary (new), on `drivers.interestExpense`:

```json
"negatedQnames": ["InterestIncomeExpenseNet", "InterestIncomeExpenseNonoperatingNet"],
"signRationale": "These two concepts report the same income-statement line as the rest of the class but under the opposite sign convention: a net EXPENSE is filed negative. LIN 2022-2025 files InterestIncomeExpenseNet at -63/-200/-256/-255M against InterestExpenseNonoperating at +63/+200/+256/+255M -- exact negations. The sign is a static property of the concept, declared here, never a predicate on the filed value and never a branch on the issuer."
```

The generator turns membership into a **positional** parallel array so the two can never drift:

- Rust `DriverOperator` gains `pub qname_signs: &'static [i8]`, same length and order as `qnames`.
- Kotlin `GeneratedSecDriverOperator` gains `val qnameSigns: List<Int>`, likewise.

Why a parallel array of signs rather than emitting the `negatedQnames` list itself: an empty list
is impossible for `qname_signs` (every driver has qnames), which removes the generator's only
edge case — verified, `KotlinList` on an empty collection emits a dangling comma inside
`listOf(...)`, which is not valid Kotlin, and `RustSlice` is similarly malformed. And
length-equality against `qnames` is a checkable invariant that a membership list cannot give.

**Two rules, stated as distinct rules** (P0-H RESOLUTION), in `shared/contracts/README.md`, in
`AGENTS.md`, and in the extended contract `rationale`:

- **R1 (existing):** an equivalence class holds **one statement's concept** only. A cash-flow
  disclosure is not an equivalent of an income-statement accrual. *(`InterestPaidNet`.)*
- **R2 (new):** an equivalence class holds **one measurement basis**. A netted concept enters the
  class only through a **declared sign convention** that maps it onto that basis. Absent a
  declared convention it reads **absent**, not equivalent.

Second-order consequence, adopted: once R2 exists it binds every other `select_one_equivalent`
list. `stockholdersEquity` mixes
`StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest` with `StockholdersEquity`
— including versus excluding NCI is a **different basis on the same line**. That audit is a named
follow-up in the economic contract (D7, LD-4); without it R2 is decorative.

---

**D7 — A latent-defect register, with ids, owners and trigger conditions, in the economic
contract.** Four items this run knowingly does not fix. Each is named so it is never later
mistaken for an oversight, and each carries the condition that would force it:

| Id | Defect | Why not now | Trigger |
| --- | --- | --- | --- |
| **LD-1** | `dcf_model::FcfPoint::with_operating_drivers` applies a blanket `.map(f64::abs)` to `interest_expense_dollars` (`dcf_model.rs:907`). For a net-**expense** filer (LIN, −63M) it is right by accident; for a cash-rich net-**income** filer it fabricates an expense add-back out of reported income — constraint 5. | Removing it **does** move published numbers. It needs its own wave, its own live QA, and its own anchor report. | The first change that makes the new Core publish, or the first issuer whose filed interest series is genuinely net *income* reaching the FCFF bridge — whichever comes first. |
| **LD-2** | `resolve_capex_abs` returns a zero CapEx when no series exists (`edgar.rs:604-607`) — a real fabricated zero on the production FCF path. | On the production FCF path; would move published anchors. | Any wave that touches the CapEx-to-FCF bridge. |
| **LD-3** | `operating_valuation::terminal_payout_bps` substitutes the cost of equity for an absent return on capital (`:223`) — the legacy FR-29. | Decision 2 allows the legacy engine to stay live during module-by-module replacement. | Retirement of the legacy engine, or the first router row whose decision inverts on the substitution. |
| **LD-4** | `stockholdersEquity`'s equivalence class mixes NCI-inclusive and NCI-exclusive concepts — one line, two measurement bases, under R2. | Out of this run's scope; changing it moves invested capital for every issuer with a material minority. | Adoption of R2 (Wave 2); the audit is due before any invested-capital estimator is pre-registered against filed equity. |

Owner for all four: the valuation quant workstream (this plan's successor run). The register
lives in `docs/valuation-economic-contract.md` and is linked from the ADR.

---

**D8 — FR-29 keeps its identifier and is retitled.** Retaining `FR-29` with inverted content makes
the record read as a *changed contract*, which is what Decision 2 asks for, and keeps every
existing cross-reference (`manifest.toml` `frs`, module docs) resolvable.

### 1.6 Public interface / contract changes

| Surface | Change | Compatibility |
| --- | --- | --- |
| `edgar::AnnualValue` | third field `provenance: AnnualProvenance` | crate-internal (F3); no serde, no IPC, no persisted format |
| `edgar::{IsoDate, AnnualProvenance, AnnualObservation, AnnualSeries}` | new public types | additive |
| `edgar::extract_driver_vintages` | new; `extract_driver_annual` becomes its `latest()` view | additive; existing signature unchanged |
| `shared/contracts/sec-driver-normalization.json` | `interestExpense` gains `negatedQnames` + `signRationale`; `fingerprint` to `sec-driver-normalization/9` | contract version bump; both targets regenerated |
| generated `DriverOperator` / `GeneratedSecDriverOperator` | new field `qname_signs` / `qnameSigns` | additive; generated on both platforms |
| Kotlin `SecDriverNormalizationPolicy.DriverOperator` | new field `qnameSigns: List<Int>` defaulted to `List(qnames.size) { 1 }` | additive; the two hand constructions in `SecEdgarTimeseriesProvider.kt` compile unchanged |
| `valuation_core::numerics` | `robust_centre`, `RobustCentre` added; `robust_mean` re-expressed over the same implementation | additive; `robust_mean`'s behaviour unchanged |
| `valuation_core::evidence::AbsenceReason` | new variant `EstimatorUnavailable` | additive to a closed enum; one match arm (F9) |
| `valuation-core` FR-29 | an absent return on capital / return on equity now **refuses** instead of valuing at the neutral line | **intentional breaking spec change**; ADR + PRD + replacement tests |
| `CrossSectionDiagnostics` | `growth_pooled_discarded`, `growth_pairs_dropped` added | additive, `Default`-derived (F14) |

### 1.7 Assumptions

1. SEC companyfacts supplies `filed` for accepted 10-K facts in the overwhelming majority of
   cases. **This is an assumption, not a measurement** — Wave 1's T1.7 probe measures it over at
   least 5 real issuers before the wave is called done.
2. Nothing published moves from Waves 3 and 5 (F1). Anchor deltas are still measured and reported.
3. `core_driver_data_deep.json` is stale relative to policy `/8` and is **not** re-captured (brief
   section 2). Every offline diagnostic in this run therefore reflects pre-`/8` drivers, and
   **cannot show Wave 2's effect at all** (F19). Stated in every report that uses it.
4. Wave 4 writes every standing rule and index entry from this plan plus Wave 5's merged diff.
   It does not need Waves 1-3's diffs.
5. The baseline (section 4) is the brief's recorded figure. The orchestrator re-establishes it
   before Round 1 and any wave measures deltas against that re-established run, not against the
   brief.

### 1.8 Risks

| # | Risk | Severity | Mitigation |
| --- | --- | --- | --- |
| R1 | **Wave 1 perturbs live inputs.** Its fail-closed rules change what `extract_driver_annual` returns from live companyfacts. v0 said "expected delta exactly zero; any non-zero delta is a defect" — that was fixture evidence making a live claim. | High | T1.7's probe **counts** accepted 10-K facts lacking `filed` or carrying an unparseable `end` over at least 5 real issuers. The rule becomes: *any non-zero delta must be explained by that count, or it is a defect.* If the count is non-zero, Wave 1 is coverage-reducing on the production FCFF path and inherits the full automated gate and the per-issuer report. |
| R2 | **Wave 2 could move a published valuation.** | High, then measured | Section 0's chain says it cannot, and T2.6 **proves** it with a test rather than asserting it. If the proof fails, live QA fires in full and the wave stops. |
| R3 | Wave 1 and Wave 2 both perturb live inputs; landing them together makes an unexpected delta unattributable. | Certain | Different rounds (P0-G). Section 6.1. |
| R4 | Wave 3's `standardize` refuses a *nearly*-flat growth history (more than half the years identical, so MAD is zero) where the naked mean published. | Medium | Exactly-flat already refuses today (`sample_variance` is zero, so the observation is absent), so only the near-flat case changes. T3.7 counts and reports how many of the 28 cohort issuers change state. |
| R5 | Wave 3 changes the pooled centre **and** the pair set, therefore `persistence` (today `0.1709`), therefore the fade rate for every issuer. | Medium | Required diagnostic: old vs new `growth_persistence`, `fade_per_year`, `growth_pooled_discarded`, `growth_pairs_dropped`. Nothing published (F1). |
| R6 | Wave 2 must edit `edgar.rs`, which Wave 1 restructures. | Certain | Serial rounds; Wave 2 rebases on merged Wave 1 and confirms `extract_annual_any_with_shape`'s post-Wave-1 shape before editing. Section 6.1. |
| R7 | Waves 3 and 5 both want `valuation_core_adapter.rs`. | Certain | Different rounds (R1 and R3). |
| R8 | Four waves would want `docs/index.md` and `AGENTS.md`. | Certain | Single ownership: Wave 4, in the last round, writes every entry by the filenames fixed in this plan. |
| R9 | Wave 5's `reason` column cells are planner-derived from reading the code and **not verified by running cucumber**. | Medium | T5.4 supplies every cell literally, plus fast Rust unit tests asserting the reason for each *guard* (not each row) so a wrong derivation surfaces in the builder's 10-second loop. The rule for a mismatch is stated in T5.4. |
| R10 | Wave 2 accidentally repairs one of the three known-failing tests. | Medium | Report it; do not revert. An honest coverage change is reported to Juan, never hidden and never patched away. |

---

## 2. Waves

### 2.0 File-ownership matrix

| File | W1 | W2 | W3 | W4 | W5 |
| --- | :-: | :-: | :-: | :-: | :-: |
| `apps/windows/src-tauri/src/edgar.rs` | R1 | R2 (narrow) | | | |
| `apps/windows/src-tauri/src/valuation_probes.rs` | R1 | | | | |
| `docs/sec-point-in-time-provenance.md` (new) | R1 | | | | |
| `shared/contracts/sec-driver-normalization.json` | | R2 | | | |
| `shared/contracts/sec-driver-normalization-fixtures.json` | | R2 | | | |
| `scripts/generate-sec-driver-normalization-policy.ps1` | | R2 | | | |
| `apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs` | | R2 | | | |
| `apps/android/core/.../SecDriverNormalizationPolicyGenerated.kt` | | R2 | | | |
| `apps/android/core/.../SecDriverNormalizationPolicy.kt` | | R2 | | | |
| `apps/android/app/.../SecEdgarTimeseriesProvider.kt` | | R2 | | | |
| `apps/android/core/src/test/.../SecDriverNormalizationPolicyTest.kt` | | R2 | | | |
| `apps/windows/src-tauri/src/sec_normalization.rs` | | R2 | | | |
| `apps/windows/src-tauri/src/dcf_model.rs` — **test module only** | | R2 | | | |
| `shared/contracts/README.md` | | R2 | | | |
| `_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md` | | R2 | | | |
| `apps/windows/src-tauri/valuation-core/src/numerics.rs` | | | R1 | | |
| `apps/windows/src-tauri/valuation-core/src/lib.rs` | | | R1 | | R3 |
| `apps/windows/src-tauri/src/valuation_core_adapter.rs` | | | R1 | | R3 |
| `docs/valuation-aggregation-audit.md` (new) | | | R1 | | |
| `docs/valuation-economic-contract.md` (new) | | | | R4 | |
| `docs/roic-research-charter.md` (new) | | | | R4 | |
| `docs/roic-preregistration.md` (new) | | | | R4 | |
| `docs/growth-research-charter.md` (new) | | | | R4 | |
| `docs/index.md` | | | | R4 | |
| `AGENTS.md` | | | | R4 | |
| `apps/windows/src-tauri/valuation-core/src/evidence.rs` | | | | | R3 |
| `apps/windows/src-tauri/valuation-core/src/projection.rs` | | | | | R3 |
| `apps/windows/src-tauri/valuation-core/src/residual_income.rs` | | | | | R3 |
| `.../valuation-core/tests/features/intrinsic-value.feature` | | | | | R3 |
| `.../valuation-core/tests/features/residual-income.feature` | | | | | R3 |
| `.../valuation-core/tests/features/manifest.toml` | | | | | R3 |
| `.../valuation-core/tests/cucumber.rs` | | | | | R3 |
| `apps/windows/src-tauri/src/valuation_core_measurement.rs` | | | | | R3 |
| `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md` (new) | | | | | R3 |
| `_bmad-output/.../prd-discount_screener-2026-08-03/prd.md` | | | | | R3 |
| `_bmad-output/.../prd-discount_screener-2026-08-03/addendum.md` | | | | | R3 |

`lib.rs` and `valuation_core_adapter.rs` appear twice — in **different rounds**, never
concurrently. `edgar.rs` likewise: Wave 1 owns it in R1, Wave 2 touches a named narrow region of
it in R2.

**Files no wave may touch, at all:** `src/operating_valuation.rs`, `src/valuation_baseline.rs`,
`src/valuation_high_signal.rs`, `src/driver_resolution.rs`, `src/dcf_model.rs` outside its
`#[cfg(test)] mod tests`, `tests/fixtures/valuation/*`, `_bmad-output/**/.memlog.md`,
`_bmad-output/project-context.md`.

---

### Wave 1 — Point-in-time evidence with vintages retained (Round 1)

| Field | Content |
| --- | --- |
| **Wave id** | `wave-1` — **Round 1** |
| **Title** | The annual observation stops discarding what it already knows, and can be read as of a date |
| **Scope** | `apps/windows/src-tauri/src/edgar.rs`, one probe in `valuation_probes.rs`, one new doc |
| **Dependencies on other waves** | none |
| **External facts relied on** | `SecFact` (`sec_normalization.rs:36`) is read-only for this wave and all its fields are `pub`; `AnnualValue` never leaves `edgar.rs` (F3) |

#### Tasks

**T1.1 — `IsoDate` exists and no date is ever a string again inside the extractor.**
Add `IsoDate` per D1 with a strict `parse` (four-digit year, calendar-valid month and day) and
derived `Ord`. Replace every date **comparison** in `annual_candidates_with_shape` — the
`candidate.filed > existing.filed` precedence and the `candidate.end > existing.end` fiscal-year
rule — with `IsoDate` comparisons.
*Acceptance:* no `String` comparison decides precedence in `annual_candidates_with_shape`;
`IsoDate::parse` rejects `"2024"`, `"2024-13-01"` and the empty string, in tests.

**T1.2 — One fiscal-year derivation, four slicing sites removed.**
Add `IsoDate::year()` and route the four sites through it: `annual_candidates_with_shape`
(`:204`), `extract_annual_percent_any` (`:417`), `extract_recurring_development` (`:495`),
`extract_acquisition_investments` (`:516`). Delete the `entry["fy"]` fallback.
*Acceptance:* a search for `get(..4)` in `src/edgar.rs` returns **zero** hits — note this is
**four** sites, not the three v0 named. `entry["fy"]` is no longer read anywhere in `edgar.rs`.

**T1.3 — Vintages are retained and resolvable as of a cutoff.**
Add `AnnualObservation`, `AnnualSeries` and
`pub fn extract_driver_vintages(facts, driver) -> AnnualSeries`. `AnnualSeries::as_of(cutoff)`
applies the existing precedence (consolidated over segment, then latest `filed`, then latest
`accession`) **restricted to `filed < cutoff`**, then the existing
one-observation-per-fiscal-year rule. `AnnualSeries::latest()` is `as_of` with no upper bound.
`extract_driver_annual` is re-expressed as `extract_driver_vintages(..).latest()` so there is
**one** resolution implementation.
*Acceptance:* removing the `filed <` filter makes the cutoff test fail; there is exactly one
place in `edgar.rs` that decides which candidate wins an `end`.

**T1.4 — Every production construction site carries real provenance.**
All nine sites, named by the function they live in:

- `annual_candidates_with_shape` (leaf) — from the winning candidate, now also carrying
  `accession` from `entry["accn"]`, plus `taxonomy` and `unit` from the extraction parameters.
- `extract_annual_any_with_shape` — the winning qname's provenance; a gap-filled year keeps the
  **filling** qname's provenance, which is what makes the merge auditable.
- `extract_total_debt` — composition of the current and non-current sources, or the
  reported-total source when it overrides. `mixed_vintage` is computed, not assumed.
- `extract_annual_percent_any` — leaf; `unit` is the filed unit string (`"pure"`), and
  `value_dollars` stores the filed integer (D1 and I6).
- `merge_capex_by_year` — the winning (largest-absolute) series' provenance.
- `extract_recurring_development` — from `evidence.development_total_by_end`; the year via
  `IsoDate::parse(end)?.year()`. Where the underlying ledger entries are reachable, `sources`
  lists them; where they are not, `sources` is taken from the entries in `evidence.ledger` whose
  `end` matches. **Wave 1 may not edit `sec_normalization.rs` to make this nicer** (I6); if
  neither mechanism reaches the entries, `sources` is empty, `known_from` comes from the matching
  ledger entries, and the limitation is written into the doc. *(This is the one mechanism choice
  left to the builder. Both answers are acceptable; whichever is taken is recorded in the doc. It
  is a mechanism choice, not a product decision.)*
- `extract_acquisition_investments` — directly from `entry.fact`, which is already a `SecFact`.
- `fcf_history` — composition of the OCF and CapEx sources for that year; `known_from` is the
  later of the two; `mixed_vintage` is true whenever they differ.
- `fetch_dcf` — reconstructs `AnnualValue` from `FcfPoint`, which carries no provenance, so this
  site **cannot** build a valid `AnnualProvenance`. Change the private `compute_dcf` (defined at
  `edgar.rs:681`, called at `:1185` — **not `:1180`, which is the reconstruction site inside
  `fetch_dcf`**) to take the year and value pairs it actually uses, so no fabricated provenance
  is created. `compute_dcf` is private to `edgar.rs`; nothing outside is affected.

*Acceptance:* the crate compiles and no `AnnualProvenance` anywhere is built from a literal, a
default, or an empty date.

**T1.5 — Test construction sites use one helper.**
Add a single `#[cfg(test)] fn annual(year: i32, value_dollars: i64, filed: &str) -> AnnualValue`
and route all 22 test sites through it.
*Acceptance:* `mod tests` contains exactly one place that constructs an `AnnualProvenance`.

**T1.6 — Fail-closed, and pinned.**
A fact whose `filed` is missing or unparseable, or whose `end` will not parse, produces no
`AnnualValue`. The `unwrap_or("")` at `edgar.rs:196` is removed.
*Acceptance:* W1-N01 and W1-N02 below.

**T1.7 — Measure the live cost of fail-closed (probe).**
Add `probe_facts_without_a_filing_date` to `valuation_probes.rs`, following
`probe_growth_persistence_rho1` structurally: `#[ignore = "network: SEC filing-date coverage
probe; diagnostic only"]`, prints a table, **asserts nothing**. Over **at least 5 real issuers**
(`AGENTS.md`'s external-provider rule), count per issuer and per driver: accepted 10-K facts with
no `filed`, and accepted facts whose `end` will not parse. The orchestrator runs it.
*Acceptance:* the probe exists, is `#[ignore]`, and asserts nothing. Its **output** is a Wave 1
exit condition, recorded in `docs/sec-point-in-time-provenance.md`.

**T1.8 — Documentation.** `docs/sec-point-in-time-provenance.md`: the three questions PIT must
answer; vintages versus `known_from` and why both exist; `as_of`'s **strict** `filed < cutoff`
boundary and why an inclusive bound is a one-day leak repeated at every cutoff; `mixed_vintage`
and why compositions are marked rather than forbidden; **why `fy` and `fp` are deliberately not
retained** (the NVDA trap at `edgar.rs:197-203`; `accession` identifies the filing exactly); the
fiscal-year semantics decision and its named limitation (fiscal-year-end changes); the
fail-closed rules **with T1.7's measured count**; and the named boundary — `dcf_model::FcfPoint`
stays provenance-free, the PIT-capable API is `fetch_company_facts` + `extract_driver_vintages`,
and extending `FcfPoint` is item 6's work.

#### Invariants

- **I1** For every `AnnualValue`, `provenance.known_from == max(provenance.sources.filed)` and it
  is a parsed `IsoDate`, never an empty or defaulted one.
- **I2** `AnnualValue::year == provenance.end.year()`.
- **I3** No code path derives a year from anything but a parsed period `end`.
- **I4** A fact without a parseable `filed`, or with an unparseable `end`, produces no
  `AnnualValue`.
- **I5** `as_of(cutoff)` admits only observations with `filed < cutoff`, strictly.
- **I6** Wave 1 does not modify `sec_normalization.rs`.
- **I7** The numeric series produced for every existing committed fixture is unchanged.

#### Test methodology — BDD scenarios

| id | type | actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W1-P01 | positive | extractor | a 10-K fact, `end` 2024-12-31, `filed` 2025-02-14, `accn` 0000320193-25-000008 | `extract_driver_annual` runs | the observation's `known_from` is 2025-02-14 and its single source names that accession | leaf provenance |
| W1-P02 | positive | extractor | OCF filed 2025-02-14 and CapEx filed 2025-05-01 for one fiscal year | `fcf_history` runs | `known_from` is 2025-05-01 and `mixed_vintage` is true | composition |
| W1-P03 | positive | extractor | current and non-current debt filed under two concepts for one year | `extract_total_debt` runs | the observation names **both** source facts | audit trail |
| W1-P04 | positive | extractor | a development total keyed by `end` 2023-09-30 | `extract_recurring_development` runs | the fiscal year is 2023 and `provenance.end` is 2023-09-30 | the `:495` fix |
| W1-P05 | positive | extractor | a rejected-acquisition ledger entry | `extract_acquisition_investments` runs | the observation carries the ledger fact's qname and accession | the `:516` fix |
| W1-P06 | positive | extractor | a percent fact with `end` 2024-12-31 | `extract_annual_percent_any` runs | the year is 2024 and the unit recorded is the filed unit, not `USD` | the `:417` fix v0 missed |
| W1-N01 | negative | extractor | a 10-K fact with no `filed` field | extraction runs | that fact produces no annual value | fail-closed; an empty date is a fabricated availability |
| W1-N02 | negative | extractor | a fact whose `end` will not parse, but which carries `fy` | extraction runs | that fact produces no annual value | the `fy` fallback is gone |
| W1-N03 | negative | extractor | an observation filed exactly on the cutoff date | `as_of(cutoff)` runs | it is **excluded** | I5, strictly before |
| W1-E01 | edge | extractor | two facts for the same `end`, filed 2024-02-01 and 2025-02-01, with different values | `as_of(2024-06-01)` and `as_of(2025-06-01)` run | the two cutoffs return **different values** | the PIT property itself; `max(filed)` alone cannot express this |
| W1-E02 | edge | extractor | a CapEx hole interpolated between neighbours filed 2023-03-01 and 2025-03-01 | `fcf_history` runs | the interpolated year's `known_from` is 2025-03-01 | an imputed value is knowable no earlier than its last input |
| W1-E03 | edge | extractor | an issuer with no facts at all for a driver | extraction runs | an empty series, not a zero-valued observation | constraint 5 |
| W1-E04 | edge | extractor | an issuer with a 2025-02-01 fiscal close | extraction runs | the year is **2025** and `provenance.end` is 2025-02-01 | the fiscal-year semantics decision, pinned |
| W1-E05 | edge | extractor | an issuer filing two period ends inside one calendar year (fiscal-year-end change) | extraction runs | the later `end` wins that year and the earlier is dropped | status quo, now pinned; named as a limitation in the doc |
| W1-B01 | boundary | extractor | facts filed one day before, on, and one day after the cutoff | `as_of(cutoff)` runs | exactly the one filed one day before is admitted | the boundary itself, both sides |
| W1-R01 | regression | extractor | the committed WDC separation fixture (`separation_facts`) | extraction runs | the value series is identical to before this wave | I7 |
| W1-R02 | regression | extractor | a re-filed consolidated fact superseding a segment fact | extraction runs | consolidated still wins over segment, then latest-filed wins | `annual_candidates_with_shape` precedence unchanged |

*One assert per test. Where two properties must hold, use the collected-violations pattern already
in the codebase (`projection.rs:512-523`): a single `assert!` over a `Vec` of offenders.*

#### Automation level

Unit tests in `edgar.rs`'s `#[cfg(test)] mod tests`, driven by inline JSON fixtures in the style
already used there. No integration or e2e work in this wave. One `#[ignore]` network probe (T1.7)
that the orchestrator runs.

#### Fast checks (builder runs; about 10 seconds each)

| Level | Command (from `apps/windows/src-tauri`) |
| --- | --- |
| unit | `cargo test --lib edgar::` |
| lint | `cargo fmt -- --check` |
| grep | `get(..4)` in `src/edgar.rs` returns 0 hits; `entry["fy"]` returns 0 hits |
| scope | `git diff --name-only` shows exactly the three files this wave owns |

#### Deferred checks (orchestrator runs)

- `cargo test --lib` — full Shell suite; the failing set must still be exactly the three named
  under "Exit criteria".
- **Automated gate, mandatory** (`AGENTS.md` manual procedures, triggered by a change to the
  CapEx-to-FCF path): `cargo test --lib dcf_model::` and `cargo test --lib valuation_baseline::`.
- `cargo test --lib probe_facts_without_a_filing_date -- --ignored --nocapture` (network) — T1.7.
- **Per-issuer report**, not just anchors: PG, GOOGL, AMZN, MSFT **and** the 26-name high-signal
  cohort — driver-year counts before and after. Running `valuation_high_signal` rewrites
  `apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json`;
  that test is one of the three known-failing ones, so **read its output as a table, not as
  pass/fail**, and leave the fixture **unstaged** (constraint 8).
- **Attribution rule:** any non-zero anchor or cohort delta **must be explained by T1.7's count**,
  or it is a defect in this wave. This replaces v0's "expected delta exactly zero", which was
  fixture evidence making a live claim.
- **Pause triggers:** an anchor moving more than plus or minus 5 percent, or changing side of a
  gate, is brief trigger (b) — stop and ask. An operating issuer going from valued to unavailable
  is trigger (c) — stop and ask.

#### Evidence of pass

The builder reports: the names of the new tests (W1-P01 through W1-R02 mapped to test function
names), the `cargo test --lib edgar::` summary line, the two grep counts, and the
`git diff --name-only` output. The orchestrator additionally reports T1.7's table and the
per-issuer driver-year comparison.

#### Documentation deliverables

`docs/sec-point-in-time-provenance.md` (new, content specified in T1.8). Module-level rustdoc on
`AnnualProvenance` and `AnnualSeries` stating the three questions and the strict cutoff. **No**
edits to `docs/index.md` or `AGENTS.md` — Wave 4 owns those and will add the index entry.

#### Done when

I1 through I7 hold and are tested. All 31 construction sites carry real provenance or no longer
exist. Zero year-from-string-slice sites remain (four removed, not three). T1.7's probe has been
run by the orchestrator and its count is written into the doc. `docs/sec-point-in-time-provenance.md`
is committed. The Shell failing set is still exactly, by name:

- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

None repaired by accident, none added, and none removed from the run.

---

### Wave 2 — One measurement basis for the interest-expense class (Round 2)

| Field | Content |
| --- | --- |
| **Wave id** | `wave-2` — **Round 2** |
| **Title** | A netted concept enters an equivalence class through a declared sign convention, or not at all |
| **Scope** | the driver-normalization contract, its generator, both generated targets, the hand-written Kotlin policy and its one consumer, the Rust extraction site, the fingerprint sites, and the contract documentation |
| **Blocked by** | **Q1 (section 0).** This wave does not start until Juan answers. |
| **Dependencies on other waves** | Round 1 must be merged (section 6.1 gives the semantic reason) |

#### What this wave is not

It is **not** a deletion. v0 deleted `InterestIncomeExpenseNet` and
`InterestIncomeExpenseNonoperatingNet`. That is replaced by the declarative sign convention of D6,
for the reasons measured in section 0. No qname is removed from any equivalence class in this
wave.

#### Tasks

**T2.1 — The contract declares the convention.**
In `shared/contracts/sec-driver-normalization.json`, on `drivers.interestExpense`, add
`negatedQnames` (the two net concepts, exactly as spelled in `qnames`) and `signRationale` (the
text in D6, carrying the LIN 2022-2025 and BAC 2025 measurements as the evidence). Extend the
existing `interestExpense.rationale` so it states **R1 and R2 as two distinct rules** (D6) rather
than one blurred one. Bump `fingerprint` to `sec-driver-normalization/9`.
*Acceptance:* `negatedQnames` is a subset of `qnames`; the JSON parses; the rationale names both
rules and both are attributed (R1 to the `InterestPaidNet` removal, R2 to this change).

**T2.2 — The generator carries the sign, positionally.**
In `scripts/generate-sec-driver-normalization-policy.ps1`:

- add a helper that maps a driver to an integer array of the same length and order as its
  `qnames`, minus one where the qname is in `negatedQnames` and plus one otherwise. A driver with
  no `negatedQnames` key yields all ones, so **every** driver emits a full, non-empty array;
- emit it from **both** `RustOperator` (as `qname_signs: &'static [i8]`) and `KotlinOperator` (as
  `qnameSigns = listOf(...)`), and add the field to the emitted `pub struct DriverOperator`
  (script lines 115-120) and the emitted `internal data class GeneratedSecDriverOperator`
  (script lines 44-49);
- **DRY:** do not write a second slice formatter. Give the existing `RustSlice` and `KotlinList`
  an opt-out of quoting (a switch parameter) rather than duplicating them. The compaction rule
  inside `RustSlice` (one element, or no nesting and at most two) stays untouched;
- the generator keeps the structural property that is the reason it exists (its header comment,
  script lines 4-8): every constant is emitted by **iterating** the contract. Do not name
  `interestExpense` anywhere in the script.

*Acceptance:* running the generator with `-OutputRoot <scratch>` produces two files that differ
from the committed ones **only** by the new field and the fingerprint. Rerunning the generator
over its own output is a fixed point.

**T2.3 — Regenerate both targets and reconcile with the formatter.**
Run the generator for real; commit
`apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs` and
`apps/android/core/src/main/kotlin/com/discountscreener/core/engine/SecDriverNormalizationPolicyGenerated.kt`.
**Trap, and it is an acceptance criterion:** the generated Rust is subject to
`cargo fmt -- --check` while the generator's `-Check` mode compares it byte-for-byte against the
generator's output. If `cargo fmt` rewrites the generated file, the *generator's emission* must be
adjusted until generated output and formatted output are identical. Never hand-edit a generated
file to settle this.
*Acceptance:* `pwsh scripts/validate-contracts.ps1` passes **and** `cargo fmt -- --check` passes,
with no manual edit to either generated file.

**T2.4 — The Rust extraction applies the sign.**
`edgar.rs`. Introduce
`fn extract_annual_equivalents(facts, qnames, qname_signs, shape, unit) -> Vec<AnnualValue>`
carrying the merge logic that `extract_annual_any_with_shape` holds today (declared-order
gap-fill through `by_year.entry(year).or_insert(..)`), multiplying each admitted value by its
qname's sign. It **asserts** that the signs and the qnames have equal length. Exactly two callers:

- `extract_driver_annual`, passing `driver.qname_signs`;
- `extract_annual_any_with_shape`, passing a locally built all-ones array, with a comment saying
  the non-driver path has no contract behind it and therefore no declared convention. **No silent
  default** — the all-ones array is written at the call site, in the open.

The declared-order semantics of `select_one_equivalent` are unchanged; only the value admitted
for a filled gap changes.
*Acceptance:* no value-conditional branch and no issuer identifier appears anywhere in the new
function; a test asserts that a driver whose signs disagree in length with its qnames panics.

**T2.5 — The Kotlin path applies the same sign.**

- `SecDriverNormalizationPolicy.kt`: the hand-written `DriverOperator` gains
  `val qnameSigns: List<Int> = List(qnames.size) { 1 }` — a default expressed in terms of the
  earlier parameter, so the two direct constructions in `SecEdgarTimeseriesProvider.kt` compile
  unchanged and are still exactly right. Add an `init` block requiring the two lists to have
  equal size. `fun operator(driver: Driver)` passes `qnameSigns` through from the generated
  operator.
- `SecEdgarTimeseriesProvider.kt`: `annualFyRecordsAny` iterates `operator.qnames` with
  `putIfAbsent` — the exact mirror of the Rust gap-fill (F21). It must multiply by the sign at
  the position of the qname being admitted. Iterate with an index rather than doing a second
  lookup.

*Acceptance:* the Rust and Kotlin merges agree on the same input; a Kotlin test in
`SecDriverNormalizationPolicyTest.kt` asserts that the interest-expense operator's signs are
negative at exactly the positions where `qnames` holds a net concept, by **looking the index up
from `qnames`**, never by hard-coding a position.

**T2.6 — PROVE the legacy published path is sign-blind. This is the wave's central evidence.**
Section 0 argues from the call chain that a normalization-layer sign change cannot move a
published number, because `dcf_model.rs:907` applies `.map(f64::abs)`. Wave 2 does not assert
that; it proves it, with two tests added to `dcf_model.rs`'s existing `#[cfg(test)] mod tests`.
**Wave 2 may add tests to that module and may not change a single non-test line of that file.**

1. `an_interest_series_reaches_the_fcff_bridge_with_its_sign_removed` — build two `FcfPoint`s
   through `FcfPoint::with_operating_drivers`, identical except that one is given a positive
   interest and the other its exact negation; assert the two points' `interest_expense_dollars`
   are equal. This characterizes the `.abs()` exactly where it lives.
2. `the_reported_fcff_bridge_is_identical_under_a_negated_interest_series` — run the FCFF bridge
   over a small multi-year history built both ways and assert the two resulting series are equal.

**This proof discharges the live-QA obligation for this wave** (directives section 0). If either
test fails, the invariance claim is false, the wave stops, and live QA fires in full.
*Acceptance:* both tests exist, pass, and are named in the wave's evidence. The diff of
`src/dcf_model.rs` contains only additions inside `mod tests`.

**T2.7 — Do not delete the negative-interest guard, and describe it correctly.**
`driver_resolution.rs:117-119` rejects a negative interest. It is dead **on the production path
only**; `driver_resolution.rs`'s own `#[cfg(test)]` `fn point(..)` helper at `:326` builds an
`FcfPoint` by literal and can still reach it. Wave 2 does not touch `driver_resolution.rs`, and
every statement about that guard carries the qualification.
*Acceptance:* `driver_resolution.rs` is absent from `git diff --name-only`.

**T2.8 — Fingerprint, all five sites.**
`sec-driver-normalization/8` becomes `/9` in exactly these five places (verified by search):

| File | Line |
| --- | --- |
| `shared/contracts/sec-driver-normalization.json` | 4 |
| `shared/contracts/sec-driver-normalization-fixtures.json` | 3 |
| `apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs` | 3 |
| `apps/windows/src-tauri/src/sec_normalization.rs`, inside `generated_contract_policy_is_the_category_source` | 344 |
| `apps/android/core/.../SecDriverNormalizationPolicyGenerated.kt` | 12 |

Two of the five are generated and must come from T2.3, never from a hand edit.
*Acceptance:* a repository search for `sec-driver-normalization/8` returns hits only in
`_bmad-output/**/.memlog.md`, which is append-only history and is never edited.

**T2.9 — A fixture case for the new rule.**
`sec-driver-normalization-fixtures.json` today carries investment-category cases only. Add at
least one interest-driver case built from the LIN figures in section 0: one fiscal year present
under both a positive gross concept and a negative net concept, expecting the class to yield one
positive expense; and one year present **only** under the net concept, expecting the negation to
be applied. Record in T2.10's documentation that this corpus is read by **Rust only** (F20) —
Kotlin's half of the dual-lock for this contract runs through the generated policy, and T2.5's
Kotlin test is what closes it.
*Acceptance:* `frozen_real_sec_fixture_corpus_executes_at_the_normalization_boundary`
(`sec_normalization.rs:397`) exercises the new cases and passes.

**T2.10 — Documentation.**

- `shared/contracts/README.md`: add `sec-driver-normalization.json` and
  `sec-driver-normalization-fixtures.json` to the `## Files` list — they are **absent** today
  (F16) — and state **R1 and R2 as two rules**, each with the example it rests on.
- `_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md`: record the `/9` bump,
  the new contract vocabulary, and the sign convention's derivation from measured filings.
- The generator's header comment gains one line: signs are positional and parallel to `qnames`,
  which is why an empty array can never be emitted.

#### Invariants

- **J1** For every driver, the sign array and the qname array have equal length, on both
  platforms.
- **J2** Every sign is a static constant of the contract. No sign is derived from a filed value
  and no sign is derived from an issuer identifier — anywhere, on either platform.
- **J3** `select_one_equivalent`'s declared-order gap-fill semantics are unchanged; only the
  value admitted for a filled gap changes.
- **J4** The two generated files are byte-identical to a fresh generator run, and both are
  formatter-clean.
- **J5** No qname is removed from any equivalence class in this wave.
- **J6** `src/dcf_model.rs` changes only inside `#[cfg(test)] mod tests`.
- **J7** The published FCFF bridge is invariant to the sign of the interest series — **proved by
  T2.6**, not assumed.

#### Test methodology — BDD scenarios

| id | type | actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W2-P01 | positive | normalizer | a year present only under `InterestIncomeExpenseNet`, filed as −256M | the interest-expense class resolves | the class yields +256M | the LIN case, the whole point |
| W2-P02 | positive | normalizer | a year present under `InterestExpenseNonoperating` at +256M and under the net concept at −256M | the class resolves | the class yields +256M once, from the earlier declared qname | declared order still decides; the sign does not change who wins |
| W2-P03 | positive | generator | the contract with `negatedQnames` on one driver | the generator runs | every driver emits a sign array of its own qname length, all ones except the two declared | positional, never empty |
| W2-P04 | positive | Kotlin policy | the generated interest-expense operator | the policy's `operator` is read | the signs are negative exactly at the indices of the two net concepts, located by lookup | cross-platform parity |
| W2-P05 | positive | Kotlin provider | one year present only under a negated qname | `annualFyRecordsAny` runs | the record's value is negated, matching Rust on the same input | F21 |
| W2-N01 | negative | generator | a `negatedQnames` entry that is not in that driver's `qnames` | the generator or `validate-contracts` runs | it fails loudly, naming the driver and the qname | a typo must not silently do nothing |
| W2-N02 | negative | normalizer | a driver whose sign array length disagrees with its qname count | extraction runs | it panics rather than silently truncating or defaulting | J1 |
| W2-N03 | negative | reviewer | the diff | review | no ticker, CIK or issuer name appears in the diff | constraint 1 |
| W2-E01 | edge | generator | a driver with no `negatedQnames` key at all (ten of the eleven drivers) | the generator runs | an all-ones array of the right length, syntactically valid on both platforms | the empty-collection formatter trap that motivated the positional design |
| W2-E02 | edge | normalizer | a year present under a negated qname with value zero | the class resolves | zero, with no sign artefact | negating zero is still zero |
| W2-E03 | edge | contract reader | the `/9` contract | the fixture corpus test runs | the fixture's `policyFingerprint` matches `POLICY_FINGERPRINT` | the check at `sec_normalization.rs:403` |
| W2-R01 | regression | FCFF bridge | an interest series and its exact negation | the bridge runs on both | the published series are identical | J7, T2.6 — the invariance proof |
| W2-R02 | regression | normalizer | `InterestPaidNet` | the class resolves | it is still **not** in the class | R1 is not weakened by R2 |
| W2-R03 | regression | contract validator | the regenerated targets | `validate-contracts.ps1` runs | no target is stale | J4; the drift the generator's header comment exists to prevent |

#### Automation level

Unit tests in `edgar.rs` and `sec_normalization.rs`; the frozen fixture corpus test; two
characterization tests in `dcf_model.rs`'s test module; one Kotlin unit test; the PowerShell
contract validator. No e2e work in this wave.

#### Fast checks (builder runs; about 10 seconds each)

| Level | Command |
| --- | --- |
| unit | `cargo test --lib sec_normalization::` and `cargo test --lib edgar::` |
| unit | `cargo test --lib dcf_model::` — includes T2.6's two proofs; this is also the `AGENTS.md` merge bar |
| contract | `pwsh scripts/validate-contracts.ps1` |
| generator | generate to a scratch `-OutputRoot` and diff against the committed targets |
| lint | `cargo fmt -- --check` |
| grep | `sec-driver-normalization/8` appears only under `_bmad-output/**/.memlog.md` |
| scope | `git diff --name-only` matches the ownership matrix; `driver_resolution.rs` is absent |

#### Deferred checks (orchestrator runs)

- `cargo test --lib` — full Shell suite.
- The Android unit tests (Gradle), for T2.5's Kotlin assertion.
- **Live**, over the four issuers named in section 0 — LIN, DAL, CHTR, BKR — plus BAC as the
  financial control: interest-expense driver-years and values before and after. The expectation
  is *more* covered years with corrected signs, and **no change to any published intrinsic
  value**, per T2.6.
- **Live QA is discharged by T2.6's proof, not by a checklist run** (directives section 0) — but
  only if both proofs pass. If either fails, run the full `AGENTS.md` live valuation QA and stop.
- Anchors PG, GOOGL, AMZN, MSFT: report the numbers even though the proof says they cannot move.
  A moving anchor here means T2.6 is wrong, which is a stop-and-ask.
- `core_versus_current_engine_on_the_pinned_cohort` is **not** useful evidence for this wave: it
  reads the stale pre-`/8` `core_driver_data_deep.json` (F19) and structurally cannot show the
  sign effect. Do not cite it.

#### Evidence of pass

The names and results of T2.6's two proofs, pasted verbatim from the test output. The scratch-root
generator diff. The `validate-contracts.ps1` output. The Kotlin test name. The
`sec-driver-normalization/8` search result. `git diff --name-only`.

#### Documentation deliverables

`shared/contracts/README.md` (`## Files` entries plus R1/R2 as two rules),
`_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md` (the `/9` record), and
the generator header line. **No** edits to `docs/index.md` or `AGENTS.md` — Wave 4 owns those and
will add the anti-pattern row and the fingerprint-bump procedure step.

#### Done when

J1 through J7 hold. Q1 has been answered by Juan. The `/9` bump is in all five sites and two of
them came from the generator. T2.6's proofs pass and are quoted in the completion statement. The
Shell failing set is still exactly, by name:

- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

If one of those three now passes, **report it and do not revert it** (R10).

---

### Wave 3 — One robust aggregation primitive, and a fit that honours it (Round 1)

| Field | Content |
| --- | --- |
| **Wave id** | `wave-3` — **Round 1** |
| **Title** | The growth channel stops averaging naked, and stops feeding back the observations it just discarded |
| **Scope** | `valuation-core/src/numerics.rs`, `valuation-core/src/lib.rs` (re-export only), `valuation_core_adapter.rs`, one new doc |
| **Dependencies on other waves** | none. File-disjoint from Wave 1. |

#### Tasks

**T3.1 — `RobustCentre` and `robust_centre` exist, over one trimming implementation.**
Per D2. Add `RobustCentre` with `centre`, `variance_of_centre`, `retained`, `discarded` and
`outliers`; add `pub fn robust_centre(sample: &[f64]) -> Result<RobustCentre, AbsenceReason>`
with **no threshold parameter**. Implement one crate-private `trimmed(sample, max_absolute_z)`
over the existing `standardize`; re-express `robust_mean` as
`trimmed(sample, max_absolute_z).map(RobustCentre::centre)`.
*Acceptance:* exactly one place in the workspace filters a sample by z-score; `robust_mean`'s
public signature and behaviour are unchanged; a test asserts
`robust_mean(s, MAX_ABSOLUTE_Z) == robust_centre(s).centre()` on the existing `CONTAMINATED`
fixture.

**T3.2 — `variance_of_centre` is the squared standard error of the retained sample.**
The retained sample's variance divided by the retained count. The rustdoc says, in the doc
comment itself: this is the width of the **centre**, not of the sample; it is computed over the
retained observations only; it therefore **understates** the estimator's uncertainty; and under
inverse-variance fusion an understated variance is an **overstated** weight.
*Acceptance:* the doc comment carries the direction of the bias in words, not just the formula.

**T3.3 — Degenerate retained counts are unreachable, and that is asserted.**
`trimmed` refuses with `AbsenceReason::InsufficientObservations` when fewer than three
observations survive; `standardize` already refuses `n < 3`, non-finite input and zero middle
spread. There is **no** special branch for a retained count of one or two — those states cannot
occur, and tests prove it rather than the code defending against it.
*Acceptance:* W3-E01, W3-E02, W3-E03 below.

**T3.4 — The pooled growth centre is robust, and the fit excludes what the centre excluded.**
`valuation_core_adapter::fit_growth_path` currently computes `mean(&series.flatten())` at `:280`
and de-means every consecutive pair by it at `:282-290`. Replace the pooled mean with
`robust_centre`. **Then apply the outlier decision to the pair set** (P1-2 RESOLUTION,
**exclude**): a discarded observation kills **both** pairs it participates in, and **no pair is
created across the resulting gap** — a pair spanning a hole is not an annual transition and
fabricating one would be a different defect. Read the excluded indices from
`RobustCentre::outliers()`; do not re-derive them.
*Acceptance:* a test with one planted extreme growth year asserts that the two pairs touching it
are absent from the fit and that no bridging pair was invented in their place.

**T3.5 — Count and report both kinds of loss.**
`CrossSectionDiagnostics` gains `growth_pooled_discarded` (observations the centre excluded) and
`growth_pairs_dropped` (pairs that consequently left the fit). The two are different numbers and
both matter: the first says how contaminated the cross-section was, the second says how much fit
evidence that cost.
*Acceptance:* on a cohort with a planted outlier the two fields are non-zero and unequal.

**T3.6 — Audit every remaining averaging site and act on each.**
The complete, verified list (F13) — note that v0's table omitted three of these and mislabelled
two function-body lines as call sites:

| Site | What it averages | Disposition |
| --- | --- | --- |
| `:280` pooled growth mean | growth across the cross-section | **replaced** by `robust_centre` (T3.4) |
| `:295-296` through-origin persistence fit | de-meaned pair products and squares | **inherits** T3.4's exclusions; not separately trimmed — trimming a regression's own residuals is a different estimator and would need its own pre-registration |
| `:335` `fit_beta_dispersion` | variance of betas | **kept**, with a written reason: betas are a bounded, already-shrunk quantity and this is a dispersion, not a location |
| `:485-491` residual scatter on n−2 df | regression residuals | **kept**; it is the fit's own residual scale, and replacing it would misstate the fit |
| `:536` trailing growth mean and variance | one issuer's own history | **kept**, with a written reason: this is an issuer's own short series, where a robust centre needs `n>=3` retained and would refuse far more often than it would help; the guard is the refusal path, not the trim |
| `:631` leverage sample variance | cross-sectional leverage | **kept**, dispersion not location; recorded as a candidate for a later pass |
| `:637` coverage sample variance | cross-sectional coverage | as `:631` |
| `:781` `least_squares` centering | the regression's own centering | **kept**; internal to the estimator |

Every "kept" needs a written reason in the audit doc. "Kept" without a reason is not acceptable.
*Acceptance:* the audit doc's table has the same eight rows and every row has a reason.

**T3.7 — Measure the refusal-rate change, do not assume it.**
`standardize` refuses when the middle spread is zero. A *nearly*-flat growth history can now
refuse where the naked mean published (R4). Count, over the 28-name pinned cohort using the
offline `core_driver_data_deep.json` path, how many issuers change between resolved and refused,
and in which direction. State in the report that this fixture is **pre-`/8` and stale** (F19), so
the count is indicative of the arithmetic change only.
*Acceptance:* the number is in the wave's report, with the staleness caveat attached.

**T3.8 — Documentation.** `docs/valuation-aggregation-audit.md`: the aggregation rule as it stands
(`AGENTS.md`'s Aggregation section); `robust_centre` versus `robust_mean` and why only one of them
takes a threshold; **the `variance_of_centre` limitation with its direction and its "no effect
today, because the forward channel is always absent and `fuse` gives an absent channel zero
precision" note**; the exclude-versus-include decision for pairs, with the reasoning; T3.6's
eight-row table with every reason; T3.7's measured refusal-rate change with its staleness caveat;
and one named follow-up — `valuation_probes.rs:465-466` calls `robust_mean` and then `standardize`
again purely to recover a count `RobustCentre` now returns directly, and uses a fully-qualified
path against constraint 10. That file is **out of Wave 3's scope**; the doc records it.

#### Invariants

- **K1** Exactly one place in the workspace filters a sample by z-score.
- **K2** `MAX_ABSOLUTE_Z` is still `3.0` and no new call site passes any other value.
- **K3** `robust_centre` takes no threshold parameter.
- **K4** An observation excluded from the pooled centre is excluded from every pair in the
  persistence fit, and no pair is created across the resulting gap.
- **K5** A retained count below three refuses; a retained count of one or two is unreachable.
- **K6** `variance_of_centre` is documented as an understatement, with its direction named.
- **K7** `robust_mean`'s public signature and behaviour are unchanged.
- **K8** `valuation-core`'s dependency list is still empty (FR-1).

#### Test methodology — BDD scenarios

| id | type | actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W3-P01 | positive | numerics | the existing `CONTAMINATED` fixture (nine values near 10, one at 910) | `robust_centre` runs | the centre is the nine-value mean and `discarded` is 1, at index 9 | reuses the committed fixture |
| W3-P02 | positive | numerics | a clean nine-value sample | `robust_centre` runs | `discarded` is 0 and the centre equals the plain mean | no trimming without contamination |
| W3-P03 | positive | numerics | any sample | `robust_mean(s, MAX_ABSOLUTE_Z)` and `robust_centre(s).centre()` | they are equal | K7, and proof there is one implementation |
| W3-P04 | positive | adapter | a cross-section with one planted extreme growth year | `fit_growth_path` runs | `growth_pooled_discarded` is 1 and `growth_pairs_dropped` is 2 | K4, both counts, and they differ |
| W3-P05 | positive | adapter | an issuer whose extreme year is first or last in its series | `fit_growth_path` runs | exactly **one** pair is dropped, not two | a boundary observation touches one pair |
| W3-N01 | negative | numerics | a two-observation sample | `robust_centre` runs | it refuses with `InsufficientObservations` | K5 |
| W3-N02 | negative | numerics | a sample containing a non-finite value | `robust_centre` runs | it refuses rather than propagating NaN | inherited from `standardize` |
| W3-N03 | negative | reviewer | the diff | review | `MAX_ABSOLUTE_Z` is unchanged and no call site passes a different threshold | K2, constraint 6 |
| W3-E01 | edge | numerics | a sample whose middle spread is zero (more than half identical) | `robust_centre` runs | it refuses; it does not return a zero-variance centre | the R4 case |
| W3-E02 | edge | numerics | a five-observation sample where three survive | `robust_centre` runs | it resolves, with `retained` equal to 3 | the exact boundary of the refusal |
| W3-E03 | edge | numerics | a five-observation sample where two survive | `robust_centre` runs | it refuses | K5; an infinite-weight centre is unreachable |
| W3-E04 | edge | adapter | an issuer whose entire series is discarded | `fit_growth_path` runs | that issuer contributes no pairs and the fit still resolves from the others | partial failure |
| W3-E05 | edge | adapter | a two-year issuer whose only pair is broken by an exclusion | `fit_growth_path` runs | that issuer contributes nothing; no bridging pair is invented | K4, stated as its own case |
| W3-R01 | regression | numerics | every existing `numerics` test | `cargo test --lib numerics::` | all still pass, unmodified | K7 |
| W3-R02 | regression | adapter | a cohort with no contamination | `fit_growth_path` runs | `growth_persistence` is unchanged from before this wave | the change is confined to contaminated inputs |
| W3-R03 | regression | reviewer | `valuation-core/Cargo.toml` | review | the dependency list is still empty | K8, FR-1 |

#### Automation level

Unit tests in `valuation-core/src/numerics.rs` and in `valuation_core_adapter.rs`'s test module.
All offline. One offline diagnostic run for T3.7.

#### Fast checks (builder runs; about 10 seconds each)

| Level | Command |
| --- | --- |
| unit | `cargo test -p valuation-core` |
| unit | `cargo test --lib valuation_core_adapter::` |
| lint | `cargo fmt -- --check`, `cargo clippy -- -D warnings` |
| grep | `MAX_ABSOLUTE_Z` still `3.0`; no new caller passes a literal threshold |
| scope | `git diff --name-only` matches the ownership matrix |

#### Deferred checks (orchestrator runs)

- `cargo test --lib` — full Shell suite.
- `cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture` —
  offline, but slower than the builder budget; produces T3.7's count. **Its fixture is stale
  (F19)** and the report must say so.
- The old-versus-new diagnostic table: `growth_persistence` (today `0.1709`), `fade_per_year`,
  `growth_pooled_discarded`, `growth_pairs_dropped`, and the resolved/refused count over the
  28-name cohort.
- Anchors PG, GOOGL, AMZN, MSFT: expected unchanged, because the Core is not wired to production
  (F1). **Report the numbers anyway.** A moving anchor here would mean F1 is wrong, which is a
  stop-and-ask.

#### Evidence of pass

`cargo test -p valuation-core` and `cargo test --lib valuation_core_adapter::` summary lines; the
new test names mapped to W3-P01 through W3-R03; the eight-row audit table with its reasons; and,
from the orchestrator, the old-versus-new diagnostic table.

#### Documentation deliverables

`docs/valuation-aggregation-audit.md` (new, content specified in T3.8). Rustdoc on
`RobustCentre::variance_of_centre` carrying the limitation and its direction. **No** edits to
`docs/index.md` or `AGENTS.md` — Wave 4 owns those.

#### Done when

K1 through K8 hold and are tested. The audit doc's eight rows each carry a reason. T3.7's number
is measured and reported with its staleness caveat. The Shell failing set is still exactly, by
name:

- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

And the Core suite is still 89 library tests plus 7 schema tests, plus whatever this wave adds —
none removed, none ignored.

---

### Wave 5 — FR-29 removed; an absent return refuses, by a named reason (Round 3)

*Presented here in round order: Wave 5 runs in Round 3, Wave 4 in Round 4, because Wave 4
documents the contract Wave 5 changes.*

| Field | Content |
| --- | --- |
| **Wave id** | `wave-5` — **Round 3** |
| **Title** | The Core stops valuing what it cannot measure, and says which kind of absence it is |
| **Scope** | `valuation-core` (`evidence.rs`, `projection.rs`, `residual_income.rs`, `lib.rs`, both feature files, `manifest.toml`, `cucumber.rs`), `valuation_core_adapter.rs`, `valuation_core_measurement.rs`, one ADR, the PRD and its addendum |
| **Dependencies on other waves** | none in code. Runs in Round 3 so Wave 3's adapter edits are merged first (section 6.1). |

#### Tasks

**T5.1 — The new variant exists and reads correctly.**
`AbsenceReason::EstimatorUnavailable`, with the doc comment from D3, plus
`Self::EstimatorUnavailable => "estimator_unavailable"` in `as_str` — the **only** exhaustive
match on the enum in the workspace (F9). Re-export unchanged; `AbsenceReason` is already public.
*Acceptance:* the crate compiles with no other match arm added anywhere; the round-trip test in
`evidence.rs` covers the new variant.

**T5.2 — `intrinsic_value` refuses instead of substituting.**
Delete the FR-29 substitution in `projection.rs` (`:223`,
`return_on_capital_bps.value().copied().unwrap_or(discount)`) and replace it with a refusal
carrying `AbsenceReason::EstimatorUnavailable`. Per D3 the reason is **not** propagated from the
input observation: the Core's statement is about its own inability to value without a return
estimate, and that is true whatever the provider said. Rewrite the FR-29 rationale comment above
the site so it states the new rule and why the old one was wrong.
*Acceptance:* `unwrap_or` does not appear on the return-on-capital path in `projection.rs`; the
refusal's `detail()` is `"estimator_unavailable"`.

**T5.3 — `residual_income_value` refuses on the same rule.**
The same removal at `residual_income.rs:111` (`.unwrap_or(cost_of_equity)`), per D4.
*Acceptance:* as T5.2, in the residual-income module.

**T5.4 — The outlines gain a `reason` column and the two `return-absent` rows convert.**
Add `And the absence reason is <reason>` to both `Scenario Outline`s, a `reason` column to both
Examples tables, and a step in `cucumber.rs`:

```rust
#[then(expr = "the absence reason is {word}")]
fn then_absence_reason(world: &mut …, expected: String) {
    let actual = world.result().absence_reason().map(AbsenceReason::as_str).unwrap_or(ABSENT);
    assert_eq!(actual, expected);
}
```

A resolved row's reason is the reserved token `ABSENT` — the reason itself is absent, which is
exactly what that token is reserved for, and `schema.rs`'s
`absence_is_spelled_only_with_the_reserved_token` therefore stays satisfied. Adding one column to
two of the seven Examples tables is spec-legal because `every_examples_row_is_rectangular` checks
each table against **its own** header (F11).

`intrinsic-value.feature`, all 18 rows, `reason` cell:

| row | value | outcome | reason |
| --- | --- | --- | --- |
| `flat-path` | 1500.00 | resolved | `ABSENT` |
| `high-return-compounder` | 2624.13 | resolved | `ABSENT` |
| `average-return-grower` | 1923.59 | resolved | `ABSENT` |
| `value-neutral-return` | 1250.00 | resolved | `ABSENT` |
| `low-return-grower` | 576.41 | resolved | `ABSENT` |
| `return-below-terminal` | −1000.00 | resolved | `ABSENT` |
| **`return-absent`** | **ABSENT** | **refused** | **`estimator_unavailable`** |
| `fast-fade` | 1924.90 | resolved | `ABSENT` |
| `slow-fade` | 3609.74 | resolved | `ABSENT` |
| `shrinking-issuer` | 1320.33 | resolved | `ABSENT` |
| `base-cash-flow-absent` | ABSENT | refused | `not_reported` |
| `growth-absent` | ABSENT | refused | `not_reported` |
| `discount-rate-absent` | ABSENT | refused | `not_reported` |
| `non-fading-path` | ABSENT | refused | `not_reported` |
| `terminal-growth-at-discount` | ABSENT | refused | `out_of_policy_range` |
| `terminal-growth-above-rate` | ABSENT | refused | `out_of_policy_range` |
| `return-on-capital-zero` | ABSENT | refused | `out_of_policy_range` |
| `growth-outruns-the-fade` | ABSENT | refused | `out_of_policy_range` |

`residual-income.feature`, all 16 rows:

| row | value | outcome | reason |
| --- | --- | --- | --- |
| `flat-book-earning-its-spread` | 1320.00 | resolved | `ABSENT` |
| `value-neutral-return` | 1000.00 | resolved | `ABSENT` |
| **`return-absent`** | **ABSENT** | **refused** | **`estimator_unavailable`** |
| `below-the-cost-of-equity` | 840.00 | resolved | `ABSENT` |
| `growing-franchise` | 1375.61 | resolved | `ABSENT` |
| `fast-fade` | 1078.85 | resolved | `ABSENT` |
| `slow-fade` | 1717.84 | resolved | `ABSENT` |
| `shrinking-book` | 1287.03 | resolved | `ABSENT` |
| `book-absent` | ABSENT | refused | `not_reported` |
| `growth-absent` | ABSENT | refused | `not_reported` |
| `cost-of-equity-absent` | ABSENT | refused | `not_reported` |
| `insolvent-book` | ABSENT | refused | `out_of_policy_range` |
| `book-outgrows-its-return` | ABSENT | refused | `out_of_policy_range` |
| `non-fading-path` | ABSENT | refused | `not_reported` |
| `terminal-growth-at-the-cost` | ABSENT | refused | `out_of_policy_range` |
| `growth-outruns-the-fade` | ABSENT | refused | `out_of_policy_range` |

**Derivation, and the rule when it is wrong.** These cells are **planner-derived by reading the
code**, not by running cucumber (R9): every `let-else` destructuring failure yields `NotReported`;
`non-fading-path` refuses at `GrowthPath::fitted`, and `cucumber.rs`'s `given_growth_path` yields
`None`, whereupon `when_intrinsic_value` / `when_residual_income` synthesize
`Observation::absent(NotReported, ..)` — hence `not_reported`, not an arithmetic reason; every
guard **after** destructuring yields `OutOfPolicyRange`. **If a cell disagrees with observed
behaviour, correct the cell to the observed value and report the correction — except for the two
`return-absent` rows, where a disagreement means the code is wrong and T5.2/T5.3 are incomplete.**
*Acceptance:* `cargo test -p valuation-core` passes with `fail_on_skipped()`; the two
`return-absent` rows read `ABSENT` / `refused` / `estimator_unavailable`.

**T5.5 — Rewrite the rationale comment blocks, which currently teach the deleted rule.**
Both feature files carry a "Rows worth reading as a set" block that explains FR-29 in its old
form — `intrinsic-value.feature` pairs `value-neutral-return` with `return-absent` and says an
absent return "gets that same value and a different provenance"; `residual-income.feature` says
the same. Those paragraphs become false the moment T5.2 lands. Rewrite both so they say: a
measured break-even return values the issuer at the neutral line, and an **absent** return is not
a measurement at all, so it refuses — the distinction the old text tried to carry in provenance
alone is now carried in the outcome.
*Acceptance:* neither comment block asserts that an absent return produces a value.

**T5.6 — `manifest.toml` records the changed contract.**
Update the `intrinsic-value-from-fading-path` entry's prose so it describes refusal rather than
value-neutrality. **Add `FR-29` to the `residual-income-on-book` entry's `frs` list** — it is
absent today (F17), and after D4 that outline covers FR-29's residual-income form.
*Acceptance:* `schema.rs`'s manifest rules pass; `residual-income-on-book`'s `frs` contains
`FR-29`.

**T5.7 — Characterize the legacy fabrication that this wave does *not* fix.**
`operating_valuation::terminal_payout_bps` is `pub`, pure, and reachable without touching a
forbidden file. In `valuation_core_measurement.rs` (which this wave owns), add
`the_legacy_engine_still_substitutes_the_cost_of_equity_for_an_absent_return`, importing
`terminal_payout_bps` (imported, never fully qualified — global style rule) and asserting that an
absent return produces the same payout as a return measured at the cost of equity. This is
LD-3's characterization: it does not fix the defect, it makes the defect fail loudly if anyone
changes it silently.
*Acceptance:* one assert; the test passes today; `src/operating_valuation.rs` is absent from
`git diff --name-only`.

**T5.8 — Prove the Core went dark, exhaustively, not by six edited assertions.**
Rather than only converting the six assertions of F2, add one property test over the adapter's
whole pinned test cohort: **every** operating issuer the adapter can build now yields no value,
and does so for `estimator_unavailable` — because `return_on_capital` is hard-coded absent
(`valuation_core_adapter.rs:557`). Use the collected-violations pattern: one `assert!` over a
`Vec` of issuers that still produced a number. Convert the six named assertions to match, and
**keep `a_bank_refuses_on_evidence_rather_than_being_valued_on_cash_flow` asserting its own
distinct reason** (`provider_unavailable` on book value) — that contrast is the whole reason
D3 introduced a new variant instead of reusing `NotReported`.
*Acceptance:* the property test exists and passes; the bank test asserts a **different** reason
from the operating issuers'.

**T5.9 — The ADR.**
`docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md`, in the standard ADR shape
(context, decision, consequences, alternatives, status). It must contain, and none of these is
optional:

- what FR-29 asserted, and the two properties it actually had: `C(t) = E(t)(1 − g/r)` collapsing
  to `E_0/w` when `r := w`, which is both value-neutral **and** growth-independent;
- why value-neutrality is not neutrality of *belief*: the substitution asserts break-even
  economics as a measurement, and a screener's job is to distinguish;
- the new rule, and why the reason is `EstimatorUnavailable` rather than `NotReported` —
  including that reusing `NotReported` would have made the bank's refusal and an operating
  issuer's refusal indistinguishable, voiding T5.8's contrast;
- **the consequence, stated plainly: the new Core now refuses every operating issuer**, because
  `valuation_core_adapter::return_on_capital` is hard-coded absent. This is intended. Nothing
  published moves, because the Core has no production caller (F1);
- **the D5 statement, verbatim:** the equivalent substitution remains live in the production path
  at `operating_valuation.rs:223`, `terminal_payout_bps`, and is unaddressed by this run;
- **the latent-defect register (D7), LD-1 through LD-4**, each with its owner and trigger
  condition. Wave 4's economic contract links to it; the ADR is where it is defined.

**T5.10 — PRD and addendum.**
`prd.md`: retitle FR-29 (approximately *"An absent return on capital refuses rather than
valuing at the neutral line"*) and rewrite its body, **keeping the FR-29 identifier** (D8) so the
record reads as a changed contract. Add one sentence naming the residual-income form (D4). Extend
`addendum.md` section B.5 the same way. **`prd.md` stays `status: draft`** (constraint 13) — do
not promote it.
*Acceptance:* `status: draft` is unchanged; `grep FR-29` finds no surviving text asserting
value-neutral substitution.

#### Invariants

- **L1** No `unwrap_or` on a return-on-capital or return-on-equity path in `valuation-core`.
- **L2** An absent return refuses with `EstimatorUnavailable`, in both the operating and the
  residual-income form.
- **L3** A bank's refusal reason is **different** from an operating issuer's refusal reason.
- **L4** Every Examples row in the two edited tables has a `reason` cell, and a resolved row's
  cell is the reserved `ABSENT` token.
- **L5** The seven `schema.rs` rules still pass, and cucumber still runs with `fail_on_skipped()`.
- **L6** `prd.md` is still `status: draft`.
- **L7** `src/operating_valuation.rs` is unmodified.
- **L8** `valuation-core`'s dependency list is still empty (FR-1).

#### Test methodology — BDD scenarios

| id | type | actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W5-P01 | positive | Core | a measured return equal to the discount rate | `intrinsic_value` runs | it resolves at the neutral line, 1250.00 | `value-neutral-return` still resolves; only *absence* changed |
| W5-P02 | positive | Core | a measured return below terminal growth | `intrinsic_value` runs | it resolves negative, −1000.00 | FR-28; not floored |
| W5-P03 | positive | Core | a measured return on equity of 400 bps against a cost of 800 | `residual_income_value` runs | it resolves at 840.00, below book | the residual-income form still discriminates |
| W5-N01 | negative | Core | an absent return on capital, everything else measured | `intrinsic_value` runs | it refuses; `kind()` is evidence and `detail()` is `estimator_unavailable` | L2; the wave's central behaviour |
| W5-N02 | negative | Core | an absent return on equity, everything else measured | `residual_income_value` runs | it refuses with `estimator_unavailable` | D4 |
| W5-N03 | negative | Core | an absent base cash flow | `intrinsic_value` runs | it refuses with `not_reported`, **not** `estimator_unavailable` | the two absences stay distinguishable |
| W5-N04 | negative | adapter | a financial issuer with no book value | `value` runs | it refuses with `provider_unavailable` | L3; the contrast that justifies the new variant |
| W5-E01 | edge | Core | a **measured** return of exactly zero | `intrinsic_value` runs | it refuses with `out_of_policy_range`, not `estimator_unavailable` | measured-zero is not absence; `return-on-capital-zero` |
| W5-E02 | edge | adapter | every operating issuer in the pinned test cohort | `value` runs | **none** produces a number, and all refuse for the same named reason | T5.8, exhaustive rather than six assertions |
| W5-E03 | edge | schema | the two edited Examples tables | `cargo test -p valuation-core --test schema` | all seven rules pass, including per-table rectangularity | F11 |
| W5-R01 | regression | Core | an absent discount rate | `intrinsic_value` runs | still `not_reported` | the existing refusal reasons are untouched |
| W5-R02 | regression | legacy engine | an absent return on capital | `terminal_payout_bps` runs | it still substitutes the cost of equity | T5.7; LD-3 characterized, not fixed |
| W5-R03 | regression | reviewer | `prd.md` | review | `status: draft` | L6, constraint 13 |
| W5-R04 | regression | reviewer | `valuation-core/Cargo.toml` | review | the dependency list is still empty | L8, FR-1 |

#### Automation level

Unit tests in `projection.rs`, `residual_income.rs`, `evidence.rs`; the cucumber outlines; the
seven schema tests; adapter tests in `valuation_core_adapter.rs`; one characterization test in
`valuation_core_measurement.rs`. All offline.

#### Fast checks (builder runs; about 10 seconds each)

| Level | Command |
| --- | --- |
| unit | `cargo test -p valuation-core` — library, cucumber and schema together |
| unit | `cargo test --lib valuation_core_adapter::` |
| unit | `cargo test --lib valuation_core_measurement::` |
| lint | `cargo fmt -- --check`, `cargo clippy -- -D warnings` |
| grep | `unwrap_or` absent from the return paths; `status: draft` present in `prd.md` |
| scope | `git diff --name-only` matches the ownership matrix; `operating_valuation.rs` absent |

#### Deferred checks (orchestrator runs)

- `cargo test --lib` — full Shell suite.
- `cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture` —
  the table now shows the Core refusing across the board. **Expected and intended**, per T5.9.
  Note the fixture's staleness (F19) in the report.
- Anchors PG, GOOGL, AMZN, MSFT: expected unchanged, because the Core is not wired (F1). Report
  the numbers. Any movement means F1 is wrong — stop and ask.
- **No live QA checklist for this wave.** Nothing on a published path changes; that claim rests on
  F1, which was verified by grep, and on the anchor report above.

#### Evidence of pass

`cargo test -p valuation-core` summary (library plus cucumber plus schema), the adapter and
measurement summaries, the names of W5-N01, W5-N04 and W5-E02 with their output, and the diff of
the two Examples tables showing the `reason` column and the two converted rows.

#### Documentation deliverables

`docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md` (new; content enumerated in T5.9,
including the D7 register), `prd.md` FR-29, `addendum.md` section B.5, both feature-file rationale
blocks, and `manifest.toml`. **No** edits to `docs/index.md` or `AGENTS.md` — Wave 4 owns those
and will index the ADR.

#### Done when

L1 through L8 hold and are tested. The ADR exists and contains every required element of T5.9,
including the verbatim D5 statement and the LD-1 through LD-4 register. Every completion statement
about this wave carries the D5 sentence. The Shell failing set is still exactly, by name:

- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

---

### Wave 4 — The economic contract, the charters, and the pre-registration (Round 4)

| Field | Content |
| --- | --- |
| **Wave id** | `wave-4` — **Round 4** |
| **Title** | What is being measured, what would count as evidence, and what a candidate must beat before anyone may promote it |
| **Scope** | four new documents, `docs/index.md`, `AGENTS.md` |
| **Dependencies on other waves** | Rounds 1-3 merged. Wave 4 needs Wave 5's merged diff to index the ADR correctly and to state the changed contract; the rest it writes from this plan. |
| **Note** | `AGENTS.md` carries **uncommitted working-tree edits** today. Wave 4 preserves them, and stages `AGENTS.md` explicitly — never `git add -A` (constraint 7). |

#### Tasks

**T4.1 — `docs/valuation-economic-contract.md`.** The quantities, their definitions, their sign
conventions, their absence semantics. Required content:

- **Return on capital**: NOPAT over invested capital; both terms defined at the filed-concept
  level; the two competing invested-capital definitions (operating-asset build-up versus
  financing-side build-up) named, with the one this project uses and why.
- **Growth**: what is being grown, over what base, and why the fade rate is one parameter
  governing both the growth path and the spread's erosion (`residual-income.feature`'s comment
  block already argues this — the contract restates it as a standing rule).
- **Absence semantics**: the full `AbsenceReason` set after Wave 5, each with the situation that
  produces it, and the rule that a reason is a claim about *why*, not a category of convenience.
- **The two equivalence-class rules, R1 and R2** (D6), each with its example.
- **The latent-defect register**, LD-1 through LD-4 (D7), each with id, owner and trigger
  condition, cross-linked to the ADR where it is defined.
- **What the legacy engine still does**, per D5.

**T4.2 — `docs/roic-research-charter.md`.** The question, the population, the data, and the
failure modes. Required content: the target quantity; the cross-section (which issuers, which
years, and what makes an issuer eligible); the point-in-time discipline Wave 1 makes possible and
why a non-PIT backtest of this quantity is worthless; the named failure modes — survivorship,
restatement leakage, the "what did we know then" trap, and the fact that a ratio's denominator can
sit near zero.

**T4.3 — `docs/growth-research-charter.md`.** The same for the growth channel: the persistence
parameter, what it is estimated from, what the fade rate means, and the specific caution Wave 3
surfaced — that the persistence fit inherits both the pooled centre's exclusions and the
`variance_of_centre` understatement (D2), and that neither is a free parameter to tune.

**T4.4 — `docs/roic-preregistration.md`.** This is the load-bearing document and its structure is
prescribed, not left to the writer:

1. **Exactly one primary endpoint.** The cross-sectional **median absolute error** (MdAE) of the
   published intrinsic value against the realized outcome, over the pre-declared cross-section.
   One endpoint, chosen before any result is seen. Define the notation `MdAE` on first use and
   use it consistently thereafter.
2. **The comparison is paired, against a named benchmark.** `prior_only` — the model with the
   return-on-capital channel absent — evaluated on the **same issuers, same years, same cutoffs**.
   An unpaired comparison against a different sample is not evidence.
3. **The uncertainty is issuer-clustered.** A bootstrap resampling **issuers**, not issuer-years,
   because an issuer's years are not independent draws. State the number of resamples before
   running any.
4. **A concrete materiality threshold, with its derivation.** Not "an improvement". A number, in
   the endpoint's units, with the reasoning that produced it — what size of error reduction would
   change a decision a user of this screener actually makes. If the derivation is judgement, say
   so and show the judgement; an undefended number is worse than an admitted judgement.
5. **A multiplicity rule.** How many comparisons will be run in total, and what happens to the
   decision threshold when more than one is run. A pre-registration that permits unlimited looks
   at unlimited endpoints pre-registers nothing.
6. **Secondary diagnostics may veto, never promote.** List them explicitly. A secondary that
   improves cannot rescue a primary that fails.
7. **Coverage is excluded from the veto set**, with the reason written out: a change that refuses
   more often will nearly always look better on error while being worse for the user, so coverage
   is *reported* alongside the primary and is never allowed to act as a gate in either direction.
8. **The anchors are excluded from the veto set too.** PG, GOOGL, AMZN and MSFT are diagnostics
   only (brief constraint 12). They appear in every report and in no gate.
9. **The plus-or-minus 5 percent anchor threshold is a communication trigger, not an acceptance
   criterion, and it is not derived from anything.** It is Juan's stated instruction in brief
   section 5: an anchor moving more than plus or minus 5 percent, or changing side of a gate, is
   a stop-and-ask. Write it as exactly that — an instruction about when to pause, with an
   attribution — rather than dressing it up as a statistic. Reviewers asked for a derivation; the
   honest answer is that there is none to give, and saying so is better than inventing one.
10. **A freeze protocol.** What is frozen (endpoint, benchmark, cross-section, cutoffs, threshold,
    resample count, multiplicity rule), when it is frozen (before any candidate is run), where the
    frozen copy lives, and what an amendment costs — an amendment made after an outcome is
    observed invalidates the pre-registration, and the document must say so itself.
11. **A no-outcome-observed attestation.** A line stating that at the time of freezing, no
    candidate had been evaluated against the endpoint. Without it the freeze is unverifiable.

**T4.5 — `docs/index.md`.** Add every new document from this run: `sec-point-in-time-provenance`,
`valuation-aggregation-audit`, `valuation-economic-contract`, `roic-research-charter`,
`growth-research-charter`, `roic-preregistration`, and the ADR. The file is flat and has never
held an ADR (F16), so **create an `## Architecture Decisions` section** and record in the file's
own `## Maintenance Rules` that ADRs are indexed there.

**T4.6 — `AGENTS.md`, and the file's own rule about how to change it.**
`AGENTS.md:573` requires that a new operational failure mode add **a row to the anti-pattern table
AND a step to the manual procedures** — both, not either. This wave does both:

- **Anti-pattern row:** *"A netted concept admitted into an equivalence class without a declared
  sign convention"*, with the LIN and BAC evidence and the pointer to R2.
- **Manual-procedure step:** a policy-fingerprint bump is now a triggering change. The step names
  all five fingerprint sites (T2.8's table), says two of them are generated and must never be
  hand-edited, and requires `pwsh scripts/validate-contracts.ps1` and `cargo fmt -- --check` to
  both pass on the regenerated output.
- **The Aggregation section** gains `robust_centre` beside `robust_mean`, states that
  `robust_centre` takes no threshold on purpose, and repeats the `variance_of_centre`
  understatement with its direction.
- **R1 and R2 as two rules**, in the anti-pattern table's cross-statement row and the new one.
- **`## Documentation Map`** gains every new document, matching `docs/index.md` exactly.
- **Preserve the uncommitted working-tree edits already in this file** and stage `AGENTS.md`
  explicitly. Never `git add -A` (constraint 7).

**T4.7 — Intra-wave checkpoint.** Wave 4 is one builder writing six documents, which is where a
plan quietly becomes a wish. After T4.1 and after T4.4's skeleton — headings plus the eleven
numbered elements, before any prose — the builder reports back for a read-through before writing
the rest. A pre-registration whose threshold is written after the writer has seen a result is not
a pre-registration, and this checkpoint is what makes the ordering auditable.

#### Invariants

- **M1** Every new document is reachable from `docs/index.md` **and** from `AGENTS.md`'s
  `## Documentation Map`, and the two lists agree.
- **M2** The pre-registration names exactly **one** primary endpoint.
- **M3** The materiality threshold is a number with a written derivation, not an adjective.
- **M4** Coverage and the four anchors are **not** in the veto set, each with its reason written.
- **M5** No document claims a result. These are charters and a pre-registration; a measurement in
  one of them is a category error.
- **M6** The plus-or-minus 5 percent trigger is attributed to Juan's instruction and is not
  presented as derived.
- **M7** `AGENTS.md`'s pre-existing uncommitted edits survive, and `AGENTS.md` is staged by name.

#### Test methodology

Documentation is checked by review, not by a runner. The checks are still objective.

| id | type | actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W4-P01 | positive | reader | `docs/index.md` | every link is followed | every new document exists at the linked path | M1 |
| W4-P02 | positive | reader | `AGENTS.md` `## Documentation Map` | it is compared with `docs/index.md` | the two lists agree, name for name | M1 |
| W4-P03 | positive | reader | the pre-registration | it is read for endpoints | exactly one is marked primary | M2 |
| W4-P04 | positive | reader | the pre-registration's threshold section | it is read | a number in the endpoint's units, followed by its derivation | M3 |
| W4-P05 | positive | reader | the economic contract | it is read for the register | LD-1 through LD-4 each carry an id, an owner and a trigger | D7 |
| W4-N01 | negative | reader | the pre-registration | it is searched for measured results | none is present | M5 |
| W4-N02 | negative | reader | the veto-set section | it is read | coverage is excluded, with the reason; the anchors are excluded, with the reason | M4 |
| W4-N03 | negative | reviewer | the 5 percent trigger | it is read | it is attributed, and not presented as derived | M6 |
| W4-E01 | edge | reviewer | `AGENTS.md` | `git diff AGENTS.md` | the pre-existing uncommitted edits are still present alongside the new ones | M7 |
| W4-E02 | edge | reviewer | the staging step | `git status` | only the intended files are staged; the high-signal fixture is unstaged | constraints 7 and 8 |
| W4-R01 | regression | reviewer | `AGENTS.md` | it is read | the merge bar, `MAX_ABSOLUTE_Z`, and every existing standing rule are unchanged | documentation may add rules, never weaken one |
| W4-R02 | regression | runner | the repository | `cargo test --lib` and `cargo test -p valuation-core` | unchanged from Round 3's end | a documentation wave changes no behaviour |

#### Fast checks (builder runs; about 10 seconds each)

| Level | Command |
| --- | --- |
| links | every relative link in `docs/index.md` resolves to an existing file |
| diff | `git diff --name-only` matches the ownership matrix |
| staging | `git status --short` — the high-signal fixture is unstaged (constraint 8) |
| regression | `cargo test -p valuation-core` — must be unchanged; this wave touches no code |

#### Deferred checks (orchestrator runs)

- `cargo test --lib` — one confirmation that a documentation wave moved nothing.
- The T4.7 checkpoint read-through, which the orchestrator performs.
- Final assembly of the run report: the anchor table from every wave, the coverage deltas, the
  T1.7 count, the T3.7 refusal-rate change, and the Q1 status.

#### Evidence of pass

The six document paths with their heading outlines; the `docs/index.md` and Documentation Map
diff showing they agree; `git diff AGENTS.md` showing the pre-existing edits preserved;
`git status --short` showing the fixture unstaged.

#### Documentation deliverables

This wave is entirely documentation: `docs/valuation-economic-contract.md`,
`docs/roic-research-charter.md`, `docs/growth-research-charter.md`,
`docs/roic-preregistration.md`, `docs/index.md`, `AGENTS.md`.

#### Done when

M1 through M7 hold. The T4.7 checkpoint happened **before** the pre-registration's prose was
written, and the orchestrator can say so. The Shell failing set is still exactly, by name:

- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

---

## 3. Task quality

Every task above states what will be true when it is done, and every one is verifiable without a
further product decision. Two deliberate exceptions are marked in the text as mechanism choices,
not product decisions, and both record the choice taken:

- T1.4's `extract_recurring_development` source list — either mechanism is acceptable, and the
  one taken goes in the doc;
- T5.4's derived `reason` cells — correct to observed behaviour and report, **except** for the
  two `return-absent` rows, where a disagreement means the code is wrong.

Everything else that could have been a decision is decided in section 1.5.

---

## 4. Baseline and the protected failing set

Re-establish before Round 1 and quote in every wave's report.

| Suite | Command (from `apps/windows/src-tauri`) | Recorded baseline |
| --- | --- | --- |
| Shell library | `cargo test --lib` | **518 passing, 22 ignored, 3 failing** |
| Core library | `cargo test -p valuation-core --lib` | **89 passing** |
| Core schema | `cargo test -p valuation-core --test schema` | **7 passing** (F10 — the brief's "six rules" is wrong) |

The three failing tests are pre-existing and are **the protected set**. Every wave's exit criteria
names them, by name, not by count:

1. `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
2. `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
3. `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

Rules that apply to every wave without exception:

- **No wave may make a fourth test fail.**
- **No wave may weaken a test, a threshold or a refusal path to gain ground** (constraint 6). If a
  wave cannot pass without moving a threshold, the wave is wrong, not the threshold.
- **If a wave accidentally repairs one of the three, report it and do not revert it** (R10). A
  coverage change is reported to Juan, never hidden and never patched away.
- **`cargo test --lib valuation_high_signal` rewrites**
  `apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json`.
  Leave it unstaged (constraint 8) and read its output as a table.
- **Stage files explicitly, by name, every time. Never `git add -A`** (constraint 7). The working
  tree carries long-lived uncommitted work in `AGENTS.md`, `_bmad-output/project-context.md`, two
  `.memlog.md` files and that fixture.

---

## 5. Documentation deliverables, consolidated

Documentation is part of every wave's definition of done, never a follow-up.

| Wave | Document | New? | Owner of the index entry |
| --- | --- | --- | --- |
| W1 | `docs/sec-point-in-time-provenance.md` | new | W4 |
| W2 | `shared/contracts/README.md` (`## Files` plus R1/R2) | edit | W4 |
| W2 | `_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md` | edit | — |
| W3 | `docs/valuation-aggregation-audit.md` | new | W4 |
| W5 | `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md` | new | W4 |
| W5 | `prd.md` FR-29, `addendum.md` section B.5, both feature rationale blocks, `manifest.toml` | edit | — |
| W4 | `docs/valuation-economic-contract.md` | new | W4 |
| W4 | `docs/roic-research-charter.md` | new | W4 |
| W4 | `docs/growth-research-charter.md` | new | W4 |
| W4 | `docs/roic-preregistration.md` | new | W4 |
| W4 | `docs/index.md`, `AGENTS.md` | edit | W4 |

Ten documentation gaps the reviewers named are each assigned above: the two equivalence-class
rules (W2/W4), the fingerprint-bump procedure (W4), the latent-defect register (W5 defines,
W4 links), the `variance_of_centre` limitation (W3 rustdoc plus W3 audit plus W4 `AGENTS.md`),
the contract README `## Files` omission (W2), the ADR index section (W4), the anti-pattern row
plus manual-procedure step pair (W4), the PIT boundary statement (W1), the fiscal-year semantics
(W1), and the `valuation_probes.rs` follow-up (W3 audit).

---

## 6. Cross-cutting

### 6.1 Round schedule, and the semantic reason for each ordering

Four serial rounds. v0 ran two rounds with three waves in the first; both reviewers rejected that.

| Round | Waves | Parallel width |
| --- | --- | --- |
| **R1** | `wave-1` + `wave-3` | 2 |
| **R2** | `wave-2` | 1 — **blocked on Q1** |
| **R3** | `wave-5` | 1 |
| **R4** | `wave-4` | 1 |

**R1: why Wave 1 and Wave 3 are genuinely independent.** Not merely file-disjoint. Wave 1 works at
the *extraction* boundary — how a filed fact becomes an annual observation. Wave 3 works at the
*aggregation* boundary — how a set of already-extracted numbers becomes a centre. Neither reads
the other's output within this run: the adapter Wave 3 edits is fed by
`valuation_core_measurement.rs`'s fixture loader, not by `edgar.rs`, and `valuation_core_adapter::value()`
has no non-test caller at all (F1). They can be built, tested and documented with no knowledge of
each other.

**R1 to R2: the dependency is semantic, not just a file collision.** Wave 2 must apply the sign
inside the very function Wave 1 restructures — `extract_annual_any_with_shape`, which Wave 1 turns
into a vintage-aware resolution path. If Wave 2 wrote the sign into the old shape, the merge would
either silently drop the sign or silently drop the vintage retention, and *both* are invisible in
a green build. Beyond the merge: **Wave 1 and Wave 2 both change what the live extractor returns**
— Wave 1 by removing facts with no filing date, Wave 2 by negating two concepts. Landing them in
one round makes an unexpected per-issuer delta unattributable to either. Wave 2 therefore rebases
on merged Wave 1 and re-reads that function's post-Wave-1 shape before editing it.

**R2 to R3: ordering by evidence quality, not by compilation.** Wave 5 does not need Wave 2's
code. It is scheduled after because Wave 5's central evidence is *"the Core now refuses every
operating issuer, and nothing published moved"*, and that claim is only clean when the last change
to the live extractor has already landed and been measured. Running Wave 5 concurrently with Wave
2 would put a coverage collapse and a sign correction into one report. **If Q1 blocks Wave 2
indefinitely, Wave 5 may be promoted into R2's slot** — it has no code dependency on Wave 2 — and
the orchestrator should do so rather than idling.

**R3 to R4: Wave 4 documents a contract that Wave 5 changes.** The economic contract enumerates
the `AbsenceReason` set *after* `EstimatorUnavailable` exists; `docs/index.md` indexes an ADR that
Wave 5 writes; `AGENTS.md`'s aggregation section describes the primitive Wave 3 shipped and the
two equivalence rules Wave 2 shipped. Wave 4 writes from this plan plus **Wave 5's merged diff**;
it does not need Waves 1 through 3's diffs, only their merged documents to link to.

**Why not more parallelism.** Waves 2, 4 and 5 are each single-wave rounds. That is the cost of
the file overlaps in section 2.0 and of the measurement isolation above, and it is the right
trade: a fast run that cannot attribute a per-issuer delta to a cause has produced nothing usable.

### 6.2 Rollout and migration

- **No data migration.** `AnnualValue` has no serde, no IPC and no persisted form (F3).
- **One contract version bump**, `sec-driver-normalization/8` to `/9`, across the five sites in
  T2.8. Two of the five are generated.
- **No published-behaviour change is intended by any wave.** Wave 1's is measured against T1.7's
  count; Wave 2's is *proved* absent by T2.6; Waves 3 and 5 rest on F1 and report anchors anyway.
- **Rollback** is per-wave and clean: each wave is one branch, one merge, and no wave depends on a
  later wave's code.
- **Q1 unanswered** ships Waves 1, 3, 5 and 4 and reports Wave 2 as blocked, with section 0 as the
  evidence. It does not ship half of Wave 2.

### 6.3 Observability and provenance

- Wave 1 makes provenance a first-class field: every annual observation names its source facts,
  their accessions and the date it became knowable. That is the run's main observability gain and
  it is what makes item 6's rolling PIT harness possible later.
- Wave 3 adds two counters — `growth_pooled_discarded` and `growth_pairs_dropped` — so a trimmed
  estimate reports what it cost, rather than quietly being narrower.
- Wave 5 makes an absence *say which absence it is*, which is the difference between a refusal a
  reader can act on and one they cannot.
- **Every diagnostic that reads `core_driver_data_deep.json` must state that the fixture is
  pre-`/8` and stale** (F19). A stale fixture presented as current evidence is exactly the failure
  both reviewers caught in v0.

### 6.4 Explicitly out of scope

Item 6 (the rolling PIT harness) and its `FcfPoint` extension. Item 7 (candidates, benchmarks,
ablations) and any measured result. Item 9 (integration). Wiring `posterior::fuse` to a
return-on-capital channel. The adapter change that would supply a NOPAT base and a measured ROIC
together. Rebuilding the growth engine. Re-capturing `core_driver_data_deep.json`. LD-1 through
LD-4, each with its trigger condition recorded (D7). The `stockholdersEquity` NCI-basis audit
(LD-4). Any change to `MAX_ABSOLUTE_Z`, to a test threshold, or to a refusal path that would gain
valuation-gate ground.

### 6.5 Standing constraints, checked against this plan

| # | Constraint | Where this plan honours it |
| --- | --- | --- |
| 1 | No ticker or issuer special-cases | J2; W2-N03; the sign is a static contract constant |
| 2 | Street price is never a clamp, an optimand or an acceptance criterion | No wave reads a market price; the pre-registration's endpoint is error against realized outcome |
| 3 | No minimum-WACC-as-truth, price caps, output clamps or sector FCF haircuts | No wave introduces one |
| 4 | Do not move a test threshold | K2; section 4's rules; the memory rule on weakening checks |
| 5 | Absence is never a fabricated zero | T1.6; W1-E03; D3; LD-2 named rather than silently kept |
| 6 | No naked averages; `MAX_ABSOLUTE_Z` does not move | D2; K1; K2; T3.6's eight-row audit |
| 7 | Never `git add -A`; stage explicitly | Section 4; T4.6; W4-E02 |
| 8 | The high-signal fixture stays unstaged | Section 4; W1 and W4 deferred checks; W4-E02 |
| 9 | Anchors PG/GOOGL/AMZN/MSFT are diagnostics only | T4.4 item 8; every wave reports them and none gates on them |
| 10 | Never FQN, always import; one assert per test; KISS; DRY | T5.7's import; the collected-violations note in W1; D2's single `trimmed`; T2.2's "do not write a second slice formatter" |
| 11 | The Gherkin outlines are the specification | T5.4 adds a **column** and edits rows; a new outline would need a `manifest.toml` entry (FR-44) and none is added |
| 12 | `valuation-core`'s dependency list stays empty | K8; L8; W3-R03; W5-R04 |
| 13 | `Observation<T>` stays a sum type; `prd.md` stays `status: draft` | D3 adds a variant, not a default; L6; W5-R03 |

### 6.6 Pause triggers, restated for every builder

Stop and ask Juan — do not decide — when any of these occurs:

- **(a)** two designs give materially different economic results and no test decides between them;
- **(b)** an anchor moves more than plus or minus 5 percent, or changes side of a gate;
- **(c)** a choice between fixing something and refusing to value it.

Q1 (section 0) is a live instance of (c), already escalated.

---

## 7. Changelog, v0 to v1

Driven by `plan-review/r1-consolidated-directives.md`. Every P0 and P1 is adopted; every
RESOLUTION is treated as binding and is not relitigated.

**Structural**

1. **Wave 2 redesigned end to end.** Was: delete `InterestIncomeExpenseNet` and
   `InterestIncomeExpenseNonoperatingNet`. Now: a declarative per-qname sign convention in the
   contract, carried through the generator into both generated targets, applied on both the Rust
   and the Kotlin extraction paths. No qname is deleted. (P0-D, P0-H.)
2. **Four rounds instead of two.** R1 = W1 + W3, R2 = W2, R3 = W5, R4 = W4, each edge justified
   semantically in section 6.1 rather than by file collision. (P0-G, directives section 7.)
3. **Q1 moved to the head of the plan**, with the four-issuer cost in numbers (57 issuer-years;
   38 in scope), a third option Juan has not seen, and an explicit statement of what it blocks
   (Round 2 only). (P0-I.)
4. **Section 0's sign-blindness is proved, not asserted.** Two new characterization tests in
   `dcf_model.rs`'s test module (T2.6) discharge the live-QA obligation, and the `.abs()` at
   `dcf_model.rs:907` is named as tracked latent defect LD-1. (Directives section 0, P0-F.)

**Correctness of the plan's own claims**

5. **Line numbers replaced by symbol names throughout**, and every surviving line citation was
   re-read. Corrected: `residual_income.rs` `:111` not `:108`; `compute_dcf` defined `:681` and
   called `:1185`, with `:1180` being the reconstruction site; `return_on_capital` `:557` not
   `:554`; `an_issuer_with_too_little_history_refuses_rather_than_extrapolating` `:1095` not
   `:1094`. (P1-1.)
6. **A fourth year-slicing site**, `edgar.rs:417` in `extract_annual_percent_any`, added to T1.2 —
   v0 named three while demanding zero hits.
7. **T3.6's averaging audit corrected and completed**: `:295-296`, `:631` and `:637` added;
   `:753` and `:758` removed, being lines inside `fn sample_variance` rather than call sites.
8. **v0's "expected delta exactly zero" for Wave 1 removed.** It presented fixture evidence as a
   live claim. Replaced by T1.7's live probe and an attribution rule. (P0-F.)
9. **"Six schema rules" corrected to seven** (F10); the "4 files carry the qnames" corrected to
   three tracked files (F7); `residual-income-on-book`'s `frs` corrected — it does **not** carry
   FR-29 today, and T5.6 adds it (F17).

**Design**

10. **`AbsenceReason::EstimatorUnavailable` added** instead of reusing `NotReported`, with the
    reason-not-propagated rule, and the bank contrast that reuse would have destroyed. (P0-B.)
11. **A `reason` column on both outlines**, verified spec-legal, with every cell given literally
    and a stated rule for a disagreement. (P0-B.)
12. **Wave 1 retains vintages** through `AnnualSeries::as_of` with a strict `filed < cutoff`
    boundary; `known_from` is demoted to a report. Fiscal-year semantics decided and pinned.
    (P0-C.)
13. **`robust_centre` takes no threshold parameter**; `variance_of_centre` is renamed and its
    understatement documented with its direction and its "no effect today" note; degenerate
    retained counts are unreachable by refusal, and that is asserted. (P0-E.)
14. **W3 excludes outliers from the persistence pair set**, killing both pairs an excluded
    observation touches and creating none across the gap; two distinct counters report the cost.
    (P1-2.)
15. **The legacy `operating_valuation.rs:223` substitution** gets a verbatim completion statement,
    a characterization test (T5.7, reachable because `terminal_payout_bps` is `pub`), and register
    entry LD-3. Sensei's Gherkin-row suggestion is recorded as not implementable, with the reason.
    (P0-A.)
16. **A latent-defect register, LD-1 to LD-4**, with owners and trigger conditions, defined in the
    ADR and linked from the economic contract — including the `stockholdersEquity` NCI-basis audit
    that R2 makes mandatory.

**Process and documentation**

17. **Every wave's exit criteria names the three protected failing tests by name**, and section 4
    records the full baseline. (Directive G.)
18. **Every wave has a "deferred checks (orchestrator runs)" heading.** Builders run only fast
    local tests. (Directive I.)
19. **The pre-registration's structure is prescribed** in eleven numbered elements, including a
    multiplicity rule, a freeze protocol, a no-outcome-observed attestation, `MdAE` notation, and
    coverage **and** the anchors excluded from the veto set with reasons. (P1-3, P1-4.)
20. **The plus-or-minus 5 percent anchor threshold is stated as Juan's communication trigger with
    an attribution, and explicitly not derived** — because there is no derivation to give.
21. **Ten documentation gaps assigned** in section 5, with `AGENTS.md`'s own "row **and** step"
    rule honoured, and `AGENTS.md`'s uncommitted working-tree edits protected. (P1-7, P2.)
22. **An intra-Wave-4 checkpoint** (T4.7), because one builder writing six documents is where a
    plan quietly becomes a wish.
