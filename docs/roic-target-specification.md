# ROIC target specification

Pins what `ΔNOPAT / ΔIC` means before any harness runs. The brief is explicit that
`ΔNOPAT / ΔIC` *"is not yet a complete target definition"* — this document is the completion, one
decision per row, each with its rationale, **written before any candidate result is inspected.**

This document depends on `docs/valuation-economic-contract.md` (T4.1) — every term below (`NOPAT`,
`InvestedCapital`, absence, issuer class) is defined there, not redefined here — and it is depended
on by `docs/roic-preregistration.md` (T4.4): a pre-registration cannot freeze a target that is not
yet pinned.

**No candidate result is referenced anywhere in this document.**

> **Standing rule, quoted verbatim from the brief:** *"Any subsequent change to the target or
> exclusions is a NEW experiment requiring a new untouched holdout."*

---

## The target quantity, restated precisely

Over a three-year window `[t, t+3]` for one issuer:

```text
target(t) = ΔNOPAT(t, t+3) / ΔIC(t, t+3)
```

where `NOPAT` and `InvestedCapital` (`IC`) are exactly the quantities defined in
`docs/valuation-economic-contract.md` §1 and §2. The seventeen rows below are what makes this
formula a complete, executable target rather than a one-line sketch.

---

| # | Decision | Rationale |
| --- | --- | --- |
| 1 | **Windows are fiscal-year-aligned, rolling, three years wide**: for each issuer and each cutoff year `t` with a complete fiscal-year observation, the window is `[t, t+3]` in that issuer's own fiscal-year sequence — not a calendar-year window. | Matches the primary endpoint's own words, *"the three-year horizon,"* exactly. Wave 1's point-in-time infrastructure (`AnnualObservation`, `AnnualSeries`) is keyed on fiscal year (`fy`/`fp`), not calendar quarters, so a fiscal-year window is the one the evidence chain can actually resolve without inventing a calendar mapping. |
| 2 | **`ΔIC` uses average capital**: `IC(t)` and `IC(t+3)` are each the average of beginning- and ending-year `InvestedCapital` for that fiscal year (not a single point), and `ΔIC = IC(t+3) - IC(t)`. | A stock measured only at year-end overstates the capital base in a growth year (capital raised late in the year has not yet been productive) and understates it in a shrinking year. Averaging is the standard treatment for a flow (`ΔNOPAT`) measured against a stock (`IC`), and it is symmetric with how the window itself spans a multi-year flow. |
| 3 | **Zero lag between investment and return**, matching the economic contract §7: the target does not model a delay between capital deployed and NOPAT earned on it. `ΔNOPAT(t, t+3)` is compared directly against `ΔIC(t, t+3)` over the same window, with no lagged offset. | Consistency with the identity this target ultimately serves (`docs/valuation-economic-contract.md` §7, §10). A lagged treatment would be a *different* economic model of `r`, requiring its own research charter and its own pre-registration — not a free parameter of this target spec. |
| 4 | **No organic/acquired capital split.** `ΔIC` is the **total** change in invested capital; the pipeline has no driver that separately tags acquired versus organically raised capital (`docs/valuation-economic-contract.md` §3). Windows contaminated by a material acquisition are handled by row 5, not by attempting a split the data cannot support. | Honesty over false precision: pretending a split exists when no acquired-capital driver is measured would silently misstate the target. Row 5 is the mechanism that actually protects against acquisition contamination. |
| 5 | **Acquisitions**: a window is **excluded** from the target cross-section if any fiscal year inside it is already excluded from the growth fit under the economic contract §4's acquisition-contamination rule (material acquisition cash in year `Y` excludes the `Y-1 → Y` transition). | Reuses the pipeline's existing, already-measured acquisition exclusion (`AGENTS.md` → SEC FCFF driver normalization) rather than inventing a second, parallel threshold that could disagree with the first. |
| 6 | **Divestitures are not a separate exclusion rule.** No driver resolves divestiture proceeds (economic contract §4: absent, not zero), so a window is **not** specially excluded on the basis of a divestiture, because the pipeline has no evidence with which to detect one. | Excluding on evidence the pipeline cannot see would be arbitrary, not principled. This is recorded as a known blind spot, not a false claim of control — the same honesty standard §4 applies to acquisitions cannot be applied symmetrically here because the underlying evidence does not exist. |
| 7 | **Impairments are not backed out.** A goodwill or asset write-down that reaches `NOPAT` through `PretaxIncome` in a window year is left as filed. If it drives that year's `NOPAT` non-positive, row 15 governs. | There is no impairment-flag equivalence class in the pipeline, and deciding by hand which write-downs are "real" would require judgement the pipeline has no principled way to automate — and doing it selectively would violate constraint 1 (no ticker special-cases). |
| 8 | **Restructurings are not backed out**, for the identical reason and by the identical mechanism as row 7 — filed as reported, no ad hoc adjustment, no per-issuer judgement call. | Consistency with row 7 and constraint 1. |
| 9 | **Currency effects require no adjustment here** because they are already excluded upstream: the SEC FCFF driver normalization boundary accepts only domestic, consolidated, USD-denominated annual evidence (`AGENTS.md` → SEC FCFF driver normalization: *"Missing approved, consolidated USD annual evidence is unavailable — not zero or an imputed cash flow"*). | The currency question is answered once, at extraction, not re-answered at the target-specification boundary — restating a currency-translation rule here would either duplicate or silently contradict the extraction-layer rule. |
| 10 | **Restatements**: the *realized* half of any comparison (what actually happened) uses the **latest** filed value for each period, reflecting subsequent restatements; the *predicted* half (what was knowable) uses only `as_of`-cutoff evidence, per Wave 1's point-in-time discipline. | This is precisely why Wave 1 built both a `latest()` view and cutoff-aware (`as_of`) resolution on `AnnualSeries`: a prediction must be blind to information not yet filed at `t`, but the realized outcome it is graded against is not required to be blind to a later correction of the historical record. |
| 11 | **`ΔIC = 0` is excluded**, not treated as an infinite or undefined-but-silently-dropped ratio. The window carries no measurable target and is recorded as such. | A zero-width denominator makes the ratio undefined, not zero and not infinite — treating it as either would be exactly the kind of fabricated value the economic contract §9's absence discipline forbids. |
| 12 | **Small denominators**: a window is excluded when `|ΔIC|` is less than 1% of `IC(t)` (row 2's average-capital base). | A near-zero denominator arithmetically amplifies any noise in `ΔNOPAT` into an explosive or wildly-signed "return," the identical failure mode `AGENTS.md`'s no-naked-averages rule exists to prevent at the aggregation layer. The 1% floor is a new parameter this row introduces (no existing repo constant covers it), and it is therefore itself bound by the standing rule above: changing it later is a new experiment, not a tuning pass. |
| 13 | **Negative invested capital**: a window whose beginning-of-window `IC(t)` is non-positive is excluded. | Reuses the pipeline's own already-measured convention: the T2.0 probe already filters `invested_capital > 0.0` and separately counts and names issuer-years with a "capital deficit." This row adopts that existing, measured convention rather than inventing a parallel one. |
| 14 | **Negative changes in invested capital are *not* excluded on sign alone.** A shrinking capital base (buybacks, debt paydown) is real, economically meaningful evidence. A window with `ΔIC < 0` is retained once it clears row 12's denominator floor, but is a population secondary diagnostics should report separately from `ΔIC > 0` windows, because the two describe different economic situations (capital returned versus capital deployed) even though the same formula computes both. | Excluding on sign alone would discard real, measured evidence exactly where Decision 1 says not to — coverage is diagnostic, not a promotion gate, and discarding usable evidence to make a target "cleaner" is the coverage-shrinking move Decision 1 exists to prevent. |
| 15 | **Negative NOPAT**: a window whose beginning- or ending-year `NOPAT` is non-positive is excluded. | Reuses the pipeline's own already-measured convention: the T2.0 probe's realized-growth calculation is explicitly guarded on `first.nopat > 0.0 && last.nopat > 0.0`, because a loss-making base makes both a log-growth rate and a return-on-capital ratio economically meaningless, not merely small. |
| 16 | **Issuer-class exclusions**: only `BusinessClass::OperatingNonFinancial` issuers are in scope. `FinancialServices` issuers are excluded structurally — they are valued on return on equity against book equity, a different return quantity on a different base (economic contract §11), not a special case of this target. `NotEligible` and `Unclassified` issuers are excluded as a closed-world refusal with no `NOPAT`/`IC` semantics at all. | The economic contract already establishes this (§11); this row exists so the exclusion is a stated target-specification decision, not something a reader has to infer by cross-referencing a different document. |
| 17 | **All data-quality exclusion rules, collected in one place**: (a) the acquisition-contamination exclusion (row 5); (b) the currency/consolidation boundary, enforced upstream (row 9); (c) the PIT discipline separating predicted from realized evidence (row 10); (d) `ΔIC = 0` (row 11); (e) the small-denominator floor, `|ΔIC| < 1% of IC(t)` (row 12); (f) non-positive `IC(t)` (row 13); (g) non-positive `NOPAT` at either window end (row 15); (h) the issuer-class restriction to `OperatingNonFinancial` (row 16). Rows 7, 8, 14 are explicitly recorded as **non-exclusions** — evidence that is retained rather than backed out or dropped — and row 6 is recorded as a **known blind spot**, not a rule. | The brief asks for this as its own numbered item, distinct from the itemized rows above. Its job is to be the checklist a reader can audit against the rows above and confirm nothing that should exclude a window was left unstated — and, equally, that nothing was excluded without a row saying so. |

---

## What this document does not do

It does not choose an estimator, a benchmark, a materiality threshold, a resample count, or a
multiplicity rule — those are `docs/roic-preregistration.md`'s job (T4.4), and that document depends
on this one being frozen first. It does not report any measurement against any candidate. It exists
solely to make `ΔNOPAT / ΔIC` a target a harness could compute unambiguously from filed evidence,
issuer by issuer, window by window, with every edge case named in advance.
