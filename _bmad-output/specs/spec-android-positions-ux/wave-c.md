# Wave C: Positions and Detail UI

Read SPEC.md, every companion, .memlog.md, and the wave A and B result reports.
Implement wave C only. The parent owns design and architecture.
You are not alone in the codebase. Preserve the earlier waves and all unrelated edits.

## Ownership

- New `ui/dashboard/PositionsContent.kt` and optional shared local position-facts composable.
- Replace only `PositionsList` in `ui/dashboard/DashboardLists.kt`.
- Remove only the old private PositionsContent from `DashboardScreen.kt`; preserve the header work.
- `ui/dashboard/DetailScreen.kt` and `ui/DiscountScreenerApp.kt` for the optional position-facts list.
- Corresponding Positions, Detail, narrow-layout, and large-text UI tests.

Use the Core-derived fields and `presentPositionsSummary` from wave A. Do not calculate money in Compose.
Use a remembered local sort choice. Keep the summary and menu inside the list so they scroll away.
The menu contains Largest position, Needs review, Earnings soon, and the existing ImportBookButton.
Retain POSITIONS_LIST and POSITIONS_GATE_IMPORT test tags at their real controls.
The first row line prioritizes ticker, value, and two-decimal weight.
The second line shows the valuation source/comparison and score with the selected score-model label.
The third line shows the research label and main reason. Keep urgent earnings visible without hiding data warnings.
Give value and weight stable alignment. Use restrained type and color. Avoid repeating the general opportunity badge strip.
Use flexible layouts at 320 dp and larger fonts. Required facts must remain readable, even when rows gain height.
Do not truncate the only refusal reason. Show unavailable facts with a reason in the row or its local facts.
Provide an explicit accessible local facts control. It must not dispatch a Detail action on off-feed rows.
Each local block labels Shares, Average cost, Price, Unrealized P/L, Weight, and quote status.
Use input index with symbol as the list identity. Preserve each duplicate input lot.

Pass all exact-symbol position rows from DiscountScreenerApp to DetailScreen and SnapshotContent.
Keep the earnings card first. Show all matching position lots after it, using the same facts component.
Do not recompute the position with a newer Detail quote. Keep the book quote status visible.

## Tests and evidence

Write failing UI tests first, then update the presentation. Existing tests that require the old Opps strip must change.
Use real mounted Positions and Detail components. Preserve tests for import and off-feed navigation boundaries.
Cover every positions_* Case in examples.md, including duplicate Detail, partial and stored totals, sorts, and accessibility layouts.
Use fixtures from `projectPositions`, so the test exercises the production presenter and quote path.
If practical, save an offline rendered screenshot for normal and narrow layouts under `.agents/workspace/tmp/positions-ux/`.
Do not install or launch a device. Do not reach a live provider from any test.
Run focused UI tests with `--rerun` on the test task. Do not run another worker's Gradle process concurrently.
Do not commit, push, or spawn.
Write `wave-c-result.md` with exact changes, red/green commands, Case mapping, and any remaining gap.
