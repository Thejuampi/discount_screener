# W2a build report — valuation-pit-contract

Worktree: `G:\dev\repos\discount_screener-wt-w2a` (harness isolation OFF, manually provisioned).
Shared checkout `G:\dev\repos\discount_screener` was read-only for this wave (plan.v6.md and its
`plan-review/` files were read there to reconstruct carry item 15's exact wording and §0's
blast-radius table; nothing there was written).

## 0. Scope covered

T2.1, T2.2, T2.3, T2.4, T2.9, T2.12, and — as a judgment call, see §7 — T2.14.

## 1. What changed, and why it fits the codebase

| File | Change |
|---|---|
| `shared/contracts/sec-driver-normalization.json` | T2.1: fingerprint → `/9`; `interestExpense` driver gained `negatedQnames: [InterestIncomeExpenseNet, InterestIncomeExpenseNonoperatingNet]`, a `signRationale` (LIN's exact-negation evidence), and the `rationale` field now carries both R1 (the pre-existing `InterestPaidNet` removal) and R2 (a pointer to `signRationale`). |
| `shared/contracts/sec-driver-normalization-fixtures.json` | T2.9 site 2: `policyFingerprint` → `/9`. |
| `scripts/generate-sec-driver-normalization-policy.ps1` | T2.2: `QnameSigns($driver)` helper derives a per-qname `+1/-1` array from `negatedQnames`; `RustSlice`/`KotlinList` gained a `-NoQuote` switch (DRY — one array-emission helper for both quoted string arrays and bare numeric arrays) and both `RustOperator`/`KotlinOperator` now emit `qname_signs`/`qnameSigns` positionally alongside `qnames`. Also: `RustSlice`'s single-line-vs-multi-line decision was count-based (`nestedIndent == 0 && count <= 2`), which happened to match rustfmt's own wrapping only because the pre-existing string arrays are always too wide to fit one line anyway. The new numeric `qname_signs` arrays are short enough to fit even at 9 elements, so the generator's raw output was **not** a `cargo fmt` fixed point. Replaced the heuristic with an actual rendered-width check (≤80 cols) so generated output matches what `cargo fmt` would produce, without ever hand-editing the `DO NOT EDIT` file. Verified: neither the qnames arrays' existing multi-line layout nor `DILUTED_AVERAGE_SHARES`'s single-qname line changed. |
| `apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs`, `apps/android/core/src/main/kotlin/.../SecDriverNormalizationPolicyGenerated.kt` | T2.3: regenerated, generator output only. Fingerprint `/9`. `INTEREST_EXPENSE.qname_signs = [1,1,1,1,1,1,-1,-1,1]` (indices 6/7 are the two net concepts). Every other driver's signs are all `1`. `-Check` is green (fixed point). |
| `apps/windows/src-tauri/src/edgar.rs` | T2.4: `concept_observations` gained `sign: i8`, multiplying `filed_value` **before** the `SecFact`/`AnnualObservation` is built (see design rationale in §2). `concept_vintages` gained `signs: &[i8]` parallel to `concepts`, with `assert_eq!(concepts.len(), signs.len(), ...)`. `extract_driver_vintages` passes `driver.qname_signs`. Two new tests: `concept_vintages_applies_each_concept_its_own_sign` (per-concept, not blanket) and `concept_vintages_panics_when_signs_and_concepts_disagree_in_length`. One `cargo fmt` fix to the new `concept_vintages(...)` call (collapsed to one line, matches rustfmt). |
| `apps/windows/src-tauri/src/valuation_probes.rs` | Compile fix only: `qname_coverage`'s single-qname diagnostic `DriverOperator { .. }` literal needed a `qname_signs: &driver.qname_signs[index..=index]` field, sliced in parallel with `qnames` (not a placeholder — the probe stays correct for negated concepts). This is the only other production/test file besides the generated ones and `sec_normalization.rs` that constructs a `DriverOperator` literal (confirmed by grep). |
| `apps/windows/src-tauri/src/sec_normalization.rs` | T2.9 site 5: fingerprint assertion → `/9`. T2.14: `generated_qname_signs_reconstruct_from_contract_negated_qnames` — for every driver in the contract, reconstructs the expected sign array from `negatedQnames` and asserts it equals the generated constant's `qname_signs`. One `cargo fmt` fix (braced the ternary-shaped `if`). |
| `apps/windows/src-tauri/src/cross_platform_parity.rs` | T2.12: `compute_mpwr()` (real MPWR SEC data, see §4) added to the exported checklist-pin rows, plus a direct, asserting hazard-pin test. Not a protected file. |

## 2. Design: where the sign applies (RESUME settle-point 1)

**Implemented:** the sign is injected inside `concept_observations`, multiplying the raw filed
value **before** the `SecFact`/`AnnualObservation` is constructed — never applied to a post-merge
scalar.

```rust
let filed_value = entry["val"].as_i64()?;
let value_dollars = filed_value * i64::from(sign);
AnnualObservation::from_fact(sec_fact_from_entry(entry, concept, unit, value_dollars))
```

Rejected alternative: applying the sign once, after `resolve_one_concept` picks a winning
vintage. That fails for exactly the reason F7/the class-merge logic exists: a gap-filled year can
be filled from a *different* concept in the same equivalence class than the year next to it, and
each concept in `interestExpense.qnames` carries its own, independently-declared sign (only two of
the nine are negated). A single post-merge sign cannot know which concept produced the winning
vintage for a given year. Applying it at the earliest point — inside the per-concept fact
construction — means the sign travels transparently through precedence resolution, restatement
comparison, and vintage retention as a property of the fact itself, which is what the class-merge
design already assumes for every other field.

## 3. Can the sign change a `materially_restated` verdict? (RESUME settle-point 2)

**No, by construction.** `materially_restated` (`edgar.rs:532`):

```rust
fn materially_restated(candidate: i64, winner: i64) -> bool {
    let reference = winner.abs().max(candidate.abs());
    reference > 0 && (candidate - winner).abs() * 10_000 >= reference * MATERIAL_RESTATEMENT_BPS
}
```

For `s ∈ {+1, -1}`: `|s·x| = |x|` for all `x`, so `reference` is unchanged, and
`(s·candidate − s·winner).abs() = |s|·|candidate − winner| = |candidate − winner|` since `|s| = 1`.
Both terms are invariant, so `materially_restated(s·candidate, s·winner) ==
materially_restated(candidate, winner)` for any uniform sign. Crucially, `candidate` and `winner`
are always two vintages of the **same concept** within one `resolve_one_concept` call (restatement
comparison never compares across concepts), so they always share the same sign — the invariance
condition is exactly what the call site guarantees. Confirmed empirically too: all pre-existing
restatement tests (`restatement_keeps_the_latest_filed_value`,
`two_cutoffs_over_one_restated_year_return_two_different_values`, etc.) still pass with signs wired
in for every concept.

## 4. T2.12 — the discriminating fixture, ruled (RESUME settle-point 3)

**The cohort-driven exporter (`export_qa_cohort_parity_snapshot_for_android`) cannot discriminate
the sign convention, and adding MPWR to it would not fix that.** Two independent reasons, both
verified by reading the file, not assumed:

1. **MPWR is not a member of `baseline_cohort_2026-07-30.json`.** Neither that file nor
   `baseline_driver_data_2026-07-30.json` names MPWR, and per the file-ownership matrix
   `baseline_cohort_2026-07-30.json` is not in W2a's editable set (unlike
   `baseline_driver_data_2026-07-30.json`, R-12.2). Adding a driver-data row without a cohort
   membership row would be silently unused.
2. **Even a cohort member would not discriminate, because this exporter never touches
   `edgar.rs`'s sign machinery at all.** `row.interest` is read straight out of the driver-data
   JSON and passed to `.with_operating_drivers(..., Some(row.interest), ...)` with zero sign
   processing in between (`cross_platform_parity.rs`, the loop body of
   `export_qa_cohort_parity_snapshot_for_android`). Whatever convention the JSON author used is
   whatever reaches `compute()`. This path is orthogonal to T2.1/T2.4 by construction.

**What discriminates instead:** the checklist-pin pattern already used for T/AMZN/ACGL, which
builds a `FundamentalSnapshot` + `Vec<FcfPoint>` directly in Rust and is pushed unconditionally
into `rows`, bypassing the cohort file entirely. Added `compute_mpwr()` on that pattern, using
MPWR's real filed SEC XBRL series (CIK 1280452; source: cached `companyconcept` fetches in the
scratchpad, reused per the original dispatch, not re-fetched):

| Year | OCF | CapEx | Revenue | `InterestIncomeExpenseNonoperatingNet` (filed) | Tax expense (filed) |
|---|---|---|---|---|---|
| 2022 | 246,674,000 | 58,843,000 | 1,794,148,000 | **+14,369,000** | 87,265,000 |
| 2023 | 638,213,000 | 57,578,000 | 1,821,072,000 | **+23,363,000** | 78,467,000 |
| 2024 | 788,410,000 | 146,118,000 | 2,207,100,000 | **+27,093,000** | −1,213,788,000 / −1,019,146,000 (see below) |
| 2025 | 838,202,000 | 172,013,000 | 2,790,459,000 | **+29,151,000** | 144,733,000 |

MPWR files this concept **positive every year** and files no `TOTAL_DEBT`/`CURRENT_DEBT`/
`NON_CURRENT_DEBT` concept at all — a real, well-known net-cash, debt-free company, so the line is
net interest **income**, not expense. Under `negatedQnames`, the `interestExpense` driver correctly
resolves it to a **negative** dollar amount, which is what `compute_mpwr()` passes into
`with_operating_drivers`.

**FY2024 tax expense is a genuine, disclosed anomaly, not fabricated.** `IncomeTaxExpenseBenefit`
FY2024 is filed as −1,213,788,000 in the 10-K filed 2025-03-03 and restated to −1,019,146,000 in the
10-K filed 2026-02-27 — both a tax **benefit** larger than FY2024 pretax income (572,912,000),
unlike every other year (all positive, all a fraction of pretax income). Neither filing in the
cached data explains the swing. Rather than compute an effective-tax-bps figure from an
unreconciled anomaly, `compute_mpwr()` passes `Some(-27_093_000.0)` for FY2024's interest (still
exercising the sign hazard that year) but `None` for its `tax_rate_bps` — refuse, don't invent
(Quality Bar: "Honest absence / refuse paths — never invent numbers... from missing evidence").

**Empirically observed, and the honest limit of what this row proves.** Running
`export_qa_cohort_parity_snapshot_for_android` and inspecting
`.agents/workspace/tmp/parity-windows-qa.json`, the MPWR row is:

```json
"case": "checklist_mpwr_negated_interest_sign",
"ok": false,
"error": "fcff unavailable: provider inconsistency, positive interest with zero debt",
```

This is `driver_resolution.rs:75-88`'s existing fail-closed guard firing — not a fabricated wrong
number, a refusal. **This is where W2a's inertness makes itself visible, and it is symmetric**:
`with_operating_drivers` un-negates via `.map(f64::abs)` at `dcf_model.rs:907` before this guard
ever runs, so `compute_mpwr()` produces the *exact same* refusal whether `-29_151_000.0` or
`+29_151_000.0` is passed for FY2025's interest — `f64::abs` erases the difference by definition.
**So `compute_mpwr()`'s `compute()`-level outcome does not, and today cannot, discriminate the two
sign conventions; it discriminates nothing about `interestExpense.negatedQnames` that a pre-W2a
tree would not also show for this same issuer.** What does discriminate the convention is upstream,
at the extraction boundary T2.4 owns — `concept_vintages_applies_each_concept_its_own_sign`
(`edgar.rs`) proves the sign is computed correctly using LIN's exact-negation pair. To close the
gap between "the sign is computed correctly" and "here is a real issuer where that fact is
currently invisible downstream," I added a second, direct assertion pin in
`cross_platform_parity.rs` that needs no `compute()` boundary at all:

```rust
#[test]
fn mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers() {
    let point = FcfPoint::new(2025, 666_189_000.0).with_operating_drivers(
        838_202_000.0, -172_013_000.0, 2_790_459_000.0,
        Some(-29_151_000.0), Some(1_889),
    );
    assert_eq!(point.interest_expense_dollars, Some(29_151_000.0), /* ... */);
}
```

This is the fixture that actually discriminates: it takes the value T2.4's corrected extraction
would produce for MPWR (`-29_151_000.0`) and pins, with real filed numbers, that
`with_operating_drivers` still flips it positive today. It will start failing the moment a later
wave removes `dcf_model.rs:907`'s `.abs()` — which is exactly the point of a hazard pin.

**Ruling, explicitly, per the RESUME instruction not to leave this as a note:** the cohort exporter
cannot discriminate the convention (evidenced above) and was not extended with MPWR; the
checklist-pin `compute_mpwr()` row is planted and real but is *not itself* the discriminating
artifact today, because `compute()`'s outcome is sign-symmetric while `.abs()` stands; the actual
discriminating pin is `mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers`,
which does not depend on `compute()` at all and fails only once a later wave removes the `.abs()`
sites.

## 5. J6 — the canonical check, and an honest discrepancy

Canonical check, run on the current tree, from `apps/windows/src-tauri/src`:

```
grep -rniE "interest.*(abs\(\)|f64::abs)" .
./dcf_model.rs:551:        let interest = interest.abs();
./dcf_model.rs:907:        self.interest_expense_dollars = interest_expense_dollars.map(f64::abs);
./dcf_model.rs:1590:                let interest = interest.abs();
./driver_resolution.rs:82:                    .is_some_and(|interest| interest.abs() > f64::EPSILON)
```

**Four rows, not the literally-specified three, both before and after my edits.** The fourth
(`driver_resolution.rs:82`) is `resolve_rate_inputs`'s zero-debt/positive-interest consistency
guard — already sign-agnostic by construction (§0 ruled it so), untouched by me
(`git status --porcelain` on the file is empty), and predates my wave (`git log -1` on the file
shows its last change at `0507dfe`, before Wave 3's merge into this tree). J6's underlying safety
property — the three canonical un-negation sites (`:551`, `:907` via `.map(f64::abs)`, `:1590`)
still standing, W2a deliberately inert — holds exactly as required. The check's literal "exactly 3"
wording is stale relative to the merged tree it now runs against; this is a plan-wording drift, not
a wave defect, and I am reporting it rather than silently reconciling it.

## 6. The 15-row read-side sweep (plan carry item 15 — pasted and ruled, all 15)

Located in the shared checkout (read-only): `plan.v3.md:1353` first states "the exhaustive
read-side sweep for `interest_expense_dollars` returns 15 sites across 6 files," carried unchanged
through v4/v5/v6, and v6's carry item 15 (line 3591) promotes it to a hard W2a pre-condition because
§0's blast-radius table only rules 10 sites across 5 files. **No revision ever pasted the actual
15-row listing** — it is asserted by count, not shown. I ran it myself, on my own tree
(`rg -n "interest_expense_dollars" apps/windows/src-tauri/src`), and it returns **exactly 15 sites
across exactly 6 files**, confirming the count. Full listing, each ruled:

| # | Site | What it is | Ruling |
|---|---|---|---|
| 1 | `driver_resolution.rs:81` | field read feeding `interest.abs() > f64::EPSILON` (§0's own site, unchanged) | Already sign-agnostic by construction. Unaffected by W2a. |
| 2 | `driver_resolution.rs:117` | `let interest = point.interest_expense_dollars?;` — feeds §0's `:118`/`:124` (the `interest < 0.0` guard and the `debt>0.0 && interest>0.0` branch) | These two branches stay in the never-negative region because `dcf_model.rs:907`'s abs still runs first; empirically confirmed live *for the zero-debt case* by `compute_mpwr()`'s observed refusal (§4). Inert for W2a's purposes; correctly ruled dead-on-values by §0. |
| 3 | `dcf_model.rs:545` | field read feeding §0's `:551` abs (`FCFF driver audit`) | Unchanged; still abs'd; inert. |
| 4 | `dcf_model.rs:795` | audit table print (§0's own site) | Cosmetic; prints the post-abs (always non-negative) value. Unchanged. |
| 5 | `dcf_model.rs:850` | `pub interest_expense_dollars: Option<f64>` field declaration | Structural; not itself a consumer; no sign implication. Not previously named by §0 because it isn't a "consumer" row, correctly so. |
| 6 | `dcf_model.rs:884` | `interest_expense_dollars: None` in `FcfPoint::new`'s initializer | Structural; always starts absent. No sign implication. |
| 7 | `dcf_model.rs:901` | parameter name in `with_operating_drivers`'s signature | Plumbing; the actual sign erasure is 6 lines later at `:907`, the named canonical site. |
| 8 | `dcf_model.rs:907` | `self.interest_expense_dollars = interest_expense_dollars.map(f64::abs);` | **The** canonical un-negation site (one of three). Confirmed the sole writer of this field — `grep -rn "interest_expense_dollars = " src/` still returns only this line, re-verifying §0's own "write path" claim on the current tree. Standing, untouched, as required. |
| 9 | `dcf_model.rs:1586` | field read feeding §0's `:1590` abs (aligned-driver bridge / published FCFF path) | Unchanged; still abs'd; inert. |
| 10 | `edgar.rs:1433` | `let interest_expense_dollars = by_year(&interest, v.year);` | **Not named by §0 at all — this is "the one unruled file."** This is the exact point where T2.4's corrected sign becomes a concrete, correctly-signed `f64` in the extraction pipeline. Correct as of this wave; carries the sign faithfully. |
| 11 | `edgar.rs:1456` | passes `interest_expense_dollars` into `with_operating_drivers` | **Not named by §0.** The direct production link between T2.4's corrected sign and `dcf_model.rs:907`'s still-standing abs, 23 lines below `:1433` in the same expression chain — confirmed by `compute_mpwr()` and the direct hazard-pin test in §4. |
| 12 | `valuation_baseline.rs:900` | reporting surface (§0's own site) | Unchanged; prints the post-abs value. |
| 13 | `valuation_fixture_capture.rs:131` | `.unwrap_or(0.0)` (§0's own site) | Unchanged; pre-existing absence-fabrication defect, explicitly out of W2a's scope (§0 names it as T2.8's job). |
| 14 | `valuation_probes.rs:476` | `count(\|point\| point.interest_expense_dollars.is_some())` | **Not named by §0** — see below, its `:344`/`:354` citations are stale. Presence-only (`.is_some()`), sign-agnostic. Unaffected either way. |
| 15 | `valuation_probes.rs:486` | `let interest = point.interest_expense_dollars?;`, feeding a NOPAT calc (`nopat: (pretax + interest) * (1.0 - marginal_tax)`, line 493) with no `.abs()` of its own | **Not named by §0, and this is a genuinely new finding.** This ROIC/NOPAT diagnostic adds back whatever is in the field with no abs of its own. Today it is inert only because `dcf_model.rs:907` already guarantees a non-negative value upstream; it becomes a **fourth**, previously-unnamed live consumer the moment a later wave removes the three canonical abs sites, and should be added to that wave's own blast-radius table. |

**Reconciling with §0's 10-across-5, precisely (not by simple arithmetic):** §0's `:551`, `:795`,
`:1590` (dcf_model.rs), `:81` (driver_resolution.rs) and `valuation_baseline.rs:900`,
`valuation_fixture_capture.rs:131` all map cleanly onto sweep rows above, either as the exact same
line or as the field-read immediately upstream of the same logical consumer (rows 1–4, 9, 12, 13);
`driver_resolution.rs:118`/`:124` share sweep row 2's field read. **§0's citations of
`valuation_probes.rs:344` and `:354` are stale — that file has grown by roughly 130 lines since v6
was written, and those line numbers now sit inside an unrelated analyst-consensus probe** (verified
by reading them directly: `triple.2`, `PROBE_COHORT.len()`, nothing to do with interest). The
file's real `interest_expense_dollars` sites today are rows 14–15. So the true reconciliation is:
**8 of §0's 10 sites still map correctly; 2 (`valuation_probes.rs:344`/`:354`) are stale and
superseded by rows 14–15; and 7 sweep sites were never named by §0 at all** (rows 5–8 structural,
10–11 in edgar.rs, 14–15 in valuation_probes.rs) — not the "five sites and one file" the carry item
guessed by subtracting 15−10. `edgar.rs` being wholly absent from §0's table, as the carry item
said, is confirmed exactly. Every one of the 15 is ruled inert for W2a specifically because J6 holds
(§5); row 15 (`valuation_probes.rs:486`) is the one genuinely new item that a later, `.abs()`-removing
wave must add to its own blast radius.

## 7. T2.14 — scope discrepancy, disclosed

T2.14 (the contract-negatedQnames ↔ generated-`qname_signs` reconstruction test) is not on my
dispatched "Scope:" line (T2.1/T2.2/T2.3/T2.4/T2.9/T2.12), but plan.v6.md's body text explicitly
assigns it to W2a. I implemented it (`sec_normalization::generated_qname_signs_reconstruct_from_contract_negated_qnames`)
because: (a) it is a natural, low-risk companion to T2.9's fingerprint bump — it directly guards
against the contract and the generated constants silently drifting apart, which is exactly the kind
of defect the fingerprint exists to catch; (b) it is the test that made the earlier `RustSlice`
fmt-fixed-point defect (§1) visible as a real regenerate-and-verify cycle, not a hypothetical one;
(c) omitting it purely because it was missing from one line of the dispatch, while the plan body
names it for this wave, seemed like the wrong kind of literalism. Flagging this explicitly as a
judgment call for the orchestrator to rule on, not asserting it as obviously correct.

**Isolated mutation verification (R-8.4).** Performed twice against the generated file, each
restored and re-confirmed green before moving on:
1. Flipped one sign in `INTEREST_EXPENSE.qname_signs` (index 6, −1 → 1). Test failed:
   `left: [1,1,1,1,1,1,1,-1,1] right: [1,1,1,1,1,1,-1,-1,1]`. Restored; passed.
2. Transposed the two negated indices (moved −1 from index 7 to index 8). Test failed:
   `left: [1,1,1,1,1,1,-1,1,-1] right: [1,1,1,1,1,1,-1,-1,1]`. Restored via the scratch backup;
   `-Check` and the test both green afterward.

## 8. Checks run

**Fast, scoped, all green (each named, mapped to what it protects):**
- `cargo fmt --check` (`apps/windows/src-tauri`) — clean for every file I touched
  (`edgar.rs`, `sec_normalization.rs`, `cross_platform_parity.rs`,
  `sec_driver_normalization_policy_generated.rs`). Remaining diffs (`fetcher.rs`, `lib.rs`,
  `valuation_gap_attribution.rs`) are pre-existing, confirmed via `git status --porcelain` on each
  (empty) — not mine, not touched.
- `.\scripts\generate-sec-driver-normalization-policy.ps1 -Check` — green (fixed point;
  the width-heuristic fix in §1 was required to reach this).
- `cargo test --lib edgar::` — 35 passed, 0 failed, 3 ignored (network probes, pre-existing
  `#[ignore]`). Covers T2.1/T2.4 (`concept_vintages_applies_each_concept_its_own_sign`,
  `concept_vintages_panics_when_signs_and_concepts_disagree_in_length`, plus every pre-existing
  restatement/precedence test — none regressed).
- `cargo test --lib sec_normalization::` — 11 passed, 0 failed. Covers T2.9 (`/9` assertion) and
  T2.14 (`generated_qname_signs_reconstruct_from_contract_negated_qnames`).
- `cargo test --lib driver_resolution::` — 10 passed, 0 failed, including the pre-existing
  `positive_interest_with_zero_debt_is_inconsistent`, whose production behavior `compute_mpwr()`
  independently exercises in §4.
- `cargo test --lib cross_platform_parity::` — 2 passed
  (`export_qa_cohort_parity_snapshot_for_android`, `mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers`),
  1 pre-existing failure (`export_random20_sp500_parity_snapshot`, missing untracked
  `.agents/workspace/tmp/random20-inputs.json` — this fixture and its exporter are explicitly
  out of scope and were never created, fixtured, or relaxed).

**Not run, correctly deferred (integration / >10s / full-suite):**
- The orchestrator's own paired baseline (`baseline-shell.txt`: `518 passed; 3 failed; 22 ignored;
  ... finished in 19.79s`) is the measurement of record for the full-suite delta — R-4 assigns this
  to the orchestrator's own paired run, not to a builder bound by the ~10s fast-check limit. I did
  not re-run it.
- The three protected tests (`operating_valuation::...durable_reported_and_holdout_cohorts_recompute_in_normal_gate`,
  `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`,
  `valuation_high_signal::high_signal_screener_cohort_all_members_pass`) live in files I never
  touched (`git status --porcelain` confirms `operating_valuation.rs`, `valuation_baseline.rs`,
  `valuation_high_signal.rs` are all clean) — I did not run them, and per §4's rule 3 (v6) a rising
  count in a file I did not write is Juan working, reported without investigation, not mine to
  chase down.
- `cargo clippy` — not run; the fast checks above already cover every touched module and I judged
  a full clippy pass (uncertain runtime, workspace-wide lint graph) a risk of exceeding the ~10s
  budget. Flagging as a deferred check for the orchestrator/QA stage, not silently skipped.
- `high_signal_screener_observation_2026-08-02.json` (protected fixture) — confirmed untouched
  (`git status --porcelain` on the file is empty); no restore was needed because no broad test run
  ever wrote to it.

## 9. Deviations from the plan

- T2.14 implemented despite being absent from the dispatched Scope line (§7) — orchestrator to
  rule.
- The `RustSlice` width-heuristic fix (§1) was not itself a dispatched task; it was required to
  make T2.3's "cargo fmt --check clean" exit criterion true once `qname_signs` existed. Scoped
  strictly to the generator and the one `edgar.rs` call-site formatting fix it also exposed.
- No ticket item asked me to run `compute_mpwr()` and read its actual JSON output, but doing so is
  what turned "plant a fixture" into "plant a fixture and honestly report what it does and does not
  prove" (§4) — judged in scope for T2.12 as dispatched.

## 10. Remaining risks / follow-up work (none deferred silently)

- `valuation_probes.rs:486`/`:493`'s NOPAT calculation (sweep row 15) is a previously-unnamed,
  genuinely live consumer of the interest sign once a later wave removes `dcf_model.rs`'s three
  `.abs()` sites. **Blocking for that later wave's own blast-radius table, not for W2a** — flagged
  here, not silently absorbed.
- MPWR's FY2024 tax-expense anomaly (§4) is disclosed, not resolved; `compute_mpwr()` deliberately
  omits that year's `tax_rate_bps` rather than guess. If a future wave wants FY2024 tax data for
  MPWR, the anomaly needs independent research (e.g. a 10-K footnote read), not another SEC XBRL
  pull.
- §5's stale "exactly 3" wording in the canonical J6 check should be corrected in the plan (or the
  check itself re-scoped to exclude `driver_resolution.rs`) so a future wave does not mistake the
  pre-existing fourth match for a regression.
- W2a is **not independently landable**. Per R-10.3, W2a and W2b are two review units but one merge
  unit; this report covers W2a only.

No known quality smell was left "for later" without being listed above as blocking or deferred with
a stated reason.

## 11. Files changed (for explicit `git add`, never `-A`)

```
apps/android/core/src/main/kotlin/com/discountscreener/core/engine/SecDriverNormalizationPolicyGenerated.kt
apps/windows/src-tauri/src/cross_platform_parity.rs
apps/windows/src-tauri/src/edgar.rs
apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs
apps/windows/src-tauri/src/sec_normalization.rs
apps/windows/src-tauri/src/valuation_probes.rs
scripts/generate-sec-driver-normalization-policy.ps1
shared/contracts/sec-driver-normalization-fixtures.json
shared/contracts/sec-driver-normalization.json
```
