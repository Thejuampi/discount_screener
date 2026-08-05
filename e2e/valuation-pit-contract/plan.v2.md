# `plan.v2.md` — Valuation PIT & Contract (E2E session `valuation-pit-contract`)

Branch: `valuation/wave1-integration` · Repo: `G:\dev\repos\discount_screener`
Supersedes `plan.v1.md` in full. This is a replacement plan, not a patch.

Inputs, in precedence order: **Juan's rulings on Q1 and Q2 (2026-08-04, recorded in section 0)** →
`brief.md` (Juan's decisions 1–3, 13 binding constraints) →
`plan-review/r1-consolidated-directives.md` and the r2 review round (Sensei 10 P0s, Advisor 1 P0;
every RESOLUTION binding) → `refine.md` → `plan.v1.md` (preserved where unfaulted).

## How to read a verification claim in this document

v1 marked twenty-two claims "verified". **Two did not survive contact with the code**, and both
were caught in r2 rather than by the plan itself:

- v1 asserted the negative-interest guard at `driver_resolution.rs:117` was reachable from that
  file's own test helper. It is not: `fn point(..)` at `:326` calls
  `FcfPoint::new(..).with_operating_drivers(..)`, not a struct literal, so the value is abs'd
  before storage. The guard is dead **everywhere in the tree**, tests included.
- v1 asserted `RustSlice` emits malformed output for an empty collection. It does not; only
  `KotlinList` does.

A third claim — that the abs setter is the field's sole writer — was **true but unproven**: the
search behind it (`interest_expense_dollars =`) cannot match Rust struct-literal initialisation
(`interest_expense_dollars: x,` or the shorthand `interest_expense_dollars,`). It has since been
re-run correctly and holds.

Therefore, in this document, **a load-bearing claim marked "verified" carries the evidence that
establishes it** — the search pattern, or the `file:line`. A claim without attached evidence is
marked **unverified** and names what would settle it. A builder that finds a "verified" row to be
false stops and reports; it does not adapt around it.

---

## 0. Juan's rulings, and what they changed

Both open questions are **closed by Juan** (2026-08-04). Neither is a planner decision and neither
may be re-opened by a builder.

### Q1 — the interest-expense sign convention: option (iii), **and LD-1 comes with it**

The declarative per-qname sign convention is adopted. But v1 put a misleading option table in
front of Juan, and the correction is the reason this wave grew.

**What v1 got wrong.** v1's cost column claimed option (iii) *"recovers all 57 issuer-years with
their sign corrected"*, and v1's T2.6 proposed to *prove* that no published number moves. Both
statements are true, and together they say something v1 never said out loud: **the change would
have been economically inert.** Trace both filer classes through the full chain:

| Filer class | Files | Today | Under (iii) alone |
|---|---|---|---|
| LIN-type (net **expense**) | `-63M` | `.abs()` → `+63M` | negate → `+63M` → `.abs()` → `+63M` |
| BAC-type (net **income**) | `+60,096M` | `.abs()` → `+60,096M` expense | negate → `-60,096M` → `.abs()` → `+60,096M` expense |

The blanket `.abs()` annihilates the corrected sign in **both** directions. The 57 issuer-years
were never lost — they are in use today with their sign destroyed. So (iii) on its own recovers
nothing relative to standing still; it only avoids *losing* them relative to deletion. And the
defect the brief actually named in its §0 — *a cash-rich issuer filing net interest income has
`pretax + interest` double-add income that pretax already contains* — **survives (iii) untouched**.
A proof of invariance was a proof of inertness.

**Juan's ruling: fix the defect, not the notation.** LD-1 — the blanket `.abs()` — is pulled into
scope. Work-order item 2 is not delivered while the correction is annihilated on write.

**LD-1 is a three-site removal, not one line** (verified, `grep -n "interest.*abs()" src/dcf_model.rs`
plus the setter):

| Site | Code | Role |
|---|---|---|
| `dcf_model.rs:907` | `self.interest_expense_dollars = interest_expense_dollars.map(f64::abs);` | the setter, on write |
| `dcf_model.rs:551` | `let interest = interest.abs();` | FCFF driver audit |
| `dcf_model.rs:1590` | `let interest = interest.abs();` | aligned-driver bridge |

Removing only `:907` changes nothing observable, because both consumers re-abs independently. A
plan or a diff that removes one site is wrong.

### Q2 — the naked mean at `valuation_core_adapter.rs:536`: **replace it, and fix the width too**

v1's T3.6 marked `:536` **"kept"**, reasoned as *"a robust centre needs `n>=3` retained and would
refuse far more often than it would help."* The brief orders the opposite (§156: *"Replace the
naked means at `:280` and `:536`"*; §247: *"`:536` is the worse of the two"*). A planner does not
overrule an explicit instruction in a table cell.

The economics behind the cell were also wrong. The sample at `:536` is `annual_revenue_growth()` —
consecutive-year log ratios, so `n = years − 1`, typically 9–18 on these 10–19 year histories.
`robust_mean` refuses at `n < 3` or when trimming leaves `kept < 3` (`numerics.rs:187`). Reaching
fewer than 3 survivors from 18 points would require discarding ~15 at `|z| > 3` on median/MAD,
which does not occur. It refuses only where an issuer has ≤3 revenue years — exactly the issuers
that should not carry a fitted growth posterior. **That reasoning is deleted from this plan, not
restated.**

**The design point underneath, which matters more than the centre.** `:536` produces *both* the
point estimate *and* the precision: `variance / growth.len()` is handed to
`UncertaintyBasis::SampleVariance` and then to `fuse`, which weights channels by `1/var`. A robust
centre paired with an untrimmed `sample_variance` is **worse than doing nothing** — a clean level
with a contaminated width, silently reassigning weight between the trailing and forward channels.
`robust_mean` returns a scalar only, so it is insufficient here on its own. This is the same
perverse monotonicity r2 identified in `variance_of_centre`: under trimming, a *dirtier* sample
yields a *smaller* reported width and therefore a *larger* fused weight. The fix is not a doc
comment; it is computing both terms from one kept set. D2 is rewritten accordingly.

**Framing note, not a scope change.** This channel is *revenue* growth, and Decision 3 says the
revenue coefficient cannot be reused as NOPAT growth, so this code is slated for rebuild. That
does not excuse leaving a banned naked mean in place, and it **must not appear anywhere in this
plan as a reason to defer**.

### What this does to the shape of the run

- Wave 2 is no longer a notation change. It **moves published numbers on the live legacy engine**,
  and the invariance proof that justified discharging live QA is deleted along with its premise.
- Wave 2 acquires a genuine economic decision — the negative-interest guard (T2.7 below) — that
  v1 did not have to make because the sign could never survive to reach it.
- Nothing is blocked. Both questions are answered; all four rounds proceed.

### Why the sign convention is the right vehicle, and the measurement behind it

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

### The consumers of the sign, now that it survives

**The write path (verified with the patterns v1's search could not match).** The abs setter at
`dcf_model.rs:907` is the sole writer of `interest_expense_dollars`. Evidence, all three searches:

- `grep -rn "interest_expense_dollars = " src/` → `dcf_model.rs:907` only. (`edgar.rs:1070` is a
  `let` binding of a local, not a field write.)
- `grep -rn "interest_expense_dollars:" src/` → `dcf_model.rs:850` (the `pub` field declaration),
  `:884` (the `None` initialiser), `:901` (the setter's parameter). No literal write of a value.
- `grep -rn "interest_expense_dollars,$" src/` → `edgar.rs:1093`, which is a **function argument**
  to `with_operating_drivers` (confirmed by reading `edgar.rs:1083-1095`), and
  `valuation_baseline.rs:900`, a JSON field name. Neither is a struct-literal field init.

Once the three abs sites are removed, every consumer below sees a **signed** value. This table is
the wave's blast radius, and each row needs a ruling rather than an assumption:

| Site | Reads | Effect once the sign survives |
|---|---|---|
| `dcf_model.rs:551` | the FCFF driver audit | **abs removed** (T2.6); a genuine net-income reading now enters signed |
| `dcf_model.rs:1590` | the aligned-driver bridge | **abs removed** (T2.6); this is the published FCFF path |
| `dcf_model.rs:795` | audit table print | prints a signed number; cosmetic, but the audit must not read as an error |
| `driver_resolution.rs:81` | `interest.abs() > f64::EPSILON` | unchanged — already sign-agnostic by construction |
| `driver_resolution.rs:118`, `:124` | `interest < 0.0 → None`; `(debt > 0.0 && interest > 0.0)` | **these branches come alive.** T2.7 must rule on them |
| `valuation_fixture_capture.rs:131` | `.unwrap_or(0.0)` | absence fabricated as zero — swept in T2.8 |
| `valuation_baseline.rs:900`, `valuation_probes.rs:344,354` | reporting surfaces | report signed values |

**Correcting v1's guard claim.** v1 said the `interest < 0.0` guard was dead *"on the production
path only"* and still reachable from `driver_resolution.rs`'s own test helper. That is **false**:
`fn point(..)` at `:326` calls `FcfPoint::new(..).with_operating_drivers(..)`, and there is no
`FcfPoint` struct literal anywhere in that file. The guard is dead **everywhere in the current
tree**. This matters in the opposite direction from how v1 used it: the guard is not a safety net
that tests already exercise, it is **untested code about to become live for the first time**.

### What is blocked

Nothing. Both questions are answered. All four rounds proceed as scheduled in §6.1.

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

### 1.3 Current-state findings (each re-verified for v1; corrections from r2 marked inline)

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
- **`as_of` covers SINGLE-CONCEPT drivers only. This is a named limitation, not a general
  capability.** `AnnualSeries` holds `Vec<AnnualObservation>` and each observation carries **one**
  `SecFact`, so a **composition** is not representable in it — yet compositions are real and this
  plan relies on them elsewhere (total debt = current + non-current; FCF = OCF − CapEx;
  development = tangible + software), which is exactly why `AnnualProvenance` carries
  `sources: Vec<SecFact>` and a `mixed_vintage` flag. Consequently `extract_total_debt`,
  `fcf_history` and `extract_recurring_development` receive provenance under T1.4 but have **no
  `as_of`** — and that excludes the entire FCFF bridge.

  v1 titled T1.3 *"Vintages are retained and resolvable as of a cutoff"* and §6.3 called provenance
  *"what makes item 6's rolling PIT harness possible later."* Item 6's harness needs FCF as of a
  cutoff, so that claim was broader than the design. Since item 6 is out of scope, naming the
  limitation is acceptable; leaving the general claim standing is not. Recorded as **LD-6**,
  trigger: *"item 6's harness construction"*, with the fix that will be needed then — compose
  **inside** the vintage layer, resolving each input concept pre-cutoff, then composing, then
  recomputing `known_from` and `mixed_vintage` from the resolved inputs. `docs/sec-point-in-time-provenance.md`
  states the limitation in these terms.
- **`accession` is fail-closed too.** Wave 1 fail-closes on `filed` and `end`; `accn` participates
  in the precedence tie-break, so a missing `accn` defaulted to `""` is the same
  fabricated-identity defect in a third field. A fact without a parseable `accn` produces no
  observation.
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
- `robust_mean` **keeps its existing signature in Wave 3, and loses the threshold in Wave 5.**
  v1 kept the public `max_absolute_z` parameter permanently, defended by a doc prohibition plus a
  grep. That is a promise, not a constraint: a public knob any call site can set to 4.0 is exactly
  the threshold-relaxation vector constraint 6 exists to close. It goes. The only reason it does
  not go in Wave 3 is a **file collision** — its one external caller is in `valuation_probes.rs`,
  which Wave 1 owns in the same round (§2.0). T5.11 removes it in R3.
  Until then `§6.5`'s constraint-6 row reads *"satisfied by construction on `robust_centre`;
  satisfied by convention on `robust_mean` until T5.11 lands in R3."*
- **The width and the centre come from ONE kept set. This is the part that matters.**
  v1 shipped `variance_of_centre` as a documented limitation with **no caller at all** — a
  known-wrong precision accessor whose only defence was a doc comment. Juan's Q2 ruling gives it
  a live consumer (`:536` feeds `UncertaintyBasis::SampleVariance` into `fuse`), so a label is no
  longer an option. The defect must be *fixed*, and it is fixed by construction:
  - `centre()` and `variance_of_centre()` are computed from the **same retained observations**.
    A robust centre paired with an untrimmed `sample_variance` is worse than doing nothing — a
    clean level with a contaminated width — because `fuse` weights by `1/var` and would silently
    reassign weight between channels.
  - The bias this removes is not a mild approximation. It is **monotone in contamination**: on the
    committed `CONTAMINATED` fixture (nine values near 10, one at 910), `var(all)/10` is enormous
    while `var(retained)/9` is minute, so a *dirtier* sample reports a *tighter* precision and
    earns a *larger* fused weight. That perverse monotonicity is the thing being killed.
  - `retained()` is what callers report as `observations`. Reporting the raw `n` for a width
    computed from the kept subset overstates precision in a third place; see T3.8.
  - **Residual limitation, honestly stated and now genuinely minor:** the retained sample is still
    narrower than the population, so the width remains a mild understatement. The alternative — a
    MAD-based scale over the *full* sample — is rejected because it would describe a *different
    estimator* than the one that produced the point. This is recorded in
    `docs/valuation-aggregation-audit.md` as **LD-5**, trigger: *"the first forward channel that
    fuses against the trailing channel."*
- **No second mean implementation, anywhere.** `numerics.rs` is extended once; `robust_centre`,
  `robust_mean` and the `:536` call site all resolve to the same private `trimmed`. Anything added
  to `numerics.rs` carries its own tests in the core crate, one assert per test.
  `MAX_ABSOLUTE_Z` stays `3.0`.
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
is impossible for `qname_signs` (every driver has qnames), which avoids `KotlinList`'s
empty-collection defect — **verified**, `KotlinList` on an empty collection emits a stray comma
inside `listOf(`, which is not valid Kotlin
(`scripts/generate-sec-driver-normalization-policy.ps1:26-29`).

**v1's claim that `RustSlice` is "similarly malformed" is false and is withdrawn.** `RustSlice`
(`:74-81`) is fine in both call shapes: at `nestedIndent = 0` the compaction branch returns `&[]`;
at `nestedIndent = 4` it emits `&[` newline newline `]`, which Rust's grammar accepts.

Two honest caveats on this rationale, since half of it just evaporated:

- Choosing a data model to route around a three-line emitter bug is weak justification, and the
  companion argument — that length-equality is a checkable invariant a membership list cannot give
  — is partly circular, since that invariant exists to mitigate a risk the positional shape itself
  creates. The design still stands on its remaining merit (it is the shape the generated Rust and
  Kotlin consumers already want, and it makes the sign available at the point of iteration), but it
  is not the slam dunk v1 presented.
- Therefore **fix `KotlinList`'s empty case anyway** (T2.11) — it is a latent generator defect
  independent of this wave — and add the **alignment** check that a length check cannot give:
  a generated-code test that **reconstructs** the sign array from `negatedQnames` and compares.
  J1 catches truncation; only reconstruction catches a wrong value at a right length.

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

**D7 — A latent-defect register, with ids, owners, trigger conditions AND detectors, in the
economic contract.** Items this run knowingly does not fix, each named so it is never later
mistaken for an oversight.

**Every trigger carries a detector.** v1's triggers were prose conditions — *"the first router row
whose decision inverts on the substitution"*, *"the first issuer whose filed interest series is
genuinely net income reaching the FCFF bridge"* — with nothing evaluating them. A trigger nobody
checks is a comment. Each row below names either a mechanical detector (a test, a probe, an
assertion) or an explicitly human review checkpoint with an owner. Where neither exists, the row
says so, so the register is honest about its own enforceability.

| Id | Defect | Why not now | Trigger | Detector |
| --- | --- | --- | --- | --- |
| ~~**LD-1**~~ | ~~blanket `.map(f64::abs)` on `interest_expense_dollars`~~ | **CLOSED BY WAVE 2.** Juan's Q1 ruling pulled it into scope. All three sites removed (T2.6). | — | W2-R01 fails if an `.abs()` is ever restored |
| **LD-2** | `resolve_capex_abs` returns a zero CapEx when no series exists (`edgar.rs:604-607`) — a real fabricated zero on the production FCF path. | On the production FCF path; would move published anchors. | Any wave that touches the CapEx-to-FCF bridge. | **Human review checkpoint**, owner as below. No mechanical detector exists; stated plainly rather than implied. |
| **LD-3** | `operating_valuation::terminal_payout_bps` substitutes the cost of equity for an absent return on capital (`:223`) — the legacy FR-29. | Decision 2 allows the legacy engine to stay live during module-by-module replacement. | Retirement of the legacy engine, or the first router row whose decision inverts on the substitution. | T5.7's characterization test fails the moment the substitution changes. The *router-inversion* half has **no** detector — human review. |
| **LD-4** | `stockholdersEquity`'s equivalence class mixes NCI-inclusive and NCI-exclusive concepts — one line, two measurement bases, under R2. | Out of this run's scope; changing it moves invested capital for every issuer with a material minority. | R2 is adopted (Wave 2); the audit is due before any invested-capital estimator is pre-registered against filed equity. | **T4.5 gate**: the target specification cannot pin "invested capital" (rows 2, 13) without resolving the NCI basis, so writing T4.5 forces the audit. |
| **LD-5** | `variance_of_centre` remains a mild understatement: the retained sample is narrower than the population it estimates. | The alternative — a MAD-based scale over the full sample — would describe a *different estimator* than the one that produced the point. The perverse monotone-in-contamination component is **fixed** (D2); only the residual bias remains. | The first forward channel that fuses against the trailing channel. | **Human review checkpoint** at the point `fuse` gains a second live channel. No mechanical detector. |
| **LD-6** | `AnnualSeries::as_of` resolves **single-concept drivers only**; composed drivers (total debt, FCF, development) carry provenance but have no cutoff-aware resolution. | Item 6's rolling PIT harness is out of scope, and composing inside the vintage layer is its own design. | Item 6's harness construction. | **Mechanical**: `extract_driver_vintages` has no composed-driver caller, so the harness cannot be built without hitting this. |
| **LD-7** | `interest == 0` with `debt > 0` is dropped from the accounting cost-of-debt fit. Either a genuine zero-coupon situation or missing data; the two are not distinguished. | T2.7 ruled on the negative case and declined to re-adjudicate the zero case in the same wave. | Any wave that revisits `resolve_rate_inputs`. | **Human review checkpoint**, owner as below. |

Owner for all open items: the valuation quant workstream (this plan's successor run). The register
lives in `docs/valuation-economic-contract.md` and is linked from the ADR.

**One register-wide risk, stated because it is load-bearing for two waves.** F1 (`valuation_core_adapter::value()`
has no non-test caller) is a *point-in-time* property carrying Wave 3's and Wave 5's live-QA
posture. The first wiring of `value()` to production silently invalidates the reasoning behind
both waves' anchor expectations. T5.12 converts F1 from a grep into a compile-enforced proof (gate
`value()` behind `#[cfg(test)]` locally and confirm the crate still builds — decisive, thirty
seconds), and the result is recorded here as a standing condition rather than a one-off check.

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
| `apps/windows/src-tauri/src/valuation_probes.rs` | R1 | | | | R3 |
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
| `apps/windows/src-tauri/src/dcf_model.rs` — **whole file** (LD-1, T2.6) | | R2 | | | |
| `apps/windows/src-tauri/src/driver_resolution.rs` (T2.7 guard ruling) | | R2 | | | |
| `apps/windows/src-tauri/src/valuation_fixture_capture.rs` (T2.8) | | R2 | | | |
| `shared/contracts/README.md` | | R2 | | | |
| `_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md` | | R2 | | | |
| `apps/windows/src-tauri/valuation-core/src/numerics.rs` | | | R1 | | R3 |
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

**`numerics.rs` and `valuation_probes.rs` each appear in two rounds, and the split is deliberate.**
Wave 1 owns `valuation_probes.rs` in R1 and Wave 3 owns `numerics.rs` in R1 — *concurrently*. So
Wave 3 **may not** change `robust_mean`'s signature, because its one external caller lives in
`valuation_probes.rs` (F18). Removing the threshold knob is therefore a **Wave 5 / R3** task
(T5.11), after both R1 waves have merged. This is a scheduling constraint, not a deferral: the
knob is gone before the run ends.

**Files no wave may touch, at all:** `src/operating_valuation.rs`, `src/valuation_baseline.rs`,
`src/valuation_high_signal.rs`, `tests/fixtures/valuation/*`, `_bmad-output/**/.memlog.md`,
`_bmad-output/project-context.md`.

*(v1 also listed `driver_resolution.rs` and `dcf_model.rs`-outside-tests here. Both are removed
from the untouchable list by Juan's Q1 ruling — LD-1 and the guard ruling are the point of Wave 2.
The three protected **failing tests** are unchanged and still protected; see §4.)*

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
probe; diagnostic only"]`, prints a table, **asserts nothing**. The orchestrator runs it.

**The sample is named, not left to the builder.** v1 said *"at least 5 real issuers"*, which leaves
the sample to the party whose wave is exonerated by a low count — and five large-cap clean filers
will return zero. The sample is: the four anchors (**PG, GOOGL, AMZN, MSFT**), the four Wave 2
issuers (**COF, DAL, CHTR, BKR**), and a slice of the 26-name high-signal cohort chosen for the
**oldest filing histories**, where missing `filed` fields are most likely.

Count per issuer and per driver, **three** columns — v1 had only the first two:

1. accepted 10-K facts with no `filed`;
2. accepted facts whose `end` will not parse;
3. **`(concept, period_end)` pairs having more than one vintage with *different values*.**

Column 3 is the one that matters and v1 omitted it. Columns 1 and 2 measure the wave's *cost*.
Column 3 measures whether `as_of` ever differs from `latest` on live data — which is Wave 1's
entire reason for existing. It is one more column in a probe already being written, and it is the
only live validation this wave has (see the `extract_driver_vintages` risk in §1.8).

**The attribution rule is operationalized, not gestured at.** v1 said *"any non-zero delta must be
explained by that count"* — but a count of N facts does not explain a 3% anchor move. For **any**
issuer showing a delta, the probe output must identify the **specific dropped facts** for that
issuer. An unexplained delta is a defect, not a rounding artifact.
*Acceptance:* the probe exists, is `#[ignore]`, asserts nothing, and emits all three columns over
the named sample. Its **output** is a Wave 1 exit condition, recorded in
`docs/sec-point-in-time-provenance.md`.

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
| **Title** | A netted concept enters an equivalence class through a declared sign convention — and the sign survives to the bridge |
| **Scope** | the driver-normalization contract, its generator, both generated targets, the hand-written Kotlin policy and its one consumer, the Rust extraction site, the fingerprint sites, **the three `.abs()` sites in `dcf_model.rs` (LD-1)**, **the negative-interest guards in `driver_resolution.rs`**, the fixture-capture absence bug, and the contract documentation |
| **Blocked by** | Nothing. Q1 is answered (section 0). |
| **Dependencies on other waves** | Round 1 must be merged (section 6.1 gives the semantic reason) |

#### What this wave is not

It is **not** a deletion. v0 deleted `InterestIncomeExpenseNet` and
`InterestIncomeExpenseNonoperatingNet`. That is replaced by the declarative sign convention of D6.
No qname is removed from any equivalence class in this wave.

#### What changed from v1, and why this wave is now the run's riskiest

v1 scoped this wave to the normalization layer and proposed to **prove** that no published number
could move. Section 0 shows why that proof was really a proof of inertness: the blanket `.abs()`
annihilated the correction. Under Juan's ruling the wave now carries LD-1, so:

- **This wave moves live published numbers.** The cohort measurement table *will* move. That is
  the intended outcome, not a regression.
- **There is no invariance proof to discharge live QA.** v1's T2.6 is deleted with its premise.
  The full `AGENTS.md` live valuation QA applies to this wave, unconditionally.
- **A dead branch becomes live.** See T2.7 — the wave's one genuine economic decision.

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
  `val qnameSigns: List<Int>` — **required, with no default.** v1 gave it
  `= List(qnames.size) { 1 }` so the two direct constructions in `SecEdgarTimeseriesProvider.kt`
  would compile unchanged. That is the *exact* silent default T2.4 forbids on the Rust side, two
  tasks earlier, and it is worse than the thing it was avoiding: a future hand construction whose
  `qnames` contains a negated concept silently gets the wrong signs, with no compile error, no
  test failure, and no reviewer signal — and the length check **cannot** catch it, because the
  lengths match. Requiring the parameter makes the compiler enumerate every construction site;
  each writes `List(qnames.size) { 1 }` explicitly with the same comment the Rust call site
  carries. Add an `init` block requiring the two lists to have equal size.
  `fun operator(driver: Driver)` passes `qnameSigns` through from the generated operator.
- `SecEdgarTimeseriesProvider.kt`: `annualFyRecordsAny` iterates `operator.qnames` with
  `putIfAbsent` — the exact mirror of the Rust gap-fill (F21). It must multiply by the sign at
  the position of the qname being admitted. Iterate with an index rather than doing a second
  lookup.

*Acceptance:* a Kotlin test in `SecDriverNormalizationPolicyTest.kt` asserts that the
interest-expense operator's signs are negative at exactly the positions where `qnames` holds a net
concept, by **looking the index up from `qnames`**, never by hard-coding a position.

**Say plainly what "the Rust and Kotlin merges agree" does and does not rest on.** v1 opened this
acceptance with that phrase and then specified two *independent* tests over two *independently
written* expectations on two platforms — and F20 confirms the fixture corpus is Rust-only. Two
beliefs are not a parity check. Parity here rests on **the shared generated constant**, and there
is **no executable cross-platform check**. That is recorded as a known gap rather than implied
away. Closing it properly means having the Kotlin test read the same fixture corpus; that is not
in this wave's scope, and the gap is stated in T2.11's documentation so nobody later reads the two
tests as a parity proof.

**T2.6 — LD-1: the sign survives to the bridge. All three `.abs()` sites, or none.**
Remove the absolute value at **all three** sites named in section 0:

| Site | Today | After |
|---|---|---|
| `dcf_model.rs:907` | `self.interest_expense_dollars = interest_expense_dollars.map(f64::abs);` | store the value as filed |
| `dcf_model.rs:551` | `let interest = interest.abs();` | delete the rebinding |
| `dcf_model.rs:1590` | `let interest = interest.abs();` | delete the rebinding |

**Removing a subset is a defect, not a partial delivery.** Both consumers re-abs independently, so
removing only the setter changes nothing observable and would produce a green build that has
delivered none of the intent. The builder removes all three in one commit or stops and reports.

This replaces v1's invariance proof, which asserted the opposite property. The characterization
tests are replaced by **sign-preservation** tests, in `dcf_model.rs`'s `#[cfg(test)] mod tests`:

1. `a_net_expense_filing_reaches_the_bridge_as_a_positive_expense` — the LIN class: a filed
   negative net-expense value, negated by the contract convention, arrives at the FCFF bridge
   positive.
2. `a_net_income_filing_reaches_the_bridge_as_a_negative_expense` — the BAC class: a filed
   positive net-**income** value arrives at the bridge **negative**, and is therefore *subtracted*
   where it used to be added. This is the brief's §0 double-add defect, and this test is the
   first thing in the repo that fails if LD-1 regresses.
3. `an_interest_series_and_its_negation_no_longer_agree` — the exact inverse of v1's W2-R01. It
   exists so that a future reader who restores an `.abs()` sees a named failure explaining why.

*Acceptance:* all three tests pass; `grep -n "interest.*abs()" src/dcf_model.rs` returns nothing;
the wave's report names which issuers changed and by how much.

**T2.7 — RULE on the negative-interest guard. This is the wave's one economic decision.**
`driver_resolution.rs:118` returns `None` when `interest < 0.0`, and `:124` admits an
observation only when `(debt > 0.0 && interest > 0.0)`. Both branches are dead today *because of*
the blanket `.abs()` (verified — the guard is unreachable everywhere in the tree, tests included).
The moment the sign survives, they become live for the first time, and they are untested.

**How untested: totally.** `driver_resolution.rs` has **10 tests** (`grep -c "#\[test\]"`), and
**every one** of them constructs its input through the `fn point(..)` helper at `:326`, which calls
`FcfPoint::new(..).with_operating_drivers(..)` and therefore abs-es (verified: every `FcfPoint`
construction in the file is either that helper or a `point(..)` call — there is no struct literal).
So the `interest < 0.0` branch at `:118` has **never executed anywhere** — not in production, not
in a test, not once. Wave 2 is enabling a branch with no execution history, in the wave that moves
published numbers.

**Therefore T2.7's tests must exercise the branch DIRECTLY**, at the `resolve_rate_inputs`
boundary, constructing a genuinely negative `interest_expense_dollars` — not only through the
end-to-end path where an upstream contract negation happens to produce one. An end-to-end test
proves the pipeline; only a direct test proves the branch. Both are required.

Left alone, they do something specific and wrong. `filter_map` **silently drops** a negative-interest
year and lets the accounting cost-of-debt fit proceed on the remaining years. That is selection on
the dependent variable: it retains exactly the years where interest expense was high relative to
interest income and discards the years where it was low, **biasing the estimated effective rate
upward**. A cost of debt fitted on that subsample is confidently wrong and feeds WACC.

**Ruling.** A negative net-interest reading is not a defective observation to be trimmed. It is
evidence that *this issuer's filed series is a net series whose sign has flipped*, and therefore
that the series does not measure gross interest expense at all. The accounting cost-of-debt
channel is **not measurable** from it. So:

| Case | Today | Under this ruling | Rationale |
|---|---|---|---|
| `interest > 0`, `debt > 0` | admit | admit, unchanged | a gross expense measurement; valid |
| `interest < 0` (any debt) | silently drop the **year** | **refuse the channel for that issuer** and fall through to the other cost-of-debt path | a net-negative year proves the series is net; a rate fitted on the surviving years is biased upward. Refusing is Decision 1 applied |
| `interest == 0`, `debt > 0` | dropped by `interest > 0` | unchanged | out of scope to re-adjudicate; recorded as **LD-7** |

Loosening the guard to `interest != 0.0` to keep issuers in the fit is **forbidden** — that is
precisely the coverage-preservation move the brief's closing line rules out.

*Acceptance:* a test asserts that an issuer with one negative-interest year yields **no** accounting
cost-of-debt (rather than one fitted on the remaining years); a second asserts the all-positive
case is bit-identical to today. The refusal-rate change is measured and reported, not assumed.

**T2.8 — Sweep the same defect class in the same file set.**

- `valuation_fixture_capture.rs:131` — `point.interest_expense_dollars.unwrap_or(0.0)` reads an
  absent value as a fabricated zero, against the standing no-fabrication rule, and a fabricated
  zero is now indistinguishable from a genuine net-zero reading. Emit an explicit null.
- `edgar.rs:987` and `:1083` use `crate::dcf_model::FcfPoint` fully qualified, against the
  never-FQN rule (constraint 10). Import and use the bare name. **Bounded**: these two lines only.

*Acceptance:* no `unwrap_or(0.0)` on an interest field remains; `grep -n "crate::dcf_model::" src/edgar.rs`
returns nothing.

**T2.9 — Fingerprint, all five sites.**
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

**T2.10 — A fixture case for the new rule.**
`sec-driver-normalization-fixtures.json` today carries investment-category cases only. Add at
least one interest-driver case built from the LIN figures in section 0: one fiscal year present
under both a positive gross concept and a negative net concept, expecting the class to yield one
positive expense; and one year present **only** under the net concept, expecting the negation to
be applied. Record in T2.11's documentation that this corpus is read by **Rust only** (F20) —
Kotlin's half of the dual-lock for this contract runs through the generated policy, and T2.5's
Kotlin test is what closes it.
*Acceptance:* `frozen_real_sec_fixture_corpus_executes_at_the_normalization_boundary`
(`sec_normalization.rs:397`) exercises the new cases and passes.

**T2.11 — Documentation.**

- `shared/contracts/README.md`: add `sec-driver-normalization.json` and
  `sec-driver-normalization-fixtures.json` to the `## Files` list — they are **absent** today
  (F16) — and state **R1 and R2 as two rules**, each with the example it rests on.
- `_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md`: record the `/9` bump,
  the new contract vocabulary, and the sign convention's derivation from measured filings.
- The generator's header comment gains one line: signs are positional and parallel to `qnames`.
- `docs/valuation-economic-contract.md` (or the Wave 4 deliverable that supersedes it) records
  **LD-1 as closed by this wave**, with the three sites named, and **LD-7** opened for the
  `interest == 0, debt > 0` case T2.7 declined to re-adjudicate.

**Do not repeat v1's empty-collection claim.** v1 justified the positional design partly on
*"`KotlinList` … and `RustSlice` is similarly malformed"* on an empty collection. Only
`KotlinList` is malformed (it emits a stray leading comma inside `listOf(`). `RustSlice` is fine
in both call shapes: at `nestedIndent = 0` the compaction branch returns `&[]`, and at
`nestedIndent = 4` it emits `&[` newline newline `]`, which Rust accepts. The positional design
still stands on its other grounds; the `RustSlice` half of the rationale is deleted, not restated.
Fix `KotlinList`'s empty case anyway — it is a latent generator defect independent of this wave.

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
- **J6** All three `.abs()` sites are removed together. The acceptance search is
  **`grep -nE "interest.*(abs\(\)|f64::abs)" src/dcf_model.rs`**, and it must return nothing.

  **Use this pattern exactly; do not simplify it.** The obvious pattern `interest.*abs()` is
  **blind to the setter at `:907`**, because `.map(f64::abs)` has no parentheses after `abs` —
  verified: `interest.*abs()` returns `:551` and `:1590` only, while the corrected pattern returns
  all three. A builder could remove the two consumer sites, run the naive check, watch it pass,
  and ship with the setter intact — which changes *nothing observable*, since the setter alone
  annihilates the sign. That is the precise subset-removal failure this wave warns about, green-lit
  by its own verification. (This defect was in v2's first draft and is recorded rather than
  quietly fixed, because it is the exact class of error this run keeps hitting: a sound conclusion
  resting on a search that could not have found the counterexample.)

  (v1's J6 said `dcf_model.rs` may change only inside `mod tests`; that invariant is deleted — it
  is incompatible with LD-1 being in scope.)
- **J7** The published FCFF bridge is **not** invariant to the sign of the interest series. A
  net-income filer's interest is subtracted where it used to be added. (v1's J7 asserted the
  opposite; section 0 explains why that property was the defect rather than the guarantee.)
- **J8** No negative-interest year is silently dropped from the accounting cost-of-debt fit. Either
  the year is admitted or the channel refuses for that issuer (T2.7).
- **J9** No absent interest value is materialised as a zero anywhere in the diff.

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
| W2-P06 | positive | FCFF bridge | a LIN-type filing: net **expense**, filed negative, negated by the convention | the bridge runs | it arrives as a **positive** expense | T2.6 test 1 |
| W2-P07 | positive | FCFF bridge | a BAC-type filing: net **income**, filed positive | the bridge runs | it arrives **negative** and is subtracted, not added | T2.6 test 2 — the brief's §0 defect, fixed |
| W2-P08 | positive | rate resolution | an issuer whose interest years are all positive | the accounting cost-of-debt fit runs | the result is bit-identical to today | T2.7; the change is scoped to the negative case |
| W2-N04 | negative | rate resolution | an issuer with one negative-interest year among several positive ones | the accounting cost-of-debt fit runs | the channel **refuses for that issuer**; it does not fit on the surviving years | J8 — no selection on the dependent variable |
| W2-N05 | negative | fixture capture | a point with an absent interest value | capture runs | an explicit null is emitted, never `0.0` | J9, T2.8 |
| W2-R01 | regression | FCFF bridge | an interest series and its exact negation | the bridge runs on both | the published series **differ** | J7 — inverted from v1 on purpose; this test is what fails if an `.abs()` is ever restored |
| W2-R02 | regression | normalizer | `InterestPaidNet` | the class resolves | it is still **not** in the class | R1 is not weakened by R2 |
| W2-R03 | regression | contract validator | the regenerated targets | `validate-contracts.ps1` runs | no target is stale | J4; the drift the generator's header comment exists to prevent |

#### Automation level

Unit tests in `edgar.rs`, `sec_normalization.rs`, `dcf_model.rs` and `driver_resolution.rs`; the
frozen fixture corpus test; one Kotlin unit test; the PowerShell contract validator; **and a full
live QA pass**, which this wave does *not* discharge by proof.

#### Fast checks (builder runs; about 10 seconds each)

| Level | Command |
| --- | --- |
| unit | `cargo test --lib sec_normalization::` and `cargo test --lib edgar::` |
| unit | `cargo test --lib dcf_model::` — T2.6's three sign-preservation tests |
| unit | `cargo test --lib driver_resolution::` — T2.7's guard ruling |
| contract | `pwsh scripts/validate-contracts.ps1` |
| generator | generate to a scratch `-OutputRoot` and diff against the committed targets |
| lint | `cargo fmt -- --check` and `cargo clippy -- -D warnings` |
| grep | `sec-driver-normalization/8` appears only under `_bmad-output/**/.memlog.md` |
| grep | `grep -nE "interest.*(abs\(\)\|f64::abs)" src/dcf_model.rs` returns nothing (J6 — the naive `interest.*abs()` is blind to the setter; see J6) |
| scope | `git diff --name-only` matches the ownership matrix |

#### Deferred checks (orchestrator runs)

**The automated gate is mandatory and unconditional** (r1 directive P0-F). It is *not* discharged
by any proof — v1 folded it into a generic full-suite run and dropped two of its four commands.
All four run, by name:

| Command | Why this one |
| --- | --- |
| `cargo test --lib dcf_model::` | the LD-1 removal site |
| `cargo test --lib valuation_baseline::` | the published-value baseline |
| `cargo test --lib quant_lens::` | reads driver series outside the FCFF bridge |
| `npm run test:e2e:native:cof` | COF is the most-affected issuer (19 years) and is valued through the **bank/residual-income** path, not the FCFF bridge — no `dcf_model.rs` test reaches it |

Also: `cargo test --lib` (full Shell suite) and the Android unit tests (Gradle) for T2.5.

**Live measurement**, over the four issuers named in section 0 — **COF (19), DAL (15), CHTR (12),
BKR (11)** — plus **BAC** as the net-income control, plus the **full 26-name high-signal cohort**
(r1 directive §5.2). v1 substituted LIN for COF and dropped the cohort entirely; both are restored.
LIN remains as the worked example of an exact negation, not as a substitute for COF.

Report per issuer: interest-expense driver-years before and after, published intrinsic value
before and after, and accounting-cost-of-debt channel resolved/refused before and after (T2.7).

**Live QA runs in full.** v1 discharged it with an invariance proof; section 0 deletes the premise.
This wave moves published numbers by design, so `AGENTS.md`'s live valuation QA applies
unconditionally, on profile `qa`, reusing one process.

`core_versus_current_engine_on_the_pinned_cohort` is **not** evidence for this wave: it reads the
stale pre-`/8` `core_driver_data_deep.json` (F19) and structurally cannot show the sign effect.
Do not cite it.

#### Pause triggers (stop and report; do not decide)

Wave 2 is the run's only wave that moves live published numbers, so the brief's pause conditions
bind here most sharply. Stop and report if:

- **(a)** any anchor — **PG, GOOGL, AMZN, MSFT** — moves more than **±5%** or changes side of a
  gate. Do not judge whether the move is worth it; that is Juan's call.
- **(b)** an operating issuer goes from valued to unavailable, or the reverse, other than through
  T2.7's declared refusal.
- **(c)** the accounting cost-of-debt channel refuses for more issuers than T2.7's ruling predicts,
  which would mean net-filing is more widespread than the four measured issuers suggest.
- **(d)** any of the three protected failing tests changes state (see *Done when*).

#### Evidence of pass

The names and results of T2.6's three sign-preservation tests and T2.7's two guard tests, pasted
verbatim. All four mandatory gate commands with their output. The per-issuer live table including
COF and the 26-name cohort. The live QA record with actual commands and results. The scratch-root
generator diff. The `validate-contracts.ps1` output. The Kotlin test name. The
`sec-driver-normalization/8` and `interest.*abs()` search results. `git diff --name-only`.

#### Documentation deliverables

`shared/contracts/README.md` (`## Files` entries plus R1/R2 as two rules),
`_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md` (the `/9` record), and
the generator header line. **No** edits to `docs/index.md` or `AGENTS.md` — Wave 4 owns those and
will add the anti-pattern row and the fingerprint-bump procedure step.

#### Done when

J1 through J9 hold. The `/9` bump is in all five sites and two of them came from the generator.
All three `.abs()` sites are gone. T2.6's and T2.7's tests pass and are quoted in the completion
statement. All four mandatory gate commands ran and are recorded with their output. Live QA ran in
full. No pause trigger fired, or one fired and the wave stopped. The Shell failing set is still
exactly, by name:

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

**T3.2 — `variance_of_centre` is the squared standard error of the retained sample, and it has a caller.**
The retained sample's variance divided by the retained count — **the same retained set that
produced `centre()`**, which is the whole point (D2). The rustdoc says, in the doc comment itself:
this is the width of the **centre**, not of the sample; it is computed over the retained
observations only; it is therefore a mild understatement of the estimator's uncertainty; and under
inverse-variance fusion an understated variance is an overstated weight.

v1 shipped this accessor with **no caller anywhere**, which made the doc comment its only defence.
Under Juan's Q2 ruling it has a real consumer in T3.8. An accessor with a live caller and a
matched kept set is a fixed defect; an accessor with neither was a trap.
*Acceptance:* `centre()` and `variance_of_centre()` demonstrably derive from one kept set — a test
plants an outlier and asserts the reported width is the retained width, **not** the full-sample
width; the doc comment carries the direction of the residual bias in words, not just the formula.

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

**The index mapping is the trap here, and it is the same defect class as Wave 2's parallel sign
array.** `robust_centre` runs over `series.flatten()`, so `outliers()` returns indices into the
**flattened** vector, while T3.4 must exclude pairs identified by `(issuer, year)`. That is
positional indices crossing a shape change with no length or identity invariant to catch a
misalignment — and a misalignment here silently kills the *wrong* issuer's pairs, which no test
that only counts dropped pairs would detect. **Do not** carry raw `usize` indices across the
boundary. Either have the flatten carry `(issuer, year)` alongside each value and map back through
that, or have the outlier accessor return typed keys rather than positions. A test plants one
extreme value in a **known** issuer and asserts that *that* issuer's pairs are the ones removed.
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
| `:536` trailing growth mean **and variance** | one issuer's own history | **replaced** by `robust_centre`, centre and width together (T3.8). Juan's Q2 ruling. v1 marked this "kept"; see T3.8 for why that reasoning was both out of order and wrong |
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

**T3.8 — Replace the naked mean AND the naked variance at `valuation_core_adapter.rs:536`.**
Juan's Q2 ruling. This is the site the brief calls *"the worse of the two"*, because it supplies
both the trailing-growth channel's point estimate **and** its precision into `fuse`.

Today: `let (Some(mean), Some(variance)) = (mean(&growth), sample_variance(&growth))`, with
`variance / growth.len()` handed to `UncertaintyBasis::SampleVariance`.

Required shape — all three parts, or the change is not worth making:

1. **Centre from `robust_centre`.** Not `mean`.
2. **Width from the same kept set.** `RobustCentre::variance_of_centre()`. A robust centre paired
   with the existing untrimmed `sample_variance` would be *worse than doing nothing*: a clean
   level with a contaminated width, silently reassigning weight between the trailing and forward
   channels. `robust_mean` returns a scalar only and is insufficient here on its own.
3. **`observations` reports the KEPT count**, `RobustCentre::retained()`, not `growth.len()`.
   Reporting n=18 for a width computed from 15 points overstates precision — the same defect in a
   third place.

**Why v1's "kept" reasoning is deleted rather than restated.** v1 argued a robust centre *"would
refuse far more often than it would help."* The sample is `annual_revenue_growth()` —
consecutive-year log ratios, so `n = years − 1`, typically 9–18 on these 10–19 year histories.
`robust_mean` refuses at `n < 3`, or when trimming leaves `kept < 3` (`numerics.rs:187`). Reaching
fewer than 3 survivors from 18 points needs ~15 discarded at `|z| > 3` on median/MAD, which does
not occur. It refuses only where an issuer has ≤3 revenue years — precisely the issuers that
should not carry a fitted growth posterior. The refusal-rate objection is unsupported.

**This channel is revenue growth**, and Decision 3 says the revenue coefficient cannot be reused
as NOPAT growth, so this code is slated for rebuild. That is **not** a reason to defer, and must
not appear as one anywhere in this plan.

*Acceptance:* `mean(` and `sample_variance(` are both gone from `:536`; a test plants a contaminated
growth year and asserts the reported `observations` equals the retained count and the reported
variance is the retained width; the refusal-rate change is measured (T3.7) and reported.

**T3.9 — Documentation.** `docs/valuation-aggregation-audit.md`: the aggregation rule as it stands
(`AGENTS.md`'s Aggregation section); `robust_centre` versus `robust_mean` and why only one of them
takes a threshold **and when the other one stops taking one (T5.11, R3)**; the `variance_of_centre`
design — that centre and width come from one kept set, that this kills a monotone-in-contamination
weighting bias, and that a mild understatement remains as **LD-5** with its trigger.

**Do not repeat v1's "no effect today" note.** v1 wrote that the width limitation *"changes nothing
economically today, because the forward channel is always absent."* That was true only while the
accessor had no caller. T3.8 gives it one: the trailing-growth channel's precision now flows to
`fuse`. The doc states the live consumer, not the absence of one.

Also required: the exclude-versus-include decision for pairs, with the reasoning; T3.6's eight-row
table with every reason; T3.7's measured refusal-rate change with its staleness caveat; and
**`persistence` and `fade_per_year` before and after, as committed numbers in this document**
(today `persistence = 0.1709`). The r1 directive required old/new `persistence` in the audit doc;
v1 left it in an orchestrator report table only. Wave 4's growth charter must cite the **new**
number, or it is written against a value Wave 3 just invalidated.

One named follow-up — `valuation_probes.rs:465-466` calls `robust_mean` and then `standardize`
again purely to recover a count `RobustCentre` now returns directly, and uses a fully-qualified
path against constraint 10. That file is **out of Wave 3's scope** (Wave 1 owns it in R1); T5.11
fixes it in R3.

#### Invariants

- **K1** Exactly one place in the workspace filters a sample by z-score.
- **K2** `MAX_ABSOLUTE_Z` is still `3.0` and no new call site passes any other value. The knob
  itself is removed from `robust_mean` by **T5.11 in R3** — a scheduling constraint (§2.0), not a
  permanent exemption.
- **K3** `robust_centre` takes no threshold parameter.
- **K4** An observation excluded from the pooled centre is excluded from every pair in the
  persistence fit, and no pair is created across the resulting gap.
- **K5** A retained count below three refuses; a retained count of one or two is unreachable.
- **K6** `centre()` and `variance_of_centre()` derive from the **same** retained set. No caller
  anywhere pairs a robust centre with an untrimmed variance.
- **K7** `robust_mean`'s public signature and behaviour are unchanged **in this wave** (T5.11
  changes them in R3).
- **K9** No naked `mean(` or `sample_variance(` remains at `valuation_core_adapter.rs:280` or
  `:536`, and `observations` at `:536` reports the retained count.
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

`docs/valuation-aggregation-audit.md` (new, content specified in T3.9). Rustdoc on
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
guard **after** destructuring yields `OutOfPolicyRange`.

**If a cell disagrees with observed behaviour: STOP and report. Do not edit the cell.** v1's rule
was *"correct the cell to the observed value and report the correction"* for 32 of the 34 rows.
That pre-authorises a builder to rewrite the **specification** to match the code, in a repo where
constraint 11 says the Gherkin outlines **are** the contract — so a genuinely wrong
`AbsenceReason` would be laundered into the spec by the one process meant to catch it. Juan has
confirmed this rule: a spec change goes through the FR-29 ADR path with rationale and replacement
tests, never through a builder's justification cell.

The orchestrator decides whether the cell or the code is wrong. The cost is one round-trip, not
one round — the builder runs `cargo test -p valuation-core` in its first ten-second loop, so a
disagreement surfaces almost immediately. The two `return-absent` rows are unchanged in kind: a
disagreement there means the code is wrong and T5.2/T5.3 are incomplete.
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
`operating_valuation::terminal_payout_bps` is `pub` — **verified**, `pub fn terminal_payout_bps(`
at `src/operating_valuation.rs:212` — and is therefore pure and reachable without touching a
forbidden file. (v1 asserted this without evidence; had it been module-private the builder would
have faced "cannot write the mandated test" against "must not modify `operating_valuation.rs`" and
resolved it under time pressure. It is `pub`, so no fallback is needed.) In `valuation_core_measurement.rs` (which this wave owns), add
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
- **the latent-defect register (D7), LD-1 through LD-7**, each with its owner and trigger
  condition. Wave 4's economic contract links to it; the ADR is where it is defined.

**T5.10 — PRD and addendum.**
`prd.md`: retitle FR-29 (approximately *"An absent return on capital refuses rather than
valuing at the neutral line"*) and rewrite its body, **keeping the FR-29 identifier** (D8) so the
record reads as a changed contract. Add one sentence naming the residual-income form (D4). Extend
`addendum.md` section B.5 the same way. **`prd.md` stays `status: draft`** (brief §2) — do
not promote it.
*Acceptance:* `status: draft` is unchanged; `grep FR-29` finds no surviving text asserting
value-neutral substitution.

**T5.11 — Remove the public threshold knob from `robust_mean`. Constraint 6, closed by construction.**
This lands in R3 rather than in Wave 3 for one reason: `robust_mean`'s single external caller is
`valuation_probes.rs:465-466` (F18), and Wave 1 owns that file concurrently in R1 (§2.0). By R3
both R1 waves have merged and the file is free.

- `pub fn robust_mean(sample: &[f64]) -> Result<f64, AbsenceReason>` — no threshold parameter,
  delegating to `robust_centre`. `MAX_ABSOLUTE_Z` stays `3.0` and stays the only threshold.
- Update `valuation_probes.rs:465-466`, which today calls `robust_mean` **and then** `standardize`
  again purely to recover a discarded count that `RobustCentre` now returns directly. Use
  `robust_centre` once. Import it — the current call is fully qualified
  (`valuation_core::robust_mean`) against constraint 10.

v1 kept this knob permanently, defended by a doc prohibition plus a grep. A public parameter any
call site can set to `4.0` is the threshold-relaxation vector constraint 6 exists to close; a
convention plus a grep is a promise, not a constraint.
*Acceptance:* `robust_mean` takes one argument; no call site in the workspace passes a threshold;
no fully-qualified `valuation_core::` path remains in `valuation_probes.rs`; §6.5's constraint-6
row reads *satisfied by construction* with no qualifier.

**T5.12 — Turn F1 from a grep into a compile-enforced proof.**
F1 — `valuation_core_adapter::value()` has no non-test caller — carries the live-QA exemption for
**two** waves (3 and 5). v1 established it by grep, and a grep is evidence about text, not about
the call graph. Gate `value()` behind `#[cfg(test)]` locally and confirm the crate still builds;
if it does, F1 holds by the compiler. Revert the gate; record the result.
Thirty seconds, and it replaces the weakest load-bearing claim in the plan.
*Acceptance:* the result is recorded in the wave report and in D7's standing-condition note. If
the crate does **not** build, F1 is false, both waves' live-QA exemptions are void, and the wave
stops and reports.

#### Invariants

- **L1** No `unwrap_or` on a return-on-capital or return-on-equity path in `valuation-core`.
- **L9** `robust_mean` takes no threshold parameter, and `MAX_ABSOLUTE_Z` is the workspace's only
  z-threshold (T5.11). (`L8` is already taken by the empty-dependency invariant.)
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
| W5-E02 | edge | adapter | every operating issuer in the pinned test cohort | `value` runs | **none** produces a number, and all refuse for the same named reason | T5.8, exhaustive rather than six assertions. **Its doc comment must say: when an estimator is promoted, DELETE this test — do not weaken it.** As written it pins the Core dark; a future reader will otherwise see it fail and "fix" it by restoring a fallback, which is the precise regression this run exists to prevent |
| W5-E03 | edge | schema | the two edited Examples tables | `cargo test -p valuation-core --test schema` | all seven rules pass, including per-table rectangularity | F11 |
| W5-R01 | regression | Core | an absent discount rate | `intrinsic_value` runs | still `not_reported` | the existing refusal reasons are untouched |
| W5-R02 | regression | legacy engine | an absent return on capital | `terminal_payout_bps` runs | it still substitutes the cost of equity | T5.7; LD-3 characterized, not fixed |
| W5-R03 | regression | reviewer | `prd.md` | review | `status: draft` | L6, brief §2 |
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
- **The automated gate, named** — v1 listed none for this wave, which is the same
  *"required gates relabeled optional"* anti-pattern Wave 2 was corrected for:
  `cargo test --lib dcf_model::`, `cargo test --lib valuation_baseline::`,
  `cargo test --lib quant_lens::`.
- Anchors PG, GOOGL, AMZN, MSFT: expected unchanged, because the Core is not wired (F1). Report
  the numbers. Any movement means F1 is wrong — stop and ask.
- **No live QA checklist for this wave** — but the claim now rests on **T5.12's compile-enforced
  proof**, not on a grep. v1 exempted two waves from live QA on the strength of a text search.
  If T5.12 shows the crate builds without `value()`, the exemption stands and is recorded with
  its evidence. If it does not, the exemption is void and live QA runs.

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
including the verbatim D5 statement and the LD-1 through LD-7 register. Every completion statement
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

**T4.1 — `docs/valuation-economic-contract.md`.** This is the brief's **gating artifact**: *"No
estimator comparison or target pre-registration is valid until this contract exists."* A contract
missing the reinvestment identity cannot gate anything, because that identity is the whole economic
argument. v1's bullet list covered six topics and **omitted most of the brief's enumeration**; the
required content is therefore the brief's list, item for item:

- **NOPAT** — defined at the filed-concept level.
- **Invested capital** — the two competing definitions (operating-asset build-up versus
  financing-side build-up) named, with the one this project uses and why.
- **Reinvestment** — and **organic investment** distinguished from it.
- **Acquisitions and divestitures** — how each enters or is excluded.
- **Capital-consumption treatment.**
- **`g` and `r`** — each defined, with its units.
- **Expected timing between investment and return** — the lag the economics assume.
- **Valid units** for every quantity above.
- **Valid absence states** — the full `AbsenceReason` set after Wave 5, each with the situation
  that produces it, and the rule that a reason is a claim about *why*, not a category of
  convenience.
- **The relationship between growth, return and reinvestment**, stated as the identity the brief
  formalizes: `FCFF = NOPAT − Reinvestment`; `ReinvestmentRate = g_NOPAT / r`;
  `FCFF = NOPAT × (1 − g_NOPAT/r)`; the Core's retention charge FR-28
  `C(t) = E(t) × (1 − g(t)/r)`. **Including the sequencing fact**: a NOPAT base alone charges
  reinvestment zero times and overvalues; ROIC alone on an FCFF base charges it twice; both
  together charge it exactly once.
- **Financial-company semantics, as its own named section** — and any other issuer class where
  ordinary invested-capital definitions do not apply. The brief requires this explicitly and v1
  omitted it entirely, which matters directly: COF is valued through the bank/residual-income
  path, so a contract silent on financial issuers cannot govern the issuer with the most
  affected years in Wave 2.
- **Growth**: what is being grown, over what base, and why the fade rate is one parameter
  governing both the growth path and the spread's erosion.
- **The two equivalence-class rules, R1 and R2** (D6), each with its example.
- **The latent-defect register**, LD-1 through LD-7 (D7), each with id, owner and trigger
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

1. **Exactly one primary endpoint, in the brief's own words.** *"Cross-sectional median absolute
   error between **predicted and realized incremental return over the three-year horizon**,
   evaluated on the common issuer-cutoff set."* Quote it verbatim; do not paraphrase.
   v1 wrote *"the MdAE of the published intrinsic value against the realized outcome"* — a
   different quantity in different units with the three-year horizon dropped. The pre-registration
   is the one document whose entire value is that it did not drift, so restating its endpoint in
   the document that freezes it defeats the instrument. Define the notation `MdAE` on first use.
2. **The comparison is paired, against a named benchmark, on a set fixed in advance.** `prior_only`
   — the model with the return-on-capital channel absent — evaluated on the same issuers, years
   and cutoffs. Two things must be stated, and v1 stated only the first:
   - **Pairing**: same issuers, same years, same cutoffs. An unpaired comparison against a
     different sample is not evidence.
   - **Set construction**: the cross-section is **pre-declared independently of any candidate's
     ability to resolve it**. "Common issuer-cutoff set" read as *the intersection of what both
     candidates happen to resolve* is precisely the win-by-abstention loophole Decision 1 exists
     to close — a candidate that abstains on hard cases would shrink the set to the easy ones and
     look better for it.
   - **Abstention is scored, not dropped.** Either substitute the benchmark's prediction for an
     abstained cell, or apply an explicit pre-registered penalty — stated before any candidate
     runs. **Dropping abstained cells from the primary endpoint is a prohibited analysis**, and
     the document says so in those words. Without this, element 7's coverage exclusion *creates*
     the loophole instead of closing it.
3. **The uncertainty is issuer-clustered.** A bootstrap resampling **issuers**, not issuer-years,
   because an issuer's years are not independent draws. State the number of resamples before
   running any.
4. **A concrete materiality threshold, with its derivation — and the derivation is required.**
   Not "an improvement". A number, in the endpoint's units. The brief requires it be *"derived
   from how return-on-capital estimation error propagates into reinvestment and valuation"*, and
   that propagation path is mechanical, not a matter of taste: `FCFF = NOPAT × (1 − g/r)`, so an
   error in `r` moves the reinvestment rate, which moves value. **Do the propagation derivation.**
   v1's *"if the derivation is judgement, say so and show the judgement"* pre-authorised skipping
   the one threshold in this plan that genuinely is derivable. Judgement is permitted only for the
   final *decision-relevance* step — what size of error reduction changes a decision a user of
   this screener actually makes — and that step is labelled as judgement where it appears.
5. **A multiplicity rule.** How many comparisons will be run in total, and what happens to the
   decision threshold when more than one is run. A pre-registration that permits unlimited looks
   at unlimited endpoints pre-registers nothing.
6. **Secondary diagnostics may veto, never promote.** List them explicitly. A secondary that
   improves cannot rescue a primary that fails.
7. **Coverage is excluded from the veto set**, with the reason written out: a change that refuses
   more often will nearly always look better on error while being worse for the user, so coverage
   is *reported* alongside the primary and is never allowed to act as a gate in either direction.
8. **The anchors are excluded from the veto set too.** PG, GOOGL, AMZN and MSFT are diagnostics
   only (brief constraint **9** — v1 cited constraint 12, which is the empty-dependency rule).
   They appear in every report and in no gate.
9. **The ±5% anchor threshold does NOT live in this document.** Its content is correct and stays
   correct — it is a communication trigger, not an acceptance criterion, and it is not derived
   from anything; it is Juan's stated instruction in brief section 5, and saying so plainly is
   better than inventing a derivation for it. But a non-derived, non-gating convention sitting
   among pre-committed decision rules invites a later reader to treat it as pre-registered. It
   belongs in the economic contract's operating-protocol section (T4.1) and in each wave's pause
   triggers. This element exists only to say where it went and why.
10. **A freeze protocol.** What is frozen (endpoint, benchmark, cross-section, cutoffs, threshold,
    resample count, multiplicity rule), when it is frozen (before any candidate is run), where the
    frozen copy lives, and what an amendment costs — an amendment made after an outcome is
    observed invalidates the pre-registration, and the document must say so itself.
11. **A no-outcome-observed attestation, which names its own weakness.** A line stating that at
    the time of freezing, no candidate had been evaluated against the endpoint. Without it the
    freeze is unverifiable. The document must also state, in its own text, that **this attestation
    is self-certified**: it is written by an agent that will not run the harness, and no external
    party attests the freeze. T4.8's checkpoint mitigates ordering, not incentive. A reader who
    does not know that is reading a stronger guarantee than exists.

**T4.5 — `docs/roic-target-specification.md`.** Brief scope names *"Item 5 — the target
specification and pre-registration"* as in scope. v1 planned the pre-registration and **not the
target specification**: it gestured at "the target quantity" in one clause of T4.2, and none of
the brief's enumerated decisions appeared anywhere in the plan. An in-scope deliverable that
appears in no task will not be built.

The brief is explicit that `ΔNOPAT / ΔIC` *"is not yet a complete target definition."* Before the
harness runs, this document pins every one of the following, each with its rationale:

| # | Decision |
|---|---|
| 1 | the exact three-year windows |
| 2 | whether changes use beginning, ending or average capital |
| 3 | lag treatment |
| 4 | organic versus acquired capital |
| 5 | acquisitions |
| 6 | divestitures |
| 7 | impairments |
| 8 | restructurings |
| 9 | currency effects |
| 10 | restatements |
| 11 | `ΔIC = 0` |
| 12 | small denominators |
| 13 | negative invested capital |
| 14 | negative changes in invested capital |
| 15 | negative NOPAT |
| 16 | issuer-class exclusions |
| 17 | all data-quality exclusion rules |

Written **before any candidate result is inspected**, and carrying the brief's standing rule
verbatim: *"Any subsequent change to the target or exclusions is a NEW experiment requiring a new
untouched holdout."*

Ordering: this document depends on T4.1 (the economic contract is the gating artifact) and is
depended on by T4.4 (a pre-registration cannot freeze an undefined target). T4.8's checkpoint
covers it.
*Acceptance:* all seventeen rows present, each with a stated decision and a reason; the
new-experiment rule quoted verbatim; no candidate result referenced anywhere in the document.

**T4.6 — `docs/index.md`.** Add every new document from this run: `sec-point-in-time-provenance`,
`roic-target-specification`,
`valuation-aggregation-audit`, `valuation-economic-contract`, `roic-research-charter`,
`growth-research-charter`, `roic-preregistration`, and the ADR. The file is flat and has never
held an ADR (F16), so **create an `## Architecture Decisions` section** and record in the file's
own `## Maintenance Rules` that ADRs are indexed there.

**T4.7 — `AGENTS.md`, and the file's own rule about how to change it.**
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

**T4.8 — Intra-wave checkpoint.** Wave 4 is one builder writing **seven** documents, which is where
a plan quietly becomes a wish. Three checkpoints, not two: after **T4.1**, after **T4.5**, and
after **T4.4's skeleton** — headings plus the eleven numbered elements, before any prose. A
pre-registration whose threshold is written after the writer has seen a result is not a
pre-registration, and this checkpoint is what makes the ordering auditable.

**The checkpoint gates on coverage of the brief's enumerations, not on prose quality.** For T4.1,
walk the brief's economic-contract list item by item and confirm each is present — including
financial-issuer semantics and the growth/return/reinvestment identity. For T4.5, walk all
seventeen target-specification rows. One builder writing seven documents will produce six good
ones and one that quietly omits the list nobody re-read; a checklist read against the brief is the
only thing that catches it.

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
| W4-P05 | positive | reader | the economic contract | it is read for the register | LD-1 through LD-7 each carry an id, an owner and a trigger | D7 |
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
- The T4.8 checkpoint read-through, which the orchestrator performs.
- Final assembly of the run report: the anchor table from every wave, the coverage deltas, the
  T1.7 three-column count, the T3.7 refusal-rate change, T3.8's `persistence` before/after,
  Wave 2's per-issuer table including COF and the 26-name cohort, and T2.7's channel-refusal count.
- **The D5 statement, verbatim, in the RUN-level report.** v1 required it in Wave 5's completion
  statements only. The failure mode is a *run* report that reads "FR-29 removed" with no mention
  that `operating_valuation::terminal_payout_bps` still substitutes the cost of equity on the live
  legacy path. A reader of the run summary is exactly the reader most likely to draw that wrong
  conclusion.
- **The honest statement of what Wave 2 delivered**: work-order item 2 is complete — the sign is
  corrected *and* survives to the bridge (LD-1 closed). Where the BAC-class double-add previously
  persisted, name the issuers whose values moved and by how much.

#### Evidence of pass

The **seven** document paths with their heading outlines; the `docs/index.md` and Documentation Map
diff showing they agree; `git diff AGENTS.md` showing the pre-existing edits preserved;
`git status --short` showing the fixture unstaged.

#### Documentation deliverables

This wave is entirely documentation: `docs/valuation-economic-contract.md`,
`docs/roic-research-charter.md`, `docs/growth-research-charter.md`,
`docs/roic-preregistration.md`, `docs/index.md`, `AGENTS.md`.

#### Done when

M1 through M7 hold. The T4.8 checkpoint happened **before** the pre-registration's prose was
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
- T5.4's derived `reason` cells — on any disagreement the builder **stops and reports**; the
  orchestrator decides whether the cell or the code is wrong. (v1 let the builder correct the cell
  to observed behaviour, which is authority to rewrite the contract. Constraint 11 stands.)

Everything else that could have been a decision is decided in section 1.5.

---

## 4. Baseline and the protected failing set

Re-establish before Round 1 and quote in every wave's report.

| Suite | Command (from `apps/windows/src-tauri`) | Recorded baseline |
| --- | --- | --- |
| Shell library | `cargo test --lib` | **518 passing, 22 ignored, 3 failing** |
| Core library | `cargo test -p valuation-core --lib` | **89 passing** |
| Core schema | `cargo test -p valuation-core --test schema` | **7 passing** (F10 — the brief's "six rules" is wrong) |
| Core cucumber | `cargo test -p valuation-core --test cucumber` | **record before Round 1** — v1 omitted this baseline entirely, and Wave 5 edits **both** feature files. The suite most likely to move was the one with no recorded starting count. |

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
| **R2** | `wave-2` | 1 |
| **R3** | `wave-5` | 1 |
| **R4** | `wave-4` | 1 |

**Serialization fixes wave-to-wave attribution and creates a time-axis attribution problem in its
place.** Four sequential rounds run against a **live** external provider, with live measurements in
R1 (T1.7) and R2 (the per-issuer table). A new 10-K landing between rounds is indistinguishable
from a code-caused delta — the exact failure serialization was adopted to prevent, relocated from
the wave axis onto the time axis. Two mitigations, both mandatory:

- **Cache the `companyfacts` payloads at the R1 baseline capture** and reuse the *same* cached
  payloads for R2's before/after comparison. R2's "before" must not be a fresh fetch.
- **Record the retrieval timestamp on every live table**, in every wave report.

Caching narrows the window; it does not close it. Any residual unexplained delta is a stop-and-ask,
not a rounding artifact.

**Also re-establish the live baseline, not just the test baseline.** §1.7's assumption 5
re-establishes the *test-suite* baseline before Round 1, but nothing re-established the **live
per-issuer driver-year** baseline after Wave 1 merges — which would leave Wave 2's "before" either
stale or ambiguous. Capture it as part of R1's exit, from the cached payloads.

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
| 6 | No naked averages; `MAX_ABSOLUTE_Z` does not move | D2; K1; K2; K9; T3.6's eight-row audit; **`:280` and `:536` both replaced** (T3.4, T3.8 — Juan's Q2 ruling). Satisfied by construction on `robust_centre`; satisfied by **convention** on `robust_mean` until **T5.11** lands in R3, after which by construction there too (L9) |
| 7 | Never `git add -A`; stage explicitly | Section 4; T4.7; W4-E02 |
| 8 | The high-signal fixture stays unstaged | Section 4; W1 and W4 deferred checks; W4-E02 |
| 9 | Anchors PG/GOOGL/AMZN/MSFT are diagnostics only | T4.4 item 8; every wave reports them and none gates on them |
| 10 | Never FQN, always import; one assert per test; KISS; DRY | T5.7's import; the collected-violations note in W1; D2's single `trimmed`; T2.2's "do not write a second slice formatter" |
| 11 | The Gherkin outlines are the specification | T5.4 adds a **column** and edits rows; a new outline would need a `manifest.toml` entry (FR-44) and none is added |
| 12 | `valuation-core`'s dependency list stays empty | K8; L8; W3-R03; W5-R04 |
| 13 | `Observation<T>` stays a sum type | D3 adds a variant, not a default; L6 |

*(The `prd.md status: draft` rule is **brief §2**, not constraint 13. v1 cited "constraint 13" for
it in T5.10, in W5-R03 and in this table; a builder checking the number would have read the
`Observation<T>` rule instead. Corrected in all three places.)*

### 6.6 Pause triggers, restated for every builder

Stop and ask Juan — do not decide — when any of these occurs:

- **(a)** two designs give materially different economic results and no test decides between them;
- **(b)** an anchor moves more than plus or minus 5 percent, or changes side of a gate;
- **(c)** a choice between fixing something and refusing to value it.

Q1 and Q2 were instances of (c) and (a) respectively. **Both are now answered** (section 0), so
neither is open — but both are worked examples of what escalation looks like, and a builder that
finds itself reasoning toward "this would refuse more often than it helps" or "the proof shows this
can't matter" should recognise the shape and stop.

**Wave 2 carries the sharpest instance of (b)** in this run: it is the only wave that moves live
published numbers. Its pause triggers are restated locally in the wave.

---

## 7. Changelog, v1 to v2

Driven by **Juan's rulings on Q1 and Q2**, plus the r2 review round (Sensei: `revise`, 10 P0s,
5 anticipatory passes; Advisor: `revise`, 1 P0, 4 passes). Every P0 is adopted. Where a reviewer's
recommendation conflicted with a Juan ruling, the ruling governs and the conflict is noted below.

**The two rulings**

1. **Q1 — LD-1 pulled into scope.** v1's option table told Juan that option (iii) *"recovers all
   57 issuer-years"*; it does not, because the blanket `.abs()` annihilates the corrected sign for
   both filer classes. v1's own T2.6 would have *proved* the change was inert. Wave 2 now removes
   all three `.abs()` sites, rules on the negative-interest guard that consequently comes alive
   (T2.7), sweeps the fabricated-zero and FQN defects in the same file set (T2.8), and gives up
   its live-QA discharge along with the premise that justified it.
2. **Q2 — `:536` replaced, width included.** v1 marked it "kept" on a refusal-rate argument that
   is unsupported on this cohort (`n` is typically 9–18; refusal needs `kept < 3`). Centre **and**
   variance now come from one kept set, and `observations` reports the retained count (T3.8).

**Where a reviewer was overruled.** Sensei's P0-2 recommended **dropping** `variance_of_centre`
because nothing called it. Juan's Q2 ruling gives it a live consumer, which is the condition
Sensei's own second-order note named (*"with `:536` converted, a precision term is required and
this cannot be deferred"*). So the accessor ships — with the monotone-in-contamination bias fixed
by construction rather than documented (D2), which is the stronger form of the same finding.

**Where the plan's own claims were wrong (the pattern that matters most)**

r2 found that v1 — the revision whose changelog boasted of auditing v0's factual claims —
introduced **two new false "verified" claims** of its own, plus one true claim resting on an
unsound search. All three are corrected, and the document now carries a standing rule (see the
header) that a load-bearing verification claim must carry its evidence inline:

- the `driver_resolution.rs:117` guard is dead **everywhere**, not just on the production path —
  and that makes it *untested code about to go live*, the opposite of v1's reassurance;
- `RustSlice` is **not** malformed on an empty collection; only `KotlinList` is;
- the abs setter **is** the sole writer, but the search behind that claim could not match Rust
  struct-literal initialisation. Re-run correctly with three patterns; it holds.

**Restored, having been dropped from v1 without notice**

- The **mandatory automated gate** — `dcf_model::`, `valuation_baseline::`, `quant_lens::` and
  `npm run test:e2e:native:cof`, by name, in Wave 2 *and* Wave 5. `quant_lens` and the COF e2e
  test appeared **zero times** in v1's 1,883 lines.
- **COF** in Wave 2's per-issuer table (v1 substituted LIN, which is not one of the four measured
  issuers) and the **full 26-name high-signal cohort**.
- **Wave 2 pause triggers**, which v1 had for Wave 1 and not for the wave that moves live numbers.
- `persistence` / `fade_per_year` old and new **in the committed audit document**, not only in an
  orchestrator table.
- A **cucumber baseline** in §4 — Wave 5 edits both feature files.

**Newly planned, having been in scope but in no task**

- **T4.5 — `docs/roic-target-specification.md`**, all seventeen decisions the brief enumerates.
  Brief scope names item 5 explicitly; v1 planned the pre-registration and not the specification.
- **T4.1 rewritten to the brief's enumeration**, including reinvestment, the
  growth/return/reinvestment identity and its sequencing fact, and **financial-issuer semantics**
  as its own section — which matters directly, since COF is valued through the bank path.
- **T5.11** removes `robust_mean`'s public threshold knob (constraint 6 by construction, not by
  convention); **T5.12** converts F1 from a grep into a compile-enforced proof.
- **LD-5, LD-6, LD-7** added to the register; **LD-1 closed**; every trigger now names a detector
  or admits it has none.

**Corrected specifications**

- The pre-registration's **primary endpoint** is restored to the brief's words verbatim; v1 had
  silently substituted a different quantity in different units with the three-year horizon
  dropped, in the one document whose value is that it did not drift.
- **Abstention is scored, not dropped**, and the cross-section is pre-declared independently of
  any candidate's ability to resolve it — closing the win-by-abstention loophole Decision 1 names.
- **T5.4's correction rule inverted**: a builder that finds a `reason` cell disagreeing with
  observed behaviour now **stops and reports** instead of editing the specification to match the
  code. Constraint 11 says the outlines *are* the contract.
- The **Kotlin `qnameSigns` default is removed** — v1 forbade a silent default on the Rust side
  and then added one on the Kotlin side two tasks later, where a length check cannot catch it.
- **`as_of` is scoped to single-concept drivers** (LD-6); v1 asserted a general PIT capability the
  design does not provide for composed drivers, which is the entire FCFF bridge.
- **Materiality must be derived** through `FCFF = NOPAT × (1 − g/r)`; judgement is permitted only
  for the decision-relevance step.
- Constraint miscitations fixed (anchors are constraint **9**, not 12; `status: draft` is
  **brief §2**, not constraint 13).
- **Live payloads cached at the R1 baseline** and retrieval timestamps recorded, because four
  serial rounds against a live provider relocate the attribution problem onto the time axis.

---

## 8. Changelog, v0 to v1 (retained for the record)

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
22. **An intra-Wave-4 checkpoint** (T4.8), because one builder writing six documents is where a
    plan quietly becomes a wish.
