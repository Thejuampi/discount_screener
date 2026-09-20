# Wave A result

## Scope

Wave A adds fixed point portfolio exposure, research eligibility, and prepared position rows.
The current `projectPositions` call remains source-compatible.
Existing `PositionsRow` constructors remain source-compatible through default fields.

## Public API

Core adds `PortfolioQuote`, `Coverage`, `PortfolioLotExposure`, `PortfolioBookSummary`, and `PortfolioExposure`.

Core adds `projectPortfolioExposure(lots, quotes)` with typed `Map<String, PortfolioQuote>` input.
It uses raw `BigInteger` products and half-up rounding at public cent boundaries.
It suppresses weights when value coverage is incomplete or totals overflow.

Core adds `summarizePortfolioExposure(rows)` for exact aggregate summaries.
Core adds `PositionDecision`, `PositionEvidence`, `PositionTrust`, `PositionPrimary`, and `PositionOpportunity`.
`PositionTrust.ModelValue` is neutral. `PositionLensState` marks typed provisional or disputed evidence.
Core adds `PositionResearchInput`, `PositionResearchResult`, and `positionResearch(input)`.
Core summary accepts an optional `totalLots` count for callers that retain unavailable rows separately.
Missing evidence with a usable quote reports `Missing analysis`. Provider errors and missing quotes keep priority.

The app extends `PositionsRow` with input index, quote, value, P/L, weight, research, score, and exposure fields.
The row keeps the formatted quantity label and does not duplicate raw quantity or value availability fields.
The app adds `PositionsBookSummary`, `PositionsSort`, `presentPositionsSummary(rows)`, and `sortPositionRows(rows, sort)`.
The presenter treats explicit stored quotes as non-current research evidence.
The presenter consumes matching `EvidenceStrength` and `ExpectedValueRange` lens statuses.

`projectPositions` accepts an optional typed quote map.
When the map is empty, it derives stored or current quote metadata from scored rows.
It preserves input order in each row's `inputIndex` and keeps exact Core exposure data.
The presenter maps rows from Core exposure records, so the Core lot and symbol remain the adapter source.
Blank or whitespace trust notes become absent. The exact `Model value` note stays neutral.
The presenter adapts positive tracked prices into quote inputs. It marks a quote current only when
`RowFreshness.Updated` confirms the price and keeps scored prices as the fallback.

## Changed files

- `apps/android/core/src/main/kotlin/com/discountscreener/core/portfolio/PortfolioExposure.kt`
- `apps/android/core/src/main/kotlin/com/discountscreener/core/portfolio/PositionResearch.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/PositionsPresentation.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/portfolio/PortfolioExposureTest.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/portfolio/PositionResearchTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/PositionsPresentationTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `_bmad-output/specs/spec-android-positions-ux/wave-a-result.md`

`AdvisorCsv.kt` and unrelated concurrent edits remain unchanged by this wave.

## Examples mapping

| Case | Test |
| --- | --- |
| `exposure_complete` | `PortfolioExposureTest.complete_book_uses_raw_values_for_weights` |
| `exposure_partial` | `PortfolioExposureTest.one_missing_quote_suppresses_all_weights_but_keeps_partial_total` |
| `exposure_none` | `PortfolioExposureTest.no_quotes_returns_unavailable_total` |
| `exposure_fraction` | `PortfolioExposureTest.fractional_positive_value_remains_eligible` |
| `exposure_zero_cost` | `PortfolioExposureTest.zero_cost_keeps_pl_dollars_and_refuses_percentage` |
| `exposure_bad_cost` | `PortfolioExposureTest.negative_cost_excludes_only_that_lot_from_paired_pl` |
| `exposure_invalid_quote` | `PortfolioExposureTest.zero_and_negative_quotes_are_unavailable` |
| `exposure_overflow` | `PortfolioExposureTest.overflow_suppresses_total_and_weights` |
| `exposure_overflow_mixed` | `PortfolioExposureTest.overflowed_lot_does_not_poison_valid_subtotal` |
| `exposure_overflow_aggregate` | `PortfolioExposureTest.individually_valid_values_can_overflow_the_aggregate` |
| `exposure_round` | `PortfolioExposureTest.fractional_raw_products_aggregate_before_rounding` |
| `exposure_duplicate` | `PortfolioExposureTest.duplicate_symbols_keep_input_order_and_index` |
| `exposure_paired` | `PortfolioExposureTest.paired_pl_reports_cents_and_basis_points` |
| `exposure_signed_round` | `PortfolioExposureTest.signed_half_cent_pl_rounds_away_from_zero` |
| `research_act` | `PositionsPresentationTest.current_act_research_shows_review_opportunity` |
| `research_watch` | `PositionResearchTest.watch_maps_to_monitor` |
| `research_avoid` | `PositionResearchTest.avoid_maps_to_review_position` |
| `research_stored` | `PositionsPresentationTest.stored_research_shows_check_data_and_marker` |
| `research_disputed` | `PositionsPresentationTest.disputed_rows_show_check_data_and_reason` |
| `research_provisional` | `PositionsPresentationTest.provisional_rows_show_check_data_and_reason` |
| `research_absent` | `PositionsPresentationTest.absent_research_shows_check_data_and_missing_quote_reason` |
| `research_legacy` | `PositionsPresentationTest.exact_stance_labels_do_not_infer_legacy_primary` |
| `research_model` | `PositionsPresentationTest.current_identity_research_shows_model_relation` |
| `research_analyst` | `PositionsPresentationTest.current_analyst_research_shows_analyst_relation` |
| `research_model_note` | `PositionsPresentationTest.model_value_note_is_neutral_when_confidence_is_high` |
| `research_lens_provisional` | `PositionsPresentationTest.matching_lens_provisional_status_requires_check_data` |
| `research_blank_note` | `PositionsPresentationTest.blank_trust_note_is_absent` |
| `research_whitespace_note` | `PositionsPresentationTest.whitespace_trust_note_is_absent` |
| `research_missing_analysis` | `PositionResearchTest.usable_quote_without_analysis_reports_missing_analysis` |
| `research_provider_error` | `PositionsPresentationTest.current_act_provider_error_shows_exact_reason` |
| `research_provider_blank` | `PositionsPresentationTest.blank_provider_error_does_not_block_current_act` |
| `research_provider_whitespace` | `PositionsPresentationTest.whitespace_provider_error_does_not_block_current_act` |
| `research_watch_presenter` | `PositionsPresentationTest.current_watch_research_shows_monitor` |
| `research_avoid_presenter` | `PositionsPresentationTest.current_avoid_research_shows_review_position` |
| `research_tracked_price` | `PositionsPresentationTest.tracked_price_without_research_projects_as_current_exposure` |
| `positions_summary` | `PositionsPresentationTest.complete_book_summary_uses_enriched_rows` |
| `positions_partial` | `PositionsPresentationTest.partial_book_summary_keeps_coverage_and_refuses_weights` |
| `positions_sort` | `PositionsPresentationTest.largest_sort_puts_unavailable_values_last` |

The audit adds `PositionsPresentationTest.model_value_note_is_neutral_when_confidence_is_high`.
It adds `PositionsPresentationTest.model_value_note_does_not_bypass_low_confidence`.
It adds matching and mismatched lens status tests.
It adds stored quote, missing exposure, overflow pairing, and boundary rounding tests.
It adds separate Core and presenter tests for blank and whitespace trust notes.
It adds tracked quote adapter coverage in the presenter and ViewModel.

## Review closure

| Review ID | Closed by |
| --- | --- |
| B1 | Tracked positive prices now feed both ViewModel projection calls. |
| B3 | Invalid quotes clear `quoteIsCurrent`. |
| B5 | Missing evidence reports `Missing analysis` with a usable quote. |
| B9 | Core totals sum only rows with public values and suppress overflow weights. |
| E1 | Provider, blank provider, whitespace provider, Watch, and Avoid cases have separate tests. |
| G1 | `PortfolioQuote.isCurrent` now describes confirmed currentness. |

The remaining mounted UI cases belong to Waves B and C.

## Test evidence

Red evidence came from the first focused Core compile before the new APIs existed.
The command failed on unresolved exposure and research symbols.

Green Core command:

```text
apps/android> .\\gradlew.bat :core:test --tests com.discountscreener.core.portfolio.PortfolioExposureTest --tests com.discountscreener.core.portfolio.PositionResearchTest --rerun
BUILD SUCCESSFUL
26 tests passed
```

Green app command:

```text
apps/android> .\\gradlew.bat :app:testDebugUnitTest --tests com.discountscreener.android.presentation.dashboard.PositionsPresentationTest --tests com.discountscreener.android.presentation.dashboard.DashboardViewModelTest --rerun
BUILD SUCCESSFUL
150 tests passed
```

Gradle daemons stopped after the focused checks.
No device, provider, install, or release command ran.

## Integration handoff

Waves B and C now connect these prepared rows to Compose and Detail.
The parent wave owns integration, documentation, review, and final debug artifact checks.

The final B9 correction checks missing analysis before quote freshness.
A new test covers a non-current quote without analysis. The final full suite checks that correction.

