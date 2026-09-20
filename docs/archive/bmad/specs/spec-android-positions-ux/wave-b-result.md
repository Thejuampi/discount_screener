# Wave B result

## File changes

- Added `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/ReturningDashboardHeader.kt`.
  It owns the measured header layout, scroll state, nested-scroll connection, threshold, pinning, and accessibility listener.
- Updated `DashboardScreen.kt`.
  The title, actions, status strips, search, and tabs now use the returning header.
  The content wrapper owns the vertical nested-scroll connection and a zero-consumption boundary scroll state.
- Updated `TickerSearchBar.kt` with an optional focus callback.
- Added `ReturningDashboardHeaderTest.kt` with mounted and pure-state coverage.
- Boundary recovery now accepts only positive unconsumed motion, so continued motion toward later list items keeps a hidden header hidden.
- The header now uses an internal vertical scroll and bounded parent measurement. Short hosts keep the full control group reachable inside the header viewport.

## Red and green evidence

The first focused test run failed at test compilation because the new header symbols did not exist.

The first implementation run then found two behavioral failures:

- Empty search focus changed the measured header height by two pixels.
- A programmatic data update test called `setContent` twice.

The tests now capture the focused natural height and use one mounted composition with mutable state.

The bounded focus follow-up first found a two-pixel focused-height difference in the new Back test.
The test now captures the focused natural height before it checks the hide transition.

The boundary follow-up red run failed the new bottom and pure negative-boundary tests.
The one-direction guard then made both tests pass.

The B10 red run failed the short expanded-search bounds and reachability test.
The bounded measurement and internal scroll then made it pass.

The final focused command passed all 23 tests:

```text
./gradlew :app:testDebugUnitTest --tests com.discountscreener.android.ui.dashboard.ReturningDashboardHeaderTest --rerun
BUILD SUCCESSFUL
```

The related dashboard command also passed:

```text
./gradlew :app:testDebugUnitTest --tests com.discountscreener.android.ui.dashboard.DashboardScreenTest --tests com.discountscreener.android.ui.dashboard.DashboardDensityTest --tests com.discountscreener.android.ui.dashboard.ImportBookScreenTest --tests com.discountscreener.android.ui.dashboard.PositionsScreenTest --tests com.discountscreener.android.ui.dashboard.OpportunityListRankOrdinalTest --rerun
BUILD SUCCESSFUL
```

## Header case mapping

| Case | Test | Result |
| --- | --- | --- |
| `header_down` | `header_down_hides_the_complete_header_and_reclaims_list_space` | Pass |
| `header_up` | `header_up_restores_the_complete_header_without_resetting_the_list` | Pass |
| `header_small` | `header_small_reverses_direction_below_eight_dp_on_the_mounted_dashboard` | Pass |
| `header_horizontal` | `header_horizontal_keeps_the_mounted_header_unchanged` | Pass |
| `header_focus` | `header_focus_pins_an_empty_search_on_the_mounted_dashboard` | Pass |
| `header_active` | `header_active_pins_search_with_existing_query` | Pass |
| `header_tab` | `header_tab_reveals_after_selected_tab_changes` | Pass |
| `header_detail_return` | `header_detail_return_starts_with_a_full_header` | Pass |
| `header_short` | `header_short_keeps_the_header_visible` | Pass |
| `header_programmatic` | `header_programmatic_list_motion_does_not_hide_the_header` | Pass |
| `header_accessibility` | `header_accessibility_pinning_keeps_the_mounted_header_visible` | Pass |
| `header_other_tab` | `header_other_tab_hides_and_recovers_with_the_same_gesture_rules` | Pass |
| `header_shrink` | `header_shrink_recovers_after_content_becomes_short` | Pass |
| `header_short_search` | `header_short_expanded_search_stays_in_bounds_and_scrolls_to_tabs_and_search` | Pass |

The mounted focus follow-up `header_focus_back_releases_empty_search_and_allows_hide` passes.
Supplementary pure-state tests cover the eight-dp threshold, complete upward recovery, ignored programmatic motion, and recovery latches.
The pure `header_small_reverses_direction_below_eight_dp_without_visibility_change` test remains as threshold coverage.
The mounted `header_bottom_continued_downward_content_motion_keeps_header_hidden` test covers the bottom boundary.
The mounted `mounted_programmatic_lazy_list_scroll_does_not_hide_the_header` test uses `LazyListState.scrollToItem` with the real header.
The horizontal case now swipes the visible selected `Opps 40` tab.

## Unresolved gap

No device scroll, keyboard, or Detail navigation QA ran. The contract marks those paths `Not run` until Juan authorizes live QA.
