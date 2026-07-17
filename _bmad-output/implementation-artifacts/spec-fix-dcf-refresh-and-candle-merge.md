---
title: 'Refresh DCF inputs and canonicalize persisted candle periods'
type: 'bugfix'
created: '2026-07-09'
status: 'done'
baseline_commit: 'b69d82873eda1cd18e2e8227cb67667e514517f0'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/dcf-source-consistency-architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A cached Android DCF is not recalculated when a refresh updates its market-cap, debt, cash, beta, or share-count inputs, so the detail view can keep a stale intrinsic value while analyst estimates change. The detail chart can also show repeated candles because persisted and incoming bars for the same market period are identified only by their exact timestamp.

**Approach:** Recompute an existing DCF from its cached selected timeseries whenever refreshed fundamentals are ingested, without another provider-timeseries request and while retaining its selected-source provenance. Make candle merge identity range-aware so the incoming candle replaces the persisted candle for the same daily, weekly, or monthly period, but never collapses intraday bars.

## Boundaries & Constraints

**Always:** Keep DCF policy/math in `core` and repository work limited to orchestration/cache updates; retain cached DCF source/provenance when recomputing; merge rules must be pure and shared by refresh and SQLite restore; incoming data wins a semantic-period collision; use strict TDD, then run Android verification and focused mutation checks.

**Ask First:** Any change to DCF methodology/calibration (for example, changing WACC/terminal-growth assumptions or reconciling levered FCF with WACC) requires a separate product/model decision.

**Never:** Do not fetch timeseries merely to refresh the DCF; do not claim parity with analysts' forward targets; do not deduplicate `Day` or `Week` intraday candles by calendar day; do not modify unrelated user changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|---------------|----------------------------|----------------|
| DCF refresh | Cached selected timeseries/DCF; newly ingested fundamentals alter beta, debt, cash, or shares | Recomputed DCF and WACC replace stale values; same selected source/provenance remains; no timeseries fetch | If recomputation cannot produce an analysis, retain existing cache and surface current repository failure behavior |
| Daily-bar collision | Existing and incoming Month-range candles have different timestamps on one calendar date | One incoming candle remains | N/A |
| Intraday bars | Two Day/Week candles share a date but differ in timestamp | Both remain | N/A |
| Long-range collision | Existing and incoming Year candles share ISO week, or Five/ten-year candles share calendar month | One incoming candle remains | N/A |

</frozen-after-approval>

## Code Map

- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt` -- ingests refresh fundamentals, owns DCF/timeseries caches, and delegates all persisted candle merges.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/PricingHistoryMerge.kt` -- pure candle merge identity and ordering policy.
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/PricingHistoryMergeTest.kt` -- regression coverage for merge semantics.
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt` -- repository refresh/cache behavior tests.

## Tasks & Acceptance

**Execution:**

- [x] `DefaultDashboardRepository.kt` -- after successful fundamental ingestion, recompute from an already cached selected timeseries and replace its analysis only on success; retain source/provenance and avoid a second provider lookup.
- [x] `DefaultDashboardRepositoryTest.kt` -- first demonstrate stale DCF after a changed-fundamentals refresh, then assert changed WACC/value, preserved source metadata, and no additional timeseries fetch.
- [x] `PricingHistoryMerge.kt` -- replace the exact-epoch-only key with a range-specific semantic-period key: exact epoch for `Day`/`Week`, UTC calendar day for `Month`, ISO week for `Year`, and UTC year-month for `FiveYears`/`TenYears`.
- [x] `PricingHistoryMergeTest.kt` -- cover incoming replacement for same daily, ISO-week, and year-month period plus retention of distinct intraday timestamps.

**Acceptance Criteria:**

- Given a cached DCF and refreshed financial inputs, when refresh completes, then the detail uses a recomputed DCF based on the current inputs without refetching the selected timeseries.
- Given differently timestamped representations of one non-intraday candle period, when histories merge, then exactly one incoming candle is rendered and persisted.
- Given two valid intraday candles on the same calendar day, when histories merge, then both candles remain in chronological order.

## Spec Change Log

## Design Notes

Analyst price targets and this historical-FCF DCF are distinct valuation models. The defect fix guarantees current inputs and correct data identity; it intentionally leaves model calibration for an explicitly scoped valuation-methodology change.

## Verification

**Commands:**

- `./gradlew :core:test` -- expected: core merge regressions and existing tests pass.
- `./gradlew :app:testDebugUnitTest` -- expected: repository refresh regression and existing app unit tests pass.
- `scripts/validate-android.ps1` -- expected: project-standard Android verification passes when SDK configuration is available.

## Suggested Review Order

**Refresh DCF correctness**

- Reuses cached source inputs when a quote snapshot is unavailable, avoiding a hidden provider refetch.
  [`DefaultDashboardRepository.kt:765`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt#L765)

- Recalculates the cached valuation immediately after fresh fundamentals enter the reporting engine.
  [`DefaultDashboardRepository.kt:2646`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt#L2646)

**Candle identity and durability**

- Defines period identity by chart granularity while preserving exact intraday timestamps.
  [`PricingHistoryMerge.kt:36`](../../apps/android/core/src/main/kotlin/com/discountscreener/core/engine/PricingHistoryMerge.kt#L36)

- Rewrites each stored range from the canonical merge so restore cannot revive replaced candles.
  [`SQLiteStateStore.kt:767`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/data/persistence/SQLiteStateStore.kt#L767)

**Regression coverage**

- Exercises snapshot-less refreshes, source preservation, and no additional timeseries request.
  [`DefaultDashboardRepositoryTest.kt:1268`](../../apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt#L1268)

- Exercises timestamp inversions across SQLite captures, where raw epoch ordering once restored the old candle.
  [`DefaultDashboardRepositoryTest.kt:546`](../../apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt#L546)

- Covers each range-specific merge identity and intraday preservation in the pure core rule.
  [`PricingHistoryMergeTest.kt:10`](../../apps/android/core/src/test/kotlin/com/discountscreener/core/engine/PricingHistoryMergeTest.kt#L10)
