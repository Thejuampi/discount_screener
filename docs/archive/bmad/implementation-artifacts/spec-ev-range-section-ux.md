---
title: 'EV Range Section UX — Percentage Rail, Signed Labels, Freshness'
type: 'feature'
created: '2026-05-04'
status: 'ready-for-dev'
context:
  - '_bmad-output/planning-artifacts/android-quant-lens-ux-spec-2026-05-03.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Expected Value Range section in the Lens subtab shows raw dollar amounts in a key-value table, missing the percentage range rail with bear/base/bull markers specified in the UX design, ignoring `freshnessQualifier`, and producing wrong chip labels (divides against `weightedFairValueCents` instead of market price).

**Approach:** Add a presenter-computed `EvRangeRailModel` carrying signed-upside bps for bear/base/bull markers; update `evSection()` to produce percentage-based primary lines, percentage detail rows, and correct footer chips; add a 56 dp Canvas-based `EvRangeRail` composable rendered inside `QuantLensSection`; fix `evRangeLabel()` to compute against market price; pass `marketPriceCents` from the ViewModel into `mapQuantLensReport`.

## Boundaries & Constraints

**Always:**
- Compose is passive — `EvRangeRail` receives a prepared `EvRangeRailModel`, does no computation.
- Dollar amount display stays in Snapshot's `ValuationSection`; Lens shows percentages only.
- `EvRangeRailModel` is only populated when `band == ScenarioWeighted` and all three bps values can be computed.
- Fixed-point convention: upside values in bps (Int), not floats.
- Rail height is fixed at 56 dp.

**Ask First:** None — all design decisions are specified.

**Never:**
- Do not add a range rail to any section other than ExpectedValueRange.
- Do not fetch data, query SQLite, or compute DCF inside Compose.
- Do not change `QuantLensExpectedValueRange` or `QuantLensEngine` core model.
- Do not animate the rail.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| ScenarioWeighted positive-only | low=+800 bps, weighted=+1500 bps, high=+2400 bps | Primary: `Range +8% to +24% · Weighted +15%`; chip: `EV +8..+24%`; rail rendered; rows: Bear +8%, Base +15%, Bull +24% |
| ScenarioWeighted cross-zero | low=-400 bps, weighted=+1200 bps, high=+2400 bps | Primary: `Range -4% to +24% · Crosses zero`; chip: `EV mixed`; rail shows zero line |
| ReferenceOnly 2 anchors | low and high present, no weighted | Primary: `2 anchors · Reference only`; no rail; rows: Low $X, High $X |
| Sparse | band=Sparse | Primary: `1 anchor · EV sparse`; chip: `EV sparse`; no rail; no rows |
| Unavailable | band=Unavailable | Primary: `No current value range`; chip: `EV unavailable`; no rail; no rows |
| Stale ScenarioWeighted | freshnessQualifier=Stale | Rail markers muted; footer chip: `saved Xh ago`; chip label unchanged |

</frozen-after-approval>

## Code Map

- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/QuantLensUiModels.kt` — presenter: add `EvRangeRailModel`, add field to `QuantLensSectionUi`, rewrite `evSection()`, fix `evRangeLabel()`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt` — call site: pass `marketPriceCents` to `mapQuantLensReport`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt` — Compose: add `EvRangeRail` composable, wire into `QuantLensSection`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/QuantLensUiModelsTest.kt` — tests for the new `evSection()` output cases

## Tasks & Acceptance

**Execution:**
- [ ] `QuantLensUiModels.kt` — Add `data class EvRangeRailModel(lowUpsideBps, weightedUpsideBps, highUpsideBps, crossesZero, isStale)` and add `evRailModel: EvRangeRailModel? = null` to `QuantLensSectionUi`.
- [ ] `QuantLensUiModels.kt` — Add `marketPriceCents: Long?` param to `mapQuantLensReport()`, thread it into `evSection(marketPriceCents)`. Compute `lowUpsideBps`/`highUpsideBps` as `(fairValueCents - price) * 10000 / price`. Rewrite `evSection()` primary line, detail rows (percentage-based for ScenarioWeighted), and footer chips per spec. Populate `evRailModel` when ScenarioWeighted and all three bps values are available. Fix `evRangeLabel()` to use `marketPriceCents` not `weightedFairValueCents`.
- [ ] `DashboardViewModel.kt` — Pass `snapshot.selectedDetail?.marketPriceCents` to `mapQuantLensReport(report, marketPriceCents)`.
- [ ] `DetailScreen.kt` — Add `@Composable private fun EvRangeRail(model: EvRangeRailModel, modifier: Modifier)` using `Canvas`. Track: 1.5 dp stroke, `outlineVariant` color (muted when stale). Bear circle (6 dp radius, hollow), weighted diamond (filled), bull circle (6 dp radius, hollow). Zero dashed line when `crossesZero`. Labels below each marker using `labelSmall`. Clamp domain to `min(lowUpsideBps, -200)` .. `max(highUpsideBps, 200)`. In `QuantLensSection`, render `EvRangeRail` between `primaryLine` and detail rows when `section.evRailModel != null`.
- [ ] `QuantLensUiModelsTest.kt` — Add tests covering the five I/O scenarios above: ScenarioWeighted positive, cross-zero, ReferenceOnly 2-anchor, Sparse, Stale footer chip.

**Acceptance Criteria:**
- Given `band == ScenarioWeighted` with positive low/weighted/high bps, when `evSection()` is called, then `primaryLine` is `"Range +X% to +Y% · Weighted +Z%"` and `evRailModel` is non-null.
- Given `band == ScenarioWeighted` with `lowUpsideBps < 0`, when `evSection()` is called, then chip label is `"EV mixed"` and `evRailModel.crossesZero == true`.
- Given `band == ReferenceOnly`, when `evSection()` is called, then `evRailModel` is null and detail rows show dollar amounts.
- Given `band == Unavailable`, when `evSection()` is called, then `primaryLine` is `"No current value range"` and `rows` is empty.
- Given `freshnessQualifier == Stale`, when `evSection()` is called, then footer chips include a `saved` freshness chip and `evRailModel.isStale == true`.
- Given a rendered `EvRangeRail` with `crossesZero == true`, the Canvas draws a zero reference line at the correct x position.
- Given `isStale == true`, marker and track colors are muted (`outline.copy(alpha=0.4f)`).

## Design Notes

**Upside bps computation:**
```kotlin
fun upsideBps(fairValueCents: Long, marketPriceCents: Long): Int? {
    if (marketPriceCents <= 0L) return null
    return (((fairValueCents - marketPriceCents).toDouble() / marketPriceCents.toDouble()) * 10_000.0).toInt()
}
```

**Rail domain mapping:**
```
val clampedMin = minOf(lowUpsideBps, -200)
val clampedMax = maxOf(highUpsideBps, 200)
val span = (clampedMax - clampedMin).toFloat()
fun xFor(bps: Int) = padding + ((bps - clampedMin).toFloat() / span) * (width - 2 * padding)
```

**`signedPercent` compact (row chip):** `"${if (bps >= 0) "+" else ""}${abs(bps) / 100}"` — no trailing `%` per individual marker; the full row chip appends `%` once: `"EV $low..$high%"`.

## Verification

Run `scripts/validate-android.ps1` after implementation to confirm all tests pass.
