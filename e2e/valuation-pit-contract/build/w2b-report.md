# W2b — builder report

**Status: STOPPED AT A PAUSE TRIGGER. Do not merge W2b. Do not merge W2a (R-10.3).**

Four of the six stop conditions fired on the live measurement. The code is complete, the tests
pass, the documentation is written, and **the measured effect on published valuations disagrees
violently with the pre-registration** — in a way that is fully attributable, and attributable to
something the pre-registration could not have measured. The wave stops here and asks.

Nothing was adjusted toward the registered numbers. No test, threshold or refusal path was moved.

---

## 0. STEP 0 — base verification

| Check | Expected | Measured | Verdict |
|---|---|---|---|
| `git rev-parse HEAD` | `4d201cf` | `4d201cfb3f09ad4dcd2b4409538ab08e73a1620f` | PASS |
| `git log --oneline -3` | 4d201cf / 3bd20f2 / f5fdac2 | identical | PASS |
| contract fingerprint | `sec-driver-normalization/9` | `/9` at all sites | PASS |
| `qname_signs` in generated Rust | non-zero | present, `&[1,1,1,1,1,1,-1,-1,1]` | PASS |
| interest-`abs` sweep across `src` | 4 rows | 4 rows (3 in `dcf_model.rs`, 1 pre-existing in `driver_resolution.rs:82`) | PASS |

Worktree: `G:/dev/repos/discount_screener-wt-w2b`. The shared checkout was read only.

---

## 1. THE FINDING — the pre-registration measured a different intervention

### 1.1 What fired

| Stop condition | Fired? | Evidence |
|---|:-:|---|
| an anchor moves at all | **no** | PG 18109, GOOGL 35679, AMZN 16185, MSFT 57139 — all move **$0.00** |
| a router-lane flip | **YES** | **9 flips**, all `sel:fcff → sel:fwd`: CPRT JKHY NKE OTIS PAYX RMD TYL YUM ZBRA |
| a move upward | **YES** | 8 issuers up: COR +1273c, CPRT +698c, RMD +8315c, TYL +6340c, ROST +470c, ULTA +654c, ZBRA +1354c, OTIS +62c |
| a move beyond −157 bps | **YES** | n=18, min **−2897 bps**, median −24, max **+4988 bps** |
| a new non-positive FCFF | no | 0 |
| a fifth failing test | no | failing set unchanged by name |

### 1.2 The registered set against the measured set

Registered (R-13.1), measured on the T2.0/R-10 counterfactual: **6 movers, all downward**,
`n=6, min −157 bps, median −60, max −18`; **19 of 25 move $0.00**; **zero lane flips**.

```
registered: [ROST(-279c) MPWR(-357c) JKHY(-135c) ULTA(-124c) CPRT(-23c) NKE(-12c)]
observed:   [ADSK(-435c) AXON(-843c) COR(+1273c) CPRT(+698c) JKHY(-1492c) MPWR(-357c)
             NKE(-294c) OTIS(+62c) PAYX(-3506c) RMD(+8315c) ROST(+470c) TPR(-844c)
             TYL(+6340c) ULTA(+654c) WSM(-13c) XYZ(-467c) YUM(-5757c) ZBRA(+1354c)]
registered and did NOT move: []
moved and NOT registered:    [ADSK AXON COR OTIS PAYX RMD TPR TYL WSM XYZ YUM ZBRA]   (12, each a trigger (c.2))
```

Exactly **one** registered value reproduced to the cent: **MPWR −357c**.

### 1.3 Why — measured, not argued

`probe_published_value_under_the_corrected_interest_sign` prints the accounting cost-of-debt
channel for both arms. **24 of the 25 affected issuers go from a fitted rate to a refusal**:

```
ABBV 319bps -> REFUSED(... net of interest income in 2011 ...)
COR  453bps -> REFUSED(... in 2008 ...)
TYL   83bps -> REFUSED(... in 2009 ...)
YUM  517bps -> REFUSED(... in 2007 ...)
... 20 more
```

and their FCFF candidate goes `absent` (column `fcff a c`), which is what flips 9 published lanes.

**The decisive control is MPWR.** MPWR is the only issuer whose cost-of-debt column reads `n/a` in
**both** arms — it never used the accounting channel, so T2.7's refusal cannot touch it. It is also
the only issuer whose measured move equals its registered move exactly. Every other registered
mover reads `Nbps → REFUSED`.

**Confirmed at the source.** The T2.0/R-10 counterfactual worktree
(`G:/dev/repos/discount_screener/.claude/worktrees/agent-a19c1b1e4036e2768`) still carries the
legacy line at `driver_resolution.rs:118`:

```rust
if !debt.is_finite() || !interest.is_finite() || debt < 0.0 || interest < 0.0 {
```

— the **silent year-drop**. It does not carry T2.7's refusal. So R-13 is a pre-registration of
**T2.6 alone**, with the legacy year-drop still in place. W2b's scope is **T2.6 + T2.7**. The two
are not the same intervention and the registration never covered the second one.

### 1.4 The part that most needs a human ruling

The refusals are triggered by **very old years that the fit would never have used**:

| issuer | the only years that trigger the issuer-wide refusal |
|---|---|
| YUM | 2007 |
| COR | 2008 |
| TYL | 2009 |
| ABBV | 2011 |
| OTIS, DDOG, XYZ | one year each (2018 / 2018 / 2016) |

A single net-interest year from 2007 now takes an issuer's whole FCFF lane dark in 2026.

The plan's own precedent section names this outcome as the one this project already rejected:

> "this project already ran the all-refuse alternative. The credit-curve episode went 15-of-20 dark
> … and was resolved by fixing five real evidence bugs and *keeping* real evidence — never by
> fabricating a rung and never by accepting the cascade."

This is that cascade at **24 of 25**. I did not narrow the guard to reduce it, because narrowing the
economic contract to keep numbers publishing is forbidden, and because choosing the narrower rule is
an economic decision that belongs to Juan, not to the builder. Two readings of the T2.7 ruling exist
and both are defensible from the plan text:

- **(A) as implemented** — any negative year *anywhere in the filed series* refuses the channel
  issuer-wide. This is the literal ruling ("a net-negative year proves the series is net", a
  property of the series, not of a window) and it produces the cascade above.
- **(B) narrower** — refuse only when a negative year falls inside the set of years that would
  otherwise have been fittable (`debt > 0`). Also consistent with the anti-selection rationale, and
  would spare the issuers blacked out by a single pre-2012 year.

**I did not implement or measure (B).** Doing so after seeing the numbers would be choosing a rule to
hit a registered target. The measurement above is enough for a ruling; if Juan wants (B) quantified,
that is a fresh pre-registration and a fresh run.

### 1.5 A second finding: the project's own damage detector is blind to this

The 26-name high-signal cohort moved **one** number: MPWR, −357c. Every other member is bit-identical
and `pass=9/26` before and after, with an identical failing set and identical reason codes. Only 1 of
the 25 affected issuers is in that cohort. **A gate that shows 9/26 → 9/26 is not evidence that
published valuations did not move**, and this run is the proof.

---

## 2. Own baseline and exit counts

| | passed | failed | ignored |
|---|---:|---:|---:|
| baseline (`cargo test --lib`, this worktree, before any edit) | 550 | 4 | 23 |
| exit (`cargo test --lib`) | **557** | **4** | **24** |
| delta | **+7** | **0** | **+1** |

Orchestrator-supplied baseline (550 / 4 / 23) matched my own exactly.

The +7 are the seven new fast tests below; the +1 ignored is Probe G. Failing set at exit, by name —
**identical to baseline**:

- `cross_platform_parity::export_random20_sp500_parity_snapshot` — worktree-isolation artifact
  (`missing random20 inputs at .agents/workspace/tmp/random20-inputs.json`). Not created, not
  fixtured around, not relaxed.
- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

None of the three protected tests started passing (R10 does not apply).

---

## 3. The named tests

### T2.6 — three sign-preservation tests (verbatim)

```
test dcf_model::tests::a_net_expense_filing_reaches_the_bridge_as_a_positive_expense ... ok
test dcf_model::tests::a_net_income_filing_reaches_the_bridge_as_a_negative_expense ... ok
test dcf_model::tests::an_interest_series_and_its_negation_no_longer_agree ... ok
```

Plus a fourth, added after self-audit found a hole the three did not cover — the FCFF **driver
audit** site is reached by none of them, so an `.abs()` restored at that site alone would have left
the suite green:

```
test dcf_model::tests::the_fcff_driver_audit_subtracts_a_net_income_reading_too ... ok
```

### T2.7 — the guard tests

**Direct, at the `resolve_rate_inputs` boundary, with a genuinely negative `interest_expense_dollars`**
(constructed through a helper that writes the field rather than through `point(..)`, which goes via
the setter):

```
test driver_resolution::tests::a_net_interest_year_refuses_the_accounting_channel_for_the_whole_issuer ... ok
test driver_resolution::tests::a_refused_channel_is_an_error_rather_than_an_absent_rate ... ok
```

**End-to-end**, proving what the refusal costs — there is no lower rung, so it reaches the caller as
a terminal error rather than a weaker number:

```
test dcf_model::tests::a_net_interest_year_takes_the_fcff_path_dark_rather_than_degrading_the_rate ... ok
```

The direct pair pins the branch; the end-to-end pins the blackout. Both were required and both exist.

---

## 4. Verification discipline — isolated mutation (fourth clause)

Every mutation was applied alone, observed, restored, and green re-confirmed. **None produced a
blanket red.** The named new failures are the whole failure set beyond the protected four.

| # | mutation | new failures (isolated) |
|---|---|---|
| M1 | restore `.abs()` at the **setter** | 4, incl. `an_interest_series_and_its_negation_no_longer_agree`; `a_net_expense_filing…` stays green; T2.7's tests stay green (they bypass the setter) — proof the checks measure different things |
| M2 | restore `.abs()` at the **FCFF driver audit** | **1**: `the_fcff_driver_audit_subtracts_a_net_income_reading_too` |
| M3 | restore `.abs()` at the **aligned driver bridge** | 2 |
| M4 | drop the negative year instead of refusing (T2.7) | 2 |
| M5 | return `Ok` with an absent rate instead of `Err` | 3 |
| M6 | invert the sign test's expected direction | **1** |
| M7 | flip `qname_signs[6]` (`InterestIncomeExpenseNet`) `-1 → +1` in the generated policy | **2**: `frozen_real_sec_fixture_corpus_executes_at_the_normalization_boundary`, `generated_qname_signs_reconstruct_from_contract_negated_qnames` |

M7's output, showing the corpus catches it at the gap-filled year — the year where the negation is
the only thing acting:

```
assertion `left == right` failed: lin_2020_2024_net_expense_filed_negative
  left: {2020: -115000000, 2024: 256000000}
 right: {2020:  115000000, 2024: 256000000}
```

---

## 5. The canonical J6 check — before and after, by reference

Never retyped. Lifted from `plan.v6.md` line 1635 and executed with `Invoke-Expression`, from
`apps/windows/src-tauri/src`:

```
Select-String -Path dcf_model.rs -Pattern 'interest.*(abs\(\)|f64::abs)'
```

**BEFORE** (`build/w2b-probe-g-before-…`, run at STEP 0): 3 rows — `:551`, `:907`, `:1590`.
**AFTER**: `(no rows)` — ROW COUNT 0.

> An intermediate run of the AFTER check returned **3 rows from my own new tests**
> (`LIN_FY2024_FILED_NET_EXPENSE_DOLLARS.abs()`). Benign code, but it made the canonical detector
> fire, which permanently degrades the instrument for every later reader. Rewritten to
> `as_the_contract_negates_it(LIN_FY2024_FILED_NET_EXPENSE_DOLLARS)` — the same value, and a truer
> statement of where it comes from. Recorded rather than quietly fixed.

---

## 6. The live per-issuer table

`build/w2b-probe-g-after.txt`, **retrieved 2026-08-04T22:24:00Z**, live risk-free 463 bps.
The "before" table is `build/w2b-probe-g-before-2026-08-04T2122Z.txt` (retrieved
2026-08-04T21:42:41Z, TREE CHECK **NO**, all deltas zero — the instrument validated against a tree
that could not carry the change).

```
symbol   yrs   before c    after c   delta c delta bps   fcff b c   fcff a c   lane b     lane a
PG         0      18109      18109         0         0      18109      18109   sel:fcff   sel:fcff
GOOGL      0      35679      35679         0         0      50810      50810   sel:fwd    sel:fwd
AMZN       0      16185      16185         0         0      11099      11099   sel:fwd    sel:fwd
MSFT       0      57139      57139         0         0      50567      50567   sel:fwd    sel:fwd
ABBV       1      41346      41346         0         0      46133     absent   sel:fwd    sel:fwd
ADSK       6      30174      29739      -435      -144      28710     absent   sel:fwd    sel:fwd
AXON      10      19362      18519      -843      -435      19340     absent   sel:fwd    sel:fwd
CARR       2       4511       4511         0         0     absent     absent   sel:fwd    sel:fwd
COR        1      54161      55434     +1273      +235      61303     absent   sel:fwd    sel:fwd
CPRT       1       2271       2969      +698     +3074       2271     absent   sel:fcff   sel:fwd   FLIP
DDOG       1       3965       3965         0         0     absent     absent   sel:fwd    sel:fwd
JKHY       2      16460      14968     -1492      -906      16460     absent   sel:fcff   sel:fwd   FLIP
MPWR      12      91657      91300      -357       -39      54630      51585   disp:fwd   disp:fwd
NKE        6       5492       5198      -294      -535       5492     absent   sel:fcff   sel:fwd   FLIP
NWS        7       2054       2054         0         0     absent     absent   sel:fwd    sel:fwd
NWSA       7       2054       2054         0         0     absent     absent   sel:fwd    sel:fwd
OTIS       1       7955       8017       +62       +78       7955     absent   sel:fcff   sel:fwd   FLIP
PAYX       2      12102       8596     -3506     -2897      12102     absent   sel:fcff   sel:fwd   FLIP
RMD        1      16670      24985     +8315     +4988      16670     absent   sel:fcff   sel:fwd   FLIP
ROL        3       3421       3421         0         0       3283     absent   sel:fwd    sel:fwd
ROST       3      17767      18237      +470      +265      12678     absent   sel:fwd    sel:fwd
TPR        3      10707       9863      -844      -788       9014     absent   sel:fwd    sel:fwd
TTD        2       4339       4339         0         0     absent     absent   sel:fwd    sel:fwd
TYL        1      23336      29676     +6340     +2717      23336     absent   sel:fcff   sel:fwd   FLIP
ULTA      10      68134      68788      +654       +96      51068     absent   sel:fwd    sel:fwd
WSM        4      15941      15928       -13        -8      11509     absent   sel:fwd    sel:fwd
XYZ        1       5979       5512      -467      -781       4059     absent   sel:fwd    sel:fwd
YUM        1      19998      14241     -5757     -2879      19998     absent   sel:fcff   sel:fwd   FLIP
ZBRA       1      24069      25423     +1354      +563      24069     absent   sel:fcff   sel:fwd   FLIP
```

- issuers with a live rewrite: **25**
- FCFF candidate moves: **20**
- published value moves: **18**
- lane flips: **9**
- cost-of-debt channel changes: **24**
- corrected FCFF non-positive: **0**
- anchors: all four **$0.00**

**COF** is not in this table's population and does not move: it is `FinancialServices` →
`residual_income`, and `fcf_history` is never passed. Confirmed live in the same session by the
26-name cohort run: `HIGH_SIGNAL_OK COF base=Some(16786) … model=Some("residual_income_equity")`,
identical to baseline. T2.7's ruling for COF ("accept the refusal; nothing published moves") holds
as measured.

### The 26-name high-signal cohort, before → after

`cargo test --lib valuation_high_signal` — read as a table, not as pass/fail. `pass=9/26` before and
after; failing set and reason codes byte-identical. **Only MPWR moved**: 91657 → 91300 (−357c). All
other members $0.00. The rewritten fixture
`tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json` was restored with
`git checkout --` and is **not** in the diff.

---

## 7. The five mandatory gate commands

| # | command | result |
|---|---|---|
| 1 | `cargo test --lib dcf_model::` | **ok. 54 passed; 0 failed** (baseline 49; +5 new) |
| 2 | `cargo test --lib valuation_baseline::` | **9 passed; 1 failed; 1 ignored** — the failure is the protected `baseline_megacap_amzn_class_not_penny_intrinsic`. Unchanged from baseline. |
| 3 | `cargo test --lib quant_lens::` | **ok. 9 passed; 0 failed** |
| 4 | `npm run test:e2e:native:cof` | **COULD NOT REACH A VERDICT IN THIS ENVIRONMENT — see below** |
| 5 | `cargo test --lib valuation_gap_attribution::` | **ok. 12 passed; 0 failed; 3 ignored** |

### Gate 4 is environment-blocked, and is neither a pass nor a code failure

`npm ci` had never been run in this worktree (`'tauri' is not recognized`); installed, then the
gate builds and launches the real binary — `Built application at target/debug/discount-screener-windows.exe`,
`discount_screener: launch profile locked to qa (20 symbols)` — and then **the WebView2 remote-debugging
target never appears**, so the harness's first `waitUntil` never resolves. Its `finally` then throws
`EPERM` removing the still-locked profile directory, which **masks the real error**.

Reproduced outside the harness (diagnostic script in the scratchpad, repo untouched): spawn the same
binary with the same environment, poll `http://127.0.0.1:9333/json/list` for 60 s →

```
TARGETS: null
APP OUTPUT: "discount_screener: launch profile locked to qa (20 symbols)\n"
```

The app runs; WebView2 has no interactive desktop to attach to in this session. **This gate needs a
session with a real desktop.** I did not edit the e2e script to work around it.

### The other required checks

| check | result |
|---|---|
| `cargo fmt -- --check` | **clean for every file I touched.** 5 diffs remain, all in files I never modified: `lib.rs` (2), `fetcher.rs` (2), `valuation_gap_attribution.rs` (1). Pre-existing at HEAD — the last commit before mine is literally *"style: cargo fmt the modules this effort authored"*. |
| `pwsh scripts/validate-contracts.ps1` | generator `-Check` **passes**; Rust contract fixtures **pass**; Kotlin `:core:test` fails one case — `valuation_model_family_policy2_fixtures_execute_against_core → amzn_capex_trough_does_not_invert_fcff_scenarios: expected <null> but was <67892702800>`. That test reads `valuation-model-family.json` only; it does not read `sec-driver-normalization*` at all (grep returns nothing), and neither file is in my diff. Pre-existing, and Android is deferred by the Windows-first ruling. |
| generator scratch-root diff | **byte-identical** for both targets after the fix below. Rust 6959 B gen vs 6959 B committed; Kotlin 7969 B vs 7969 B. |
| `sec-driver-normalization/8` search | **(no rows)** anywhere in the tree. The `/9` sites are the 5 required plus 4 more: `sec-driver-normalization.json:4`, `…-fixtures.json:3`, `sec_driver_normalization_policy_generated.rs:3`, `SecDriverNormalizationPolicyGenerated.kt:13`, `sec_normalization.rs:346`, and additionally `dcf_model.rs:923`, `dcf_model.rs:4880`, `cross_platform_parity.rs:468`, `spec-sec-driver-normalization.md:83`. |
| `cargo clippy -- -D warnings` | **not a passing gate on this repo, before or after.** `valuation-core` fails to compile under it (2 lints in `numerics.rs`, a file I never touched), and with those two allowed the Windows crate reports 92 + 100 errors. Of the findings inside files I touched, **none is in a line I authored** — verified against the diff hunk ranges. |

---

## 8. Blast radius, including the fourth consumer W2a flagged

R-16 hand-off (1): `valuation_probes.rs:486` is a **fourth** `.abs()`-dependent consumer absent from
§0's blast-radius table. Confirmed in my tree, with the citation corrected:

| site | what it is | what happens to it |
|---|---|---|
| `valuation_probes.rs:486` | `let interest = point.interest_expense_dollars?;` — the read | now yields the **signed** value |
| `valuation_probes.rs:493` | `nopat: (pretax + interest) * (1.0 - marginal_tax)` — the arithmetic R-16 attributed to `:486` | a net-interest-**income** filer now **reduces** NOPAT instead of increasing it |

**Ruling:** left as is, deliberately, and it is not a defect. This is a `#[ignore]` diagnostic probe,
publishes nothing, and the corrected sign makes its NOPAT arithmetically consistent with the FCFF
bridge for the first time. Any probe output produced before this wave and compared against output
produced after it is **not comparable for a net-income filer**, and that is recorded here so nobody
diffs the two and calls the difference a regression.

R-16 hand-off (3): §0's citations of `valuation_probes.rs:344`/`:354` are stale in my tree too; the
real sites are `:476` (presence-only, sign-agnostic) and `:486`/`:493`.

R-16 hand-off (2): `cross_platform_parity::mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers`
went red, as predicted, and was updated to assert the corrected sign — renamed
`mpwr_negative_interest_income_reaches_with_operating_drivers_still_negated`, asserting
`Some(-29_151_000.0)`.

> **This is the single case in this effort where changing a test is not weakening it.** The old
> assertion pinned the defect: it asserted that the setter *un-negated* MPWR's filed net interest
> income, which is precisely the behaviour LD-1 describes and this wave removes. Its doc comment now
> says so in those terms, so a reviewer reading the diff cold cannot mistake it for ground gained by
> relaxing a check. No other test's expectation was altered anywhere in this wave.

---

## 9. J10 — every `with_operating_drivers` site, enumerated and classified

The plan's PowerShell sweep returns **59 lines** here, not 45. Seven of those are prose (doc
comments, a test name, a `println!`), so the real surface is **51 call sites + 1 definition**.
Each call's 4th argument was extracted from the source (calls wrap across lines, so the call line
alone is not enough) and classified.

| file | lines | affected | unaffected |
|---|---:|---:|---:|
| `cross_platform_parity.rs` | 19 (10 prose/name) | 5 literal negatives + 1 data-fed | 8 |
| `dcf_model.rs` | 24 | 1 (my test helper) + 1 definition | 21 |
| `valuation_baseline.rs` | 11 | 0 | 11 (10 positive literals + 1 data-fed) |
| `driver_resolution.rs` | 2 (1 prose) | 0 | 1 |
| `edgar.rs` | 1 | **1 — the sole production site** | 0 |
| `valuation_probes.rs` | 2 (1 prose) | 1 | 0 |

**AFFECTED (a negative demonstrably reaches the site): 8**

- `edgar.rs:1447` — the sole production site; live filed data after the contract negation. This is
  the wave's purpose.
- `cross_platform_parity.rs:509, 518, 527, 536, 569` — five literal-negative MPWR rows, all authored
  by W2a (T2.12).
- `dcf_model.rs:4850` — `history_with_constant_interest`, my T2.6 helper; a negative is the point.
- `valuation_probes.rs:450` — `PROBE_INTEREST_DOLLARS = -1.0`, my TREE CHECK guard.

**DATA-FED, measured non-negative today: 3** — `cross_platform_parity.rs:274` (`Some(row.interest)`),
`dcf_model.rs:4118`/`:4504` (`by_year(&inputs.interest_expense_annual_dollars)`),
`valuation_baseline.rs:144` (`Some(driver.interest)`). All 39 JSON fixtures and contracts were
scanned for a negative interest value, including the nested `interestExpenseAnnualDollars` series
inside `valuation-model-family.json`: **zero found**.

**UNAFFECTED: the remaining 40** — `None`, non-negative literals, or arithmetic that cannot go
negative (`Some(revenue * 0.01)`; `steady_margin_history`'s two callers pass 899 and 0 bps).

**Pause trigger (e) does not fire.** No affected site sits in a §2.0-untouchable file.
`valuation_baseline.rs`'s eleven sites are ten positive literals plus one fixture-fed value measured
non-negative.

---

## 10. NEW LATENT DEFECT — T2.8 vs an untouchable file

T2.8 replaced `"interest": point.interest_expense_dollars.unwrap_or(0.0)` with
`"interest": point.interest_expense_dollars` in `valuation_fixture_capture.rs`, so an absent reading
is now an explicit `null` instead of a fabricated zero (which is also now *ambiguous*, since zero is
a legitimate value of a signed net series).

**The reader cannot represent it.** `valuation_baseline.rs:72` declares `DriverAnnual.interest: f64`,
not `Option<f64>`, and `valuation_core_measurement.rs:63` loads `core_driver_data_deep.json` through
it. The next re-capture will produce a fixture that fails to deserialize
(`invalid type: null, expected f64`). The fix is one field in `valuation_baseline.rs` — **which §2.0
declares untouchable for every wave**.

Nothing fails today: the capture is `#[ignore]`/network-only and the committed deep fixture still
carries numbers. But note what that means — **the committed deep fixture currently contains
fabricated zeros for absent interest, and cannot be honestly re-captured until that field becomes
`Option<f64>`.** Routed to the orchestrator; not fixed here, and not worked around by reverting T2.8.

---

## 11. What changed

| file | why |
|---|---|
| `apps/windows/src-tauri/src/dcf_model.rs` | **T2.6.** All three `.abs()` sites removed together — FCFF driver audit, `with_operating_drivers` setter, aligned driver bridge. CapEx keeps its `.abs()`; the comment says why the two are different. `FcfPoint.interest_expense_dollars`' doc now declares the sign a measurement. Four sign tests + the end-to-end T2.7 test. |
| `apps/windows/src-tauri/src/driver_resolution.rs` | **T2.7.** Issuer-wide refusal of the accounting cost-of-debt channel when any year is net; the selection-on-the-dependent-variable argument and LD-8 recorded above it. A provably-redundant dead branch removed. Two direct-boundary tests. |
| `apps/windows/src-tauri/src/valuation_fixture_capture.rs` | **T2.8.** Absent interest emits `null`, never a fabricated zero. |
| `apps/windows/src-tauri/src/edgar.rs` | **T2.8.** Three FQN uses replaced by an import. |
| `apps/windows/src-tauri/src/cross_platform_parity.rs` | R-16 hand-off (2): the hazard pin now asserts the corrected sign, with the doc comment required by §8. |
| `apps/windows/src-tauri/src/sec_normalization.rs` | **T2.10.** The frozen corpus test gained an interest branch through `extract_driver_annual` + a `companyfacts_payload` helper. |
| `shared/contracts/sec-driver-normalization-fixtures.json` | **T2.10.** `interestFixtures`: LIN 2020/2024, BAC 2025. |
| `shared/contracts/README.md` | **T2.13.** Both files in `## Files`; R1 and R2 as two rules, each with its example. |
| `_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md` | **T2.13.** The `/9` record: vocabulary, derivation from filings, LD-1 closed, LD-7 opened. |
| `apps/windows/src-tauri/src/valuation_probes.rs` | Probe G, the measurement W2b's Done-when requires. |

`git diff --name-only` (staged by name; never `git add -A`; the high-signal fixture restored and
absent from this list):

```
_bmad-output/implementation-artifacts/spec-sec-driver-normalization.md
apps/windows/src-tauri/src/cross_platform_parity.rs
apps/windows/src-tauri/src/dcf_model.rs
apps/windows/src-tauri/src/driver_resolution.rs
apps/windows/src-tauri/src/edgar.rs
apps/windows/src-tauri/src/sec_normalization.rs
apps/windows/src-tauri/src/valuation_fixture_capture.rs
apps/windows/src-tauri/src/valuation_probes.rs
shared/contracts/README.md
shared/contracts/sec-driver-normalization-fixtures.json
```

### T2.10, on the measured data rather than the illustrative figure

The plan's W2-P01 expected "a year present only under `InterestIncomeExpenseNet`, filed as −256M".
The real filings (fetched this session, real accessions in the fixture) show **2024 is filed under
both** concepts (+256M / −256M), and the net-only years are **2020 (−115M)** and 2021. The fixture
therefore pins LIN **2024** as the both-concepts case and **2020** as the net-only case. Same two
properties, measured values. A third case pins the opposite economics: **BAC 2025
`InterestIncomeExpenseNet = +60,096M` → −60,096M**, so the corpus constrains the negation in both
directions rather than only the one that makes numbers positive.

The interest cases live in a **separate top-level array**: the existing `fixtures` array is iterated
through `normalize_investments` and every entry is asserted `SelectedDevelopment`/`RejectedAcquisition`,
so an interest case placed there would have broken the investment corpus.

---

## 12. Deviations, each stated as a deviation

1. **`docs/valuation-economic-contract.md` was not created.** T2.13 asks it to record LD-1 closed and
   LD-7 opened, but §2.0 assigns that file to **Wave 4 / R4** and it does not exist yet. Both records
   are written into the spec doc W2b owns, which states explicitly that they must be carried into the
   canonical register when Wave 4 writes it. Not a silent omission; a refusal to take another wave's
   file.
2. **`valuation_probes.rs` is not in W2's column of §2.0** (W1/R1 and W5/R3 own it). Probe G is the
   only way to produce the live per-issuer table W2b's Done-when requires, and no W2-owned file is a
   plausible home for a network probe. W1's round has merged and W5's has not started, so there is no
   concurrent owner. Flagged rather than assumed acceptable.
3. **T2.8's acceptance is literally unsatisfiable as written.** *"`grep -n "crate::dcf_model::" src/edgar.rs`
   returns nothing"* cannot hold, because the `use` declaration must name that path. Read as "import
   and use the bare name": three FQN *uses* removed, one `use` added; 4 insertions / 3 deletions.
   Not contorted around.
4. **Plan line numbers have drifted.** `edgar.rs` FQN sites are `:1374/:1440/:1471` (plan said
   `:987/:1083`); the `.abs()` sites were `:551/:907/:1590` as stated; J10's sweep returns 59 lines,
   not 45, with `cross_platform_parity.rs` at 19, not 9 (W2a added ten).
5. **The generator's `-OutputRoot` mode cannot write into an empty directory** — it does not create
   parent directories, so a scratch-root run fails on the Kotlin path. I pre-created the two
   directories. A latent usability defect in the generator, reported, not fixed (W2a's file).
6. **Self-inflicted and repaired:** two Python file rewrites during mutation testing wrote CRLF into
   `sec_driver_normalization_policy_generated.rs`. Under `core.autocrlf=input` this is **invisible to
   `git diff`** but broke the generator's byte-comparison `-Check` ("generated SEC policy is stale").
   Restored with `git checkout --`; verified byte-identical to a fresh generator run. Recorded because
   the failure mode is silent and someone will hit it again.
7. **The canonical J6 check briefly fired on my own tests** (§5), fixed by removing the `.abs()` from
   the test constants.

---

## 13. Quality statement

No known quality smell was left "for later". Everything I found is above, as blocking or as a routed
hand-off: the T2.7 cascade (§1, blocking), the T2.8/`valuation_baseline.rs` coupling (§10, routed),
the `valuation_probes.rs:493` NOPAT sign (§8, ruled), the pre-existing `cargo fmt` and clippy state
(§7, not mine and not touched), the Kotlin AMZN contract failure (§7, not mine), and the generator's
`-OutputRoot` defect (§12.5, routed).

Self-audit found and closed one real hole: the first three sign tests never reached the FCFF driver
audit site, so an `.abs()` restored there alone would have left the suite green. `M2` now fails alone.

**W2b does not merge, and neither does W2a (R-10.3).** Both wait on a ruling for §1.
