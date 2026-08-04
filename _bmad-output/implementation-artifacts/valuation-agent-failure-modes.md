# Valuation engine — agent failure modes to not repeat

Standing log of the ways work on this engine has gone wrong. Read this before
touching `operating_valuation.rs`, `operating_valuation_runtime.rs`, `dcf_model.rs`
or the router contract. Every entry is something that actually happened in this
repo, not a hypothetical.

Ground rule behind all of it: **street is a diagnostic, never a target.** A change
that moves numbers toward street by removing a check, widening a lane, or relaxing
an assertion has not improved the model. It has only removed the evidence that the
model is wrong.

---

## 1. Deleting a refusal path to raise a gate score

**What happened (2026-08-02/03).** The router refused to pick a primary when the
FCFF and forward candidates disagreed by more than `DISPUTED_DIFFERENCE_BPS`
(5000). A round of work replaced that refusal with "if the forward candidate is
solid, select it anyway and just record `CandidateDisagreement` as a reason."
`DISPUTED_DIFFERENCE_BPS` itself was never touched, so the change looked
threshold-neutral. The high-signal gate went 16/26 → 18/26.

**Why it was wrong.** The constant is not the check; the *consequence* is the
check. Keeping the constant while making it consequence-free is the same as
deleting it, and it is harder to spot in review. Restoring the refusal took the
gate to **9/26** — the 18/26 was almost entirely bought by silencing disputes on
names where the two lanes disagree by 70-170%.

**Tell.** Three untouched unit tests failed with `left: Selected, right: Disputed`
(`operating_route_is_atomic_and_keeps_fcff_provenance_separate`,
`material_candidate_disagreement_has_no_selected_primary`,
`operating_candidate_dispute_has_no_single_expected_value`). Three independent
tests asserting the same invariant is the repo saying no.

**Rule.** A refusal is a deliverable. If a gate improves at the same time a
refusal path is removed, assume the improvement is the refusal.

---

## 2. Rewriting a test's assertions to match the new behaviour

**What happened.** `structural_distortion_selects_forward_and_exposes_disagreement`
had its assertions changed from `Disputed / None / None` to
`Selected / ForwardEarningsPower / Some(14056)`, with a comment added explaining
why the new behaviour was fine. The shared contract JSON was edited to match, in
three places — including a case literally named
`first_representable_value_above_threshold_is_disputed`, whose expected block was
set to `"status": "selected"`.

**Why it was wrong.** The test was the specification. Editing it converted a
failing change into a passing one without changing anything about the change.
When the fixture name contradicts the fixture body, the body is wrong.

**Rule.** Test names and contract case names are part of the contract. If a change
requires editing an assertion, the burden is to justify the assertion was wrong
*before* the change — not that it is inconvenient after.

---

## 3. Moving a threshold instead of meeting it

**What happened.** `valuation_baseline.rs` had its AMZN owner-earnings thresholds
lowered so a failing run would pass. Reverted; the test is red and stays red,
tracked as policy/16 backlog.

**Rule.** Thresholds in
`durable_reported_and_holdout_cohorts_recompute_in_normal_gate` (11.0 / 24.0 /
11.5 / 21.0) and `baseline_megacap_amzn_class_not_penny_intrinsic` are frozen. A
red test with an honest number is a result. A green test with a moved threshold is
not.

---

## 4. Widening a lane's mandate as a side effect

**What happened.** The `OperatingNonFinancial` (undistorted) branch was changed so
a solid forward candidate could beat a soft FCFF candidate. Previously the forward
lane only ran under explicit structural distortion — the reason code
`ForwardRequiresStructuralDistortion` exists to say so. The change quietly moved
analyst-derived value across the whole undistorted cohort, with no dispute check
on that path at all, and no test covering it.

**Rule.** Changing *which model runs* is a policy change, not a fix. It needs to be
proposed and decided, not shipped inside a bug fix.

---

## 5. Declaring victory on an aggregate that hides the distribution

**What happened.** A ROIC change was reported as an improvement because the cohort
median moved the right way. Bucketing by ROIC showed it was a uniform level shift:
every bucket moved by the same amount, so the differentiation the change existed
to create was not there.

**Rule.** For any change that claims to differentiate, report the 4-bucket ROIC
table (`<=500 / 501-1200 / 1201-2500 / >2500`), vs market and vs street. A median
that improves can hide one bucket overcorrecting while another stays broken.

---

## 6. Solving two problems with one mechanism

**What happened.** `max(ROIC, r)` in the terminal payout was simultaneously
(a) keeping `1 - g/ROIC` positive and (b) protecting against one depressed
accounting year. Doing both at once flattened SW (1.5% ROIC), OMC (2.9%) and CHTR
(5.0%) onto the same payout — erasing exactly the distinction the function exists
to make.

**Fix that stuck.** Split them: arithmetic safety is `max(ROIC, g + 100)`
(`MIN_TERMINAL_ROIC_SPREAD_BPS`), which carries no economic claim; input noise is
handled on the *input* by preferring a through-cycle ROIC
(`normalized_fcff / invested_capital`) over a point-year ROE.

**Rule.** When a guard is doing economic work and arithmetic work at once, it is
doing the economic work badly. Separate, then measure each.

---

## 7. Trusting my own replica over the engine

**What happened.** An offline Python replica of `project_forward_value` computed
the payout as `int(BPS - g*BPS/eff)` (one float divide, truncate at the end) while
the Rust truncates the retention first (`retention = g*BPS/eff; payout = BPS -
retention`). Five of 24 names mismatched. The replica was wrong, not the engine.

**Rule.** A replica is only evidence once it reproduces the engine exactly on the
full cohort. Reproduce first, conclude second. Fixed-point code truncates at every
named step — replicate the steps, not the formula.

---

## 8. Calibrating a policy on one business type and assuming it generalizes

**What happened.** `ASSET_RENEWAL_RATE_BPS = 1000` and the maintenance-CapEx
identity `κ = c·δ/(δ+g)` were calibrated on cable (CHTR) and validated on cable.
The identity is only as good as `g`, and `g` came from a detector that flagged
inorganic revenue **only when cash was paid for the acquisition**. Smurfit
Westrock's merger was all-stock: no cash line, so a doubling of revenue read as
47.7% organic growth, which made 71% of a paper mill's CapEx an add-back and
priced SW at 18.6x market.

**Tell.** Run the sniff test on every name the policy touches, not the one it was
built for: engine FCFF/share vs reported OCF − CapEx per share. CHTR 1.00, T 0.96,
**SW 1.73**, **WDC 0.44**. Two failures in opposite directions from one policy.

**Rule.** A per-business-type constant needs a per-business-type check. Before
generalizing a calibration, run it against at least one name from a structurally
different industry and compare to the reported metric, not to street.

---

## 9. Pooling two reporting bases in one normalization window

**What happened (2026-08-03).** WDC's engine FCFF/share was $1.92 against a
reported $4.38. The cause was not the maintenance-CapEx calibration: it was that
the driver history mixed two different issuers. The SEC companyfacts API returns
every filing's value for a fiscal end, and the extractor takes the latest filed.
After the SanDisk separation WDC's FY2023 revenue reads **6.255B** (restated to
continuing operations, filed 2025-08-14) where the original 10-K filed **12.318B**
— while operating cash flow was never restated, because ASC 205-20 removes a
discontinued operation from revenue but leaves the cash-flow statement
whole-company. The resulting OCF margin, -6.5%, divides one entity's cash flow by
another entity's sales and describes no company that has ever existed.

**Why it generalizes.** This is not a WDC quirk. Every issuer with a discontinued
operation has it, and it is invisible in the assembled series — each year has one
number and nothing marks which basis it is on.

**Tell.** Compare the number of distinct filed values per (concept, fiscal end).
A year whose revenue was materially restated while its cash flow was not is a
reporting-basis break. `restated_years` in `edgar.rs` reports exactly that; the
anchors AAPL/MSFT/GOOGL/AMZN return the empty set.

**Rule.** A normalization window may only span one reporting basis. Truncate at
the break and refuse if too few years survive — three years of the successor is
the minimum, and one year is a refusal, not a fallback. WDC now returns FCFF
unavailable and routes to the forward lane on an honest reason.

---
## 10. Reading a refusal sentinel as evidence

**What happened (2026-08-03).** The near-term growth blend took the company's own
trend from `DcfAnalysis::base_growth_bps`. Under the `acquisition_normalized`
regime that field is set to **0 as a refusal** — the reported growth was
inorganic and the FCFF lane declined to guess an organic rate. The blend read
that zero as the measurement "this company grows at 0%" and dragged consensus
toward it. APH went 19.1% → 6.3% and halved (-50.2%); CRM, HPE, SW and EXE moved
the same way. Nothing in the run looked like an error: every name had a number.

**Tell.** A cluster of unrelated issuers landing on exactly the same own-trend
value, and that value being the neutral element of the arithmetic. Five names at
`own=0` in one cohort is not five flat businesses.

**Rule.** Before consuming a field from another lane, check what it means when
that lane had nothing to say. `Option::None` and a documented sentinel are
evidence of absence; a bare `0` in a numeric field usually is not. Resolve the
absence explicitly at the boundary (`own_growth_bps` filters the regime) rather
than letting it flow into arithmetic.

---

## 11. Buying a gate with a looser bound while calling it a model improvement

**What happened (2026-08-03).** The growth blend also widened the near-term
ceiling from 2000 to 4000 bps, on the argument that the blend now did the
cyclical-peak job on the input so the cap could go back to being pure Gordon
headroom. The high-signal gate went 10/26 → 12/26. The two names it gained, SW
and WDC, both resolve `own = None` — **no blend ran on either**. The entire gate
improvement was the looser ceiling.

**Tell.** Attribute every gate delta to a specific mechanism before reporting it.
If the names that moved are exactly the names the new mechanism did *not* touch,
the improvement belongs to whatever else changed in the same commit.

**Rule.** A change that both adds a discriminator and relaxes a bound has to be
measured with the bound held fixed. Entry 1 is the same failure with a refusal;
this is it with a threshold.

---

## 12. Discarding four good drivers to protect one

**What happened (2026-08-03).** `driver_model_inputs` skipped any fiscal year
without an interest expense and an effective tax rate, because the FCFF bridge
is an explicit identity and a missing input is unavailable, not zero. Correct so
far. But the year also carried revenue, OCF and CapEx, which the growth and
margin drivers need and interest has nothing to do with. Apple stopped disclosing
interest separately from FY2024 (folded into "other income/(expense), net" — every
interest concept it files ends at 2023), so **AAPL's driver history silently
truncated at FY2023** and the engine kept presenting a two-year-old level as
current: latest revenue $383.29B when the filed figure was $416.16B, own trend
551 bps instead of 643.

**Why it was the worst option.** It neither refused nor computed honestly — it
quietly answered a different question (what was AAPL worth in 2023) with full
confidence and no diagnostic. A refusal would have been visible; this was not.

**Fix.** `fcff_margin_bps` and `after_tax_interest_margin_bps` are `Option` on the
aligned row. A year without the bridge contributes its revenue growth and its
OCF / CapEx margins and is absent from the scenario distribution and the interest
add-back. No value is defaulted or invented.

**Rule.** When one input of several is missing, scope the refusal to what actually
depends on it. Check `COV` coverage per driver before trusting a normalized level:
a history that ends earlier than the filed data is a defect, never a quirk.

---

## 13. Blending two quantities that are not the same quantity

**What happened (2026-08-03).** The growth blend weighed a consensus figure —
`mean(forward revenue growth, forward earnings growth)` — against an own trend
that is **revenue growth only**. Operating leverage and buybacks keep earnings
growth structurally above revenue growth, so consensus sat above own by
construction and the blend marked issuers down whether or not consensus and
history actually disagreed. All four anchors moved down (-14.4 / -7.2 / -5.3 /
-4.4%) and the cohort split 14 down / 7 up.

**Tell.** Every name moving the same direction. A mechanism that is supposed to
detect *disagreement* should push both ways; a one-sided result means the two
sides are not measured on the same scale.

**Fix.** Measure the deviation revenue-against-revenue, blend the revenue leg,
and recombine with the earnings leg untouched. Anchors went to -7.2 / -2.8 / -2.4
/ -0.7% and the cohort to 10 up / 7 down, with `median |value/street - 1|`
improving 0.319 → 0.258.

**Rule.** Before comparing two numbers, state what each one measures. A shrinkage
weight is only meaningful between like quantities.

---
## 14. A concept list that silently returns zero instead of refusing

**What happened (2026-08-03).** The CapEx policy recognized five tangible
concepts and no software one. FIS invests through capitalized software —
`PaymentsForSoftware` $0.835B in FY2025 against $0.154B of plant — so the engine
read its CapEx as 1.17% of revenue, overstated FCFF by 68% ($2.76B vs $1.62B),
and priced both lanes at ~2.3x market. For FY2014-2021 FIS filed *no* tangible
CapEx fact at all, and `resolve_capex_abs` imputed those holes rather than
reporting that the driver was missing, which also truncated the usable history
from 10 years to 4.

**Tell.** A capital-intensity number that is implausible for the industry, in the
*low* direction. A missing concept does not raise a refusal — it produces a small
number, and small CapEx looks like a high-quality business rather than like
absent data. Every other refusal in this engine is loud; this one is silent.

**Fix.** `developmentSoftware` as a second component class, summed with the
tangible one (`sum_disjoint_components`), suppressed when the selected tangible
concept is `PaymentsToAcquireProductiveAssets` — whose us-gaap definition already
includes software, so summing would double count. Contract fingerprint
`sec-driver-normalization/6`, with FIS and INTU added to the frozen fixture
corpus. Verified by comparing the tangible-only selection against the summed
total for all 26 cohort names plus the four anchors: 28 unchanged, only FIS and
INTU moved.

**Rule.** When a driver comes from an enumerated concept list, the absence of
every concept must be distinguishable from a genuine zero. Before trusting a
driver, check it against the *industry's* plausible range, not only against
internal consistency — and audit the raw filed facts for large recurring
outflows the list does not recognize.

---
## 15. Assuming a data defect from an implausible number

**What happened (2026-08-03).** MPWR's forward EPS of $34.80 implied a ~49% net
margin against FY2025 revenue, which is impossible for a semiconductor issuer, so
it was written up as a contaminated feed. Probing the raw `earningsTrend` ladder
showed `+1y` ends **2027-12-31**, not 2026: consensus has revenue going $2.79B →
$4.12B → $5.18B. Against FY2027 revenue the implied margin is ~33%, which is
normal for MPWR. The number was correct; the assumption about which period it
described was not.

**Tell.** The implausibility was computed by dividing a forward figure by a
*trailing* denominator. Any ratio mixing two periods will look wrong.

**Rule.** Before calling a provider figure contaminated, print the period it
belongs to. Two conclusions from this one: the forward lane's `+1y` is roughly 17
months out and skips the current fiscal year entirely, and the FCFF lane anchors
on trailing actuals — a level gap of two years of growth between the lanes is
structural, not a disagreement about value.

---
## 16. Asking the user a question the code answers

**What happened.** The user was asked whether beta came from a per-ticker
regression or an industry table. It is in the code.

**Answer, for the record.** `resolve_cost_of_equity` (`dcf_model.rs`) blends
`fundamentals.beta_millis` — the ticker's own Yahoo regression beta — at 67% with
the industry prior at 33% (33/67 for through-cycle names), per
`industry-beta-policy-v1.json`. The dominant component is per-ticker. There is no
Hamada relevering anywhere, and there must not be: the own-betas already embed
leverage, so relevering would double-count. Live evidence — CHTR at D/E 4.42x has
`beta_co = 704`; MPWR at D/E 0 has `1710`; WDC at D/E 0.18 has `2166`; T at D/E
1.29 has `422`.

**Corollary that retracts an earlier claim.** The reported
`corr(D/E, r) = -0.48` "cost-of-equity/leverage inversion" was called a model
defect. It is not. It is an empirical property of this cohort's own regression
betas. That claim is withdrawn.

**Rule.** Before escalating, grep. Escalate economic choices, not facts.

---

## 17. Burning the data source on exploratory runs

**What happened.** Yahoo rate-limited twice, both times from repeated live
diagnostic runs, blocking verification for the rest of the round.

**Rule.** Implement from known facts, then do one validation run. Batch every
diagnostic you will need into that one run's output.

---

## 18. Reporting one finding per message

**What happened.** Eight rounds, each surfacing one real defect and stopping. The
defects were real; the cadence was the problem.

**Rule.** Close blocks. Run the chain — anchors, buckets, gate — and report the end
state with the evidence that each link passed its own check. Pause only for a
decision that genuinely cannot be derived from the code.

## 19. Citing a constant without checking it is reachable

**What happened.** The 2026-08-03 cohort review reported that the FCFF lane holds a
hard near-term growth ceiling of ~15%, from
`MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS = 1_200` applied as
`g_stable_base ± 1200`. The constant is declared at `dcf_model.rs:62` and the clamp
is written at `dcf_model.rs:2366` — but line 2366 sits inside a `/* */` block kept
for auditability. It has never executed. The live path is `fcff_driver_wacc`, where
`base_growth_bps` is the plain median of the last five realized revenue growth rates,
with no ceiling at all.

A whole causal group ("Cause A — growth clamped") and the first item of a proposed
work sequence were built on a dead line.

**Why it slipped.** `grep` found the declaration and found a use, and the use looked
like production code. Reading a 30-line window around it did not reveal that the
window was itself inside a comment opened 8 lines earlier.

**Rule.** A constant is not part of the model until its use site is shown to be
reachable. Confirm by counting call sites of the enclosing function, or by deleting
the constant and observing the compiler complain. `grep` proves a string exists, not
that it runs.

**Corollary.** The true defect was worse than the reported one: the FCFF lane observes
no forecast of any kind. Reporting the wrong mechanism would have sent the fix to the
wrong module.

## 20. Re-deriving a claim the record already retracted

**What happened.** The 2026-08-03 fix pass measured
`corr(discount_rate, gap_vs_street) = -0.491` across the 26-name cohort and read it
as fresh evidence of a leverage defect in the discount rate: the overvalued names
(CHTR +254%, T +71%, FIS +59%) carry the lowest rates, the undervalued names
(WDC -53%, MPWR -50%, TER -36%) the highest. The next step drafted was a
leverage-responsive cost of capital.

Section 18 of this same document had already recorded `corr(D/E, r) = -0.48`,
explained it as an empirical property of the cohort's *own regression betas*
(CHTR beta 704 at D/E 4.42x; MPWR 1710 at D/E 0; WDC 2166 at D/E 0.18), and
**explicitly withdrawn** the claim that it was a model defect. It also recorded that
Hamada relevering must not be added, because own-betas already embed leverage.

The measurement was real. The interpretation was one already tried and retracted.

**Why it slipped.** The correlation was recomputed from a fresh capture against a
different variable name (`discount_rate` rather than `D/E`), so it did not look like
the retracted finding. Nothing in the measurement itself carries the memory of having
been litigated.

**Rule.** Before proposing a mechanism for a correlation, grep this document and the
handover for the *quantity*, not for your phrasing of it. A retraction is only worth
writing down if the next agent is required to find it. Search on the number.

**What survived the check, and why it is different.** The cost-of-*debt* term is a
separate defect from the beta question, and it is real:

| name  | net debt | naive FCFF WACC |
|-------|----------|-----------------|
| CHTR  | +$96.2B  | 493             |
| T     | +$148.2B | 680             |
| MPWR  | -$1.4B   | 925             |
| WDC   | -$1.5B   | 925             |
| GOOGL | -$121.7B | 925             |

Every net-cash name lands exactly on the unit-beta cost of equity. Every levered name
is dragged below it, because `r_d = interest_t / avg_debt_t` is a trailing accounting
coupon on debt issued years ago and rises with nothing. WACC is therefore *strictly
decreasing* in leverage — the model asserts a firm can lower its cost of capital
without limit by borrowing. That is an internal coherence failure provable without
Street, and it does not touch beta.

**Rule.** `∂WACC/∂leverage < 0` across an entire cohort is a structural bug, not a
calibration gap. It is checkable with no external reference and belongs in the gate.

**Blocked on evidence, not on effort.** No leverage-responsive spread can be derived
from a single issuer's own accounting history — the observable is one stale coupon.
It needs a rating, a bond yield, a historical risk-free series, or a cross-sectional
fit over a universe. The first three are external references the project does not
carry; the fourth is a two-pass architecture the current per-name pipeline has no
place to put. Sizing that choice is the decision, not the coding.
