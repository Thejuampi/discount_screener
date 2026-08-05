# Orchestrator rulings — carried into plan.v4.md

These are decisions I made as Orchestrator, or that Juan made directly. They are recorded here
the moment they are taken so that v4 is a transcription job rather than a recall job.

Each ruling states **what changes**, **the evidence**, and **what it costs**. A ruling with no
stated cost is a ruling that has not been thought through.

---

## R-1 — Wave 2 splits into W2a and W2b

**Ruled by:** me, on Sensei r4's recommendation.

**W2a** — the contract, the generator, the generated policy, and the Rust extraction sign
convention, **with the three `.abs()` sites still standing**.
**W2b** — LD-1 (removing the `.abs()` sites), the T2.7 guard ruling, and J10's classification.

**Why the split, and why in this order.** With `.abs()` standing, W2a cannot move a published
number on either platform: extraction negates, `.abs()` un-negates, the composition is the
identity. That inertness is the point — it makes W2a **reviewable against a fixed output**. Any
number that moves during W2a is, by construction, a mistake, and needs no judgement to classify.
W2b then moves numbers deliberately, against a base that has already been proven not to have
moved them by accident.

> **Corrected.** My first statement of this rationale said the split "keeps
> `cross_platform_parity.rs` a working instrument." **That is false, and it is the same error
> class this plan exists to catch — I asserted a test's function without reading it.** See R-6.
> The split stands; the reason above is the real one.

**Cost:** two waves where the plan had one; W2a ships a change that is deliberately inert, which
a reader may mistake for a change that does nothing worth making.

---

## R-2 — *(RETRACTED)* Kotlin is in W2b's scope

**Retracted by Juan, mid-flight, verbatim:**

> "android esta fuera de scope ahora. el scope es windows. cuando consigamos una solucion buena
> en windows, hacemos el port a android."

I had ruled that Kotlin was in W2b's scope on the grounds that shipping the Rust sign convention
without the Kotlin bridge fix creates a new divergence. **That reasoning was correct and the
ruling is still withdrawn** — Juan has weighed the divergence against scope and chosen scope.
Superseded by R-3. Recording the retraction rather than deleting the ruling, because the argument
it rested on is the same argument that R-3 has to answer.

---

## R-3 — Android is out of scope. Windows only.

**Ruled by:** Juan, directly. The port happens once the Windows solution is good.

This is not a matter of judgement for me. What *is* mine is working out what "Android out of
scope" mechanically permits, because the generator emits both platforms from one contract, and
naively cutting Kotlin would re-create the exact generator/output drift that §1 of this plan
exists to repair.

### R-3.1 — The contract and the generator do NOT narrow to Rust

`sec-driver-normalization.json` still gains `qnameSigns`; the fingerprint still moves `/8` → `/9`;
the generator still emits **both** generated targets.

Emitting to Rust only would mean deliberately desynchronizing the generator from its own contract
— manufacturing, on purpose, the precise defect §1 was written to fix. Scope reduction is not a
licence to introduce drift.

### R-3.2 — Generated Kotlin gains the field *inertly*. Verified, not assumed.

`SecDriverNormalizationPolicyGenerated.kt` gains `qnameSigns` and nothing on Android reads it.
Three checks, all executed against the working tree:

| check | command | result |
|---|---|---|
| the data class is self-contained | `grep -rn "GeneratedSecDriverOperator" --include=*.kt` | declared at `SecDriverNormalizationPolicyGenerated.kt:4`; **no other file references it** |
| its one consumer reads by *named field*, not positionally | read `SecDriverNormalizationPolicy.kt:72-81` | `DriverOperator(qnames = source.qnames.toList(), unit = source.unit, …)` — an added field is simply not read |
| nothing destructures or `copy()`s it | `grep` for `val (…) = source` / external `GeneratedSecDriverOperator(` | **no matches** — these are the only two constructs an added field could break |

So the generated Kotlin carries the sign data, compiles, and changes no Android behaviour. The
eventual port consumes a field that is already sitting there, already correct, already
fingerprinted. That is a strictly better handoff than porting the contract change too.

### R-3.3 — What is CUT

| cut | was | why it is the behavioural half |
|---|---|---|
| **T2.5** | hand-written `SecDriverNormalizationPolicy.kt` gains `qnameSigns`; `SecEdgarTimeseriesProvider.kt` negates in `annualFyRecordsAny`; a Kotlin unit test asserts it | this is where Android's *numbers* would change |
| **T2.11** | fix `KotlinList`'s empty-collection defect (stray leading comma inside `listOf(`) | latent, Kotlin-output-only; Windows is unaffected by it. Deferred to the port, recorded below |
| **W2-P04, W2-P05** | Kotlin policy / Kotlin provider negative-path examples | they exist to prove T2.5; T2.5 is cut |
| **the Gradle run** in the wave's verification | `cargo test --lib` **and** the Android unit tests | no Kotlin test is written, so there is nothing new for Gradle to run |

**W2-P04's intent survives without a Kotlin test.** It asserted the *generated* interest-expense
operator carries negative signs at exactly the two net-concept indices. The generator's `-Check`
mode — PowerShell, Windows-runnable, and wired into `validate-contracts.ps1` by §1 — already
proves committed output equals generated output for **both** targets. The generated Kotlin's
correctness is therefore locked by a gate that runs on Windows, with no Gradle and no Kotlin test.

### R-3.4 — The cost, stated plainly: W2b introduces a real divergence

This is the price of R-3 and it must not be buried.

- **W2a is inert on both platforms.** Rust negates then `.abs()`; Kotlin neither negates nor
  changes. Same numbers everywhere. No divergence.
- **W2b removes Rust's `.abs()`.** From that commit: Rust negates and keeps the sign; Kotlin
  (`DcfAnalysisEngine.kt:535-541`, `:802`) still `abs()`-es. **The platforms now disagree on the
  sign of net interest**, and they disagree *because we chose to ship one and not the other*.

And the instrument that exists to catch exactly this **cannot see it**:
`baseline_driver_data_2026-07-30.json` contains zero negative interest values
(`grep -c '"interest": *-'` → `0`), so `cross_platform_parity.rs` compares on inputs that cannot
discriminate. A parity suite that passes because its inputs are degenerate reports an agreement it
never checked.

Juan has authorized the divergence — *"cuando consigamos una solucion buena en windows, hacemos el
port a android"* is an explicit sequencing decision, not an oversight. My obligation is therefore
not to prevent it but to **make it impossible to miss at port time**. How to do that is the open
question put to the Advisor (items i/ii/iii) and is the one thing in this ruling still unresolved.

### R-3.5 — What one argument this ruling *voids*, and I must not quietly keep using

My strongest stated reason for treating LD-1 as urgent was cross-platform: *"shipping T2.5 without
the bridge fix is strictly worse than not shipping."* With Android deferred, **that argument is
dead** and I may not recycle it. LD-1 now has to stand on its Windows-only merits — that
`.abs()` on a net-interest concept fits a net series as though it were gross and understates the
cost of debt — or not stand at all. Flagged to the Advisor explicitly, with an invitation to tell
me the correction is weaker than I assessed.

---

## R-4 — Per-wave delta baselines *(Juan delegated: "lo dejo a tu criterio")*

Each wave measures its own baseline at wave start; exit criteria compare against **that**, never
against a number written in the plan. The protected failing set is **named, never counted**:

- `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`
- `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`
- `valuation_high_signal::high_signal_screener_cohort_all_members_pass`

**Why.** The session-start baseline drifted 518 → 520 from two `#[test]`s added in uncommitted work
during this session. A plan that hardcodes a count is a plan that fails for reasons unrelated to
its own change. Counts are facts about a moment; names are facts about the code.

---

## R-6 — The cross-platform parity suite is not an instrument. It is an exporter.

**Found by:** me, while checking my own R-1 rationale. Nobody asked for this and no reviewer named it.

`cross_platform_parity.rs:1-4` says it outright — *"Cross-platform valuation parity export (Windows
side). Writes fixed-input DCF results … so Android can be compared cent-for-cent."* Both of its
`#[test]`s are exporters, and **neither asserts a single value**:

| site | what it asserts |
|---|---|
| `:242-319` `export_qa_cohort_parity_snapshot_for_android` | `assert!(path.is_file())` — that a file was written |
| `:503-557` `export_random20_sp500_parity_snapshot` | `assert_eq!(file.members.len(), 20)` — the input had 20 rows |

It writes to `.agents/workspace/tmp/parity-windows-qa.json` (`:123-133`) — **untracked**; `git ls-files`
returns no parity export. The real comparison lives in
`scripts/compare-windows-android-valuation-parity.ps1`, against an Android-side export from
`CrossPlatformParityExportTest.kt`.

**And nothing invokes the comparator.** `grep` across every `.yml`/`.yaml`/`.ps1`/`.md`/`.toml`
finds exactly two references outside the script itself: its own self-test
(`test-valuation-parity-comparator.ps1:2`) and two spec documents describing it as an expectation.
Not `validate-contracts.ps1`, not CI.

So the divergence W2b introduces would go unnoticed for **three independent reasons**, each
sufficient on its own:

1. the Windows suite has no expected values — it produces the reference rather than checking it;
2. the comparator that *would* check it is wired to nothing;
3. and even fully wired, `baseline_driver_data_2026-07-30.json` has zero negative-interest rows
   (`grep -c '"interest": *-'` → `0`), so it would compare on inputs that cannot discriminate.

**This makes Juan's scope call cheaper than I told him it was.** I framed deferring Android as
accepting a real divergence against a working parity gate. There is no working parity gate. The
cost of R-3 is lower than my own R-3.4 stated, and I have corrected R-3.4 and R-1 accordingly.

**Adopted remedy** (the Advisor's, and I agree it beats inventing a mechanism): add **one synthetic
negative-interest row** to the Windows-side fixture so the export already contains the
discriminating case, and register the divergence in the D7 latent-defect register in LD-6's shape —
mechanical trigger and detector, not a comment:

> *trigger:* the Android port begins.
> *detector:* `DcfAnalysisEngine.kt:537` / `:802` still apply an unconditional `abs()` to interest,
> **or** the parity fixture still contains zero negative-interest rows. Both are one-line greps.

No new "refuse on degenerate fixture" test pattern. §6.3 already establishes this repo's answer for
an instrument that cannot see what it is supposed to check — *label the blind spot wherever the
result could be cited* — and inventing unreviewed methodology mid-wave is the scope growth this
plan is supposed to be suspicious of.

---

## R-5 — *(RESOLVED)* The four named issuers: fix versus refusal

`InterestIncomeExpenseNet` — the brief's work order says *"Fix … including COF, DAL, CHTR and
BKR"*, and none of the four is fixed by the plan as written:

- **COF** — net interest *income*, filed positive → negated → negative → T2.7's guard fires → and
  because `edgar.rs:1099-1100` passes `None, None`, `driver_resolution.rs:236-239` returns `Err`
  and **the entire FCFF valuation is refused**.
- **DAL / CHTR / BKR** — net interest *expense*, filed negative → negated → positive → the guard
  never fires → a net series is fitted as though gross → cost of debt understated, with
  plausible-looking numbers (LD-8).

Juan routed it: **"Consultalo con el @Advisor."** The Advisor's answer overturned the premise of my
own escalation, and I verified every load-bearing claim myself before adopting it.

### R-5.0 — The COF half of my escalation was false. Corrected.

I told Juan that refusing COF's interest channel takes down its whole FCFF valuation and darkens
the issuer that has its own e2e gate. **It does not.** COF is classified `FinancialServices` and is
valued by a residual-income *equity* model that the FCFF interest question cannot reach.

| claim | verified by me | result |
|---|---|---|
| COF dispatches away from FCFF | `dcf_model.rs:1263-1265` | `BusinessClass::FinancialServices => residual_income(fundamentals, market_price_cents, market_params, source)` — **`fcf_history` is not even a parameter** |
| `derive_wacc` is unreachable from that path | `grep` all callers | exactly two: `fcff_wacc` (enclosing `:2350`, reached only by the `OperatingNonFinancial` arm) and `resolve_attribution_wacc` |
| what the guard firing for COF *actually* touches | sole caller `valuation_gap_attribution.rs:1722` | a diagnostic the file itself disclaims — *"is **not** a Shapley factor"* (`:32`, `:146`), *"Diagnostic capture only… Do not calibrate to minimize diagnostic_gap_vs_street_*"* (`:1325`) |
| the `Err` arm is graceful | `valuation_gap_attribution.rs:1742-1744` | `naive.source_note = Some(format!("fcff_level_ok_wacc_fail:{e}"))` and continues — nothing propagates |
| COF's real published number | `apps/windows/e2e/native/cof-detail.native.e2e.mjs:83,88` | asserts `dcf_analysis?.model == "residual_income_equity"` and `valuation_unavailable_reason == null` |

**The true cost of refusing COF is one `source_note` string in a self-disclaiming diagnostic.** Not
a valuation, not the e2e gate. I escalated a four-way choice to Juan on a premise I had not traced
to the dispatch — the same failure class as J7's Kotlin blindness, twice in one session.

### R-5.1 — The ruling

**COF → accept the refusal.** Decision 1 applies, and applies *cheaply*. There is no coverage
lost to weigh against it.

**DAL / CHTR / BKR → ship the sign fix; register the residual honestly.** These three do run the
live `fcff_wacc` → `derive_wacc` path, so this is their published valuation, not a diagnostic.
Net interest expense and total debt are both real filed measured quantities for all three; the
limitation is that *net* is not *gross* — a bounded, nameable imprecision. That is categorically
different from FR-29's `r := w`, which invented a value for an **absent** quantity. Refusing three
issuers for which real evidence exists would discard measured coverage, which is not what Decision 1
asks: it says coverage is not a *promotion gate*, not that supported evidence should be thrown away.

This project has already lived the alternative. The credit-curve episode
(`prd-discount_screener-2026-08-03/.memlog.md:26-29`) went 15-of-20 dark, and was resolved by
**fixing five real evidence bugs and keeping real evidence** — once by falling back to the newest
*complete* filed year — never by fabricating a rung and never by accepting the cascade.

**Binding condition on the above, adopted from the Advisor and not negotiable:** LD-8 must carry a
**trigger and a detector**, or explicitly state that no detector exists — the same standard every
other D7 entry meets. As it stands, LD-8 says the numbers "look plausible" and nothing more.
*Plausible-looking with no way to catch it* is functionally the FR-29 problem this entire brief
exists to remove. Bare-plausibility LD-8 does **not** survive Juan's closing instruction; LD-8 with
a detector-or-honest-admission does.

**(c) build the missing rung → rejected on evidence, not on preference.** I verified there is no
data source. `market_yield_bps` and `rated_or_synthetic_spread_bps` appear at 12 sites across
`dcf_model.rs` and `driver_resolution.rs` — declared, defaulted to `None` (`:889-890`), settable
(`:940-941`), consumed (`driver_resolution.rs:95`, `:106`) — and **set to a real value by nothing,
anywhere**. A search for any bond-yield, credit-rating, or synthetic-spread provider returns only
those same field names. Feeding a rung means either a new live market-data integration (new I/O, out
of scope) or synthesizing one from an assumption — *selecting an estimator*, forbidden outright.

**(d) measure per issuer → folded in as verification, not as a competing branch.** The per-issuer
before/after report must confirm two things specifically:
1. **COF's published-value delta is exactly $0.00** — turning R-5.0's structural argument into a
   measured one;
2. **DAL/CHTR/BKR's largest published-value swing**, as a percentage of current published value.

That second number is the one thing that could still reopen this ruling. If all three swings are
small and none flips an anchor-adjacent gate, LD-8-with-detector is uncontroversial. If one swings
hard, **that name** — not the ruling — gets the refusal treatment instead. It is a measurement the
build produces, so it does not need to go back to Juan as a question now.

### R-5.2 — What this ruling does *not* rest on

Per R-3.5, the cross-platform argument is void and I have not used it. LD-1 stands on its
Windows-only merits, which the brief already measured (`brief.md:30-32`): `InterestIncomeExpenseNet`
resolves as an expense for COF (19 yrs), DAL (15), CHTR (12), BKR (11), and for a cash-rich issuer
filing net interest *income*, `pretax + interest` double-adds income `pretax` already contains.
Real, live, Windows-side, and independent of Android's status.

---

## R-7 — T2.0's measurement overturns the pre-registration. MPWR joins the set; trigger (c) gains a published-effect condition.

**Status:** RESOLVED. Ruling made on the builder's escalation, which was correctly refused as a
builder decision.

### R-7.0 — What T2.0 measured, verified independently

Three reproducible runs, SEC `companyconcept` to the dollar, control BAC `+60,096M` for 2025
matching `brief.md` exactly — so the zeroes are a result, not a dead instrument. I verified MPWR's
cohort membership (`valuation_high_signal.rs:669`), the full 12-year series, and the report's
separation of counterfactual from published delta before ruling.

| trace | issuers | filed sign | guard | published delta |
|---|---|---|---|---|
| A — files positive, guard fires | **COF** (2024, 2025) | `+31,208M`, `+42,878M` | FIRES | **$0.00** — `FinancialServices` → `residual_income` (`dcf_model.rs:1263-1265`); `fcf_history` is not passed, so the FCFF bridge is unreachable |
| B — files negative every net year | **DAL** (9), **CHTR** (12), **BKR** (11) | negative | never | **$0.00** — bit-identical; every `dFCFF` and margin shift exactly 0 |
| **C — files positive, guard never fires, published number moves** | **MPWR** (12 consecutive years, 2014-2025) | `+1.1M` … `+29.2M` | **never** — no filed debt in any year, so the net years never reach the cost-of-debt fit | **−77 to −224 bps of revenue, every net year**; −169 bps / −$47.3M in 2025 |

### R-7.1 — The pre-registration measured the wrong quantity. Corrected, not weakened.

§6.2 pre-registered **{COF, DAL, CHTR, BKR}** on the basis of *how many years each files
`InterestIncomeExpenseNet`* (19 / 15 / 12 / 11). That is **extraction incidence**. The quantity the
pre-registration exists to protect is **published effect**, and the two sets are disjoint:

- the four registered names move **$0.00 published between them**;
- the one name that moves — **MPWR** — was named nowhere in any of six plan revisions, and is
  **inside the 26-name high-signal gate cohort**.

**Ruling.** The plan carries two clearly separated rows: an *extraction-incidence* set (the original
four, retained — it is true and it is what LD-8 keys on) and a **pre-registered published-effect set
= {MPWR}**, carrying its measured direction and magnitude band. Registering MPWR *after* seeing the
result is legitimate here and only here: T2.0 is the task the plan itself ordered to produce this
number, its exit condition was "enumerated by name or declared empty with evidence," and the
registration happens **before Wave 2b runs**. That is pre-registration working, not being bypassed.

### R-7.2 — Trigger (c) gains a disjunctive published-effect condition. The builder was right.

Restated (c) keys entirely on **refusal of the cost-of-debt channel** — i.e. on the guard firing.
MPWR proves a published number can move while the guard never fires. As written, **(c) is blind to
the only issuer the change actually affects**, and its own third row anticipated this case but could
not fire on it.

The new condition needs no invented threshold, because the prediction for every name except MPWR is
exactly zero:

> **Any issuer outside the pre-registered published-effect set with a non-zero published delta is a
> stop** — regardless of whether its guard fires.

And symmetrically, so the registered name cannot absorb an arbitrary result:

> **MPWR itself is a stop if its measured shift falls outside −77 to −224 bps**, or if the sign is
> positive. The band is the measurement; a result outside it means the implementation did something
> other than what was measured.

### R-7.3 — The direction is correct, and the plan must say so *before* the number moves

A −224 bps FCFF-margin drop on a gate-cohort member reads as a regression. It is not.

`OCF` **already contains interest received.** The FCFF bridge adds back `interest × (1 − t)`. For a
net-interest-**income** filer, `.abs()` presents that income as an expense and the bridge adds it
back — **double-counting income the OCF already carried**. MPWR's published FCFF has been
**overstated** for twelve years. Removing the `.abs()` sites and negating the two net concepts makes
the bridge subtract, and FCFF falls to its correct level.

Recorded in advance so that no later reader — and no Stage 5 reviewer — mistakes the correction for
damage, and so that nobody proposes recovering the 224 bps.

### R-7.4 — Is this a pause? Not yet, and the deciding measurement is ordered.

Juan's trigger (b) is *"an anchor moving >±5% or changing side of a gate."* MPWR is **not an
anchor** (anchors are PG, GOOGL, AMZN, MSFT — it cannot fire on that clause). But it **is** a member
of the high-signal gate cohort, so the *"changing side of a gate"* clause applies in full.

Whether it crosses is **measurable and not yet measured**, so I have ordered it on the T2.0 builder
rather than guessing from magnitude. Also ordered: the ~25s wider scan, because
*"Wave 2b moves exactly one published number"* is presently a statement about **29 names, not about
the model** — the net-cash/debt-free archetype (semis, software) is common and unmeasured outside
the cohort. The plan may not state the narrow claim as a general one.

**If the gate check returns "crosses side," trigger (b) fires and Wave 2b stops for Juan.** That is
a decision with data, taken at the point the data exists — not a stop at a seam.

### R-7.5 — What I will not do, stated in advance

- **No threshold moves to keep MPWR passing.** If MPWR leaves the gate cohort because its FCFF was
  overstated for twelve years, that is a **true finding about the cohort**, and the cohort is wrong,
  not the correction.
- **No ticker special-case.** No `if symbol == "MPWR"`, no carve-out, no exemption row.
- **No recovery of the lost 224 bps** by any other channel.
- **The overstatement is not re-described as a "conservatism adjustment."**

### R-7.6 — Process note, recorded against myself

The pre-registered set survived **six plan revisions, two full review rounds, and two reviewer
threads** without anyone — including me — noticing it registered extraction incidence while claiming
to register published effect. Reviewers checked that the set was *stated*; nobody checked that the
stated quantity was the one the control protects. The measurement caught it. **This is the third
time this session that a claim about what an instrument measures survived review because it was
plausible rather than checked** (J7's Kotlin blindness, R-1's parity-suite rationale, now this).
Belongs in the retro as a pattern, not as three incidents.

---

## R-8 — Wave 3 accepted. Four rulings, one of them against the builder's stated reasoning.

Wave 3 (`abc1254dc8b9c83fd`) delivered: `RobustCentre`/`robust_centre`, both adapter call sites
(pooled growth centre `:334`, trailing growth centre **and variance** `:635`), `GrowthKey`-based pair
exclusion, two diagnostics counters, `docs/valuation-aggregation-audit.md`. 26 new tests, each
observed **red before green** across three mutation rounds. Four paths staged explicitly, nothing
committed. `MAX_ABSOLUTE_Z` still `3.0`; `valuation-core`'s dependency list still empty.

### R-8.1 — W3-E03 is impossible as specified. The stricter implementation is ACCEPTED.

The plan asked for a five-observation sample where **two** survive trimming. It cannot exist, and I
derived this independently rather than taking the builder's word:

`standardize` computes `scale = median(|xᵢ − median|) × MAD_TO_DEVIATION` (≈1.4826) and **refuses
outright when `scale` is not `> 0`** (`numerics.rs:149`). So on any sample that does not refuse,
with deviations `d₁ ≤ … ≤ d₅`, the keep-threshold is `3.0 × 1.4826 × d₃ ≈ 4.45·d₃`, and
`d₁, d₂, d₃ ≤ d₃ < 4.45·d₃` — **three observations always survive.** The zero-dispersion edge does
not produce "two survive" either; it refuses.

This also **contradicted the plan's own K5**, which already called a retained count of two
unreachable — so the plan disagreed with itself and no reviewer caught it.

The builder implemented the reachable form **plus**
`no_five_observation_sample_can_be_trimmed_below_three`, asserting the impossibility over three
adversarial samples. That is **stricter than what the plan asked for**, not looser, and it converts
a spec error into an enforced invariant. Accepted as delivered. **Correctly refused to adapt
silently** — this is exactly the escalation shape the plan asks for.

### R-8.2 — The pinned cohort is **20 names, not 28**, and it is disjoint from Wave 2's issuers.

Verified: `core_driver_data_deep.json` → `rows` has exactly 20 keys —
`AAPL ADMA AMSC AMZN APP BWMN CALX FIGS HURN IDCC INOD INVA MH MIR MSFT ROCK T VICR VRRM VRT`.
The plan asserted 28 in two places. Both corrected in v6.

**The consequence is larger than the count.** This cohort contains **no MPWR** and none of
COF/DAL/CHTR/BKR. So Wave 3's *"refusal-rate change is zero, every published p50 identical to the
cent"* is evidence about **this** population and says **nothing** about the issuers Wave 2 moves.
Two different cohorts are in play and must never be conflated in a report: the **26-name high-signal
gate** cohort (holds MPWR, COF) and this **20-name pinned measurement** cohort. Recorded in v6 at
the point of use, not just in the changelog.

### R-8.3 — The phantom fourth failure is **mine**, and it has been propagated.

`cross_platform_parity::export_random20_sp500_parity_snapshot` fails in every worktree because it
reads **untracked** `.agents/workspace/tmp/random20-inputs.json`. I verified the file exists in the
main repo (19,502 bytes, Jul 30) and is absent from worktrees **by construction** — an artifact of
the worktree isolation **the orchestrator owns**, not a defect in any wave.

R-4 amended in v6: builders baseline **four**, and what R-4 protects is that the set does not
*change state*, never that it equals three. **Nobody creates the file, adds a fixture, or relaxes
the test.** Sent to both running builders (Wave 1, W2a) — W2a especially, since T2.12 has it editing
the parity fixture and it would otherwise have burned time diagnosing my mess.

The builder found this by reporting four where the plan said three, instead of reporting the three
the plan expected. That is the behaviour the protected-set rule exists to produce.

### R-8.4 — The masked-pin finding is elevated; it generalises beyond Wave 3.

In combined mutation round B, `a_clean_cohort_discards_nothing` **passed under mutation** — the
whole-fit-refusal mutation returns `None` before `growth_pooled_discarded` is ever written, so the
counter stayed 0 and the assertion held vacuously. Round B2 isolated the threshold mutation and the
pin failed properly.

**The general lesson, which belongs to the whole run and not to Wave 3:** a combined mutation that
turns *everything* red is precisely the evidence shape that **hides a test which never fired**. Red
is not proof a specific assertion is load-bearing; only *isolated* mutation is. Every wave claiming
"observed red before green" must say whether the mutations were combined or isolated.

The builder also re-derived its full mutation record from scratch rather than trusting its own
earlier notes, which carried a 7-vs-8 ambiguity — and **the discrepancy was the masked pin**. The
sloppy count was the symptom of the real defect.

### R-8.5 — Clippy at `numerics.rs:149`: leave it, but the stated reason is wrong.

The builder left the lint and justified it as: taking clippy's suggested `scale <= 0.0` would
"flip NaN from *refuse* to *proceed*." **Checked, and that mechanism does not hold.** The line is

```rust
if !(scale > 0.0) || !scale.is_finite() {
```

The second clause catches NaN regardless of how the first is written, so the combined condition
refuses NaN either way. Moreover NaN is not reachable here at all: `:140` already rejects any
non-finite input, and a median of finite deviations scaled by a finite constant is finite or ±∞,
never NaN. (`!scale.is_finite()` is *not* dead code — it catches **overflow to ∞** on extreme
inputs.) The lint is also `neg_cmp_op_on_partial_ord`, which asks for `partial_cmp`; it does not
assert that `scale <= 0.0` is equivalent.

**Outcome unchanged — leave the line as written**, on the honest ground: `!(x > 0.0)` stays correct
*independently* of the `is_finite` clause, so the refusal survives a future refactor of the guard
above it. Defense in depth on a refusal path, not NaN-flipping. Both lints are **pre-existing and
out of Wave 3's scope** either way.

**Recorded because the shape matters more than the line.** This is the fourth instance this session
of a claim about what a check does being asserted rather than verified — and this time it came from
a builder, arriving as a reason to leave a lint on a **refusal path**. The conclusion happened to be
right. The reasoning was not, and a reason that does not hold cannot be allowed to sit in the record
as if it does.

---

## R-9 — The baseline is resolved. v5's "MEASURED 520" was the error; the inherited 518 was correct.

**Status:** RESOLVED by measurement. Raised by Wave 3's builder, decided by the orchestrator on the
shared checkout, cause confirmed by Juan.

### R-9.1 — Three trees, three correct numbers, one canonical

| tree | reads | why |
|---|---|---|
| **committed `4d1e916` — canonical** | **518 / 3 / 22, 543 tests** | what every wave is actually built from |
| shared checkout | 520 / 3 / 22, **545** | **+2 `#[test]` from Juan's own uncommitted work** in `engine.rs` — unrelated to valuation |
| builder worktree | 517 / **4** / 22, 543 | committed tree minus the parity test needing untracked `random20-inputs.json` (R-8.3) |

**Evidence.** `cargo test --lib -- --list` → **545** in the shared checkout. `git diff -- '*.rs'`
adds exactly **+2 `#[test]`, 0 removed**, both in `engine.rs`:
`upside_and_discount_use_distinct_denominators`, `nem_like_row_shows_upside_near_thirty_one_not_twenty_four`.
The builder's independent per-commit count gives 543 at `4d1e916`, `3d01d5a`, `e4e152e`, `131e72b` —
**545 never occurred on this branch.** Two routes, same answer.

Juan confirmed the working-tree changes are his (2026-08-04: *"son cambios que hice yo, tranqui,
dejalos como estan y segui con el e2e workflow"*). **Not touched, not staged, not counted.** They
are the reason constraint 7 exists.

### R-9.2 — The plan corrected a right number into a wrong one, then built a blocker on it

v5 recorded **"MEASURED 2026-08-04: 520 passing"** and treated the inherited **518** as a
discrepancy needing explanation — §4.2 was written up as a Stage 4 blocker on that basis. **The
direction was backwards.** 518 was correct; the "measurement" that overrode it was taken on a tree
carrying two unrelated tests, and the word *MEASURED* gave a contaminated reading authority over a
clean inherited one.

**The lesson is not "measure more."** It is that **a measurement carries its tree with it**, and a
number quoted without the tree it was taken on is not yet evidence. §4 now records all three rows
and names which is canonical.

### R-9.3 — The control is the delta, not the absolute

Restated in §4 for every remaining wave: **baseline against your own tree at your own start, quote
which row you are in, diff against that.** Wave 3's `517 → 529` with an identical failing set is a
clean pass under this rule and always was — it only looked anomalous against a number measured on a
different tree.

### R-9.4 — Both of the builder's honesty flags are upheld

1. It reported its start triple as **recorded, not captured verbatim**, and offered to re-measure
   rather than let a reconstructed string read as a captured one. Correct call — and it did not
   revert its work to manufacture the capture, which I had forbidden.
2. It refused to choose between its two candidate causes from inside isolation, naming the
   experiment that separates them instead of guessing. **That experiment is what I ran**, and it
   returned the branch the builder flagged as *"the more serious finding"* — two tests genuinely not
   in the committed tree. Serious in mechanism, benign in cause.

This is the second wave in a row where the builder's *refusal to resolve* something outside its
scope was worth more than a confident answer would have been. Compare R-8.1 (W3-E03) — the same
shape, and the same correct instinct.

---

## R-10 — The wider scan overturns R-7.1. The set is 24 issuers, not one. Trigger (b) does not fire.

**Status:** RESOLVED. Both follow-up measurements returned. Report:
`build/t2.0-followup-wide-scan-and-gate.md` (+ `-raw.txt`).

### R-10.1 — `{MPWR}` was a cohort-local artifact. The archetype is common.

I registered `{MPWR}` in R-7.1 and **flagged it as complete only within 29 names, pending the
scan**. The scan returned and the flag was justified:

**496 issuers scanned** (universe 501; 5 have no CIK). **25 symbols / 24 distinct issuers — 5.0% —
are `OperatingNonFinancial` with ≥1 net-interest year filed positive:**

> ABBV ADSK AXON CARR COR CPRT DDOG JKHY **MPWR** NKE NWS NWSA OTIS PAYX RMD ROL ROST TPR TTD TYL
> ULTA WSM XYZ YUM ZBRA  *(NWS/NWSA share a CIK)*

Max |Δmargin|, bps of revenue: `n=25, min=0, median=53, max=271`. **Ten of twenty-five move ≥100
bps, and MPWR is not the largest — CPRT moves further.**

Breadth beyond the movers: **107/496 (21.6%)** file a net-basis concept at all; **48 (9.7%)** file
it positive; **22 of those 48 are `FinancialServices`**, carrying enormous counterfactual `dFCFF`
(COF $78.5bn, C $87.2bn) that reaches **$0.00 published** — the COF result generalised, and further
confirmation that the `residual_income` dispatch is doing real work.

**Both `{MPWR}` and the 25-name set are true, of different populations.** The intersection of the 25
with the 26-name gate cohort is exactly `{MPWR}`. **The gate observes one name while the product
moves twenty-four.** Quoting the gate figure as the blast radius would be the R-8.2 error committed
a second time, and §6.2 now forbids it explicitly. **No anchor is in the affected set.**

The builder was asked to say plainly if MPWR turned out to be one of many, and did. That is the
answer that was wanted, not the tidy one.

### R-10.2 — MPWR does not cross its gate. Trigger (b) does NOT fire. Wave 2b proceeds.

Measured live, not inferred from magnitude. **MPWR fails its gate today** on four counts
(`unavailable_or_non_positive_base`, `point_estimate_unreliable`, `provisional_rates`,
`street_disagreement_exceeds_high_signal_band`). Decisively, **its published base is not the FCFF
number at all**: the router returns `Disputed` and resolves to `ForwardEarningsPower` — published
`91657c` forward vs `54630c` FCFF candidate, gap 5062 bps against a 5000 threshold. The
counterfactual is **identical on every field. Verdict changes side: NO.**

And it *cannot* cross in this direction, by arithmetic: while `Disputed`, three of the four failures
are set unconditionally (`valuation_high_signal.rs:509-530`); `difference_bps` is strictly
decreasing in the FCFF candidate for `c < f` and this correction is **uniformly downward**, so the
gap only widens; and even with FCFF selected, the street band needs a base ≥ `118920c` against an
FCFF of `54630c`.

**So the pause I was holding open in R-7.4 does not materialise.** Recorded as a measured negative,
not as an absence of evidence.

### R-10.3 — W2a and W2b are two review units and ONE merge unit. Binding.

The same run **measured W2a's inertness** instead of assuming it: MPWR's twelve years were rewritten
at the field level and the after-tax interest add-back did not move — **`Some(85)` → `Some(85)` bps**.
Writing the field bypasses the setter's `.abs()` at `dcf_model.rs:907`, but **both read sites
re-apply it**, `:551` and `:1590`. Extraction negates, the reads un-negate, composition is identity.

That is the split working as designed — **and it is the hazard.** W2a alone ships a contract, a
bumped `/9` fingerprint, and generated policy in both languages **declaring a convention the code
does not honour**: the exact generator/output drift the contract pipeline exists to prevent,
installed deliberately.

**Ruling: W2a may be reviewed separately but must not be merged without W2b.** If W2b stops for any
reason, W2a waits with it. **Its inertness is what makes it unsafe to ship alone, not what makes it
safe** — a builder or reviewer arguing the reverse has inverted the argument. Sent to the running
W2a builder with instructions that its report must not imply independent landability.

### R-10.4 — What was deliberately not measured, and why that was right

The full three-site counterfactual needs the two `.abs()` read sites deleted — a **production
edit**. The builder did not make it, and said so rather than approximating. Unmeasured: the FCFF
intrinsic value under the corrected series, and whether the forward candidate shifts through its
`return_on_capital_bps` / `own_growth_bps` coupling. **Neither can overturn R-10.2** — the
street-band argument is sufficient alone.

It also declined to run `high_signal_screener_cohort_all_members_pass`, because it calls
`write_observation_audit`, which would **overwrite the pinned fixture the counterfactual is compared
against** — and that fixture is under Juan's constraint 8. Correct call, and it named the reason
instead of silently skipping.

---

## R-11 — Wave 1 accepted. T1.7 run by the orchestrator; both judgement calls resolved by measurement.

Wave 1 (`a7ce432bfde5d3294`) delivered `IsoDate`, `AnnualObservation`, `AnnualProvenance`,
`AnnualSeries`, `extract_driver_vintages`; real provenance at every production construction site;
fail-closed on `end`/`filed`/`accn`; 17 new tests under **18 mutations, none surviving**; T1.7's
probe; `docs/sec-point-in-time-provenance.md`. Three paths staged, nothing committed.
Baseline **517/4/22 → 534/4/23**, failing set identical by name — and it measured **four**
independently, before my R-8.3 note arrived, without creating or relaxing the parity input.

### R-11.1 — T1.7 executed. Fail-closed extraction costs this sample nothing.

The plan assigns this run to the orchestrator; I ran it. 17 issuers — four anchors, the Wave 2
issuers **including MPWR** (my amendment landed before the probe was written, so nothing was
redone), and an oldest-filing-history slice.

| | total |
|---|---|
| accepted 10-K facts | **8504** |
| no `filed` / bad `end` / no `accn` — all refusals | **0 / 0 / 0** |
| **disagreeing period-ends** | **305** |

**Zero in every refusal column, for every issuer.** Recorded in the doc as a measurement on 17
issuers, **not** a proof for the universe — a sparser filer would still lose years, which is exactly
what the probe exists to observe.

**Column 3 is the one that matters, and it is 305, not zero.** Live SEC data files the same
`(concept, period_end)` more than once **at different values** (CHTR alone excepted). So `as_of` and
`latest` genuinely disagree on real data: Wave 1 built a mechanism that live evidence can
distinguish from `latest`, rather than one that is merely unused pending item 6. A zero here would
have been the more interesting — and worse — result.

### R-11.2 — Judgement call 1, the accession fail-close: ACCEPTED as implemented.

D1 taken literally emptied every series, because no inline fixture carried an `accn`. The builder
implemented the **binding** fail-close and added `"accn"` to six fixtures, changing **no numeric
assertion, threshold or expected value** — verified: net assertions **+17**, and the one test that
looked like a loss (`resolve_capex_carries_outside_span`) is fully intact, same four assertions,
same `20_000_000_000` / `16_000_000_000`. The apparent deletions were a struct-return refactor.

**Adding a now-required provenance field to fixtures is not weakening a check** — the check is
strictly stronger, and the fail-close retains its negative test. The live cost, which was the only
real risk, is **measured at zero** by R-11.1. Accepted. The builder's instruction stands: if the
rule is ever judged too strict, change `AnnualObservation::from_fact` and its negative test —
**never the fixtures**.

### R-11.3 — Judgement call 2, two new dead-code warnings: ACCEPTED, do not silence.

`as_of` never used and `FcfAnnual::provenance` never read are **true statements**: this wave's PIT
API has no production consumer until item 6. `#[allow(dead_code)]` would convert a true warning into
a hidden one, and the warning is the honest record that the API is built but unwired. Net warning
count unchanged at 41 — four orphaned wrappers deleted, two of which were already dead at `4d1e916`.

### R-11.4 — W1-N02 is the same defect as Wave 3's masked pin. That is now five.

**A test that did not test what it claimed.** Mutation M3 (an `fy`-style fallback for an unparseable
`end`) **survived**, because the duration period-shape check had already rejected the entry — the
assertion could never fire. Rewritten against an *instant* driver, where the fail-close is the only
gate; M3 then turns it red.

Counting the run: J7's blind grep; the parity suite's phantom assertions; §6.2 registering the wrong
quantity; Wave 3's vacuously-passing pin; and now W1-N02. **Five instances of the same shape** — a
check that exists, is named, points at the right area, and cannot fail. Three were caught by
reviewers or by me, **two were caught by builders auditing their own work**, and none were caught by
the check itself.

The v6 rule added at R-8.4 — *red must be **isolated***, and every "observed failing" claim must say
which — is what generalises across all five. It is the run's most portable finding and belongs at
the top of the retro, above any individual defect.

---

## R-12 — W2a's worktree was stale. Orchestrator defect, second of the session. Re-dispatched.

**What happened.** W2a's builder found its worktree 28 commits behind at `32b5c96` — fingerprint
`sec-driver-normalization/5` against the plan's stated pre-W2a `/8`, and **missing entire source
files** (`valuation_high_signal.rs`, `valuation_probes.rs`, `valuation_gap_attribution.rs`,
`valuation_fixture_capture.rs`). T2.1/2.2/2.3/2.4/2.9/2.12 are unimplementable on that tree.

**It made zero repository edits and stopped.** Correct, and it explicitly declined to reset the
worktree itself on the grounds that isolation is the orchestrator's responsibility, not a builder's
to improvise. Right on both counts.

**Verified.** `git worktree list` shows the three sibling worktrees all at `4d1e916` and W2a's
**absent entirely** — it was auto-removed on completion because the builder changed nothing. Note
`G:/dev/repos/discount_screener-wt-main-compare` sits at exactly `32b5c96`, the reported commit,
which makes a stale-ref creation or a path resolving into that pre-existing unrelated worktree the
likely mechanism.

**Cost.** ~1.6M subagent tokens and 27 minutes to discover broken ground. The design work for all
six tasks, the T2.12 file-ownership analysis, and the report were **lost with the worktree**. The
gathered MPWR SEC data survived only because it was written to the scratchpad, outside the worktree
— `mpwr_interest.json`, `mpwr_ocf.json`, `mpwr_capex.json`, `mpwr_cash.json`, `mpwr_pretax.json`,
`mpwr_chart.json`. That is luck, not design.

**This is the second isolation defect I have caused this session**, after R-8.3's phantom fourth
failure. Both share a cause: **I treated the worktree as a neutral copy of the repo and never
verified it.** Same shape as the run's other five defects — an instrument assumed to do what its
name implies.

### R-12.1 — Corrections applied

1. **Re-dispatched with a STEP 0 ground check** the builder must run before anything else: HEAD is
   `4d1e916`, `valuation_high_signal.rs` and `valuation_probes.rs` present, fingerprint reads `/8`.
   Any failure is an immediate stop. Cheap, and it converts a 27-minute discovery into seconds.
2. **Told it to read `plan.v6.md` from the main checkout path**, since the entire `.agents/` tree is
   untracked and therefore absent from every worktree — a fact I had not stated to any builder, and
   which every prior builder worked around silently.
3. **Reports must be written inside the worktree**; the orchestrator copies them out. Already the
   practice, now stated in the brief.
4. **Handed over the surviving scratchpad data** so the re-dispatch does not re-fetch SEC facts.

### R-12.2 — The open question the lost report raised, ruled

The previous attempt flagged a conflict between §2.0's untouchable list and T2.12's literal text.
Ruling: **protected are `valuation_baseline.rs` (§2.0) and Juan's
`high_signal_screener_observation_2026-08-02.json` (constraint 8). `cross_platform_parity.rs` is
NOT protected, and neither is `baseline_driver_data_2026-07-30.json`** — T2.12 may edit the fixture.

**But a real hazard survives, and the re-dispatch is told to re-derive it rather than take my word:**
`cross_platform_parity.rs` holds **9 `.abs()` sites** and its fixtures are built through the abs'd
setter, so a planted *negative* interest row may be un-negated by the export path and be
unobservable until W2b lands. If so, **T2.12 cannot be satisfied inside W2a and must move into
W2b** — a finding, not a defect, and the builder is instructed to report it rather than invent a
workaround. Same standing as W3-E03 (R-8.1).

### R-12.3 — It happened again. The guard worked; harness isolation is now abandoned for this wave.

The re-dispatch's **STEP 0 caught the identical failure**: HEAD `32b5c96`, `valuation_high_signal.rs`
and `valuation_probes.rs` missing. **31 seconds and 17K tokens, against 27 minutes and ~1.6M for the
same discovery without the guard.** That ratio is the entire argument for cheap pre-flight checks,
and it is worth carrying into future runs as a rule rather than a one-off.

The builder again made zero edits and — correctly — declined to write its report inside the bad
worktree, on the grounds that a report produced against wrong ground should not be trusted as an
artifact. That is a better instinct than the instruction it was given.

**Diagnosis.** Every worktree the harness provisioned *early* in this run sits at `4d1e916`; every
one provisioned *later* lands at `32b5c96` — which is exactly the HEAD of the pre-existing,
unrelated `discount_screener-wt-main-compare` worktree. The branch tip is `4d1e916` and has not
moved. I cannot reach the harness's provisioning logic, so I stopped trying to make it behave and
took the responsibility the plan already assigns me.

**Fix applied — the orchestrator provisions the worktree by hand:**
```
git worktree add -b w2a-manual G:/dev/repos/discount_screener-wt-w2a 4d1e916
```
Verified before dispatch: HEAD `4d1e916`, both files present, fingerprint `/8`. W2a re-dispatched
**without** harness isolation, pointed at that path, with an explicit prohibition on touching the
shared checkout — which carries Juan's uncommitted work and would be a far worse failure than a
stale worktree.

**Recorded for the retro.** Three dispatches were spent on this: one that discovered it expensively,
one that discovered it cheaply because of the guard the first one paid for, and one that should
work. The cost was mine, not the builders'. **Both builders behaved correctly at every step** —
stopping, refusing to improvise isolation, and refusing to emit artifacts from a tree they did not
trust. The failure was entirely in my assumption that a provisioned worktree is what it claims to
be, which is the same assumption behind R-8.3 and behind all five of the run's dead-check defects.

---

## R-13 — The published-value counterfactual: the pre-registration is complete, and it registers six names

**Ordered by:** R-7.5 and R-10.4, which both closed with the same open item — the pre-registration
stated magnitude at *driver* level (Δmargin bps across 25 names) and said nothing about **published
intrinsic value**. W2b could not land against a number registered before it until that was measured.

**Method.** LD-1's three `.abs()` sites deleted in an isolated worktree, the negation applied in the
probe's arithmetic, both histories valued through the production router, and the two published
numbers compared per issuer. The worktree is marked **`T2.0/R-10 COUNTERFACTUAL — MUST NOT MERGE`**;
its diff is retained at `build/r10-counterfactual.diff` so W2b's output can be compared against what
the deletion actually produced rather than against a description of it.

### R-13.1 — The registered set is six, not twenty-five. Third revision, third measurement.

> `{ROST, MPWR, JKHY, ULTA, CPRT, NKE}` — all downward.
> `n=6, min −157 bps, median −60 bps, max −18 bps`. Sorted: `−157 −101 −82 −39 −22 −18`.
> Cents: ROST −279, MPWR −357, JKHY −135, ULTA −124, CPRT −23, NKE −12.

The 25-symbol set registered **whose interest series changes**. The control exists to protect
**whose published number changes**. Those are two different sentences, and step 3 of the
three-step check — *prove they select the same set* — returns **no**: nineteen of the twenty-five
are true negatives.

This is the same defect as R-8.2 and R-7.1, caught for the third time, and it is worth stating that
plainly: **`{MPWR}` → 25 → 6, and not one of those revisions came from an argument.** Each came from
a measurement that overturned the previous registration. The instinct to reason from the mechanism
to the affected set has now been wrong three times in a row on this one wave.

### R-13.2 — Nineteen move $0.00, and that is a *finding*, not a null result

The tree check confirms all 25 have rewritten years, so the correction arrives. It is then absorbed
by **robust normalization over the series** and by **the router publishing the forward lane**. WSM
is the clean demonstration: its interest add-back flips outright, `28 → −28` bps, and its published
value does not move a cent.

That is Wave 3's robust aggregation doing precisely what it was built to do, observed on live data
rather than on a fixture. It is also the strongest available evidence that the two waves compose:
Wave 3 is what makes Wave 2's correction safe for nineteen issuers.

### R-13.3 — Zero router-selection flips. Registered as a qualitative stop.

A lane flip outranks any magnitude — it changes which model publishes, not by how much. **None
occurred, anywhere**, MPWR included (`disp:fwd` on both sides). The nearest approach moves *away*
from a flip: MPWR's FCFF candidate drops `54630 → 51585`, **widening** its 5062-vs-5000 bps gap.

Trigger (c.2) now stops the wave on **any** flip. There is no threshold to argue about; the
prediction is exactly zero.

### R-13.4 — Anchors move $0.00 at `yrs=0`. Trigger (b) does not fire, structurally.

`PG 18109→18109`, `GOOGL 35679→35679`, `AMZN 16185→16185`, `MSFT 57139→57139` — and `yrs=0` means
**not one fiscal year differs** between the two histories. This is not a small delta rounding to
zero; the input is identical. Any anchor movement in W2b therefore means W2b did something the
deletion did not, and that is a stop rather than a tolerance.

### R-13.5 — The refusal row is registered *empty*

No corrected FCFF goes negative or non-positive. CARR, DDOG, NWS, NWSA and TTD have no FCFF
candidate on *either* side — pre-existing absence, not a refusal this change causes.

Registering the empty row is the point: a single new refusal in W2b is then a stop, where an
unstated refusal expectation would have absorbed it as "the correction found a bad issuer."

### R-13.6 — The builder found its own instrument defect mid-measurement

A `BLOCKED` label in the harness would have reported *"the counterfactual isn't working"* when it
was working and being absorbed — **inverting the conclusion of the entire measurement**. Seventh
instance of the "check that cannot fail" pattern this session; third caught by a builder auditing
its own work.

The builder's own framing is the right one to carry into Stage 5, verbatim:

> *"Predicting that all 25 move would be falsified by the wave just as surely as predicting that
> none do."*

> *"19 of 25 move $0.00, and not because the change failed to arrive. It arrives and is absorbed."*

**Both halves matter.** A pre-registration that cannot be falsified in either direction is not a
pre-registration. This one can.

### Consequences

1. §6.2 carries a **three-row** affected-set table — driver-level (25), published effect (6), gate
   cohort (`{MPWR}`) — with the R-8.2 conflation warning attached.
2. Trigger **(c.2)** re-keyed to the six names, with four additional stops: any upward move, any
   move beyond −157 bps, any router flip, any anchor movement, any new non-positive FCFF.
3. **R-7.5 and R-10.4's open item is closed.** W2b is no longer gated on an unmeasured premise.
4. **W2b may be dispatched once W2a returns** — the merge-unit constraint from R-10.3 is unchanged:
   W2a and W2b are two review units and **one** merge unit.

---

## R-14 — Round 1 is merged, and W2a was dispatched against the wrong base

**My defect, caught eleven minutes before it became a silent one.**

I dispatched W2a against `4d1e916`. The plan's Wave 2 header says **"Dependencies on other waves: Round 1 must be merged"**, and §6.1 states why in terms that name the exact failure:

> "Wave 2 must apply the sign inside the very function Wave 1 restructures — `extract_annual_any_with_shape`, which Wave 1 turns into a vintage-aware resolution path. If Wave 2 wrote the sign into the old shape, the merge would either silently drop the sign or silently drop the vintage retention, and *both* are invisible in a green build."

T2.4 — *the Rust extraction sign* — is W2a's, and it is that edit. I read the wave header, the split table and the Done-when, and did not read the one row that says which ground the wave stands on.

**How close it came.** The builder's status on hold: *"I had drafted a T2.4 design against this shape: a new `extract_annual_equivalents(facts, qnames, qname_signs, shape, unit)` function replacing the merge logic in `extract_annual_any_with_shape`, but nothing was written to disk."* The design was formed. Nothing was written. Had the hold arrived one tool round later it would have been a rebase-with-a-committed-design, which is the situation where a builder reconciles rather than re-derives.

**Why I caught it at all, and the honest version of that.** Not by review — I had already passed the row twice. I caught it while checking whether Wave 5 could be dispatched in parallel, which sent me to §6.1 for Wave 5's dependency row, where Wave 2's was three paragraphs above. **The check that found this was looking for something else.** That is luck with a useful shape: reading the dependency section as a whole rather than the one row I wanted.

### The merge

Wave 1 and Wave 3 committed in their own worktrees and octopus-merged onto a new `round1-integration` branch at `G:/dev/repos/discount_screener-wt-round1`:

```
3bd20f2  merge: Round 1 -- Wave 1 (point-in-time vintages) + Wave 3 (robust aggregation)
f5fdac2  feat(edgar): retain filed vintages so a driver can be read as of a date
515728c  feat(valuation-core): one robust aggregation primitive, and a fit that honours it
```

**File-disjoint, verified before merging rather than assumed** — Wave 1 touches `edgar.rs`, `valuation_probes.rs`, `docs/sec-point-in-time-provenance.md`; Wave 3 touches `valuation_core_adapter.rs`, `valuation-core/{lib,numerics}.rs`, `docs/valuation-aggregation-audit.md`. Zero overlap, no conflict, no manual resolution. §6.1's independence argument holds mechanically as well as semantically.

**Measured on the merged base**, `cargo test --lib`:

| | worktree at `4d1e916` | merged Round 1 |
|---|---|---|
| passed | 517 | **546** |
| failed | 4 | **4** |
| ignored | 22 | **23** |

`+29 = +17` (Wave 1) `+12` (Wave 3), exactly each wave's own additions, and **the failing set is the same four names**. Neither wave repaired one by accident, neither added one, and the arithmetic reconciles without a residual — which is the strongest available evidence that the two waves did not interact.

Note this passes §4's new rule-3 clause on its own terms: the count rose, every point of the rise is attributable to a file a wave owned, and none of it is "Juan working."

### Consequences

1. **W2a re-pointed at the same path**, `G:/dev/repos/discount_screener-wt-w2a`, reset in place onto `3bd20f2` (the worktree was clean). Same path means the builder keeps its bearings; new ground means T2.4 is re-derived.
2. **The builder is instructed to discard its draft design and re-derive**, with two specific questions §6.1 implies but does not spell out: whether the sign travels with the fact *through* resolution or is applied to a post-merge scalar — a gap-filled year keeps the **filling** concept's provenance, so the latter silently mis-signs it — and whether a sign change can flip a per-concept material-restatement verdict.
3. **Wave 5 is NOT promoted into Round 2.** I checked this and the answer is no. §6.1 permits promotion only *"if Q1 blocks Wave 2 indefinitely"*; Q1 is answered and Wave 2 is running. The stated cost of parallelizing stands: *"Running Wave 5 concurrently with Wave 2 would put a coverage collapse and a sign correction into one report."* Idling one wave is cheaper than an unattributable report.
4. **The three counterfactual/stale worktrees stay unmerged.** `worktree-agent-a19c1b1e4036e2768` carries the `.abs()` deletions and is marked `T2.0/R-10 COUNTERFACTUAL — MUST NOT MERGE`; it also collides with Wave 1 on `valuation_probes.rs`, which is a second, independent reason.

### The pattern, stated once more because it is now eight

Seven of this run's defects were *"the check measures something other than what it protects."* This one is *"the dependency was written down, in the right place, in binding language, and I acted without reading it."* Different failure, same remedy: **the row that says what a thing stands on is not optional reading, and a builder is the wrong place to discover it.**

---

## R-15 — Correction: R-12.3's worktree diagnosis was wrong, and the real cause is deterministic

R-12.3 recorded: *"Every worktree the harness provisioned **early** in this run sits at `4d1e916`;
every one provisioned **later** lands at `32b5c96`."* That is a timing story, it implies a race, and
**it is not what happened.** Juan asked what caused it; measuring it overturned my own account.

### The measurement

`git reflog show HEAD` in every worktree. The oldest entry is the creation commit.

| worktree | created | at | how it reached `4d1e916` |
|---|---|---|---|
| `agent-a7ce432bfde5d3294` (Wave 1) | 15:15:45 | **`32b5c96`** | builder `reset --hard 4d1e916` at 15:17:01 |
| `agent-abc1254dc8b9c83fd` (Wave 3) | 15:16:01 | **`32b5c96`** | reset to `valuation/wave1-integration` at 15:17:47 |
| `agent-a19c1b1e4036e2768` (counterfactual) | 15:16:23 | **`32b5c96`** | reset to `valuation/wave1-integration` at 15:18:11 |
| `discount_screener-wt-w2a` (orchestrator, manual) | 16:23:27 | **`4d1e916`** | created correctly — explicit commit argument |

**`32b5c96` is `main`.** `4d1e916` is `valuation/wave1-integration`, the branch the session has been
on the whole time.

### What this actually means

**Every harness-provisioned worktree was created from the repo's default branch, not from the
session's current branch.** All three, without exception. It is **deterministic, not flaky** — there
was never an early cohort that came out right.

The three that "were fine" were fine because **their builders fixed them**, and Wave 1's report said
so in plain words in its section 0, which I read and did not connect:

> *"The worktree was created at commit `32b5c96`. The target branch `valuation/wave1-integration` is
> at `4d1e916` … it was moved with `git reset --hard 4d1e916` before any work started."*

**I credited the harness for work the builders did**, and then built a timing theory on top of the
gap that credit left. I also named `discount_screener-wt-main-compare` as the likely source because
its HEAD is `32b5c96`; that is a coincidence — it is a *main*-compare worktree, so of course it sits
on main.

### Consequences

1. **The remedy is much stronger than a retry.** A deterministic default-branch base does not need
   luck or timing to avoid — it needs one argument. `git worktree add -b <name> <path> <commit>` got
   it right on the first attempt and every attempt.
2. **Harness `isolation: "worktree"` is not to be used in this repo while work sits on a non-default
   branch.** The orchestrator provisions manually and passes the path with isolation off.
3. **STEP 0 stays, and asserts the exact commit** — not "the files are present", which a `main`
   checkout can also satisfy for most files.
4. **Nothing here excuses R-14.** That was a base error of a different kind: the plan named the
   dependency and I did not read it. A correct worktree at the wrong *ordered* base is still wrong.

### The pattern, ninth instance

R-12.3 is an unmeasured premise that survived because it was specific, plausible, and pointed at a
real phenomenon. It went into a rulings file as a diagnosis. **Nine defects this run, and the two
most recent are both mine describing something I had not measured** — which is exactly what
`feedback-verify-what-an-instrument-measures` says, applied to my own prose rather than to a test.

---

## R-16 — W2a accepted. Its inertness is measured, and three of its findings outrank its deliverables.

Report: `build/w2a-report.md` (337 lines). Nine files staged by name, no `git add -A`, shared
checkout read-only and verified untouched.

### R-16.1 — Done-when item 3 was NOT delivered by the builder, and that is my dispatch defect

W2a's exit requires *"a live per-issuer table, measured, not argued … every delta exactly zero."*
The builder did not produce it, correctly citing the ~10 s fast-check budget it was dispatched
under. **A dispatch that imposes a budget which makes a mandatory exit criterion impossible has
moved the criterion, not met it** — and R-4 puts the paired run on me regardless. I ran it.

Paired run, both trees back to back, retrieval timestamp **2026-08-04T21:22:04Z**:

| | round1 base `3bd20f2` | W2a |
|---|---|---|
| passed | 546 | **550** |
| failed | 4 | **4** (same four names) |
| ignored | 23 | 23 |

The `+4` is exactly W2a's four new tests, by name — `concept_vintages_applies_each_concept_its_own_sign`,
`concept_vintages_panics_when_signs_and_concepts_disagree_in_length`,
`generated_qname_signs_reconstruct_from_contract_negated_qnames`,
`mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers`. No residual.

**The live per-issuer table: 26 issuers, ZERO delta.** Every published `base` bit-identical between
the two trees. This is the measurement W2a's split exists to produce, and it is now a measurement
rather than the invariance *argument* Juan's Q1 ruling killed. It also independently reconfirms
T2.0's finding at a different layer: twelve years of interest are re-signed at extraction and not
one published number moves, because the three `.abs()` sites still stand.

Independently verified by me, not taken from the report: fingerprint `/9` at **5 of 5** sites; J6
**FALSE** (all three `dcf_model.rs` sites present at `:551`, `:907`, `:1590`);
`INTEREST_EXPENSE.qname_signs = [1,1,1,1,1,1,-1,-1,1]`.

### R-16.2 — Three findings the wave was not asked for, all accepted

1. **The generator was not a `cargo fmt` fixed point** for the new numeric arrays. `RustSlice`'s
   single-line-vs-multi-line rule was count-based (`count <= 2`) and only ever matched rustfmt
   because every pre-existing array is too wide to fit one line anyway. `qname_signs` fits, so the
   generator would have emitted output `cargo fmt --check` rejects — a contract-pipeline defect that
   was latent until the first short array existed. Replaced with a rendered-width check. **This is
   the second generator-drift defect this effort has found by adding one field.**
2. **`valuation_probes.rs:486` is a fourth, previously-unnamed live consumer of the interest sign**
   — `nopat: (pretax + interest) * (1.0 - marginal_tax)` with no `.abs()` of its own. Inert today
   *only because* `dcf_model.rs:907` guarantees non-negativity upstream. **It becomes live the moment
   W2b removes the three `.abs()` sites, and section 0's blast-radius table does not name it.** This is
   assigned to W2b's blast radius, blocking.
3. **Section 0's citations of `valuation_probes.rs:344` / `:354` are stale.** Verified directly: those lines
   are now `triple.2.map_or(…)` and a bare `);` inside an unrelated analyst-consensus probe. The
   file's real sites are `:476` (presence-only, sign-agnostic) and `:486` (finding 2).

The true reconciliation is **not** the carry item's guess of "five sites and one file", arrived at by
subtracting `15 − 10`: **8 of section 0's 10 map correctly, 2 are stale, and 7 sweep sites were never named
at all.** Carry item 15 is **discharged**, and it is worth recording that its own arithmetic was
wrong — a subtraction is not an audit.

### R-16.3 — T2.12: the planted fixture does not discriminate, and the builder said so

Ordered to rule the row rather than note it. It ruled *against its own deliverable*, which is the
harder direction:

- The cohort exporter cannot discriminate the convention — MPWR is not in
  `baseline_cohort_2026-07-30.json`, **and** that exporter reads `row.interest` straight from JSON
  into `with_operating_drivers` with no sign processing, so it never touches `edgar.rs` at all.
- **`compute_mpwr()`'s own `compute()`-level outcome is *also* sign-symmetric today.** `.map(f64::abs)`
  at `:907` erases `−29,151,000` vs `+29,151,000` before `driver_resolution.rs`'s zero-debt guard
  runs; the row refuses identically either way (`"provider inconsistency, positive interest with
  zero debt"`).

**That is instance number ten of the run's governing pattern, and the first one caught by a builder
ruling on a fixture it had just written.** It planted the row, ran it, read the JSON output, and
reported that the artifact does not measure what it was commissioned to measure. The discriminating
pin is instead `mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers`, which
needs no `compute()` boundary and fails the moment W2b lands — a hazard pin that is *designed* to go
red on the next wave.

MPWR's FY2024 tax anomaly (filed `−1,213,788,000`, restated `−1,019,146,000`, both a benefit larger
than pretax income) is disclosed and left as `None` rather than guessed. **Correct.** Absence never
becomes a fabricated number.

### R-16.4 — T2.14 was assigned to no wave. Implemented in W2a. Accepted.

The split table gives W2a `{T2.1, T2.2, T2.3, T2.4, T2.9, T2.12}` and W2b `{T2.6, T2.7, T2.8, T2.10,
T2.11}`. **T2.14 is in neither** — a plan defect, not a builder overreach. It belongs with W2a
because it guards precisely the contract-to-generated drift that W2a creates, and it is what made
the `RustSlice` defect visible as a real regenerate-and-verify cycle. Its isolated-mutation proof
satisfies v6's fourth verification clause: one flipped sign and one transposition, each failing
alone with the exact arrays printed, each restored and re-confirmed green.

### R-16.5 — J6's "exactly three" wording is stale; the plan is corrected, not the check

The canonical check returns **four** matches on the merged tree. The fourth,
`driver_resolution.rs:82`, is `resolve_rate_inputs`'s zero-debt guard — sign-agnostic by
construction, last changed at `0507dfe` (2026-08-03), and `git diff --cached` on that file is empty.
**Pre-existing, verified independently.** The builder reported the discrepancy instead of quietly
reconciling it, which is the right instinct and the opposite of moving a check to make a wave pass.
J6's wording is corrected to *"the three `dcf_model.rs` sites"*; the threshold does not move.

### R-16.6 — One stale number in the report, corrected

The report cites `baseline-shell.txt: 518 passed; 3 failed; 22 ignored` as the measurement of
record. That is the **shared checkout's** old baseline, not this wave's base. The correct base is
`3bd20f2` at **546/4/23** and the correct delta is the one in R-16.1. No conclusion changes; the
citation does.

### Disposition

**W2a ACCEPTED as a review unit. It does not merge.** R-10.3 stands: W2a and W2b are two review
units and one merge unit, and W2a's measured inertness — 26 issuers, zero delta — is exactly what
makes it unsafe to ship alone. A `/9` fingerprint and a sign convention in two generated languages,
with code that un-negates all of it, is the generator-drift shape the pipeline exists to prevent.

Carried into W2b's dispatch as blocking:

1. `valuation_probes.rs:486` joins W2b's blast-radius table. It is a **fourth** `.abs()`-dependent
   consumer and it is not in section 0.
2. `mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers` **will go red** when
   W2b lands. That is by design. W2b updates it to assert the corrected sign — this is the one case
   where changing a test is not weakening it, and W2b must say so explicitly in its report.
3. The paired live table above is W2b's "before". Its "after" is the six-issuer prediction of R-13.

Minor, recorded not blocking: the sweep now returns **16 sites across 7 files**, because W2a's own
hazard-pin test added `cross_platform_parity.rs:564`. The report's "exactly 15 across 6" was true
when run and stale when delivered. Trivially ruled — it is the wave's own assertion.

---

## R-17 — W2b built clean and STOPPED. My pre-registration measured a different intervention than the wave implements.

Report: `build/w2b-report.md` (530 lines). Nothing committed, nothing merged, ten files staged by
name. Baseline 550/4/23 matched mine exactly; exit **557/4/24**, failing set unchanged by name.
J6 is **green** — all three `dcf_model.rs` sites gone, verified by me.

### R-17.1 — The defect is mine, and it is instance eleven

**R-13 was measured on the T2.0/R-10 counterfactual worktree, which does not touch
`driver_resolution.rs`.** Verified directly: that worktree's diff is two files (`dcf_model.rs`,
`valuation_probes.rs`), and `driver_resolution.rs:118` still carries the legacy silent year-drop
`… || interest < 0.0 { return None; }`.

So the counterfactual measured **T2.6 alone, with the year-drop intact**: remove `.abs()`, let
negative years be silently dropped, fit on the survivors, publish. **W2b is T2.6 + T2.7**, where
T2.7 replaces that silent drop with an **issuer-wide channel refusal**. Two different interventions.

I specified that counterfactual correctly as an LD-1 measurement and then labelled it *"THE
pre-registration"* for a wave that is LD-1 **plus** the guard ruling. **The registration was not
falsified by the wave — it was never a prediction of this wave.** That distinction is the whole
finding, and collapsing it into "the model was wrong" would be the more comfortable and less true
reading.

This is the same defect as R-7.1 — a control registering one quantity while claiming another —
committed one level up, by me, in the artifact whose entire purpose was to prevent it.

**The builder's attribution proof is the part worth keeping.** MPWR is the only issuer whose
cost-of-debt column reads `n/a` in **both** arms — no filed debt, so the accounting channel was
never used and T2.7's refusal cannot reach it. **It is also the only issuer whose measured move
equals its registered move to the cent (−357c).** The single name the second intervention cannot
touch is the single name that reproduces the one-intervention prediction. Every other registered
mover reads `Nbps → REFUSED`.

### R-17.2 — What actually fired

| Stop condition | Fired | Measured |
|---|:-:|---|
| an anchor moves at all | **no** | PG 18109, GOOGL 35679, AMZN 16185, MSFT 57139 — all $0.00 |
| a router-lane flip | **YES** | **9**, all `sel:fcff → sel:fwd` — CPRT JKHY NKE OTIS PAYX RMD TYL YUM ZBRA |
| a move upward | **YES** | 8 up, to **+4988 bps** (RMD +8315c, TYL +6340c, ZBRA +1354c, COR +1273c) |
| beyond −157 bps | **YES** | `n=18, min −2897, median −24, max +4988` |
| new non-positive FCFF | no | 0 |
| a fifth failing test | no | failing set identical by name |

**24 of 25 affected issuers go from a fitted rate to a refusal**, their FCFF candidate goes absent,
and that is what flips nine lanes. Twelve movers were never registered at all.

**Anchors held at $0.00.** Trigger (a) and (a′) did not fire. That is the one thing that did survive
the mis-registration, and it survived it under a much more aggressive intervention than the one
registered.

### R-17.3 — The economic question, which is Juan's and not mine

The refusals are triggered by **single very old years the fit would never have used**: YUM by
**2007**, COR by 2008, TYL by 2009, ABBV by 2011. A 2007 net-interest year takes an issuer's whole
FCFF lane dark in 2026.

The plan names this outcome as the one this repo already rejected — the credit-curve episode
(plan `:1467`, `:3685`), **15-of-20 dark, resolved by fixing five real evidence bugs and keeping
real evidence, never by accepting the cascade.** This is that cascade at **24 of 25**.

The builder **did not** narrow the guard, and its reason is correct: choosing the narrower rule
after seeing the numbers is choosing a rule to hit a target, and narrowing the economic contract to
keep the Core publishing is forbidden outright. It stopped instead. That is the behaviour the
protocol asks for and it should be read as the wave succeeding at its hardest moment, not failing.

**Three readings, and the third is not on the builder's list because the builder correctly refused
to invent one:**

- **(A) as implemented.** Any negative year anywhere in the filed series refuses the channel
  issuer-wide. The literal ruling — *"a net-negative year proves the series is net"* is a property
  of the series, not of a window. Produces the cascade.
- **(B) narrower window.** Refuse only when a negative year falls inside the otherwise-fittable set
  (`debt > 0`). Spares the pre-2012 blackouts. **Chosen after seeing the numbers**, which is exactly
  the objection.
- **(C) key on the basis, not the sign.** The builder's own code comment names this and names why it
  didn't do it: *"This keys on the sign, which is an approximation of the rule that matters. A
  net-expense filer's series is equally net … Keying on the basis of the series rather than the sign
  of its value needs per-field concept provenance on `FcfPoint`, which does not exist yet."*

**My recommendation is (C).** Three reasons, in order of weight:

1. **It is what T2.7's ruling already says.** The ruling's logic is *the series is net, therefore
   gross interest is not measurable from it.* The sign is **evidence of** the basis, not the thing
   itself. Wave 1 now retains, per fact, which concept won. Using a proxy when the measured quantity
   is available is strictly worse, and it is the failure mode this run has hit ten times.
2. **It is the only option that fixes both directions.** DAL, CHTR and BKR file net **expense** —
   equally net, sign positive, and therefore fitted as gross under (A) *and* under (B). LD-8 stays
   open under both. Only (C) reaches them.
3. **It is not chosen to hit a target.** (B)'s objection is that its boundary was picked after
   seeing which issuers it spares. (C)'s boundary is picked by what the evidence measures, and it
   would have been the right rule before any number was seen.

**Its honest cost:** `FcfPoint` carries no provenance — the PIT boundary
`docs/sec-point-in-time-provenance.md` already names. (C) means carrying the winning concept (or a
single `net_basis` flag) from `edgar.rs` into `FcfPoint`, which is real work in the run's riskiest
wave, and it needs **its own pre-registration, measured before it lands** — this time on a tree that
carries both interventions.

**This is Juan's call under (a) and (c) of the working protocol, and I am not making it.**

### R-17.4 — The project's damage detector is blind to this, and this run proves it

The 26-name high-signal cohort moved **one** number — MPWR — and stayed `9/26 → 9/26` with an
identical failing set and identical reason codes, while eighteen published values moved by up to
`+4988 bps` and nine lanes flipped. **Only 1 of the 25 affected issuers is in that cohort.**

A gate reading `9/26 → 9/26` is not evidence that published valuations did not move. This is the
gate/product asymmetry of R-10.4 demonstrated on live data, and it is a finding about the project's
instrumentation that outlives this wave.

### R-17.5 — Accepted alongside the stop

- **All three `.abs()` sites removed together**, plus a **fourth** test after the builder's own audit
  found the FCFF-audit site at `:551` was reachable by none of the first three. Self-caught.
- **T2.7 has both** the direct `resolve_rate_inputs` boundary tests and the end-to-end blackout test,
  as ordered — the branch had never executed anywhere before this wave.
- **Seven isolated mutations**, each with named failures; two produce exactly one failure. v6's
  fourth verification clause satisfied.
- The **MPWR hazard pin** updated to assert the corrected sign, with a doc comment stating that the
  old assertion pinned the LD-1 defect itself. This is the one sanctioned case, and it is labelled.
- The probe's before-arm re-abs (`valuation_probes.rs:467`) is `#[cfg(test)]` and named
  `history_as_published_before_the_sign_correction`. Verified: it is not a production `.abs()`.

### R-17.6 — Routed, not silently absorbed

`valuation_fixture_capture.rs` now emits `null` for absent interest, but `valuation_baseline.rs:72`
declares `DriverAnnual.interest: f64` — **the next re-capture will fail to deserialize**, and §2.0
declares that file untouchable. Which also means **the committed deep fixture currently holds
fabricated zeros**. That is a pre-existing absence-fabrication defect the wave exposed rather than
created. It needs a ruling; it is not W2b's to fix under §2.0.

Gate 4 (`test:e2e:native:cof`) is **environment-blocked, not code-failing** — the app builds and
launches, WebView2 never exposes a debug target, and the harness's `finally` EPERM masks it.
Reproduced outside the harness. Neither a pass nor a failure; it is a missing measurement.

### Disposition

**W2b's code is accepted as built. W2b is NOT accepted as a wave, and neither W2a nor W2b merges.**
R-10.3 holds. The wave is blocked on one economic ruling from Juan, and on a re-registration
afterwards that covers T2.6 **and** T2.7 together, measured on a tree that carries both.

---

## R-18 — PRE-REGISTRATION of the guard-rule measurement. Written before any number is read, and it predicts against my own recommendation.

Juan ruled: **stop and re-measure**; the guard rule is decided against numbers for every candidate,
not against my argument for one of them. R-17's recommendation of (C) is hereby demoted from a
recommendation to **one registered hypothesis of four**, and this section states what each predicts
and what would falsify it.

### R-18.1 — Four structural facts, each read in code, none assumed

1. **`AnnualProvenance.sources: Vec<SecFact>` retains `qname` per year** (`edgar.rs:194`). The
   winning concept per issuer-year is already available on the merged Round 1 tree. **(C) needs
   plumbing to _ship_; it needs none to _measure_.**
2. **W2a negates the two net concepts at extraction** (`concept_observations`, `value_dollars =
   filed_value * sign`). Therefore, post-W2a, a **negative** interest value no longer means "net
   basis" — it means "net basis **and** interest income exceeded interest expense in that year."
3. **The six gross concepts precede the two net ones**, and `AnnualSeries::merge` fills by
   `.or_insert` over concepts in declared order (`edgar.rs:330-338`). **A net concept can win a year
   only when all six gross concepts are absent for that year.**
4. **The accounting lane is third priority** (market yield → rated spread → accounting) and carries
   **no minimum observation count**: `!accounting_common.is_empty()` admits a single year, then takes
   the median of the annual rates.

### R-18.2 — Fact 2 breaks the premise my own recommendation rested on

I recommended (C) on the reasoning that keying on the basis is strictly better than keying on the
sign because the sign is a lossy proxy. Fact 2 says which direction it is lossy in, and fact 3 says
how much, and **neither favours the conclusion I drew**:

- (A) fires on `interest < 0` post-negation ⟹ it detects **net-basis-AND-income-exceeded-expense**.
  That is a **strict subset** of net-basis issuers.
- (C) fires on net basis ⟹ it refuses that subset **and** every net-**expense** filer besides.

**(C) is therefore WIDER than (A), not narrower. It makes the 24-of-25 cascade bigger.** R-17
presented (C) as the principled correction to an over-wide rule; it is in fact a strictly wider rule
whose extra refusals I described as a benefit (LD-8, "reaches DAL/CHTR/BKR") without noticing that
"reaches" here means "goes dark." That is my error, it is pre-registered as such, and it is exactly
what Juan's ruling was for.

### R-18.3 — (D), stated now and marked post-hoc

Fact 3 admits a fourth rule that none of (A)/(B)/(C) expresses:

> **(D) refuse the YEAR on basis, not the ISSUER.** A year won by a net concept has no measurable
> gross interest expense, so it is not a fittable observation and is dropped. The issuer loses the
> accounting channel only if that empties the fittable set — the rule that already exists — and the
> market-yield and rated-spread lanes are untouched.

This escapes the selection-on-the-dependent-variable objection that motivated T2.7. That objection
was that dropping negative years keeps high-expense years and discards low-expense ones. Dropping on
**basis** does not select on the value at all: the whole year is unmeasured regardless of what
number it holds.

**(D) was constructed by me after seeing that (A) produced a cascade, and is marked post-hoc.** Its
defence is that it follows from fact 3, which is structural and was read from `merge`, not from any
output number — but that defence is a claim, and the measurement is entitled to reject it. Registered
as a candidate, not as a recommendation.

### R-18.4 — Registered predictions. Numbers before measurement.

| # | prediction | falsified by |
|---|---|---|
| **P1** | For every issuer (A) refuses, at least one year's winning qname is in `negatedQnames`. | any (A)-refused issuer whose every year is won by a gross concept |
| **P2** | **FP(A) against basis = 0.** A negative post-negation value cannot arise from a gross concept. | any negative year won by a gross concept — which would mean a filer credits a gross interest line |
| **P3** | **(A) ⊊ (C) strictly.** (C) refuses every issuer (A) does, **plus** at least one more; specifically DAL, CHTR and/or BKR if they file net-expense. | (C)'s refusal set equal to or smaller than (A)'s |
| **P4** | **\|(D)\| ≪ \|(A)\| = 24.** Registered point estimate: **(D) refuses ≤ 6 issuers**, because a net concept wins only gap years and the old blackout triggers are single early years. | (D) refusing ≥ 15 |
| **P5** | Under (D), **YUM, COR, TYL and ABBV are NOT blacklisted** by their 2007/2008/2009/2011 years. | any of the four dark under (D) |
| **P6** | The 9 router-lane flips are a **monotone function of refusal-set size**: \|flips(D)\| ≤ \|flips(A)\| ≤ \|flips(C)\|. | any non-monotone ordering |

### R-18.5 — The decision criterion, fixed now

The rule is scored on **whether it refuses exactly when gross interest expense is not measurable for
the fit**, by two measures:

1. **Misclassification against measured basis** — false positives (refused while gross basis) and
   false negatives (fitted while net basis), per issuer-year and per issuer.
2. **Whether any issuer is refused on the strength of a year the fit would never have used.** The
   accounting fit already requires `debt > 0 && interest > 0` and intersects with `tax_years`. A
   refusal triggered by a year outside that set is a refusal on evidence the model does not consume.

**Explicitly NOT criteria**, and this is binding: how many issuers stay lit; the direction or size of
any published-value move; distance from street; and whether the result is convenient. Anchor movement
stays a **stop condition** — it halts the round, it never scores a rule.

**If P2 holds and P3's extra refusals are all genuine net-expense filers, then (A) and (C) are both
_correct_ classifiers of the basis and differ only in how much of the cohort they take dark. In that
case the choice is not "which rule is true" but "is an unmeasurable gross interest expense grounds
for refusing the accounting lane at all" — and (D) answers that with the year, not the issuer.** I am
registering that framing now so it cannot be reached by working backwards from the counts.

### R-18.6 — Scope of the round

Measurement only. Nothing merges out of it, no guard is edited on a wave branch, and the arms are
throwaway branches that exist to be read and deleted. The re-registration R-17 demanded — T2.6 and
T2.7 together, measured on a tree carrying both — is **satisfied by this round and not before it**.

### R-18.7 — Instance twelve, caught before it fired

My dispatch for the basis probe named `PROBE_COHORT` as the measurement universe. `PROBE_COHORT` is
28 names and contains **none** of the 25 issuers in `INTEREST_SIGN_AFFECTED_COHORT`
(`valuation_probes.rs:324`) — not ABBV, not COR, not TYL, not YUM, none of the cascade.

A basis measurement run over that universe would have produced a clean, internally consistent,
fully-formatted confusion matrix **about a population in which the phenomenon under study does not
occur**, and Table 4's trigger-year audit would have had nothing to audit. It would have looked like
a measurement and decided nothing.

That is the run's governing pattern for the twelfth time, and the second time I have committed it
personally (R-17.1 was the eleventh). The distinction worth keeping is that this one was caught by
reading the cohort const before the probe ran rather than by reading the output afterwards —
**verifying what the instrument measures, not what it reports**, which is the standing rule this run
keeps rediscovering.

Corrected universe dispatched: `INTEREST_SIGN_AFFECTED_COHORT` ∪ `VALUATION_ANCHORS` ∪
`PROBE_COHORT`, deduplicated. The third list is not optional: DAL, CHTR, BKR and COF live only there,
and **P3 is unmeasurable without them**. The correction carries an explicit instruction that a
prediction measured on a universe incapable of falsifying it must be reported `UNMEASURED`, never
`SURVIVED`.

---

## R-19 — The fixture-capture absence defect. W2b fixed one field of three, and the function contradicts itself in the same breath.

Ruled on the carry item W2b raised and correctly declined to fix.

### R-19.1 — The break is real, and it is latent rather than immediate

`valuation_fixture_capture.rs` now emits `"interest": point.interest_expense_dollars` — an explicit
`null` when absent. `valuation_baseline.rs:72` declares `pub(crate) interest: f64`. **`serde` cannot
deserialize `null` into `f64`, so the next re-capture produces a fixture the reader rejects.**

It is latent, not immediate: the fixture is committed, so nothing fails until someone regenerates it.
That is the worst shape for this class of defect — it fires on whoever next re-captures, with no
connection to the wave that introduced it.

### R-19.2 — The larger finding: two more fields still fabricate, and they fabricate silently

The capture block carries this comment:

> *"Filling any of them would be inventing the very history this capture exists to stop inventing."*

and then, **three lines below it**, fills two fields with invented values:

```rust
"effective_tax_bps": point.tax_rate_bps.unwrap_or(0),
"marginal_tax_bps":  point.marginal_tax_bps.unwrap_or(2_100),
```

`unwrap_or(2_100)` **writes the US federal statutory rate into committed evidence for issuers that
reported no marginal tax**, and `unwrap_or(0)` writes a zero effective tax rate for issuers that
reported none. Both are exactly the fabrication the surrounding comment forbids, and both are worse
than the interest defect W2b fixed, because the interest one at least breaks loudly on the next
re-capture while these two stay silent forever.

`marginal_tax` is not incidental to this round. `resolve_rate_inputs` refuses outright when
`marginal_tax.is_empty()` and intersects every accounting-fit year against `tax_years`. A fabricated
`2_100` converts an issuer that should refuse into one that fits.

### R-19.3 — The committed fixture currently holds fabricated zeros

Because the emitter wrote `unwrap_or(0.0)` until this wave, **the committed deep fixture holds `0.0`
for every issuer-year with no filed interest.** Every test reading it has been reading a fabricated
zero as though it were evidence.

This mattered less before T2.6 because `.abs()` erased sign anyway. It matters now: after T2.6 a
signed net series can legitimately be near zero, so a fabricated `0.0` and a real `0.0` are no longer
distinguishable in the committed file — the precise ambiguity W2b's comment names, already realised
in the artifact.

### R-19.4 — Disposition

1. **W2b's emitter fix stands.** Emitting `null` is correct and it is what exposed the rest.
2. **The reader change is NOT a weakening of a check, and §2.0 does not forbid it on the merits.**
   §2.0's untouchability exists to stop a wave from moving a published-value baseline to make itself
   pass. Widening `interest: f64` to `Option<f64>` so the reader can *represent absence* moves no
   threshold, relaxes no refusal, and changes no expected value. It is the opposite of the thing
   §2.0 guards.
3. **But it is not W2b's to make, and not this round's.** Editing an §2.0 file mid-round, in the wave
   already stopped for a mis-registration, is how a scope leak becomes a merge argument. **Routed to
   its own wave**, which must land all three fields together — `interest`, `effective_tax_bps`,
   `marginal_tax_bps` — plus a re-capture, plus a diff of the regenerated fixture against the
   committed one so the fabricated population is *counted rather than estimated*.
4. **Registered ahead of that wave:** the re-captured fixture will differ from the committed one on
   every issuer-year where a field was absent. That count is currently unknown, and any report that
   states it without having run the re-capture is to be rejected.

Carry item **19** opened. It does not block Round 2's merge decision, and it is not discharged by
W2b's emitter fix alone — a wave that fixes the writer and leaves the reader unable to read it has
made the artifact worse, not better.

---

## R-20 — The basis measurement. Two of my six predictions are falsified, and the rule I recommended is measured as the worst of the four.

Probe H, `measure-guard-rules` @ `2e02f26` (merged Round 1 + W2a + W2b). Universe = union of
`INTEREST_SIGN_AFFECTED_COHORT` ∪ `VALUATION_ANCHORS` ∪ `PROBE_COHORT` = **52 symbols, 52/52 fetched,
772 issuer-year rows, 0 failures.** Reports: `build/basis-versus-sign.md`, `-raw.txt`.

Structural fact 3 held on **every one of 772 rows** (`n_sources == 1`, never once contradicted), so
the precedence reasoning R-18.1 rests on is measured, not assumed.

### R-20.1 — Registered predictions, scored

| | prediction | verdict | deciding number |
|---|---|---|---|
| **P1** | every (A)-refused issuer has a net year | **SURVIVED** | `a_not_c` empty |
| **P2** | FP(A) against basis = 0 | **SURVIVED** | **FP = 0 / 772 rows** |
| **P3** | (A) ⊊ (C), extra = DAL/CHTR/BKR | **SURVIVED** | \|A\|=26 ⊂ \|C\|=29; extra named exactly **CHTR, BKR, DAL** |
| **P4** | \|D\| ≤ 6 | **FALSIFIED** | \|D\| = **11** |
| **P5** | (D) rescues YUM/COR/TYL/ABBV | **FALSIFIED for COR**, survived for the other three | COR is net **18/18 filed years** |
| **P6** | monotone in refusal-set size | **SURVIVED** | D 11 ≤ A 26 ≤ C 29 |

I registered six and lost two. Both losses are recorded as written, not softened.

### R-20.2 — The sign is a SOUND but INCOMPLETE detector. Its defect is the response, not the detection.

**FP(A) = 0 across 772 rows.** A negative post-negation value never once arose from a gross concept.
So when the sign fires, it is never wrong: (A) never refuses an issuer whose series is genuinely
gross.

Its incompleteness is exactly LD-8, now named and counted: **FN = 122 issuer-years**, and at issuer
level **three issuers — CHTR, BKR, DAL** — file a net concept, stay positive, and are fitted today as
though gross. BKR is the cleanest specimen: **net in every filed year, never once negative.** No
sign-based rule can ever reach it. Their cost of debt is understated right now, in the shipped
product, and nothing detects it.

**But the measurement locates (A)'s real defect somewhere else, and Table 4 is decisive:**

> **Every one of the 25 trigger years has `fittable? = no`, by construction.** A year with
> `interest < 0` can never satisfy the fit's `interest > 0` leg. **So the year that vetoes the entire
> issuer is never a year the accounting fit would have consumed.**

ABBV is vetoed by a 2011 year whose debt reads `n/a`. COR by a 2008 year, debt `n/a`. TYL 2009,
`n/a`. YUM 2007, `n/a`. **(A) refuses issuers on the strength of evidence the model does not
consume** — which is criterion 2 of R-18.5, fixed before any of this was measured.

### R-20.3 — Ranked on the pre-registered criteria, not on the counts

48 of 51 measured issuers have a usable accounting channel (some year with `debt > 0 && interest >
0`). Refusals that actually cost an issuer a channel it had:

| rule | refuses | of which had a channel | share of channel-havers |
|---|---|---|---|
| **(D)** per-year basis | 11 | **8** | **17%** |
| (B) narrower window | 11 | 11 | 23% |
| (A) as implemented | 26 | 23 | 48% |
| **(C) issuer-wide basis** | 29 | **26** | **54%** |

Three of (D)'s eleven — **MPWR, TTD, DDOG** — have no filed debt in any year, so the accounting
channel never existed for them and (D) takes nothing from them. That is why 11 refusals cost 8
channels.

**(C), the rule R-17 recommended, is measured as the most destructive of the four.** R-18.2 predicted
this from the contract before the probe ran; the probe confirms it. My R-17 recommendation is
withdrawn on measured evidence.

Scored on R-18.5's two criteria:

1. **Misclassification against measured basis.** (D) is the basis rule at year granularity: FP = FN =
   0 by construction. (A): FP 0, FN 122 issuer-years / 3 issuers. (B): FP 0, FN 195.
2. **Refused on the strength of a year the fit would never use.** (A): **yes, all 25**. (C): yes
   (ABBV's only net year has no debt). (B): no, by construction. **(D): no** — it drops exactly the
   unusable years and nothing else.

**(D) is the only candidate that wins on both, and both were fixed before the numbers existed.**
(D) remains post-hoc in construction (R-18.3) and that does not change; what changes is that it is
now post-hoc *and* measured against criteria it could have failed.

### R-20.4 — COR is a different and more permanent gap, and no rule choice fixes it

**COR's winning qname is net for every one of its 18 filed years (2008-2025)**, with real debt
throughout (1.2B → 7.7B). It has never once filed a gross interest concept.

- (A) refuses it — on its 2008 year, which has no debt.
- (B) fits it — on a net series, understating its cost of debt with nothing to detect that.
- (C) and (D) refuse it — correctly, because gross interest expense is genuinely not measurable for
  COR from this driver at all.

COR is therefore **not evidence against (D)**. It is an evidence-coverage gap that survives every
rule on the table, and P5 was wrong to expect a per-year rule to rescue it. Routed to backlog as its
own item: an issuer whose gross interest expense is unobtainable from the current qname list needs
either another concept or another provider — **never a fabricated value and never a laxer rule
chosen to cover it.**

### R-20.5 — What (D) costs, stated honestly

(D) keys on basis per year, so `FcfPoint` must carry the basis per year. That is the same LD-8
plumbing (C) needs — **(D) is not cheaper to build than (C), only far less destructive to run.** The
minimum shape is one field, `interest_is_net_basis: Option<bool>`, set where `edgar.rs` already knows
the winning qname; the full qname is not required.

`Option<bool>`, not `bool`: an issuer-year with no interest reading has no basis either, and a
defaulted `false` would silently assert "gross" about a year nothing was filed for — the
absence-fabrication this effort exists to stop.

### R-20.6 — One reconciliation I can only close halfway, and I am not closing it further

W2b measured **24 of 25** affected issuers going `Nbps → REFUSED`. Probe H measures **25 of 25**
firing (A)'s predicate. These count different things and both can be right: MPWR fires the predicate
but has **no filed debt in any of its 12 years**, so it had no fitted rate to lose and reads `n/a` in
both arms — exactly the control W2b identified.

That explains one name. My `fitany` proxy says only **22** of the affected 25 had a channel, because
`resolve_rate_inputs` gates on `reported_total_debt_dollars` (a separate scalar) while the fit uses
per-year `point.total_debt_dollars`, and Probe H carries only the latter. **The residual two names
are unreconciled**, and I am recording that rather than picking whichever number reads better.
Closing it requires the published-value arm, not more argument.

### Disposition

The classification question is measured and settled; the published-value question is not. **No rule
is adopted and nothing merges on this ruling alone.** The choice is Juan's under protocol (a) and (c)
and he reserved it explicitly. **My recommendation changes from (C) to (D)**, on evidence that
falsified two of my own predictions and reversed my prior advice.

Once a rule is chosen, its published-value arm must be run on this tree and registered **before** it
lands — that is the re-registration R-17 demanded, and it is still outstanding.

---

## R-21 — PRE-REGISTRATION of the published-value arms. Written before rule (D) exists in code.

Juan ruled: measure the value effect for (A) and (D) on this tree, register first, then decide.
This section is written **before any implementation of (D)**, and it is the re-registration R-17
demanded — it covers T2.6 **and** T2.7 together, on a tree carrying both.

### R-21.1 — The three arms

| arm | tree | guard |
|---|---|---|
| **base** | `3bd20f2` + W2a (`4d201cf`) | legacy: silent year-drop, `.abs()` intact |
| **A** | `2e02f26` (base + W2b) | as built: any negative year refuses the issuer |
| **D** | `2e02f26` + `interest_is_net_basis` | drop net-basis years; refuse only if fittable set empties |

Arm **A**'s numbers already exist from W2b and are **not** to be re-derived from that report — they
are re-measured in the same run as **D**, or the comparison is between two different retrieval dates
and means nothing.

### R-21.2 — The affected populations, derived from Probe H before the run

Disjoint by construction, and this is what makes the predictions falsifiable:

- **(D) refuses 11**: ADSK, COR, DDOG, MPWR, NKE, NWS, NWSA, TTD, ULTA, WSM, BKR.
  Of these **MPWR, TTD, DDOG have no filed debt in any year** and must show **no change** — they had
  no channel to lose.
- **(D) keeps the channel but drops fitted net years for 8**: XYZ, ZBRA, CPRT, RMD, ROL, CHTR, ROST,
  DAL. **Their fitted rate must move.**
- **CHTR and DAL are invisible to the sign rule** — never negative in any filed year. They are the
  only two issuers that can move under **D** and cannot move under **A**.

### R-21.3 — Registered predictions

| # | prediction | falsified by |
|---|---|---|
| **Q1** | **Anchors PG, GOOGL, AMZN, MSFT move $0.00 in both arms.** None appears in any refusal or mixed-basis set. | any anchor movement — **STOP condition, halts the round** |
| **Q2** | **CHTR and DAL move in arm D and are bit-identical in arm A.** | either moving under A, or either failing to move under D |
| **Q3** | **CHTR and DAL move DOWN.** Dropping net years removes observations that understate interest expense, so the fitted cost of debt RISES and value FALLS. | either moving up |
| **Q4** | **arm D movers < arm A's 18.** Point estimate **14-20**, centred 16 (8 channel losses + 8 mixed-basis rate moves, less overlap with T2.6's own 6). | ≥ 18, or < 10 |
| **Q5** | **arm D lane flips < arm A's 9.** Point estimate **≤ 5**. | ≥ 9 |
| **Q6** | **YUM, TYL, ABBV read a fitted rate under D, not REFUSED** (P5 survived for these three). | any of the three dark under D |
| **Q7** | **COR and BKR are REFUSED under D.** COR is net 18/18; BKR net in every filed year. | either fitting a rate |
| **Q8** | **MPWR, TTD, DDOG are bit-identical between arms A and D**, having no debt in any year. | any of the three differing |
| **Q9** | The **24-vs-25 reconciliation closes**: the affected-cohort issuers that lose a fitted rate under A is measured exactly, and MPWR is among those that do not. | MPWR losing a rate |

### R-21.4 — A degenerate case registered in advance, so it is not discovered as a convenience

**ROL has 13 net years, exactly 1 fittable net year and exactly 1 fittable gross year.** Under (D) it
drops to a **single** observation, and `resolve_rate_inputs` has **no minimum observation count** —
`!accounting_common.is_empty()` admits it and takes the median of one.

**A cost of debt fitted from one issuer-year is not a measurement, and it is a defect of the existing
code that (D) makes reachable rather than one (D) introduces.** It is registered here, before the
run, so that whatever the number turns out to be it cannot be read as a success of (D) nor as an
argument against it. If (D) is adopted, the minimum-observation question is a separate ruling and
**must not be settled by whichever threshold makes this round's numbers look best.**

### R-21.5 — Binding on the run

- **No rule is chosen by these numbers.** R-18.5's criteria decided the classification; this arm
  measures *cost*, and cost is Juan's to weigh, not mine to optimise.
- **A refusal count that comes out lower than predicted is not a success.** Q4's falsification band is
  two-sided (`< 10` falsifies as surely as `≥ 18`) precisely so that "fewer refusals" cannot be read
  as "better rule."
- Anchors are a **stop condition**, never a score (R-18.5, unchanged).
- Arm D is built on the **throwaway** `measure-guard-rules` branch. It does not merge, and adopting
  (D) later means implementing it in a real wave with its own review — not promoting this branch.

---

## R-22 — The repo has no published-value regression gate at all. R-17.4 was not a cohort-coverage gap; it is structural.

R-17.4 recorded that the 26-name high-signal cohort read `9/26 → 9/26` with identical reason codes
while eighteen published values moved by up to `+4988` bps. I attributed that to coverage — only 1 of
the 25 affected issuers is a cohort member. **Reading the two gates at the source shows the coverage
gap is real but secondary.** The primary defect is that no gate anywhere asserts on a published value.

### R-22.1 — What the two gates actually measure, read not assumed

**`valuation_high_signal.rs:689` `high_signal_screener_cohort_all_members_pass`** asserts
`assess_high_signal(obs, &criteria)` per member — a **classification** against thresholds. A member's
published value may move arbitrarily and the gate stays green so long as the member does not cross a
criterion boundary. It calls `write_observation_audit(...)` first, which **dumps** every observation
to `tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json` — and **nothing
asserts against that dump.** The file is rewritten on every run and read by no check.

**`valuation_baseline.rs`** uses `base_cents` exclusively in **absurdity predicates**: `base_cents <=
0`, `base_cents < 100` with a large FCF run rate, `base_cents < market_price_cents / 10`,
`base_cents < selection_intrinsic_cents / 8`. Every one of them detects *nonsense*, not *change*.
`baseline_cohort_determinism_double_run` checks run-to-run stability within a single execution — not
stability across a change.

### R-22.2 — The consequence, demonstrated by this effort rather than argued

**There is no test in this repository that fails when a published intrinsic value changes.**

W2b moved 18 published values, 9 router lanes, and a distribution spanning `−2897` to `+4988` bps —
and the suite went **550 → 557 passing with the same four failures.** Every gate was green. The only
reason anyone knows those values moved is that this run built a probe specifically to look.

That is why this effort has needed a manual pre-registration before every wave (R-13, R-18, R-21):
**the discipline exists because the instrument does not.** Eleven of the twelve "check that cannot
fail" instances this run has logged are downstream of the same absence.

### R-22.3 — The fix is NOT a golden file of published values

The obvious response — commit the values, assert equality — is wrong and would be abandoned within
two waves. Every legitimate improvement to the model moves published values; a naive golden turns
each one into a red test whose only available remedy is to overwrite the golden, which trains exactly
the habit this project's standing rule forbids (*never gain valuation-gate ground by relaxing a
test*). A gate whose normal maintenance is "update the expected numbers" is a gate that will be
updated without being read.

**The correct shape is a registered-change gate**, which is what this run has been executing by hand:

- a committed table of published values per cohort member;
- a run that diffs against it and **fails on any unregistered movement**;
- a registration file where a wave declares, *before* it lands, which symbols it expects to move, in
  which direction, and by roughly how much;
- the gate passes when observed movement is covered by the registration, and fails both when an
  unregistered symbol moves **and when a registered symbol does not** — the second direction is the
  one that catches a wave measuring a different intervention than it implements, which is precisely
  the defect R-17.1 recorded against me.

Movement stays permitted. Movement stays *cheap*. What stops being possible is movement nobody
looked at.

### R-22.4 — Disposition

Routed to its own wave; it does not block Round 2. Registered constraints for whoever builds it:

1. **The cohort must intersect the population that moves.** The present cohort contains 1 of the 25
   `INTEREST_SIGN_AFFECTED_COHORT` names. A value gate over a cohort that excludes the movers is the
   twelfth instance of this run's pattern wearing a new hat.
2. **Not a `.abs()`-style tolerance band chosen to make the current diff pass.** Any tolerance is
   declared with its reason before the first run, not fitted afterwards.
3. **The audit dump is not a gate.** Writing a file no assertion reads is a record, not a check, and
   the distinction is exactly what R-16.3 and R-17.1 both turned on.
4. `high_signal_screener_observation_2026-08-02.json` stays untouched by this effort. The new gate
   gets its own artifact; it does not adopt a file that is already rewritten on every run and that
   Juan holds uncommitted.

**This is the most durable finding of the run so far.** The guard-rule question decides one number in
one channel; this decides whether the project can ever tell that a number moved.

---

## R-23 — The three-arm result. (D) eliminates the cascade, closes LD-8 where it reaches a published number, and reproduces R-13's counterfactual bit-for-bit.

One run, one fetch, three arms, 51/52 issuers (COF excluded as `FinancialServices`, not a fetch
failure). Both arms share one `fittable_accounting_candidates` helper — no duplicated resolution
logic, so the arms cannot drift from production. `cargo test --lib` 557/4/26, zero regressions.

### R-23.1 — Registered predictions, scored

| | verdict | deciding number |
|---|---|---|
| **Q1** anchors $0.00 both arms | **SURVIVED** | PG 18109, GOOGL 35679, AMZN 16185, MSFT 57139 — `+0` in all three arms |
| **Q2** CHTR & DAL move under D only | **FALSIFIED** | CHTR **−2289c** under D, `+0` under A ✔; **DAL `+0` under both** ✘ |
| **Q3** both move DOWN | **FALSIFIED** | CHTR down as predicted; DAL does not move at all |
| **Q4** D movers < 18, band [10,18) | **SURVIVED** | **18 → 12** |
| **Q5** D flips < 9, ≤5 | **SURVIVED** | **9 → 3** |
| **Q6** YUM/TYL/ABBV fit under D | **SURVIVED** | ABBV 319bps, TYL 83bps, YUM 517bps |
| **Q7** COR & BKR refused under D | **SURVIVED** | both REFUSED |
| **Q8** MPWR/TTD/DDOG identical A vs D | **FALSIFIED** | published cents identical; **refusal-reason string differs** |
| **Q9** the 24-vs-25 reconciliation | **CLOSED at 22** | neither prior figure was right |

Three of nine falsified. Across both rounds I registered fifteen predictions and lost five.

### R-23.2 — The cascade is eliminated, and this is the headline

| issuer | trigger | arm A | **arm D** |
|---|---|---|---|
| TYL | 2009 | **+6340c** | **`+0`** |
| YUM | 2007 | **−5757c** | **`+0`** |
| ABBV | 2011 | 319bps → REFUSED | **319bps, `+0`** |
| ROL | 2016-18 | 587bps → REFUSED | **587bps, `+0`** |

**Under (D) the ancient-year blackouts do not happen at all.** Lane flips fall **9 → 3**. The
`sel:fcff → sel:fwd` stampede that made W2b stop is gone.

COR still moves `+1273c` under **both** arms — correctly. COR is net 18/18 and genuinely unmeasurable
(R-20.4); no rule rescues it and none should.

### R-23.3 — LD-8 closes where it reaches a published number, and not where it doesn't

Of the three issuers only a basis rule can see:

- **CHTR** — cost of debt **513bps → 708bps**, value **−2289c**, lane `sel:fwd → disp:fwd`. Dropping
  the net years **raised** the fitted rate, exactly as R-21's Q3 reasoned: net years understate
  interest expense, so removing them corrects the rate upward and the value down.
- **BKR** — REFUSED under D, value **+3035c (+7889 bps)**. Net in every filed year.
- **DAL** — **no movement in any arm**, cost of debt 816bps throughout, lane `disp:fwd`.

DAL is why Q2/Q3 fell, and the reason is worth more than the prediction was: **DAL's cost of debt
never reaches its published value.** It routes through the forward lane, and its 816bps resolves from
a higher-priority channel the guard does not touch. **Basis presence and reaching a published number
are different questions**, and Probe H's static table cannot distinguish them. That is a genuine
limit of the classification measurement, discovered only by running values.

### R-23.4 — R-13's counterfactual was a valid prediction of (D). It was never a prediction of (A).

R-13 registered six movers. Arm D reproduces **four of them bit-for-bit**:

| | registered | arm D | arm A |
|---|---|---|---|
| ROST | −279c | **−279c** ✔ | +470c |
| MPWR | −357c | **−357c** ✔ | −357c |
| JKHY | −135c | **−135c** ✔ | −1492c |
| CPRT | −23c | **−23c** ✔ | +698c |
| ULTA | −124c | +654c ✘ | +654c |
| NKE | −12c | −294c ✘ | −294c |

ULTA and NKE diverge for one measured reason: under (D) their per-year dropping **exhausts** the
fittable set, so they degrade to (A)'s answer. Everywhere the fittable set survives, (D) *is* the
T2.6-only counterfactual.

**This sharpens R-17.1 rather than excusing it.** The mis-registration was real and remains mine: I
registered a T2.6-only counterfactual as "THE pre-registration" for a wave implementing T2.6 **and**
T2.7. What this run adds is that the intervention I accidentally measured is **the one (D)
implements** — so the registration was a sound prediction of the wrong wave, not a bad prediction of
the right one. **(A) is what diverged from it.** I am recording this because it would be easy and
self-serving to now read R-13 as vindicated; it was not vindicated, it was mis-filed, and it happened
to be mis-filed toward the rule the evidence now favours.

### R-23.5 — Q9: both prior figures were wrong, and W2b's was the further off

**22 of 25** `INTEREST_SIGN_AFFECTED_COHORT` members lose a fitted accounting rate under (A) — names
whose base carried a rate and whose (A) arm is REFUSED. Not W2b's 24, not Probe H's 25.

The three excluded are **DDOG, MPWR, TTD** — precisely the issuers with no filed debt in any year,
which had no fitted rate to lose. This matches the independent `fitany` proxy I computed in R-20.6,
which also said 22. **W2b's "24 of 25" is corrected to 22 of 25**, and the residual R-20.6 recorded
as unreconciled is now closed.

### R-23.6 — Two costs of (D), neither hidden

1. **ROL fits 587bps from a SINGLE observation** (fiscal 2025), registered in advance at R-21.4.
   `resolve_rate_inputs` has no minimum observation count and medians one value. **This is a
   pre-existing defect (D) makes reachable, not one (D) introduces**, and it stays unfixed here: a
   threshold chosen now, against these numbers, is a threshold chosen to make a round look good. Its
   own ruling, later.
2. **BKR moves +7889 bps upward on refusal.** Refusing the accounting lane is not a
   conservative act — it can raise a published value substantially. (D)'s delta range is
   `−535 … +7889` against (A)'s `−2897 … +4988`: (D) moves fewer issuers but its single largest move
   is larger, because it is the only rule that reaches BKR at all.

### Disposition

Both axes are now measured: classification (R-20) and published value (this section). **No further
measurement is available on this question** — the remaining choice is a judgement about what an
unmeasurable gross interest expense should cost, and that is Juan's under protocol (a) and (c).

Recommendation unchanged from R-20: **(D)**. It wins on both criteria fixed before any number
existed, eliminates the cascade that stopped the wave, and is the only rule that reaches the LD-8
population where that population actually reaches a published number.

> **Annotated at R-33.1, not rewritten.** The clause "eliminates the cascade that stopped the wave"
> is the criterion R-18.5 forbade in binding language ("how many issuers stay lit"). The decision
> stands on the two criteria fixed before any number existed; that clause should not have been
> listed among the reasons.

**Nothing merges on this ruling.** `measure-guard-rules` is a throwaway; adopting (D) means
implementing it in a real wave, without the `NetInterestPolicy` knob, with its own review.

---

## R-24 — RULING: (D) per-year basis is the T2.7 contract. Juan's call under protocol (a) and (c).

Juan ruled **(D)** after two measurement rounds he ordered: classification (R-20) and published value
(R-23). Recorded verbatim as the disposition, not as my recommendation being accepted — the choice
was reserved to him and taken by him.

### R-24.1 — The contract, stated so an implementation can be checked against it

> A fiscal year whose `interestExpense` was won by a concept declared in `negatedQnames` reports a
> **net** figure. Gross interest expense is therefore **not measurable for that year**, and the year
> is not a fittable observation for the accounting cost-of-debt channel. It is dropped.
>
> The **issuer** loses the accounting channel only when dropping those years empties the fittable set
> — the rule that already exists (`accounting_common`). The market-yield and rated-spread lanes are
> untouched: this rule governs what the accounting channel may fit, nothing else.

The sign of the filed value is **not** part of the contract. It was an approximation of it (R-20.2:
sound, `FP = 0/772`, but incomplete, `FN = 122` issuer-years / 3 issuers) and it is now replaced by
the quantity it was approximating.

**LD-8 closes with this ruling** — for the issuers where the basis reaches a published number.
R-23.3's DAL result means "closes LD-8" must be stated precisely: DAL is net-basis and does not move,
because its cost of debt resolves from a higher-priority lane and never reaches its published value.
The ledger entry is *the basis is now read where the fit consumes it*, not *every net-basis issuer
now moves*.

### R-24.2 — The pre-registration for the real wave already exists, and it is exact

This is the first wave in the run to land with a **measured, per-issuer, bit-level** prediction
registered before it is written, and it is not a band — it is an identity:

> **The real (D) implementation must reproduce arm D of R-23 exactly.**

| quantity | required |
|---|---|
| movers | **12**: ADSK −435, COR +1273, CPRT −23, JKHY −135, MPWR −357, NKE −294, ROST −279, ULTA +654, WSM −13, ZBRA −82, CHTR −2289, BKR +3035 |
| lane flips | **3**: NKE, CHTR, BKR |
| anchors | **$0.00** — PG 18109, GOOGL 35679, AMZN 16185, MSFT 57139 |
| delta distribution | `n=12 min=−535 median=−60 max=+7889` bps |
| fitted under D | ABBV 319bps, TYL 83bps, YUM 517bps, ROL 587bps |
| refused under D | COR, BKR (+ ADSK, DDOG, MPWR, NKE, NWS, NWSA, TTD, ULTA, WSM) |
| non-positive FCFF | **0** |

**Any deviation is a defect in the implementation until proven otherwise** — not a reason to update
the registration. If the numbers move because a provider re-filed between runs, that must be
demonstrated per-issuer against retrieval timestamps, never asserted.

**A registered mover that does NOT move fails this wave**, exactly as R-22.3 requires of the gate
this project does not yet have. That direction is what R-17.1 missed.

### R-24.3 — The T2.7 tests change, and this is the sanctioned case

W2b's T2.7 tests assert rule **(A)**: a negative year refuses the issuer. Under this ruling that is
no longer the contract, so those tests must be rewritten to assert (D).

**This is the one circumstance in which changing a test is not weakening it** — the same standing
applied to the MPWR hazard pin in R-16.2. The conditions are binding:

1. The wave states explicitly, in the report, which tests changed and that **R-24 is the authority**.
2. Each rewritten test asserts something **strictly more specific** than what it replaced: (D)
   distinguishes per-year basis from issuer-wide sign, so each test names the year and the concept,
   not just an outcome.
3. **No test is deleted.** A rule-(A) test that no longer expresses the contract becomes a
   rule-(D) test over the same fixture, so the coverage count does not fall.
4. The `net_interest_years` comment in `driver_resolution.rs` — which currently documents keying on
   the sign as an approximation and defers the real rule to LD-8 — is replaced by the actual rule.
   **LD-8's deferral text must not survive the wave that discharges it.**

### R-24.4 — What does NOT ship

- **No `NetInterestPolicy` knob.** It exists only on `measure-guard-rules` to run two arms side by
  side. Shipping a switch between a ruled contract and a rejected one would leave the rejected rule
  reachable in production, and R-21.4/R-24 would then be documenting a configuration rather than a
  contract.
- **No minimum-observation threshold.** ROL fits 587bps from a single observation (fiscal 2025).
  Juan took plain (D), not the ROL-first option, so the defect is **deferred, not dropped**: it is
  pre-existing, (D) makes it reachable, and it gets its own ruling on its own evidence. Fixing it
  inside this wave would set a threshold against numbers already seen.
- **`measure-guard-rules` does not merge.** It is a measurement artifact. Its probes and its
  `pub(crate)` widening of `extract_total_debt` may be re-derived in the real wave on their merits;
  the branch itself is retained for audit and then deleted.

### Disposition

Round 2 becomes **W2a + W2b + (D)** as one merge unit (R-10.3 unchanged). W2b's stop is discharged by
this ruling — it stopped for a contract question, the contract is now ruled, and the wave resumes
rather than being re-planned.

Carried forward, none of them blocking this wave: **ROL minimum observations** (R-23.6),
**COR's permanent evidence gap** (R-20.4), **the fixture reader/writer defect** (R-19), and **the
absent published-value regression gate** (R-22), which remains the most durable finding of the run.

---

## R-25 — Wave (D) verification: accepted on mechanism, held on population

The wave implementing R-24 is complete and I verified it directly rather than on its report.

### R-25.1 — What I checked myself, and what it showed

| claim | how I checked it | result |
|---|---|---|
| the guard keys on basis, not sign | read `driver_resolution.rs:137-148` | one `.filter(\|point\| point.interest_is_net_basis != Some(true))`, one code path, no enum, no policy parameter |
| no sign-keying survives | `grep` for `net_interest_years`, `interest < 0.0`, `is_net_basis` | the only remaining `interest < 0.0` is inside a doc comment describing the *retired* path |
| the detector never hardcodes a concept | read `edgar.rs:651-663` | reads `driver.qname_signs[index] < 0` by position in `driver.qnames`; tracks the contract automatically |
| `.first()` on `sources` is the winning fact | read `AnnualProvenance` at `edgar.rs:187-195` | "every fact that contributed, in combination order"; `INTEREST_EXPENSE` is `select_one_equivalent`, so a year's value comes from one concept and its sources are single-qname. Sound — but note this would be **wrong for a composed driver** like `extract_total_debt`, where `.first()` picks one of several qnames. The function is correct for its one caller and quietly unsafe for a second. Recorded, not fixed. |
| LD-8's deferral text is gone | read the replacement comment | discharged as R-24.3 condition 4 requires; the replacement states the rule and names the BKR counterexample |

Mutation testing was real: three distinct mutations of the guard line, each producing **named** failures, each reverted to 14/14 green. Mutation 2 (delete the filter) is the one that matters — it failed only 2 tests, and the builder said why: the first rewritten test's dropped year is also negative, so the pre-existing `interest > 0.0` predicate excludes it independent of basis. That is the builder finding a weakness in its own test and reporting it rather than counting the kill. It is the correct reading.

### R-25.2 — The population gap is the finding, and it is not cosmetic

The builder reported 10/12 movers and 1/3 flips reproduced, attributing the shortfall to
`INTEREST_SIGN_AFFECTED_COHORT` being pinned to R-13.1. It was right to refuse to mutate that
constant. Its conclusion — accept the mechanism-identity argument — is what I am not accepting.

**The ten confirmed movers are all sign-detected names: exactly the population where (A) and (D)
agree.** CHTR and BKR are the only registered names (D) reaches that (A) cannot — BKR is net in
every filed year and never once negative, which is the counterexample the shipped code comment
itself cites as the reason the rule exists.

So the tree had demonstrated (D) **nowhere that (D) differs from the rule it replaced.** The
verification covered the agreement set and missed the discriminating set entirely.

This is **instance thirteen** of the pattern: a check that cannot fail on the population that would
falsify it. It is the same shape as R-18.7's probe-cohort defect, and it arrived through a different
door — not a brief naming the wrong universe, but a correct refusal to widen a pinned one, with no
second universe put in its place. Refusing to corrupt an instrument and leaving the measurement
unmade are not the same act, and the report treated them as one.

"The mechanism is symbol-agnostic" is an argument. R-24.2 says any deviation is a defect **until
proven otherwise**, and arguments do not discharge that.

### R-25.3 — Disposition

Wave held, not rejected. The fix is additive and touches no pinned constant: a separate
`BASIS_ONLY_COHORT` of the two names, chained into the probe universe, and a live re-run reporting
CHTR and BKR in cents and bps against R-24.2 — plus confirmation that the ten verified names and the
four anchors are **unchanged** by the widening, since a universe change that moves an already-measured
name is itself a finding.

R-24.4 licenses re-deriving the reference branch's probes "on their merits." Adding a constant is
that. Mutating R-13.1's population would not have been.

### R-25.4 — Carried, with one new entry

`winning_qname_is_net_basis` is production logic with **no fast-test coverage** — reachable only
through the network-bound `fetch_fcf_history`. The builder declined to synthesize a fixture rather
than guess at its shape, and said so. That is the right call under wave scope and the wrong state to
leave permanently: the function that decides what "net basis" means is currently proven only by the
downstream deltas it produces. **New carried item, alongside R-19, R-20.4, R-22, and R-23.6.**

---

## R-26 — Two plan statements Round 2 invalidated, corrected before Round 3 can encode them

Round 2 shipping changes two things the plan says, and both would otherwise be written into Round 3
and Round 4 as *tests* — which is the dangerous form, because a test asserting a stale fact passes.

### R-26.1 — LD-8 is struck as closed by Wave 2, alongside LD-1

The plan enumerates the D7 latent-defect register in five places (T5.9, Wave 5 *Done when*, Wave 4
deliverables, W4-P05, and §6) as **LD-2 … LD-8, LD-9, LD-10, LD-11, with LD-1 struck**. R-24.1 closed
LD-8 and the shipped code discharges its deferral text: `driver_resolution.rs` now keys on the filed
basis, and `winning_qname_is_net_basis` reads `INTEREST_EXPENSE.qname_signs` per year. LD-8's entry
says the correct rule "is unimplementable until `FcfPoint` carries per-field concept provenance."
`FcfPoint` now carries it.

**The register enumeration becomes: LD-2, LD-3, LD-4, LD-5, LD-6, LD-7, LD-9, LD-10, LD-11 — with
LD-1 and LD-8 both struck as closed by Wave 2.**

This is not bookkeeping. W4-P05 asserts that every enumerated id "carries an id, an owner, a trigger
and a detector." Left uncorrected, Round 4 would write an owner, a trigger and a detector for a
defect that no longer exists, and the test would pass — documenting a live hazard against shipped
code that closed it. LD-8's own text says it has **no mechanical detector** because it "produces
plausible numbers"; inventing one now to satisfy a rectangularity check would be the fourteenth
instance of the pattern, in the register that exists to name the pattern.

Wave 5's ADR (T5.9) must state LD-8 as **closed, with the commit that closed it** — not omit it.
A register that silently drops entries cannot be audited backwards.

### R-26.2 — The protected failing set is three, not four; the fourth is a worktree artifact

Every wave report since Round 1 has reported "4 pre-existing failures" and named
`cross_platform_parity::export_random20_sp500_parity_snapshot` among them. The plan's *Done when*
says the failing set is **exactly three, by name**. Both cannot be right, and the discrepancy has
been carried across two rounds without being reconciled.

Measured, not argued. I checked out Round 1 (`3bd20f2`) into a fresh worktree and ran the test:

```
panicked at src\cross_platform_parity.rs:506:5:
missing random20 inputs at ...\.agents/workspace/tmp/random20-inputs.json
 -- run .agents/workspace/tmp/build_random20_inputs.py first
```

The input is **generated** and lives under `.agents/workspace/`, which `.gitignore:28` excludes. It
exists in the main checkout (19,502 bytes, generated 2026-07-30) and in **no** worktree. So:

- The failure predates Round 2 and is not caused by any wave.
- It is not a code failure at all — it is a **missing input**, and the test fails closed rather than
  fabricating one, which is the correct behaviour.
- **The plan's three-name failing set is correct.** The fourth name is an artifact of running the
  suite anywhere other than the main checkout.

**Rule for Rounds 3 and 4:** the protected failing set is the three named in the plan. A worktree
build may additionally see `export_random20_sp500_parity_snapshot` fail for missing input, and that
is reported as **environment-missing, not as a failure** — the same standing as Gate 4's WebView2
block. It is a measurement that did not run. It is **not** added to the protected set: growing a
protected failing set is how a real regression eventually hides inside it.

Rounds 1 and 2 are unaffected — the number was mis-described in their reports, never acted on.

---

## R-27 — Wave 5 pre-registration, written before the wave is dispatched

Registered now so the wave cannot be graded against a target adjusted after seeing it.

| # | prediction | falsified if |
|---|---|---|
| P1 | Anchors do not move: PG 18109, GOOGL 35679, AMZN 16185, MSFT 57139, delta **$0.00** | any anchor moves by one cent. That falsifies F1 (the Core has no production caller) and the wave STOPS |
| P2 | T5.8's property test finds **zero** operating issuers still producing a Core value | any issuer produces one; `return_on_capital` is hardcoded absent at `valuation_core_adapter.rs:557`, so a number means a second substitution path exists that FR-29's deletion did not reach |
| P3 | The bank test keeps a **different** reason (`provider_unavailable`) from operating issuers' `estimator_unavailable` | they converge — which would void the entire justification for adding a new variant rather than reusing `NotReported` |
| P4 | T5.12's compile gate holds: the crate builds with `value()` behind `#[cfg(test)]` | it does not build, in which case F1 is false, Waves 3 and 5 both lose their live-QA exemption, and the wave STOPS |
| P5 | Every one of the 34 planner-derived Examples cells matches observed behaviour without edit | any cell disagrees — the wave STOPS and reports rather than editing the cell, per T5.4 |
| P6 | The Shell failing set stays exactly the three named in R-26.2 | a fourth appears that is not the environment-missing parity input |

P5 is the one with real prior probability of falsifying: 34 cells derived by reading code, never by
running cucumber. A disagreement there is a **finding**, not a defect in the plan — and the rule that
the builder may not edit the cell is what makes it a finding instead of a laundered spec change.

---

## R-28 — Wave 5 verified; Round 3 merged as `d688fe9`. Wave 4 pre-registration.

### R-28.1 — All six of R-27's predictions confirmed, two of them by me rather than by the builder

The builder correctly declined to run the full Shell suite (out of a builder's scope, and it rewrites
a checked-in fixture as a side effect) and left P1 and P6 to me. I ran both.

- **P6 exact.** `cargo test --lib` → **563 passed, 3 failed, 24 ignored**, and the three are exactly
  the protected names. **This also confirms R-26.2 empirically**: with the generated input seeded into
  the worktree, `cross_platform_parity::export_random20_sp500_parity_snapshot` **passes**. It was
  never a code failure, and it is now off the reported failing set for good.
- **P1 exact.** PG 18109, GOOGL 35679, AMZN 16185, MSFT 57139 — bit-identical to Round 2, delta $0.00.
  CHTR −2289 and BKR +3035 also unchanged, so Round 3 moved nothing published, which is what F1
  predicts and what the compile gate (P4) independently proves.
- **P5 held, and it was the one with real prior probability of falsifying.** Thirty-four Examples
  cells derived by *reading* code, confirmed against 95/95 cucumber scenarios with **no cell edited**.
  The escalation path existed and was not needed.

### R-28.2 — Two builder course corrections, both reported rather than buried, both correct

**T5.8's cohort.** The builder first put the property test against the real 20-name pinned cohort and
it failed: MH and BWMN refuse `not_reported`, not `estimator_unavailable`, for a pre-existing evidence
gap unrelated to return on capital. Rather than weaken the assertion it re-read T5.8, found "the
adapter's pinned test cohort" means the adapter's own synthetic fixture, and moved the test there —
**then kept the real-cohort measurement as a second test** with an explicit two-name exception list,
corroborated against `valuation-aggregation-audit.md §7`'s pre-Wave-5 measurement of the same two
names under the same reason. That is the correct handling of a failing check: neither relaxed nor
discarded.

**T5.9's location.** It intended to embed the register in the ADR; re-reading D7 showed D7 is
unconditional and binding. It created `docs/valuation-economic-contract.md` instead. **Wave 4 must
extend that file, never replace it** — the register with LD-8 closed at `f38fe2c` already lives there.

### R-28.3 — A tooling defect worth carrying: `cargo fmt -- <files>` is not file-scoped

In this workspace `cargo fmt -- <file list>` reformats the **entire crate** and ignores the trailing
paths. It pulled three out-of-scope files into the diff as pure formatting noise; the builder caught
and reverted all three. Plain `rustfmt --check <files>` does respect the list.

I checked whether an earlier wave shipped this contamination: `f38fe2c` and `4d201cf` touch none of
`fetcher.rs`, `lib.rs`, or `valuation_gap_attribution.rs`. **No committed wave is affected.** Any
future wave claiming a scoped format check must use `rustfmt`, not `cargo fmt`.

### R-28.4 — Wave 4 pre-registration

| # | prediction | falsified if |
|---|---|---|
| Q1 | `cargo test --lib` is **unchanged**: 563 / 3 / 24, same three names | any number moves — a documentation wave that changes behaviour has escaped its scope |
| Q2 | `docs/index.md` and `AGENTS.md`'s Documentation Map agree **name for name** | they diverge (M1) |
| Q3 | The pre-registration names **exactly one** primary endpoint, quoted verbatim from the brief | more than one, or a paraphrase — the document's entire value is that it did not drift |
| Q4 | The materiality threshold is a **number in the endpoint's units** with a written propagation derivation | an adjective, or a number with the derivation skipped |
| Q5 | **No document claims a measured result** | any does — these are charters and a pre-registration; a measurement in one is a category error |
| Q6 | The register in `valuation-economic-contract.md` still shows **LD-1 and LD-8 struck**, LD-8 at `f38fe2c` | Wave 4 overwrites Wave 5's file and loses it |

Q6 is the one with a real failure mode: Wave 4 owns the file Wave 5 just created, and the natural
builder instinct on "write `docs/valuation-economic-contract.md`" is to write it, not extend it.

---

## R-29 — Wave 4 verified. The checkpoint that could not run, and why the thing it protected survives anyway.

### R-29.1 — T4.8's checkpoints did not happen, and the fault is mine

I instructed the builder to `SendMessage` me and wait at three points. **The builder role has no
SendMessage tool** — Read/Write/Edit/Grep/Glob/Bash only. I mandated something impossible.

The builder did the right thing: it produced the checkpoint content at the correct points, self-verified
it against the same criteria, and **reported the gap as a blocking orchestration defect** rather than
quietly claiming compliance. That is the behaviour this run has been trying to select for, and it
appeared without being asked for.

**T4.8 existed to make one property auditable: that T4.4's skeleton preceded its prose.** I tried to
recover it post-hoc from the agent transcript. The transcript file is **zero bytes**. There is no
audit trail. **The ordering claim is self-certified and I cannot verify it.** I am not going to
describe it as verified.

### R-29.2 — The stronger guarantee, which does hold, and which ordering was only a proxy for

Ordering matters for exactly one reason: a materiality threshold written *after* seeing a candidate
result is tuned, not pre-registered. So I checked the thing itself rather than its proxy.

- `probe_return_on_capital_availability` **exists** in the tree (`valuation_probes.rs:655`) — it
  belongs to a different, later plan and computes three candidate return estimators.
- It has **never been run in this effort.** No recorded output exists anywhere in `build/`.
- No candidate estimator has been evaluated against the primary endpoint, by anyone, ever.

**The threshold cannot have been tuned to a result that does not exist.** That is a stronger
statement than "the skeleton came first," and it is checkable, which the ordering claim is not.

The irony is worth recording rather than smoothing over: T4.4 element 11 requires the pre-registration
to confess **in its own text** that its freeze attestation is self-certified, written by an agent that
will not run the harness, with no external party attesting it. The process that produced the document
turns out to have precisely that property. The document is honest about its own weakness; this ruling
is the same honesty applied one level up.

### R-29.3 — Q1 through Q6, verified by me

| # | verdict |
|---|---|
| Q1 | **exact** — `cargo test --lib` → 563 / 3 / 24, the same three names. A documentation wave moved nothing |
| Q2 | **held after a one-line fix I made myself** — see R-29.4 |
| Q3 | **held** — one primary endpoint, quoted verbatim at line 17, `MdAE` defined on first use |
| Q4 | **held, and I checked the algebra.** `d(FCFF)/FCFF = [b/(r(1−b))]·dr` follows correctly from `FCFF = NOPAT(1−b)`; at `b=1/3, r=0.09` the factor is 5.56; `0.01 = 5.56·dr → dr ≈ 18bps`, rounded **up** to 20 — the stricter direction. The judgement step is labelled as judgement, and the ±5% anchor trigger is explicitly *not* reused as though derived |
| Q5 | **held** — the grep hits are the adjective ("mis-measured `r`", "already-measured convention"), never a claimed result |
| Q6 | **held** — the register survived; LD-8 struck and marked `CLOSED, at commit f38fe2c`, with no invented detector. The file was extended, not replaced |

`Dropping abstained cells from the primary endpoint is a prohibited analysis` appears verbatim.
The cluster bootstrap resamples **issuers**, 10,000 replicates fixed in advance.

### R-29.4 — One M1 gap I closed rather than narrowed

All seven new documents appear in both `docs/index.md` and `AGENTS.md`'s Documentation Map. But M1
says *the two lists agree*, and `cross-platform-parity.md` was in the index and not in the map — a
**pre-existing** asymmetry, not one this wave introduced.

The tempting move is to declare M1 satisfied on the seven new documents, which is the narrower reading
and would have been defensible. T4.7 says the map matches `docs/index.md` **exactly**. I added the one
missing line. Closing a gap costs less than the precedent of narrowing an invariant to fit the work.

### R-29.5 — Carried forward from this wave

The builder named four open items rather than leaving them implicit: **LD-2** (fabricated-zero capex),
a newly found **divestiture blind spot in `resolve_capex_abs`**, the unresolved **two competing
definitions of invested capital**, and `variance_of_centre`'s residual bias. The divestiture finding
is new to this run and is not in the register; it belongs there.

---

## R-30 — Stage 6 Sensei: `revise`. What I verified, what I refuted, and the measurement nobody had taken.

Sensei returned `revise` on the shipped effort. I did not accept it and I did not dismiss it — I ran
its checkable claims. Three resolve against it, one resolves for it and is a P0, and its broadest
charge is correct and now measured.

### R-30.1 — CONFIRMED, and it is the effort's most serious open defect: the double-count is armed

`valuation_core_adapter.rs:542` — `fn base_cash_flow` builds its observation with
`self.provenance("free_cash_flow", frame)`. **FCFF is fed into the Core's `E` slot.**

The retention charge is `C(t) = E(t)(1 − g/r)`, and that identity requires `E` to be earnings
*before* growth reinvestment — NOPAT. FCFF is already net of reinvestment. Feeding it applies the
haircut twice.

Sensei's reading of what FR-29 was actually doing is correct and sharper than the plan's: with
`r := w`, the `(1 − g/r)` factor vanishes identically and the model was computing `E_0/w` — **a
no-growth perpetuity on FCFF at WACC.** Internally consistent, dimensionally sound, and therefore
plausible enough to argue about rather than obviously broken. FR-29 was not a growth bug with a
value-neutral side effect; it was a silent model substitution.

**Round 3 deleted the mask and did not fix the unit mismatch.** Today the double-count is unreachable
because `return_on_capital` is hardcoded absent. The day an estimator supplies a real `r`, the Core
publishes values wrong by `(1 − g/r)` — roughly a 40% understatement at `g=4%, r=10%` — and it does so
at the single most trusted-looking moment in the roadmap.

This is independently corroborated: a separate planning document in this repo reaches the same
conclusion unprompted, stating that NOPAT alone charges reinvestment zero times, ROIC alone on an FCFF
base charges it twice, and only both together charge it once.

**Ruling: no estimator wave may land before `E`'s input is made unrepresentable as FCFF** — a distinct
type, or at minimum a test named for the double-count that fails by default. Deleting a substitution
that made a wrong input harmless, without making the wrong input impossible, *arms* it. Sensei's
phrase is the right one and I am adopting it: a landmine with the pin replaced.

### R-30.2 — REFUTED: the degenerate-MAD candidate

Sensei predicted `robust_mean` was verified only on comfortable series, and that MAD = 0 at `n=3`–`4`
with duplicate values would be untested. **False.** `numerics.rs:593`,
`a_sample_whose_middle_has_no_width_has_no_robust_centre`, asserts
`robust_centre(&[7,7,7,7,7,7,1000]) == Err(OutOfPolicyRange)`, with a doc comment naming the reason: a
zero-width centre under inverse-variance weighting is infinite confidence. The boundary from above is
covered too (`a_sample_trimmed_exactly_to_three_still_reports_a_centre`). The primitive is verified on
exactly the population the prediction said it would miss.

### R-30.3 — REFUTED, by direct experiment: the economics is not unfalsifiable

Sensei's highest-confidence candidate for instance fourteen was that 100% of reachable behaviour is
the refusal path, so a mutation inside the charge formula should **survive**.

I ran it. `projection.rs:483`, `1.0 - instant / return_on_capital` → `1.0 + ...`:

```
test projection::tests::unit_value_agrees_with_direct_numeric_integration ... FAILED
test result: FAILED. 104 passed; 1 failed
```

**Killed.** The Core's own unit and cucumber tests drive `intrinsic_value` with *measured* returns and
never go through the dark adapter, so the economics is reachable and falsifiable. The prediction's
mechanism is wrong.

Its instinct is not entirely wrong, though, and I am recording the honest version: **1 kill out of 105
tests.** A single numeric-integration test is the whole defence of the central identity. That is thin,
and it is thin in the place that matters most.

### R-30.4 — CONFIRMED, and now measured: the effort had never measured its own goal

The charge: four rounds of self-versus-self deltas, and no measurement of the thing the effort exists
to close. Correct. Round 2 measured movers in bps *relative to the previous build*. Nobody had
computed the distance to street.

I measured it. Across the 26 issuers carrying a street comparison:

| | value |
|---|---|
| n | 26 |
| min | 0.47× |
| p25 | 0.78× |
| **median** | **1.06×** |
| p75 | 1.26× |
| max | 3.45× |

**This materially reframes the problem.** The model is not systematically below street — the median
sits 6% above it. The defect is **dispersion, not bias**: a quarter of issuers are 26%+ above, a
quarter 22%+ below, with tails to 3.45× and 0.47×. "The numbers don't match street" is true issuer by
issuer and false in aggregate, and those two facts call for different work. A systematic bias would
point at the discount rate or the base; dispersion this wide points at per-issuer evidence quality —
which is exactly what Rounds 1 and 2 addressed, and is the first evidence the effort has that it was
working on the right thing.

**The population caveat is not a footnote.** These 26 are the high-signal cohort — issuers that pass
the gates. It is a filtered sample, biased toward the names the model already handles confidently. It
is not evidence about the issuers we refuse, and coverage is deliberately excluded from the veto set,
so a wave that quietly refuses more would *improve* this table while serving users worse. This
measurement is **report-only and must stay report-only.**

**Pre-commitment, recorded now rather than after it becomes tempting:** no wave may cite gap-to-street
reduction as acceptance evidence. Juan's constraint is that street is a diagnostic — never a clamp, an
optimand, or an acceptance criterion. Sensei is right that the effort over-generalized that into
*never look*, and a diagnostic never read is a disclaimer. Reading it while pre-committing not to
optimize toward it is the reading that honours the constraint.

### R-30.5 — Adopted, with its sequencing

The single highest-value next move is **the published-value regression gate** (R-22, open since Round
1 and deferred four times): every issuer, published value *or refusal reason* as a first-class golden
value, offline fixture, one assertion. The observation audit is already most of it — it dumps and
nothing asserts.

Sensei's argument for why this outranks the estimator is the one I find decisive: it converts every
future pre-registration from *author-selected population* to *whole cohort, author explains the diff*,
which makes the ten-of-twelve failure mode **structurally impossible** rather than dependent on
someone noticing. Thirteen instances is not a tally of bugs caught; it is a measurement of how often
one careful reader was needed, and that does not survive the reader's attention moving elsewhere.

Sequence, which matters: **build the gate → bless current state → then fix the fixture writer (R-19)**,
so its effect appears as a visible diff. Fixing the fixture first destroys the observation.

Two further items adopted: a gate degrades into re-bless-and-move-on within about three waves unless
the diff justification must appear in a pre-registration *written before* the wave — so the gate
composes with the registration discipline rather than replacing it. And R-29.2's alibi ("it cannot
have been tuned to a result that does not exist") is **single-use**, valid for the 20bps threshold and
generalizing to nothing.

### R-30.6 — One open claim I have not verified

Sensei's second-ranked candidate: Round 2's guard drops fiscal years, the cost-of-debt fit has no
minimum `n`, and the registration measured published-value movers rather than fit sample sizes — so a
change whose mechanism is "drop years" was verified on a population that cannot exhibit its
characteristic harm. **Which issuers moved from `n≥2` to `n=1`?** Unanswered, concrete, answerable,
and it belongs with the ROL minimum-observation ruling (R-23.6) rather than being carried separately.

---

## R-31 — Sensei's candidate (b), resolved. Refuted as stated; a worse defect found one step over.

Sensei's second-ranked prediction, verbatim: *"Round 2 therefore moved issuers toward `n=1` and its
pre-registration measured published-value movers, not fit sample sizes. A change whose known mechanism
is 'drop years' was verified on a population that cannot exhibit the harm."*

I read the fit path end to end and ran one experiment. The prediction is **wrong**, and the reason it
is wrong is the useful part.

### R-31.1 — REFUTED: the `n≥2 → n=1` harm is value-visible by construction

`driver_resolution.rs:280` grades the fit by sample size, and the grade is not cosmetic:

```rust
let quality = if period_count >= 2 && !matches!(tax_source, DomicileTaxProxy) {
    EvidenceQuality::Solid
} else if period_count >= 1 {
    EvidenceQuality::Provisional
} else {
    return Err("fcff unavailable: no common valid debt and marginal-tax period".into());
};
```

`Provisional` propagates into `dcf_model.rs:3127`, where it adds `provisional_wacc_uplift_bps` scaled
by debt weight and sets `wacc_clamped = true`. **An issuer crossing `n=2 → n=1` gets a higher WACC and
therefore a lower published value.** It cannot cross that boundary silently — it lands in the
published-value mover set by the same mechanism that makes it harmful. Round 2's registration
*was* an instrument for this transition, by accident rather than design, but it was one.

So there is a minimum-`n` instrument after all. Sensei read `!accounting_common.is_empty()` as
"no minimum" — literally true for the hard-refusal boundary (`n=0`), and it missed that the
graded boundary sits at `n=2` and carries a price. My R-23.6 finding still stands on its own terms
(the 587bps issuer fits from one year), but that issuer is flagged `Provisional` and uplifted, not
published as though it were solid. The register should record that qualification.

### R-31.2 — The defect this actually found: `n=3 → n=2` is the silent transition

Directly above the grade, the rate is chosen by a bare order statistic:

```rust
rates.sort_unstable();
let rate = rates[rates.len() / 2];
```

At `len = 3` that is the median. **At `len = 2` it is the maximum.** I did not infer this — I injected
a two-period case and ran it:

```
two_period_accounting_fit_selects_the_higher_rate_not_a_centre ... ok
```

with `cost_of_debt_bps == 545`, the higher of `{500, 545}`; a centre would be ~523. Confirmed by
execution, not by reading.

That composes badly with R-31.1 in one specific place. An issuer dropping `n=3 → n=2`:

- **stays `Solid`** — no quality downgrade, no uplift, no `wacc_clamped`;
- **switches from median-of-three to max-of-two**, a strictly upward-biased cost of debt;
- **lowers its published value**, which Round 2's registration *would* have seen as a bps move and
  attributed to "one fewer year of evidence" — the expected consequence, correctly reproduced, wrong
  cause.

This is the fourteenth instance of the effort's signature defect, and it is a subtler species than the
previous thirteen: not a check that cannot fail, but a check that fires for the wrong reason and is
therefore read as confirmation. Sensei was right that Round 2's population reasoning was unsound. It
was wrong about which boundary carries the risk.

It is also a **naked order statistic on a measured series** — the family the workspace prohibits and
routes through `robust_mean` / `robust_centre`, which refuse below `kept < 3` precisely because two
points have no centre. The prohibition was written for this and this call site predates it.

### R-31.3 — Carried, with the reproduction already paid for

The fix is not mine to make outside a wave, and the choice between them has an economic consequence,
so it goes to the next pre-registration rather than being decided here:

1. route the annual rates through `robust_centre` and let `n<3` refuse — consistent with policy,
   and it converts today's silent bias into an honest refusal;
2. or keep `n=2` and downgrade it to `Provisional`, making the grade match where the estimator
   actually degrades rather than where the sample happens to hit one.

Option 1 removes published values from issuers that have them today; option 2 keeps them and prices
the uncertainty. That is a real economic fork with no test deciding it, which is Juan's category (a).
**I am not choosing it.**

The characterization test is written and verified and should land with whichever wave takes this, so
the current behaviour fails loudly if anyone changes it silently — the same treatment Wave 5 gave the
legacy `terminal_payout_bps` substitution:

```rust
#[test]
fn two_period_accounting_fit_selects_the_higher_rate_not_a_centre() {
    let history = vec![
        point(2022, Some(100.0), Some(5.0), Some(2_000)),
        point(2023, Some(120.0), Some(6.0), Some(1_900)),
    ];
    let resolved = resolve_rate_inputs(&history, Some(120), 430).unwrap().unwrap();
    assert_eq!(resolved.cost_of_debt_bps, 545);
}
```

I injected it, ran it green against the shipped code, and reverted; the tree is clean. Landing it
costs a paste.

---

## R-32 — Stage 5 Reviewer: `revise`, upheld against me. R-29's acceptance of Wave 4 was wrong.

The Reviewer read the merged tree, ran the suite itself rather than accepting the wave reports, and
independently reproduced R-24.2's per-issuer registered predictions live (CHTR `disp:fwd`, MPWR's
FCFF candidate at exactly `51585`, BKR passing through `forward_earnings_power` after its accounting
refusal). It found the shipped code faithful to R-24 and R-25. Then it blocked the wave on
documentation, and it was right.

### R-32.1 — CONFIRMED by my own grep, not accepted: five carried items were absent

I verified rather than took the finding. `docs/valuation-economic-contract.md §14` at `ecff9ab`
contained LD-2…LD-11 and none of R-19, R-20.4, R-22, R-23.6, R-25.4. The Reviewer's grep hits for
`R-19`, `ROL`, `COR` and `winning_qname_is_net_basis` are false positives on my re-run too — `FR-19`
is a functional requirement, `COR` matches case-insensitively inside other words, and
`winning_qname_is_net_basis` appears only inside LD-8's *closure* text. The finding survives its own
weakest evidence.

**R-29.3 recorded Q6 as `held`, and Q6 was the wrong measurement.** I asked whether the register
*survived* Wave 4's edit — LD-8 struck, prior rows intact, file extended not replaced — and it did.
Completeness was never checked. That is this run's signature defect committed by the run's own
verification: a check that could not fail on the population that would falsify it, written by me,
one level above the code it was cataloguing. Thirteen instances found in the work; this is one in the
instrument. I am not filing it as instance fourteen, because it is a different and less flattering
category: not a check that cannot fail, but the *verifier* choosing the property that was easy to
confirm.

R-29.3's Q6 verdict is amended from `held` to `held on survival, wrong property`.

### R-32.2 — Fixed, at `e41a7ed`, and not by narrowing the requirement

The tempting disposition was available and would have been defensible: W4-P05 asks for a register of
latent defects, and the five carried items are arguably *findings* rather than *latent defects*, so
the register could be declared complete on its own terms. That is the same move R-29.4 refused for
M1, and refusing it twice is the point.

`e41a7ed` adds **LD-12 through LD-16** with the same id / defect / why-not-now / trigger / detector
shape as the existing rows, and the preamble now records how the omission happened. Two rows carry
more than they were carried as:

- **LD-13** states the *sequencing* as load-bearing — LD-12's gate must exist and bless current state
  before the fixture emitter is fixed, or the correction becomes the new baseline instead of a
  visible diff. Carried as a defect; registered as a defect with an order.
- **LD-15** absorbs R-31 and is materially worse than R-23.6 described. It records that the graded
  boundary is at one observation while the biased transition is at two, and that the two candidate
  fixes are a Juan-category-(a) fork with different economic results and no test between them —
  **stated, not settled.**

Three rows say plainly that they have **no detector**, which the register's own preamble demands and
which is the only honest entry for LD-12.

### R-32.3 — Convergent, independently: the Reviewer and I found LD-15's mitigation by different routes

The Reviewer's Finding 4 reports something no wave report mentions: ROL's single-observation rate does
not reach WACC unadjusted, because `EvidenceQuality` degrades to `Provisional` below two periods and
`dcf_model.rs` applies `provisional_wacc_uplift_bps`. I reached the same code from the opposite
direction while refuting Sensei's candidate (b) (R-31.1), reading the fit path forward rather than the
risk backward.

Two lenses that never spoke to each other landing on the same twenty lines is the strongest signal
this run has produced about that code. It is also the correction both of them make to *my* record:
R-23.6 framed the single-observation fit as an unmitigated risk, and it is not.

### R-32.4 — Accepted and not acted on

- **Boy-scout:** `driver_resolution.rs`'s header still reads *"DEPRECATED — do not extend"* while
  T2.7 added economically load-bearing logic to it. The Reviewer is right that the tension is
  unstated at the point of the edit. It is a one-line doc addendum to a module the roadmap retires;
  it goes to the next wave rather than to a docs commit that touches code files for prose.
- **Missing R-28/R-29 headers:** an artifact of reading the rulings file while I was still appending
  to it. Not a gap.
- **The fixture:** the Reviewer ran the live suite, which rewrote
  `high_signal_screener_observation_2026-08-02.json`, and reverted it. I verified independently that
  **none of the five round commits touches that file** (`git show --stat` across `3bd20f2`,
  `4d201cf`, `f38fe2c`, `d688fe9`, `ecff9ab` — zero hits each). Juan's constraint held.

### R-32.5 — One thing I broke and repaired inside this ruling

Reading the register at `round4-integration` I used `git checkout <branch> -- <path>`, which **stages**
into the current index — and the current branch was Juan's `valuation/wave1-integration`. It added
`docs/valuation-economic-contract.md` to his staging area, a file absent from that branch's HEAD.
Caught immediately, `git rm --cached` plus removal of the untracked copy; index and `docs/` verified
clean before continuing.

Worth the ruling line because the standing constraint is *never `git add -A`, stage explicitly*, and
the rule as written does not cover this: `git checkout <ref> -- <path>` stages without ever naming
`add`. Use `git show <ref>:<path> > <scratchpad>` to read a file from another branch. That is what the
rest of this ruling used.

---

## R-33 — Stage 7 retro. The curator found the third instance, and it is mine.

I asked the curator for one thing above all: *"anything in the record I appear to have gotten wrong,
been inconsistent about, or convinced myself of too easily — I have twice caught myself choosing the
easier property to verify, look for a third."* It found one. I verified it in the text before
accepting it.

### R-33.1 — ACCEPTED: R-23's disposition cites a criterion R-18.5 had forbidden

**R-18.5, line 1340, binding:**

> **Explicitly NOT criteria**, and this is binding: how many issuers stay lit; the direction or size
> of any published-value move; distance from street; and whether the result is convenient.

**R-23's disposition, line 1833:**

> Recommendation unchanged from R-20: **(D)**. It wins on both criteria fixed before any number
> existed, **eliminates the cascade that stopped the wave**, and is the only rule that reaches the
> LD-8 population where that population actually reaches a published number.

"Eliminates the cascade" is *how many issuers stay lit*, restated as a virtue, three rulings after I
bound myself against it. It is also the **headline** of R-23 — the first clause of the section title.
Not a buried slip.

**The decision stands; the justification does not.** The same sentence says (D) "wins on both criteria
fixed before any number existed," which is the load-bearing clause and was independently verified;
the third clause (reaching the LD-8 population where it reaches a published number) is legitimate too.
The cascade clause is rhetorical surplus. But it is exactly the surplus R-18.5 anticipated and
forbade, because a convenient result reads as better-justified when its convenience is listed among
the reasons. Juan ratified (D) under protocol (a)/(c) on the legitimate grounds; nothing about the
shipped rule changes.

**What changes is the process.** Adding to the standing check: *before closing a ruling, re-read your
own "explicitly not a criterion" list against the disposition text.* Seconds to run, and it would have
caught this. The failure shape is the one this run has catalogued fourteen times, at a new layer —
not "what does this instrument measure" but "does my conclusion honour the constraint I bound myself
to." R-23 and R-18.5 are annotated in place rather than rewritten.

The curator's secondary candidate — R-16.1 dispatching a wave under a ~10s fast-check budget while
its own Done-when demanded *"a live per-issuer table, measured, not argued"* — is also right, and is
the same shape one step earlier: a budget that forecloses the exit criterion paired with it. Caught
then only because the builder came back short, not at dispatch.

### R-33.2 — What was written to persistent memory, and what was refused

Three entries updated, three created, one index duplicate removed:

| entry | change |
|---|---|
| `verify-what-an-instrument-measures` | reframed to the sharper test — *can it fail on the population that would falsify it* — and given the **recursive clause**: apply it to your own verification. Both self-caught instances and R-33.1 recorded as worked examples |
| `no-naked-averages` | **corrected a stale API** (`robust_mean` lost its threshold parameter in T5.11; the memory still showed the two-argument form) and extended to bare order statistics — `sorted[len/2]` is the max at `n=2` |
| `stage-files-explicitly` | extended to `git checkout <ref> -- <path>`, which stages without saying `add` |
| `scope-you-cannot-get-wrong` | **new.** Pre-registration works and its population is author-selected; prefer the gate that needs no correct choice. Absorbs the single-use-alibi rule |
| `isolate-the-mutation` | **new.** A combined mutation going red proves nothing about any single assertion |
| `check-a-role-can-do-what-you-mandate` | **new.** Verify the role's tool inventory before dispatch; when a checkpoint is unrecoverable, substitute the stronger invariant rather than reconstructing it |

**Refused, on the curator's own recommendation and mine:** commit hashes, LD row contents, the (D)-
over-(A) choice, Sensei's specific findings, and the instance tally. Git history and
`docs/valuation-economic-contract.md` already hold those, and a memory that duplicates a repo fact
drifts the moment the code moves. The tally in particular is a fact about this run, not a transferable
one — the *pattern* is the durable part.

The stale-API correction is the retro's quietest useful result: a memory written four days ago already
described an interface this run had changed. Memories record what was true when written.

### R-33.3 — Two tooling facts to the repo, not to memory

`e6f4ee3` adds a section to `AGENTS.md` under *Preventing repeat operational errors*, whose closing
line already asks that new operational failure modes be written there rather than left in chat:
`cargo fmt -- <files>` is not file-scoped in this workspace, `git checkout <ref> -- <path>` stages,
and — flagged as tool-scoped rather than repo-scoped, with an expiry condition — a harness asked for
worktree isolation may provision from the default branch rather than the session's branch.

These are facts about this repo and this toolchain. They belong where a future agent will meet them,
not in cross-project memory.

### R-33.4 — Pipeline state

Stages 5, 6 and 7 are closed. The Reviewer's `revise` was upheld and its blocking finding fixed at
`e41a7ed`; the Sensei's `revise` was adjudicated claim by claim in R-30 and R-31, two of four claims
refuted by experiment; the retro is recorded here.

`round4-integration` is now `e6f4ee3` and pushed. **The armed FCFF→`E` double-count (R-30.1) is the
gating item for everything downstream** — no estimator wave lands before `E`'s input is made
unrepresentable as FCFF, and LD-12's whole-cohort gate is the wave that should precede all of it.

---


---

## R-34 — Round 5 pre-registration: LD-12, the whole-cohort published-value gate. Written before the wave.

R-30.5 ranked this above the estimator and R-32 showed why: the register that was supposed to carry
it did not, for one review cycle, because nobody could fail a test by dropping it. This wave makes
that impossible.

### R-34.1 — The host, and why it needs no new harness

`valuation_core_measurement.rs:115` carries a doc comment that states LD-12 in the code's own words:
**"The whole reason the Core exists, printed rather than asserted."** The test below it,
`core_versus_current_engine_on_the_pinned_cohort`, already:

- reads **offline** fixtures (`baseline_driver_data_2026-07-30`, `core_driver_data_deep`),
- fixes the market frame in source (`rf 430bps, erp 450bps, g 300bps, epoch 20663`) precisely so
  *"a measurement whose frame moves between runs measures the frame"*,
- computes both the legacy engine and the Core for the whole pinned cohort,
- runs in **0.04s** with no network,
- and throws every number to stdout under `#[ignore]`.

The gate is not a new harness. It is an assertion on numbers this repo already computes correctly and
discards. That is why this is one wave and not three.

Note in passing, not in scope: `median()` at `:107` is another bare `values[len / 2]`, the LD-15
family. It summarises a diagnostic and reaches no published value, so it is recorded here and left
alone rather than fixed opportunistically inside a gate wave.

### R-34.2 — The registered state, measured at `e6f4ee3` before any code was written

Bit-level, per issuer, in cohort order. **A registered value that moves is a failure of this wave.**

| # | symbol | market ¢ | legacy engine ¢ | Core |
|---|---|---|---|---|
| 1 | VRRM | 412 | **3461** | refuses `estimator_unavailable` |
| 2 | T | 2112 | **5972** | refuses `estimator_unavailable` |
| 3 | ADMA | 859 | **5475** | refuses `estimator_unavailable` |
| 4 | INOD | 6363 | **267** | refuses `estimator_unavailable` |
| 5 | MH | 932 | **2662** | refuses **`not_reported`** |
| 6 | VICR | 21470 | **2533** | refuses `estimator_unavailable` |
| 7 | AMSC | 3455 | **4176** | refuses `estimator_unavailable` |
| 8 | AMZN | 23933 | **12429** | refuses `estimator_unavailable` |
| 9 | BWMN | 2640 | **9463** | refuses **`not_reported`** |
| 10 | AAPL | 31339 | **11101** | refuses `estimator_unavailable` |
| 11 | IDCC | 26437 | **29370** | refuses `estimator_unavailable` |
| 12 | FIGS | 1027 | **929** | refuses `estimator_unavailable` |
| 13 | CALX | 3747 | **1995** | refuses `estimator_unavailable` |
| 14 | MSFT | 50000 | **31904** | refuses `estimator_unavailable` |
| 15 | MIR | 1627 | **940** | refuses `estimator_unavailable` |
| 16 | ROCK | 4359 | **11356** | refuses `estimator_unavailable` |
| 17 | HURN | 11085 | **27185** | refuses `estimator_unavailable` |
| 18 | VRT | 22646 | **16591** | refuses `estimator_unavailable` |
| 19 | INVA | 7614 | **7614** | refuses `estimator_unavailable` |
| 20 | APP | 15556 | **15556** | refuses `estimator_unavailable` |

*(rows 19–20 market/value transcribed from the same run; the wave's own capture is authoritative and
any discrepancy in these two cells is a transcription error in this table, not a licence to move a
value.)*

Legacy: **20 valued, median 1.11× market.** Core: **0 published.**

### R-34.3 — P1 through P7, falsifiable, scored after

| # | prediction |
|---|---|
| **P1** | The gate covers **exactly 20** issuers — the pinned cohort filtered by `!quarantine && status == "ok"` — and names them. Not a subset, not a sample. |
| **P2** | All 20 legacy published values match R-34.2 **to the cent**. Zero tolerance; this is an identity, not a band. |
| **P3** | All 20 Core entries are refusals, **18 `estimator_unavailable` and exactly 2 `not_reported` — MH and BWMN**. |
| **P4** | **The gate fails on isolated perturbation, proven twice, separately.** (a) Perturb one issuer's legacy value by **1 cent** → gate fails and **names that issuer**. (b) Swap one issuer's refusal reason `estimator_unavailable` ↔ `not_reported` → gate fails and **names that issuer**. Mutations run one at a time and reverted; a combined mutation does not count ([[isolate-the-mutation]]). |
| **P5** | The gate is **offline and deterministic**: no network, frame fixed in source, runs in the default `cargo test --lib` — **not `#[ignore]`** — and two consecutive runs agree. |
| **P6** | Failures stay at **exactly 3**, the same three protected names. Ignored count unchanged. Passed rises by exactly the number of non-ignored tests added. **No existing test changes status.** |
| **P7** | Re-blessing is **explicit and separate**: the golden file is regenerated only by a distinct `#[ignore]` writer that a person must invoke. There is no path by which a normal test run rewrites the golden values. |

### R-34.4 — What this closes, including something the effort had left open

**Sensei's candidate (c)** — `EstimatorUnavailable` vs `NotReported` precedence untested on a
discriminating population — closes here as a side effect, and it closes properly. The population
exists offline and is not synthetic: 18 issuers refuse for an absent estimator, MH and BWMN refuse
because the evidence was never filed. P3 and P4(b) pin the distinction, so a gate that merely recorded
"refuses" would pass if the two reasons swapped and this one must not. That is the difference between
pinning an outcome and pinning a *reason*, and it is the whole point of LD-12's "published value **or
refusal reason** as a first-class golden value."

### R-34.5 — Binding constraints on this wave

Restated because a gate wave is exactly where they are most tempting to bend:

- **The gate blesses what is there. It does not fix anything.** If a registered value looks wrong,
  that is a finding for a later wave, recorded — not corrected inside the wave that pins it. A gate
  that improves values while pinning them cannot be told from a gate that was tuned to pass.
- **`high_signal_screener_observation_2026-08-02.json` is untouchable.** It is Juan's, it churns on
  every live run, and it must not become the golden source. The golden file is **new**.
- No test threshold moves; no refusal path is relaxed; absence never becomes a zero.
- The three protected failures stay failing. Making one green in this wave is a defect, not a bonus.
- LD-12's register row is updated to **CLOSED** with the detector named — and LD-13's row already
  says the fixture-writer fix comes **after** this, so its effect shows as a visible diff. That
  sequencing is now load-bearing and is Round 6.

---

## R-35 — Round 5 verified. Six of seven predictions exact; the seventh was my own registration contradicting itself.

Shipped at `56b0c09`. I re-ran the load-bearing proofs myself rather than accepting the wave report,
and deliberately used **different issuers than the builder did** so the verification is independent
rather than a replay.

### R-35.1 — P1, P2, P3: exact

I compared the golden fixture against R-34.2 field by field, in code rather than by eye: **20 rows, 20
registered, zero missing, zero mismatches**, and the refusal split is `Counter({estimator_unavailable:
18, not_reported: 2})` with MH and BWMN the two. Every legacy value reproduced to the cent.

### R-35.2 — P4: the gate fails, proven twice, independently of the builder

The builder proved it on AMZN and MH. I re-ran it on **MSFT and BWMN**:

```
MSFT: legacy published(31903) -> published(31904)
BWMN: core refused(evidence/estimator_unavailable) -> refused(evidence/not_reported)
```

One mutation at a time, each reverted, gate green after each. A combined mutation was not offered and
would not have been accepted.

The second of those is the one that matters most and it is worth naming plainly: **a gate that pinned
only "refuses" would have stayed green while the reason changed underneath it.** This one does not.

### R-35.3 — P5, P7: exact

`0.03s`, `590 filtered out`, two consecutive runs identical — offline and deterministic. And the
golden file is **byte-identical after a full `cargo test --lib`**, verified by `diff` against a copy
taken before the run: no normal test run can re-bless. The writer is `#[ignore]` and its doc comment
says in its own text never to run it to make a failing gate pass.

The builder also added something I did not register and should have: the gate is **bidirectional** —
an issuer that leaves the pinned cohort fails, and an issuer that enters it unregistered fails too.
Pinning twenty values while silently accepting a twenty-first is the exact shape of defect this run
has catalogued fourteen times. That it was added unprompted is the wave's best sign.

### R-35.4 — P6: my registration was self-contradictory, and the fault is mine

P6 said *"ignored count unchanged."* P7 required a **separate `#[ignore]` writer**. Those cannot both
hold. Measured: `563 passed / 4 failed / 25 ignored`, ignored up by exactly one — the writer P7
demanded.

I am not scoring this as a wave failure. **A prediction that contradicts another prediction in the
same registration is a defect in the registration**, and the discipline is worth nothing if the
registrant grades himself generously on his own drafting. Recorded as such.

On the failure count: **4, not the registered 3.** The fourth is
`cross_platform_parity::export_random20_sp500_parity_snapshot`, which **R-26.2 already settled** — it
passes in the main checkout with the gitignored generated input seeded and fails in a fresh worktree
that lacks it. Not a regression, and the ruling that established it four rounds ago is what let me say
so in one line instead of re-investigating.

### R-35.5 — Two findings the wave surfaced that belong in the register

Neither is in scope for a gate wave; both are real.

1. **`high_signal_screener_cohort_all_members_pass` is not `#[ignore]`d, reaches the network, and
   rewrites a committed fixture on every default `cargo test --lib`.** That makes the default suite
   network-bound and non-deterministic, and it means
   `high_signal_screener_observation_2026-08-02.json` — the file Juan has told me repeatedly to leave
   alone — churns on every run anyone makes. It is the mechanism behind that file appearing modified
   in every session, and it is why the builder was instructed to leave it modified rather than revert
   it.
2. **`commands::qa_universe_apply_tests::ensure_symbol_loaded_does_not_grow_active_symbols` is
   order-dependent** under parallel execution: it failed in the builder's full run, passed in
   isolation, and passed in mine. A flaky test in the default suite degrades every count this effort
   quotes.

### R-35.6 — What Round 5 closed beyond its own scope

**Sensei's candidate (c)** — `EstimatorUnavailable` vs `NotReported` precedence never tested on a
population where both occur — is closed, on a real population rather than a synthetic one, and closed
by a mutation I ran rather than by an argument. That is the last of the four Sensei candidates
resolved: one confirmed and now gating the roadmap (R-30.1), three refuted by experiment.

---

## R-36 — Round 6 pre-registration: LD-13. The fabrication is worse than the register says, and the sequencing just paid for itself.

Base `56b0c09`. Written before the wave, after measuring.

### R-36.1 — What I measured, and why it changes the wave's shape

LD-13 describes two fabricated fields. Measured on the committed
`core_driver_data_deep.json` — **20 issuers, 274 issuer-years**:

| value | rows | what it means |
|---|---|---|
| `marginal_tax_bps == 2100` | **179 of 274** | the `unwrap_or(2_100)` statutory guess — **or** a genuinely filed 21%. **Indistinguishable.** |
| `marginal_tax_bps == 3500 / 3400` | 59 / 29 | pre-2018 US rates; these look filed |
| `effective_tax_bps == 0` | 24 | the `unwrap_or(0)` floor — **or** a real zero-tax year. **Indistinguishable.** |
| nulls in either field | **0** | absence was never representable |

**The defect is not that two fields are fabricated. It is that the fabrication is byte-identical to the
truth**, so no reader — human or mechanical — can audit the committed fixture. 65% of the corpus is in
that state.

And it is load-bearing, which the register row did not say. `valuation_core_adapter.rs:517`:

```rust
let after_tax = 1.0 - f64::from(annual.marginal_tax_bps) / 10_000.0;
```

The fabricated rate scales the interest add-back in FCFF, on both the Core path and — via
`valuation_baseline.rs:149,153` — the legacy engine's published values. `DriverAnnual` types both
fields as bare `i32`, so absence is unrepresentable at the type level too.

**The sequencing ruling paid for itself here.** LD-13's row says the gate must exist first *so the
correction appears as a visible diff*. It now does: the 20 legacy values Round 5 pinned are computed
through this exact field.

### R-36.2 — What this wave does, and the one thing it deliberately cannot

The committed fixture **cannot be corrected offline**. Distinguishing a fabricated 2100 from a filed
one requires re-fetching EDGAR, which is network-bound and would move published values. So:

**In scope**
1. The emitter stops fabricating — `null`, not `unwrap_or`, matching what `interest` already does and
   for the reason already written in that comment.
2. `DriverAnnual`'s two fields become `Option<i32>`, so **the compiler** — not a grep, not a review —
   forces every consumer to decide what absence means. Absence must drop the year or refuse; it must
   never become a zero, a floor, or a statutory guess at the read side either.
3. A test that fails if the emitter ever fabricates again.

**Explicitly out of scope, and registered as such so it cannot be quietly claimed:** the committed
fixture's 179 unauditable rows stay unauditable. Correcting them needs a network re-capture, which is
its own event, gated by Round 5, and must not ride along inside a type-safety wave.

### R-36.3 — Q1 through Q6

| # | prediction |
|---|---|
| **Q1** | **`published_value_regression_gate` stays green, all 20 issuers unchanged.** The committed fixture contains no nulls, so an honest reader reads exactly what a fabricating one did. This is the wave's central claim and the reason it is safe. |
| **Q2** | Failures stay at **4** with the same four names (3 protected + the `cross_platform_parity` worktree artifact of R-26.2). Ignored **25**. Passed rises by exactly the number of new non-ignored tests. |
| **Q3** | `grep -n "unwrap_or" valuation_fixture_capture.rs` returns **zero** hits on `effective_tax_bps` / `marginal_tax_bps` — LD-13's own registered detector, run against the result. |
| **Q4** | Both fields are `Option<i32>` in `DriverAnnual`, and **every** consumer compiles only after deciding about absence. `valuation_core_adapter.rs:517` in particular must not read a fabricated rate through an `unwrap_or`. |
| **Q5** | **Anchors do not move.** AMZN `12429` and MSFT `31904` identical to the cent — enforced by Q1, stated separately because they are the anchors. |
| **Q6** | A **new register row** records that 179 of 274 committed issuer-years carry an unauditable `marginal_tax_bps` and 24 an unauditable `effective_tax_bps`, that correcting them requires a network re-capture, and that the re-capture is gated by `published_value_regression_gate`. LD-13's row closes **only** for the emitter and the types — not for the corpus. |

### R-36.4 — The trap this wave is most likely to fall into

An `Option<i32>` whose every consumer immediately calls `.unwrap_or(2_100)` is **the same defect with
more ceremony**, and it would pass Q1, Q3 and Q5 without complaint. The type change is only worth
something if absence propagates to a drop or a refusal.

That is the wave's real acceptance criterion and it is not mechanically checkable, so it is stated
here as the thing I will read the diff for personally — a human-review checkpoint, named as one,
rather than a detector I do not have.
## R-37 — Round 6 verified. Six of six predictions held; the wave's own correction was unfalsifiable until I mutated it.

Base `56b0c09`. Verified by reading the diff line by line and by running the mutation myself, not by
accepting the wave report.

### R-37.1 — Q1, Q5: exact, and proven structurally rather than by transcription

`published_value_regression_gate` green. Better than a value comparison: the golden fixture
`published_value_regression_gate_cohort.json` **does not appear in `git status`** — it is
byte-identical to `56b0c09`. A green gate against an unmodified golden file is the whole of Q1 and Q5
at once, and it cannot be satisfied by a transcription error the way reading twenty numbers back can.

### R-37.2 — Q2, Q3, Q6: exact

`565 passed / 4 failed / 25 ignored` — the registered `563` baseline plus exactly the two new
non-ignored tests, with the same four failure names (three protected plus the `cross_platform_parity`
worktree artifact R-26.2 settled). `grep -n "unwrap_or" valuation_fixture_capture.rs` returns no hit
on either tax field; the four surviving hits are a print-only year label, a sort key, an
error-to-panic, and two doc-comment references to the removed fabrication. LD-13 is struck **for the
emitter and the types only** and LD-17 records the corpus half in the register's five-column shape.

### R-37.3 — Q4, and the trap it was written against

R-36.4 named the failure mode: *an `Option<i32>` whose every consumer immediately calls
`.unwrap_or(2_100)` is the same defect with more ceremony*. It did not happen. I read every consumer
myself:

| site | what absence does |
|---|---|
| `valuation_baseline.rs:149,153` | passes `Option` through unchanged |
| `dcf_model.rs:548` | `let Some(tax_bps) = … else { continue }` — the year drops |
| `dcf_model.rs:1630` | `.zip(point.tax_rate_bps)` — the bridge is not produced |
| `dcf_model.rs:972,1005` | `marginal_tax_source` is only set when a rate exists |
| `valuation_core_measurement.rs:79` | `row.marginal_tax_bps?` inside `filter_map` — the year drops |

No default anywhere. `valuation_core_adapter.rs:955,1030` are inside `mod tests`, constructing
synthetic `IssuerEvidence`; test fixtures are not production defaults.

### R-37.4 — What the wave shipped that no test could fail on

I flipped `row.marginal_tax_bps?` back to `.unwrap_or(2_100)` — **the exact defect this wave
exists to remove** — and ran the full suite. `565 passed / 4 failed / 25 ignored`: **identical.**
Nothing went red. Reverted.

So the honest drop was correct and **unfalsifiable**. The corpus carries zero nulls (that is LD-17),
so the branch has no reachable population, and a later edit could quietly restore the fabrication
inside the very function written to end it. This is the run's signature defect wearing its most
flattering disguise: not a check that cannot fail, but a *fix* that cannot fail. Fourteen instances
catalogued, one committed by the verification process itself in Stage 6, and now one committed by a
wave whose entire subject is fabricated evidence.

It was cheap to close, and the wave had already shown how: the emitter half was made falsifiable by
extracting `deep_driver_year_row` into a named function and unit-testing it. The same move on the
reader side — `issuer_annual(&DriverAnnual) -> Option<IssuerAnnual>` plus one test on a `None` row —
makes the drop measurable without touching the corpus. Sent back to the same builder thread, and
**I re-ran the mutation myself after it reported**: `565 passed / 5 failed`, the fifth being
`issuer_annual_drops_a_year_with_no_marginal_tax_rate`, naming itself. Reverted: `566 / 4 / 25`.

Shipped at `dc61f20`.

**The rule this earns:** a wave that replaces a fabrication with an honest absence must leave behind
a test that fails when the fabrication returns. Removing the defect and detecting its return are two
deliverables, and only the first is visible in a diff.

### R-37.5 — What Round 6 deliberately did not do

The 179-of-274 unauditable `marginal_tax_bps` rows and the 24 unauditable `effective_tax_bps` rows
stay exactly as they are. Correcting them needs a network re-capture of
`core_driver_data_deep.json`, which is a separate gated event. R-36.2 registered that as out of scope
*before* the wave precisely so it could not be quietly claimed afterwards, and it was not claimed.

LD-17 also records the thing that makes the deferral safe today and unsafe tomorrow: every pinned
issuer's Core outcome is a refusal raised upstream of `free_cash_flow`'s marginal-tax read, so the
fabricated rates are not load-bearing for any currently pinned value. The day an estimator is
promoted and the Core starts publishing off this fixture, `valuation_core_adapter.rs:517` becomes
load-bearing and the re-capture must be **pair-measured** against the gate rather than re-blessed.
That is the second roadmap item now gated on evidence rather than on intent — the first being
R-30.1's armed FCFF→E double-count.

---

## R-38 — Round 7 pre-registration: read the return-on-capital probe, and pre-commit to what each answer means.

Base `dc61f20`. **Written before the probe is run**, which is the only ordering under which a
measurement can decide anything.

### R-38.1 — Why this is the next round, and why it is a measurement rather than a wave

The effort's stated goal is a model coherent with street *without clamping to street*. Every issuer
the Core sees refuses, and the reason is always the same: `return_on_capital` is hardcoded absent at
`valuation_core_adapter.rs:657`, so `intrinsic_value` refuses with `EstimatorUnavailable` before any
growth is priced. Growth is credited nothing, for everyone. That is the largest remaining gap and
nothing downstream of it can be judged until it closes.

The evidence to close it **already landed** and I verified that rather than assuming it: the contract
carries `stockholdersEquity` at fingerprint `sec-driver-normalization/9`, and `FcfPoint` carries
`pretax_income_dollars` / `stockholders_equity_dollars` through `with_return_on_capital_inputs`
(`dcf_model.rs:879,882,994`). What is missing is not evidence. It is the **decision about which
return** the retention charge should use — and Juan's binding constraint forbids selecting an
estimator to keep the Core publishing numbers.

So the next round selects nothing. It runs `probe_return_on_capital_availability`, which asserts
nothing, writes nothing, and exists precisely to answer this by measurement.

### R-38.2 — The two things that could make this round produce no answer at all

Registered so that a null result is reportable as a null result rather than quietly reinterpreted:

1. **The probe is network-bound** (`#[ignore = "network: …"]`). If EDGAR is unavailable or the
   fetch fails for most of the cohort, the round returns *"not measured"*, not a fallback choice.
2. **The equity driver may not resolve to a plausible total.** The probe reports which qname won per
   issuer. If `StockholdersEquity` reads two orders of magnitude below market cap and debt, that is
   dimensional facts leaking in from the statement of changes in equity, and **the fix is the driver
   precedence, never the ratio.** Round 8 becomes a driver-precedence repair, not an estimator.

### R-38.3 — The decision rule, committed before the numbers

Three candidates: average book ROIC, the least-squares slope of NOPAT on invested capital, and the
return implied by realized reinvestment. The model already rests on the identity `g = b · r`, so each
candidate implies a reinvestment rate `b = g / r`, and the issuer filed what it actually reinvested.

**The candidate whose implied `b` sits closest to the realized `b`, across the cohort, is the one
Round 9 adopts.** Closest is measured by a robust centre of the per-issuer gaps
(`robust_mean` / `robust_centre`, no threshold argument), not by a mean of them, and not by counting
per-issuer wins — a naked average of gaps and a naked win-count are both order statistics this run
has already ruled against.

Committed consequences, so the result cannot be argued into either direction afterwards:

| outcome | what Round 9 does |
|---|---|
| one candidate is clearly closest | it is adopted; the other two are recorded as measured-and-rejected with their gaps |
| two candidates are indistinguishable | the **asymmetry** breaks the tie: an overstated `r` merely makes the charge vanish (`1 − g/r → 1`), while an understated or negative `r` produces negative value or a refusal. The less catastrophic estimator wins, and the tie is recorded as a tie rather than dressed up as a finding |
| every candidate sits far from realized `b` | **no estimator is adopted.** The Core keeps refusing, and the finding is that book capital does not measure this cohort's economic capital. That is a legitimate outcome of this round and it is registered as one *before* the numbers, because it is the outcome most likely to be rationalized away |

### R-38.4 — What the probe cannot decide, and the ordering it does not get to skip

R-30.1 stands: the adapter feeds FCFF into the slot FR-28's retention charge requires NOPAT for, and
that double-count is **armed** — harmless today only because `return_on_capital` is absent and the
integral never applies the charge. The probe measuring a good estimator does not disarm it. It makes
it fire.

So the ordering is forced and this ruling fixes it:

1. **Round 7** — measure. No code changes to any valued path.
2. **Round 8** — R-30.1: carry the return-on-capital terms into `IssuerAnnual`, replace the FCFF base
   with NOPAT, and make FCFF **unrepresentable** in that slot rather than merely unused. This is the
   Round 6 move applied to economics instead of evidence: the compiler, not a review, finds the
   consumers.
3. **Round 9** — the estimator R-38.3 selected, landing on a base that is already correct.

Reversing 8 and 9 charges reinvestment twice and would show up as a *worse* fit to street, which is
the failure mode most likely to be misread as "the estimator was wrong."

### R-38.5 — The gate's role in Round 8, stated now rather than when it is inconvenient

Round 8 changes the base input for every issuer. `intrinsic_value` refuses on an absent base with
`NotReported` **before** it reaches the `EstimatorUnavailable` check, so an adapter that supplies no
NOPAT would flip eighteen pinned refusal reasons and turn `published_value_regression_gate` red.

That is the gate working, not the gate being in the way. Round 8's acceptance is that all twenty
issuers keep the reason they have today, which is only achievable by supplying a real NOPAT rather
than by emptying the slot. **Re-blessing the golden fixture to absorb a reason change is forbidden**
unless the change is pre-registered per issuer, with the reason, in the ruling that precedes it.

---

## R-39 — Round 7 read: no estimator is adopted, and the reason is that the yardstick is not a yardstick.

Probe run at `dc61f20`, 28 issuers requested, 25 with three or more complete years. Scored against
R-38.3's rule, which was written before the numbers existed.

### R-39.1 — R-38.2's two null conditions did not fire

The network answered and the equity driver resolves to a plausible total, which is the thing R-38.2
said to check first and to fix at the driver rather than at the ratio. MSFT reads NOPAT `$99.55B` on
invested capital `$386.63B`, GOOGL `$126.05B` on `$464.35B`, AMZN `$78.67B` on `$479.90B` — the right
order of magnitude against market capitalization and debt, not the two-orders-smaller signature of
dimensional facts leaking in from the statement of changes in equity. **`IC <= 0` on zero
issuer-years**, which refutes outright the buyback-driven capital-deficit failure the plan expected
to have to handle.

So the round measured what it set out to measure. What it found is that the measurement cannot
decide.

### R-39.2 — The scored answer, under the rule as written

R-38.3 committed to a **robust centre** of the per-issuer gaps — not a mean, and explicitly not a
per-issuer win count.

| population | book ROIC | regression slope |
|---|---|---|
| probe's printed median | 0.980 | 1.113 |
| `robust_centre`, as printed (n=21 / n=17) | **1.040** | **1.153** |
| `robust_centre`, paired (n=16) | **1.190** | **1.465** |

Book ROIC is closer on every reading. The win count says the opposite — the slope is closer on 12 of
16 paired issuers — and **that is exactly why R-38.3 excluded win counts in advance.** Had the rule
not been fixed beforehand I would have had two defensible summaries pointing opposite ways and a free
choice between them, which is not a measurement.

It does not matter, because of the next paragraph.

### R-39.3 — Both candidates miss by more than the width of the quantity

`b` is a reinvestment rate. It lives in roughly `[0, 1]`. **Every centre above is greater than 1.0**:
the implied reinvestment rate misses the realized one by more than the entire plausible range of the
thing being compared.

That is R-38.3's third row, fired: *every candidate sits far from realized `b`, so no estimator is
adopted.* I flagged that row when I wrote it as the outcome most likely to be rationalized away.
I am not rationalizing it away. **Round 7 adopts no estimator. The Core keeps refusing.**

### R-39.4 — Why the yardstick is broken, which is the round's real finding

The realized `b` column contains `9.684`, `6.282`, `5.650`, `−3.081`, `−1.542`. Those are not
reinvestment rates, and a decision procedure whose reference term is not a measurement of the
quantity it names cannot decide anything — no matter how carefully the candidates are scored against
it.

The cause is arithmetic, not noise. The probe computes `b = (NOPAT − FCFF) / NOPAT`, and

```
FCFF  = OCF − capex + after-tax interest      (OCF already carries the D&A add-back)
NOPAT = (pretax + interest) × (1 − t)          (no D&A add-back)
```

so `NOPAT − FCFF` is not net reinvestment; it is net reinvestment **plus depreciation and
amortization**. For any mature issuer where `D&A > capex`, `FCFF > NOPAT` and `b` goes negative —
which is precisely the shape of the column, and it is also why the median `NOPAT/FCFF` reads
**`0.85×`** rather than the below-one-because-of-reinvestment figure one would expect.

Economic reinvestment is `(capex − D&A) + ΔWC`. **The contract has no depreciation or amortization
driver at all** — verified against `sec-driver-normalization.json` at fingerprint
`sec-driver-normalization/9`, whose eleven drivers are `operatingCashFlow`, `revenue`,
`interestExpense`, `totalDebt`, `currentDebt`, `nonCurrentDebt`, `stockholdersEquity`, `taxExpense`,
`pretaxIncome`, `marginalTaxReference`, `dilutedAverageShares`. The term the whole retention identity
turns on is not in evidence.

This is the same defect family the run has been cataloguing, in a new position: not a check that
cannot fail on the population that would falsify it, but an **instrument that cannot succeed**,
because the quantity it compares against was never measured. The probe was asked which estimator
reproduces realized reinvestment, and the honest answer is that this evidence set does not know what
these issuers reinvested.

### R-39.5 — Three method findings about the probe itself

Recorded because the probe is now a decision instrument, and an instrument that decides gets audited.

1. **It scores two estimators on two different populations.** Book ROIC is present for 21 issuers,
   the slope for 17, and only 16 have both. The printed medians `0.980` and `1.113` are therefore not
   comparable quantities. Paired, both move and the gap between them widens.
2. **It summarizes with a bare median of the gaps** — the naked order statistic this workspace rules
   against, in the same family as LD-15's `rates[len / 2]`. `robust_centre` shifts book from `0.980`
   to `1.190` and the slope from `1.113` to `1.465` on the paired set, trimming `42.138` and three
   others. The conclusion survives; the reported numbers did not.
3. **Credit where it is due, and it generalizes.** The probe refuses to score `r_impl` at all,
   stating that its gap is identically zero by construction. That is an author applying the
   unfalsifiability test to their own instrument, unprompted, and it is the discipline the rest of
   this ruling is asking for.

### R-39.6 — R-38.4's ordering is revised, by evidence rather than by preference

R-38.4 fixed Round 8 as R-30.1: replace the FCFF base with NOPAT and make FCFF unrepresentable in
that slot. That ordering is now wrong, and the probe is what makes it wrong rather than a change of
mind.

R-30.1's whole content is that the retention charge needs earnings *before* reinvestment. Landing a
NOPAT base while reinvestment itself is unmeasurable would move the base by a median `0.85×` on a
quantity nobody can yet check, and any subsequent misfit would be read as the estimator's fault. The
revised order:

1. **Round 8** — add the depreciation-and-amortization driver, at contract fingerprint `/10`, and
   re-run this probe. This is an evidence wave, inert to every valued path, and the gate proves the
   inertness rather than a pair-measurement by hand.
2. **Round 9** — R-30.1, on a base whose reinvestment term is measurable.
3. **Round 10** — the estimator, if and only if a re-run of R-38.3's rule finds one. If the gaps stay
   above the width of `b` with D&A in evidence, the finding is that book capital does not measure
   this cohort's economic capital, and it is reported as that rather than resolved by choosing.

Registering the revision rather than quietly resequencing, because R-38.4 said *"reversing 8 and 9
charges reinvestment twice, which is the failure mode most likely to be misread as the estimator was
wrong"* — and inserting a wave in front of both is a change to that commitment even though it moves
in the same direction.

---

## R-40 — R-39.4 is wrong, and the correct defect is smaller, provable, and already fixable.

R-39 is annotated rather than rewritten, following the R-23 precedent: the finding stands, the
mechanism it named does not.

### R-40.1 — What R-39.4 claimed, and why it is false

R-39.4 said the probe's realized reinvestment rate is contaminated because `NOPAT − FCFF` is
*"net reinvestment plus depreciation and amortization"*, and concluded that the contract's missing
D&A driver is the root cause. I checked the algebra after writing it, and it does not hold:

```
OCF   ≈ NI + D&A − ΔWC
FCFF   = OCF − capex + interest·(1 − t)
NOPAT  = EBIT·(1 − t)

NOPAT − FCFF = capex + ΔWC − D&A        ← D&A cancels through OCF
```

The depreciation add-back is already inside operating cash flow, so it cancels. **A D&A driver would
not have fixed the column.** Round 8 was one ruling away from adding a contract driver, bumping the
fingerprint and regenerating two platforms' policy files on a diagnosis that was wrong.

Recorded plainly because this run has repeatedly held others to proving a mechanism by execution
rather than by reading, and this was a mechanism I asserted from reading. The correction came from
opening `fcf_history_detailed` and `probe_return_on_capital_availability` and checking what they
actually compute, which is what I should have done before writing R-39.4 rather than after.

### R-40.2 — The real defect: an unlevered numerator against a levered cash flow

The probe sets `free_cash_flow: point.value_dollars` (`valuation_probes.rs:754`), and
`value_dollars` is `OCF − capex` (`edgar.rs:1065`, via `fcf_history_detailed`). **There is no
after-tax interest add-back.** So the probe measures

```
NOPAT − FCF = capex + ΔWC − D&A + interest·(1 − t)
```

`NOPAT` is a firm-level, unlevered quantity; `OCF − capex` is what is left for equity, *after* cash
interest. Subtracting one from the other leaves a full after-tax interest charge sitting inside a
term labelled "what the issuer reinvested."

That is why the column explodes exactly where it does. The contamination is `interest·(1−t) / NOPAT`,
so it is largest for levered issuers with small NOPAT — `HPE` reads NOPAT `$0.25B` against invested
capital `$42.51B` (ROIC 58bps), and `BKR`, `FIS`, `EXE` and `CRM` are the same shape. Those are
precisely the rows carrying `b` of `5.650`, `6.282`, `9.684` and `−1.542`.

The adapter already computes this correctly. `valuation_core_adapter.rs:517-520` adds interest back
after tax, with a doc comment explaining that the tax shield is carried by the WACC and counting it
in both places is a double count. **The probe and the adapter disagree about what FCFF is, and the
probe is the one that is wrong.** R-39.2's scored answer was computed against the wrong series, so
it is withdrawn along with the mechanism — but R-39.3's conclusion is not: no estimator is adopted,
now because the comparison has not yet been run on a defensible reference at all.

### R-40.3 — A second defect in the same instrument, and it is one this run already named

`valuation_probes.rs:748`:

```rust
let marginal_tax = point.marginal_tax_bps
    .map_or(STATUTORY_MARGINAL_TAX_BPS, f64::from) / 10_000.0;   // 2_100.0
```

That is **LD-13's fabrication, at a read site, inside the instrument chosen to decide the
estimator.** Round 6 removed the identical `unwrap_or(2_100)` from the emitter and made
`DriverAnnual`'s fields `Option<i32>` so the compiler would find every consumer — and it did find
every consumer *of that struct*. This site reads `FcfPoint` directly and was never in the compiler's
path, so the type change could not reach it.

The lesson generalizes and is worth stating as a rule: **making one carrier of a quantity honest does
not make the quantity honest.** The grep that would have caught this is for the *constant*, not for
the type.

`NOPAT = (pretax + interest)·(1 − t)` — so a fabricated `t` scales the entire numerator of every
candidate return, on every issuer-year with no filed marginal rate. Round 6 measured that population
at 179 of 274 in the committed corpus; the probe fetches live, so its own share is unmeasured.

### R-40.4 — Round 8, pre-registered

Base `dc61f20`. Scope is the instrument, not the contract. No driver is added, no fingerprint moves,
no generated policy file is regenerated, and no valued path is touched.

| # | prediction |
|---|---|
| **S1** | The probe computes FCFF with the after-tax interest add-back, matching `valuation_core_adapter.rs:517-520` rather than `point.value_dollars`. The two definitions of FCFF in this workspace agree afterwards, and that agreement is asserted by a test rather than by a comment. |
| **S2** | The statutory-rate fallback at `:748` is **removed**. An issuer-year with no filed marginal rate is **dropped**, exactly as Round 6 dropped it at `valuation_core_measurement.rs`, and the probe **reports how many years and which issuers it lost** so the cost of honesty is measured rather than assumed. |
| **S3** | The probe reports book and slope gaps on the **same population**, and says the population size. R-39.5 found it scoring 21 issuers against 17 with 16 in common; a comparison of two estimators on two populations is not a comparison. |
| **S4** | The summary statistic is `robust_centre`, not a bare median of the gaps. R-39.5 measured the difference at `0.980 → 1.190` for book and `1.113 → 1.465` for the slope; the conclusion survived, the reported numbers did not. |
| **S5** | `published_value_regression_gate` green, all 20 issuers unchanged, and the suite at `566 passed / 4 failed / 25 ignored` with the same four names. A probe is `#[ignore]`d and diagnostic; if this wave moves a published value, something outside its scope was touched. |
| **S6** | The re-run is **read against R-38.3's rule unchanged.** The rule was fixed before any number existed and it does not get to move now that a number exists. If the gaps stay wider than the quantity, no estimator is adopted and that is the finding. |

### R-40.5 — What is *not* being fixed, and why that is deliberate

The residual after S1 is `capex + ΔWC − D&A`, and neither `ΔWC` nor `D&A` is in the driver set. That
is genuinely fine: the identity `g = b·r` wants net reinvestment, and `NOPAT − FCFF` **is** net
reinvestment once both sides are on the same capital scope. No new driver is needed to measure it.

What is not fine, and is registered here rather than discovered later: `b` is a ratio whose
denominator is `total_nopat`, which is small and can change sign. `HPE` at `$0.25B` of NOPAT against
`$42.51B` of capital will produce an unstable `b` no matter how clean the numerator gets. If, after
S1 and S2, the gaps are still dominated by low-NOPAT issuers, **the finding is that this identity
cannot be evaluated on issuers whose earnings are near zero**, and the honest response is a
refusal-to-decide on that subpopulation — not a wider trim, and not a threshold invented after seeing
which issuers are inconvenient.

---

## R-41 — Round 8 verified. The repair flipped the answer, and the answer is still that no estimator is adopted.

Shipped from `dc61f20`. I ran the suite, the gate, and the probe myself; the probe's summary block
reproduces to the digit.

### R-41.1 — S1 through S6, all held

`566 passed / 4 failed / 25 ignored`, same four names. `published_value_regression_gate` green. The
FCFF formula is now **one function** — `after_tax_fcff` in `valuation_core_adapter.rs`, called by both
the adapter and the probe — rather than two statements of it and a comment asking them to agree. The
statutory fallback is gone, the constant with it. The comparison is paired and summarized by
`robust_centre` with its trimmed observations named.

### R-41.2 — The repair reversed the ranking, which is the strongest evidence that R-39.2 was worthless

| | book ROIC | regression slope |
|---|---|---|
| R-39.2, on the contaminated series (paired n=16) | 1.190 | 1.465 |
| **R-41, on the repaired series (paired n=14)** | **1.112** | **0.862** |

Book was closer before; the slope is closer now. Nothing about either estimator changed — only the
reference they were scored against. R-40.2 withdrew the earlier scoring on the grounds that it was
computed against the wrong series; this is that withdrawal confirmed by execution rather than by
argument, and it is worth stating plainly that had Round 7's number been acted on, the effort would
have adopted the losing candidate for a reason that had nothing to do with either candidate.

### R-41.3 — The cost of removing the fabrication, measured rather than assumed

**76 issuer-years dropped** for a missing filed marginal rate — `DAL(11) EME(10) INTU(10) BKR(9)
AMZN(9) AVY(6) CRM(6) T(4) AVGO(3) SLB(3) DVN(2) GEHC(2) EXE(1)`. Four issuers fell below three
usable years and left the estimator table entirely (`EME`, `BKR`, `INTU`, `GEHC`); the paired
population went from 16 to 14.

The constant that was deleted carried an argument in its own doc comment, and it deserves an answer
rather than a deletion: *"A marginal rate is a property of a jurisdiction, not a measurement of an
issuer, so requiring the issuer to have filed one is a stricter test than the quantity deserves."*
That is a real argument. The answer is that **the jurisdiction is not known either** without the
filing, and Round 6 measured what the substitution costs: 179 of 274 committed issuer-years read
`2100`, where the guess and a genuinely filed 21% are byte-identical and no reader can separate them.
A default that is usually right is worse than an absence precisely because it is usually right.

### R-41.4 — No estimator is adopted, and this time without inventing a threshold

The identity the whole test rests on is `g = b · r`. On the repaired series:

- **14 of 21** issuers have a **negative** realized reinvestment rate.
- Of the **14** issuers with positive realized growth, **8** have `b < 0` — `AVY CHTR TER AVGO T CRM
  PG MSFT`. For those, `g = b · r` with `g > 0` and `b < 0` implies **`r < 0`**, which the Core's own
  `r <= 0` guard refuses. Only **6** — `DVN FIS APH GOOGL EXE AMZN` — are consistent with a positive
  return at all.

A reference that contradicts the model's identity for a majority of the growing cohort cannot rank
candidates against it. **Round 8 adopts no estimator.** That is R-38.3's third row, fired for the
second time and now for a reason that needs no threshold.

### R-41.5 — My own registration was under-specified, and I am not using the patch I wrote for it

R-38.3's third row said *"every candidate sits far from realized `b`"* and **never defined far**.
R-39.3 then supplied one — *"greater than 1.0, the width of the quantity"* — **after** the first
numbers existed. That is a threshold invented to fit a measurement, which is the move this run
forbids in binding language and has enforced against others repeatedly.

So it is not used here. The conclusion in R-41.4 rests only on sign: positive growth with negative
reinvestment implies a negative return, and the Core refuses those already. Had the argument needed
`0.862` to be compared against some number, the honest report would have been *"my registration
cannot decide this and I will not repair it after the fact."*

Recorded in the same register as R-35.4's self-contradictory P6: **a pre-registration that omits the
criterion for one of its own branches has not pre-registered that branch.**

### R-41.6 — Round 9, pre-registered: decompose the residual before touching anything

After the levered/unlevered repair, `NOPAT − FCFF` should be `capex + ΔWC − D&A`. It is negative for
two thirds of the cohort, which is not what a cohort of large caps reinvesting through a capex boom
should read. Something still sits between the two sides.

Two candidates are nameable and **both are measurable without a contract change**, because the probe
already calls `fetch_company_facts` for its qname-coverage block:

1. **Non-cash charges beyond depreciation, share-based compensation above all.** Operating cash flow
   adds SBC back; `pretax_income` has it deducted. So `OCF − capex` overstates cash relative to
   NOPAT by roughly after-tax SBC, on exactly the names where `b` is most negative — `CRM`, `MSFT`,
   `AVGO`, `CHTR`.
2. **A tax-basis mismatch.** NOPAT is struck at the *marginal* rate while operating cash flow carries
   *cash taxes paid*. Where the two differ materially the difference lands entirely in `b`.

**Round 9 measures both and adopts neither.** Per issuer: realized `b`, `SBC / NOPAT`, and
`(marginal − cash effective) × pretax / NOPAT`, with a residual column so that what is *not*
explained is visible rather than absorbed. No driver is added, no fingerprint moves, no valued path
is touched, and no estimator is chosen.

That sequencing is not caution for its own sake. **R-40.1 asserted a mechanism from reading and was
wrong**, one ruling away from adding a contract driver and regenerating two platforms' policy files
on a diagnosis that did not survive its own algebra. The rule that earns is: **a mechanism gets
measured before it gets built against**, and the diagnostic that can measure it without touching the
contract is the cheapest place to find out.

### R-41.7 — What this means for the effort's actual question

Juan's goal is a model coherent with street without clamping to street. Three rounds have now been
spent on one question — what return the retention charge should use — and the honest status is that
**the reinvestment rate is not yet measurable from this evidence set.** Growth cannot be priced until
it is.

That is not a failure to report reluctantly. It is the answer that the constraint *"do not select an
estimator to keep the Core publishing numbers"* was written to protect: every round so far that
produced a publishable-looking answer produced a **different** one, and each time the difference came
from the instrument rather than from the issuers.

---

## R-42 — Round 9 verified. The instrument is clean now, and the cohort's reinvestment is genuinely zero.

Shipped at `9d4ebc0`. Suite `566 / 4 / 25`, gate green, probe re-run by me and reproduced to the
digit. Scope is one file, `valuation_probes.rs`, plus the fixture the network-bound test rewrites on
every run.

### R-42.1 — Both mechanisms are real, and together they are not enough

| | robust centre |
|---|---|
| realized reinvestment `b`, paired population (n=20) | **−0.317** |
| after-tax SBC / NOPAT | **+0.101** |
| (marginal − cash effective) × pretax / NOPAT | **+0.063** |
| **residual** — what `b` reads with both removed | **−0.008** |

Both corrections are real, sizeable and in the predicted direction. The centre moves essentially to
zero. And the count this wave existed to move barely moves: issuers with positive growth and negative
reinvestment go from **8 to 6**.

Per issuer the picture is not uniform, which is the honest part. `CHTR` goes `−0.153 → −0.044`,
nearly fully explained. `MSFT` goes `−0.062 → +0.093`, overcorrected. `SLB` goes `−1.824 → −1.930`,
**worse**, because it pays more cash tax than its marginal reference implies and the correction has
the opposite sign for it. `COF −2.435`, `WDC −0.624` and `CRM −0.761` remain large and negative.
Neither candidate is *the* explanation, and the probe says so in numbers rather than in prose.

### R-42.2 — What the zero means, now that the instrument is no longer suspect

Three rounds have been spent removing defects from this measurement: a levered cash flow subtracted
from an unlevered earnings figure, a fabricated statutory tax rate, two estimators scored on
different populations, a bare median as the summary, and now two unmodelled non-cash effects. After
all of it, **net reinvestment for this cohort centres at zero.**

That is no longer a broken instrument. It is a statement about the cohort. Capital expenditure sits
at about depreciation, and the growth is funded through charges the income statement **expenses**
rather than capitalises — research and development, share-based compensation, customer acquisition.
Book invested capital therefore does not move while earnings do.

The retention identity `g = b · r` needs reinvestment to divide by. **This cohort does not supply
any**, so the identity returns no finite return for the median issuer, and the estimator question as
posed has no answer on this evidence. That is the fourth and final firing of R-38.3's third row, and
it is now a finding about the world rather than about the code.

### R-42.3 — This is a Juan-category-(a) fork and I am not settling it

Two designs follow, they produce **different economic results**, and no test in this repository
decides between them. Juan's standing protocol reserves exactly this to him.

**(A) Capitalise the intangible investment.** Treat R&D — and arguably a share of SBC — as capital
rather than expense: add it back to earnings, accumulate it into invested capital with an
amortisation life, and re-measure. Book capital then tracks economic capital, `b` becomes positive
for the growing names, and the identity has something to divide by. This is the standard academic
correction and it is what makes ROIC meaningful for asset-light issuers. It is also a **large**
change: a new driver, a fingerprint bump, two platforms regenerated, an amortisation life that is a
policy choice with no filed value, and a new class of judgement about which expenses are investment.

**(B) Price growth without the retention identity.** Drop `C(t) = E(t)·(1 − g(t)/r)` as the mechanism
and credit growth through something measurable on this evidence — a fade to the terminal rate with no
retention charge, with the growth path itself already fitted and carrying its own uncertainty. This
is much smaller and it removes FR-28's dependence on a quantity the evidence does not contain. It
also gives up the discipline the retention charge exists to impose: growth stops costing anything, so
a high-growth issuer is credited its growth for free, which is the failure mode FR-28 was written
against.

**My recommendation is (A)**, and the reason is the asymmetry rather than elegance. (B) removes the
only mechanism that makes growth cost something, and this effort's whole complaint is that the model
does not price growth honestly — trading an unmeasurable charge for no charge at all moves the error
from "growth credited nothing" to "growth credited free", which is the direction that flatters and
therefore the direction to distrust. (A) is expensive and introduces a policy choice (the
amortisation life) that must be registered as a policy choice rather than smuggled in as a
measurement — but it makes the quantity the model already depends on actually measurable.

Registered here, not decided.

### R-42.4 — What is true regardless of which branch is chosen

R-30.1 still stands and still gates both: the adapter feeds FCFF into the slot the retention charge
requires NOPAT for, and the double count is armed rather than harmless. Under (A) it fires the day
the estimator lands. Under (B) the retention charge disappears and the slot's contract changes
entirely, which is a *different* reason to touch the same code but not a reason to leave it as it is.

And the gate holds either way. Every one of the twenty pinned issuers currently refuses, so any wave
that starts publishing values will move them **visibly, by name, in a failing test** — which is
precisely why LD-12 was sequenced first, four rounds ago, against the argument that it could wait.

---

## R-43 — The literature read. R-42's finding is the textbook signature, the fork had a third branch I missed, and half of branch (A) is contradicted on the record.

Juan asked for research rather than a choice. This is the read. Nothing is built and no branch is adopted.

### R-43.1 — What we measured has a name, and it is not an instrument defect

McKinsey, on business units whose capital base is small:

> the ROIC is usually extremely large (whether positive or negative), very sensitive to small changes
> in capital, and highly volatile and thus often inappropriate as a tool for comparing the performance
> of business units or companies

Their worked example is two units differing by **2% of revenue** in invested capital, reading **−700%
and +700%** ROIC. That is R-42 exactly: when the quantity you divide by centres at zero, the ratio is
not merely noisy, its **sign** is not identified. Round 8's ranking flip and Round 9's residual of
−0.008 are what that pathology looks like from inside.

This is worth stating plainly because it cuts both ways. Three rounds of instrument repair were **not**
wasted — the levered/unlevered mismatch and the fabricated statutory rate were real defects and both
moved the number. But the conclusion at the end of them was available in the literature from the
start, and I did not look. **Measure the mechanism before building against it** has a companion:
*read whether the mechanism is already named before spending three rounds measuring it.*

### R-43.2 — The remedy the literature offers for this exact case is neither (A) nor (B)

Damodaran's answer, when reinvestment cannot be recovered from the capital account, is to **estimate
reinvestment from revenue** and demote the return to a sanity check:

> Reinvestment_t = Change in revenues_t / (Sales/Capital)

chosen because the ratio *"can be estimated using the company's data (and it will be more stable than
the net capital expenditure or working capital numbers)"* — which is precisely the instability R-42
measured. And the discipline that replaces the measured return:

> keep track of the imputed return on capital (based on our forecasts of operating income and capital
> invested) to ensure that it stays within reasonable bounds

That inverts our current dependency. Today `r` must be **measured** or the Core refuses. Under this
route the reinvestment is measured and `r` is **implied**, then bounds-checked. Growth still costs
something, so it does not carry (B)'s flattering direction.

**This branch needs no new driver.** Revenue and invested capital are both already carried through
`IssuerAnnual`. It is the cheapest of the three by a wide margin, and it is the only one whose central
quantity we have not yet measured.

### R-43.3 — Branch (A)'s SBC half is contradicted, on the record, by the same source

R-42.3 wrote *"R&D — and arguably a share of SBC — as capital rather than expense."* The *arguably* was
doing more work than I knew. Damodaran:

> stock-based compensation is not comparable [to depreciation] ... it is more of an in-kind expense,
> where you give away shares of equity in the company instead of paying cash

He does not add it back and does not treat it as investment; adding it back assumes either that *"you
can stop paying employees in the future (and still hold on to them) or that you can keep giving away
equity stakes in your company with no consequences for value per share."*

Round 9's `sbc/NOPAT` column, centre **+0.101**, is therefore not a correction waiting to be applied.
It is a **real economic cost, correctly expensed**, and the probe added it back into the reinvestment
numerator — the one move this source names as wrong.

The honest qualification: Mauboussin and Callahan **do** capitalise a share of SG&A into *invested
capital*. That is a different question from whether to add SBC back to *cash flow*, and both positions
can hold at once. But nothing in what I read supports the specific thing Round 9 did.

### R-43.4 — Branch (A)'s R&D half has no filed life, and the practitioners disagree about the number

Damodaran does not publish an authoritative table. The life is *"determined by the nature of the
research expenses, and the estimated time until there is a payoff"* — pharma around ten years because
of the approval process, software around three because *"products tend to emerge from research much
more quickly and have shorter commercial lives."*

On how much to capitalise, the secondary sources disagree with each other:

| source | R&D | S&M | G&A |
|---|---|---|---|
| Mauboussin & Callahan, as reported | 100% | 70% | 20% |
| a second summary, software sector | ~90% | 20–25% of SG&A combined | |

**The disagreement is the finding.** The primary Morgan Stanley PDF returned 403 and I did not read
it, so I am reporting second-hand figures and saying so. But two reputable summaries of the same
literature differ by an amount that swamps the effect being measured, on a parameter that has **no
filed value anywhere in the evidence set**. That is exactly the class of author-chosen quantity this
run has spent six rounds removing — a statutory tax rate, a naked mean, a post-hoc threshold. Adopting
(A) would put one back, and this time in the numerator of the valuation.

### R-43.5 — A falsifiable gate that branch (A) would have to pass

Mauboussin, on what the adjustment does:

> The reclassification does not affect free cash flow, the main driver of corporate value, but it does
> provide a more accurate view of profits and investments

So under (A), **year-by-year FCFF must be unchanged to the cent.** R&D is added to earnings and to
reinvestment in the same amount; their difference cannot move. If an implementation of (A) moves
FCFF, it is wrong, and that is checkable without any reference to street.

I checked whether the stronger claim follows — *"therefore the valuation is invariant"* — and **it
does not**, so I am not asserting it. Our model derives growth and the retention charge **from** `r`
and the reinvestment rate rather than discounting an explicit FCFF series. Changing both inputs
changes the value. (A) is value-relevant here precisely because FR-28 drives value off `r`. The
invariance is a property of the cash flows, not of this model's output.

### R-43.6 — Nothing I read recommends branch (B)

No source found proposes crediting growth with no reinvestment charge. McKinsey's answer when the
capital base is unusable is a **different metric** — economic profit divided by revenue — not the
removal of the charge, and it is a performance-comparison metric rather than a valuation mechanism, so
it should not be imported as one. Damodaran's answer is a **different estimator of reinvestment**, not
its removal.

R-42.3 argued against (B) from asymmetry alone. The literature adds an absence: for a mechanism this
central, nobody proposing to drop it is evidence, and it points the same way.

### R-43.7 — My recommendation changes, and I am saying so rather than smoothing it

R-42.3 recommended **(A)**. On this reading I recommend **(D) — the sales-to-capital route — measured
in a probe first, and not adopted before it is measured.**

The reasons are three and none of them is elegance. (A) needs a policy parameter with no filed value
that reputable practitioners set differently by more than the effect is worth. (D) uses only
quantities already carried, so it can be measured this week rather than after a fingerprint bump and
two platforms regenerated. And (D) keeps growth costly, which is the property FR-28 exists to protect
and the one (B) gives up.

**The caveat that decides whether (D) is real, stated before measuring it:** the sales-to-capital ratio
still divides by invested capital, the same quantity R-42 found unusable. The difference is
arithmetic and it is the whole argument — R-42's problem is that the **change** in capital centres at
zero, while the ratio uses its **level**. A ratio of two levels is far better conditioned than one
whose denominator is a near-zero difference. That is a claim about this cohort, not a theorem, and it
is measurable: per issuer, `Sales / Invested Capital`, its dispersion, and how many issuers have a
usable positive capital **level** at all. If the levels are as ill-conditioned as the differences,
(D) dies too, and it dies cheaply.

Registered, not decided. Juan chooses.

---

## R-44 — Branch (D) chosen. And on inspection it is far smaller than R-43 said, because it collapses into an identity.

Juan chose the sales-to-capital route. Round 10's probe is running under the R-43.7 pre-registration,
extended once — before any number existed — with a cash-netted capital definition and a turnover ×
margin decomposition. This ruling records four consequences that follow from the research and the
choice, none of which needed the probe to see.

### R-44.1 — (D) is not a new formula. It is the same integrand with the other unknown supplied.

FR-28's retention charge is `C(t) = E(t)·(1 − g(t)/r)`. Write the reinvestment rate as `b(t)`. The
identity the whole effort rests on is `g = b · r`, so `g/r = b`, and therefore

    C(t) = E(t)·(1 − b(t))

**The same expression.** `{b, r}` is a pair in which measuring either one determines the other given
`g`. Rounds 7–9 tried to measure `r` and validate it against realized `b`. Branch (D) measures the
reinvestment side and lets `r` be implied. The Core's integrand does not change at all.

It goes one step further. Damodaran, on what the route assumes:

> If you leave margins unchanged and set the company's sales to capital ratio at its current level,
> you are essentially assuming that the company's **current return on capital will continue** for the
> long term

Which is the DuPont identity, exact year by year:

    NOPAT / Capital  ≡  (Sales / Capital) × (NOPAT / Sales)

So (D), applied with an issuer's own ratio and its own margin, **is book return on capital**, arrived
at from the other side. The estimator was never the disputed thing. What (D) actually abandons is the
**validation step** — the demand, registered in R-38.3's third row and fired four times, that a
candidate `r` reproduce the realized `b`. R-41.4 established that this reference is unusable on this
cohort: `b` is negative for 14 of 21 issuers, so it implies a negative return that the Core already
refuses. **A test whose reference is not identified is not a test**, and continuing to gate on it is
how three rounds produced three different answers.

That is worth stating without softening. Branch (A) was scoped as a large change — a new driver, a
fingerprint bump, two platforms regenerated, an amortisation life with no filed value. (D) is: net
cash out of the capital base, take the centre, and drop a validation gate that cannot fail correctly.
The cost difference between the branches is roughly an order of magnitude, and the research is what
revealed it.

### R-44.2 — Two forms of the same estimator remain genuinely distinct, and the probe will say whether it matters

The identity holds **per year**. It stops holding the moment a robust centre is taken, because
`robust_centre(Sales/Capital) × robust_centre(NOPAT/Sales)` is not `robust_centre(NOPAT/Capital)` —
each factor is trimmed against its own dispersion, so the two forms keep different years.

Round 10 reports both with the difference and the retained counts, and adopts neither. Registered
before the numbers: any material gap between them is a statement about **which years each form
trimmed**, not about the economics. If the gap is negligible everywhere, that is a finding too, and it
means the choice does not matter.

### R-44.3 — R-30.1 moves from latent to blocking, and it is now the critical path

The adapter feeds FCFF into the slot `E(t)` where FR-28 requires NOPAT — earnings **before** growth
reinvestment. Today that is armed rather than firing, because with `r` absent every issuer refuses.

Under (D) it fires the day the estimator lands, and it fires **definitely** rather than latently:
`C(t) = E(t)·(1 − b(t))` subtracts reinvestment explicitly, and FCFF has already subtracted it once.
Reinvestment charged twice. The sequencing recorded in the original plan — *"ROIC alone (base still
FCFF) → charged twice → the understatement worsens"* — is precisely the failure mode.

So the base change and the estimator land **together or not at all**, and the base change is now
upstream of everything else in this branch. This was written down before the fork existed and it did
not need re-deriving; it needed re-reading.

### R-44.4 — Round 9's decomposition shipped a column whose framing is now known to be wrong

R-43.3 established, on the record, that share-based compensation is an in-kind expense rather than
investment, and is not added back. Round 9's probe prints `sbc/NOP` as a **correction term to
realized `b`**, which is the one treatment the source names as wrong, and it is committed at `9d4ebc0`
where a future reader will find it.

The measurement is not wrong — the ratio is what it is, centre +0.101. The **framing** is. A
diagnostic that invites a reader to apply a correction the literature rejects is a latent defect of
the same species this effort has been cataloguing, and it is cheap to fix: relabel the column as a
descriptive magnitude and state in the probe's own output why it is not a correction. Registered as
work, not done here.

### R-44.5 — The bounds check has no number in the literature, and I am not inventing one

The discipline (D) substitutes for the failed validation is Damodaran's:

> keep track of the imputed return on capital ... to ensure that it stays within **reasonable bounds**

He does not say what the bounds are. The nearest thing found is a doctrine rather than a threshold —
*"For most companies, ROC should equal the cost of capital unless there is a lasting competitive
advantage"* — and turning that into a numeric gate would be a modelling choice with an author-chosen
constant, which is the exact class of quantity six rounds have been spent removing.

**So the bound is the guard that already exists.** The Core refuses `r <= 0` and refuses a terminal
growth at or above the discount rate. Those are arithmetic, not economics, and they are already
contract. Nothing tighter goes in without Juan choosing it as a policy, registered as a policy, with
the number written down before any issuer is scored against it.

The ROC-toward-cost-of-capital doctrine is registered here as a **separate, later** question. It is a
real idea with real support and it belongs to whoever decides terminal-regime policy — not to this
branch, and not smuggled in as a measurement.

---

## R-45 — Round 10 verified. The pre-registration passes on the deciding definition, and the probe found the next problem on its way past.

Shipped at `9919449` on `r10`, over the verified base `9d4ebc0`. Suite `566 / 4 / 26` — the extra ignored
is this probe. `published_value_regression_gate` green, golden fixture unmodified, the network-rewritten
high-signal fixture left unstaged. One file staged by path.

### R-45.1 — P1 and P2 resolved, and P2 resolved decisively

| | `gross` | `oper` (deciding) |
|---|---|---|
| **P1** qualifying issuers, floor 14 | 21 of 28 | **20 of 28** |
| **P2** positive `Sales/Capital` centre | 21 of 21, 0 refused | **18 of 20, 2 refused** |
| **P2** positive realized `b` centre, same years | 3 of 21 | **4 of 20** |

The route is not dead by supply. And the property realized `b` lacked is present: **no issuer under
either capital definition produced a resolved non-positive `Sales/Capital` centre.** The two refusals
under `oper` are `DAL` and `SLB`, both `InsufficientObservations` after trimming a three-year series —
a supply refusal, not a sign. Against 18 of 20, realized `b` is positive for 4.

That is the whole argument for the branch, and it needs no threshold to state: the quantity (D) divides
by has an identified sign on this cohort, and the quantity Rounds 7–9 divided by does not.

**P3 lands the same way and also needs no threshold.** Within-issuer relative dispersion is smaller for
`Sales/Capital` than for realized `b` for **all 20** `oper` issuers and **20 of 21** `gross` (COF the
lone exception). A unanimous ordering is a statement; a cutoff would have added nothing to it and would
have been mine rather than the data's. The probe declares no winner and neither does this ruling —
but 20 of 20 in one direction is reportable exactly as it stands.

### R-45.2 — The cost of netting cash, counted rather than assumed

`COF` leaves entirely: a bank, filing neither cash concept, 16 of 16 years dropped. Four more shorten —
`SLB` 14→3, `OMC` 17→12, `PG` 14→10, `MSFT` 16→15, `DVN` 10→9 — and `MSFT` loses one further year to
capital going non-positive after netting. That is the price of measuring operating capital instead of
gross capital, and it is printed per issuer rather than inferred.

**A contract-shape limitation surfaced and is registered rather than worked around.** The cash concept
resolved to plain cash for 24 of 28 issuers; only `APH`, `GOOGL`, `MSFT` and `AMZN` filed
`CashCashEquivalentsAndShortTermInvestments`. So `oper` in practice nets *less* than the definition
asks. The builder did not paper over it: `select_one_equivalent` **selects** rather than sums, so
adding a marketable-securities qname would have **replaced** cash in every year both were filed rather
than adding to it. The direction of the residual error is stated and it is the safe one — under-netting
leaves capital higher and the ratio lower, so it errs toward `gross` and never past the operating
definition.

The fix, when the driver lands for real, is a **composition** in the mould of `extract_total_debt`
rather than a `select_one_equivalent`. That is a wave decision, not a probe decision, and it is written
here so it is not rediscovered.

### R-45.3 — P5 is not negligible, and it is not negligible on the anchors

`gap = prod − roic` centres at **−0.0032** (`gross`) and **−0.0050** (`oper`). Third decimal for most
issuers. But it reaches **0.202 (AMZN, `oper`)**, **0.091 (MSFT, `oper`)**, 0.070 (COF), 0.061 (PG) and
0.057 (AMZN) — material against return levels of 0.1–0.3, and landing on three of the four anchors.

As registered before the numbers, the two forms are equal year by year, so the gap is a **trimming**
difference: which years each centre kept. The kept counts show it directly — `gross` PG keeps 11 years
for turnover, 14 for margin and 10 for the one-ratio form.

So the choice between `prod` and `roic` is a live one with different economic results on the names that
matter, and **nothing in this probe decides it.** Registered as open.

### R-45.4 — The probe found something it was not looking for, and it outranks everything above

`MSFT` reads turnover **3.305** under `oper` against **0.808** gross, and a one-ratio return of
**0.815** against **0.224**. `AMZN` goes 3.923 → 9.741. Those two also carry the highest `disp s2c` in
the entire `oper` set — 0.384 and 0.414, against 0.05–0.22 for every other issuer.

An 0.815 return is the pathology the original plan named in its own words before any of this began:
*"AAPL is the counterexample: ~700B of buybacks have shrunk book equity to ~57B, so its book ROIC reads
~75% and growth gets credited nearly free."* At `r = 0.815` the retention charge keeps 88% of earnings;
at a plausible 0.33 it keeps 70%. Not free, but a several-fold error in the one quantity FR-28 divides
by.

**I have a hypothesis and I am not recording it as a finding.** The series run to nineteen years, and an
issuer whose net-of-cash capital was small a decade ago and large now would have its centre dominated by
years that no longer describe it. Damodaran's prescription is the ratio *"at its **current** level"*,
and a centre over nineteen years is not that. If it holds, it is not repairable by choosing a different
estimator — it is a **window** question and it sits upstream of the entire branch.

Round 11 measures it and chooses nothing: the full annual series per issuer under both definitions,
plus `latest`, `centre`, `latest/centre`, and the first and last year each series spans. **No window is
computed, proposed or hinted at** — not a trailing-N, not a recency weight, not a "last five years"
column. Any N chosen now would be chosen *after* seeing that `MSFT` and `AMZN` are the issuers it would
help, which is R-41.5's post-hoc threshold with a new name.

That sequencing is not caution. R-40.1 asserted a mechanism from reading, was wrong on its own algebra,
and came one ruling from adding a contract driver and regenerating two platforms' policy files. The rule
that earned it is **measure the mechanism before building against it**, and it applies to the
Orchestrator's hypotheses first.

### R-45.5 — What is decided, what is open

**Decided by measurement:** the sales-to-capital quantity is supplied (20 ≥ 14) and sign-identified
(18 of 20 resolved, zero resolved non-positive) where realized reinvestment is neither. The branch
survives its own killing condition.

**Open, and none of it is mine to settle:**

1. The **window** — Round 11 measures, nobody has chosen.
2. `prod` **vs** `roic` — R-45.3, different results on three anchors.
3. The **cash driver's operator** — composition, not selection (R-45.2).
4. **R-30.1 / R-44.3** — the FCFF base feeds the slot FR-28 requires NOPAT for. Under (D) the retention
   charge subtracts reinvestment explicitly and FCFF has already subtracted it once. **Blocking**, and
   upstream of the estimator: base and estimator land together or not at all.

Nothing above is a reason to publish a value yet, and the gate still holds either way: all twenty pinned
issuers refuse today, so the first wave that publishes moves them visibly, by name, in a failing test.

---

## R-46 — Round 11 verified. The window hypothesis survives for one issuer, dies as a general claim, and a worse defect surfaced beside it.

Shipped at `21d48b3` on `r10`. Suite `566 / 4 / 26`, gate green, golden fixture untouched, nothing
pushed. The probe printed the annual series and **chose no window**, which is what it was sent to do.

### R-46.1 — The hypothesis, graded honestly in three parts rather than one

R-45.4 recorded a suspicion: that a centre taken over nineteen years describes an issuer that no
longer exists. The series grades it three different ways and the probe kept them separate.

**MSFT — supported, cleanly.** `oper` sales-to-capital is a complete sixteen-year series falling
essentially monotonically from 4.3–6.8 across 2010–2015 to **1.030 and 0.965** in 2024–2025. Centre
**3.305**, latest **0.965**, `latest/centre` **0.292**, and **no year was trimmed** — so the centre is
the whole sixteen years and the current regime is roughly a quarter of it. The return behaves
identically: 1.137 → **0.341** against a centre of **0.815**. This is the shape the hypothesis
describes, visible without imposing any window on it.

**AMZN — supported only in a confounded form, and the probe says so rather than claiming the win.**
`oper` runs 10.5–16.7 across 2009–2013, then **nine consecutive absent years** (2014–2022, dropped for
a missing filed marginal tax rate), then 2.938 / 2.406 / 1.824. The centre is five old observations
against three recent ones with the entire middle missing. *"The window is too long"* and *"the middle
is not there"* both predict that row and **this instrument cannot separate them.** Reporting it as a
window effect would be the same class of error the round existed to avoid.

**Not a cohort property.** For the other eighteen qualifying `oper` issuers, `latest/centre` runs
**0.568 to 1.440**, most between roughly 0.8 and 1.2. MSFT at 0.292 and AMZN at 0.187 are the two
lowest by a wide margin — and they are **the same two the dispersion column flagged in Round 10**, so
two independent statistics agree on which issuers are unusual. Under `gross`, MSFT is unremarkable at
0.902 while AMZN is still 0.381: netting amplifies the effect for MSFT but is not what causes AMZN's.

So a blanket window change would be repairing two issuers and perturbing eighteen. That is an argument
against imposing one, and it is an argument the numbers made rather than one I brought to them.

### R-46.2 — The finding nobody was looking for: the trimming rule can excise the present

`PG gross roic` trims **2022, 2023, 2024 and 2025** — every one of its four most recent years — so its
latest *kept* value is 2019's 0.024 against a centre of 0.067. `COF gross roic` trims 2024 and 2025.

This is the standing `|z| > 3` rule doing exactly what it was written to do, applied to an axis it was
not written for. `standardize` trims a **cross-section**: draws from one population, where a point four
deviations out is a bad observation. A **time series** is not that. When an issuer's economics change,
the recent regime is a minority of the series and reads as outlying — so the rule deletes the present
and keeps the past, and reports a confident centre for a firm that has moved.

`OMC oper` is the same failure by a different route: n=12 looks well supplied until you see that the
twelve end in **2020**, because cash resolution stops there. `AVY` (hole 2018–2024) and `TER` (hole
2014–2024) have centres that are mostly pre-hole years — AMZN's situation at smaller magnitude.

**A count of retained years is not evidence that a centre is current.** That is now written down, and
it generalises past this branch: anywhere in this codebase a robust centre runs along time rather than
across a population, the same failure is available. Registered as a latent defect to be swept for, not
swept here.

### R-46.3 — AMZN's hole is the price of a correct decision, and the answer is not to un-make it

The nine missing years are years with no filed marginal tax rate. Round 8 removed the statutory
substitution that used to fill them, and R-41.3 measured what that cost: 76 issuer-years, four issuers
falling out of the comparison entirely. AMZN was on that list with nine.

That decision stands. The argument then was that a default which is usually right is worse than an
absence precisely because it is usually right, and nothing here weakens it. But the cost has now
compounded: **an anchor cannot be estimated across nine of its most informative years**, and the
resulting hole confounds the very hypothesis we needed to test.

The fix is **supply, not fabrication** — recapture, additional qnames for the marginal rate, or an
honest refusal for AMZN until the years exist. It is LD-17's consequence arriving at the estimator, and
it is now on this branch's critical path rather than in the backlog.

### R-46.4 — The window decision, with both parameter-free options and both of their failure modes

This is Juan's. Two options take **no parameter at all**, which is why only these two are on the table.
A trailing-N, a half-life or a recency weight would each require a constant chosen after seeing which
issuers it flatters, and none is proposed here.

**(i) `robust_centre` over the whole filed history** — what Rounds 10 and 11 measured.
*Fails* when the issuer's economics moved: MSFT reads 0.815 against a current 0.341. And it carries
R-46.2 — the trimming can delete the present, as it does for PG and COF.

**(ii) The latest usable year** — literally what the source prescribes: the ratio *"at its **current**
level."* No parameter, no trimming, so R-46.2 cannot occur and AMZN's hole stops mattering (latest is
2025). MSFT reads 0.965 / 0.341, which is the firm that exists.
*Fails* on single-year noise. `HPE`'s latest return is **0.007**; a growth rate above that makes the
retention charge violently negative — the `return-below-terminal` row's behaviour, on an issuer whose
centre says 0.045. One bad filing becomes the estimate.

**(iii) Keep the centre, but refuse when the present was trimmed out** — not an estimator choice but a
refusal rule, and it uses only the `|z| > 3` that already exists. If the most recent filed year is not
in the retained set, the centre does not describe the issuer and no value is published. This converts
PG's and COF's pathology into a refusal rather than a wrong number, and it composes with (i).
*Costs* coverage, and it does nothing for MSFT, whose sixteen years were **all** retained.

**Neither (i) nor (ii) dominates and I am not choosing.** What I will say is that the prescription
behind (ii) was quoted in **R-44.1, committed at `b80525b` before Round 10 returned a single number** —
so preferring it is following a source that predates this evidence rather than fitting a rule to it.
That is checkable in the history, which is the only reason it is worth asserting.

### R-46.5 — Still open, unchanged, and none of it mine

1. **The window** — R-46.4, three options, Juan's.
2. **`prod` vs `roic`** — R-45.3; gap 0.202 on AMZN, 0.091 on MSFT, three of four anchors.
3. **The cash driver's operator** — composition, not selection (R-45.2).
4. **R-30.1 / R-44.3** — the FCFF base in the slot FR-28 requires NOPAT for. Blocking, upstream.
5. **New:** sweep for robust centres taken along time rather than across a population (R-46.2).
6. **New:** AMZN's marginal-rate coverage hole, to be solved by supply (R-46.3).

Every one of the twenty pinned issuers still refuses, so the gate remains armed: the first wave that
publishes moves them by name, in a failing test.

---

## R-47 — The window read in the literature. No source prescribes one, both prescriptions are conditional on a property we can measure, and the measured decay says neither registered option is right.

Juan declined to choose between R-46.4's three options and asked for research, with an explicit
instruction for the case where research does not settle it: *"si no hay info conclusiva entonces hay
que experimentar con todos y elegir la mejor opción o un blend de opciones."* This ruling reports the
read and registers the experiment — its estimators, its grading criterion and its stopping rule —
**before a single number is measured.** That ordering is the whole point (R-41.5).

### R-47.1 — Damodaran's own caveat covers the case, and it says our estimator is already his fix

The prescription quoted in R-44.1 — the ratio *"at its current level"* — is one sentence in a slide
deck. The considered treatment is `normearn.htm`, and it names **two** procedures with a condition
attached to the first:

> **Average the firm's dollar earnings over prior periods** ... If it is applied to a firm that has
> become larger or smaller (in terms of the number of units it sells or total revenues) over time, it
> will result in a normalized estimate that is **incorrect**.

> **Average the firm's return on investment or profit margins over prior periods** ... it allows the
> normalized earnings estimate to reflect the **current size** of the firm.

So the failure he warns about is averaging **dollars** through a change of scale, and the remedy is to
average the **scaled** measure instead. Our estimator already averages the scaled measure — a ratio,
not a dollar amount. Read literally, R-46.4 option (i) **is** his repair, not the thing he is warning
against, and my R-45.4 hypothesis leaned on a sentence that does not carry the weight I put on it.

That is worth saying plainly rather than quietly dropping. But the read does not end there, because
**neither procedure covers MSFT.** Averaging a scaled measure is valid when the scaled measure is
stable and only the size moved. MSFT's *ratio itself* fell 4.3–6.8 to 0.965 essentially monotonically
across sixteen years. The quantity that is supposed to be the stable one is the quantity that moved.
The source's remedy assumes away exactly our case, and it assumes it away **detectably** — the
condition is a property of the series, not a matter of taste.

### R-47.2 — The decay rate is measured, published, and it disqualifies an equal weight

Fama & French, *Forecasting Profitability and Earnings* (J. Business 73(2), 2000), on Compustat
1964–1996:

> in a simple partial adjustment model, the estimated rate of mean reversion is about **38% per year**
> ... mean reversion is **faster when profitability is below its mean and when it is further from its
> mean in either direction**

Take that at face value and apply it to our estimator. A year's information about next year's level
decays by a factor of `0.62` annually. The tenth year back retains `0.62^10 ≈ 0.008` — under one
percent. `robust_centre` over a filed history gives that year **the same vote as last year**.

This is not an argument from elegance and it is not a threshold I chose. It is a published decay
constant, and an equal-weighted centre is inconsistent with it for every issuer in the cohort, not
only the two R-46.1 flagged. Option (i) is not merely fragile where economics moved — it is
mis-weighted everywhere, and the eighteen issuers whose `latest/centre` sits near 1.0 are issuers
where the mis-weighting happens not to bite, which is a different statement from correct.

### R-47.3 — And the same literature disqualifies a zero weight on history

Reversion is real but **incomplete**, and the primary evidence is consistent across three sources.
McKinsey: a cohort starting at 17% ROIC still reads **13% after five years and 12.5% after ten**.
Mauboussin, on 1,000 non-financial firms 1997–2006: **41%** of top-ROIC-quintile firms were still in
the top quintile after nine years and **64%** in the top two; serial correlation in the extreme
quintiles exceeds **80%**. Fewer than 4% held the top quintile in every single year, so the individual
years are noisy while the level persists.

That is the exact profile that makes a single year the wrong estimator too: high year-to-year
correlation says the latest year carries the most signal of any one year; sub-4% run persistence says
it also carries real noise. `HPE`'s 0.007 against a centre of 0.045 is what that noise looks like when
one filing becomes the whole estimate.

**So the literature is not inconclusive — it is conclusive against both registered options.** Option
(i) weights a 99%-decayed year equally with the present. Option (ii) discards the persistent component
that three independent studies measure. Neither is the estimator the evidence describes.

### R-47.4 — What the evidence does describe is Juan's third alternative, and it needs no chosen constant

He offered *"un blend de opciones"* and the research lands there without being steered: an estimate
between the current level and a centre, weighted by **how persistent the quantity actually is**.

The objection to a blend is obvious and it is the one that killed every trailing-N: a weight is a
constant, and a constant chosen after seeing that it flatters MSFT and AMZN is R-41.5 wearing a new
hat. **That objection does not apply when the weight is estimated rather than chosen.** Fama & French
did not select 38%; they regressed for it. This repo already owns that idiom — `probe_growth_persistence_rho1`
measures an AR(1) coefficient instead of assuming one, and `least_squares` is already in the probe
module. A persistence coefficient fitted on the whole cohort by a procedure written down in advance is
a **measurement**, and it is falsifiable: if the fitted coefficient comes back at 1.0 the blend
degenerates to option (i) and the data has chosen it; at 0.0 it degenerates to option (ii).

A fourth option also surfaced, and it comes from R-47.1 rather than from me: both prescriptions are
conditional on the scaled measure being **stable**, so the condition itself can be the selector.
Per issuer, test the series for trend; where there is none, the centre is valid; where there is one,
the centre is not describing the issuer. That is not an author-chosen window either — it is a
measurable property, and it predicts R-46.1's split exactly, MSFT monotone against eighteen issuers
sitting flat between 0.568 and 1.440.

### R-47.5 — "Elegir la mejor opción" needs a criterion, and the obvious one is forbidden

This is the part of the instruction that cannot be executed as written without deciding something
first, so I am deciding it here and in the open.

Running all four estimators and picking the best requires a definition of best. The criterion sitting
right there — *whichever lands closest to street* — is the one thing this entire effort is not allowed
to use. It is forbidden as a clamp, as an optimand and **as an acceptance criterion**, and selecting an
estimator by it is the third of those. It would also be self-defeating: an estimator chosen to match
street cannot afterwards be evidence about street.

**The criterion is out-of-sample forecast error, and it is registered now.** Form each estimator from
data through year `t` only. Compare it to the ratio the issuer actually realized at `t+1`, `t+2`,
`t+3`. Aggregate the error across every issuer-year the cohort supplies. Lowest error wins.

It qualifies on every count this effort has learned to demand. It never reads a price. It grades the
estimator on the job the model actually gives it — `r` enters `C(t) = E(t)(1 − g(t)/r)` as a claim
about the **future**, so forward accuracy is the property, not backward fit. It runs on the **whole
cohort** rather than an author-selected population, which is `feedback_scope_you_cannot_get_wrong`.
It can fail: if the estimators tie, that is a finding and the choice reverts to Juan on other grounds.
And it is the same experiment Fama & French ran, so the method is not novel and the result is
comparable to a published one.

### R-47.6 — Round 12, pre-registered in full, before any number exists

**Estimators, all four, none preferred:**

| id | estimator |
|---|---|
| `E1` | `robust_centre` over the whole history through `t` — R-46.4 (i) |
| `E2` | the latest usable year at `t` — R-46.4 (ii) |
| `E3` | blend: `w·E2 + (1−w)·E1`, `w` fitted on the cohort by pre-registered regression, never chosen |
| `E4` | `E1`, but refuse where the series through `t` carries a significant trend — R-47.4's conditional |

R-46.4 (iii) — refuse when the present was trimmed out — is a **refusal rule that composes with any of
the four** rather than a fifth competitor, so it is measured as a coverage cost against each, not
raced against them.

**Grading.** Mean absolute error of the estimate at `t` against the realized ratio at `t+1`, `t+2` and
`t+3`, pooled over every issuer-year with enough history to form the estimate and enough future to
score it. Reported per horizon and per capital definition (`gross`, `oper`), and reported **with its
coverage**: an estimator that refuses is not thereby accurate, and a comparison that silently scores
different populations is the R-8.2 error a fourth time. Errors are aggregated with `robust_mean`.

**Registered in advance, so it cannot be reinterpreted afterwards:**

- **P7.** If one estimator's error is lowest at all three horizons under both capital definitions, it
  wins and I will say so. Anything short of that is not a win and will be reported as not a win.
- **P8.** If `E3`'s fitted `w` lands within noise of 1.0 or 0.0, the blend has degenerated and the
  corresponding pure estimator is the answer. The regression's standard error decides this, not me.
- **P9.** If the estimators are indistinguishable, that is the finding — the window does not matter on
  this cohort, and the choice goes back to Juan on coverage grounds alone.
- **P10.** `E4`'s trend test must **not** be tuned. One significance level, stated before running, and
  it is the conventional one rather than a bespoke number.
- **P11.** No result of this round may be used to move `MAX_ABSOLUTE_Z`, any threshold, or any refusal
  path. It selects an estimator and nothing else.

**Choosing nothing else.** The round measures and reports. `prod` vs `roic` (R-45.3) is not settled by
it and stays open. No value is published, the gate stays armed, and all twenty pinned issuers still
refuse when it ends.

### R-47.7 — What I got wrong, since it is cheaper to say than to have found later

R-45.4's hypothesis rested on *"at its current level"* carrying more weight than a lecture slide can
bear, and R-47.1's fuller source says the scaled average **is** the repair for changing scale. The
hypothesis survived Round 11 on the numbers rather than on the citation, and R-46.1 already graded it
honestly — MSFT clean, AMZN confounded, not a cohort property. Nothing built on it, because nothing was
built. That is the sequencing working, not luck.

---

## R-48 — Round 12 verified. Option (i) loses twelve of twelve, option (iii) buys nothing, and the blend turns out to have been shrunk toward the wrong anchor.

Shipped at `244053b` on `r10` over the verified base `21d48b3`. Suite `566 / 4 / 27`, gate green, golden
fixture untouched, nothing pushed. Four panels raced against live filings under the R-47.6
pre-registration, which was committed at `d654ae6` **before** the probe existed.

### R-48.1 — The result the round's own summary under-states

The registered outcomes were reported correctly and P7 was called *not a win*, which is right and is
what the rule says. But P7 is a question about the top two, and it buried the finding that the round
was actually sent to get.

**`E1` — R-46.4 option (i), the robust centre over the whole filed history — loses every single
comparison.** Twelve of twelve: three horizons × two capital definitions × two series, against `E2`.

| panel | h=1 | h=2 | h=3 |
|---|---|---|---|
| `s2c / gross` | 0.1527 vs **0.0919** | 0.1976 vs **0.1208** | 0.2159 vs **0.1571** |
| `s2c / oper` | 0.1880 vs **0.1189** | 0.2724 vs **0.1582** | 0.2777 vs **0.1927** |
| `roic / gross` | 0.4573 vs **0.2621** | 0.5581 vs **0.4668** | 0.5986 vs **0.3898** |
| `roic / oper` | 0.3927 vs **0.2152** | 0.4428 vs **0.4206** | 0.4637 vs **0.4089** |

`E1` on the left, `E2` on the right, common set, primary metric, lower is better. The margin at one
year runs **58% to 82%** worse. Not a tie, not a coin flip, and not confined to the two issuers Round
11 flagged — it is the whole cohort, which is exactly what R-47.2 predicted from a 38%-per-year decay
rate and is the reason that prediction was written down before this ran.

**`E4` — option (iii)'s shape — buys nothing at all.** By construction it *is* `E1` wherever it does
not refuse, so it inherits all twelve losses, and it pays 40–60% of coverage for them. That settles
something the literature could not: the mis-weighting is not a property of a trended minority that a
refusal rule could quarantine. Refusing the trended issuers leaves the remaining centres just as
wrong. **Options (i) and (iii) are both out, and the data ended them rather than an argument.**

### R-48.2 — P8 fired, and it fired differently for the two series, which is an economic result

`phi` was fitted, never chosen, leave-one-issuer-out, through the origin on the Fama–French
partial-adjustment specification.

| series | `phi` | se | leave-one-out range | P8 |
|---|---|---|---|---|
| `s2c / gross` | 0.9785 | 0.0433 | 0.674 – 1.020 | within 1 SE of 1.0 — **degenerate** |
| `s2c / oper` | 1.0121 | 0.0328 | 0.911 – 1.018 | within 1 SE of 1.0 — **degenerate** |
| `roic / gross` | 0.3689 | 0.0849 | 0.342 – 0.431 | not degenerate |
| `roic / oper` | 0.5609 | 0.0964 | 0.261 – 0.719 | not degenerate |

Read it as economics rather than as arithmetic. **Return on capital reverts; capital intensity does
not.** `roic`'s 0.37–0.56 brackets Fama & French's 0.62 on Compustat profitability, which is a
published number our cohort was never fitted toward and lands beside it anyway. `s2c`'s ~1.0 is a
random walk: how much revenue a dollar of capital carries is a fact about the business model and the
technology, and competition does not compete it away the way it competes away a return.

That split is worth more than the horse race. It also means the two series need different treatment,
and any estimator that assumes one persistence for both is wrong for one of them.

**P8 was registered with a consequence, and the consequence fires.** R-47.6 states verbatim that a
degenerate blend hands the answer to the corresponding pure estimator. For `s2c`, under both capital
definitions, `E3` **is** `E2` with a two-percent dash of `E1` — the 0.0911-against-0.0919 style gaps
are that dash, not a contest. So for sales-to-capital the registered rule has already chosen, and it
chose **the latest usable year**.

### R-48.3 — Where the blend is genuinely a blend, it loses — and the reason is a defect in how I specified it

For `roic`, `phi` is not degenerate, so `E3` is a real mixture. And it lands **strictly between** the
two estimators it mixes, every time:

    roic / gross,  h=1:    E1 0.4573   >   E3 0.3652   >   E2 0.2621
    roic / oper,   h=1:    E1 0.3927   >   E3 0.3059   >   E2 0.2152

Monotone interpolation. The fit did not discover a combination better than its parts; it averaged a
good estimator with a bad one and got something in between. Across both `roic` panels `E2` beats `E3`
at **five of six** horizons, losing only `oper/h=2` by 0.0026 — six tenths of one percent.

The reason is not that blending is wrong. It is that **`E3` shrinks toward `E1`, and `E1` is the worst
estimator in the race.** Shrinking toward a contaminated anchor cannot beat not shrinking, whatever the
weight.

And the anchor is contaminated for a reason already on the record. R-46.2 measured that `standardize`,
run along time, can delete the present — `PG gross roic` trims 2022 through 2025, so its centre is
anchored on 2019. Shrinking a current observation toward *that* is shrinking toward a firm that no
longer exists.

**This is a mis-specification I introduced, and it is checkable against the citation I used.** Fama &
French estimate reversion of a firm's profitability toward the **cross-sectional** mean — toward what
other firms earn, because the mechanism is competition. R-47.2 quoted them and then R-47.6 specified
`E3` to revert toward the firm's **own history**, which is a different model with a different economic
claim and no support in the source. The measurement caught it. The blend has not actually been tested
yet; what has been tested is a mis-specified cousin of it.

### R-48.4 — Round 13, and why adding an estimator now is not fishing

The obvious objection: a fifth estimator proposed after seeing four results is how a post-hoc winner
gets manufactured. It does not apply here, and the reason is checkable rather than asserted.

- The correction is a **specification error against a source quoted before the numbers** — R-47.2, at
  `d654ae6`, states the mechanism is reversion toward a mean produced by competition. Anyone can read
  that commit and see that `E3` as built does not implement it.
- **The criterion does not move.** Same out-of-sample forecast error, same three horizons, same common
  set, same whole cohort, same `robust_mean`. R-41.5's failure mode is a threshold chosen to fit
  numbers; nothing here touches the threshold or the grading.
- It is **falsifiable in the same way**: if reverting toward the cross-section also loses to `E2`, then
  blending is finished on this cohort and `E2` wins outright, which is a cleaner answer than the one
  we have now.

**Registered before Round 13 runs:**

- **`E5`** — partial adjustment toward the **cross-sectional** centre: `E5(t) = M(t) + psi·(E2(t) − M(t))`,
  where `M(t)` is the `robust_centre` of the quantity across **all other issuers** at year `t`, and
  `psi` is fitted by the same through-origin least squares, leave-one-issuer-out, exactly as `phi` was.
- **P12.** `E5` is graded on the common set against `E1`, `E2`, `E3`, `E4` at all three horizons under
  both definitions for both series. Same win condition as P7 — all six, or it is not a win.
- **P13.** If `psi` degenerates to 1.0 within one standard error, `E5` is `E2` and the answer is `E2`.
  If it degenerates to 0.0, the answer is the cross-sectional centre alone, which nobody proposed and
  which would be a finding worth stopping for.
- **P14.** `M(t)` uses only issuers other than the one being scored and only data at or before `t`. A
  cross-sectional anchor that peeks at the scored issuer or at the future is not a forecast.
- **P15.** No result of Round 13 may move `MAX_ABSOLUTE_Z`, any threshold, or any refusal path, and
  none of it settles `prod` vs `roic`.

`E1` and `E4` remain in the table as losers rather than being dropped, so the comparison stays honest
and the twelve-of-twelve result stays visible.

### R-48.5 — A limitation of the criterion I registered, which I would rather state than have found for me

Out-of-sample forecast error on the **ratio** is not error in the **value**, and the map between them
is not linear. The retention charge is `1 − g/r`; as `r` approaches zero the charge diverges. An
estimator can carry the lowest mean absolute error on `r` and still produce a catastrophic value on the
one issuer-year where it lands near zero — `HPE`'s 0.007 against a centre of 0.045 is that case, and
`robust_mean` of an absolute error is precisely the statistic that will not see it.

So the race identifies the best **estimator of the ratio** and does not by itself certify the best
**estimator for this model**. I am not repairing that by inventing a bound: R-44.5 already ruled that
the bound is the arithmetic guard that exists — `r <= 0` refuses, terminal growth at or above the
discount rate refuses — and that nothing tighter goes in without Juan registering it as policy with the
number written first. The gap is registered as a latent defect with a trigger (the first wave that
publishes a value from a measured `r`) and a detector (the pre-registration for that wave must predict
the per-issuer value, so a divergent one appears by name in a failing gate rather than in a screen).

### R-48.6 — Housekeeping, and one thing my own brief got wrong

The brief predicted `28 ignored`; the true count is `27` — 26 pre-existing plus this round's one. One
of the twenty-eight `#[ignore]` grep hits is a doc-comment mention rather than an attribute. The round
flagged the discrepancy instead of quietly matching the number it was told to expect, which is the
correct behaviour and worth recording as such.

Option (iii)'s standalone coverage cost, measured rather than argued: the latest observation is trimmed
out of the retained set in **12.7%** of windows (`s2c/gross`), 11.2% (`roic/gross`), 7.9% (`roic/oper`),
5.1% (`s2c/oper`). One window in eight at the top end. That number stands on its own even now that
option (iii) is out as an estimator — it is the rate at which a published centre would be describing a
firm whose present was deleted, and R-46.2's sweep still needs it.

Excluded from the primary metric by name, always for insufficient observations in the whole-history
scale and never silently: `DAL` and `SLB` (`s2c/oper`), `SLB` (`roic/oper`).

### R-48.7 — What is now settled, and what is still Juan's

**Settled by measurement:** option (i) is out, twelve of twelve. Option (iii) is out as an estimator
and survives only as a diagnostic. `s2c` is a random walk and `roic` reverts at a rate that brackets
the published one.

**One round from settled:** `E2` versus a correctly-specified blend. `E2` leads everywhere it is not
tied, and its only losses are hairlines against a mixture that P8 shows is `E2` in disguise.

**Still open, still not mine:** `prod` vs `roic` (R-45.3), untouched by design — Round 12 ran the two
series separately precisely so it could not settle it in passing. The FCFF-in-the-NOPAT-slot base
change (R-30.1 / R-44.3), blocking and upstream. AMZN's marginal-rate supply hole (R-46.3). The
time-axis sweep (R-46.2). Every one of the twenty pinned issuers still refuses.

---

## R-49 — Both reviews in. They contradict each other on a fact, one of them is right, and between them they surfaced two defects larger than the window question.

Sensei returned `revise` with four P0s. Advisor returned `approve` with no blocking findings and three
P1s. That is not a disagreement about quality — Advisor can read the repository and Sensei cannot, by
design, and the split falls exactly along that line. I checked the two claims neither of them could
settle alone.

### R-49.1 — Where they contradict, the one holding the code wins, and the plan is wrong

Sensei's P0-B rests on the premise that the trimming defect is **already shipping**: that
`growth_posterior` runs a robust centre along time and feeds `g0` to every published value today.
It took that from plan.v0, which asserts it in two places.

Advisor grepped it. `growth_posterior` is called from **one** site inside `valuation_core_adapter.rs`
and from its own unit tests. Nothing outside the adapter calls it. That is the same population the
plan itself established two sections earlier — *"the Core has no production consumer at all"* — and
verified independently by the same grep. **The plan contradicts itself, and the section Advisor
verified is the correct one.**

This matters beyond a wording fix. The R-46.2 finding was measured on `probe_sales_to_capital_conditioning`,
an `#[ignore]`d diagnostic. Re-attaching it to a differently-named function with a similar code shape,
without checking whether the two share a caller, is R-40.1's failure exactly: a mechanism asserted from
reading. It has now happened inside a document whose own earlier paragraph disproved it, which is worth
recording as the cheapest possible instance of the lesson.

**Sensei's structural point survives the correction, and I am keeping it.** Nothing publishes through
this centre *today*; the wave that wires the Core to production is the wave that arms it. So the defect
is not live — it is **scheduled**, and it is scheduled for the same wave that mints the first golden
values. That is a better statement of the risk than the one Sensei was given, and it makes the
"sweep and report, fix nothing" disposition *more* clearly right rather than less.

### R-49.2 — The 179 fabricated tax rates are not 179, and the real number is worse than a count suggests

Sensei's P0-A asked how many of the fixture's 179 `marginal_tax_bps == 2100` rows land on rows the
NOPAT base would consume for the twenty pinned issuers, and said the answer decides whether the wave
can proceed as scoped. I measured it.

**All of them.** The deep fixture holds 274 issuer-years and every one belongs to a pinned issuer, so
the question of overlap does not arise — the fixture *is* the gate cohort.

But the count 179 is the wrong instrument, and splitting it by year says something the count hides:

| | count |
|---|---|
| `2100` in **2018 or later**, when the US federal statutory rate **is** 21% | **146** |
| `2100` in **2017 or earlier**, when the US federal statutory rate was **35%** | **33** |

And the control is already in the file. The fixture carries `3500` **59 times** and `3400` **29 times**,
all pre-2018. So the pipeline demonstrably reads the older rate when it is there. **21% in 2009 is not
a house convention; it is a value that disagrees with the rate that existed.**

That reframes the defect in both directions and neither is the framing plan.v0 used. The 146 are
plausibly correct and were being treated as a hazard. The 33 are the ones that cannot be what they
claim, they are spread across **twelve of the twenty pinned issuers including AAPL, AMZN and T**, and
they were being protected by a technique chosen to avoid disturbing them.

Round 13 audits all 33 against the filings, with a ten-row control drawn from the `3500` population so
the instrument has to prove it can distinguish the case that would falsify it. Three permitted
classifications, and the count of *"the fixture holds a number where the source has nothing"* is the
number that decides whether the base wave proceeds. **No rate is substituted, repaired or fabricated
under any classification.**

### R-49.3 — The Core asserts a permanent excess return, and our own measurement now contradicts it

Sensei's P0-C asked whether the terminal segment uses the same implied `r`, unfaded. I read
`unit_value`:

```rust
let terminal_payout = 1.0 - terminal / return_on_capital;
```

`return_on_capital` is a single scalar. Growth fades from `g0` to `terminal` along the path; **`r` does
not fade at all.** Whatever return is measured is asserted for every year to infinity, and it lands in
the terminal payout ratio, which is where a no-horizon integrand puts most of the value.

The arithmetic is not subtle. At `g_inf = 300 bps`, a measured `r = 8150 bps` gives a terminal payout of
**0.963**; the same issuer at a return reverted to a 800 bps cost of capital gives **0.625**. A 54%
difference in the terminal payout, and it runs **one way only** — the higher the measured return, the
larger the overstatement. That is a first-principles objection with no reference to any market price,
so it survives the constraint that killed every other objection of its size.

**And this is no longer a theoretical worry, because Round 12 measured the rebuttal on our own cohort.**
R-48.2: `roic` reverts with a fitted `phi` of **0.37 to 0.56**, bracketing Fama & French's 0.62 on
Compustat. Our own data says the return on capital reverts. The Core holds it constant forever. Two
independent instruments — a published study and our own leave-one-issuer-out fit — agree against the
model as written.

**The two open decisions compound, and nobody had noticed they interact.** R-48 shows the estimator
race pointing at `E2`, the latest usable year, which for a high-return issuer reads the **peak** of a
declining series. Feed that peak into a charge that never fades and the model asserts an issuer's best
year, forever. MSFT's `oper` return reads 1.137 at the start of its series and 0.341 now; whichever end
we pick, holding it to infinity is the assertion, not the estimate.

**I am not fixing this here and I am not choosing a fade.** A fade rate for `r` is a policy constant,
it has no filed value, and R-44.5 already ruled that nothing tighter than the existing arithmetic
guards goes in without Juan registering it as policy with the number written down first. What I am
doing is moving it from unregistered to registered, and stating that it is **upstream of the window
decision** — the window picks a point on a series; this decides whether that point is asserted for one
year or for all of them, and the second question is worth more than the first.

### R-49.4 — What goes into plan.v1, and what does not

**Absorbed, from Advisor:** the `growth_posterior` correction (R-49.1) as a wording fix that changes no
wave's scope; the fixture-enrichment proof moved from a hand-run Python script into the `#[ignore]`d
Rust test itself, because a proof a human pastes into a terminal once is indistinguishable six months
later from a claim nobody checked, and this repo's own idiom is to encode the invariant where the
harness re-runs it; and an explicit fail-closed rule on that enrichment, so a transient fetch error
aborts the run rather than writing a `null` that is indistinguishable from a genuine absence.

**Absorbed, from Sensei:** splitting W1, whose six tasks share no exit criterion and half of which have
no dependents; resolving W5's self-contradictory dependency on W1 — either the coverage measurement
selects the concept set, in which case the "not a contract proposal" disclaimer is false and the set is
a policy that must be registered first, or it does not, in which case W5 is an independent root; the
cash-composition overlap lattice, because "aggregate supersedes parts" is undefined without a declared
containment relation on every pair and double-counted cash moves value **one way only, upward**; and
`r`'s domain rows, which is R-49.3's registration made testable.

**Absorbed with its premise corrected:** the pinned-versus-provisional split of the golden file. Not
because values are being minted from something already shipping wrong — they are not — but because the
wave that mints is the wave that arms, and a value minted under a named open defect should say so in
the file rather than becoming indistinguishable from a validated one the moment it is written.

**Rejected, with the reason:** Sensei's unit vocabulary across the Core's public surface (`Money`,
`Rate`, `Ratio`, so `g/r` cannot silently become `r/g`) is a better type investment than the `E`
newtype and the argument for it is correct. It is also a rewrite of a public boundary in the middle of
a branch that has not yet published a single value, and it protects against a transposition that no
test has ever caught happening. It goes in the register as work with a stated trigger — the first
addition of a fifth rate-shaped input — not into this plan.

**Also rejected:** Sensei's proposal to use the four sign-identified realized-reinvestment issuers as an
independent oracle for W6's pre-registration. The idea is right in shape and I want it, but R-41.4
established that realized `b` is negative for 14 of 21 issuers on this cohort, and a reference that is
not sign-identified on three quarters of the population is not made sound by restricting it to the
quarter where it happens to behave — that is `feedback_scope_you_cannot_get_wrong` with the sign
flipped. If the four are to be an oracle, the case has to be made on why those four are a legitimate
population rather than the ones that survived.

### R-49.5 — Still open

1. **`r`'s fade** — R-49.3. New, unregistered until now, and upstream of the window. Juan's.
2. **The window** — Round 13 closes the estimator race or reports that it does not.
3. **`prod` vs `roic`** — R-45.3, untouched by design.
4. **The 33 rates** — Round 13, and the count of "nothing filed" gates the base wave.
5. **R-30.1 / R-44.3** — FCFF in the NOPAT slot. Blocking, upstream.
6. **R-46.2's sweep**, now correctly scoped as scheduled rather than live.
7. **AMZN's marginal-rate hole** — R-46.3, to be solved by supply.

Every one of the twenty pinned issuers still refuses.

---

## R-50 — Round 13 verified, both tasks re-measured by me. The window question has collapsed into a different question, and the tax audit came back worse than the count suggests.

Shipped at `66ff5e7` on `r10` over the verified base `244053b`. One file, `valuation_probes.rs`.
Suite `566 / 4 / 29`, gate untouched, golden fixture absent from `git status`, nothing pushed.
I re-ran `probe_window_estimator_race_e5` myself rather than reading the round's summary of it, and
I verified Task B's instrument by reading `classify_marginal_tax_row` before reading its answer.

### R-50.1 — `E5` is not a win, it is not close to a win, and the pattern is not the one anyone expected

P12 is registered and it fires: **not a win, for either series.** That is the ruling, and nothing
below softens it. But the five-way common set says *why*, and the why is a structural result rather
than a horse-race result.

Five-way common set, primary metric, lower is better, winner in bold:

| panel | n (h1/h2/h3) | `E1` | `E2` | `E3` | `E5` |
|---|---|---|---|---|---|
| `s2c / gross` h1 | 60 | 0.1527 | 0.0919 | 0.0911 | **0.0907** |
| `s2c / gross` h2 | 50 | 0.1976 | 0.1208 | **0.1204** | 0.1251 |
| `s2c / gross` h3 | 42 | 0.2159 | 0.1571 | 0.1677 | **0.1362** |
| `s2c / oper` h1 | 50 | 0.1880 | 0.1189 | 0.1115 | **0.0940** |
| `s2c / oper` h2 | 39 | 0.2724 | 0.1582 | 0.1566 | **0.1553** |
| `s2c / oper` h3 | 29 | 0.2777 | 0.1927 | 0.1919 | **0.1756** |
| `roic / gross` h1 | 87 | 0.4573 | **0.2621** | 0.3652 | 0.3417 |
| `roic / gross` h2 | 74 | 0.5581 | 0.4668 | 0.4772 | **0.4519** |
| `roic / gross` h3 | 63 | 0.5986 | **0.3898** | 0.4556 | 0.4634 |
| `roic / oper` h1 | 63 | 0.3927 | 0.2152 | 0.3059 | **0.1787** |
| `roic / oper` h2 | 54 | 0.4428 | 0.4206 | 0.4180 | **0.2915** |
| `roic / oper` h3 | 44 | 0.4637 | 0.4089 | 0.4278 | **0.3415** |

**The split is by capital definition, and it is clean.** Under `oper`, `E5` is strictly lowest at all
three horizons for **both** series — six of six, and not by hairlines: `roic/oper` h2 improves on `E2`
by **31%** (0.2915 against 0.4206) and h1 by 17%. Under `gross`, `E5` takes three of six and `E2`
takes the two largest margins.

`E4` remains identical to `E1` on every row, as it must be, and both remain last or joint-last
everywhere. Round 12's twelve-of-twelve stands unchanged.

### R-50.2 — Neither registered stopping rule covers the outcome, and the tiebreak they point to is empty

R-47.5's **P9** registered the case where the estimators are indistinguishable: *"the window does not
matter on this cohort, and the choice goes back to Juan on coverage grounds alone."* They are not
indistinguishable. **P7** and **P12** registered the case where one wins everywhere. None does. The
measured outcome is a third thing neither anticipated: **two estimators, each winning cleanly on one
capital definition.**

And P9's fallback is exhausted before it can be used. `E2` and `E5` have **identical coverage** on
every panel and every horizon — 154/132/111 on `gross`, 119/100/81 on `oper` — because `E5` is built
from `E2` and refuses exactly where it does. There is no coverage argument between them. The
registered tiebreak has nothing to break.

**So the window decision has collapsed into the capital-definition decision.** That is the finding.
`gross` versus `oper` was never raced — R-45.1 named `oper` the deciding definition at `9919449`,
three rounds before any of these numbers existed, and every round since has reported both without
choosing. It is now upstream of the window rather than a presentation detail, and it sits beside
`prod` vs `roic` (R-45.3) rather than being settled by it.

**I am not resolving it by invoking R-45.1.** P12's win condition requires both capital definitions,
I wrote that condition knowing `oper` had already been named deciding, and narrowing to the panel
where the answer is clean — after seeing which panel that is — is `feedback_scope_you_cannot_get_wrong`
with a citation attached. What R-45.1 does license is stating that the definition is a live decision
with a documented prior, not a coin flip discovered today.

### R-50.3 — `psi` replicates Fama & French where the capital definition matches theirs, and only there

| panel | `psi` | se | distance from FF's 0.62 | LOO range |
|---|---|---|---|---|
| `s2c / gross` | 0.9124 | 0.0214 | — | 0.9003 – 0.9217 |
| `s2c / oper` | 0.9379 | 0.0177 | — | 0.9320 – 0.9645 |
| `roic / gross` | **0.5959** | 0.0696 | **0.35 SE** | 0.5427 – 0.7093 |
| `roic / oper` | 0.8769 | 0.0405 | 6.3 SE | 0.7442 – 0.9121 |

`roic/gross` fits a cross-sectional adjustment speed of **40.4% per year** against Fama & French's
**38%**, on a specification written to match their mechanism and a coefficient this cohort was never
fitted toward. That is a replication, and it is the first one this branch has produced on the
*correctly* specified estimator — R-48.2's "brackets 0.62" was the own-history `phi`, which R-48.3
established was reverting toward the wrong anchor.

It replicates under `gross` and not under `oper`, and the plausible reason is that Fama & French ran
Compustat book capital, which is the `gross` definition, not capital net of cash. **That is a story,
not a measurement**, and R-40.1 says a mechanism read off a coincidence is a hypothesis. It is
registered as one, and it is the same hypothesis that would explain R-50.1's split: a cross-sectional
anchor formed over issuers holding very different cash piles is anchoring to a mixture, and netting
the cash out makes the cross-section comparable. If that is right, `oper` should be the better anchor
and `gross` should track the published coefficient — which is exactly the pattern observed. Not
adopted on that basis.

**P13 fires nowhere:** `psi` is more than one standard error from both 0.0 and 1.0 on all four panels.
`E5` is a real estimator, distinct from `E2` and from the bare cross-sectional centre.

Note against P8: for `s2c` the own-history `phi` was degenerate at ~1.0 and P8 handed those panels to
`E2`, while the cross-sectional `psi` is 0.91–0.94 and is **not** degenerate — 1.0 sits 3 to 4 standard
errors away. Both readings are true of the same series and they do not conflict: a random walk in its
own history can still carry a weak pull toward what everyone else earns. P8 eliminated `E3` for `s2c`;
it says nothing about `E5`.

### R-50.4 — The fit rests on MSFT, which is the issuer whose economics moved

Leave-one-issuer-out on `roic/oper`: `phi` all-issuer 0.5609, LOO range **0.2608 – 0.7192**, spread
0.4584 — and the 0.2608 is **MSFT**, alone, the next lowest being OMC at 0.5470. `psi` on the same
panel: 0.8769 all-issuer, MSFT alone at 0.7442 against a 0.8650–0.9121 body. On `roic/gross`, SLB at
0.7093 is the corresponding outlier against a 0.5427–0.6059 body.

So the persistence coefficients this branch has been reading as cohort properties are, on the
`oper` return panels, substantially one issuer's coefficient. That does not invalidate them —
leave-one-out is exactly the instrument that exposes it, and it was pre-registered — but it is a
fragility that has to be carried with the number rather than discovered later. **MSFT is also the
single issuer R-46.1 graded as cleanly supporting the window hypothesis**, and the issuer R-45.4
flagged for an 0.815 return. It keeps being the observation that moves things, which is worth
recording as a standing caution rather than as a finding about MSFT.

One more caveat on `M(t)` itself: the cross-sectional centre for `roic/oper` runs **0.0444 in 2019 and
0.2375 in 2022**, a five-fold move in three years. An anchor that unstable is not obviously an
improvement on no anchor, whatever the forecast error says, and P19-style stability of the anchor was
never registered as a property. Registered now as a gap in the criterion, in the same spirit as
R-48.5.

### R-50.5 — Task B: the instrument was checkable before its answer, and it checks out within issuer

`classify_marginal_tax_row` classifies from SEC's raw facts and reports the pipeline's resolution
**alongside** rather than from it, so a resolution bug cannot manufacture a "nothing filed". `NotMeasured`
is a separate verdict from `NothingFiled`, so an unreachable EDGAR cannot be read as an absent filing —
*"a partial audit is not an audit"*, in the probe's own words. The control-failure branch is written
into the probe and prints instead of the counts if it fires.

And the control sample turns out to have a property stronger than the one I asked for. Eight of the
twelve audited issuers appear in the control **at a different year**:

| issuer | audited years — fixture says 2100 | control year — fixture says 3500 |
|---|---|---|
| AAPL | 2007 | 2008 |
| AMZN | 2007, 2014, 2015, 2016, 2017 | 2010 |
| HURN | 2008, 2009 | 2012 |
| IDCC | 2010, 2011 | 2015 |
| INVA | 2009 | 2017 |
| ROCK | 2008 | 2014 |
| T | 2007, 2008, 2009 | 2016 |
| VICR | 2009, 2012, 2013 | 2011 |

Every control row came back **genuinely filed at 35%**, ten of ten. So for eight of the twelve
issuers, the same issuer files the fact in one year and the audit finds nothing in another — and the
fixture carries 2100 for the second. *"This issuer does not file it"* is dead as an explanation, and
it is dead within issuer rather than by an appeal to the cohort. I did not specify that overlap; the
round's sample happens to have it, and it is worth more than the ten-of-ten headline.

### R-50.6 — The answer is 26, the number under it is 0, and the fixture disagrees with the pipeline on every audited row

| classification | audited (n=33) | control (n=10) |
|---|---|---|
| genuinely filed at the fixture's rate | **0** | **10** |
| filed at something else | 7 | 0 |
| nothing filed at all | **26** | 0 |
| not measured | 0 | 0 |

**Not one of the thirty-three is filed at 21%.** The seven "something else" are filed at **−0.34** — a
negative thirty-four percent, a loss-year reconciliation line — which `reference_rate_bps` correctly
refuses because it accepts only `0.0..=MAXIMUM_REFERENCE_RATE_BPS`. So production resolves `<none>`
on all thirty-three, and the fixture says 2100 on all thirty-three. **The fixture and the pipeline
disagree on every audited row.**

The seven deserve their own line, because they are the good news in this ruling: a negative filed rate
reaches the resolver and is refused rather than taken. The guard works. What did not work is whatever
wrote 2100 into the fixture afterwards.

### R-50.7 — The measurement I ran myself, which needs no network and is worse than the audit

Every one of the deep fixture's **274 rows carries a non-null `marginal_tax_bps`**. The key is never
absent; the value is never null. `effective_tax_bps` likewise, 274 of 274.

Production resolves nothing on at least thirty-three of them. So **absence is not representable in
this column at all.** That is not a data error on thirty-three rows — it is a column with no way to
be right about a missing filing, which puts every value in it under the same suspicion, including the
146 post-2018 rows that R-49.2 called *"plausibly correct"* because 21% is the statutory rate then.

R-46.3 already wrote the argument this triggers, and it was written about the opposite decision:

> a default which is usually right is worse than an absence precisely because it is usually right

The 146 are the half where the default is right by construction, so it cannot be caught. The 33 are
the half where it is falsifiable. It failed **thirty-three out of thirty-three**. Concluding the 146
are fine because they look fine is `feedback_verify_what_an_instrument_measures` — the check that
would clear them cannot fail on the population that would falsify it.

### R-50.8 — What the registered rule forces, and what it explicitly does not

R-49.2 registered, before the audit ran, that the count of *"the fixture holds a number where the
source has nothing"* decides whether the base wave proceeds. It is **26**. **The NOPAT-base wave does
not proceed as scoped.**

What that does not decide is what the base wave becomes. Two repairs are available and **substituting
a rate is neither of them**:

**(a) Regenerate the tax column from the pipeline, letting absence be absence.** Requires the fixture
to carry an optional rate, and drops every unfiled row out of NOPAT. Same shape as R-46.3's *supply,
not fabrication*. Makes the fixture faithful, which is a precondition for every wave that reads it,
not only this one.
*Costs* coverage, on the gate cohort, and the cost is measurable rather than arguable.

**(b) Keep the fixture and refuse on any row whose rate cannot be traced to a filed fact.** Requires
per-row provenance the fixture does not carry, so it is (a) plus a column.

**I am not choosing between them before the cost is measured**, and the cost is one probe away.

### R-50.9 — Round 14, pre-registered in full, before it runs

**Whole fixture. All 274 rows. No sample, no era split chosen by me, both tax columns.** Same
instrument as Task B — classify from raw SEC facts, report the pipeline's resolution alongside but
never from it, reuse Task B's control-failure branch rather than rewriting it.

- **P16.** If the 146 post-2018 `2100` rows come back predominantly *genuinely filed*, the fabrication
  is confined to pre-2018 and the column is repairable by making 33 rows absent. If they come back
  predominantly *nothing filed*, the entire `2100` population is a fill and the column is not data.
  Either answer is reported as the answer; neither is narrated as the one that was hoped for.
- **P17.** The two rows carrying `0` are classified by the same instrument. A `0` where nothing is
  filed is a **fabricated zero** and is named as one — that is the standing constraint's exact
  prohibition, and it would be the second mechanism in the same column.
- **P18.** No rate is substituted, repaired, defaulted, back-filled or written. The fixture is not
  modified by this round. The probe prints a table and asserts nothing.
- **P19.** Report the coverage cost of faithful absence: how many of the 274 rows, and how many of the
  20 pinned issuers, lose NOPAT entirely if every unfiled row drops. **That number is what decides
  between (a) and (b), and it is measured before either is chosen.**
- **P20.** `effective_tax_bps` is audited by the same instrument in the same run. If the effective
  column is faithful while the marginal one is not, that is diagnostic about which fill happened and
  when; if both are filled, the finding is about the fixture rather than about a rate.
- **P21.** Nothing in this round moves `MAX_ABSOLUTE_Z`, any threshold, or any refusal path, and it
  settles neither the window, nor the capital definition, nor `prod` vs `roic`, nor `r`'s fade.

### R-50.10 — Two corrections, one mine and one the round made against me

**Mine.** My brief for Round 13 stated that the five-way common set *"will shrink"* relative to the
four-way set, because it requires all five estimators. It did not shrink — it is identical on all four
panels, 60/50/42, 50/39/29, 87/74/63, 63/54/44 — because `E5` covers exactly what `E2` covers, which
is at least as broad as `E4` everywhere. The round measured it and corrected me rather than reporting
around it. That is the second consecutive round to correct a number I predicted in its own brief
(R-48.6 was the first), and both times the correction was volunteered.

**Not a correction, a confirmation:** option (iii)'s standalone coverage cost reproduces R-48.6
exactly — 12.7% / 11.2% / 7.9% / 5.1% — on an independent run of the same probe. Two runs, same
numbers, so the network path is stable and the earlier figures were not a one-off read.

### R-50.11 — Still open

1. **The capital definition, `gross` vs `oper`** — R-50.2. **New as a blocking decision**, though the
   prior is three rounds old. The window now sits downstream of it.
2. **`r`'s fade** — R-49.3, upstream of everything above. Juan's.
3. **The window** — no longer answerable on its own terms; see (1).
4. **`prod` vs `roic`** — R-45.3, still untouched by design.
5. **The tax column** — Round 14, and P19's number decides the base wave's shape.
6. **R-30.1 / R-44.3** — FCFF in the NOPAT slot. Blocking, upstream.
7. **R-46.2's sweep** — scheduled, not live.
8. **AMZN's marginal-rate hole** — R-46.3, and R-50.6 shows five of AMZN's rows were being papered
   over by the very fill this audit found, so the hole is larger than R-46.3 measured.

Every one of the twenty pinned issuers still refuses.

---

## R-51 — The registered gate fires four times, not once. And Sensei found a defect in my own reading of Round 13.

Round 14 shipped at `ec4b278` on `r10` over the verified base `66ff5e7`. Suite `566 / 4 / 30`, gate green,
golden untouched, fixture unstaged, nothing pushed. One file changed, `valuation_probes.rs`, `525`
insertions. The control was re-run unmodified and came back **10/10** before any audited row was read,
so the instrument earned the right to be believed a second time.

### R-51.1 — Every fabricated row in the fixture is a `2100` row, and two thirds of them are post-2018

The whole corpus, 274 issuer-years, audited against live filings:

| | genuine | filed at something else | **nothing filed** | not measured |
|---|---|---|---|---|
| `marginal_tax_bps`, all 274 | 191 | 7 | **76** | 0 |
| of which: `2100`, pre-2018 (n=33) | **0** | 7 | **26** | 0 |
| of which: `2100`, post-2018 (n=146) | 96 | 0 | **50** | 0 |
| every other claimed value — `0`, `2450`, `2500`, `2810`, `3100`, `3400`, `3500` (n=95) | **95** | 0 | **0** | 0 |

Read the last row first. **Ninety-five for ninety-five.** Every value the fixture carries other than
`2100` is genuinely filed. So the 76 fabricated rows are *all* `2100` rows, and `2100` is not a value
the pipeline sometimes reads and sometimes invents — **it is the fill.**

### R-51.2 — My own pre-registered wording for P16 is right as a proportion and wrong as a conclusion

P16 was registered to key on whether post-2018 rows come back *predominantly* genuine. They do: 96 of
146 is 65.8%, and the probe reported the registered reading — *"the fabrication is confined to
pre-2018."* The agent applied my rule exactly as written and was right to.

**The rule's wording was the defect.** Two thirds of the fabricated rows — **50 of 76** — are
post-2018. The fabrication is not confined to pre-2018; it is *proportionally thinner* there and
*absolutely larger*. A proportion answered the question I asked and the count answers the question
that matters, because a wave consumes rows, not percentages. Recorded as a wording failure of mine,
not of the round.

### R-51.3 — R-49.2 was wrong by fifty rows, and R-50.7 is why we know

R-49.2 called the 146 post-2018 rows *"plausibly correct"* and treated only the 33 as the hazard. That
was wrong: **50 of them have nothing behind them.** R-50.7 then withheld judgement and said the 146
were *unproven*, on the grounds that a column in which absence is unrepresentable cannot clear itself.
That was right, and it is the whole return on `feedback_verify_what_an_instrument_measures`: auditing
only the population where the check *can* fail would have certified fifty fabricated rows.

**P17 closes cleanly and in the good direction.** The two rows the fixture carries at `0bps` are HURN
2016 and HURN 2017, and both are **genuinely filed at 0%**. Neither is a fabricated zero.

### R-51.4 — P19 fires my registered rule, and it fires four times

plan.v1's W4 registered this, before the number arrived:

> if branch (a) leaves **every one of the 18 falsifiable issuers with ≥3 usable years**, take (a) … If
> it does not, **stop and return to Juan** with the per-issuer cost.

Traceable years per issuer, under faithful absence:

| issuer | total | traceable | | issuer | total | traceable |
|---|---|---|---|---|---|---|
| MSFT | 19 | 19 | | INVA | 17 | 12 |
| AAPL | 19 | 18 | | AMZN | 19 | **9** |
| AMSC | 18 | 17 | | MIR | 7 | 6 |
| ROCK | 18 | 17 | | **FIGS** | 7 | **3** |
| CALX | 17 | 16 | | **VRRM** | 8 | **2** |
| HURN | 18 | 16 | | **ADMA** | 15 | **1** |
| IDCC | 16 | 14 | | **VRT** | 8 | **1** |
| T | 19 | 13 | | **APP** | 6 | **0** |
| INOD | 17 | 13 | | *(MH 3/3, BWMN 6/6 — vacuous)* | | |
| VICR | 17 | 12 | | | | |

The round reported **one** issuer losing the rate *entirely* — APP — because that is what P19 asked.
**My decision rule asks a different question, and against it four issuers fall: APP 0, VRT 1, ADMA 1,
VRRM 2.** FIGS lands at exactly 3, which is `MIN_ANNUAL_OBSERVATIONS` with zero slack. And traceable
years are an **upper bound** on usable years — a usable year also needs pretax, which W1a has not
measured — so **four is a floor on the damage, not the damage.**

**The rule fires. W4 stops and the cost goes to Juan.** The falsifiable population drops from 18 to at
most 14, and the pinned cohort keeps twenty rows of which six would be vacuous.

**The rule's stated rationale is void, and I am recording that rather than quietly relying on it.** I
wrote that the stop was needed because *"the choice is then between a narrower cohort and a heavier
fixture."* It is not: branch (b) refuses on an untraceable rate exactly where (a) erases it, so **(b)
does not recover a single one of the four.** The branch question is settled by measurement — **(a)**,
strictly simpler, identically faithful. What the stop is actually for is the question underneath,
which is larger than the branch and is Juan's.

### R-51.5 — The option set at the stop, registered before Juan sees it

Sensei's P0-3 makes the point that a coverage shortfall put to a self-declared non-expert as an open
plea is exactly where *"21% is the statutory rate anyway, just keep the post-2018 rows"* comes back and
wins — the one argument this audit disproved. So the options are enumerated first:

1. **Accept the narrower cohort.** Four issuers go dark, the falsifiable population is ≤14, and every
   downstream count in plan.v1 that says 18 says 14.
2. **Compute NOPAT at the effective rate instead of the marginal rate.** Coverage is dramatically
   better — 13 untraceable rows against 76, **no issuer lost entirely**. It is a real economic
   position with a literature behind it, and it is **not** free; see R-51.6.
3. **Widen the pinned cohort** with issuers that do file the rate. A cohort change, and it needs
   registering before the replacements are chosen, or it is selection.

**Not among the options, and it is registered as excluded:** restoring a statutory default, in any
year, under any name. That is the fallback R-41.3 deleted, and it is what the audit measured as
seventy-six rows of fabrication.

### R-51.6 — The effective rate is not a free alternative, and I checked the code rather than the summary

`edgar.rs:1457-1464`:

```rust
let tax_rate_bps = match (by_year(&tax, v.year), pretax_income_dollars) {
    (Some(tax_expense), Some(pretax_income)) if pretax_income.abs() > 0.0 => Some(
        ((tax_expense.abs() / pretax_income.abs()) * 10_000.0)
            .round()
            .clamp(0.0, 3_500.0) as i32,
    ),
    _ => None,
};
```

There is **no filed reference tag for `effective_tax_bps` at all** — production computes it. Two
defects in four lines, both in the production path:

- **`.abs()` on both terms destroys the sign.** A loss year with a tax benefit — negative pretax,
  negative tax — reads as a *positive* effective rate of the same magnitude. This is the same class as
  LD-1 / R-7.3, which cost this effort a round when it was `interest` being abs-ed.
- **`.clamp(0.0, 3_500.0)` replaces a measurement with a boundary value.** A genuinely-measured rate
  outside `[0%, 35%]` is silently rewritten to `0` or `3500`. That is an **output clamp on a measured
  quantity**, which is on the forbidden list by name, and it is the reason 261 of 274 rows "reproduce":
  a clamp reproduces well because it discards the cases that would not.

So option 2 trades a fabrication problem for a clamp-and-sign problem. **I am not recommending it and I
am not ruling it out** — it is a genuine second design with a different economic result and no test
that decides between them, which is category (a). Registered as **LD-19** either way, because the clamp
and the `.abs()` are wrong regardless of which option Juan takes.

### R-51.7 — Sensei returned `revise` with six P0s. Four land, one is a false dilemma with a good fix, one needs measuring before I absorb it.

**P0-4 lands, and it is the most valuable finding in the review.** After branch (a) the tax columns are
the resolver's own output frozen to disk, so the fixture certifies nothing about the resolver — a
tautology gate. The audit bought external truth at real cost and regenerating without pinning it throws
that away. Sensei believed the external truth was 43 rows; **Round 14 makes it 274**, which makes the
fix both stronger and cheaper. And *"an `#[ignore]`d test is documentation, not a gate"* is simply
correct: plan.v1 put the diff proof inside the ignored writer, which is right for the writer and wrong
for the invariant. Both absorbed. So is the atomic-rename point — a fail-closed abort that has already
begun writing is itself the corruption.

**P0-5 lands.** W5 commits a cash-netting convention, and whether cash is netted off capital *is* the
`gross`/`oper` axis, which is H4 and unresolved. A scheduled wave would silently decide a registered
decision, and H4 would then arrive as a choice between the thing already built and rework. Absorbed in
Sensei's own preferred form: **W5 produces a cash quantity per issuer-year and wires it into nothing**,
with whether and how it enters capital registered as H4's. The "total versus excess cash" question
hiding inside it is a second convention with the same upward direction and is registered too.

**P0-3 lands in four of its five parts.** *"Usable"* is undefined and must be enumerated before the
predicate is scored — that is real, and a threshold registered over an undefined predicate is not
registered. Branch (b) had no trigger and therefore was not a branch; R-51.4 deletes it on measurement
instead. The option-set point is absorbed as R-51.5. And **W4-R02's erasure-only invariant is unbounded
above** — "no value changes from one number to another" is trivially satisfied by erasing everything —
so it gains its companion: **the erased set must equal the resolver's refusal set exactly, not be a
superset.**

The fifth part I contest. Sensei reads the 18 as *"author-selected"* and says the plan does not name
which two were removed or why. **plan.v1 §1.1(c) names them and gives the mechanism**: MH and BWMN
refuse `not_reported` *upstream* of the return check — MH has 3 annuals, so 2 growth transitions, and
`standardize` refuses below 3 — so they cannot be moved by a base change and their green rows are
vacuous. That is measured, not chosen. But the *consequence* Sensei draws is right and costs nothing:
**state the rule over all 20.** Both have full coverage (MH 3/3, BWMN 6/6), so nothing hid there this
time, and that is luck rather than design.

**P0-1 is a false dilemma with a fix worth taking anyway.** Sensei argues the value-neutrality proof —
gate green plus golden absent from `git status` — is either green-by-construction (if the Core is not
in the published path) or else the trimming defect is live. **Neither.** The gate is bidirectional: it
pins the legacy engine's published cents *and* the Core's refusal reasons. A Core change cannot move
the first — correct, and that is the point — but it can absolutely move the second, and W4 changing the
base is precisely a change that could flip a reason. So the instrument *can* go red on the population
that would falsify it. What has never been *shown* is that it does, and that is the part of Sensei's
objection that survives, applied to my own proof. **The calibration is absorbed**: before a wave relies
on the gate, apply a known reason-moving mutation, record the gate going red, revert. Plus the one-line
statement of what the gate covers — twenty pinned issuers — and which two anchors it cannot see.

**P0-2 is the right question with arithmetic that does not transfer, so it gets measured before it gets
absorbed.** Sensei's floor — `max|z| = (n−1)/√n`, so `MAX_ABSOLUTE_Z = 3.0` cannot fire below n≈10 — is
derived for a **mean/SD** standardization. Ours is **median location, MAD × MAD_TO_DEVIATION scale**,
and MAD does not bound z the way SD does: on `{1, 2, 3, 4, 1000}` the median is 3, the MAD is 1, and
the outlier's z is roughly **672** at n=5. So the floor may not exist at all in the form stated.

But the *worry* is real and Sensei named its true shape in a parenthesis: **small-n MAD can be exactly
zero**, and on `{0, 0, 1000}` it is. What `standardize` does at a zero scale is unmeasured, and the
answer decides whether W3's sweep can return a meaningful null, whether "≥3 usable years" is a floor on
anything, and whether H2's *"they differ only in which years each centre trimmed"* is even a live
mechanism. **Round 15 measures it.** Deriving a floor by reading, from a formula for a different
estimator, and then re-scoping three waves against it, is R-40.1 exactly — and R-49.1 already cost this
effort one instance of a mechanism asserted from reading inside a document whose own earlier paragraph
disproved it.

### R-51.8 — P0-6 is right, and it is a defect in my own R-50.2

This is the finding I would least like to be true and it is the one I am most confident about.

R-50.2 concluded that the estimator split is *"entirely by capital definition"* by comparing `E5`'s
`roic/oper` h1 of **0.1787** against its `roic/gross` h1 of **0.3417**. The five-way common set makes
estimators comparable **within** a panel. **It does nothing across panels** — `gross` coverage is
154/132/111 and `oper` is 119/100/81, a 23–27% smaller sample self-selected to the issuer-years where
operating capital is computable. So the comparison is confounded: a lower error under `oper` is equally
consistent with the `oper`-computable subset simply being easier to forecast.

**I built a common set precisely to defeat this class of error, and then made it one axis over.** The
per-panel results stand; the cross-panel conclusion does not. R-50.2's *finding* — that the window
question collapsed into the capital definition — survives, because it rests on `E5` sweeping six of six
*within* `oper` and `E2` taking the largest margins *within* `gross`, both of which are within-panel.
What does not survive is any claim that `oper` is the better definition. **H4 is not decidable from
that table**, and Round 15 re-races it on the intersection.

Sensei's coherence point stands alongside it: `E5` wins on `oper` while its own `psi` replicates Fama &
French only on `gross` (0.5959 against 0.62, 0.35 SE — versus 0.8769 on `oper`, 6.3 SE away). Adopting
`oper` means adopting an estimator whose speed parameter contradicts the only external anchor
available, on the panel chosen for its accuracy. The benign explanation — `oper` capital is a smoother
denominator, so the ratio is more autocorrelated, so partial adjustment fits better *mechanically*
while measuring less economics — is testable and untested.

**And the largest one: `g = b·r` may not describe this cohort.** R-41.4 measured realized
`b = ΔIC / ΣNOPAT` **negative for 14 of 21 issuers**. I used that to reject Sensei's four-issuer oracle,
correctly, and then filed it. Sensei is right that the anomaly is the more valuable object: the
retention identity is what the entire model is built on, and if two thirds of the cohort has negative
realized reinvestment then either the accounting IC series is dominated by buybacks, impairments and
lease-standard changes rather than by reinvestment, or **the identity does not hold here** — in which
case W6 would estimate `r` for a relation that does not describe the firms, and that is a live
candidate root cause of *"los numeros no dan acorde al street."* **No numeric policy constant should be
registered while the model's core identity is measured to fail on two thirds of the cohort.** Round 15
decomposes it.

### R-51.9 — I was wrong that the `r == wacc` row admits no isolating check

plan.v1's §4 hole 3 recorded, honestly, that `reverted-return-flat-path` sits at `r == wacc` where no
arithmetic branch is unique to it, so no *code mutation* turns only that row red. Sensei accepted the
honesty and then produced the check by a route I did not look down: an **analytic invariant** rather
than a mutation.

    V = base·(1 − g/r)/(w − g)     at r = w:     base·((w − g)/w)/(w − g)  =  base/w

**At `r == wacc`, value is `base/wacc` — independent of `g`, and independent of any fade path.** So
`1250.00` is `100/0.08`, a closed form rather than a recorded number, and **a second row at `r == wacc`
with a different `g0` must produce the identical value.** That row is uniquely sensitive: only the
terminal-payout or fade arithmetic can break g-invariance at `r == wacc`. The isolation hole closes,
and the same row is the perfect **H5 sentinel** — whatever fade is eventually chosen, this row must not
move, by identity rather than by preference. Absorbed, along with asserting the **span** (1926.38 /
1250.00 = 1.541) as a fact beside the two values, so a mutation scaling both leaves the ratio failing.

The habit of writing *"I could not"* rather than manufacturing a green cell is what made this
correction reachable, and it stays.

### R-51.10 — Round 15, pre-registered before dispatch

Three of Sensei's P0s rest on mechanisms nobody has measured on this cohort. They get measured before
plan.v2 is scoped against them.

- **P22 — the trim floor, under the estimator we actually use.** For the real `standardize`: the
  smallest n at which `|z| > MAX_ABSOLUTE_Z` is attainable, and what happens when MAD is exactly zero
  — refuse, divide, or silently keep everything. Then the **n distribution per series** across both
  cohorts, so W3 can report *not measured* rather than *clean* wherever the instrument cannot fire.
  **No threshold moves whatever this returns.**
- **P23 — `gross` versus `oper` on the intersection.** `E2` and `E5`, three horizons, both series,
  restricted to issuer-years where **both** capital definitions are computable. Registered before the
  numbers: if the intersection cannot separate them at any horizon, **that is the answer** — H4 is not
  evidence-decidable and goes to Juan as an economic choice, stated as such, and no panel comparison
  may be used to break the tie afterwards.
- **P24 — the retention identity.** Decompose `ΔIC` for the 14 negative-`b` issuers into its
  components. Registered before the numbers: if buybacks, impairments and standard changes account for
  the sign, `b` measured from accounting IC is not realized reinvestment and must never be used as an
  oracle; if they do not, **`g = b·r` does not describe this cohort** and that outranks every open
  decision including H4.
- **P25 — the effective-rate defects.** Confirm on the cohort how many rows the `.clamp(0, 3500)`
  actually binds on and how many have a sign the `.abs()` pair destroys. LD-19's size, measured.
- **P26.** No result of Round 15 may move `MAX_ABSOLUTE_Z`, any threshold, or any refusal path; may
  select an estimator, a capital definition, or a fade; or may read a market price.

### R-51.11 — Still open

1. **The tax base** — R-51.4's stop. Juan's, with the option set registered at R-51.5.
2. **H4, the capital definition** — and R-51.8 means it is *less* decided than R-50.2 claimed, not more.
3. **H5, `r`'s fade** — R-49.3, upstream of H4, and now with an identity-based sentinel.
4. **H2, `prod` vs `roic`** — R-45.3, untouched.
5. **The retention identity itself** — R-51.8, and it may outrank all four above.
6. **R-30.1 / R-44.3**, FCFF in the NOPAT slot. Blocking, upstream.
7. **LD-19**, the effective rate's clamp and sign loss — new, and wrong regardless of which option wins.
8. **AMZN**, now 9 traceable years of 19.

Advisor's re-review of plan.v1 has not returned. plan.v2 waits for it. Every one of the twenty pinned
issuers still refuses, and no value has been published.

---

## R-52 — Advisor approved plan.v1, and in doing so found that this record had been missing three rulings

### R-52.1 — My brief asked for seven things the role cannot do

Advisor returned **`approve`** with three P1s and no P0s, and opened by restating its charter:
**documentation only — no application source**. My re-review brief asked it to verify `projection.rs`,
`edgar.rs`, the feature file, `manifest.toml` and `DriverAnnual`'s shape. **That is my error, not a
limitation it should have worked around**, and it did the right thing: it did everything checkable from
documentation, arithmetic and history, and handed off five source-level claims explicitly rather than
letting a plausible reading stand in for a read. The handoff list is the correct artifact; I resolve
two of its items below and the remaining three go to the Reviewer.

### R-52.2 — LD-18 should never be minted, because LD-17 already asked this exact question

plan.v1 instructs, twice, to add **LD-18** for the fabricated tax column. **LD-17 already exists** at
`docs/valuation-economic-contract.md:423`, and its registered trigger is *"any wave that re-captures
`core_driver_data_deep.json`, or any claim that the Core-side cohort's tax evidence is audited,"* with
the detector *"counting nulls in the file is the only audit, and today there are none to count."*

**Rounds 13 and 14 are LD-17's own trigger firing, and they returned the answer LD-17 asked for.** So
this is not a new defect — it is an open one answered. Minting a second id would reproduce, inside the
plan written to correct the register's worst instance, precisely the failure §14's preamble warns
about: a register that *looks* complete is worse than one with an honest gap. **LD-17 is updated in
place** with the audit's counts and closed when W4's branch lands, following LD-13's existing pattern.

**This renumbers R-51.6.** The effective-rate defect at `edgar.rs:1457-1464` — the `.abs()` pair that
destroys a loss-year tax benefit's sign, and the `.clamp(0.0, 3_500.0)` that replaces a measured
out-of-range rate with a boundary value — is a genuinely new defect in a different place, and it takes
the next free id: **LD-18, not LD-19.**

### R-52.3 — The `extract_total_debt` precedent is true. I checked rather than handing it on.

Advisor flagged that plan.v1's *"take the max `end`, as `extract_total_debt` does"* is asserted rather
than verified, and that LD-16's text cites `extract_total_debt` as *where the hazard lives* rather than
as a clean precedent — so if it still used `.first()`, the hazard would be **live today** and a larger
finding than W5 plans to write down. That was Advisor's predicted P0 #1 and it was the right thing to
be suspicious of.

`edgar.rs:731`:

```rust
let end = parts.iter().map(|part| part.end).max()?;
```

**The precedent holds.** `extract_total_debt` takes the max `end` over contributors. The predicted P0
does not fire, W5's instruction is correct, and the §14 note that LD-16's hazard *"now has a second
call site"* is accurate as written.

### R-52.4 — This record was missing R-34, R-35 and R-36, and had been for fifteen rounds

Advisor could not resolve plan.v1's deferral of *"the two R-35.5 findings"* and reported that the
rulings file jumps **`## R-33` straight to `## R-37`**, with a single inline mention of R-35 anywhere
in it. I checked. **It is right.** Three ruling sections were absent from the binding record.

They were written and never appended — the sources were sitting in the session scratchpad the whole
time, complete, with the failure most likely being the same empty-output-file fault that has now hit
this effort three times. **Repaired**: R-34 (Round 5 pre-registration, the whole-cohort published-value
gate), R-35 (Round 5 verified) and R-36 (Round 6 pre-registration, LD-13) are inserted in sequence
between R-33 and R-37. The record now carries 52 rulings and the numbering is contiguous.

**R-35.5's two findings, named here so the deferral no longer depends on a citation resolving:**

1. `high_signal_screener_cohort_all_members_pass` is **not** `#[ignore]`d, reaches the network, and
   rewrites a committed fixture on every default `cargo test --lib`. That is the mechanism behind
   `high_signal_screener_observation_2026-08-02.json` appearing modified in every session, and the
   reason every brief in this effort instructs that it be left unstaged rather than reverted.
2. `commands::qa_universe_apply_tests::ensure_symbol_loaded_does_not_grow_active_symbols` is
   **order-dependent** under parallel execution — it failed in one full run and passed in isolation and
   in mine. A flaky test in the default suite degrades every count this effort quotes.

Both remain out of scope and both are now nameable from inside the plan.

### R-52.5 — The lesson, which is about this record rather than about the plan

**Three rulings were missing for fifteen rounds and nobody noticed, including me.** The reason is that
every reference to them was **by id** — *"the two R-35.5 findings"*, *"the same register as R-35.4's
self-contradictory P6"* — and an id resolves in a reader's head whether or not the section exists. The
record's integrity was never checked because it was never *used* in a way that would fail.

That is `feedback_verify_what_an_instrument_measures` pointed at the instrument this whole effort
depends on to remember what it decided. Two mechanical consequences, both cheap:

- **Any deferral or citation carries a one-clause restatement of what it says**, not just the id. A
  citation that cannot be dereferenced silently is not a record.
- **The header sequence is contiguous**, and checking it is one `grep -c`. It now runs before any
  ruling is appended.

Advisor's second lesson candidate said this first and said it more compactly: *citation-by-reference
degrades silently and nobody notices until a reviewer tries to resolve it.* This is the first time in
the effort a reviewer tried.

---

## R-53 — The tax source researched, and the finding is not about the seventy-six missing rows

### R-53.1 — Round 15 stalled on transport, and the output file was empty for the fourth time

Round 15's agent stopped with *"no progress for 600s (stream watchdog did not recover)"* and its
captured output file was **zero bytes** — the same fault named in R-52.4, now on its fourth
occurrence in this effort. The visible tail was a single line: *"Now insert the P24 ad hoc drivers
after this block."*

**I did not trust that line.** I measured the worktree instead: base `ec4b278` (R14, correct),
`valuation_probes.rs` at +80/-12, and `cargo test --lib --no-run` **finished clean** — only
unused-constant warnings for the four new drivers and the two new `CapitalYear` fields. So the
stall was transport, not logic, and what had landed was coherent: `equity`/`debt` split onto
`CapitalYear` so P24 can read the two sides of `ΔIC` separately, four ad hoc drivers
(`NET_INCOME_LOSS`, `COMMON_STOCK_DIVIDENDS`, `SHARE_REPURCHASES`, `IMPAIRMENT_CHARGES`), and
`cash_and_marketable_by_year` hoisted out of `assemble_window_race_panels`.

The agent's own last line was **already false when it was written** — the drivers were in at lines
514–557. Resumed with the verified checkpoint stated as fact, and with an explicit instruction to
read before editing so the block is not inserted twice. **A stalled agent's final line is a
statement of intent, not of state**, and the two diverge exactly when the stall is what interrupted
the write.

### R-53.2 — The contiguity check I registered one ruling ago is the wrong predicate

R-52.5 registered: *"the header sequence is contiguous, and checking it is one `grep -c`."* This is
its first run. It reported **three gaps** — `4 → 6`, `6 → 5`, `5 → 7`.

**The record is complete.** 52 distinct ids, 52 headers, max 52, nothing missing, nothing
duplicated. R-5 and R-6 are merely **transposed in the file**: R-6 sits at line 146 and R-5 at line
197. Adjacency-of-successive-headers is not the property that matters; **set completeness is**. A
check that reports three failures on a sound record is worse than no check, because the next reader
learns to scroll past it — which is the same silent degradation R-52.5 was written to stop.

**The registered check is replaced** by: every id in `1..max` present exactly once, duplicates
reported separately, ordering reported as informational only. The transposition stays as it is —
moving fifty lines of settled prose mid-effort is churn, and the id is how every citation resolves.

This is `feedback_verify_what_an_instrument_measures` firing on an instrument I built **to enforce
that same lesson**, one ruling after writing it. The failure mode is not carelessness; it is that
the cheap version of a check is cheap precisely because it measures something adjacent to what you
meant.

### R-53.3 — What is actually available, having gone and looked

Juan's instruction was **"encontrá una fuente de datos confiable online"** — supplanting all three
options R-51.5 registered, none of which was *go get the data*. Four sources examined:

| source | grain | verdict |
|---|---|---|
| Damodaran, marginal tax rates by country | **country**, single Jan-2026 snapshot | **unusable** — no time series, no issuer |
| Graham (Duke), simulated marginal rates | **firm-year**, 349,722 obs, 1927–2024, 27,471 firms | authoritative, **not droppable in** |
| US statutory schedule | public law, by year **and taxable-income bracket** | derivable, but see R-53.4 |
| SEC XBRL frames / untried tags | issuer-year, with provenance | **not yet asked** — see R-53.5 |

Graham's is the academic gold standard, validated against real return data. It fails on four
independent counts, any one of which is disqualifying on its own:

1. Keyed to **`gvkey`** (Compustat). Our corpus is ticker/CIK. The crosswalk needs a licensed source.
2. The rates are **after-financing** — they already embed the interest deduction. Our construction
   is `(pretax + interest) × (1 − t)`: we add interest back *then* tax it. Composing those
   **double-counts the deduction**. This is a modelling incompatibility, not an access problem, and
   it would have survived every licensing fix.
3. 11 GB, ending 2024; our corpus reaches 2025.
4. Terms of use unstated; the author asks to be contacted.

Item 2 is the one worth carrying: **the right-looking number from the right-looking source is still
wrong if it is defined against a different formula than ours.**

### R-53.4 — The tag is the federal statutory rate. That is a bigger finding than the missing rows.

Production reads `EffectiveIncomeTaxRateReconciliationAtFederalStatutoryIncomeTaxRate`. By its own
name that is the **statutory federal** rate — not the issuer's marginal rate. Damodaran measures the
US marginal rate at **25–27% once state and local are included**, against the tag's 21%.

**So the 191 genuinely-filed rows may be measuring the wrong quantity too.** A 21% rate where 25–27%
is economically true **overstates NOPAT for every issuer**, whether or not the row was filed. The
defect is not 76 rows of coverage; it is a candidate 274-row definitional error, and the 76 are a
subset symptom.

Corroboration that the tag is not a bare public constant: the fixture carries **3400 in 29 rows and
3500 in 59**, both pre-2018. That is not noise — the pre-TCJA schedule was **graduated 15%–35%**, so
34% is a legitimate bracket determined by the issuer's taxable income. The rate is public law *given
the bracket*, and the bracket is issuer-dependent.

This reframes W4 entirely and it is **not mine to settle**. R-51.5's exclusion stands verbatim.

### R-53.5 — Round 16: ask SEC the question we never asked it

Round 14 measured **only the qnames in our own `marginalTaxReference` policy list**. That is an
instrument that can only ever report on the tags we already chose. The cheapest, most authoritative,
licence-free, provenance-carrying source is the one already in the pipeline, under tags never
requested: state tax reconciliation, total effective rate reconciliation, foreign-jurisdiction
lines.

Queued as **Round 16**, after Round 15 lands — same file, and adding scope to a running agent is how
briefs get muddied. It answers two things at once: how many of the 76 are filed under another tag,
and whether **any** tag delivers an all-in marginal rate rather than the federal statutory one.

This is *supply, not fabrication* in the form R-46.3 already registered.

### R-53.6 — What stays Juan's

R-51.5's option set is unchanged and its exclusion holds: **no statutory default, in any year, under
any name.** R-53.4 adds a question that did not exist when that set was written, and I am putting it
to him rather than answering it:

> Is a **year-aware, bracket-aware, externally-sourced, provenance-carrying** statutory schedule the
> same thing as the fallback R-41.3 deleted? It differs in every property that made that one bad —
> year-blind, provenance-free, a hardcoded constant. It shares exactly one: **the number does not
> come from the issuer's own filing.**

Whether that single shared property is the disqualifying one is a policy judgement about what the
economic contract means, not a measurement. It goes to him with Round 16's counts, so he decides
with the coverage number in hand rather than ahead of it.

---

## R-54 — Every estimator hypothesis is measured on a population that shares three members with the one the gate protects

### R-54.1 — The overlap is 3 of 20, and I computed it rather than assuming it

Advisor handed off *"the exact 28-member `PROBE_COHORT` overlap"* as unverifiable from documentation.
I resolved it against source and the fixture:

| set | n | members |
|---|---|---|
| `PROBE_COHORT` (`valuation_probes.rs:167`) | 28 | DVN FIS AVY SW COF MPWR APH EME CHTR BKR INTU TER AVGO EPAM T GEHC DAL WDC GOOGL HPE CRM SLB EXE OMC PTC PG MSFT AMZN |
| pinned cohort (`baseline_cohort_2026-07-30.json`) | 20 | AAPL ADMA AMSC AMZN APP BWMN CALX FIGS HURN IDCC INOD INVA MH MIR MSFT ROCK T VICR VRRM VRT |
| **overlap** | **3** | **AMZN, MSFT, T** |
| probe-only | 25 | the rest of the large caps |
| pinned-only | **17** | ADMA AMSC APP BWMN CALX FIGS HURN IDCC INOD INVA MH MIR ROCK VICR VRRM VRT AAPL |

**Seventeen of the twenty issuers whose published values must not regress have never appeared in a
single probe table in this effort.**

### R-54.2 — And it is the estimator work specifically that sits on the wrong population

Which probe walks which population, from source:

- `valuation_probes.rs:1959, 2766, 2914, 3500, 3933` — the **E1–E5 window race, the `gross`/`oper`
  capital panels, the sales-to-capital conditioning, the return-on-capital availability and the
  revenue-persistence probes** all iterate `PROBE_COHORT`.
- `valuation_probes.rs:4812` — **only** Round 14's tax audit reads `DEEP_DRIVER_FIXTURE`, the pinned
  twenty's corpus.

So every hypothesis W6 is blocked on — H1 `prod` vs `roic`, H2, H4 the capital definition, H5 `r`'s
fade — has been measured on 28 large caps, while the gate that decides whether any of it ships pins
20 issuers that are mostly small and mid cap. **The one instrument ever pointed at the gate's own
population is the one that found seventy-six rows of fabrication.** That is not a coincidence worth
passing over.

### R-54.3 — The disjointness is defensible. The silence about it is not.

I am not claiming the two sets should be equal. Choosing an estimator on the same twenty issuers it
will be graded against is **overfitting to the gate**, and a general estimator deserves a broad
population. The design is defensible and I would likely choose it again.

**What is not defensible is that nothing measures the transfer.** Every H1–H5 answer is an
**out-of-sample claim** about the pinned twenty, and the plan treats it as in-sample. Concretely,
plan.v1's W6 requires a pre-registration predicting, issuer by issuer, how the pinned twenty move
when the estimator lands — and it would be written from evidence gathered on **three** of them.
Seventeen predictions would be extrapolation wearing a pre-registration's clothes, which is worse
than no pre-registration because R-34 established the whole-cohort gate precisely to stop
author-selected populations.

The small-cap direction makes it concrete rather than theoretical: the pinned twenty include issuers
with **1, 1, 2 and 3 traceable tax years** (VRT, ADMA, VRRM, FIGS — R-51). `PROBE_COHORT` is 28
megacaps with long, clean XBRL histories. **The estimator will be chosen where data is abundant and
applied where it is scarce**, and the scarce end is exactly where P22's trim floor and the `n < 3`
refusal decide everything.

### R-54.4 — What this changes, registered before Round 15's numbers arrive

1. **W6's pre-registration cannot be written from `PROBE_COHORT` evidence alone.** Either a probe
   runs the chosen estimator over the **pinned twenty** before the pre-registration is authored, or
   the pre-registration is explicitly labelled out-of-sample for 17 of 20 and its predictive claim is
   dropped to a refusal-count claim.
2. **Round 15's P22 is now the most load-bearing probe in the effort**, not the housekeeping item it
   looked like. The trim floor and the zero-MAD case are not edge conditions for this cohort — with
   issuers at 1, 2 and 3 usable years, they are the **modal** case for the gate's population.
3. **P23's intersection must be reported twice**: once on `PROBE_COHORT` where the estimator is
   chosen, once on whatever the pinned twenty support. If the two disagree, the disagreement is the
   result.
4. This is registered **now**, ahead of Round 15 returning, so it cannot be read as a rationalisation
   of whatever it reports.

### R-54.5 — The lesson

`feedback_scope_you_cannot_get_wrong` warned that a pre-registration's population is author-selected
and that a whole-cohort gate is the fix. We built the whole-cohort gate — **and then measured the
hypotheses on a different cohort**, so the gate protects a population the evidence never described.
The guard was real; it was pointed at the output while the input was never checked.

The cheap check that would have caught it on day one is a set intersection over two constants in the
same repository, and nobody ran it for fourteen rounds — including me, and including every review
that read both names without ever comparing them. **Two named populations in one effort must have
their overlap stated the first time both are cited**, not the fifteenth.

A small corroboration that the code was already reaching this way: the Round 15 agent, before it
stalled, had added `load_cohort` to `valuation_probes.rs`'s imports — the pinned cohort loader,
which no probe in the file had previously needed.
