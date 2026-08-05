# ROIC pre-registration

Depends on `docs/valuation-economic-contract.md` (T4.1) and `docs/roic-target-specification.md`
(T4.5), both frozen before this document's prose was written — see §10 for the freeze protocol and
the timestamps that prove the ordering.

**No candidate result is referenced anywhere in this document.** Everything below is written before
the rolling PIT harness (work-order item 6, out of this run's scope) exists, so no candidate has
been evaluated against anything stated here.

---

## 1. Primary endpoint

Exactly one, quoted verbatim from the brief, not paraphrased:

> **"Cross-sectional median absolute error between predicted and realized incremental return over
> the three-year horizon, evaluated on the common issuer-cutoff set."**

**Notation.** `MdAE` denotes this quantity — the cross-sectional **median** of the **absolute
error**, not the mean. It is used every time the primary endpoint is referenced below, and it is
always the median-absolute form; nothing in this document computes a mean-squared or mean-absolute
error instead. "Incremental return" is the target quantity `docs/roic-target-specification.md`
pins: `ΔNOPAT / ΔIC` over a fiscal-year-aligned three-year window (target spec §1-2). "Predicted"
means a candidate estimator's forecast of that quantity, made from evidence available **as of** the
window's start cutoff `t` (point-in-time, per the economic contract). "Realized" means the same
quantity computed after the fact from the best-known (post-restatement) filed evidence for the same
window (target spec, row 10).

No other endpoint is primary. Every other metric in this document is a secondary diagnostic (§6-8).

---

## 2. The comparison: paired, against a named benchmark, on a set fixed in advance

### 2.1 Pairing

The candidate is compared against **`prior_only`** — the identical model with the return-on-capital
channel absent (i.e. today's actual production behaviour under FR-29's removal: an
`EstimatorUnavailable` refusal wherever the channel would have contributed) — evaluated on **the
same issuers, the same years, and the same cutoffs** as the candidate. `improvement = MdAE(prior_only)
- MdAE(candidate)` is computed only within pairs that share issuer, year and cutoff. An unpaired
comparison — different samples, different cutoffs, or a candidate's own subset compared against a
different subset for `prior_only` — is not evidence for this endpoint, regardless of how favorable
the numbers look.

### 2.2 Set construction

**The cross-section — the "common issuer-cutoff set" — is pre-declared independently of any
candidate's ability to resolve it.** It is built from `docs/roic-target-specification.md`'s rows
1-17 alone: every issuer-window that survives the target specification's exclusions (acquisition
contamination, `ΔIC=0`, the small-denominator floor, non-positive capital, non-positive NOPAT,
issuer-class restriction, and so on) is **in** the set, before any candidate — including
`prior_only` — is asked to produce a prediction for it.

Reading "common issuer-cutoff set" as *the intersection of what both candidates happen to resolve*
is precisely the win-by-abstention loophole Decision 1 exists to close: a candidate that abstains on
hard cases would shrink the set to the easy ones and look better for it purely by declining to
answer. That reading is rejected outright. The set is fixed by the target specification's own
exclusion rules, full stop, and neither candidate's coverage narrows it.

### 2.3 Abstention is scored, not dropped

When a candidate — `prior_only` or the estimator under test — cannot produce a prediction for a
cell in the pre-declared set (an explicit `EstimatorUnavailable`/`ProviderUnavailable`-class
refusal), that cell is **not removed** from the primary endpoint's computation. One of two
pre-registered treatments applies, chosen **before** any candidate runs:

- **Substitution**: the abstaining candidate's prediction for that cell is replaced by the
  benchmark's (`prior_only`'s) prediction for the same cell, so an abstention costs the abstaining
  candidate nothing better than the benchmark's own answer; or
- **Explicit penalty**: the abstaining candidate is charged a pre-registered penalty absolute error
  for that cell, fixed here at the 90th percentile of `prior_only`'s absolute errors across the
  full pre-declared set — a poor-but-not-infinite penalty, chosen before any candidate result exists
  and computable from `prior_only` alone (which never abstains under Decision 2's contract:
  `prior_only` either resolves or explicitly refuses per the economic contract's absence rules, and
  a refusal there is itself informative and is folded into `prior_only`'s own error distribution
  before the percentile is taken).

Either treatment is acceptable and the harness (out of this run's scope) selects one before running
any candidate; what is fixed **here, now** is that a treatment exists and is applied uniformly.

**Dropping abstained cells from the primary endpoint is a prohibited analysis.** Stated in exactly
those words because element 7's coverage exclusion from the veto set would otherwise *create* the
loophole it is meant to close: if abstained cells could simply vanish from the primary computation,
a candidate could achieve a better `MdAE` purely by refusing on the hardest cells, and coverage being
outside the veto set would mean nothing caught it. Scoring abstention, not dropping it, is what
makes coverage's exclusion from the veto set safe rather than exploitable.

---

## 3. Uncertainty: issuer-clustered bootstrap

Rolling cutoffs drawn from the same issuer are not independent observations — an issuer's own
history shares a business cycle, a capital structure, a management team. Treating each issuer-cutoff
pair as an independent draw would understate the true uncertainty of `improvement`.

Uncertainty on `improvement = MdAE(prior_only) - MdAE(candidate)` is therefore estimated by a
**cluster bootstrap that resamples issuers**, not issuer-cutoff observations: each bootstrap
replicate draws, with replacement, a full set of issuers (not individual issuer-year or
issuer-cutoff cells) equal in count to the original issuer count, and includes **every** cutoff
belonging to each drawn issuer intact. An issuer's own cutoffs never get split across different
resample draws within one replicate.

**The resample count, fixed before any candidate runs: 10,000 replicates.** This is a standard,
conservative count for a percentile bootstrap confidence interval at typical decision thresholds
(95% and, per §5's multiplicity correction, up to 97.5% per comparison); it is stated here as a
frozen number, not a range to be adjusted once a candidate's variance is observed.

---

## 4. Materiality threshold, with derivation

**The threshold is a number, in the endpoint's units (basis points of incremental return), not an
adjective.** It is derived from the mechanical propagation `docs/valuation-economic-contract.md`
already states — `FCFF = NOPAT x (1 - g/r)` — through to a relative error in the published cash-flow
base. One step at the end requires a decision about what size of downstream value change is
decision-relevant to a user of this screener, and that step is explicitly labelled **judgement**,
per the brief's own instruction that judgement is permitted only there.

**Step 1 (mechanical).** From `FCFF = NOPAT x (1 - g/r)`, holding `NOPAT` and `g` fixed and
differentiating with respect to `r`:

```text
d(FCFF)/dr = NOPAT * g / r^2
```

**Step 2 (mechanical).** In relative terms, writing `b = g/r` (the reinvestment rate, §3 of the
economic contract) so that `FCFF/NOPAT = 1 - b`:

```text
d(FCFF)/FCFF = [ b / ( r * (1 - b) ) ] * dr
```

This is exact to first order: a small error `dr` in the estimated return on capital moves `FCFF`
proportionally to `b / (r(1-b))`, a quantity that grows without bound as `b` approaches 1 (an
issuer reinvesting nearly all of NOPAT is far more sensitive to a mis-measured `r` than one
reinvesting little) — which is itself an economically sensible property of the identity, not an
artifact of the derivation.

**Step 3 (judgement, labelled as such).** Two choices are needed to turn the formula above into one
concrete number, and both are decision-relevance judgement, not derivation:

1. **A representative operating point** for `g` and `r`, at which to evaluate the formula. This uses
   two constants **already declared in this codebase**, not invented for this document: the terminal
   stable-growth ceiling `macro_stable_growth_bps = 300` (`operating_valuation_runtime.rs`, `g = 3%`)
   and a representative discount rate built from `AGENTS.md`'s own dynamic-parameters table
   (risk-free rate order-of-magnitude ~400 bps plus equity risk premium order-of-magnitude ~500 bps
   `= r = 900 bps = 9%`) — used here purely to seed an illustrative sensitivity, never as a
   valuation input, consistent with `AGENTS.md`'s own warning that neither is "sole truth." At this
   point, `b = g/r = 300/900 = 1/3`, and `b / (r(1-b)) = (1/3) / (0.09 x 2/3) ≈ 5.56` per unit of
   `r`.
2. **A decision-relevant relative-value bound**: how large a relative change in the published `FCFF`
   base would need to be true before it plausibly changes a decision a user of this screener makes.
   Fixed here, as judgement, at **100 bps (1%) of `FCFF`** — deliberately a different number from,
   and not derived from, the separate ±5% anchor-movement communication trigger (`docs/valuation-economic-contract.md`
   §16), which is Juan's own instruction and is not reused here as though it were derived.

**Combining:** `0.01 = 5.56 x dr` gives `dr ≈ 0.0018`, i.e. **≈18 basis points of `r`**. Rounded up
— the more conservative, stricter direction, requiring a larger measured improvement before
promotion — the pre-registered materiality threshold is:

> **The candidate must reduce `MdAE` relative to `prior_only` by at least 20 basis points of
> incremental return** (`improvement >= 20 bps`) for the improvement to be considered economically
> meaningful, in addition to clearing §5's statistical requirement.

No candidate has been evaluated against this number. It is fixed before any harness run.

---

## 5. Multiplicity rule

**Total comparisons pre-registered for this experiment: two candidate estimators against
`prior_only`** — the book-centre return on capital (`robust_centre` over an issuer's annual
`NOPAT/InvestedCapital` history) and the marginal/slope return on capital (the least-squares slope
of `NOPAT` on `InvestedCapital` across an issuer's history). Both are already implemented as
measurable quantities in `valuation_probes.rs::probe_return_on_capital_availability` and neither is
promoted or selected by this run (brief §2, explicitly out of scope). A third candidate the same
probe computes — "implied" `r = g/b` — is **excluded from comparison entirely**: by construction its
own `g/r` returns `b` exactly, so its gap against the realized reinvestment rate is identically zero
for every issuer and it measures nothing (the probe's own module documentation states this; this
document does not re-derive it, only inherits the exclusion).

**What happens to the decision threshold when more than one comparison is run**: with `N = 2`
pre-registered comparisons, each candidate's required cluster-bootstrap confidence interval is
raised from the unadjusted 95% to a Bonferroni-corrected `(1 - 0.05/N) = 97.5%` before that
candidate's interval may be read as excluding zero. §4's 20 bps materiality threshold is unchanged
by `N` — multiplicity inflates the statistical bar, not the economic one. A pre-registration that
permitted unlimited comparisons at a fixed significance level would pre-register nothing; fixing `N`
at 2 here is what keeps the correction meaningful rather than retroactive.

---

## 6. Secondary diagnostics may veto, never promote

Listed explicitly, per the brief's own enumeration. A secondary diagnostic below finding a problem
can **veto** a candidate that passed §1's primary endpoint and §4's threshold; none of them, however
favorable, can **promote** a candidate that failed either:

- **Material signed bias** — the candidate's errors are not centred near zero in a consistent
  direction across the cross-section.
- **Materially miscalibrated intervals** — where the candidate reports an uncertainty band, the
  realized outcome falls outside it far more or less often than the band's own stated coverage.
- **Unacceptable tail failures** — a small number of issuer-cutoffs with extreme absolute error, even
  if the median (§1) is acceptable.
- **Temporal instability** — the candidate's error behaves very differently across different cutoff
  eras, suggesting it is not a stable estimator of the same underlying process.
- **Evidence leakage** — any dependency of a prediction on evidence not yet knowable as of its
  cutoff (the point-in-time discipline the economic contract and target specification both require).
- **Dependence on a small number of issuers** — the improvement is carried by a handful of names
  rather than broadly distributed across the cross-section.
- **Failure in economically important cohorts** — specifically, the LD-8-exposed set (DAL, CHTR,
  BKR — `docs/valuation-economic-contract.md` §14) and any issuer whose invested-capital basis is
  still unresolved under LD-4. A candidate that improves the aggregate `MdAE` while performing badly
  on these named, already-flagged-as-fragile cohorts is vetoed even with a passing primary result.

---

## 7. Coverage is excluded from the veto set

**Coverage — the fraction of the pre-declared cross-section a candidate actually resolves rather
than refusing — is reported alongside the primary endpoint and is never allowed to act as a gate in
either direction**, exactly per Decision 1.

**The reason, written out:** a change that refuses more often will nearly always look better on the
primary error metric while being worse for the user, because §2.3 scores every abstention against
the benchmark or a fixed penalty rather than dropping it — but the *reported* coverage number itself
still trivially improves whenever a candidate simply declines to answer more cells, since a refused
cell contributes no observed error of its own to a naive count even though it is scored. Making
coverage a veto criterion in either direction would reward or punish abstention directly, which is
exactly the promotion-by-refusal shape Decision 1 forbids. Coverage is reported for transparency; it
decides nothing.

---

## 8. The anchors are excluded from the veto set too

**PG, GOOGL, AMZN and MSFT are diagnostics only** (brief constraint 9). They appear in every report
this experiment produces and in no gate, promotion or veto, in either direction. Citing an anchor's
behaviour as a reason to promote or veto a candidate would be treating market-adjacent
diagnostic output as an acceptance criterion, which constraint 2 forbids outright regardless of
which document does it.

---

## 9. Where the +/-5% anchor trigger lives (and why not here)

The ±5%-anchor-movement / gate-side-change communication trigger (brief §5) does **not** live in
this document. Its content is correct and stays correct, but it is **Juan's stated instruction — a
communication trigger, not derived and not a gate** — and a non-derived, non-gating convention
sitting among this document's pre-committed decision rules (§1-5, each of which *is* derived or
fixed before observing a result) would invite a later reader to mistake it for something
pre-registered the same way. It belongs, and now lives, in `docs/valuation-economic-contract.md`
§16 ("Operating protocol"), and is restated in each wave's own stated pause triggers. This element
exists only to record where it went and why it is not duplicated here.

---

## 10. Freeze protocol

**What is frozen**: the primary endpoint (§1); the benchmark, `prior_only` (§2.1); the cross-section
construction rule and the abstention-scoring treatment (§2.2-2.3); the bootstrap resample count and
clustering unit (§3); the materiality threshold, 20 bps, and its derivation (§4); the multiplicity
rule and the set of two pre-registered candidates (§5); the veto list (§6-8).

**When it is frozen**: before any candidate — including `prior_only` itself, run in its role as
benchmark rather than as a subject — is evaluated against the primary endpoint. Work-order item 6
(the rolling PIT harness that would make such an evaluation possible) is explicitly out of this
run's scope (brief §2), so no evaluation has occurred and none can have occurred before this
document's commit.

**Where the frozen copy lives**: this file, `docs/roic-preregistration.md`, as committed to the
repository. The commit that lands it is the authoritative frozen copy; a diff against that commit is
how any later reader verifies nothing here was altered after an outcome existed.

**What an amendment costs**: any subsequent change to this document's frozen elements —
after any candidate has been run against them — **invalidates the pre-registration**. This mirrors
`docs/roic-target-specification.md`'s own standing rule verbatim, extended to this document's
contents: an amendment made after an outcome is observed is not a correction, it is a new
experiment, and it requires a new, untouched holdout exactly as the target specification's own rule
states.

---

## 11. No-outcome-observed attestation

At the time this document was frozen (§10), no candidate estimator — book-centre, marginal/slope, or
`prior_only` in a comparative role — had been evaluated against the primary endpoint defined in §1.
No rolling PIT harness exists yet to run such an evaluation (work-order item 6, out of scope), and no
measurement anywhere in this run's build artifacts computes `MdAE`, `improvement`, or any quantity
this document defines as a candidate result.

**This attestation names its own weakness, in its own text, because the weakness is real and a
reader deserves to know its shape rather than infer a stronger guarantee than exists:** this
attestation is **self-certified**. It is written by the same agent (and process) that wrote the
threshold and the freeze protocol it attests to, that will not itself run the harness, and no
external party — no independent auditor, no separately-authored process, nothing outside this run's
own authorship — attests the freeze from outside it. `docs/valuation-economic-contract.md`'s and
this document's own T4.8 checkpoint discipline mitigates *ordering* (the skeleton in §10's history
was written, and reviewed, before this prose), but ordering mitigation is not the same guarantee as
independent attestation. A reader relying on this document as proof against post-hoc threshold
selection should read this section as: *the ordering is auditable; the freeze is not independently
witnessed.*
