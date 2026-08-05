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
