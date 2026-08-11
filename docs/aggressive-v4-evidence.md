# Aggressive V4 — what has been measured, and what has not

V4 removes defects that are visible in the code: buckets that count the same fact twice, a coverage
bonus that pays for presence rather than agreement, and multiples compared across the whole universe
instead of inside a sector. **None of that is the same as being more accurate.** This page records
what the measurements actually say, including where they say nothing.

## Bucket overlap — Wave 0, 2026-08-11

The premise of V4 is that the four buckets share inputs. Reading the code says so; it does not say
by how much. Measured on the `sp500` cohort exported from a live device
(`lab/data/overlap-report-2026-08-11.txt`):

| Pair | Spearman ρ |
|---|---|
| M, F | **+0.381** |
| M, T | **−0.367** |

The gate was ρ under 0.3 on both. It passed on magnitude, and **the gate was under-specified**: it
never named a sign, so it passed while its unstated same-sign premise half failed. A second reading
100 minutes later reproduced every pair within 0.015. Any future gate of this shape must state the
sign it expects.

A per-term probe followed, because the composite could not name a mechanism
(`lab/data/overlap-terms-report-2026-08-11.txt`). It is what decided which market terms V4 drops:

| Term | ρ against the bucket that already holds it | Verdict |
|---|---|---|
| `qualityScore` | +0.783 vs F | dropped — same sign in every stance |
| `valueScore` | +0.511 vs F | dropped — same sign in every stance |
| `lowBetaScore` | +0.686 vs M | dropped — the haircut already holds beta |
| `trendAlign` | +0.822 vs T | **kept** — the regime can flip its weight against `extension` |
| `extension` | −0.818 vs T | **kept** — same reason, opposite side |
| `oversoldQuality` | −0.686 vs T | **kept** — same reason |

The rule the table encodes: a term whose weight can flip the sign of what it says, by regime, is an
arbitration and stays. A term whose sign is fixed across every stance and is already scored in
another bucket is a duplicate and goes.

## Agreement bonus — the constant, and the population it was fitted to

`V4_SPREAD_FULL = 38.5`, the p90 of the mean absolute deviation across buckets, measured on the
**qualified** rows rather than the whole cohort (`lab/data/overlap-spread-median-2026-08-11.txt`):

| | cohort, 498 rows | qualified, 61 rows |
|---|---|---|
| p50 | 16.8 | 22.5 |
| p90 | 29.0 | **38.5** |

The qualified rows are markedly more divided than the cohort — their median row's spread is close to
the cohort's p75 — so a cohort-fitted constant would have zeroed the bonus for about a third of the
Opportunities list.

**The bonus is a hypothesis, not an assumed improvement.** `spec-windows-fourth-dimension-context.md`
is frozen and states that disagreement between the market dimension and the others is meaningful. The
market bucket is *built* to disagree with technicals in anti-chase stances, and the agreement bonus
punishes exactly that. One of the two is wrong and this page cannot yet say which.

## Forward returns — Wave 4b, 2026-08-11, and the answer is nothing

Run on device, profile `qa`, 20 symbols, one year of stored daily bars
(`lab/data/retrospective-qa-2026-08-11.txt`). Only the technicals bucket can be replayed without
look-ahead bias, and V3 and V4 share it, so this is a finding about both models and evidence for
neither over the other.

```
symbols=20 bars=5020 scores=1020 warmup=200  score range: -83..71

horizon 21 bars   held=580  dropped: no-entry-bar=20 no-exit-bar=420
  decile   1    2    3    4    5    6    7    8    9   10
  centre  451  511  162  335  152  153  278  317  403  280   bps
  top-minus-bottom: -171 bps

horizon  63 bars  held=0 — every scored date lacks an exit bar
horizon 126 bars  held=0 — same
```

**Stated plainly: there is no signal here, and the top decile came out 171 bps *behind* the bottom.**
The decile progression is not monotonic in either direction; it is noise.

What this does **not** license anyone to conclude:

- It is not evidence of a negative edge. Twenty large caps over one year, scored at 51 dates each on
  a 21-bar horizon, produce observations that overlap almost completely. The effective sample is
  nowhere near 580 independent draws.
- The 63- and 126-bar horizons are not weak results. They are **unmeasurable** on a single stored
  year: the first scored date is bar 200 and the last bar is 250, so no observation can reach 63 bars
  forward. That is what the growing bar series exists to fix, and it needs months of running.

## What would change these answers

- **The score journal** (`score_journal`) accumulates one row per symbol per completed refresh, so
  V3 and V4 can eventually be compared on outcome rather than on tidiness. It starts empty. It is
  worth nothing until it has weeks in it.

  **Read the sampling rule before reading the data, because it is not "every model viewed".** The
  journal is written from the refresh job, stamped with whichever model was *selected when the
  refresh completed*. Toggling to V4, reading the list and toggling back writes no V4 row — the
  toggle re-scores from the cached snapshot and never reaches the write path. So the sample is
  weighted by which model happened to be selected at refresh time, not by which models were looked
  at. That is a deliberate consequence of keeping the write off the render path (the alternative
  lets a user manufacture rows by toggling, and the primary key is per second, so those rows would
  be several readings of one day). It is fine for a comparison over weeks and it is **not** fine
  for any claim about a short window: a V3-heavy user's V4 column is a thinner and differently
  timed sample, not the same days.
- **The daily bar series** grows past one year now that it is persisted, which is what makes the
  longer horizons reachable.
- Re-running the retrospective on `sp500` rather than `qa` would raise the cross-section from 20
  names to 500 — and would need the one-off, explicitly-authorised universe exception that Wave 0
  used, since live QA is `qa`-only.

## Standing risks

- **V4 is uncalibrated on the day it ships.** V3 stays the control and `AggressiveV2` stays the
  default.
- **`V4_SPREAD_FULL` is fitted to one snapshot.** The distribution is recorded above so a later
  reading can challenge it.
- **Sector benchmarks are thin for small sectors.** Below five usable members the fundamentals bucket
  falls back to the absolute band, and the metric token is marked `§` when the sector rule was used,
  so the switch is visible rather than silent.
- **Android and Windows diverge for the duration.** Android has a model Windows does not.
  `shared/contracts/opportunity-v4.json` now exists and is what stops that becoming permanent
  drift — see below for the one thing that would break it.

## The contract, and the one way to ruin it

`shared/contracts/opportunity-v4.json` binds twenty-two cases: twelve on the composite (centre,
spread, bonus, beta haircut, both bounds) and ten on the fundamentals bucket — one interior case per
`SectorBenchmarks` field, two boundary cases, and the three share-count readings.
`OpportunityV4ContractTest` validates it from `:core`.

**Two boundary cases, not three, and that is deliberate.** `smoothRamp` is one function, so its
`observed <= lower` is pinned once by `forward_pe` and its `observed >= upper` once by `ev_ebitda`.
A third edge case on `price_to_book` would re-pin a comparison already bound and discriminate
nothing new, so `price_to_book` has an interior case only.

Every other contract in `shared/contracts/` carries Rust's output, so Kotlin agreeing with it means
two implementations agree. **This one cannot.** Windows has no V4, so the file has no second
implementation behind it, and if its numbers came from Kotlin it would prove only that Kotlin equals
Kotlin. So the expected values were computed by hand from the constants and written down before the
validator was run for the first time. That is the whole of its independence.

The consequence is a rule, and it is stated in the file itself: **no expected value in it may ever
be regenerated from Kotlin.** When the Rust port disagrees, the question is which side is wrong. It
is never a licence to copy this side's answer across.

Ten isolated mutations were run to check the validator can actually fail. **The first pass reported
six kills and all six were false** — the runner never started a shell, so a launch failure was being
read as a dead mutant. With a runner that names its killer, one real survivor came out.

**The survivor was in the fixture, in the case that had been called the most important one.** The
return-on-equity case sat exactly on the floor of its sector band, and an observation on an edge
returns ±1.0 under the additive rule and under a multiplicative one alike. Replacing the additive
upper offset with the multiplicative multiplier changed nothing, so the case that existed to bind
the one field with a differently shaped band was binding only the edge it sat on. Its own note in
the file claimed the opposite, and was wrong. There are now two ROE cases, both strictly inside the
band, one on each side of the centre — because the offsets are a *width*, and a width cannot be
measured from a point on its boundary. All ten mutations are killed against a baseline verified
green first, which is the other way a mutation run lies.

**Then review found the fix was necessary and not sufficient, and the reason is worth keeping.** The
same defect sat in five more cases — both remaining multiples and both share-count readings, each
pinned on an anchor computed from the constant it claimed to bind. The ten-mutation round had passed
them, and the commit message had gone as far as asserting that their pinning "is still right". It
was not. **Every one of those ten mutations moved its constant in one direction only.** A case at
`0.7 × centre` does move when the multiplier drops to 0.5, and does not move at all when it rises to
1.0, because `1400 <= 1400` and `1400 <= 2000` clamp identically. The round measured the direction
the case could see and never tried the other.

Five observations were moved strictly inside their bands. Two edge cases were kept and relabelled
for what they actually do — `forward_pe` on the low anchor binds `observed <= lower`, `ev_ebitda` on
the high anchor binds `observed >= upper`, and neither claims to measure the multiplier it is
computed from. A case that binds a constant and a case that binds a boundary are two cases.

Twelve further mutations then moved all six sector and share constants **in both directions**:

| constant | down | up |
|---|---|---|
| `V4_FUND_SECTOR_CHEAP_MULT` 0.7 | 0.5 killed | 1.0 killed |
| `V4_FUND_SECTOR_RICH_MULT` 1.5 | 1.0 killed | 2.0 killed |
| `V4_FUND_SHARE_COUNT_SHRINK_BPS` −300 | −500 killed | −100 killed |
| `V4_FUND_SHARE_COUNT_DILUTE_BPS` 300 | 100 killed | 600 killed |
| `V4_FUND_SECTOR_ROE_LOWER_OFFSET_BPS` −500 | −1000 killed | −100 killed |
| `V4_FUND_SECTOR_ROE_UPPER_OFFSET_BPS` 1500 | 800 killed | 3000 killed |

Twelve of twelve, against a baseline verified green first. **The lesson is about the instrument, not
the fixture: a one-directional mutation round grades a one-directional test as passing.** Each
interior case's `binds` note now lists its score under all four mutants of the constants it covers,
so the next reader can check the claim without re-deriving it.
