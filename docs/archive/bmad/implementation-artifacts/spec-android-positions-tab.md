---
title: Android Positions tab
type: feature
created: '2026-09-06'
status: done
route: dispatch
review_loop_iteration: 0
baseline_commit: d35d2ba8132ff87cd09e6f100387165cf3b1e215
context:
  - _bmad-output/specs/spec-android-chase-portfolio/SPEC.md
  - _bmad-output/specs/spec-android-chase-portfolio/examples.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The book lives in SQLite. Off-feed lots stay invisible. Juan cannot see closeness or Opps flags on the names he holds.

**Approach:** Add a Positions tab next to Earnings. Core emits closeness. Presenter projects every lot. Compose paints. Import book is a third caller of the same writer.

## Boundaries & Constraints

**Always:** Core enum `Today`/`Tomorrow`/`ThisWeek`/`Later`/`None` on the New York session day. Off-feed tap is a no-op. Assemble does not hydrate. Join Opps by exact ticker. Scores stay. PHYL import-only.

**Never:** Frozen percent. Device `LocalDate.now()`. Feed add. P&L overlay. `positionSizeBps` from lots. `pm clear`. `sp500`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| POS-PHYL | Lot PHYL 12730000, no Opps row | Row qty 1273, cost, no Act | N/A |
| POS-SORT-ORDINAL | MSFT Today, AMZN Later, PHYL blank | MSFT, AMZN, PHYL | N/A |
| CLOSE-SUN-MON | NY 2026-09-13, report 2026-09-14 | Tomorrow | N/A |
| CLOSE-FRI-MON | NY 2026-09-11, report 2026-09-14 | Later | N/A |
| CLOSE-TZ | Device Madrid, NY 2026-09-07, report same | Today | Compose has no clock |
| POS-TAP-PHYL | Tap PHYL | No Detail, no Yahoo | N/A |
| POS-EMPTY | No lots | Empty state + Import book | N/A |

</frozen-after-approval>

## Code Map

- `apps/android/core/src/main/kotlin/com/discountscreener/core/portfolio/` — closeness + share format. Reuse `PortfolioLot`, `QTY_SCALE`, `EXCHANGE_ZONE`.
- `apps/android/app/.../presentation/dashboard/PositionsPresentation.kt` — project lots × Opps × upcoming dates.
- `apps/android/app/.../DashboardViewModel.kt` — `DashboardTab.Positions` after Earnings. `selectTab` loads earnings log once. Snapshot carries lots. No `ensureDetailLoaded` on assemble.
- `apps/android/app/.../ui/dashboard/DashboardLists.kt` — reuse `OpportunityRowSignals` (make internal). Off-feed has no click.
- `apps/android/app/.../ui/dashboard/ImportBook.kt` — third caller, new test tag.
- `apps/android/app/.../domain/model/DashboardSnapshot.kt` — defaulted `portfolioLots`.
- Do not change `pinHeldFirst`, OpportunityEngine, yaml `/3`, earnings-gate-policy.yaml.

## Tasks & Acceptance

**Execution:**
- [x] core closeness + formatLotShares tests then impl
- [x] presenter projectPositions tests then impl
- [x] tab + list + Import book + ViewModel
- [x] docs: AGENTS.md, project-context.md, advisor-csv-import.md, Android README, parity Chase-book row
- [x] `:core:test` and targeted Positions `:app:testDebugUnitTest` (no `--info`). Full app suite still hits the 3-minute cap.
- [x] live `make android-run-qa` — PHYL 1273 and AMZN 36.2954 already on device from Import. Tokens match Opps after `scoredLots`. PHYL tap is a no-op. CLOSE-* stays the four-tag proof.

**Acceptance Criteria:**
- Given lots including PHYL, when Positions opens, then every lot paints and PHYL has no Act and no Detail tap.
- Given NY dates in CLOSE-*, when Core projects, then the enum matches the table.
- Given a scored AMZN lot, when Positions paints, then the Opps strip is the same snapshot row.

## Implementation Notes

- Tab order: append `Positions` after `Earnings` so earlier ordinals stay.
- `reportEpochDay` added on `EarningsEventRowUi` as source 1. Yahoo `nextEarningsEpoch` is source 2 on scored rows only.
- Core: `Closeness.kt` (`closeness`, `formatLotShares`, `nySessionDay`).
- Presenter: `PositionsPresentation.kt` `projectPositions`. Snapshot carries `portfolioLots`. Assemble does not call `ensureDetailLoaded`.
- Off-feed tap: Compose has no clickable; ViewModel `openDetail` returns when that Positions row exists and has no scored pointer.
- Full `:app:testDebugUnitTest` still hits the 3-minute Gradle cap on `RefreshButtonReplacesRefreshTest` under suite load. Isolation of that test is green. Do not raise the timeout.

## Review Triage Log

| Finding | Verdict | Route | Evidence |
| --- | --- | --- | --- |
| Source 1 reads UTC-cut `upcoming` so NY Today after 20:00 EDT sits in `settled` | high | patch | `earningsEvents` passes UTC today. `upcomingReportDates` maps `upcoming` only. Off-feed PHYL then paints None. |
| Positions joins query/cheap-list `opportunityRows` | high | patch | CAP-9: view filters do not hide the strip. `currentFilter` still carries query. Assemble uses `opportunityRowsLocked(normalizedFilter)`. |
| `openDetail` no-op for any symbol that is not a scored lot while Positions is selected | high | patch | Guard is `positionsRows.none { symbol && opportunity != null }`. Search of a qa name that is not a lot cannot open. |
| Production `portfolioLots` on snapshot has no repository test | high | patch | Fakes already set the field. Dropping `portfolioLots = portfolioLots` leaves Positions empty. Held tests stay green. |
| `upcomingReportDates` never runs through `presentEarningsGate` | high | patch | CLOSE-LOG injects a map. `reportEpochDay` can stay null and those tests still pass. |
| `loadEarningsGate` catch writes an empty gate and leaves old `positionsRows` | medium | patch | Next snapshot recomputes from the empty gate. One frame of stale closeness is real. |
| ViewModel clock is `Instant.now()` with no injectable epoch | medium | patch | CLOSE-TZ tests call `nySessionDay` or inject the enum. Madrid after NY midnight is untested on the ViewModel. |
| CLOSE-YAHOO tests encode noon UTC, not midnight | medium | patch | A Yahoo date-only midnight UTC is the prior NY evening. Add that epoch Case. |
| Scored Positions Detail Prev/Next walks Tracked | medium | patch | `else -> DetailSourceTab.Tracked`. Tap still opens. Map Positions to Opportunities so Prev/Next walks Opps. |
| Live QA checklist has no Positions path | medium | patch | Home is `docs/advisor-csv-import.md`. Index pointer stays a pointer. Do not copy facts into the valuation checklist. |
| `docs/index.md` Advisor CSV line omits Positions | low | patch | One pointer line. Fact stays in `advisor-csv-import.md`. |
| `POS-FLAGS-AMZN` checks tokens on Positions only | low | reject | Tokens are shown. Dual-surface compare is extra surface. |
| Add/search on Positions still grow the feed | false | reject | Spec bans assemble hydrate, not the existing Add control. |
| Two upcoming log rows, map last-write | low | reject | Log is one upcoming print per name in practice. Earliest-wins is extra branch. |
| ScreenDataProjectionEngine omits lots | false | reject | Frozen approach: presenter projects every lot. |
| SPEC.md status draft; CLOSE-TZ one Examples row | false | reject | Fix would edit the product spec. CLOSE-TZ paint is already a second zone assertion. |
| Duplicate Advisor/Sensei r3/r4 under PRD and spec | low | defer | Planning copies from the lock loop. Not product behavior. |
| S-2 Confirm/Cancel then process death | medium | defer | Advisor P1 carry. Frozen matrix does not include restart. |
