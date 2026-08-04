# Valuation aggregation audit

What this repository is allowed to average, with what, and why every remaining plain mean in the
valuation adapter is still there. Written as part of Wave 3 of the valuation PIT & contract run
(`valuation/wave1-integration`).

The rule this document audits against is `AGENTS.md` → **Aggregation — no naked averages
(mandatory)**. It is quoted rather than paraphrased below, because a rule restated loosely is a
rule weakened.

---

## 1. The standing rule

> **A plain `sum / n` over a real-world series is a defect in this repo, not a style preference.**
> It weights a 2008 observation exactly like a 2025 one, and a single contaminated point moves the
> result by `outlier / n` while leaving no trace in the output.

with the four clauses that make it operable:

- The **one** implementation lives in `valuation-core/src/numerics.rs`. Do not write a second one.
- Scores are **median/MAD, not mean/sd**, and that is load-bearing: with mean and standard
  deviation the outlier inflates the scale it is then measured against, and no score in an
  `n`-point sample can exceed `(n - 1) / sqrt(n)` — 2.85 at `n = 10`. Filed histories here are
  10–19 years.
- **`MAX_ABSOLUTE_Z = 3.0` does not move.** It is a boundary between populations, not a knob.
- Trimming must **refuse**, never silently fall back to the untrimmed mean, and must **report how
  many observations were discarded** alongside the estimate.

Wave 3 changes nothing about this rule. It makes the last clause mechanically available — before
this wave the discarded count could only be recovered by standardizing the sample a second time —
and it extends the rule's reach from the point estimate to the **width of the point estimate**.

---

## 2. `robust_centre` and `robust_mean` are one implementation

```rust
pub fn robust_centre(sample: &[f64]) -> Result<RobustCentre, AbsenceReason>;
pub fn robust_mean(sample: &[f64], max_absolute_z: f64) -> Result<f64, AbsenceReason>;
```

Both are the crate-private `trimmed(sample, max_absolute_z)`, which is the only function in the
workspace that acts on a z-score. `robust_mean` is now literally
`trimmed(sample, max_absolute_z).map(|centre| centre.centre())` — the centre with everything but
the point estimate dropped. Its behaviour is unchanged, and the tests that pinned it before this
wave (`a_contaminated_observation_does_not_move_the_robust_centre`,
`trimming_below_a_usable_sample_refuses_rather_than_falling_back`) still pass unmodified. They also
fail the moment the shared implementation is broken, which is how the sharing is proved rather than
asserted.

### Why only one of them takes a threshold, and when the other stops

`robust_centre` takes **no threshold parameter**. `MAX_ABSOLUTE_Z` is a boundary between
populations, so a call site able to pass `4.0` would be relaxing a threshold without touching the
constant — the exact move `AGENTS.md` forbids, executed somewhere no reviewer of the constant would
look.

`robust_mean` keeps its `max_absolute_z` parameter **in this wave only**, and for one reason that
is scheduling rather than design: its single caller outside `numerics.rs` is
`valuation_probes.rs:465`, which belongs to another wave running in the same round. The parameter
is removed by **T5.11 in Round 3**, after both Round 1 waves have merged. Until then the invariant
reads: *satisfied by construction on `robust_centre`; satisfied by convention on `robust_mean`.*

`valuation_probes.rs:465-466` also calls `robust_mean` and then `standardize` a second time purely
to recover a discarded count that `RobustCentre` now returns directly, using a fully-qualified path
against the repo's import convention. That is the same T5.11 task; it is named here so it is not
mistaken for an oversight.

---

## 3. `variance_of_centre`: the centre and its width come from one kept set

```rust
impl RobustCentre {
    pub fn centre(&self) -> f64;
    pub fn variance_of_centre(&self) -> f64;   // squared standard error of `centre`
    pub fn retained(&self) -> usize;
    pub fn discarded(&self) -> usize;
    pub fn outliers(&self) -> &[usize];
}
```

**The defect this closes.** A robust centre paired with the *untrimmed* variance of the same series
is worse than not trimming at all. `posterior::fuse` weights channels by
`Observation::precision()`, which is `1.0 / variance` and nothing else, so the variance field *is*
the weight. Pair a clean level with a contaminated width and the arithmetic runs backwards: the
dirtier the sample, the more variance sits in the discarded tail, the wider the number that was
thrown away — and therefore the **tighter** the width that gets reported and the **larger** the
weight the channel earns. Contamination would buy influence. That is monotone in contamination, not
a rounding-order approximation.

On the committed `CONTAMINATED` fixture (nine values near 10, one at 910) the two readings are
`var(all)/10 ≈ 8092` against `var(retained)/9 = 23/162 ≈ 0.142` — a factor of about fifty thousand,
in the direction that rewards the bad sample.

The fix is structural rather than documentary: there is no way to obtain the centre without the
width that belongs to it, because `robust_centre` returns both from the same kept vector. A caller
cannot pair them wrongly.

**This accessor has a live consumer.** `valuation_core_adapter`'s trailing-growth channel
(`growth_posterior`) supplies both the point estimate and the precision that reaches `fuse`. An
earlier draft of this design shipped `variance_of_centre` with no caller at all and a doc comment as
its only defence; that is not the situation now, and this document does not repeat the claim that
the width "changes nothing economically today".

### LD-5 — the residual bias, its direction, and its trigger

A retained sample is narrower than the population it was drawn from, so `variance_of_centre` is a
mild **understatement** of how uncertain the centre is. Under inverse-variance fusion an understated
variance is an **overstated weight**, so a channel carrying this width pulls slightly harder on a
posterior than the evidence entitles it to.

The alternative — a MAD-based scale over the *full* sample — is rejected because it would describe a
**different estimator** than the one that produced the point.

| | |
|---|---|
| **Id** | LD-5 |
| **Defect** | `variance_of_centre` understates the estimator's uncertainty; the retained sample is narrower than its population |
| **Why not now** | the correction describes a different estimator than the one that produced the point; the monotone-in-contamination component — the one that could be exploited — is fixed |
| **Trigger** | the first forward channel that fuses against the trailing channel |
| **Detector** | **human review checkpoint** at the point `fuse` gains a second live channel. No mechanical detector exists, and that is stated rather than implied |
| **Owner** | the valuation quant workstream |

The canonical home of the latent-defect register is `docs/valuation-economic-contract.md` (Wave 4);
this entry is stated here in full so that Wave 3 is readable on its own.

---

## 4. Degenerate retained counts are refusals, not branches

`standardize` already refuses three samples outright: fewer than three observations, any non-finite
value, and a middle with no width (more than half the observations identical — the near-flat
history). `trimmed` adds one refusal: fewer than three survivors is no longer a measurement.

There is **no** special branch for a retained count of one or two, because above three observations
those states cannot arise. The scale is the middle deviation, so on five observations only the two
largest deviations can exceed any multiple of it, and three always survive whatever is done to the
other two. The refusal is reachable at exactly three inputs, where trimming one leaves a pair.

Both halves are asserted rather than claimed:
`a_sample_trimmed_exactly_to_three_still_reports_a_centre`,
`a_sample_trimmed_below_three_refuses_rather_than_reporting_a_pair`,
`no_five_observation_sample_can_be_trimmed_below_three`.

> **Deviation from the plan, recorded.** The wave's scenario table asked for a five-observation
> sample in which *two* survive. That case does not exist: it contradicts the arithmetic above, and
> it also contradicts the wave's own invariant K5, which says a retained count of two is
> unreachable. The refusal is exercised in its reachable form — three observations, one of them a
> category error — and the unreachability is asserted directly.

---

## 5. Exclude, not include: an excluded year leaves the pairs too

`fit_growth_path` pools every issuer's annual revenue growth, takes the cross-sectional centre, and
de-means every consecutive-year pair by it before regressing next-year deviation on this-year
deviation through the origin.

The decision, and it is a decision: **an observation the centre excluded is excluded from every pair
it belongs to.**

- *Include* — trim the location, then feed every observation to the regression that uses that
  location — was rejected. It does not exclude the contaminated year; it only stops counting it
  once. The year still sets the fitted persistence, which sets the fade rate for every issuer in the
  cohort.
- **No pair is built across the resulting gap.** A pair is a consecutive-year transition. Joining
  the year before an exclusion to the year after it is not a transition that happened, and inventing
  one would be a different fabrication from the one being removed. A year in the middle of a series
  therefore costs **two** pairs; a year at either end costs **one**.

**The index trap, and how it is closed.** `robust_centre` runs over the flattened pooled sample and
reports its exclusions as positions in *that* vector, while the pair construction identifies years
by `(issuer, step)`. Positions crossing a shape change with no identity invariant is precisely the
defect class this plan exists to remove elsewhere, and a misalignment here would silently kill the
**wrong issuer's** years — invisible to any test that only counts dropped pairs. So the flatten
carries a typed `GrowthKey { issuer, step }` alongside each value, the reported positions are
resolved back into keys once, against the very vector that was flattened, and nothing downstream
speaks in bare indices. `an_excluded_year_at_the_edge_of_a_series_costs_only_the_one_pair_it_touched`
plants the extreme year in a known issuer at a known position and asserts which pairs went.

Two counters are reported, because they are two different facts:

| Field | Says |
|---|---|
| `CrossSectionDiagnostics::growth_pooled_discarded` | how contaminated the cross-section was |
| `CrossSectionDiagnostics::growth_pairs_dropped` | how much fit evidence that contamination cost |

---

## 6. Every averaging site in `valuation_core_adapter.rs`, and its disposition

A "kept" without a reason is not acceptable, so every row carries one.

| Site | What it averages | Disposition |
| --- | --- | --- |
| `:280` pooled growth mean | growth across the cross-section | **replaced** by `robust_centre`. One contaminated issuer-year moved the location every pair in the cohort is de-meaned by, and therefore the fade rate of every issuer |
| `:295-296` through-origin persistence fit | de-meaned pair products and squares | **inherits** the centre's exclusions and is not separately trimmed. Trimming a regression's own residuals is a *different estimator* — it changes what is being fitted, not how contamination is kept out of it — and would need its own pre-registration |
| `:335` `fit_beta_dispersion` | variance of betas across the cohort | **kept.** This is a dispersion, not a location, and it is the dispersion of an already-shrunk, bounded quantity: published betas arrive shrunk toward an industry beta and live in a narrow range, so there is no tail for a robust scale to protect against. Trimming it would also narrow the very width it exists to supply, which errs in the wrong direction for an unknown quantity |
| `:485-491` residual scatter on `n−2` df | regression residuals about the fitted level line | **kept.** It is the fit's own residual scale. Replacing it with a robust scale would report a width for a *different* line than the one that produced the level, which is the same mismatch section 3 exists to remove |
| `:536` trailing growth mean **and variance** | one issuer's own revenue-growth history | **replaced** by `robust_centre`, centre and width and count together. This is the site that supplies both the point estimate and the precision reaching `fuse` |
| `:631` leverage sample variance | leverage across the cohort | **kept**, dispersion not location, and it is added to a reading variance to widen a balance-sheet quantity rather than to locate one. Recorded as a candidate for a later pass, together with `:637` |
| `:637` coverage sample variance | coverage across the cohort | as `:631` |
| `:781` `least_squares` centering | the regression's own centering of its two variables | **kept.** Internal to the estimator: ordinary least squares is *defined* as a fit about the sample means, and centering it on anything else would make the returned intercept and slope not those of the fit being described |

Line numbers are as of the start of Wave 3 and are given so each row can be located, not as an
address that will survive editing.

---

## 7. What the change did to the fit, measured

Measured on the pinned offline cohort via
`cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture`,
before and after the change, on the same fixture.

| Quantity | Before Wave 3 | After Wave 3 |
| --- | --- | --- |
| `growth_persistence` | **0.1709** | **0.2417** |
| `fade_per_year` | **1.7666** | **1.4199** |
| `growth_pairs` (in the fit) | 231 | 197 |
| `growth_pooled_discarded` | — (not measurable) | **22** |
| `growth_pairs_dropped` | — (not measurable) | **34** |
| issuers published / refused | 18 / 2 | 18 / 2 |

**Persistence and fade are the numbers to cite.** `persistence = 0.2417` and
`fade_per_year = 1.4199` are the current values; anything written against `0.1709` is written
against a value this wave replaced. Twenty-two of 231 pooled observations — about one in ten — were
category errors that the plain mean was reporting as growth.

### Refusal-rate change (T3.7): zero

`standardize` refuses a history whose middle has no width, so a *nearly*-flat revenue line can now
refuse where the plain mean published. Measured rather than assumed: **no issuer changed state.**
Eighteen published and two refused before and after, the same two names (MH, BWMN) with the same
reason (`evidence / not_reported`, neither of them a growth refusal), and every published median is
identical to the cent.

**Two caveats, both load-bearing:**

1. **The fixture is stale.** `core_driver_data_deep.json` was captured before the `InterestPaidNet`
   removal and is pre-policy-`/8`. This count is therefore indicative of the arithmetic change only;
   it cannot show any driver-policy effect.
2. **The cohort is 20 names, not 28.** `baseline_cohort_2026-07-30.json` carries 20 members, all
   with `status == "ok"` and none quarantined. The wave plan's "28-name pinned cohort" does not
   match the committed fixture.

### Why published values did not move at all

Two independent reasons, and both should be known before anyone reads the zero as reassurance:

1. **The Core is not wired to production.** `valuation_core_adapter::value()` and its whole
   downstream subtree have no non-test caller — proved by the compiler, by gating `value()` behind
   `#[cfg(test)]` and observing a clean build. Nothing this wave touches can reach a published
   number today.
2. **The growth path is economically inert under FR-29.** With invested capital absent, the return
   on capital arrives absent and every valuation sits at the value-neutral line `cash flow / wacc`,
   which does not read the growth path at all. Wave 5 removes that substitution; from that point a
   change to the fade rate *does* move a value.

So this is a correctness fix landing ahead of the wiring, and the identical output table is the
expected state rather than evidence that the change was inert where it matters.
