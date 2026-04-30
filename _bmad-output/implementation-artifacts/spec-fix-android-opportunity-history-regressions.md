---
title: 'Fix Android opportunity scoring and pricing-history regressions'
type: 'bugfix'
created: '2026-04-30'
status: 'done'
baseline_commit: '63a415aa161caad1b82eadf018c53e20dad5c327'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/current-functionality-prd.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The current Android in-flight changes introduced four coupled regressions: the three-chip scoring selector still behaves like a cycle, migrated saved chart history can disappear when only some ranges exist in the new pricing table, duplicate candles are frozen to the first-seen value instead of allowing later corrections, and Aggressive V2 can over-rank symbols with only one strong signal because sparse buckets saturate too easily.

**Approach:** Keep the current feature direction, but harden the implementation at the owning boundaries: make UI chip selection explicit instead of indirect, merge pricing history across old and new persistence shapes during migration, switch candle dedupe to latest-write-wins, and scale V2 bucket scores against the full evidence budget so sparse data stays informative without dominating rankings.

## Boundaries & Constraints

**Always:** Keep business rules in Android core or repository/persistence boundaries, not in Compose. Preserve on-demand history loading. Preserve Aggressive V2 as the default model while keeping Legacy and Aggressive selectable. Keep persistence and scorer changes covered by focused tests before broad validation.

**Ask First:** Any change that alters the public set of scoring models, removes the cycle action entirely, changes warm-start scope beyond per-ticker on-demand history loading, or introduces a new persistence table or destructive migration.

**Never:** Do not revert the broader in-progress feature work. Do not hide degraded history states by silently dropping fallback data. Do not keep first-write-wins candle semantics if it conflicts with later provider corrections. Do not solve the sparse-ranking problem by moving logic into UI sorting or adding opaque heuristic penalties outside the scorer.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Direct selector | Opportunities tab with Legacy selected, user taps Aggressive V2 chip | UI state changes directly to AggressiveV2 and refreshed rows use the V2 model | N/A |
| Mixed persistence sources | Symbol has migrated pricing_candle rows for one range and raw_capture chart history for another range | Detail history loads both ranges instead of hiding raw-only ranges | N/A |
| Duplicate candle correction | Existing candle and incoming candle share symbol/range/epoch but later value differs | Merged history keeps the later candle value | N/A |
| Sparse positive bucket | Symbol has only one bullish V2 sub-signal available | Bucket score remains positive but bounded by the bucket’s full evidence budget instead of saturating to 100 | N/A |

</frozen-after-approval>

## Code Map

- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt` -- dashboard actions and scoring-model state transitions
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardScreen.kt` -- opportunity scoring chips on the Android dashboard
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/persistence/SQLiteStateStore.kt` -- SQLite migration and per-symbol pricing-history load path
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt` -- detail history hydration and candle merge entrypoint
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/PricingHistoryMerge.kt` -- canonical candle dedupe/merge semantics
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/OpportunityEngine.kt` -- Aggressive V2 scoring and opportunity ranking
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt` -- direct model-selection and cycle behavior coverage
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt` -- persistence + merge semantics regression coverage
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/PricingHistoryMergeTest.kt` -- candle merge overwrite semantics
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/AggressiveV2ScoringTest.kt` -- sparse-evidence scoring invariants

## Tasks & Acceptance

**Execution:**

- [x] `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt` and `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardScreen.kt` -- add an explicit set-model action and use it from the chips while preserving the existing cycle path where it is still useful -- aligns the UI affordance with the behavior
- [x] `apps/android/app/src/main/kotlin/com/discountscreener/android/data/persistence/SQLiteStateStore.kt` -- load pricing history by merging pricing_candle and raw_capture records per symbol/range during the migration window, and update duplicate-row persistence to latest-write-wins -- prevents partial migrations from hiding existing history and preserves corrected candles
- [x] `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/PricingHistoryMerge.kt` and `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt` -- make merge semantics deterministic with incoming/latest candle values winning for duplicate keys -- keeps repository and persistence behavior consistent
- [x] `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/OpportunityEngine.kt` -- normalize V2 buckets against their full expected evidence budget rather than only the observed subset, while keeping missing-evidence buckets nullable -- stops sparse symbols from receiving saturated V2 scores
- [x] `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`, `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt`, `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/PricingHistoryMergeTest.kt`, and `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/AggressiveV2ScoringTest.kt` -- pin the direct-selection, mixed-source history, overwrite semantics, and sparse-score invariants -- protects the regression fixes

**Acceptance Criteria:**
- Given the user taps any scoring-model chip, when the opportunities filter updates, then the selected model matches the tapped chip rather than the next step in the legacy cycle.
- Given a migrated database contains some chart ranges only in pricing_candle and others only in raw_capture, when a detail screen hydrates saved price history, then all saved ranges remain available until refreshed into the new table.
- Given a later chart capture supplies a different candle for the same symbol, range, and epoch, when persistence and repository merge history, then the later candle value becomes the surviving value.
- Given Aggressive V2 sees only a sparse subset of bucket evidence, when the opportunity rows are scored, then sparse bullish evidence remains positive but cannot saturate a bucket to the same level as full-bucket confirmation.

## Design Notes

The two persistence fixes should agree on one rule: the newest known candle for a key wins, and migration-time reads must see both storage shapes. That means the app cannot treat pricing_candle as an all-or-nothing replacement until every legacy row has been represented there.

For V2 scoring, the intent is not to punish missing data as bearish. The intent is to stop a single observed positive metric from claiming full-bucket confidence. Scaling by the bucket’s full evidence budget preserves that distinction cleanly.

## Verification

**Commands:**
- `cd apps/android; ./gradlew :core:test --tests "com.discountscreener.core.engine.AggressiveV2ScoringTest" --console=plain` -- expected: V2 invariant suite passes with updated sparse-evidence semantics
- `cd apps/android; ./gradlew :core:test --tests "com.discountscreener.core.engine.PricingHistoryMergeTest" --console=plain` -- expected: duplicate-candle overwrite semantics pass
- `cd apps/android; ./gradlew :app:testDebugUnitTest --tests "com.discountscreener.android.presentation.dashboard.DashboardViewModelTest" --console=plain` -- expected: direct model selection and cycle behavior pass
- `cd apps/android; ./gradlew :app:testDebugUnitTest --tests "com.discountscreener.android.data.repository.DefaultDashboardRepositoryTest" --console=plain` -- expected: mixed-source history and persistence tests pass
- `./scripts/validate-android.ps1` -- expected: full Android validation passes

## Suggested Review Order

**Model selection behavior**

- Make chip taps select the exact requested scoring model.
  [`DashboardViewModel.kt:105`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt#L105)

- Wire each chip to direct selection instead of indirect cycling.
  [`DashboardScreen.kt:221`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardScreen.kt#L221)

**Pricing-history migration safety**

- Merge legacy raw history with pricing-table rows during on-demand detail loads.
  [`SQLiteStateStore.kt:720`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/data/persistence/SQLiteStateStore.kt#L720)

- Use one latest-write-wins merge rule for duplicate pricing candles.
  [`PricingHistoryMerge.kt:7`](../../apps/android/core/src/main/kotlin/com/discountscreener/core/engine/PricingHistoryMerge.kt#L7)

**Aggressive V2 ranking stability**

- Normalize sparse buckets against the full evidence budget.
  [`OpportunityEngine.kt:59`](../../apps/android/core/src/main/kotlin/com/discountscreener/core/engine/OpportunityEngine.kt#L59)

**Regression coverage**

- Presenter test for exact chip-to-model selection.
  [`DashboardViewModelTest.kt:386`](../../apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt#L386)

- Persistence test for mixed pricing-table and raw-history ranges.
  [`DefaultDashboardRepositoryTest.kt:275`](../../apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt#L275)

- Scoring invariant that one positive signal cannot saturate a bucket.
  [`AggressiveV2ScoringTest.kt:153`](../../apps/android/core/src/test/kotlin/com/discountscreener/core/engine/AggressiveV2ScoringTest.kt#L153)