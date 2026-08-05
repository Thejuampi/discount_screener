# Wave 1 — Point-in-time evidence with vintages retained — build report

Branch `valuation/wave1-integration`, worktree
`G:\dev\repos\discount_screener\.claude\worktrees\agent-a7ce432bfde5d3294`.
Plan: `plan.v5.md` §"Wave 1", with `plan-review/ORCHESTRATOR-RULINGS.md` R-4.

> Written to the worktree copy of the path (the agent is worktree-isolated and
> cannot write into the shared checkout). Intended destination:
> `.agents/workspace/tmp/e2e/valuation-pit-contract/build/wave-1-report.md`.

## 0. Worktree correction (finding, reported before anything else)

The worktree was created at commit `32b5c96`. The target branch
`valuation/wave1-integration` is at `4d1e916`, and the plan's line references
match `4d1e916`, not `32b5c96`. The worktree was clean, so it was moved with
`git reset --hard 4d1e916` before any work started. **All measurements below are
against `4d1e916`.**

## 1. Baselines (measured, not taken from the plan)

Command, from `apps/windows/src-tauri`: `cargo test --lib`.

| | start (`4d1e916`, before any edit) | exit (final state) |
| --- | --- | --- |
| passed | 517 | 534 |
| failed | 4 | 4 |
| ignored | 22 | 23 |
| wall clock | 18.82s | 16.57s |

The plan recorded 520/22/3. My own start measurement is 517/22/**4**; R-4 says to
trust my own measurement, so 517/22/4 is the baseline this wave is judged against.

**Failing set at start, by name:**

- `cross_platform_parity::export_random20_sp500_parity_snapshot`
- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

**Failing set at exit, by name: identical — the same four.** None repaired by
accident, none added, none removed from the run.

The +17 passing and +1 ignored are exactly this wave's additions: 17 new unit
tests in `edgar::tests` and one new `#[ignore]` network probe.

### The fourth name

`cross_platform_parity::export_random20_sp500_parity_snapshot` panics at
`src/cross_platform_parity.rs:506`:
`missing random20 inputs at ...\.agents/workspace/tmp/random20-inputs.json`.
That file is untracked and exists only in the main repo, so it is absent from
every worktree by construction. This matches the orchestrator's note. It was not
created, not fixtured around, and not relaxed.

### `high_signal_screener_cohort_all_members_pass` — note on the environment

Network **is** available in this worktree, so this test did not fail offline as
anticipated; it ran the live cohort and failed the gate at `pass=9/26` at start
and `pass=9/26` at exit, with the same member list. Its fixture
`tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json` was
rewritten by each full-suite run and restored with `git checkout --` each time.
It is **not** staged and the worktree is clean of it.

Anchor spot-check across two runs of the cohort test:
GOOGL 35679 → 35679, GOOG 35665 → 35665, CRM 25645 → 25645, SLB 7581 → 7581,
PTC 16415 → 16480 (+0.40%). OMC's market price also moved between the two runs
(8148 → 8189), so this test is not deterministic across wall-clock time. No
anchor moved more than ±5% and no name changed side of a gate, so neither pause
trigger (b) nor (c) fired. The authoritative before/after per-issuer comparison
is the orchestrator's deferred check.

## 2. Files changed

| path | what |
| --- | --- |
| `apps/windows/src-tauri/src/edgar.rs` | the whole wave's implementation and tests |
| `apps/windows/src-tauri/src/valuation_probes.rs` | T1.7 probe + its sample, driver list and measurement helper |
| `docs/sec-point-in-time-provenance.md` | T1.8, new file |

`git status --porcelain` at exit shows exactly those three and nothing else.
Files are staged explicitly; `git add -A` was never used. Nothing was committed
(the plan does not ask for a commit).

## 3. Tasks

| task | state | note |
| --- | --- | --- |
| T1.1 `IsoDate`, no string date comparisons | done | strict parse; `Ord` derived; precedence and fiscal-year rules compare `IsoDate` |
| T1.2 one fiscal-year derivation, four slicing sites removed | done | `grep -c "get(..4)"` = **0**, `grep -c 'entry["fy"]'` = **0** |
| T1.3 `AnnualObservation` / `AnnualSeries` / `extract_driver_vintages` | done | `latest()` is `merge(None)`, `as_of(D)` is `merge(Some(D))` — one resolution implementation |
| T1.4 production sites carry real provenance | done, with one deviation | see §6(b) |
| T1.5 one test helper | done | `mod tests` constructs `AnnualProvenance` in exactly one place (`annual`) |
| T1.6 fail-closed, `unwrap_or("")` removed | done | three refused fields: `end`, `filed`, `accn` — see §6(a) |
| T1.7 probe | written; **not run** (orchestrator's) | `probe_facts_without_a_filing_date`, `#[ignore]`, asserts nothing |
| T1.8 documentation | done | `docs/sec-point-in-time-provenance.md`, with the T1.7 slot clearly marked unfilled |

T1.7's sample includes **MPWR**, per the orchestrator's mid-build amendment: the
four anchors (PG, GOOGL, AMZN, MSFT), the interest-sign wave's issuers (COF, DAL,
CHTR, BKR) **plus MPWR**, and the oldest-filing-history slice of the cohort
(T, SLB, OMC, AVY, DVN, TER, EME, APH). The reason MPWR is in the list is
written into the constant's doc comment, not just into this report.

### Invariants

- **I1** `known_from == max(sources.filed)`, a parsed `IsoDate` — the only
  constructors are `from_observations` and `composed`, both of which return
  `None` rather than invent a date when there is no source. Red evidence: M17.
- **I2** `AnnualValue::year == provenance.end.year()` at every construction site.
  Red evidence: M11, M18.
- **I3** No year is derived from anything but a parsed period `end`. Enforced by
  the T1.2 greps and by `IsoDate::year()` being the only derivation.
- **I4** Unparseable `filed` or `end` produces no `AnnualValue`. Red: M2, M3.
- **I5** `as_of` is strictly before. Red: M5, M6.
- **I6** `sec_normalization.rs` is not modified — it is not in `git status`.
- **I7** Every pre-existing `edgar::` test passes unchanged and no numeric
  assertion in any of them was altered. See §6(a) for the one fixture change (an
  added `accn` field; no numbers touched).

## 4. Tests, and red-then-green evidence

Every new test was run against a deliberately broken implementation and
**observed to fail** before being relied on. The harness applies one mutation at
a time to `edgar.rs`, runs `cargo test --lib edgar::`, records the red set and
restores the file. Full transcript:
`<scratchpad>/final-mutation-evidence.txt` (18 mutations, run against the final
code). The green run is
`test result: ok. 33 passed; 0 failed; 3 ignored; 0 measured; 525 filtered out`.

| plan id | test function | seen red under | red result line |
| --- | --- | --- | --- |
| T1.1 | `iso_date_refuses_anything_that_is_not_a_calendar_day` | M1 loose parse | `FAILED. 32 passed; 1 failed` |
| W1-P01 | `leaf_observation_is_knowable_from_its_filing_date_and_names_its_accession` | M17 `known_from := end` | `FAILED. 30 passed; 3 failed` |
| W1-P02 | `free_cash_flow_is_knowable_from_the_later_of_its_two_inputs` | M7 earliest input; M8 `mixed_vintage` never set; M17 | `FAILED. 31 passed; 2 failed` (M7) |
| W1-P03 | `total_debt_names_both_of_the_components_it_summed` | M9 first source only; M14 | `FAILED. 32 passed; 1 failed` |
| W1-P04 | `development_total_takes_its_fiscal_year_from_the_period_end` | M12 fabricated December close | `FAILED. 32 passed; 1 failed` |
| W1-P05 | `rejected_acquisition_observation_carries_the_ledger_facts_identity` | M18 year from filing date | `FAILED. 31 passed; 2 failed` |
| W1-P06 | `percent_fact_records_its_fiscal_year_and_the_filed_unit` | M13 everything recorded as USD | `FAILED. 32 passed; 1 failed` |
| W1-N01 | `a_fact_without_a_filing_date_produces_no_annual_value` | M2 defaulted `filed`; M14 | `FAILED. 32 passed; 1 failed` |
| W1-N02 | `a_fact_whose_end_will_not_parse_produces_no_annual_value_despite_fy` | M3 `fy`-style fallback; M14 | `FAILED. 32 passed; 1 failed` |
| (accn) | `a_fact_without_an_accession_produces_no_annual_value` | M4 check removed; M10 defaulted accn; M14 | `FAILED. 32 passed; 1 failed` |
| W1-N03 | `as_of_excludes_an_observation_filed_exactly_on_the_cutoff` | M5 inclusive cutoff; M6 | `FAILED. 31 passed; 2 failed` |
| W1-E01 | `two_cutoffs_over_one_restated_year_return_two_different_values` | M6 no cutoff filter; M16 | `FAILED. 30 passed; 3 failed` |
| W1-E02 | `an_interpolated_capex_year_is_knowable_only_from_its_later_neighbour` | M7; M17 | `FAILED. 31 passed; 2 failed` |
| W1-E03 | `an_issuer_with_no_facts_for_a_driver_yields_an_empty_series` | M14 absent driver filled with a zero | `FAILED. 28 passed; 5 failed` |
| W1-E04 | `a_february_fiscal_close_belongs_to_the_calendar_year_of_its_end` | M11 year from period start | `FAILED. 30 passed; 3 failed` |
| W1-E05 | `a_fiscal_year_end_change_keeps_the_later_period_end` | M15 earlier end wins | `FAILED. 32 passed; 1 failed` |
| W1-B01 | `as_of_admits_only_the_filing_strictly_before_the_cutoff` | M5; M6 | `FAILED. 31 passed; 2 failed` |
| W1-R01 | `restatement_keeps_the_latest_filed_value` + `discontinued_operation_marks_revenue_restated_but_not_cash_flow` (pre-existing, on `separation_facts`) | M11; M16 | `FAILED. 29 passed; 4 failed` (M16) |
| W1-R02 | `annual_extraction_prefers_consolidated_annual_over_segment_and_quarter` + `annual_extraction_accepts_later_ten_k_amendment` (pre-existing) | M16 precedence inverted | `FAILED. 29 passed; 4 failed` |

W1-R01 and W1-R02 are mapped to the pre-existing tests that already assert
exactly those scenarios rather than duplicated; both were put under mutation, so
they are relied on only after being seen to fail.

The 18 mutations, in full: M1 loose `IsoDate::parse` · M2 defaulted `filed` ·
M3 `fy`-style end fallback · M4 accession check removed · M5 inclusive cutoff ·
M6 cutoff filter dropped · M7 composition knowable from its earliest input ·
M8 `mixed_vintage` never set · M9 total debt keeps one source · M10 accession
defaulted to a literal · M11 fiscal year from the period start · M12 fabricated
December period end · M13 every unit recorded as USD · M14 absent driver filled
with a zero · M15 earlier period end wins the year · M16 precedence inverted ·
M17 period end mistaken for the filing date · M18 acquisition year from the
filing date. **Every one produced at least one red test.** No mutation survived
in the final run.

### One test was found not to test what it claimed — and was fixed

The first version of W1-N02 used a **duration** driver. Mutation M3
(reintroducing an `fy`-style fallback for an unparseable `end`) **survived**: the
duration period-shape check parses `end` itself and had already rejected the
entry, so the test would have passed no matter what the fail-close did. It was
rewritten against an **instant** driver, where the fail-close is the only gate;
M3 then turns it red, and the reason is written into the test's doc comment.
This is exactly why a check must be seen failing, and it is recorded here rather
than quietly fixed.

## 5. Fast checks run (builder)

| check | command (from `apps/windows/src-tauri`) | result |
| --- | --- | --- |
| unit | `cargo test --lib edgar::` | `ok. 33 passed; 0 failed; 3 ignored; 525 filtered out` |
| unit | `cargo test --lib valuation_probes::` | `ok. 3 passed; 0 failed; 4 ignored` |
| lint | `cargo fmt -- --check` | clean for both owned files; see below |
| grep | `grep -c "get(..4)" src/edgar.rs` | `0` |
| grep | `grep -c 'entry["fy"]' src/edgar.rs` | `0` |
| scope | `git diff --name-only` | exactly `src/edgar.rs`, `src/valuation_probes.rs` (+ the untracked new doc) |

`cargo fmt -- --check` is **not** clean crate-wide, but every remaining diff is
in a file this wave does not own and did not touch: `src/fetcher.rs:998`,
`src/fetcher.rs:1008`, `src/lib.rs:54`, `src/lib.rs:62`,
`src/valuation_gap_attribution.rs:1589`. They are unformatted at `4d1e916` as
well (the previous commit formatted only the modules that effort authored). The
two owned files were formatted with `rustfmt --edition 2021` rather than
`cargo fmt`, precisely so no unowned file was rewritten.

### Compiler warnings: net zero

`cargo build --lib` emits **41 warnings before and 41 after**. Composition
changed:

- **Removed (already dead at `4d1e916`):** `extract_annual`,
  `extract_annual_any`. Two more (`extract_annual_with_shape`,
  `extract_annual_any_with_shape`) became dead when resolution moved into
  `AnnualSeries`; all four were deleted and the four tests that used them now
  call one test helper, `resolved_annual`.
- **Added, deliberately:** `method as_of is never used` and `field provenance is
  never read` (on `FcfAnnual`). Both are true statements about the boundary the
  plan created: nothing in production consumes the point-in-time API yet, and
  `fetch_fcf_history` drops provenance at the `FcfPoint` boundary by design.
  They were **not** suppressed with `#[allow(dead_code)]`, because suppressing
  them would hide the fact that item 6 has not landed. They clear when a
  production consumer of `as_of` exists.

## 6. Deviations from the plan, and why

**(a) D1's accession fail-close versus I7 — the one real tension.**
D1 binds: "a fact without a parseable `accn` produces no observation". But no
inline fixture in `edgar.rs` carried an `accn` at `4d1e916`, so implementing D1
literally emptied every series and violated I7 ("numeric series unchanged").

Resolution taken, and it is a judgement the reviewer should check: the binding
D1 fail-close was implemented, and `"accn"` was **added** to the six inline JSON
fixtures. **No numeric assertion, threshold or expected value in any of those
tests was changed** — the additions make the fixtures more like real
companyfacts entries; they do not relax anything. A dedicated negative test
(`a_fact_without_an_accession_produces_no_annual_value`) pins the refusal, and
T1.7 gained a fourth column so the live cost of the accession rule is measured
rather than assumed. If the orchestrator judges the accession fail-close too
strict, the place to change it is `AnnualObservation::from_fact` and that one
test — not the fixtures.

**(b) `extract_annual*` wrappers deleted rather than re-provenanced.**
T1.4 names `extract_annual_any_with_shape` as one of the nine production
construction sites. Once T1.3 moved resolution into `AnnualSeries` — which T1.3
requires ("exactly one place decides which candidate wins an `end`") — the four
`extract_annual*` wrappers became one-line aliases reachable only from tests.
They were deleted. The plan's own "Done when" sanctions this: "all 31
construction sites carry real provenance **or no longer exist**".

**(c) `extract_recurring_development` mechanism choice** (the one T1.4 left to
the builder): sources come from `evidence.recurring_development_by_end` and
`evidence.software_development_by_end` — the *reachable* mechanism, not the
ledger scan — with `DEVELOPMENT_AGGREGATE` consulted so a software fact the
normalizer did not add is not listed as a source of a magnitude it did not
contribute to. The resulting coupling (two files deciding contribution from one
generated constant) is documented in the code and in the doc; removing it means
changing `sec_normalization.rs`, which I6 forbids this wave.

**(d) `accepted_annual_entries` is a new `#[cfg(test)] pub(crate)` seam in
`edgar.rs`.** T1.7 needs "facts the driver accepted on form/shape/frame, before
the fail-close", and that predicate is private. Rather than duplicate the
admission rules inside the probe — where they would drift — one crate-private,
test-only accessor was added. It cannot widen the public API and does not exist
in a release build.

**(e) FQNs replaced by imports.** `edgar.rs` gained `use std::fmt;` and
`use std::iter::once;`; `valuation_probes.rs` now imports the `edgar` and
policy items it uses instead of naming them fully at each call site, including
the pre-existing probes in that file (a mechanical, compiler-verified rename, no
behaviour change). This follows the repository-wide rule against fully-qualified
names.

**(f) `IsoDate::iso()` became `impl Display for IsoDate`.** T1.1 does not name a
printing method; `Display` is the idiomatic form, is used by the tests, and does
not leave a production-dead method behind.

## 7. Not completed, and why

1. **T1.7's probe has not been run.** It is a network test and the plan assigns
   the run to the orchestrator. `docs/sec-point-in-time-provenance.md` therefore
   carries an explicitly marked empty section, "Measured cost (T1.7)", with the
   exact command. **Wave 1 is not done until that section carries a
   measurement** — in particular column 3, which is the only live evidence that
   `as_of` and `latest` can ever disagree.
2. **No integration, e2e, `dcf_model` or `valuation_baseline` suites were run.**
   They are deferred by the plan and exceed the builder's time budget.
   Suggested, from `apps/windows/src-tauri`:
   - `cargo test --lib probe_facts_without_a_filing_date -- --ignored --nocapture`
   - `cargo test --lib dcf_model::` and `cargo test --lib valuation_baseline::`
     (the mandatory `AGENTS.md` automated gate for a change to the CapEx-to-FCF path)
   - the per-issuer driver-year comparison over PG/GOOGL/AMZN/MSFT and the
     26-name cohort
   I did run the full `cargo test --lib` twice, because R-4 requires me to
   measure my own exit baseline; that is reported in §1 and is not offered as a
   substitute for the deferred checks.
3. **LD-6 stands unresolved by design.** Composed drivers (total debt, FCF,
   recurring development) carry provenance but have no `as_of`. Documented in
   the doc and in `AnnualSeries::as_of`'s rustdoc, not silently omitted.

## 8. Quality statement

No known smell was left "for later" without being listed here. The three things
a reviewer should look at first are: the accession fail-close in §6(a), the two
new dead-code warnings in §5, and the fact that the point-in-time API this wave
built has **no production consumer yet** — it is exercised only by unit tests
until item 6 extends `FcfPoint`.

No test, threshold or refusal path was moved or weakened. No absence became a
zero: `AnnualProvenance` cannot be constructed without at least one source, which
is why mutation M14 (fabricating a zero for an absent driver) turns five tests
red instead of passing quietly.
