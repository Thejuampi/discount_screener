# Wave C result

## Changes

- Added `PositionsContent` with a compact stock value and P/L summary.
- Added the sort menu with Largest position, Needs review, Earnings soon, and Import book.
- Added exact USD cents formatting with `BigDecimal`.
- Added local facts for shares, cost, price, P/L, P/L percentage, weight, and quote status.
- Added explicit reasons for missing quote, cost, value, percentage, and incomplete book weight.
- Added the quote age disclosure and the `Stored` score marker.
- Showed `Unconfirmed` quote status when the core quote is not current.
- Added aggregate overflow reasons while retaining complete input coverage.
- Kept the normal summary compact and wrapped row facts at large text sizes.
- Preserved Positions sort through Detail and tab round trips.
- Kept import as one full-width control and launched `ACTION_OPEN_DOCUMENT` from the menu.
- Added stable row identity from symbol and input index.
- Passed all matching position lots from the dashboard to Detail after the earnings card.
- Preserved off-feed local facts without a Detail action.

## Validation

The acceptance red run found one strict duplicate-lot order assertion. Production sorting allows equal-date lots to reorder by value. The test now checks that earnings precedes both lots.

Red command:

```text
./gradlew.bat :app:testDebugUnitTest --tests com.discountscreener.android.ui.dashboard.PositionsScreenTest --tests com.discountscreener.android.ui.dashboard.PositionsNormalLayoutTest --tests com.discountscreener.android.ui.dashboard.PositionsLayoutTest --tests com.discountscreener.android.ui.dashboard.PositionsSortTest --tests com.discountscreener.android.ui.dashboard.DetailPositionsTest --tests com.discountscreener.android.ui.DashboardRouteContentTest --rerun
```

Red result: 30 tests completed, 1 failed at the duplicate-lot order assertion.

Final focused command:

```text
./gradlew.bat :app:testDebugUnitTest --tests com.discountscreener.android.ui.dashboard.ImportBookScreenTest --tests com.discountscreener.android.ui.dashboard.PositionsScreenTest --tests com.discountscreener.android.ui.dashboard.PositionsNormalLayoutTest --tests com.discountscreener.android.ui.dashboard.PositionsLayoutTest --tests com.discountscreener.android.ui.dashboard.PositionsSortTest --tests com.discountscreener.android.ui.dashboard.DetailPositionsTest --tests com.discountscreener.android.ui.DashboardRouteContentTest --tests com.discountscreener.android.ui.DashboardStateRetentionTest --tests com.discountscreener.android.presentation.dashboard.PositionsPresentationTest --rerun
```

Result: 98 tests pass across the listed suites.

No device, provider, install, full suite, commit, or push ran.

## Case mapping

| Case | Test | Result |
| --- | --- | --- |
| `positions_summary` | `positions_summary_shows_stock_value_pl_and_two_decimal_weight` | Pass |
| `positions_partial` | `positions_partial_book_shows_coverage_and_unavailable_weights` | Pass |
| `positions_import` | `positions_menu_keeps_import_and_sort_choices` | Pass |
| `positions_empty` | `pos_empty_shows_no_lots` | Pass |
| `positions_local` | `positions_local_facts_expand_without_dispatching_off_feed_detail` | Pass |
| `positions_detail` | `DashboardStateRetentionTest.positions_detail_tap_shows_matching_facts_and_whole_book_weight` | Pass |
| Additional single-lot check | `DetailPositionsTest.detail_keeps_shared_book_weight_and_paired_pl_for_one_matching_lot` | Pass |
| `positions_sort` | `PositionsSortTest.positions_sort_menu_orders_each_sort_choice` | Pass |
| `positions_sort_detail_return` | `DashboardStateRetentionTest.positions_sort_survives_detail_round_trip` | Pass |
| `positions_sort_tab_return` | `DashboardStateRetentionTest.positions_sort_survives_tab_round_trip` | Pass |
| `positions_narrow` | `PositionsNormalLayoutTest.positions_320dp_normal_text_keeps_required_facts_inside_the_row` | Pass |
| `positions_large_text` | `PositionsLayoutTest.positions_narrow_large_text_keeps_unavailable_reason_and_earnings_visible` | Pass |
| `positions_priced_large` | `PositionsLayoutTest.positions_320dp_large_text_keeps_current_priced_row_facts_readable` | Pass |
| `positions_stored_large` | `PositionsLayoutTest.positions_320dp_large_text_keeps_stored_score_and_quote_disclosure_readable` | Pass |
| `positions_duplicate_detail` | `DetailPositionsTest.detail_places_earnings_before_both_duplicate_lots_and_keeps_book_weights` | Pass |
| `positions_stored_total` | `positions_summary_discloses_stored_quote_and_lot_status` | Pass |
| `positions_overflow_total` | `positions_summary_explains_aggregate_overflow_without_losing_coverage` | Pass |
| `positions_zero_cost_percent` | `positions_summary_keeps_zero_cost_percentage_reason_separate_from_overflow` | Pass |
| `positions_import_empty` | `the_positions_tab_offers_import_book` | Pass |
| `positions_import_picker` | `the_positions_menu_import_control_launches_document_picker` | Pass |

Additional route boundary tests pass:

- `DashboardRouteContentTest.route_without_detail_mounts_the_real_dashboard_positions_surface`
- `DashboardRouteContentTest.route_detail_filters_exact_symbol_and_keeps_duplicate_book_lots`

The empty Positions case shows the import control in the initial viewport. The populated ticker case scrolls the lazy list before its assertion.
The single-lot Detail test uses an unrelated book lot and checks the retained `25.00%` weight.
The duplicate-lot Detail test checks the retained `16.67%` and `33.33%` whole-book weights.
The layout tests use the real `GetTextLayoutResult` action and assert `TextLayoutResult.hasVisualOverflow` is false.

No offline screenshots were saved.

