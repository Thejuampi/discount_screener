# Growth research charter

The growth channel's peer of `docs/roic-research-charter.md`: what must be established before a
growth estimator can be proposed as a candidate, belonging to the same economic model and the same
definition of done (Decision 3). Growth work is not deferred and not optional scaffolding — the
ROIC/reinvestment model cannot be promoted until the growth workstream is complete and its units are
aligned with `docs/valuation-economic-contract.md` §6, §10.

---

## 1. The question, and the quantity a persistence parameter answers

`docs/valuation-economic-contract.md` §12 already states the constraint this research must satisfy:
the identity's growth term is `g_NOPAT`, and the persistence/fade machinery that governs how `g(t)`
relaxes toward its terminal rate must be fitted on a quantity that is actually NOPAT growth, not a
proxy standing in for it.

**What is being estimated.** `FadePath`'s single parameter `k` (`fade_per_year`) — the rate at which
`g(t) = g_inf + (g_0 - g_inf) * exp(-k t)` decays toward the terminal growth rate. `k` is fitted from
a **persistence coefficient**, today produced by a through-origin regression of next-period
de-meaned growth on this-period de-meaned growth, pooled across the cross-section
(`valuation_core_adapter.rs::fit_growth_path`). The persistence coefficient (`rho`, today `0.2417`
post-Wave-3, cited here only as the current arithmetic state of the pipeline, not as a research
result of this charter) is the input `k` is derived from; a research candidate that changes how
persistence is fitted changes `k` for every issuer in the cross-section, because both are pooled
quantities, not per-issuer ones.

**What it is currently estimated from, and why that must change.** `fit_growth_path` today fits
annual **revenue** growth, not NOPAT growth (brief Decision 3, verbatim: *"the `0.1709` [pre-Wave-3]
persistence everything rests on is a revenue number"*). The existing revenue-growth coefficient
**cannot be reused as though it were NOPAT growth** — revenue and NOPAT growth are different
quantities with different volatility, different cyclicality, and no guaranteed common persistence,
and treating one as a proxy for the other without validation is exactly the kind of unvalidated
substitution Decision 2 forbids for the return channel and Decision 3 forbids here.

---

## 2. Two candidate directions, neither approved in advance

Per Decision 3, both directions are research candidates only — this charter defines what each must
establish, and selects neither:

1. **Estimate and validate NOPAT-growth persistence directly.** Refit the pooled persistence
   regression on `g_NOPAT` transitions instead of revenue-growth transitions, using the same
   robust-centre-and-exclude discipline §4 below requires. Must establish that NOPAT growth series
   are stable enough, across the 10-19-year filed histories this pipeline works with, to support a
   through-origin persistence fit at all — a quantity with more zero-crossings and sign changes than
   revenue (NOPAT can be negative; §12's log-growth definition in
   `docs/valuation-economic-contract.md` is undefined when either endpoint is non-positive) may not
   support the same estimator revenue growth does.
2. **Project revenue and margins separately, derive NOPAT growth through an explicit margin
   bridge.** Fit revenue growth and operating-margin trajectory as two separate processes, and
   compose `g_NOPAT` from their product rather than fitting NOPAT growth as a single series. Must
   establish what persistence means for *each* leg separately (revenue growth may persist
   differently from margin drift), and how the two composed uncertainties propagate into a single
   `g_NOPAT` uncertainty rather than being silently treated as independent when they may not be.

Neither direction is implemented, piloted, or preferred by this charter. Both must be evaluated
against the economic contract and point-in-time evidence (brief Decision 3) before either is a
candidate in `docs/roic-preregistration.md`'s sense — and that pre-registration governs the
return-on-capital channel specifically; a growth-channel pre-registration, should one be written, is
a distinct deliverable this charter does not attempt to substitute for.

---

## 3. What the fade rate means, restated for the growth channel specifically

`docs/valuation-economic-contract.md` §12 already states the load-bearing fact: `k` is not only the
rate `g(t)` decays toward its terminal level, it is *simultaneously* the rate the reinvestment
spread erodes, because the retention charge `C(t) = E(t) x (1 - g(t)/r)` shares the same `g(t)` path.
**A growth-channel research candidate that changes how `k` is fitted therefore changes two economic
claims at once, not one**: how fast growth fades, and how fast the business's capital-efficiency
advantage (relative to what it costs to sustain growth) fades. Any candidate direction (§2) must
report both effects, not just the growth-path fit quality, or it has silently smuggled a
spread-erosion assumption change into what looks like a pure growth-fit improvement.

---

## 4. The specific caution Wave 3 surfaced: two inherited defects, neither a free parameter to tune

Wave 3 (`docs/valuation-aggregation-audit.md`) replaced the pooled growth centre's naked mean with
`robust_centre`, and the persistence fit **inherits** that change rather than being independently
trimmed:

- **The pooled centre's exclusions.** An observation `robust_centre` discards from the pooled growth
  location is also excluded from every consecutive-year pair that observation participates in before
  the persistence regression runs — no pair is built across the resulting gap
  (`docs/valuation-aggregation-audit.md` §5). This means the *set of years the persistence fit sees*
  is not a free choice a growth-channel candidate gets to make independently; it is downstream of
  the pooled centre's own trimming, `MAX_ABSOLUTE_Z = 3.0`, which does not move (`AGENTS.md`, and
  restated as a standing constraint here: lowering it to make a growth persistence fit come out
  differently is the same forbidden move as relaxing a test threshold).
- **The `variance_of_centre` understatement (LD-5, D2).** The pooled centre's own width is a mild,
  structural understatement of the true dispersion, because the retained sample the width is
  computed from is narrower than the population it estimates
  (`docs/valuation-economic-contract.md` §14, LD-5). Any growth-channel candidate that consumes this
  width — for instance, to weight a trailing-growth channel against a forward one in `fuse` — is
  consuming a slightly-too-confident number. This is a **known, registered, residual limitation**,
  not a defect a growth-channel research candidate is expected to fix as part of its own work; a
  candidate that silently "corrects" it by substituting a different variance estimator would be
  changing the width without a pre-registration of its own, and would need one before doing so.

**Neither of these is a free parameter to tune.** A growth-channel candidate that finds its fitted
persistence sensitive to `MAX_ABSOLUTE_Z` or to `variance_of_centre`'s known bias must report that
sensitivity as a finding about the growth channel, not adjust either quantity to make its own
candidate look better — both are governed by the standing rules above this charter, not by this
research programme.

---

## 5. What this charter does not do

It does not fit a NOPAT-growth persistence coefficient, does not implement the margin-bridge
direction, does not build the rolling PIT harness, and does not compare either candidate direction
against the other or against today's revenue-growth-derived fit. It states what either candidate
direction must establish and the two inherited constraints (§4) neither may quietly relax.
