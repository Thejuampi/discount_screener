# Plan review r1 — consolidated directives for plan.v1

Both reviewers returned **revise**, each with 4 anticipatory passes. This file is the
authoritative input for `plan.v1.md`. Where the two reviews conflict, the orchestrator's
resolution is recorded under **RESOLUTION** and is binding.

---

## 0. NEW MEASUREMENT — the legacy path is sign-blind (supersedes both reviews on W2 blast radius)

Both reviewers analysed W2 as **deleting** the two net qnames. That design was withdrawn
before their reports landed (see §1). Under the replacement design the blast radius is
different, and measurably smaller.

Verified call chain:

- `edgar.rs:1093` passes `interest_expense_dollars` into `FcfPoint::with_operating_drivers`.
- `dcf_model.rs:907` — the setter body is `self.interest_expense_dollars = interest_expense_dollars.map(f64::abs);`

Therefore every production consumer sees a pre-absolute-valued number:

| Site | Reads | Effect of a sign change |
|---|---|---|
| `dcf_model.rs:550` | `let interest = interest.abs();` | none (second abs) |
| `dcf_model.rs:1586-1594` | `.filter(is_finite).zip(tax).map(|i| i.abs())` | none (second abs) |
| `driver_resolution.rs:81` | `interest.abs() > EPSILON` | none |
| `driver_resolution.rs:117` | `if ... \|\| interest < 0.0 { return None }` | **dead guard** — cannot fire |
| `valuation_baseline.rs:900`, `valuation_fixture_capture.rs:131`, `valuation_probes.rs:344,354` | post-abs field | none |

**Consequence.** Negating a qname at the normalization layer produces a bit-identical
legacy DCF/FCFF output. The published intrinsic value cannot move. The sign convention's
entire effect lands on the new-Core ROIC path, which publishes nothing today (F1).

**Two things follow, and plan.v1 must carry both.**

1. W2 must **prove** the invariance rather than assert it: a test that the legacy driver
   path is sign-invariant for the interest series. Advisor P0-1's live-QA obligation is
   then *discharged by evidence*, not waived. If the proof fails, live QA fires in full.
2. The blanket `.abs()` is itself a latent defect and must be **named, not fixed here**.
   For a net-*expense* filer (LIN: `InterestIncomeExpenseNet` = −63M) `.abs()` is right by
   accident. For a cash-rich net-*income* filer it fabricates an expense add-back from
   reported income — brief constraint 5. Removing it **does** move published numbers and
   therefore needs its own wave, its own live QA, and its own anchor report. Record it in
   `docs/valuation-economic-contract.md` with a tracked id, owner and trigger condition.

---

## 1. Wave 2 redesign — declarative sign convention, not deletion (binding)

The deletion design is withdrawn. Measured filed data:

| LIN | 2022 | 2023 | 2024 | 2025 |
|---|---|---|---|---|
| `InterestIncomeExpenseNet` | −63M | −200M | −256M | −255M |
| `InterestExpenseNonoperating` | +63M | +200M | +256M | +255M |

Exact negations. BAC 2025 `InterestIncomeExpenseNet` = **+60,096M** against pretax 37,695M.
This is a **sign-convention** defect, not an equivalence-class defect. Deleting the qnames
would discard ~38 recoverable issuer-years (DAL 15, CHTR 12, BKR 11) that the work order
names explicitly.

W2 therefore adds a **declarative per-qname sign convention** to
`shared/contracts/sec-driver-normalization.json` — a static constant in the contract, never
a runtime value predicate, never a ticker branch (constraint 1). The gross-vs-net add-back
semantics question routes to the economic contract (W4), not to W2.

---

## 2. P0 findings — adopted

### P0-A (Sensei P0-1) — the fabrication survives in the only engine that publishes
`operating_valuation.rs:223` `let observed = return_on_capital_bps.unwrap_or(cost_of_equity_bps);`
stays live and out of scope. Required:
- every completion statement qualified: *"FR-29 removed from `valuation-core`; the equivalent
  substitution remains live in the production path (`operating_valuation.rs:223`) and is
  unaddressed by this run"*;
- a characterization test in the Shell naming the live fabrication;
- a tracked identifier, owner and **trigger condition** for the out-of-scope item.

**RESOLUTION — Sensei's Gherkin-row suggestion is NOT implementable.** Verified: the feature
files cover `valuation-core` only; the legacy engine is Shell code with no Gherkin surface.
A characterization test is the correct instrument. Do not add a spec row.

### P0-B (Sensei P0-2) — `AbsenceReason::NotReported` fabricates a *cause*
Reusing `NotReported` replaces a fabricated value with a fabricated cause (MSFT's return on
capital is not "not reported"), destroys the changed-contract audit trail, and **voids W5's
own discriminating test** — the bank path and the new path both surface as
`kind()=="evidence"`, `detail()=="not_reported"`.

Required: add `AbsenceReason::EstimatorUnavailable`; add a `reason` column to the converted
outlines; make the financial-issuer regression assert the reason.

**RESOLUTION — verified spec-legal.** There are 7 Examples tables, each with its own header,
and `every_examples_row_is_rectangular` compares `row.len()` against *that table's* header. A
`reason` column on the two converted outlines imposes nothing on the other five. The plan's
objection does not survive.

**RESOLUTION — this supersedes Advisor P1-1.** Advisor independently found the same defect
from the other end (`projection.rs:211` hard-codes `refused(AbsenceReason::NotReported)` in
the `let-else`, discarding the input's reason; the adapter supplies `ProviderUnavailable`)
and offered (a) propagate or (b) record the inaccuracy. Advisor's own second-order note shows
(a) breaks the bank test's only discriminator. Sensei's new variant resolves both: it
discriminates by construction. Adopt the variant; do not adopt (a) or (b).

Note Advisor's verified detail: existing Core tests at `projection.rs:590,653,672` assert
`Some(NotReported)` and are constructed with `NotReported` inputs at `:433`, so they stay
green. The cost is confined to the bank test, which is exactly where the discrimination
should be asserted.

### P0-C (Sensei P0-3) — `known_from = max(sources.filed)` is not point-in-time
No vintages retained, so it answers *"when did we first know what we now believe"*, not
*"what did we believe on date D"*. Required: retain vintages keyed by `(period_end, vintage)`
with a single `as_of(cutoff)`; forbid or mark mixed-vintage compositions; pin `filed < cutoff`
**strictly**; parse dates into newtypes; decide and pin fiscal-year semantics for Jan/Feb-FYE
issuers.

### P0-D (Sensei P0-4) — "common issuer-cutoff set" read as an intersection permits win-by-abstention
Also: no multiplicity rule for serial candidate re-testing; MdAE vs MAE notation conflated.
Surfaces a genuine conflict — anchors in the veto set against "anchors are diagnostics only"
(constraint 9). **RESOLUTION:** constraint 9 wins; anchors leave the veto set and stay
diagnostic. Record the conflict and its resolution in the pre-registration.

### P0-E (Sensei P0-5 + Advisor P2) — trimmed-sample variance is biased downward
Both reviewers reached this independently. Variance computed from the *retained* sample
understates the estimator's uncertainty, so a contaminated channel reports **tighter**
precision and gets weighted **up** under inverse-variance fusion. Backwards.

Required:
- handle degenerate cases explicitly: retained n=1 (infinite weight), n=2, MAD=0;
- rename `variance()` → `variance_of_centre()` (it is a squared standard error, not a variance);
- state the approximation **and its direction** (narrower than truth) in
  `docs/valuation-aggregation-audit.md`;
- record that this changes nothing economically today because the forward channel at
  `valuation_core_adapter.rs:547` is always absent so `fuse` reassigns no weight — and that it
  will matter the day a forward channel exists;
- **`robust_centre(sample, max_absolute_z: f64)` reopens the threshold constraint.** Any call
  site could pass 4.0 without touching `MAX_ABSOLUTE_Z`. Remove the parameter or make it
  crate-private. Constraint 6 is not satisfied by a public knob.

### P0-F (Advisor P0-1) — `AGENTS.md` live-gate waiver
Verified at `AGENTS.md:486`: the mandatory list includes **"model policy version"**, and the
anti-pattern table carries *"Required native/live gates relabeled optional"*. §6.2's
*"no live QA required"* must be deleted.

**RESOLUTION.** The automated gate is unconditional and mandatory: `cargo test --lib dcf_model::`,
`valuation_baseline::`, `quant_lens::`, and `npm run test:e2e:native:cof` (COF is one of the
four affected issuers). Live QA is conditioned by `AGENTS.md` on *"model changes that affect UI
numbers"*; §0 proves by construction that this change cannot affect them. Discharge it with the
invariance proof-test **and record the proof**, per the anti-pattern's own remedy (*"record
actual commands/results before changing status"*). Do not write the word "optional".

### P0-G (Advisor P0-2) — attribution and per-issuer reporting
Issue (a) is correct and independent of the redesign: W1's fail-closed rules change what
`extract_driver_annual` returns from live companyfacts, so W1 — not only W2 — perturbs live
inputs. "Expected delta exactly zero, any non-zero delta is a defect" is fixture evidence
making a live claim.

Required: **schedule W1 and W2 in different rounds**; extend W2's report from anchors to a
named-issuer table for **COF, DAL, CHTR, BKR** plus the full 26-name high-signal cohort;
treat "an operating issuer goes from valued to unavailable" as pause trigger (c).

Note: running `valuation_high_signal` rewrites
`tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json` (constraint 8).
That test is one of the three known-failing ones, so read its output as a **table**, not
pass/fail, and leave the fixture unstaged.

### P0-H (Advisor P0-3) — the `InterestPaidNet` precedent does not cover this case
Verified at `AGENTS.md:565` and `sec-driver-normalization.json:76`: the committed rule is
*"equivalence classes hold one statement's concept only … not a substitute from another
statement"* — a **cross-statement** rule. `InterestPaidNet` is a cash-flow-statement
disclosure spliced into an income-statement accrual series. `InterestIncomeExpenseNet` and
`InterestIncomeExpenseNonoperatingNet` are income-statement concepts. The existing rule, read
literally, does not forbid them.

**RESOLUTION — restate R2 for the sign design.** Advisor wrote R2 as a prohibition ("one
measurement basis only"). Under the sign convention it is an admission rule:

- **R1 (existing):** an equivalence class holds one statement's concept only.
- **R2 (new):** an equivalence class holds one **measurement basis**; a netted concept enters
  the class only through a **declared sign convention** that maps it onto the class's basis.
  Absent a declared convention it reads absent, not equivalent.

State both in `shared/contracts/README.md`, in `AGENTS.md`, and in the extended `rationale`.
Pin the seven-name list against **both rules by name** in the failure message.

Second-order, adopted: once R2 exists it binds every other `select_one_equivalent` list.
`stockholdersEquity` (`:97`) mixes `…IncludingPortionAttributableToNoncontrollingInterest`
with `StockholdersEquity` — including vs excluding NCI is a different basis on the same line.
Name that audit as a follow-up in the economic contract or R2 is decorative.

### P0-I (Advisor P0-4) — Q1 was escalated under pause trigger (a) and the plan decides it
`refine.md:49-50` marks Q1 **OPEN, FOR JUAN**. The plan proceeds on option (i) and defends it
with reversal cost — a delivery-convenience argument against `AGENTS.md:36` and brief §5.

**RESOLUTION — escalate, and the escalation has changed.** The answer is now option **(iii)**,
the sign convention, which was not among the two options put to Juan. Plan.v1 must carry Q1 at
its head, with the four-issuer cost in numbers and the §0 invariance proof, and must state that
**W2 does not start until Juan answers.** Rounds 1, 3 and 4 are unblocked and proceed.

---

## 3. P1 findings — adopted

- **(Sensei P1-2 + Advisor P1-2, converged) W3 trims the mean and feeds the outlier back into
  the fit.** `robust_centre` replaces `mean` at `:280` for `pooled_mean` only; the contaminated
  observation remains in `pairs` at `:282-290`, and `persistence = cross / square` at `:295-297`
  runs over those pairs. Centre from one sample, slope from another — arguably worse than today.
  Decide **in the plan**: either exclude discarded observations from pair construction — stating
  whether removing observation *i* kills one pair or both adjacent pairs — or retain them with a
  written reason. `growth_pooled_discarded` must report the count that actually affects the fit.
  `docs/valuation-aggregation-audit.md` must show old/new `persistence`. T3.5's audit table omits
  `:295-296` entirely; add a Keep/Fix row for the through-origin fit.
- **(Advisor P1-3) W4 consumes W5's output in the same round.** T4.6 writes an `AGENTS.md`
  standing rule describing W5's behaviour and T4.5 links an ADR W5 creates. Brief §2 permits W5
  to be deferred — then `AGENTS.md` ships a rule for behaviour that does not exist and
  `docs/index.md` ships a dead link (`AGENTS.md:173`). **Schedule W4 after W5.**
- **(Sensei P1-5 + Advisor P1-4, converged) W1's blast radius is unproven on live data.**
  `edgar.rs:196` currently admits a fact with no `filed` via `unwrap_or("")`; W1 drops it.
  Add an `#[ignore]` probe on the `valuation_probes.rs` convention counting, over ≥5 real
  issuers (`AGENTS.md:366`), accepted 10-K facts lacking `filed` or carrying an unparseable
  `end`. Change "any non-zero delta is a defect" to "any non-zero delta must be explained by
  that count or is a defect". If the count is non-zero, W1 becomes coverage-reducing on the
  production FCFF path and inherits P0-F and P0-G.
- **(Advisor P1-5) residual income loses its FR anchor.** Verified `manifest.toml:56`:
  `residual-income-on-book` has `frs = ["FR-30","FR-31","FR-32"]` — **no FR-29**. T5.5's claim
  that *"`frs` keeps FR-29"* for both entries is false. Add `FR-29` to that entry and one
  sentence to FR-29's rewritten prose stating the residual-income form. **Do not open FR-31** —
  `prd.md:437` carries an assumption and open question 5 that would pull COF provision
  normalization into scope.
- **(Advisor P1-6) W1/W2 disjointness holds only if `SecFact` is frozen.** `SecFact.value_dollars`
  is `i64` but T1.3 routes `extract_annual_percent_any` (unit `"pure"`) through it; a builder
  who concludes the field is mistyped will edit `sec_normalization.rs`, which W2 owns.
  Add **I6 — Wave 1 does not modify `sec_normalization.rs`**, and pre-decide that non-dollar
  facts store the filed integer with the true `unit` string.
- **(Sensei P1-1) wave independence is file-level, not semantic.** State the semantic
  dependencies explicitly now that rounds are serial.
- **(Sensei P1-3) known failures are protected by count, not identity.** Assert the three
  failing test **names**, not the number 3.
- **(Sensei P1-4) the ±5% anchor gate is undrived.** Derive it or name it as a convention.
- **(Sensei P1-6) converting six assertions does not prove the Core went dark.** Add an
  exhaustive property test that no ROIC-dependent path publishes a number.
- **(Sensei P1-7 / P1-8) add an intra-W4 checkpoint and a no-outcome-observed attestation.**
- **(Sensei P1-2 freeze protocol)** pre-registration freeze protocol must be written.

---

## 4. P2 findings — adopted

- `shared/contracts/README.md` has a `## Files` bullet structure (lines 24-51) that omits
  `sec-driver-normalization.json` and its fixtures. Add both in the existing style; the file is
  also absent from `docs/index.md`.
- `AGENTS.md`'s `## Documentation Map` (585-601) is not updated by T4.6. Add the economic
  contract, both charters, the pre-registration, the aggregation audit, the PIT provenance doc
  and the ADR. **`AGENTS.md:573` requires a row to the anti-pattern table *and a step to the
  manual procedures*** — T4.6 adds three rows and zero procedure steps. Add the
  policy-fingerprint-bump procedure step.
- `valuation_probes.rs:465-466` calls `robust_mean` then `standardize` again purely to recover
  the discarded count — the exact duplication `robust_centre` eliminates — and uses a
  fully-qualified `valuation_core::robust_mean` against constraint 10. Not in W3's file set;
  name it in the audit doc as a follow-up.
- **Line-number drift.** `residual_income.rs`'s `unwrap_or(cost_of_equity)` is at **:111**
  (plan says :108); `compute_dcf` is called at **:1185**, defined at **:681** (plan says :1180);
  `return_on_capital` is at **:557** (refine says :554). **Prefer symbol names over line numbers
  in task text.**
- `schema.rs` defines **seven** `#[test]`s (139,158,171,196,228,244,279), not six — matching the
  "Core = 89 + 7" baseline. The plan repeats the brief's error.
- `AGENTS.md` carries uncommitted working-tree edits. W4 is its sole owner; it must preserve
  them and stage only `AGENTS.md` (constraint 7).
- F7's file enumeration is wrong: 4 files only if `_bmad-output/.../.memlog.md` counts; the
  parenthetical says 3.

---

## 5. Doc gaps that must become deliverables

1. W2's automated-gate + native-COF evidence record, with the §0 invariance proof discharging live QA.
2. The named-issuer before/after table for COF, DAL, CHTR, BKR plus the 26-name cohort.
3. R2 (measurement basis + declared sign convention) as a rule distinct from R1, in three places.
4. `## Files` entries for the contract and its fixtures; `docs/index.md` link.
5. `AGENTS.md` Documentation Map entries + a manual-procedure step for policy-fingerprint bumps.
6. The ADR's statement of the refusal's actual `detail()` string under `EstimatorUnavailable`.
7. The trimmed-mean standard-error approximation and its direction, in the aggregation audit.
8. The through-origin persistence fit's outlier policy, in the aggregation audit.
9. A live-evidence count of facts lacking `filed`, in `docs/sec-point-in-time-provenance.md`.
10. The `dcf_model.rs:907` blanket `.abs()` as a named, tracked latent defect in the economic contract.

---

## 6. What both reviewers said is right — do not churn

- Constraint 6 compliance is exact: `robust_centre` + `robust_mean` delegating to it is literally
  what `AGENTS.md:449` demands. `MAX_ABSOLUTE_Z` pinned at 3.0. No second implementation anywhere.
- D1's rejection of `fy`/`fp` is evidence-backed; the NVDA trap comment at `edgar.rs:197-203` is
  real; `accession` + `form` + `filed` identifies the filing exactly.
- T3.5's Keep verdicts are sound: `:781` least-squares centering (OLS *is* defined by the
  arithmetic mean), `:489` residual scatter on `n-2` df, `:335` `fit_beta_dispersion` (a width
  where erring wide is the safe direction).
- File-ownership analysis largely correct: `SecFact`'s derives make W1/W2 disjoint (under I6);
  `CrossSectionDiagnostics` is adapter-local; `compute_dcf` is private; the generator writes
  exactly two files so W2's 6-file set is complete.
- No ticker literals anywhere in W2, and `select_one_equivalent` declared-order preservation,
  are both handled correctly given `edgar.rs:317-322`'s `or_insert` gap-filling.
- Leaving `operating_valuation.rs:223` alone and recording it in the ADR is correct under
  Decision 2 — subject to P0-A's qualification requirements.

---

## 7. Revised round schedule (binding)

| Round | Waves | Rationale |
|---|---|---|
| R1 | W1 (PIT provenance), W3 (aggregation) | disjoint file sets; W3 must not touch `sec_normalization.rs` or `edgar.rs` |
| R2 | W2 (sign convention) | **blocked on Juan's Q1 answer**; separated from W1 for live-input attribution (P0-G) |
| R3 | W5 (FR-29 removal + `EstimatorUnavailable`) | must precede W4 so standing rules describe real behaviour (P1-3) |
| R4 | W4 (economic contract, charters, pre-registration, `AGENTS.md`, indices) | consumes W5's ADR and W3's persistence numbers |

Max 3 concurrent builders is not binding here — no round exceeds 2.
