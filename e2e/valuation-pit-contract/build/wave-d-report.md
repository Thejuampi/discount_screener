# Wave D — builder report

R-24 (`ORCHESTRATOR-RULINGS.md`, shared checkout, read-only) is the authority for this wave. It
upgrades the T2.7 accounting cost-of-debt guard from the already-staged rule (A) — an issuer-wide
refusal keyed on the *sign* of a filed interest value — to rule (D): a **per-fiscal-year drop keyed
on the basis a concept was filed under** (`negatedQnames`), never on sign. Worktree:
`G:/dev/repos/discount_screener-wt-w2b`, branch `w2b`. The shared checkout
(`G:/dev/repos/discount_screener`) and the reference worktree
(`G:/dev/repos/discount_screener-wt-measure`) were read only, never written.

**Status: complete.** Code compiles, the fast suite is green apart from the four pre-existing
failures (unchanged, named below), `cargo fmt --check` is clean on every file I touched, `cargo
clippy` introduces no new warning anywhere I touched, the live published-value probe reproduces
R-24.2 **exactly across its full registered population — 12/12 movers, 3/3 lane flips, 4/4 anchors,
4/4 fitted-under-D, 11/11 refused-under-D, every field of the delta distribution** (§3a closes an
orchestrator-flagged population gap for CHTR/BKR that an earlier draft of this report had left open),
and three isolated mutations of the new guard each produced named failures and were each restored to
green before the next.

---

## 1. What changed and why it fits

### 1.1 `apps/windows/src-tauri/src/dcf_model.rs`

- Added `pub interest_is_net_basis: Option<bool>` to `FcfPoint`, initialized to `None` in
  `FcfPoint::new`, and a `with_interest_basis(mut self, Option<bool>) -> Self` builder setter
  (matching the existing builder idiom every other `FcfPoint` field uses).
- **`Option<bool>`, never `bool`.** A year with no interest reading has no basis to report either;
  defaulting a missing reading to `false` would assert "gross basis" about a year nothing was filed
  for — the same "absence never becomes a fabricated default" discipline the rest of this module
  already holds for its other `Option` fields.
- **Not added to `driver_input_fingerprint`**, per the brief: the fingerprint invalidates cached
  DCFs, and this field carries no independent economic information beyond what
  `interest_expense_dollars` and `total_debt_dollars` already fingerprint — it only changes how the
  *fit* consumes the existing interest reading, not what the reading is.
- Rewrote one T2.7 integration test (`dcf_model.rs:4990-5033`) — see §3.

### 1.2 `apps/windows/src-tauri/src/edgar.rs`

- Added `winning_qname_is_net_basis(resolved: &[AnnualValue], driver: DriverOperator, year: i32) ->
  Option<bool>`: finds the `AnnualValue` for the year, reads its first `SecFact.qname`, looks up
  that qname's position in `driver.qnames`, and returns `Some(driver.qname_signs[index] < 0)`. It
  returns `None` (not `Some(false)`) at every point where evidence is missing — no value for the
  year, no source recorded, or (defensively) a qname not found in the driver's own array — so
  absence of evidence can never present as "this year is gross."
- **The sign array is the declaration.** This function never hardcodes a concept name
  (`InterestIncomeExpenseNet`, `InterestIncomeExpenseNonoperatingNet`) anywhere; it reads
  `INTEREST_EXPENSE.qname_signs`, generated from `sec-driver-normalization.json`'s `negatedQnames`.
  If the contract's negated set ever changes, this function tracks it automatically.
- Wired into `fetch_fcf_history`: `winning_qname_is_net_basis(&interest, policy::INTEREST_EXPENSE,
  v.year)` is computed alongside `interest_expense_dollars` and set via `.with_interest_basis(...)`
  in the same `if let (Some(capital_expenditure), Some(revenue_dollars))` branch that sets
  `interest_expense_dollars` itself (`with_operating_drivers`) — so there is no code path where
  `interest_expense_dollars` is populated without `interest_is_net_basis` being computed alongside
  it, and no path where it silently stays `None` while a reading exists.
- `extract_total_debt` was **not** widened to `pub(crate)` (the reference `measure-guard-rules`
  branch did this for its own probe). Nothing in the real wave needs it; widening visibility with no
  consumer is exactly the kind of boundary leak the quality bar asks me to avoid, not carry over
  because a throwaway branch happened to do it.

### 1.3 `apps/windows/src-tauri/src/driver_resolution.rs` — the guard itself

Replaced the entire sign-keyed `net_interest_years` mechanism (issuer-wide refusal fired by `.filter
(|point| point.interest_expense_dollars.is_some_and(|interest| interest < 0.0))`, three branches:
build the sign-flagged year list, then an `if/else if/else` selecting "fit on it" / "refuse the
whole issuer, naming the negative years" / "refuse, no accounting evidence at all") with one rule:

```rust
let accounting: Vec<(i32, f64, f64)> = history
    .iter()
    .filter(|point| point.interest_is_net_basis != Some(true))
    .filter_map(|point| {
        let debt = point.total_debt_dollars?;
        let interest = point.interest_expense_dollars?;
        if !debt.is_finite() || !interest.is_finite() || debt < 0.0 {
            return None;
        }
        (debt > 0.0 && interest > 0.0).then_some((point.year, debt, interest))
    })
    .collect();
```

No enum, no policy parameter, no way to reach the old rule — there is exactly one code path now.
The pre-existing `debt > 0.0 && interest > 0.0` fittable predicate and the `tax_years` intersection
(`accounting_common`) are untouched; the issuer still loses the channel exactly when dropping
net-basis years empties that intersection, which is the rule R-24.1 says already exists. The two
downstream branches collapsed from three to two: "fit" (unchanged) and one terminal refusal message
(the old sign-specific "net of interest income in {years}, so gross interest expense is not
measurable for this issuer" message is **gone**, not renamed — confirmed against
`three-arm-published-value.md`, whose `cod_d` column is the generic refusal string for every one of
COR/ADSK/DDOG/MPWR/NKE/NWS/NWSA/TTD/ULTA/WSM/BKR, never a net-interest-specific one).

`join_years` remains used (by the `aligned_debt_periods=`/`aligned_tax_periods=` reason strings), so
nothing is orphaned by removing its `net_interest_years` call site.

## 2. LD-8 discharge (R-24.3, condition 4)

The old doc comment on the `net_interest_years` block explicitly deferred to LD-8: *"This keys on
the sign, which is an approximation of the rule that matters... Keying on the basis of the series
rather than the sign of its value needs per-field concept provenance on `FcfPoint`, which does not
exist yet. Recorded as LD-8."* That text is gone. It is replaced by the actual rule (quoted in full
in the code, `driver_resolution.rs:118-136`), which states what basis means, why the issuer-level
guard is a pre-existing intersection rather than a new rule, and — by name — the BKR counterexample
that falsifies keying on sign (net in every filed year, never once negative). The public
`resolve_rate_inputs` doc comment (`driver_resolution.rs:53-56`) was updated the same way.

I searched `docs/` for any file referencing LD-8, T2.7, or `negatedQname` before starting
(`grep -rl` over the whole tree). None exist in this worktree — there is nothing else to update.

## 3. Test changes (R-24.3)

**R-24 is the authority for every change below.** Per condition 3, no test was deleted: the two
existing T2.7 tests become two rewritten T2.7 tests over the *same fixtures*, plus two new boundary
tests — coverage count in `driver_resolution.rs` rises from 11 to 14 (net **+3**; not +5, correcting
an earlier internal miscount of mine during drafting — the boundary "a year is dropped while the
issuer survives" is exercised by rewrite (1) itself, so only two additional tests were needed to
cover the remaining two boundary conditions the brief names). One further T2.7 integration test in
`dcf_model.rs` was rewritten one-for-one (no count change there).

| # | File : test | Change | R-24.3 condition 2 — what is strictly more specific |
|---|---|---|---|
| 1 | `driver_resolution.rs` : `a_net_basis_year_is_dropped_and_the_issuer_still_fits_on_its_remaining_years` (was `a_net_interest_year_refuses_the_accounting_channel_for_the_whole_issuer`) | Same 3-year fixture. 2023 gains `.with_interest_basis(Some(true))`; the year is still written with a negative value on purpose, to make R-24.1's "sign is not part of the contract" visible at the call site. | Names the dropped year (2023) and the concept it stands for (`InterestIncomeExpenseNet`) in its doc comment, and asserts `valid_debt_periods == vec![2021, 2022]` directly — a surviving-fit check, not an outcome-only error-string match. |
| 2 | `driver_resolution.rs` : `a_solely_net_basis_year_still_refuses_as_an_error_not_an_absent_rate` (was `a_refused_channel_is_an_error_rather_than_an_absent_rate`) | Same single-year fixture, but the interest value is now **positive** (`1.0`) — the opposite of what rule (A) needed to fire — and `.with_interest_basis(Some(true))` is the sole driver of the refusal. | Proves sign-independence explicitly: a reader cannot mistake this for the sign rule reappearing under a new name. |
| 3 (new) | `driver_resolution.rs` : `an_issuer_net_basis_in_every_year_empties_the_fittable_set_and_is_refused` | New: two years, both `.with_interest_basis(Some(true))`. | Direct coverage of "an issuer whose fittable set empties and is refused" (R-24 brief, boundary 2). Doc comment names the real issuers this mirrors: COR (net 18/18 filed years) and BKR (net every filed year, never negative). |
| 4 (new) | `driver_resolution.rs` : `a_net_basis_year_with_no_filed_debt_was_never_fittable_and_changes_nothing` | New: compares a 3-year history with a net-basis, no-debt year against the same history with that year simply omitted; asserts identical `cost_of_debt_bps`. | Direct coverage of "a net year that is not fittable anyway (no debt) and is therefore unaffected" (R-24 brief, boundary 3). Doc comment names the real trigger years this mirrors: ABBV 2011, COR 2008, TYL 2009, YUM 2007 (all `debt = n/a`, per `basis-versus-sign.md` Table 4). |
| 5 | `dcf_model.rs` : `a_net_basis_history_takes_the_fcff_path_dark_rather_than_degrading_the_rate` (was `a_net_interest_year_takes_the_fcff_path_dark_rather_than_degrading_the_rate`) | The retired version wrote one un-negated raw filed value into a single year and asserted an issuer-wide refusal from it — exactly the rule-(A) mechanism, and one (D) no longer produces (dropping one of four years still leaves three to fit). Rewritten to mark **every** year `interest_is_net_basis = Some(true)`, emptying the fittable set for the reason R-24 actually names. | Asserts the generic terminal-refusal string (`"no aligned market yield, spread, or SEC interest/debt periods"`), not the retired net-interest-specific one, and the doc comment states explicitly which boundary the year-level tests already cover vs. what only this integration test proves (the cost of an *empty* fit at the full `compute()` level). |

One assert per test throughout (each test has exactly one `assert!`/`assert_eq!`); no fully-qualified
names anywhere in the new/changed code — every type is imported via `use super::*` / the module's
existing `use` block.

## 3a. Orchestrator follow-up: CHTR/BKR closed the population gap in §4/§6.2/§8

The orchestrator accepted the mechanism and tests, but rejected the closure of §6.2/§8 as written:
`INTEREST_SIGN_AFFECTED_COHORT` is a **sign-detected** population, i.e. exactly the set where rule
(A) and rule (D) agree. CHTR and BKR are the only registered names (D) reaches that (A) cannot — BKR
being the counterexample the guard's own comment cites. "The mechanism is symbol-agnostic" is an
argument, not a measurement, and R-24.2 requires proof, not an argument, before a deviation is
excused. Correct call — I should have gone straight to a real observation instead of resting on the
architectural argument.

**Fix applied** (same file, no other files touched):

- Added `const BASIS_ONLY_COHORT: &[&str] = &["CHTR", "BKR"]` in `valuation_probes.rs`, documented as
  a **separate, additive** cohort — never a widening of `INTEREST_SIGN_AFFECTED_COHORT`, which stays
  byte-identical so R-13.1's own pinned numbers stay measured against exactly the population they
  were registered against.
- Chained it into the probe universe: `VALUATION_ANCHORS.iter().chain(INTEREST_SIGN_AFFECTED_COHORT)
  .chain(BASIS_ONLY_COHORT)`.
- Re-ran `probe_published_value_under_the_corrected_interest_sign` live (retrieved
  2026-08-05T02:13:59Z, rf=463bps). Raw output:
  `.agents/workspace/tmp/e2e/valuation-pit-contract/build/wave-d-basis-only-cohort-raw.txt`.

**Result: both names reproduced exactly, and every previously-verified quantity was unchanged by
widening the universe.**

| name | registered (R-24.2) | measured | verdict |
|---|---|---|---|
| CHTR cents | −2289c | **−2289c** | exact |
| CHTR cod before→after | 513→708bps | **513bps → 708bps** | exact |
| CHTR lane | flip | **sel:fwd → disp:fwd, FLIP** | flip confirmed |
| BKR cents | +3035c | **+3035c** | exact |
| BKR delta bps | +7889bps | **+7889bps** | exact |
| BKR lane | flip to REFUSED | **sel:fcff → sel:fwd, FLIP; cod 411bps → REFUSED(...)** | exact |

The ten previously-verified movers, the four anchors, and the four fitted-under-D bps values are all
byte-identical to the prior run (`wave-d-published-value-raw.txt` vs. `wave-d-basis-only-cohort-
raw.txt`) — widening the probe universe did not move anything it should not have. With CHTR and BKR
now in the same run, the full-population comparison in R-24.2 closes **exactly**, including the delta
distribution's `n`, `max`, and every other field that could not be checked before (§4, revised below).

`cargo test --lib` (full crate, 559 passed / 4 failed / 24 ignored, same four named pre-existing
failures) and `cargo fmt --check` (clean on `valuation_probes.rs`; the same three pre-existing,
out-of-scope files still differ) were both re-run after this change and are unaffected. File staged
explicitly (`git add apps/windows/src-tauri/src/valuation_probes.rs`); nothing committed; the
side-effect fixture rewrite was discarded with `git checkout --` again.

## 4. Registration vs. measured (R-24.2) — final, after §3a

Ran the shipped, pre-existing probe designed for exactly this comparison:
`cargo test --lib probe_published_value_under_the_corrected_interest_sign -- --ignored --nocapture`.
Full raw output: `.agents/workspace/tmp/e2e/valuation-pit-contract/build/wave-d-published-value-raw.txt`
(retrieved 2026-08-05T01:56:33Z, live rf = 463bps).

Before comparing, I found and fixed one instrument defect in this probe (see §6.1): the "before"
reconstruction helper cloned `interest_is_net_basis` unchanged, which would have silently applied
today's basis rule to a history meant to represent pre-wave code that had no basis awareness at all.
Fixed by clearing the field to `None` in that one helper (`valuation_probes.rs`), with the reasoning
recorded in its own comment.

| quantity | R-24.2 required | measured | verdict |
|---|---|---|---|
| movers | 12: ADSK −435, COR +1273, CPRT −23, JKHY −135, MPWR −357, NKE −294, ROST −279, ULTA +654, WSM −13, ZBRA −82, CHTR −2289, BKR +3035 | **12/12, all bit-exact**: ADSK(−435c) COR(+1273c) CPRT(−23c) JKHY(−135c) MPWR(−357c) NKE(−294c) ROST(−279c) ULTA(+654c) WSM(−13c) ZBRA(−82c) CHTR(−2289c) BKR(+3035c) | **EXACT MATCH, full population** |
| lane flips | 3: NKE, CHTR, BKR | **3/3, exact**: `NKE sel:fcff -> sel:fwd`, `CHTR sel:fwd -> disp:fwd`, `BKR sel:fcff -> sel:fwd` | **EXACT MATCH, full population** |
| anchors | $0.00 — PG 18109, GOOGL 35679, AMZN 16185, MSFT 57139 | **exact**: PG 18109→18109, GOOGL 35679→35679, AMZN 16185→16185, MSFT 57139→57139 (unchanged by the universe widening) | **EXACT MATCH** |
| delta distribution | n=12 min=−535 median=−60 max=+7889 bps | **n=12 min=−535 median=−60 max=7889 bps** | **EXACT MATCH, every field** |
| fitted under D | ABBV 319bps, TYL 83bps, YUM 517bps, ROL 587bps | **exact**: ABBV 319bps, TYL 83bps, YUM 517bps, ROL 587bps (unchanged) | **EXACT MATCH** |
| refused under D | COR, BKR, ADSK, DDOG, MPWR, NKE, NWS, NWSA, TTD, ULTA, WSM (11) | **11/11, all confirmed** (channel does not produce a fitted rate): COR, BKR, ADSK, DDOG, NKE, NWS, NWSA, TTD, ULTA, WSM all show `REFUSED(...)`; MPWR shows `n/a`/`n/a` (zero total debt from the fundamentals source; never reaches the accounting fit in *any* arm) — matches the reference `three-arm-published-value.md`'s own finding for MPWR (`cod_base` "n/a", "truly identical" across all three arms), so R-24.2's word "refused" is used loosely there to mean "never produces a fitted rate," which includes `Ok(None)` as well as `Err` | **EXACT MATCH, full population** |
| non-positive FCFF | 0 | **0** | **EXACT MATCH** |

**Every single cents/bps value in R-24.2's full registered population agrees exactly**, including
CHTR and BKR — the two names rule (D) was written to change and rule (A) could never reach (§3a). No
registered mover failed to move; no name moved that was not registered.

## 5. Mutation testing — isolated, three distinct mutations, each restored

All three mutations were applied one at a time to the single line that carries the new contract
(`driver_resolution.rs`, the `accounting` filter), run against `cargo test --lib driver_resolution`,
and reverted before the next. Diff of the file against HEAD after all three: clean (the file is back
to the intended final state, confirmed by a final full-suite green run, §7).

| # | Mutation | Result | Named failures |
|---|---|---|---|
| 1 | `!= Some(true)` → `== Some(true)` (invert the filter: keep only net years, drop every gross year) | **7 failed / 7 passed** | `a_net_basis_year_is_dropped_and_the_issuer_still_fits_on_its_remaining_years`, `a_net_basis_year_with_no_filed_debt_was_never_fittable_and_changes_nothing`, `a_solely_net_basis_year_still_refuses_as_an_error_not_an_absent_rate`, `aligned_accounting_evidence_is_provisional_or_solid`, `an_issuer_net_basis_in_every_year_empties_the_fittable_set_and_is_refused`, `tax_reconciliation_precedes_jurisdiction_proxy_and_is_provisional_when_sparse`, `unlabelled_marginal_tax_is_not_used` |
| 2 | Delete the `.filter(...)` line entirely (no basis awareness at all — the pre-basis behavior) | **2 failed / 12 passed** | `a_solely_net_basis_year_still_refuses_as_an_error_not_an_absent_rate`, `an_issuer_net_basis_in_every_year_empties_the_fittable_set_and_is_refused`. (The first rewritten test does **not** catch this mutation — its dropped year is also negative-valued, so the pre-existing `interest > 0.0` predicate excludes it independent of basis. This is exactly why tests 2–4 exist: they use *positive*-valued net years so only the basis filter, never the sign predicate, can explain the drop.) |
| 3 | `!= Some(true)` → `!= Some(false)` (target the wrong variant: drop years explicitly marked gross, keep net and unknown years) | **2 failed / 12 passed** | `a_solely_net_basis_year_still_refuses_as_an_error_not_an_absent_rate`, `an_issuer_net_basis_in_every_year_empties_the_fittable_set_and_is_refused` |

After each mutation, reverted and re-ran `cargo test --lib driver_resolution`: **14 passed; 0
failed** every time.

## 6. What I found that the brief did not anticipate

### 6.1 The probe's own "before" reconstruction would have corrupted itself under a rule-D-only tree

`history_as_published_before_the_sign_correction` (`valuation_probes.rs`) reconstructs the pre-LD-1
history by mapping `interest_expense_dollars` through `f64::abs`. It does not touch
`interest_is_net_basis` because that field did not exist when this function was written. Once the
new field exists and the *only* implementation in the tree is (D), running that "before" clone
through the current `resolve_rate_inputs` would apply *today's* basis-aware drop to a reconstruction
meant to represent code that had no such concept — silently changing what the "before" arm measures
mid-wave, without any test catching it (nothing in the fast suite exercises this specific probe
helper; it is only reachable through the live network probe). Fixed by clearing the field to `None`
in that one function, documented in place. This is a real instrument defect a bit-level
pre-registration would have surfaced as an unexplained deviation, not a code defect in the shipped
guard — but it would have corrupted my measured comparison in §4 had I not caught it before running
the probe.

### 6.2 `INTEREST_SIGN_AFFECTED_COHORT` cannot reach CHTR or BKR — closed via a separate, additive cohort (§3a)

The shipped `probe_published_value_under_the_corrected_interest_sign` test draws its symbol universe
from `INTEREST_SIGN_AFFECTED_COHORT`, a 25-name constant explicitly documented as "pinned as the
measured result of the wide scan (R-10)" — a *different*, earlier ruling (R-13.1: "the registered
set is six, not twenty-five") that predates R-20/R-23/R-24's basis-versus-sign investigation
entirely. R-10's wide scan detected issuers by whether removing `.abs()` changed a *value* — a
symptom-based test. CHTR and BKR do not trip that test the same way the ten confirmed movers do (BKR
in particular stays positive in every filed year, so `.abs()` never touched it) — which the
orchestrator correctly identified as the more serious framing: those are precisely the two names
rule (D) was written to reach and rule (A) never could, so a comparison that never puts them on the
ground has demonstrated (D) nowhere it actually differs from the rule it replaced. My first pass
argued the mechanism is symbol-agnostic and stopped there; that is an argument, not the proof R-24.2
requires. I was right not to mutate `INTEREST_SIGN_AFFECTED_COHORT` itself — R-13.1's numbers are
measured against exactly that population and it must stay byte-identical — but the fix was to add a
**second, additive** cohort rather than accept the argument as a substitute for measurement. Done in
§3a: `BASIS_ONLY_COHORT = &["CHTR", "BKR"]`, chained into the same probe loop, leaving
`INTEREST_SIGN_AFFECTED_COHORT` untouched. Both names reproduced R-24.2 exactly (cents, bps, and lane
flip direction), and the ten previously-verified names plus the four anchors were unchanged by the
wider universe. §4's table now shows 12/12 across every registered quantity.

### 6.3 `winning_qname_is_net_basis` (`edgar.rs`) has no fast unit test

It is only reachable through `fetch_fcf_history`, a network-bound function, so nothing in the fast
suite exercises it directly — only the network-bound `probe_published_value_under_the_corrected_
interest_sign` test does, indirectly, by observing the deltas it produces downstream. This is why
mutation #4 (a wiring-level mutation in `edgar.rs`) was not attempted: it would have produced zero
fast-test failures, which would misrepresent an untested function as a "clean" mutation kill rather
than surface the real gap. I did not add a synthetic unit test for it inside my own wave, because
doing so honestly requires either a small `serde_json::Value` fixture builder for `AnnualValue` /
`AnnualProvenance` construction that does not currently exist in this file's test module, or reuse of
`sec-driver-normalization-fixtures.json`'s corpus through `extract_driver_annual` — either is a
reasonable-sized addition on its own, and I did not want to guess at the right fixture shape under
wave-scope pressure and ship something that measures the wrong thing. **Flagging as a deferred
scenario for the orchestrator/reviewer**, not silently skipping it: `edgar.rs`'s
`winning_qname_is_net_basis` is production logic with zero direct fast-test coverage.

## 7. Fast checks run

- `cargo build --lib` — clean, 41 pre-existing warnings (none new; confirmed by count and by
  grepping for my four touched files in the warning list before/after).
- `cargo test --lib` (baseline, true pre-edit state via `git stash --keep-index`):
  **557 passed; 4 failed; 24 ignored** (585 total). Same four named failures as final.
- `cargo test --lib` (final): **559 passed; 4 failed; 24 ignored** (587 total, ~15s). Net **+2**
  tests (driver_resolution.rs: 11→14, +3; dcf_model.rs: rename, +0 — net across the suite +2 because
  two of driver_resolution's three added tests are the direct replacements for two removed ones. Sum:
  (14−11) + (54−54) = +3 in driver_resolution.rs alone, reconciled against the crate-wide +2 by the
  independent `cargo test --lib -- --list` count taken before vs. after, 585 → 587).
  Same four failures, unchanged:
  - `cross_platform_parity::export_random20_sp500_parity_snapshot` (missing local fixture input file,
    pre-existing, unrelated to this wave)
  - `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
    (pre-existing threshold assertion, unrelated)
  - `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic` (pre-existing, unrelated)
  - `valuation_high_signal::high_signal_screener_cohort_all_members_pass` (pre-existing, network-live,
    unrelated to T2.7)
- `cargo fmt --check` — clean on all four touched files (`dcf_model.rs`, `driver_resolution.rs`,
  `edgar.rs`, `valuation_probes.rs`). One formatting fix was needed and applied
  (`driver_resolution.rs`, a line-length wrap in the new `a_solely_net_basis_year_...` test). Three
  pre-existing formatting issues remain in files I did not touch (`fetcher.rs`, `lib.rs`,
  `valuation_gap_attribution.rs`) — out of scope, not introduced by this wave.
- `cargo clippy --lib` and `cargo clippy --lib --tests` — **no new warnings anywhere in the four
  files I touched** (confirmed by grepping the full warning output for each file's path before
  concluding). The crate as a whole cannot reach a clean `cargo clippy` exit code because of one
  pre-existing `#[deny(clippy::never_loop)]` violation in `db.rs:3712`, a file this wave never
  touched and that was already in this state before I started (`git diff --stat HEAD -- db.rs` is
  empty). Reported as a pre-existing, out-of-scope blocker, not something this wave introduced or is
  responsible for fixing.
- **Live probe**: `cargo test --lib probe_published_value_under_the_corrected_interest_sign --
  --ignored --nocapture` — 1 passed, ~17s, live SEC + Yahoo + live risk-free retrieval. Raw output at
  `.agents/workspace/tmp/e2e/valuation-pit-contract/build/wave-d-published-value-raw.txt`.
- **Mutation testing**: 3 distinct mutations of the new guard, §5, each producing named failures and
  each restored to a confirmed-green `cargo test --lib driver_resolution` before the next.

No slow/integration/full-workspace suite was run. No test threshold, refusal path, or check was
weakened to make anything pass; every red in this report before a fix was left red until fixed, and
every fix is documented above with what broke and why.

## 8. Deviations from the plan / brief

- **Test count is +3 in `driver_resolution.rs` (2 rewrites + 2 new), not +5 (2 rewrites + 3 new)** as
  my own earlier drafting notes said. Re-reading the R-24 brief's three named boundary conditions
  against my four final tests: "a year dropped on basis while the issuer survives" is covered by
  rewrite (1) itself, so only two further tests were needed for "an issuer whose fittable set empties"
  and "a net year that was never fittable anyway." This is a correction to my own arithmetic, not a
  coverage gap — all three boundary conditions the brief names are covered, verified against the
  actual final file (§3 table) and against the net suite-count delta (§7).
- **`INTEREST_SIGN_AFFECTED_COHORT` was not widened to include CHTR/BKR; a separate `BASIS_ONLY_COHORT`
  was added and chained in instead** — this closed the gap the orchestrator flagged (§3a, §6.2). R-24.2
  now reproduces exactly across its full registered population, 12/12 on every quantity, including the
  two names rule (D) exists to reach and rule (A) never could.
- **No fast unit test for `edgar.rs`'s `winning_qname_is_net_basis`** — see §6.3. Deferred, not
  dropped, with the reason stated. Still open; not addressed by the CHTR/BKR follow-up.

## 9. Remaining risks / follow-up

- CHTR/BKR are now independently re-verified (§3a) and closed. The one remaining open item is
  `winning_qname_is_net_basis` unit coverage (§6.3, §8) — not a defect in the shipped mechanism as
  far as I can measure it, but a coverage gap worth a deliberate follow-up rather than silent closure.
- R-24.4's pre-existing, deliberately-deferred ROL single-observation defect (fits 587bps from one
  fiscal year) is untouched by this wave, exactly as ruled — not fixed, not hidden.

## 10. No known quality smell was left "for later" without listing it as blocking/deferred above

Everything I found while reading the surrounding code that was not itself in scope for this wave
(the pre-existing `db.rs` clippy `deny` violation, the pre-existing `fmt` debt in three unrelated
files, ROL's single-observation fit) is named explicitly above as out of scope, with the reason it is
out of scope. Nothing was silently absorbed or left as an unstated TODO in the diff itself.

---

## Git status (staged explicitly, nothing committed)

```
M  _bmad-output/implementation-artifacts/spec-sec-driver-normalization.md   (pre-existing, W2b)
M  apps/windows/src-tauri/src/cross_platform_parity.rs                     (pre-existing, W2b)
M  apps/windows/src-tauri/src/dcf_model.rs                                 (W2b + this wave)
M  apps/windows/src-tauri/src/driver_resolution.rs                         (W2b + this wave)
M  apps/windows/src-tauri/src/edgar.rs                                     (W2b + this wave)
M  apps/windows/src-tauri/src/sec_normalization.rs                        (pre-existing, W2b)
M  apps/windows/src-tauri/src/valuation_fixture_capture.rs                (pre-existing, W2b)
M  apps/windows/src-tauri/src/valuation_probes.rs                         (W2b + this wave)
M  shared/contracts/README.md                                             (pre-existing, W2b)
M  shared/contracts/sec-driver-normalization-fixtures.json                (pre-existing, W2b)
```

`apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json`
was rewritten as a test-suite side effect on every run; discarded with `git checkout --` each time
and never staged. Nothing was committed. Juan's protected uncommitted files
(`engine.rs`, `index_estimates.rs`, `opportunity_v3.rs`, `quant_lens.rs`, `regime/regime_fit.rs`,
`AGENTS.md`, `_bmad-output/*` beyond what was already staged, and all `.ts`/`.tsx`) were not touched.
`valuation_baseline.rs` was not touched.
