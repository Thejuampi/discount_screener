# Stage 1 — Refine (E2E question mode)

Session: `valuation-pit-contract` · Date: 2026-08-04
Refiner ran in E2E question mode with no file reads, per `agents/refiner.md`.
The orchestrator then ran read-only reconnaissance to resolve as many questions as
possible with **evidence instead of Juan's time**, per §5 ("show the measurement
rather than asserting the conclusion").

Result: **8 refiner questions → 1 genuinely open decision for Juan.**

---

## Orchestrator reconnaissance — the two findings that dissolved most questions

### F1. The new Core is not wired to production
`valuation_core_adapter::value()` has **zero non-test callers**. `src/lib.rs:53` declares
`pub mod valuation_core_adapter;` and `:55` declares `mod valuation_core_measurement;`, but:

- every test in `valuation_core_measurement.rs` is `#[ignore = "diagnostic: prints a table
  for a person to read"]` (`:122`, `:234`, `:298`) and asserts nothing;
- the only cross-module reference is `valuation_probes.rs:474`, which borrows
  `least_squares` — not `value()`;
- no Tauri command, `commands.rs`, or `engine.rs` path reaches it.

Everything the app publishes today comes from `dcf_model.rs` / `operating_valuation.rs`.

**Consequence:** the `:280` / `:536` naked-mean fixes and FR-29 removal cannot move a
published anchor valuation. **Pause trigger (b) cannot fire for this run's Core work.**

### F2. FR-29 removal is bounded — six assertions, not an avalanche
Complete blast radius of replacing the value-neutral substitution with an explicit
unavailable state:

| Kind | Count | Items |
| --- | --- | --- |
| Core unit tests | 2 | `an_absent_return_on_capital_is_value_neutral_rather_than_floored` (`projection.rs:502`); `an_absent_return_on_equity_values_the_issuer_at_book` (`residual_income.rs:315`) |
| Gherkin rows | 2 | `intrinsic-value.feature:32` (`return-absent`, `roc = ABSENT`); `residual-income.feature:31` (`return-absent`, `roe = ABSENT`) |
| Shell adapter tests | 2 | `an_absent_return_on_capital_values_at_the_neutral_line` (`:1057`); `a_complete_issuer_publishes_a_posterior` (`:1047`) |

`a_complete_issuer_publishes_a_posterior` breaks *because* ROC is absent for every issuer —
it is a transitive dependant and must be converted, not deleted.

**Consequence:** FR-29 removal **lands in this run**. The §2 deferral escape hatch is not needed.

---

## Question resolutions

### Q1 — `InterestIncomeExpenseNet`: drop, or admit conditionally? — **OPEN, FOR JUAN**
Priority P0 · Pause trigger (a) · **The one decision escalated.** See "Open decision" below.

### Q2 — What precision accompanies the robust point estimate at `:536`? — RESOLVED (assumption)
Evidence: `posterior.rs:71` `fuse` gives an `Absent` channel precision exactly zero, so it
"contributes nothing and the posterior falls back to whatever else is present." The forward
channel at `valuation_core_adapter.rs:545` is *always* `Observation::absent`. Therefore the
`:536` precision reassigns **no weight today** — it passes through as
`posterior_variance = 1/total_precision`, i.e. it solely determines the **published interval
width** of the growth posterior. Combined with F1 (nothing published), no economic result
changes in this run. **Trigger (a) does not fire.**

**Decision taken:** extend `valuation-core/src/numerics.rs` (do NOT add a second
implementation — brief §3.6) so one call returns the robust centre **and** the variance of
that centre computed from the **retained, post-exclusion** observations. Rationale: the
precision must describe the estimator actually used. A MAD scale over the full contaminated
sample would describe a different estimator than the one that produced the point estimate.
`MAX_ABSOLUTE_Z` stays `3.0`.

### Q3 — Pre-authorization to land the mean fixes through anchor movement? — DISSOLVED
Dissolved by **F1**. `:280` and `:536` are in `valuation_core_adapter.rs`, which nothing in
production calls. No anchor moves. Anchor deltas will still be **measured and reported** as
diagnostics. If a diagnostic delta unexpectedly shows a *published* anchor moving, the run
stops and asks — but on current evidence that path does not exist.

### Q4 — Financial issuers: taxonomy now, or named deferral? — RESOLVED (documents existing behaviour)
Evidence: the Core **already** implements the named deferral. `valuation_core_adapter.rs:349-364`:
a `BusinessClass::FinancialServices` issuer returns `Observation::absent(ProviderUnavailable,
"book_value")` — it classifies correctly and then refuses for the input it actually lacks.
Pinned by `a_bank_refuses_on_evidence_rather_than_being_valued_on_cash_flow` (`:1082`).
`BusinessClass` (`classification.rs:45`) is a closed world with no `Other`, classified from
sector/industry text (`classification.rs:92`), not SIC. COF is the only unambiguous financial
in the 28-issuer cohort (`valuation_probes.rs:42`).

**Decision taken:** the economic contract **documents the deferral that already exists** and
names its absence state. It does not invent a bank invested-capital formula. Writing a
taxonomy now would be widening the contract to publish numbers — the mirror image of Juan's
closing instruction.

### Q5 — Pre-registration: a numeral, or a derivation with the numeral pending? — RESOLVED (assumption)
**Decision taken:** ship a **concrete numeral together with the derivation that produced it**,
in the same commit, plus the numeral's sensitivity to its own inputs. The propagation is
analytic — through `ReinvestmentRate = g/r` into `C(t) = E(t)(1 - g/r)` — and needs no
harness. A pre-registration with a pending threshold is not a pre-registration.

### Q6 — The target spec's ~17 sub-decisions: batch or per-item? — RESOLVED (assumption)
**Decision taken:** decide all with per-item economic rationale; open the pre-registration with
a **"Decisions with material economic leverage"** section naming the 3-5 whose alternatives
move the target most, each with the rejected alternative and why. Nothing executes this run
(items 6/7 out of scope), so the written spec is the natural review point. No trigger fires.

### Q7 — FR-29 blast radius: which tests get converted? — RESOLVED by measurement (F2)
**Decision taken:** all six items converted, none deleted, none relaxed, with a manifest
mapping each to its old and new contract.

**Additional finding the refiner could not have known — a THIRD substitution site.**
`src/operating_valuation.rs:223`: `let observed = return_on_capital_bps.unwrap_or(cost_of_equity_bps);`
inside `terminal_payout_bps` (`:212`). This is the **legacy engine's** FR-29 equivalent and it
**is production-live**. It affects 4 rows of `shared/contracts/operating-valuation-router-v1.json`
(GDDY, WYNN, BSX, ALB) and 3 tests — one of which is a known-failing test we must not repair.

**Decision taken: site 3 is OUT OF SCOPE and stays.** Brief Decision 2 is explicit: "The old
engine may remain live in the Shell as a separate legacy module during module-by-module
replacement." Touching it would move published anchors (trigger (b)) and would repair or
disturb a known-failing test (brief §4). **The ADR must record site 3 explicitly** as
knowingly retained legacy, so it is not later mistaken for an oversight.

**Sibling site decision.** `residual_income.rs:108` (`unwrap_or(cost_of_equity)`, FR-31's form
of the FR-29 identity) **is in scope and is removed with site 1.** It is the same fabrication
in the same new Core, and Decision 2 says the new Core must not publish a number it cannot
justify. Its lane is currently unreachable from the adapter (Q4), so removal has zero
production consequence and pure specification benefit. Leaving it would be exactly
"preserving an unsupported fallback."

### Q8 — PIT carrier: extend `AnnualValue`, or wrap it? — RESOLVED by measurement
Evidence:
- `AnnualCandidate` (`edgar.rs:154`) **already carries** `end`, `fiscal_year`, `value_dollars`,
  `filed`, `consolidated`, and collapses to two fields at **`edgar.rs:262-265`**. The data is
  already in hand and is thrown away at one line.
- `AnnualValue` derives **only** `Debug, Clone` (`edgar.rs:71`). **No serde.** Never persisted
  to JSON or SQLite. Appears in **zero** Tauri command signatures. Adding fields breaks no
  persisted format and no IPC contract.
- All **31** construction sites are in `edgar.rs` alone (9 production, 22 test). Zero elsewhere.
- A provenance-bearing type **already exists**: `sec_normalization::SecFact` (`sec_normalization.rs:36`)
  carries `qname, taxonomy, value_dollars, start, end, unit, form, accession, filed, consolidated`
  — a strict superset of the brief's required fields — and `select_one_equivalent_per_end`
  (`:117`) already implements filed/accession precedence on it.
- Sites `edgar.rs:497` and `:532` currently **re-derive** the year by string-slicing
  `end.get(..4)` while holding the real `end` date — the exact "reconstruct availability from
  `year`" defect the brief forbids.

**Decision taken:** extend the annual observation in place with the provenance it already has
in hand, and **align its field vocabulary with `SecFact`** rather than inventing a third
spelling of "filed". Whether that is literally reusing `SecFact` or extending `AnnualValue`
to a superset is a **planner** call on DRY grounds; the binding requirement is that no layer
can answer availability from `year` alone, and that `edgar.rs:497`/`:532` stop re-deriving
years from string slices. `filed` and `end` are required; `accession` and fact identity are
`Option` because SEC genuinely may not supply them.

---

## Standing assumptions (carried forward)

1. **ADR location:** `docs/` is flat and contains no ADR. Land `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md`
   with a two-line convention note (first ADR sets the precedent). No `docs/adr/` directory.
2. `docs/index.md` is updated with all five new artifacts.
3. The growth research charter **picks no direction** — Decision 3 says neither is approved.
   It documents both, states the units problem (`0.1709` is revenue persistence, not NOPAT),
   and specifies the evidence that would select between them.
4. The `fuse` audit is a **written finding in the ROIC research charter only.** Nothing wired.
   The module doc at `posterior.rs:26-33` already states the caveat in the authors' own words
   and should be quoted rather than paraphrased.
5. Gherkin changes prefer **rows**; a new `Scenario Outline` requires a `manifest.toml`
   `[[outline]]` entry with non-empty `why_new` (enforced by `schema.rs:280`).
6. Any normalization change bumps past `sec-driver-normalization/8`, regenerates Rust **and**
   Kotlin, updates the fingerprint assertions at `sec_normalization.rs:344` and `:403` **and**
   `shared/contracts/sec-driver-normalization-fixtures.json`, and passes
   `validate-contracts.ps1 -Check`.
7. Coverage is reported, never gates — including inside this run's own decisions.
8. Any probe follows `valuation_probes.rs` convention: `#[ignore]`, prints a table, asserts nothing.
9. Staging is explicit and file-by-file. The high-signal fixture stays unstaged. `prd.md` stays `draft`.
10. `MAX_ABSOLUTE_Z = 3.0` untouched; `mean()` call site `:781` (least-squares centering) left
    alone as defensible estimator arithmetic.

---

## THE OPEN DECISION FOR JUAN — Q1

**`InterestIncomeExpenseNet` resolves as an interest expense for COF (19 yrs), DAL (15),
CHTR (12), BKR (11). How do we fix it at policy level?**

Current contract entry (`shared/contracts/sec-driver-normalization.json:71-77`), qnames in
declared order — and **declared order is the whole semantics**, because
`extract_annual_any_with_shape` (`edgar.rs:318-320`) does
`by_year.entry(year).or_insert(value)`, so the first qname supplying a year wins it permanently
and later qnames only fill gaps:

```
1 InterestExpenseNonOperating      6 InterestExpenseOtherLongTermDebt
2 InterestExpenseNonoperating      7 InterestIncomeExpenseNet            <- net concept
3 InterestExpenseDebt              8 InterestIncomeExpenseNonoperatingNet <- net concept
4 InterestAndDebtExpense           9 FinanceLeaseInterestExpense
5 InterestExpense
```

Note there are **two** net concepts, not one. Both are gap-fillers at positions 7-8.

### Option (i) — remove both net concepts from the equivalence set *(orchestrator default)*
- **Precedent, three commits ago:** `InterestPaidNet` was removed from this exact list with the
  written rationale that "it is not an equivalent of the other qnames and `select_one_equivalent`'s
  gap filling was splicing cash into an accrual series year by year." A **net** concept is
  likewise not an equivalent of a **gross expense** concept. Same definitional argument, same list.
- Consistent with Decision 1 (abstention beats unsupported estimates) and with the closing
  instruction (do not preserve an unsupported fallback to keep publishing numbers).
- **Cost:** COF, DAL, CHTR, BKR lose those years of interest expense, and with them ROIC coverage.
  Per Decision 1 that cost may not itself argue against the fix.

### Option (ii) — admit the net concepts only under a condition (e.g. sign)
- **Not currently expressible.** The contract has no `sign` field; the word does not appear
  anywhere in it. `rejection` (`:42`) is a static category→state lookup. `precedence` (`:43`)
  ranks but never rejects. The closest precedent, `suppressSoftwareWhenTangibleQnameIn` (`:47`),
  is a **qname-membership** predicate, not a **value** predicate.
- The generator `scripts/generate-sec-driver-normalization-policy.ps1` emits exactly four driver
  fields (`RustOperator` `:89-98`); any extra field is **silently dropped** — already observable
  in that `rationale` never reaches the generated Rust. So (ii) needs new contract vocabulary,
  generator changes on both platforms, and a `DriverOperator` struct change.
- It would be the **first value-conditional rule** in the driver language.

### The honest counter-argument for (ii)
DAL and CHTR are heavily indebted with little interest income, so for them the net concept is
probably ≈ the gross expense, and (i) discards approximately-correct data. **But** "these
particular issuers are probably indebted" is per-issuer reasoning — the thing constraint 1
forbids, just applied mentally instead of in code.

### What the orchestrator is doing pending your answer
Proceeding with **(i)**, isolated to the contract JSON + rationale so reversing it is a one-line
change, and recording (ii) as a **named, unimplemented finding in the ROIC research charter**
together with the probe that would decide it (compare net vs gross for issuers filing both).

**Say the word and it flips.** Nothing else in the run depends on this branch.

---

## Refined package

### Goal
Replace the fabricated return-on-capital path with an honest one: make the data point-in-time
correct, make the arithmetic non-contaminated, write the economic contract that defines what is
being measured, and pre-register the experiment that could promote an estimator — **without
promoting one**, and without letting the new Core publish a number it cannot justify.

### Background
`return_on_capital` is hardcoded absent for all issuers (`valuation_core_adapter.rs:554`).
FR-29 (`projection.rs:223`) then substitutes `r := w`, collapsing `C(t) = E(t)(1 - g/r)` to
`E_0/w` — growth credited nothing, universally. The feeding inputs are themselves compromised:
annual observations discard the `filed` date the extractor already reads (`edgar.rs:262`);
two net interest concepts gap-fill into a gross-expense series with no sign normalization; and
two naked means contaminate the growth channel, one supplying both a point estimate and the
precision that sets the published interval width. The persistence constant everything rests on
is fitted on **revenue**, not NOPAT. The OLS levels-slope estimator is spurious-regression
diagnosed and permanently deleted, including as a refusal signal.

### In scope
Work-order items 1-5; all five repository artifacts; FR-29 removal + explicit unavailable-state
contract (now confirmed landable — F2), including the `residual_income.rs:108` sibling.

### Out of scope
Items 6, 7, 9; item 8 beyond its charter; selecting/promoting any estimator; wiring
`posterior::fuse` to the ROIC channel; the adapter change; **`operating_valuation.rs:223`
(legacy, knowingly retained, recorded in the ADR)**; AMZN policy/16; Android parity; the ROIC
fixture; `docs/project-context.md`; re-capturing `core_driver_data_deep.json`; PRD Finalize.

### Acceptance criteria
1. No consumer can determine an annual value's cutoff availability without provenance; a test
   distinguishes two facts sharing a `year` but differing in `filed`; `edgar.rs:497` and `:532`
   no longer re-derive the year by slicing `end`.
2. The interest fix changes `shared/contracts/sec-driver-normalization.json` with **zero** ticker
   literals in any branch; fingerprint moved past `/8`; both targets regenerated; all three
   fingerprint assertions updated; `validate-contracts.ps1 -Check` green.
3. `:280` and `:536` no longer compute `sum / n`; both route through the single extended
   primitive in `numerics.rs`; no second implementation; `MAX_ABSOLUTE_Z` still `3.0`.
4. The economic contract defines every term the brief enumerates, including financial-issuer
   semantics as the **named deferral that the Core already implements**.
5. The pre-registration names exactly one primary endpoint and one promotion rule, paired
   against `prior_only`, issuer-clustered resampling, a **concrete** threshold with its
   derivation, and a veto set excluding coverage that cannot promote.
6. The target specification resolves every enumerated sub-decision in writing.
7. FR-29: all six affected assertions converted, none deleted, none relaxed, each mapped
   old-contract → new-contract; ADR records the change **and** the retained legacy site 3.

### Definition of done
Five artifacts committed and indexed. Shell `cargo test --lib` ends with **exactly** the three
pre-existing failures — none repaired, none added. Core crate green including cucumber. Contracts
CI green. No ticker special-case, no market price in the value function, no clamp or haircut, no
relaxed threshold, no fabricated zero, no naked average, no `git add -A`, high-signal fixture
still unstaged, `prd.md` still `draft`. No estimator selected, no fallback preserved, no contract
definition narrowed to keep the Core publishing.
