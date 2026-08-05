# Probe H: interest basis versus sign

Branch `measure-guard-rules`, base `2e02f26`, worktree `G:/dev/repos/discount_screener-wt-measure`.
Raw output: `.agents/workspace/tmp/e2e/valuation-pit-contract/build/basis-versus-sign-raw.txt`
(`cargo test --lib probe_interest_basis_versus_sign -- --ignored --nocapture`, 14.62s, exit 0).

## Universe

Per the coordinator's mid-task correction, the universe is the **union of three named
populations**, deduplicated:

- `VALUATION_ANCHORS` (4): PG GOOGL AMZN MSFT
- `INTEREST_SIGN_AFFECTED_COHORT` (25): ABBV ADSK AXON CARR COR CPRT DDOG JKHY MPWR NKE NWS NWSA
  OTIS PAYX RMD ROL ROST TPR TTD TYL ULTA WSM XYZ YUM ZBRA
- `PROBE_COHORT` (28): DVN FIS AVY SW COF MPWR APH EME CHTR BKR INTU TER AVGO EPAM T GEHC DAL WDC
  GOOGL HPE CRM SLB EXE OMC PTC PG MSFT AMZN

Union = **52 distinct symbols**. DAL, CHTR, BKR, COF (the P3 net-EXPENSE suspects) were already
members of `PROBE_COHORT` — confirmed present, none added separately.

Fetch result: **52/52 fetched, 0 failed.** No `n_sources != 1` anomaly was ever printed (structural
fact 3 held on every one of the 772 issuer-year rows measured). EPAM extracted zero
interest-expense years and is reported as absent, not zero, everywhere downstream.

## Table 1 — per issuer-year basis facts (condensed; full table in raw output, 772 rows)

`total_debt_dollars` is read via `crate::edgar::extract_total_debt()` — the exact function
`fetch_fcf_history` calls to fill `FcfPoint.total_debt_dollars` (`pub(crate)`-widened for this
probe; see "Production edits" below) — so it cannot drift from what `resolve_rate_inputs` sees.

Sample rows (see raw file for all 52 issuers):

| symbol | year | winning_qname | is_net | interest_$ | total_debt_$ | n_src |
|---|---|---|---|---|---|---|
| ABBV | 2011 | InterestIncomeExpenseNonoperatingNet | true | -20,000,000 | n/a | 1 |
| ABBV | 2012 | InterestExpense | false | 104,000,000 | n/a | 1 |
| COR  | 2008 | InterestIncomeExpenseNet | true | -64,496,000 | n/a | 1 |
| COR  | 2025 | InterestIncomeExpenseNonoperatingNet | true | 291,548,000 | 7,660,773,000 | 1 |
| CHTR | 2013 | InterestExpense | false | 846,000,000 | 14,181,000,000 | 1 |
| CHTR | 2014 | InterestIncomeExpenseNet | true | 911,000,000 | 21,023,000,000 | 1 |
| BKR  | 2015..2025 | InterestIncomeExpenseNet (all 11 yrs) | true (all) | always positive | mostly present | 1 |
| DAL  | 2010 | InterestExpense | false | 1,004,000,000 | 13,179,000,000 | 1 |
| DAL  | 2011 | InterestIncomeExpenseNet | true | 901,000,000 | 11,847,000,000 | 1 |
| COF  | 2023 | InterestExpense | false | 12,697,000,000 | 49,856,000,000 | 1 |
| COF  | 2024 | InterestIncomeExpenseNet | true | -31,208,000,000 | 45,551,000,000 | 1 |

**Finding not anticipated by the brief:** COR's winning qname is `Interest*Net` for **every single
filed year** (2008–2025, 18/18 years) — it never once wins on a gross concept. CHTR, BKR and DAL
also flip to a net concept and then **stay positive for years** before (DAL, COF) or without ever
(CHTR, BKR) going negative — direct, filed confirmation that basis and sign are different axes, not
that sign lags basis by one year.

## Table 2 — the four candidate refusal sets

Predicates (verbatim, printed in the raw output beside the sets):

- **(A)** as implemented — refuse issuer if ANY year has `interest_value < 0`
  (`driver_resolution.rs` `net_interest_years`, lines 134-142).
- **(B)** narrower window — refuse issuer if any year with `total_debt > 0` has `interest_value < 0`.
- **(C)** basis, issuer-wide — refuse issuer if ANY year has `is_net == true`.
- **(D)** basis, per-year — drop every `is_net == true` year; refuse only if the surviving fittable
  set (`debt > 0 && interest > 0`) is EMPTY.

| rule | count | refused |
|---|---|---|
| (A) | 26 | ABBV ADSK AXON CARR COR CPRT DDOG JKHY MPWR NKE NWS NWSA OTIS PAYX RMD ROL ROST TPR TTD TYL ULTA WSM XYZ YUM ZBRA **COF** |
| (B) | 11 | ADSK CPRT JKHY NKE NWS NWSA RMD ROST TPR ZBRA COF |
| (C) | 29 | (A)'s 26 + **CHTR BKR DAL** |
| (D) | 11 | ADSK **COR** DDOG MPWR NKE NWS NWSA TTD ULTA WSM BKR |

**Honesty caveat (required by the brief):** production additionally intersects the accounting-fit
years with `tax_years` (`driver_resolution.rs:184-191`, `accounting_common`) before a rate is
actually resolved. Neither (B) nor (D) computes that intersection — this probe only carries the
interest and debt series, not the marginal-tax series. Direction of the error: a year (B)/(D) counts
as usable can still be dropped downstream for lacking an aligned marginal-tax year, but a year
(B)/(D) already excludes was never going to be used either way. So **(B) and (D) can only be as
permissive or more permissive than the real production fit, never stricter** — their refused-counts
are a **lower bound** on what production actually refuses, and an issuer they show as surviving may
still be refused in the real pipeline for a reason this probe does not measure.

## Table 3 — confusion matrix, ground truth = is_net (rule C)

| granularity | rule | TP | FP | FN | TN |
|---|---|---|---|---|---|
| issuer-year | (A) vs (C) | 96 | **0** | 122 | 554 |
| issuer-year | (B) vs (C) | 23 | **0** | 195 | 554 |

Per issuer:

- Refused by **(C) but not (A)** — the net-EXPENSE filers, the LD-8 population (3): **CHTR, BKR,
  DAL**.
- Refused by **(A) but not (C)** — would falsify "negative implies net" (0): none.

## Table 4 — trigger-year audit (population: `INTEREST_SIGN_AFFECTED_COHORT` only, 25 names)

Every one of the 25 named issuers is refused by (A) in this run. Every earliest trigger year has
`fittable? = no` — by construction: the trigger year's own `interest_value < 0` means it can never
satisfy the fit predicate's `interest > 0` leg, so the year that vetoes the *whole issuer* is never a
year the accounting fit itself would have consumed.

| symbol | trigger year | winning_qname | total_debt_$ | fittable? |
|---|---|---|---|---|
| ABBV | 2011 | InterestIncomeExpenseNonoperatingNet | n/a | no |
| COR  | 2008 | InterestIncomeExpenseNet | n/a | no |
| TYL  | 2009 | InterestIncomeExpenseNonoperatingNet | n/a | no |
| YUM  | 2007 | InterestIncomeExpenseNet | n/a | no |

Named checks: **ABBV MATCH (2011), COR MATCH (2008), TYL MATCH (2009), YUM MATCH (2007)** — all
four expected trigger years confirmed exactly. (Full 25-row table in the raw output.)

## What this falsifies

- **P1** — every issuer (A) refuses has ≥1 year whose winning qname is net.
  **SURVIVED.** `a_not_c` (refused by A but not C) is empty: all 26 issuers (A) refuses are inside
  (C)'s 29.

- **P2** — FP(A) against basis = 0: no negative year is won by a gross concept.
  **SURVIVED.** Measured FP(A) = 0 across all 772 issuer-year rows.

- **P3** — (A) is a STRICT subset of (C); (C) refuses at least one more issuer, specifically
  DAL/CHTR/BKR if they file net-expense.
  **SURVIVED.** `|A|=26`, `|C|=29`, strict subset holds, and the extra three are named exactly:
  `named_evidence=[CHTR BKR DAL]`. COF does **not** supply extra evidence for P3 — COF is refused by
  *both* (A) and (C) (its 2024/2025 net interest reading goes deeply negative, -$31.2B / -$42.9B,
  plausibly Discover-acquisition-driven), so it is not part of the "sign misses it, basis catches it"
  gap; DAL/CHTR/BKR are.

- **P4** — |D| much smaller than |A|=24; point estimate (D) refuses ≤ 6 issuers.
  **FALSIFIED.** Measured `|A|=26` (not the brief's reference point estimate of 24 — see note below)
  and `|D|=11`. |D| is smaller than |A| (11 < 26, roughly 2.4×, not obviously "much smaller"), but
  11 > 6, so the numeric ceiling in P4 is directly falsified. `(D)`'s 11 refused issuers are: ADSK,
  **COR**, DDOG, MPWR, NKE, NWS, NWSA, TTD, ULTA, WSM, BKR.

- **P5** — under (D), YUM/COR/TYL/ABBV are NOT blacklisted by their 2007/2008/2009/2011 years.
  **FALSIFIED for COR, survived for the other three.** YUM, TYL and ABBV each have a non-empty
  fittable set under (D) once their net years are dropped (YUM: 17 surviving years back to 2009; TYL:
  5 years 2021-2025; ABBV: 13 years 2013-2025) — for those three, dropping the specific net year does
  rescue the issuer as predicted. **COR does not survive**: COR's winning qname is net for
  **every filed year (18/18, 2008-2025)**, so dropping the `is_net` years under (D) empties COR's
  series entirely and (D) refuses it — not "by its 2008 year" specifically, but because COR has no
  gross-concept year to fall back on at all.

## A finding that updates a previously-stated reference number

The brief and the coordinator's correction both reference a prior measurement of "24-of-25" /
"|A|=24" issuers refused within `INTEREST_SIGN_AFFECTED_COHORT`. This run measures **25 of 25**
(all) `INTEREST_SIGN_AFFECTED_COHORT` members refused by rule (A) — plus COF from `PROBE_COHORT`,
for 26 total in the wider union. I cannot identify which single issuer the earlier "24" excluded (no
prior per-issuer table was provided to diff against), so I am reporting the discrepancy rather than
reconciling it: either new fiscal-year filings since the R-10 scan pushed a 25th name's interest
series negative for the first time, or the two measurements used different mechanics. This does not
change any P1-P3 verdict (all three used the measured 26/29, not the brief's reference 24), but it
does directly change P4's arithmetic, which is why P4 restates both the reference and the measured
value rather than silently substituting one for the other.

## Structural facts — status

All four given structural facts held throughout this measurement with **zero exceptions**:
1. `AnnualProvenance.sources` carried exactly one `SecFact` with a `qname` for every one of the 772
   issuer-year rows extracted via `select_one_equivalent` (`n_sources == 1` on every row; no
   `*** UNEXPECTED` line was ever printed).
2/4. Every `is_net == true` row's `winning_qname` was one of `InterestIncomeExpenseNet` /
   `InterestIncomeExpenseNonoperatingNet` — read live from `INTEREST_EXPENSE.qname_signs`, not
   hand-typed — confirming these are exactly the two negated concepts.
3. Never contradicted: n_sources was 1 in all 772 rows.

## Production edits (full list, minimum required)

Exactly one, a visibility widening, no logic changed:

- `apps/windows/src-tauri/src/edgar.rs`: `fn extract_total_debt` → `pub(crate) fn extract_total_debt`,
  with a doc comment explaining why (so `valuation_probes.rs` reads total debt through the exact
  composition `fetch_fcf_history` feeds `FcfPoint`, instead of a probe-local reimplementation that
  could silently drift from it).

## Compile / test delta

- Baseline (`git stash` back to `2e02f26`, `cargo test --lib`): **557 passed, 4 failed, 24 ignored.**
  Pre-existing failures: `cross_platform_parity::export_random20_sp500_parity_snapshot`,
  `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`,
  `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`,
  `valuation_high_signal::high_signal_screener_cohort_all_members_pass`.
- With this probe (`cargo test --lib`): **557 passed, 4 failed (same four), 25 ignored** (the +1 is
  this probe, correctly `#[ignore]`d). Zero delta beyond the one new ignored test.
- `high_signal_screener_observation_2026-08-02.json` is rewritten as a side effect of running
  `valuation_high_signal::high_signal_screener_cohort_all_members_pass` regardless of these changes
  (it is an audit-dump test, not a golden fixture the suite asserts against). Reverted with
  `git checkout --` both times it appeared, per the brief's explicit prohibition on touching it.

## `git status --short`

```
 M apps/windows/src-tauri/src/edgar.rs
 M apps/windows/src-tauri/src/valuation_probes.rs
?? .agents/workspace/tmp/e2e/valuation-pit-contract/build/basis-versus-sign-raw.txt
?? .agents/workspace/tmp/e2e/valuation-pit-contract/build/basis-versus-sign.md
```
