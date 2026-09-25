# Product call — Android FCF score (2026-08-23)

**To:** independent reviewer (equity / quant / product)
**From:** Juan, Discount Screener (personal workstation)
**Need:** one letter per question. No extra essays unless a letter is unsafe.

This is **ranking**, not DCF. Valuation already uses residual income for banks, insurers, lenders, and managed care. Payment networks (`V`, `MA`) stay on industrial FCFF. The questions below are only about the **fundamentals score** on Android Aggressive V3 / V4 / V5.

Please mark anything that would change how names rank, not only how the Score tab looks.

---

## Already decided (do not reopen)

| Topic | Rule now in code |
| --- | --- |
| Absolute FCF sign vote | Removed. $1 no longer scores like $1T. |
| Size | FCF as a **yield** against firm size. |
| Size order | Reported EV if > 0. Else equity + debt − cash when cash and debt are known and **net debt ≠ 0**. Else equity cap. Missing cash is not zero cash. Negative cash or debt is ignored. |
| Multi-year numerator (V4/V5) | Last 3–5 annual FCF prints. Robust centre when five years exist. Median for two to four years. TTM if the series cannot speak. |
| Financial services | No industrial FCF yield. Score tab shows a skip reason. Visa / Mastercard **do** take the industrial yield (issuer override). |
| Conversion (FCF / OCF) | Silent after a yield vote. Still speaks when yield has no size. |
| Sector FCF centre | Median/MAD of **one** size kind. EV members first if five exist. Equity members only if EV is too thin. A name uses `FCFy§` only when its own size kind matches that centre. |
| Sector band | Centre ± 400 bps when § applies. Else absolute −2% to +8%. FCF weight 22 points. OCF fallback weight 10 points. |

Windows still uses FCF / market cap and the old sign vote. This call is for **Android**. Say if Windows must match before Android ships.

---

## How to answer

For each question pick **one** letter. If two letters are both acceptable, pick the one you would ship first.

---

### Q1 — Numerator and denominator

**Current.** Yahoo FCF is after interest (equity-ish). Size prefers **enterprise value** (firm). Leverage can sit in the denominator after interest already left the numerator.

**Why it is open.** Standard FCF/EV is cleaner with **unlevered** cash (FCFF). Standard equity yield is FCF / market cap. Mixing levered FCF with EV can count debt twice.

**A.** Keep levered FCF / EV (shipped). Accept the mix. Label stays `FCF / EV`.
**B.** Switch size to **equity cap** for this Yahoo FCF. Label `FCF / market cap`.
**C.** Keep EV only when an unlevered numerator exists. Until then, equity cap.
**D.** Other (one sentence).

---

### Q2 — Unknown or ineligible issuers

**Current.** Banks and insurers skip industrial FCF. `Unclassified` (empty or unknown sector/industry) and `NotEligible` (ETF, fund, REIT, …) still take the industrial yield. V5 already refuses **leverage** for those classes.

**A.** Keep industrial FCF for unknown / not-eligible (fail-open, same as V4 leverage history).
**B.** Refuse FCF for `Unclassified` and `NotEligible`, with a visible reason, same spirit as valuation.
**C.** Refuse only `NotEligible`. Leave `Unclassified` on industrial FCF.
**D.** Other (one sentence).

---

### Q3 — Zero net debt, reported EV vs constructed EV

**Current.** Yahoo **reported** EV > 0 always labels `FCF / EV`, even when net debt is zero. **Constructed** EV (equity + debt − cash) labels `FCF / market cap` when net debt is zero.

**A.** Trust reported EV. Keep the two labels as they are.
**B.** If net debt is zero (or missing), always treat size as equity and label `FCF / market cap`, even when Yahoo sent an EV.
**C.** Other (one sentence).

---

### Q4 — OCF fallback band

**Current.** No TTM FCF → score **OCF / size** on the **same** −2% to +8% band as FCF. Weight 10, not 22. No sector centre. Label says `OCF`.

OCF is usually larger than FCF, so the band is easier to max.

**A.** Keep the FCF band for OCF.
**B.** Give OCF its own looser band (state the two ends in % if you can).
**C.** Drop the OCF fallback. No cash vote without FCF.
**D.** Other (one sentence).

---

### Q5 — Empty slots in the fundamentals budget

**Current.** V4/V5 divide the bucket by a **fixed** 110-point budget. A bank skips FCF (22) and often leverage. Those zeros stay in the denominator. ROE and multiples shrink even when they max out.

**A.** Keep a fixed budget. Missing terms pull the score toward zero (coverage penalty).
**B.** Divide only by the weights that actually voted. A bank’s ROE can reach a full fundamentals print.
**C.** Other (one sentence).

---

### Q6 — Score tab: stock vs reference

**Current.** Multiples, FCF yield, sector ROE, and sector leverage print **stock vs reference**. Absolute ROE, growth, ND/EBITDA, D/E, and cash vs debt still print **points only**.

**A.** Leave those terms as points only.
**B.** Add the same stock-vs-reference line to every fundamentals term that has a band.
**C.** Other (one sentence).

---

### Q7 — Failed robust centre on a five-year FCF series

**Current.** Five annual prints: use a robust (trimmed) centre. If the sample has **no width** (all but a spike sit on one number), use the **median** (the spike does not win). If the trim fails **and** the sample has width, use the **TTM** print. That TTM print can be the spike the trim just dropped.

**A.** Keep TTM after a failed trim with width.
**B.** Use the series **median** after a failed trim (outliers can still sit in the middle of an even sample).
**C.** Refuse the multi-year vote and show no FCF yield (or TTM only with a flag).
**D.** Other (one sentence).

---

### Q8 — Sector FCF window vs symbol FCF window

**Current.** The **symbol** yield (V4/V5) can be a 3–5 year centre. The **sector** FCF centre is **TTM only** (`computeSectorBenchmarks` has no annual series per member). `FCFy§` can print a multi-year rate against a TTM sector rate.

**A.** Keep TTM sector vs multi-year symbol. Label is enough.
**B.** Build the sector centre from the same multi-year rule (needs annual FCF on members).
**C.** Force the symbol onto TTM whenever § is used, so both sides match.
**D.** Other (one sentence).

---

### Q9 — V3 vs V4/V5 cash window

**Current.** V4 and V5 can use the annual FCF series. V3 still uses **TTM only** (no timeseries passed into the vote).

**A.** Leave V3 on TTM. V4/V5 may use the series.
**B.** Pass the series into V3 so all three models share one cash window.
**C.** Other (one sentence).

---

### Q10 — Windows parity

**Current.** Android ranks on the new yield. Windows still ranks on FCF / market cap and a sign vote.

**A.** Android may ship alone. Windows later.
**B.** Do not treat Android ranking as final until Windows matches.
**C.** Other (one sentence).

---

## Reply template (copy back)

```
Q1  B
Q2  B
Q3  N/A — superseded by Q1
Q4  B
Q5  B
Q6  B
Q7  B
Q8  C
Q9  A
Q10 A

Notes:
```

**Q1 (B).** `cashFlowSizeForYield` (SectorBenchmarks.kt) is its own function, not shared with the
Size order used by leverage or multiples. Switching it to equity cap only touches the FCF term.
Applies to V3/V4/V5 alike, since they all call the same function.

**Q3 (N/A).** Once Q1 ships, `cashFlowSizeForYield` never returns EV, so the reported-vs-constructed
question has nothing left to answer for this term. Two follow-ups in the same change:
- Delete the EV branch in `cashFlowSizeForYield` (lines 65–74), not just stop calling it.
- The "Already decided" sector-FCF-centre rule ("EV members first if five exist… `FCFy§` only when
  its own size kind matches") goes dead too — every member is now equity-sized. Simplify that rule
  to plain equity-based median/MAD in the same revision. Don't leave the stale EV branch as dead code.

**Q4 (B).** Direction is right — reusing the FCF band overstates OCF because OCF has no capex
deducted. I don't have a measured OCF/FCF ratio for this universe, so I won't invent exact
thresholds. Provisional band: **0% to +10%** (vs FCF's −2% to +8%), shipped as a stated estimate,
not a backtested constant. Flag it for calibration once real OCF/FCF ratios are pulled — don't let
this provisional pair calcify into an undefended magic number the way the old V2 constants did.

**Q5 (B), scoped.** Adaptive budget applies only to terms a **class** structurally cannot have —
a bank skipping the FCF term because it's a bank. It does **not** apply when an eligible,
non-exempt name's term fails to vote for a missing-data reason (e.g., an industrial name's leverage
vote failing for lack of debt/cash fields). That case keeps counting against the fixed denominator
(current behavior). Otherwise a sparse-data name would out-score a well-covered peer on fewer,
cherry-picked terms — the same "missing data becomes a free pass" pattern AD-VM-002 already rejects
for FCFF eligibility.

**Q6 (B).** Display-only, no ranking impact.

**Q7 (B), with a precondition.** Use the series median after a failed trim with width, not raw TTM.
Before shipping: confirm the median call used here is a true median (average of the two middle
values at even n), not a bare `sorted[len/2]` index. That exact bug shipped once already in
`driver_resolution.rs`'s cost-of-debt fit — don't let it reappear on the FCF path.

**Q8 (C), accepted as a known asymmetry.** Forcing the symbol onto TTM when `§` applies is the
right call given building a multi-year sector centre needs new per-member annual-series
infrastructure not yet justified. Note for the record: this means two peers in the same universe
can score the FCF term on different windows (TTM vs multi-year) purely because one sector has a
`§` benchmark and the other doesn't. Accepted, not silently absorbed — revisit if it proves
material once sector coverage grows.

**Q9 (A).** V3/V4/V5 are already allowed to diverge by design (multi-year numerator is
scoped to V4/V5 in the "Already decided" table). No change needed.

**Q10 (A).** Android ships alone; Windows parity is separate work. This is a deliberate,
scoped exception for the scoring-engine slice, not a reversal of Windows-first project
sequencing generally — the rest of the codebase still follows that order.
