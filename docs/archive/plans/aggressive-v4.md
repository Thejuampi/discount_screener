# Aggressive V4 — one fact, one bucket

## Context

V3 scores a symbol on four buckets — fundamentals, technicals, forecast, market — and averages
them. Reading the code shows the four buckets are not independent:

| Fact | Counted in |
|---|---|
| forward P/E, EV/EBITDA, P/B | F `multiplePanel` **and** `RegimeFit.kt:302 valueScore` |
| price vs EMA20 / EMA50 / EMA200 | T ladder **and** `RegimeFit.kt:358 trendScore` |
| beta | `lowBetaScore`, `v3BetaRiskHaircut`, `betaHaircutMult` |

An average of four buckets that share inputs is not an average of four opinions. It is one
opinion with extra weight.

Two more defects, both verified on the device:

- **The coverage bonus pays for presence, not agreement.** SNDK's market bucket was 43, *below*
  the 3-bucket mean of 45.33, yet the score rose 7 points, because the bonus went +10 → +15. A
  bucket that disagrees with the others must not raise the score.
- **Multiples are compared across the whole universe.** A utility and a chip maker are ranked on
  the same P/E band, so the model ranks industries before it ranks companies.

Two inputs are downloaded and never scored: `dilutedAverageShares` (`Models.kt:418`) and the
sector name (`Models.kt:258`).

**Outcome.** A new model, `AggressiveV4`, that removes the double counting, pays the coverage
bonus for agreement, and compares multiples inside the sector. V3 stays and stays the default,
so it is the control. And a score journal, so that in a few weeks there is evidence about which
model works — not only which model is cleaner.

**Platform: Android only** (user's call). Windows keeps `aggressive_v3`. Wave 5 leaves a contract
fixture so the later Rust port is bound to these numbers rather than re-derived.

---

## Decisions taken

| Decision | Choice |
|---|---|
| Platform | Android first. No Rust edits in this effort. |
| Default model | Stays `AggressiveV2`. V4 is opt-in, V3 is the control. |
| Proof | Include a backtest — but an honest one. See Wave 4. |
| Overlap measurement | Debug CSV export of the live rows, analysed in `lab/`. |

---

## Setup

```
git worktree add G:/dev/repos/discount_screener-wt-v4 android/aggressive-v4
```

Base: `android/profile-switch-and-refresh-fixes` (3 local commits), which stacks on PR #30.
Verify PR #30 is merged before the branch is opened; if not, rebase later.

---

## Wave 0 — Measure the overlap. This wave is a gate. · `depends_on: []`

**The premise of V4 is that the buckets overlap. Reading the code says they share inputs. It does
not say by how much.** Measure it before building against it.

**Files:** new `app/.../data/debug/ScoreExport.kt`; one action in the System tab
(`DashboardScreen.kt`), guarded by `BuildConfig.DEBUG`.

Dump the rows the Opportunities list already computed — no refetch, no new scoring path:

```
symbol, sector, F, T, Fc, M, base, final, coverage, betaMillis,
forwardPeHundredths, evEbitdaHundredths, priceToBookHundredths,
closeCents, ema20Cents, ema50Cents, ema200Cents
```

Write to `filesDir/score-export-<profile>.csv`. Pull with
`adb exec-out run-as com.discountscreener.android cat files/score-export-sp500.csv`.
No storage permission, no `getExternalFilesDir`.

**Analysis** in the existing Python lab (`lab/`), not in Kotlin:

- **Spearman** rank correlation for all six bucket pairs. Rank, not Pearson — the buckets are
  clamped at ±100 and the tails are flat.
- The share of composite variance each bucket explains, and how much M adds over F and T.
- Repeat the correlation on the *inputs* (P/E rank vs `valueScore`, price-vs-EMA200 vs
  `trendScore`) to confirm the shared inputs are the cause, not a coincidence of ranking.

**The gate.** If `ρ(M, F)` and `ρ(M, T)` are both under 0.3, the de-duplication premise is wrong
and V4 must be re-scoped before Wave 2 starts. Report the numbers either way.

**DONE, 2026-08-11. Gate passed on magnitude — ρ(M,F) +0.381, ρ(M,T) −0.367 — and the gate was
under-specified: it never named a sign, so it passed while its unstated same-sign premise half
failed. Any future gate of this shape must state the sign it expects.** A second reading 100
minutes later reproduced every pair within 0.015. The composite could not name a mechanism, so a
per-term probe followed (`regimeFitTerms`), and Wave 2's market row is revised below on its result.

**What this measurement cannot tell you, stated up front:** it is one market reading. The regime
policy re-weights the market bucket as the regime changes, so a single snapshot bounds the
overlap *today*, not across regimes. It also cannot say which model is *right* — only how much
the buckets repeat each other. Wave 4 is what addresses right.

**Test** (`app/src/test`, Robolectric): the export writes one line per opportunity row and the
header names every column. Assert the row count against the list size, so a silently truncated
export fails.

---

## Wave 1 — Sector benchmarks and one robust centre · `depends_on: []`

Android has no sector benchmarks at all. `grep SectorBenchmark core/src/main` returns nothing.
Windows has `compute_sector_benchmarks` (`engine.rs:1202`), but it computes only P/E and ROE, and
it takes a naked median (`v[v.len() / 2]`). Do not copy that.

**Files:** new `core/.../engine/SectorBenchmarks.kt`, new `core/.../math/RobustCentre.kt`

```kotlin
data class SectorBenchmarks(
    val forwardPeHundredths: Int?,
    val enterpriseToEbitdaHundredths: Int?,
    val priceToBookHundredths: Int?,
    val returnOnEquityBps: Int?,
)

fun computeSectorBenchmarks(details: Collection<SymbolDetail>): Map<String, SectorBenchmarks>
```

Each field is a **robust centre**, never `sorted[n/2]` and never `sum/n`. A sector with fewer than
five usable values yields `null` for that field, and the V4 fundamentals bucket then falls back to
the absolute band V3 uses. A sector of three is not a benchmark.

**Two floors, two questions, both kept.** Five asks whether the sector is big enough to have a level
at all. `robustCentre`'s own three-survivor floor then asks whether enough of it survived the trim to
still speak. A sector of five whose trim removes three fails the second after passing the first, and
that is correct. Five is a choice and is recorded as one: Windows's `compute_sector_benchmarks`
claims three samples in its comment and enforces none in its code, so this is deliberately stricter
than the twin rather than a copy of it.

**A multiple at or below zero is not a price level** and does not count toward the centre. Return on
equity crosses zero honestly and is not filtered.

**Boy scout, in its own commit.** `DriverResolution.kt:121` only.

**`DcfAnalysisEngine.kt:852 medianBps` is out of scope, and this reverses the approved text.** It is
not a naked average: it sorts and takes the midpoint of the middle pair on an even count. It is the
named implementation of a contracted field — `valuation-model-family.json:15` promises
`..._nonneg_annual_median` — and it feeds thirteen call sites in the DCF driver path plus
`classifyDriverRegime`'s thresholds. Swapping a median for a trimmed mean there changes intrinsic
values with no Kotlin gate behind it: the r5 published-value gate is Rust-only, and Android's
`ContractFixtureTest` is two issuers and *bounds* the base value rather than pinning it. That is a
valuation-policy change needing a `modelPolicyVersion` bump and the full bar, not a boy-scout line.

**DONE, 2026-08-11.** `RobustCentre.kt` ships two functions, not one, because there are two
questions: `robustCentre` for a real sample (trims, refuses) and `medianOf` for a handful (no
outlier can be named below three observations). One function that changed rule with the sample size
would be a silent methodology switch.

The advisor's P0, settled here so Wave 2 does not meet it as a surprise: **the composite must call
`medianOf`, not `robustCentre`.** A row holds two to four bucket scores, and `robustCentre` returns
null on nearly all of them — two observations, three that lose one to the trim, and four with three
alike are each pinned as tests.

The boy-scout commit also deleted `DriverResolution.kt:120`'s `averageDebt`, a `sum / n` assigned in
all three branches and read by nothing. The fix for a value nothing reads is to stop computing it.

**Both changes are disclosed as published-value movers.** The median fix moves the cost of debt for
every issuer with an even number of aligned financing periods. Two divergences against Windows are
now open and stay open, because this effort edits no Rust: `driver_resolution.rs:222` still takes
the upper middle rate, and `driver_resolution.rs:41` still carries the unread `average_debt_dollars`.

**Tests** (`core/src/test`, JUnit 5, one assert each)
- The centre of a sector with one extreme outlier is close to the bulk, not dragged to it.
- A sector with four usable values yields `null`, not a centre.
- A symbol with no sector name is absent from the map and does not throw.

---

## Wave 2 — the V4 buckets · `depends_on: [0, 1]`

**Files:** `core/.../model/Models.kt`, `core/.../engine/OpportunityEngine.kt`,
`core/.../regime/RegimeFit.kt`

Add `AggressiveV4` to `OpportunityScoringModel` (`Models.kt:55`). Every existing `when` over the
enum must gain a branch — 17 files reference `AggressiveV3` today, and the compiler names them all.
V3's functions are **not edited**. V4 gets its own, beside them.

**The rule that decides every allocation: one fact, one bucket.**

**REVISED after Wave 0's per-term measurement (2026-08-11). The rule now has a measured boundary:**

> A term whose weight can flip the sign of what it says, by regime, is an **arbitration** and stays.
> A term whose sign is fixed across every stance and is already scored in another bucket is a
> **duplicate** and goes.

`RegimeScoringPolicy.baseForStance` swings `wTrend` from 0.1 (BloodInStreets) to 1.0 (Deploy) while
`wAntiExtension` runs the other way, so `trendAlign` and `extension` invert against each other by
regime. `wQuality` (0.2–1.0), `wValue` (0.2–0.6) and `wLowBeta` (0.0–1.0) never change sign.

| Bucket | V4 holds | V4 drops, and why |
|---|---|---|
| **F** Fundamentals | FCF yield, ROE, growth, D/E, FCF÷OCF, multiples **relative to the sector**, **share-count change** | — |
| **T** Technicals | the EMA ladder, MACD, RSI, volume | — |
| **Fc** Forecast | target upside, DCF margin, recommendation, skew, breadth, uncertainty, freshness | — |
| **M** Market | sector fit, liquidity, **`trendAlign` and `extension` — kept, both of them** | `qualityScore` — ρ(F, t_quality) **+0.783**, same sign in every stance. `valueScore` — ρ(F, t_value) +0.511, same sign in every stance. `lowBetaScore` — the haircut already holds it, ρ(M, t_lowbeta) +0.686. |

**The trend pair stays, and this reverses what the approved plan said.** Measured: ρ(T, t_trend)
+0.822, ρ(T, t_extension) −0.818, and ρ(T, M | t_trend, t_extension) = +0.055 — the pair is the
whole T–M channel, but it reads the observable in *opposition*, and which side wins is the regime's
call. Dropping `trendScore` would have deleted M's only trend-following mode in every regime,
including Deploy where the policy weights it at 1.0. That regression is invisible on any single
snapshot.

**`oversoldQuality` also stays**, and needs its own decision recorded: ρ(T, t_oversoldqual) −0.686
puts it on the anti-extension side of the same dial, and `wOversoldQuality` runs 0.0 (Euphoria) to
1.0 (BloodInStreets). Same test, same answer — an arbitration.

Three concrete changes:

1. **Multiples become sector-relative.** V4's multiple panel scores the symbol's P/E, EV/EBITDA and
   P/B against `SectorBenchmarks`, and falls back to V3's absolute band when the sector has no
   benchmark. `OpportunityContext` gains `sectorBenchmarks: Map<String, SectorBenchmarks>`,
   computed once per snapshot in `DefaultDashboardRepository`, next to the existing caches.

   **ROE gets an additive band, not the multiplicative ramp the three price multiples get.** Windows
   already draws this distinction (`engine.rs:1299-1305`): `median ± bps` for ROE, `× 0.7 / × 1.5`
   for P/E. A percentage-of-median band breaks near zero and inverts below it, and ROE crosses zero.

   **The fallback must be visible, and it is not a follow-up.** Two symbols in one list scored by
   different rules with nothing saying which is the same failure as a refusal rendered as a mute
   dash. Windows already pays for this in one character: `score_fundamentals_v2` labels the
   sector-adjusted metric `"FwdPE§"` and the absolute one `"FwdPE"` (`engine.rs:1303`). It is a
   label convention, not a UI feature, and it ships with the V4 metric tokens in this wave.
2. **Share-count change enters F.** `dilutedAverageShares` (`Models.kt:418`) is an annual series.
   Score the change over the most recent pair: shrinking count is a buyback and scores positive,
   growing count is dilution and scores negative. Missing or single-point series contributes
   nothing and reduces the panel weight — it is not a zero.
3. **The V4 market feature set is narrower.** `SymbolFeatures.extract` (`RegimeFit.kt:202`) gains a
   V4 variant that omits `quality`, `value` and `lowBeta` — **not** `trendAlign`, and **not**
   `extension`. `scoreRegimeFit` is unchanged in shape; the coverage floor of 2 still applies, and a
   symbol that now has fewer than two features reports `InsufficientAssetData` rather than a
   fabricated score.

   **Land each removal as its own commit**, each carrying the correlation that justifies it. Wave 4a
   compares V3 against V4 as whole models; if three removals ship as one change and the pair turns
   out worse, the journal cannot say which removal did it.

**The composite pays for agreement.**

```kotlin
// AggressiveV4 — dispersion replaces the presence bonus
val present = buckets.filterNotNull()
val centre  = medianOf(present)                  // not robustCentre: see below. Never sum/n.
val spread  = meanAbsoluteDeviation(present)
val bonus   = V4_AGREEMENT_BONUS * (present.size - 1) * (1.0 - (spread / V4_SPREAD_FULL).coerceIn(0.0, 1.0))
val haircut = v3BetaRiskHaircut(betaMillis) * betaHaircutMult.coerceIn(0.0, V3_BETA_HAIRCUT_MULT_MAX)
```

**`medianOf`, not `robustCentre`, and Wave 1 proved why.** `present` holds two to four values.
`robustCentre` refuses below three survivors, so it returns null for every row with two or three
buckets, and also for a four-bucket row with three alike — an ordinary row, not a contrived one.
Below three observations no outlier can be named at all, so trimming is not a thing that can be done
here and the middle is the honest centre.

Four buckets that agree earn the full bonus. Four that disagree earn none. `V4_SPREAD_FULL` is the
spread at which the bonus reaches zero.

**Measured on the population it grades, and around the centre production computes**
(`lab/data/overlap-spread-median-2026-08-11.txt`):

| | cohort, 498 rows | **qualified, 61 rows** |
|---|---|---|
| p25 | 11.5 | 17.5 |
| p50 | 16.8 | 22.5 |
| p75 | 22.8 | 27.8 |
| **p90** | 29.0 | **38.5** |
| max | 44.8 | 40.5 |

Take **38.5**, the qualified p90. The approved text said 29.5, fitted on the cohort — the wrong
population. `V4_SPREAD_FULL` grades the rows the user sees, and the qualified rows are markedly more
divided than the cohort: their median row's spread, 22.5, is close to the cohort's *p75*. A cohort-fit
constant would have zeroed the bonus for about a third of the Opportunities list. At 38.5 the most
divided tenth of the list earns nothing and the median row earns about 42% of full. Record both
columns in the commit message.

**The bonus is a hypothesis, not an assumed improvement, and Wave 2 must say so in its own text.**
**Correction (2026-08-11):** the original text here claimed the frozen spec "states that disagreement
between the market dimension and the others is meaningful." It does not — its nearest clause forbids
*claiming* alignment that is not there, and V4 does the opposite of what that forbids. The evidence
doc was corrected; this is the same fix in the plan. M is *built* to disagree with T in anti-chase
stances, and the agreement bonus punishes exactly that. Whether a divided model should score lower is
a hypothesis Wave 4 settles.

Beta appears in exactly one place: the haircut.

**Tests** (`core/src/test`, JUnit 5, invariant style — one assert each)
- Four buckets at the same value score above four buckets with the same centre and a wide spread.
  *This is the SNDK defect, expressed as a test.*
- A fourth bucket below the centre of the other three lowers the final score. Under V3 the same
  input raises it — assert V3 and V4 separately, so the two claims cannot share one mutant.
- A cheap symbol in an expensive sector scores above the same multiples in a cheap sector.
- **Under a Deploy stance, a symbol in a strong uptrend scores higher on the V4 market bucket than
  the same symbol does under Euphoria.** This is the regression trap the per-term measurement
  exposed: nothing else in the suite would notice V4 losing its trend-following mode, because the
  loss only shows in a stance no snapshot happened to hold.
- The V4 market bucket keeps a trend term and an anti-extension term, and they report one stretched
  chart with opposite signs — the same claim `RegimeFitTermsTest` fixes for V3.
- A sector with four members falls back to the absolute band and does not throw.
- Shrinking share count scores above growing share count, all else equal.
- A missing share series reduces the panel weight and does not score zero.
- `betaHaircutMult` of 1.0 and no regime reproduces the V3 arithmetic for the beta term exactly.

**Not in this wave: estimate revisions.** `earningsTrend` is not fetched anywhere on Android —
`grep epsRevisions` returns nothing in both modules. It needs a new Yahoo module fetch, a new
model, persistence and a parity story. It is a wave of its own and it is not what V4 stands or
falls on. Left out deliberately.

---

## Wave 3 — V4 in the UI · `depends_on: [2]`

**Files:** `DashboardScreen.kt`, `DashboardLists.kt`, `DetailScreen.kt`, `MarketDimensionUi.kt`

A fourth chip in `OpportunityScoringModelToggle`. The existing `ScoreBadge`, `MetricToken` and
`formatOpportunityBucket` (`DashboardLists.kt:706`) already handle a ±100 model, so V4 reuses them
unchanged; only the `when` over the model gains its branch.

Detail shows, for V4 only, the agreement term as its own line: `centre · agreement · final`, in the
same place V3 shows `base · context · final`. If the bonus is zero the line says the buckets
disagree — the user should see that the model is unsure, not only that the number is lower.

**Tests** (Robolectric + Compose): the V4 chip re-ranks the list — assert the ordered symbols before
and after, not the state flag. The agreement line renders its three numbers, and states disagreement
when the bonus is zero.

---

## Wave 4 — the backtest, honestly · `depends_on: [2]`

**The constraint that shapes this wave.** There are no point-in-time fundamentals. Replaying history
with today's P/E, today's ROE and today's analyst targets is look-ahead bias, and it produces a
result that looks like proof and is not. So this wave does two separate things, and never mixes them.

**4a — Score journal (starts empty, becomes the real evidence).**

`SQLiteStateStore` gains one table. Each completed scoring pass appends one row per symbol:

```sql
CREATE TABLE score_journal (
    symbol TEXT NOT NULL, scoring_model TEXT NOT NULL, scored_at INTEGER NOT NULL,
    fundamentals_score INTEGER, technical_score INTEGER, forecast_score INTEGER,
    regime_score INTEGER, composite_score INTEGER NOT NULL, composite_score_base INTEGER NOT NULL,
    market_price_cents INTEGER NOT NULL,
    PRIMARY KEY (symbol, scoring_model, scored_at)
)
```

This is the gap found while scoping: `discovery_score` (`SQLiteStateStore.kt:1529`) stores three
buckets and no regime score, and it only covers Discovery. The journal covers Opportunities and all
four buckets, for **every** model the user views — so V3 and V4 accumulate side by side and are
compared on the same days.

Write it where the snapshot is built (`DefaultDashboardRepository.kt:1703`), on the IO dispatcher,
never on the render path. Cap the table by age, not by row count, and log what is dropped.

**4b — Point-in-time retrospective, technicals only, runnable on day one.**

The technicals bucket is the only one computable from history without bias: it reads candles, and
candles are dated. Persist the market daily series into the existing `pricing_candle` table
(`SQLiteStateStore.kt:882`) under a distinct `chart_range` key — the column is free text, so this is
an insert path, not a migration. Today those daily bars live only in `MarketDataRepository`'s
in-memory cache and are lost on exit.

Then a pure-JVM evaluator in `:core`:

```kotlin
fun forwardReturnByDecile(
    scores: List<DatedScore>, candlesBySymbol: Map<String, List<Candle>>, horizonDays: Int,
): List<DecileResult>
```

Score at each historical date from bars up to that date only, hold for 21 / 63 / 126 trading days,
report the forward return of each score decile.

**State the result plainly, including the null one.** If the top decile does not beat the bottom by
more than the spread between deciles, say so. A technicals bucket with no forward signal is a
finding about V3 and V4 alike, and it is worth more than a favourable number.

**Tests** (`core/src/test`)
- A synthetic series where high scores are constructed to precede rises yields a positive
  top-minus-bottom spread. *This is the instrument check: the evaluator can detect signal.*
- A synthetic series with scores shuffled against returns yields a spread near zero. *This is the
  falsification check: the evaluator can also detect no signal.*
- The evaluator never reads a bar dated at or after the scoring date — assert on a series whose
  future bars would flip the answer.

Both directions are needed. An evaluator tested only on signal cannot be trusted to report its
absence, and its absence is the outcome most likely to matter.

---

## Wave 5 — bind the numbers for the later Rust port · `depends_on: [2]`

**Files:** new `shared/contracts/opportunity-v4.json`, validated from
`core/src/test/.../contracts/`

A fixture of `(bucket scores, spread, beta, expected bonus, expected composite)` cases, plus a
sector-relative case for **each of the four `SectorBenchmarks` fields** — `forwardPeHundredths`,
`enterpriseToEbitdaHundredths`, `priceToBookHundredths` and `returnOnEquityBps`. Naming only "the
multiple cases" would silently drop ROE, which is the one field whose band has a different shape and
so the one most worth binding. Kotlin validates it now. When Windows ports V4, the fixture is what
makes agreement a measured claim instead of a second reading of the same prose. No Rust file is
edited in this effort.

---

## Verification

Per wave, from the worktree:

```bash
cd apps/android
./gradlew :core:test
./gradlew :app:testDebugUnitTest --rerun     # --rerun: UP-TO-DATE reports success without running
./gradlew :app:assembleDebug
```

Live, on the device. Profile `qa` (≤20 symbols) for every step except step 1:

```bash
make android-run-qa
```

1. **Wave 0 only, and only because it needs the population.** Run the `sp500` profile once, export
   the CSV, pull it, and run the correlation in `lab/`. This is an explicit, single-run exception to
   the `qa`-only rule (`AGENTS.md:282`) — the overlap cannot be measured on 20 rows.
2. Opportunities → V4. The list re-ranks against V3. Both chips remain, V3 unchanged.
3. Open a symbol whose four buckets disagree. Detail shows `centre · agreement · final`, and the
   agreement term is small.
4. Open a symbol whose four buckets agree. The agreement term is at or near full.
5. Compare a cheap symbol in an expensive sector under V3 and V4. It ranks higher under V4.
6. Toggle the market dimension off under V4. The score changes and the list re-orders.
7. Kill and relaunch. The model choice survives; the journal has rows for the models viewed.
8. Airplane mode, cold start. V4 renders from the warm start, the market bucket reports unavailable,
   nothing crashes.

**The claim that must be shown, not asserted:** Wave 0's correlation numbers, and Wave 4b's
top-minus-bottom decile spread with its null case. Both go in the commit message with the data
they came from.

---

## Risks, stated plainly

- **V4 is uncalibrated on the day it ships.** It removes defects that are visible in the code. That
  is not the same as being more accurate. Wave 4a is the only thing that can eventually settle it,
  and it needs weeks of journal rows before it says anything.
- **Wave 0 can kill Wave 2's premise.** If the buckets barely overlap, the de-duplication is not the
  fix and the plan must change. That is the point of putting it first.
- **`V4_SPREAD_FULL` is fitted to one snapshot.** A constant chosen from a 61-row qualified spread
  distribution is a reasonable start and a weak one. Record the distribution so a later reading can
  challenge it.
- **Sector benchmarks are thin for small sectors.** The five-member floor keeps them honest but means
  some symbols use the absolute band. Which rule scored a metric is now marked with `§` in Wave 2,
  so the switch is visible rather than silent.
- **Android and Windows diverge for the duration.** Android gains a model Windows does not have.
  Wave 5's fixture is what stops that divergence from becoming permanent drift.
