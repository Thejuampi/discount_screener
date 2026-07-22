---
title: Android Quant Lens UX Specification
phase: 3 Plan Repair Round 2
date: 2026-05-03
owner: Sally, UX Designer
status: Repaired Draft
artifact_type: ux_spec
platform: Android
selected_use_cases:
  - Evidence Strength
  - Expected Value Range
  - Correlation Risk
  - Trend Reliability
  - Similar Setups
inputs_read:
  - docs/MIT-Quant-Bible.md
  - _bmad-output/project-context.md
  - apps/android/README.md
  - apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardScreen.kt
  - apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardLists.kt
  - apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt
  - apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/EstimatesScreen.kt
  - apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt
---

## Overview

## Purpose

Quant Lens adds five compact statistical lenses to the existing Android workstation flow so an operator can judge whether an apparent opportunity is supported, fragile, locally correlated, technically reliable, or similar to current peers before drilling too deeply into a ticker.

The UX must feel like a dense financial workstation on a phone: small labels, quick scanning, explicit data quality, no marketing language, no formulas in primary UI, and no probability claims that sound more certain than the data supports.

## Existing Surface Fit

Quant Lens should extend the existing surfaces instead of creating a new dashboard destination.

- Dashboard tabs stay: `Opps`, `Upside`, `Watch`, `System`, `Estimates`.
- No new global `Quant Lens` dashboard tab.
- List rows in `Opps`, `Upside`, and `Watch` gain a compact Quant Lens chip strip.
- Symbol detail gains a third subtab: `Snapshot`, `Lens`, `History`.
- The default row tap behavior stays unchanged: tapping a row opens the selected ticker detail.
- Back, Prev, Next, Watch, Unwatch, Refresh, Add, profile switching, and opportunity scoring controls keep their current roles.

## Design Principles

- Show uncertainty as a first-class state, not as hidden absence.
- Keep primary screens free of formulas, educational prose, and implementation vocabulary.
- Use evidence language, not prediction certainty. Prefer `Evidence strong` over `74% likely`.
- Keep Compose passive. The presenter supplies labels, severity, ordering, state, colors, and counts; composables render and emit actions only.
- Preserve mobile density. Quant Lens is a signal layer, not a report page.
- Use existing chip, FlowRow, LazyColumn, chart-range, and section patterns where practical.

## Information Architecture

### Dashboard Row Placement

In `OpportunityList`, place `QuantLensStrip` immediately after `OpportunityRowSignals(row)` and before the existing score/metric FlowRow.

In `TrackedList`, place `QuantLensStrip` immediately after `TrackedRowSignals(row)` and before `TrackedRowMetrics(row)`.

`Watch` uses the same tracked-row treatment because it renders filtered tracked rows.

Do not show Quant Lens chips in `System` or `Estimates`.

### Detail Placement

Update the detail tab row to this order:

1. `Snapshot`
2. `Lens`
3. `History`

`Snapshot` keeps the current price/fair/discount headline. Add a one-line Quant Lens mini strip immediately below that headline and above the chart-range chips.

`Lens` is a dedicated full-height LazyColumn with these sections in order:

1. `Evidence strength`
2. `Expected value range`
3. `Correlation risk`
4. `Trend reliability`
5. `Similar setups`

`History` remains focused on saved price and analyst-target history. Do not move existing history behavior into Lens.

### Detail Navigation Behavior

- Opening a row lands on `Snapshot`, as today.
- Tapping the `Lens` subtab opens the five-section Quant Lens view.
- Tapping a Quant Lens chip in the `Snapshot` mini strip switches to `Lens` and scrolls to the matching section.
- Dashboard list chips are passive; tapping anywhere on the row opens detail as today.
- Prev and Next preserve the active detail subtab. If the operator is on `Lens`, moving to the next ticker keeps them on `Lens` for side-by-side scanning.
- Back returns to the prior dashboard tab and preserves the list scroll position if the existing navigation state supports it.

## Dashboard Quant Lens Strip

### Strip Layout

The strip is a bounded `FlowRow` of compact status chips. It follows the existing dashboard row rhythm: 6 dp horizontal spacing, 2 to 4 dp vertical spacing, and at most one text line per chip.

Rows are summaries, not reports. The detail `Lens` subtab is the only place that shows all five full lens states, reasons, and reference values.

Row summaries use this priority order:

1. Evidence
2. EV, only when a three-anchor weighted range is available
3. Corr, only when local return correlation is Elevated or High
4. Trend, only when a primary fit label is available
5. Similar, only when at least three qualifying comparable symbols are available

Visible row chip caps:

- Width at or above 360 dp: show at most three Quant Lens chips.
- Width below 360 dp: show at most two Quant Lens chips.
- If the priority list has more chips than the cap, drop lower-priority chips from the row and keep their full states in detail.
- Do not add horizontal scrolling for row chips.
- Do not let a row chip wrap internally; the presenter supplies compact text before a chip can exceed one line.

If the row has no Quant Lens model yet, show one chip: `Lens loading`.

If the presenter cannot produce a row summary without detail-only fetches, show one chip: `Lens unavailable` or `Lens sparse`, based on presenter state. Do not fetch missing chart, DCF, history, or comparable data just to improve a row chip.

For any visible row-summary chip, use the presenter’s explicit structured label for that use case: unavailable, sparse/insufficient, stale, provisional/partial, or available. If the chip is hidden by the row cap, keep the full state visible in detail.

Each row chip is built from canonical row-summary `lensStates` entries supplied by the presenter, not inferred in Compose:

- Primary status text, such as `Trend sparse`, `EV stale`, or `Evidence provisional`.
- Optional freshness qualifier text, such as `saved 2h ago`, `based on saved returns`, or `Updated now`.
- Optional count/source qualifier text, such as `2 comparables` or `3 anchors`, when a sparse label needs count context.
- Severity/color token.

When sparse/insufficient and stale both apply, the row chip primary status stays sparse/insufficient and the stale condition appears only as the freshness qualifier. Composables must not parse freeform reason text to decide whether a chip is stale, sparse, or available.

### Row Chip Labels

| Use case | Available row labels | Sparse/insufficient label | Stale label | Unavailable label |
| --- | --- | --- | --- | --- |
| Evidence Strength | `Evidence strong`, `Evidence provisional`, `Evidence mixed` | `Evidence sparse` | `Evidence stale` | `Evidence unavailable` |
| Expected Value Range | `EV -4..+12%`, `EV +8..+24%`, `EV mixed` | `EV sparse` | `EV stale` | `EV unavailable` |
| Correlation Risk | `Corr low`, `Corr elevated`, `Corr high` | `Corr sparse` | `Corr stale` | `Corr unavailable` |
| Trend Reliability | `Trend reliable`, `Trend moderate`, `Trend noisy`, `Trend flat` | `Trend sparse` | `Trend stale` | `Trend unavailable` |
| Similar Setups | `Similar 3`, `Similar close`, `Similar near` | `Similar sparse` | `Similar stale` | `Similar unavailable` |

For widths below 360 dp, use compact variants only if needed to keep the row professional:

| Full label start | Compact start |
| --- | --- |
| `Evidence` | `Ev` |
| `Correlation` or `Corr` | `Corr` |
| `Trend` | `Tr` |
| `Similar` | `Sim` |

Do not abbreviate `EV`, because it is already compact.

### Row Color Semantics

Use the existing signal color language:

- Supportive/low-risk/reliable: bullish green family.
- Mixed/medium/provisional: amber or tertiary family.
- High-risk/noisy: bearish red family.
- Sparse/stale/unavailable: outline or muted surface variant, with stale using the existing amber warning tone.

No row chip should use a color that implies an action stronger than the existing `Act`, `Watch`, or `Avoid` decision chip.

## Detail Lens Screen

### Lens Header

At the top of `Lens`, show:

- Symbol title is already in the app bar.
- A compact line: `Quant Lens`.
- A five-chip summary strip using the same labels as dashboard rows.
- A window selector using existing chart range labels: `1D`, `1W`, `1M`, `1Y`, `5Y`, `10Y`.

The window selector should share the same selected range as the Snapshot chart range. Changing it in Lens updates the same route state used by Snapshot.

Do not show explanatory copy under the header.

### Section Pattern

Each Lens section uses the same compact structure:

- Section title.
- Primary state chip.
- One primary value line.
- Optional compact evidence rows or mini visualization.
- Source/freshness chips at the bottom when available.

Use full-width section bands or unframed column groups. Do not nest cards.

## Use Case Specs

### 1. Evidence Strength

#### Evidence Strength User Need

The operator needs to know whether independent signals are reinforcing the same thesis or whether one loud metric is masking conflicting evidence.

#### Evidence Strength Primary Labels

- Section title: `Evidence strength`
- Strong state: `Evidence strong`
- Mixed state: `Evidence mixed`
- Provisional state: `Evidence provisional`
- Sparse state: `Evidence sparse`
- Stale state: `Evidence stale`
- Unavailable state: `Evidence unavailable`

#### Evidence Strength Detail Content

Primary value line examples:

- `Supports 4 · Conflicts 1 · Neutral 2`
- `Supports 2 · Conflicts 2 · Neutral 3`
- `Signals 2 · Evidence sparse`

Evidence rows:

| Row label | Value examples |
| --- | --- |
| `Valuation` | `Supports`, `Conflicts`, `Neutral`, `Unavailable` |
| `Analyst revision` | `Supports`, `Conflicts`, `Neutral`, `No target` |
| `Trend` | `Supports`, `Conflicts`, `Neutral`, `No chart` |
| `Correlation` | `Elevated`, `Low`, `Unavailable` |
| `Similar setups` | `Close`, `Mixed`, `Sparse`, `Unavailable` |

Source/freshness chips:

- `Updated now`
- `saved 2h ago`
- `3 sources`
- `1 source`

#### Evidence Strength Sparse/Stale/Unavailable Behavior

- Sparse: Show `Evidence sparse` when too few eligible evidence rows exist. The primary value line shows the eligible count, such as `Signals 2`.
- Stale: Show `Evidence stale` when any required upstream signal is old enough for the presenter to mark stale. Preserve the last known evidence rows with muted colors.
- Unavailable: Show `Evidence unavailable` when no eligible evidence rows exist. The section body shows `No eligible evidence`.

#### Evidence Strength What Not To Show

- No internal evidence-updating formula.
- No prior, likelihood, posterior, or denominator labels in primary UI.
- No `probability of success`, `win probability`, or `chance stock rises` copy.
- No raw source weights unless a later debug surface is explicitly designed.

### 2. Expected Value Range

#### Expected Value User Need

The operator needs to see the trade's reward/risk range without mistaking a single fair-value estimate for precision.

#### Expected Value Primary Labels

- Section title: `Expected value range`
- Healthy row label pattern: `EV -4..+12%` or `EV +8..+24%`
- Cross-zero state: `EV mixed`
- Sparse state: `EV sparse`
- Stale state: `EV stale`
- Unavailable state: `EV unavailable`

#### Expected Value Detail Content

Primary value line examples:

- `Range +8% to +24% · Weighted +15%`
- `Range -6% to +11% · Crosses zero`
- `2 anchors · Reference only`
- `1 anchor · EV sparse`

Mini visualization:

- Use a horizontal range rail similar in spirit to the existing valuation range chart.
- Draw the range rail only when three positive scenario anchors are available.
- Show low/base/high or bear/base/bull markers when a full scenario set is available.
- For one or two positive anchors, show reference chips or a simple reference row only; do not draw a range rail and do not show weighted upside.
- Show zero as a subtle reference marker when the range crosses zero.
- Use percentage labels only, not formulas or probability weights.

Bottom chips:

- `DCF`
- `Analyst`
- `3 anchors`
- `Reference only`
- `Updated now`
- `saved 1d ago`

#### Expected Value Sparse/Stale/Unavailable Behavior

- Available: Show a row EV range and detail weighted value only when current price is positive and the selected source has three positive scenario anchors. DCF bear/base/bull is primary; analyst low/primary/high is fallback.
- Sparse/insufficient: Show `EV sparse` when only one or two positive anchors exist. Treat those anchors as reference-only in detail, and do not show a row EV range, weighted upside, or range width.
- Stale: Show `EV stale` when price or fair-value inputs are stale. Keep the last computed range muted and show the stale freshness chip.
- Unavailable: Show `EV unavailable` when current price or all value anchors are missing. The section body shows `No current value range`.

#### Expected Value What Not To Show

- No expected-value formula.
- No scenario probability percentages in primary UI.
- No single large target number that hides the range.
- No wording like `expected return guaranteed`, `best case`, or `safe upside`.

### 3. Correlation Risk

#### Correlation Risk User Need

The operator needs to know whether the selected symbol's saved return series is moving with other current tracked, watchlist, or opportunity symbols already known to the app.

#### Correlation Risk Primary Labels

- Section title: `Correlation risk`
- Healthy row labels: `Corr low`, `Corr elevated`, `Corr high`
- Sparse state: `Corr sparse`
- Stale state: `Corr stale`
- Unavailable state: `Corr unavailable`

#### Correlation Risk Detail Content

Primary value line examples:

- `High local return correlation · 2 peers`
- `Elevated local return correlation · MSFT 0.72`
- `Low local return correlation · 64 overlaps`
- `28 overlapping returns · Corr sparse`

Top pair rows:

| Row label | Value examples |
| --- | --- |
| `Local peer` | `MSFT`, `QQQ`, `XLK` |
| `Correlation` | `0.72`, `0.68`, `-0.42` |
| `Overlap` | `64 returns`, `31 returns`, `Insufficient` |
| `Set` | `Tracked`, `Watch`, `Opportunity` |

Top related symbols:

- Show up to three rows: `MSFT 0.72`, `QQQ 0.68`, `XLK 0.64`.
- If correlation is negative and material, show a signed value such as `-0.42` and use neutral color. Do not call it diversification.
- Detail must say `Local return correlation` or `based on local saved returns` and show sample counts.

#### Correlation Risk Sparse/Stale/Unavailable Behavior

- Sparse: Show `Corr sparse` when the selected window has too few valid pair correlations. Include overlapping return count when available.
- Stale: Show `Corr stale` when returns are based on saved data that is not current. Keep top overlaps muted.
- Unavailable: Show `Corr unavailable` when fewer than three universe symbols have sufficient local history, the selected symbol has no return series, or no paired return data exists. The section body shows `Insufficient local history` or `No paired returns`.

#### Correlation Risk What Not To Show

- No full correlation matrix on mobile primary UI.
- No covariance values.
- No causal wording such as `moves because of`.
- Do not describe this section as data-provider concentration or source-family redundancy.
- No automatic `Avoid` implication from high correlation. High correlation is local return context, not the final decision.

### 4. Trend Reliability

#### Trend Reliability User Need

The operator needs to know whether the visible price, target, or estimate trend has enough sample quality and least-squares fit to summarize the data without overstating noisy movement.

#### Trend Reliability Primary Labels

- Section title: `Trend reliability`
- Healthy row labels: `Trend reliable`, `Trend moderate`, `Trend noisy`, `Trend flat`
- Sparse state: `Trend sparse`
- Stale state: `Trend stale`
- Unavailable state: `Trend unavailable`

#### Trend Reliability Detail Content

Visible classification rules:

- If fitted end-to-end movement is below 200 bps, the visible state is always `Trend flat`, regardless of fit quality.
- Weak fit becomes `Trend noisy` only when fitted end-to-end movement is at least 200 bps.
- `Trend noisy` copy stays direction-neutral. Do not show noisy trends as positive, negative, uptrend, or downtrend claims; directional trend claims are reserved for reliable or moderate non-flat states.

Primary value line examples:

- `Reliable uptrend · R^2 0.74 · 64 samples`
- `Moderate downtrend · R^2 0.48 · 52 samples`
- `Noisy trend · R^2 0.22 · 64 samples`
- `Flat · movement <200 bps · 42 samples`
- `18 candles · Trend sparse`

Fit rows:

| Row label | Value examples |
| --- | --- |
| `Target` | `Price`, `Analyst target`, `Estimate base` |
| `Slope` | `Up`, `Down`, `Flat` |
| `Fit quality` | `R^2 0.74`, `R^2 0.48`, `R^2 0.22` |
| `Samples` | `64 candles`, `4 targets`, `3 reports` |
| `Window` | `1M`, `1Y`, `5Y` |

Supporting visual context may appear as bottom chips only when supplied by the presenter:

- `EMA context`
- `MACD context`

EMA and MACD context can help the operator orient on the chart, but they do not define the Trend Reliability state. The state is based on fitted end-to-end movement, fit quality, and sample sufficiency.

#### Trend Reliability Sparse/Stale/Unavailable Behavior

- Sparse: Show `Trend sparse` when the selected target lacks the minimum samples: price under 20 candles, analyst target under 3 collapsed observations, or estimates under 3 saved reports. Keep the chart visible elsewhere, but the Lens section should not infer reliability from it.
- Stale: Show `Trend stale` when chart data is saved but not current. Include `saved <relative time>`.
- Unavailable: Show `Trend unavailable` when there is no chart data. The section body shows `No chart data`.

#### Trend Reliability What Not To Show

- No duplicate full price chart inside Lens. Snapshot already owns chart inspection.
- No `trend will continue` copy.
- No formulas for least-squares fit, EMA, MACD, or reliability scoring.
- No EMA/MACD wording that implies those signals determine reliability.
- No animated or decorative chart treatment.

### 5. Similar Setups

#### Similar Setups User Need

The operator needs a quick current-universe comparison: which already-known opportunities have similar valuation, evidence, trend, and range features right now?

#### Similar Setups Primary Labels

- Section title: `Similar setups`
- Healthy row labels: `Similar 3`, `Similar close`, `Similar near`
- Sparse state: `Similar sparse`
- Stale state: `Similar stale`
- Unavailable state: `Similar unavailable`

#### Similar Setups Detail Content

Primary value line examples:

- `3 current setups · closest by features`
- `2 qualifying comparables · Similar sparse`
- `No current comparables`

Comparable rows:

| Column | Visible examples |
| --- | --- |
| Symbol | `MSFT`, `ASML`, `TSM` |
| Current setup | `Deep discount`, `Target raise`, `Noisy fit` |
| Similarity | `Close`, `Near`, `Loose` |
| Shared features | `5 shared`, `4 shared`, `3 shared` |
| Current labels | `Upside +18% · Evidence strong`, `EV sparse · Trend noisy` |

Show comparable rows only when at least three qualifying comparable symbols meet the shared-feature minimum. Then show the top three comparable rows, matching the current-only top-3 model. If one or two qualifying comparable symbols exist, do not show a partial success list; show `Similar sparse` with the qualifying comparable count instead, such as `2 qualifying comparables · Similar sparse`.

Put coverage chips above available comparable rows:

- `Current universe`
- `3 shared min`
- `Top 3`

For sparse comparable states, replace rows with count and sample chips:

- `1 comparable` or `2 comparables`
- `3 shared min`
- `Low sample`

#### Similar Setups Sparse/Stale/Unavailable Behavior

- Sparse/insufficient: Show `Similar sparse` when fewer than three qualifying comparable symbols meet the shared-feature minimum, including exactly two. Do not show a success-looking top-3 list or partial comparable rows; show the qualifying comparable or shared-feature count instead, such as `2 qualifying comparables · Similar sparse`.
- Stale: Show `Similar stale` when current comparable inputs are based on saved data that has not refreshed. Keep comparable rows muted.
- Unavailable: Show `Similar unavailable` when no comparable universe exists. The section body shows `No comparable setups`.

#### Similar Setups What Not To Show

- No nearest-neighbor formula.
- No raw distance vectors, feature weights, or K controls in primary UI.
- No `this stock should repeat` wording.
- No date column, backtest-style fields, post-period summary chips, or winner/loser language.
- No long comparable table on dashboard rows.

## Loading And Data Quality States

### Whole Lens State

If detail data is not loaded, the `Lens` subtab body shows:

- `Loading Quant Lens...`

If the presenter reports that Quant Lens is unsupported for the symbol, show:

- Header strip chip: `Lens unavailable`
- Body: `No Quant Lens data`

### Per-Use-Case State Priority

When more than one degraded state applies, display the most actionable state in this priority:

1. `Unavailable`
2. `Sparse` or `Insufficient`
3. `Stale`
4. `Provisional` or `Partial`
5. Available state

This matches the PRD/core rule that missing required base inputs dominate, sample limits are more important than freshness, stale/restored inputs remain visible as freshness context, provisional/partial states are limited but usable, and fully available states are shown only when no higher-priority degraded state applies.

If sparse/insufficient and stale both apply, sparse/insufficient wins the primary chip and stale appears as a secondary freshness qualifier. Examples: primary chip `EV sparse`, freshness qualifier `saved 1d ago`; primary chip `Trend sparse`, bottom chip `saved 2d ago`. If stale and provisional/partial both apply without a sparse/insufficient state, stale wins the primary chip and the limited state appears as secondary context.

Visible degraded copy by lens:

| Lens | Sparse/insufficient body | Stale body | Unavailable body |
| --- | --- | --- | --- |
| Evidence Strength | `Signals 2` | `saved 2d ago` | `No eligible evidence` |
| Expected Value Range | `2 anchors · Reference only` | `saved 1d ago` | `No current value range` |
| Correlation Risk | `28 overlapping returns` | `based on saved returns` | `Insufficient local history` |
| Trend Reliability | `18 candles` | `saved 2d ago` | `No chart data` |
| Similar Setups | `1 comparable · 3 shared min` or `2 comparables · 3 shared min` | `saved comparable data` | `No comparable setups` |

### Do Not Smooth Missing Data

- Missing values stay missing.
- Sparse states must not be converted into neutral states.
- Stale saved values must not look live.
- Unavailable values must not display as zero.

## Mobile Ergonomics

- Use one vertical scroll per screen. Do not add nested vertical scroll regions inside Lens sections.
- Use FlowRow for chip groups. No horizontal scrolling for row chips or section chips.
- Keep tap targets at least 48 dp for tabs and range chips.
- List row chips are passive text chips, so they may be smaller than controls but must remain legible.
- Keep list rows scan-friendly: symbol/company first, existing decision/freshness signals second, Quant Lens strip third, financial metrics fourth.
- On narrow phones, row chip strips may wrap within the visible chip cap. Use compact labels before dropping lower-priority chips, and never exceed two strip lines.
- Section range rails must have stable height between 112 dp and 132 dp so labels do not shift other content.
- Long company names, symbol labels, and setup tags use one line with ellipsis.
- No section should require landscape orientation.

## Passive Compose And MVP Boundary

The UX assumes a presenter-provided UI model, not UI-owned business rules.

Presenter supplies:

- Full and compact chip labels.
- Structured primary status and optional freshness qualifier for each lens chip, already in presentation language.
- State enum for each use case.
- Severity/color token choice.
- Display ordering.
- Counts, source labels, freshness labels, and section rows.
- Selected Lens section target when a Snapshot mini-strip chip is tapped.

Compose screens render:

- Dashboard Quant Lens strip.
- Snapshot mini strip.
- Lens subtab and sections.
- Range selector dispatches.
- Section navigation dispatches.

Compose screens must not:

- Calculate evidence strength.
- Calculate EV ranges.
- Calculate correlations.
- Judge trend reliability.
- Select nearest neighbors.
- Infer sparse or stale states from freeform reason codes.
- Interpret sparse/stale thresholds.

## What Not To Show Anywhere In Primary UI

- Raw mathematical formulas.
- Full derivations from the Quant Bible.
- `posterior probability`, `win probability`, or `chance of success` claims.
- Raw model weights, priors, likelihoods, covariance, distance vectors, or K controls.
- Tutorial text explaining what evidence strength, EV, correlation, trend, or nearest neighbors mean.
- Decorative illustrations, hero panels, nested cards, or marketing copy.
- More than three similar setup rows on a phone screen.
- A new global Quant Lens dashboard tab.
- Any Lens result that silently disappears when data is sparse, stale, or unavailable.

## Acceptance Criteria

1. On `Opps`, each opportunity row shows the existing signal chips, then a Quant Lens strip, then the existing score and metric tokens.
2. On `Upside`, each tracked row shows the existing signal chips, then a Quant Lens strip, then the existing price/fair/discount/upside metrics.
3. On `Watch`, watched rows show the same Quant Lens strip as tracked rows.
4. `System` and `Estimates` do not show Quant Lens chips.
5. Row Quant Lens chips appear in priority order and never exceed the row cap: Evidence, eligible EV, elevated/high Corr, available Trend, Similar available only with at least three qualifying comparable symbols. One or two qualifying comparable symbols produce `Similar sparse` only in the detail Lens section.
6. If all Quant Lens values are loading, the row shows `Lens loading` rather than blank space.
7. If a visible row-summary chip is unavailable, sparse/insufficient, stale, provisional/partial, or available, its chip uses the structured primary status label and optional freshness qualifier supplied by the presenter; states hidden by the cap remain visible in detail.
8. Tapping a dashboard row still opens symbol detail; tapping a row chip does not create a separate list action.
9. Detail tabs show `Snapshot`, `Lens`, `History` in that order.
10. Opening a row lands on `Snapshot` by default.
11. `Snapshot` shows a Quant Lens mini strip below the price/fair/discount headline and above chart range chips.
12. Tapping a Snapshot Quant Lens chip opens `Lens` and focuses the matching section.
13. `Lens` shows five sections in this order: `Evidence strength`, `Expected value range`, `Correlation risk`, `Trend reliability`, `Similar setups`.
14. Each Lens section has a primary state chip and an explicit body state for sparse, stale, and unavailable data.
15. Expected Value Range displays a bounded percentage range only with positive current price and three positive anchors; one or two anchors are reference-only and produce no row EV range.
16. Correlation Risk displays local return correlation, top peer rows, and sample counts, and does not show a full correlation matrix.
17. Trend Reliability displays least-squares movement/fit/sample quality, treats fitted end-to-end movement below 200 bps as `Trend flat` regardless of fit quality, uses `Trend noisy` for weak fit only when movement is at least 200 bps, and does not duplicate the full price chart or let EMA/MACD define reliability.
18. Similar Setups displays the top three current comparable rows only after at least three qualifying comparable symbols exist, shows exactly two comparables as `Similar sparse` with count rather than partial-list success, and does not expose raw nearest-neighbor distances or backtest-style fields.
19. Prev and Next preserve the active `Lens` subtab so operators can compare Lens sections across adjacent tickers.
20. Back from detail returns to the originating dashboard tab.
21. Narrow phone layouts respect the two-chip row cap below 360 dp, wrap without horizontal scrolling, and use compact labels before dropping lower-priority chips.
22. No primary UI surface displays formulas, educational prose, or overconfident probability language.
23. Compose receives all Quant Lens labels, structured primary statuses, freshness qualifiers, and states from presentation models. It does not compute thresholds, parse reason text, or own business logic.
24. Stale Lens values show freshness context, such as `saved 2h ago`, instead of appearing live.
25. Sparse states stay visible and are never rendered as neutral, zero, or absent values.

## Manual QA Scenarios

1. Narrow rows: At 320 dp and 360 dp row widths, verify row Quant Lens strips respect the two-chip and three-chip caps, stay legible, avoid horizontal scrolling, and do not overlap score or metric tokens.
2. No-fetch row chips: On a cold or warm-start list, verify rows show `Lens loading`, `Lens sparse`, or `Lens unavailable` without triggering chart, DCF, history, or comparable fetches for non-selected symbols.
3. Tab and navigation: Open detail from `Opps`, switch `Snapshot` to `Lens`, tap Snapshot mini-strip chips, use Back, Prev, and Next, and verify the active tab, focused section, selected range, and originating dashboard tab behave as specified.
4. Copy audit: Search visible strings for probability, advice, formula, hidden-model, and backtest-style language; verify visible evidence labels say `Evidence`, not internal model terminology.
5. Sparse/stale/unavailable matrices: Force each lens into unavailable, sparse/insufficient, stale, provisional/partial, and available states and verify the primary chip follows the shared precedence while secondary chips keep sample, anchor, or freshness context visible.
6. EV anchor honesty: Verify one or two positive anchors show reference-only detail rows, no weighted upside, no range rail, and no row EV range; verify three positive anchors unlock the row range and detail weighted value.
7. Correlation honesty: Verify Correlation Risk says `Local return correlation`, shows overlapping return counts, uses current tracked/watch/opportunity peers only, and does not describe data-provider concentration.
8. Trend honesty: Verify Trend Reliability labels come from fitted end-to-end movement, R^2, and sample count; movement below 200 bps shows `Trend flat`, weak fit with movement at least 200 bps shows `Trend noisy`, noisy states avoid up/down or positive/negative claims, and EMA/MACD appear only as supporting context chips if present.
9. Similar setup honesty: Verify Similar Setups rows in detail show only current symbols, current setup reasons, similarity, shared-feature counts, and current labels; exactly two qualifying comparables shows `Similar sparse` with count and no comparable rows; no comparable rows appear on dashboard or row-chip selections with fewer than three qualifying comparables; no dates, backtest-style fields, post-period summaries, or winner/loser language appear.

## Open Implementation Notes For Later Phases

- The exact numeric thresholds for strong/provisional/mixed evidence, EV range quality, low/elevated/high correlation, trend reliability, and similar setup sufficiency should be defined in the functional core or presenter-facing domain models, not in Compose.
- The detail route will need a `Lens` subtab value and an optional focused Lens section target.
- Snapshot chip taps require an action that switches to `Lens` and records the focused section.
- Similar setup computation must avoid expensive startup work. It should use current in-memory universe features only and must not introduce backtest-style modeling.
- Any provider-derived gaps must surface as sparse, stale, or unavailable states rather than optimistic defaults.
