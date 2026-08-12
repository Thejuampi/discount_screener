package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.discountscreener.android.StuckTestWatchdog
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.DetailSubtab
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Opening a ticker must answer "what is this scored, and under which model" without a trip back to
 * the Opportunities tab.
 */
@RunWith(RobolectricTestRunner::class)
class DetailScoreHeaderTest {
    /** Prints every thread's stack if this test stalls, so the next hang arrives with evidence. */
    @get:Rule
    val stuckTestWatchdog = StuckTestWatchdog()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun detail_header_shows_the_composite_score_for_the_open_symbol() {
        setDetailContent(scoreRow = scoreRow(composite = 42))

        composeRule.onNodeWithText("Score 42").assertIsDisplayed()
    }

    /**
     * The block lives inside Snapshot now, not in the chrome above the subtabs.
     *
     * It moved because on a phone the old placement plus the search bar pushed the tab row and the
     * first line of content off the fold, so a ticker opened on nothing but its own header. The
     * assertion that pins the move is the *absence*: the old layout rendered the score on every
     * subtab, so it would fail here while the positive case above kept passing.
     */
    @Test
    fun the_score_block_belongs_to_the_snapshot_tab_not_to_the_chrome_above_it() {
        setDetailContent(scoreRow = scoreRow(composite = 42), subtab = DetailSubtab.Lens)

        composeRule.onNodeWithText("Score 42").assertDoesNotExist()
    }

    @Test
    fun detail_header_shows_every_dimension_bucket() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, fundamentals = 61, technical = -12, forecast = 30),
        )

        composeRule.onNodeWithText("F 61").assertIsDisplayed()
        composeRule.onNodeWithText("T -12").assertIsDisplayed()
        composeRule.onNodeWithText("Fc 30").assertIsDisplayed()
    }

    @Test
    fun a_bucket_without_evidence_reads_as_absent_rather_than_zero() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, fundamentals = 61, technical = null, forecast = 30),
        )

        composeRule.onNodeWithText("T --").assertIsDisplayed()
    }

    /**
     * The `§` is the whole point of the sector work: it says the F token above was scored against
     * this symbol's own industry rather than against one band shared by utilities and chip makers.
     *
     * It is asserted here because it cannot be asserted on a device. A sector needs five members
     * before `computeSectorBenchmarks` will speak, and live QA is pinned to a universe of twenty
     * symbols spread across every sector, so the marker never appears there. A synthetic row is not
     * a weaker check than the emulator here — it is the only one available.
     */
    @Test
    fun a_sector_scored_multiple_says_on_screen_that_it_was_scored_against_its_sector() {
        setDetailContent(scoreRow = scoreRow(composite = 42, fundamentalsSignals = listOf("Mult§++")))

        composeRule.onNodeWithText("Mult§++").assertIsDisplayed()
    }

    /**
     * Its companion, and the one that makes the assertion above mean something. A row is displayed
     * whether or not any composable reads [OpportunityListRow.fundamentalsSignals] — a hardcoded
     * legend would satisfy the positive case alone. This one fails unless the tokens come from the
     * row.
     */
    @Test
    fun a_row_that_carries_no_fundamentals_signal_renders_no_signal_token() {
        setDetailContent(scoreRow = scoreRow(composite = 42, fundamentalsSignals = emptyList()))

        composeRule.onNodeWithText("Mult§++").assertDoesNotExist()
    }

    /**
     * The three lists that were still write-only after `§` was fixed.
     *
     * They are asserted one per test rather than in one pass over a row carrying all three, because
     * a single test would stay green while two of the three renders were deleted — the same reason
     * the `§` pair is split. Each of these fails alone if its own list loses its consumer.
     */
    @Test
    fun a_technical_reading_reaches_the_screen() {
        setDetailContent(scoreRow = scoreRow(composite = 42, technicalSignals = listOf("RSI--")))

        composeRule.onNodeWithText("RSI--").assertIsDisplayed()
    }

    @Test
    fun a_forecast_reading_reaches_the_screen() {
        setDetailContent(scoreRow = scoreRow(composite = 42, forecastSignals = listOf("Target++")))

        composeRule.onNodeWithText("Target++").assertIsDisplayed()
    }

    @Test
    fun a_market_reading_reaches_the_screen() {
        setDetailContent(scoreRow = scoreRow(composite = 42, regimeSignals = listOf("Liquidity+")))

        composeRule.onNodeWithText("Liquidity+").assertIsDisplayed()
    }

    @Test
    fun detail_header_marks_the_active_scoring_model() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42),
            scoringModel = OpportunityScoringModel.AggressiveV3,
        )

        composeRule.onNodeWithText("Aggressive V3").performScrollTo().assertIsSelected()
    }

    @Test
    fun changing_the_model_inside_detail_drives_the_one_global_selection() {
        val actions = mutableListOf<DashboardAction>()
        setDetailContent(
            scoreRow = scoreRow(composite = 42),
            scoringModel = OpportunityScoringModel.AggressiveV2,
            onAction = actions::add,
        )

        composeRule.onNodeWithText("Aggressive V3").performScrollTo().performClick()

        assertEquals(
            listOf(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.AggressiveV3)),
            actions,
        )
    }

    @Test
    fun the_rendered_score_follows_the_selected_model() {
        setDetailContent(
            scoreRow = scoreRow(composite = 4, fundamentals = 3),
            scoringModel = OpportunityScoringModel.Legacy,
        )

        composeRule.onNodeWithText("F 3/5").assertIsDisplayed()
    }

    @Test
    fun a_symbol_outside_the_ranked_set_says_so_instead_of_showing_a_score() {
        setDetailContent(scoreRow = null, scoringModel = OpportunityScoringModel.AggressiveV3)

        composeRule
            .onNodeWithText("Not in the ranked set under Aggressive V3")
            .assertIsDisplayed()
    }

    @Test
    fun the_model_selector_stays_reachable_for_a_symbol_outside_the_ranked_set() {
        setDetailContent(scoreRow = null, scoringModel = OpportunityScoringModel.AggressiveV3)

        composeRule.onNodeWithText("Aggressive V2").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detail_header_shows_absolute_and_relative_position_in_the_source_list() {
        setDetailContent(scoreRow = scoreRow(composite = 42), sourceSymbols = rankedListOf(size = 80, place = 60))

        composeRule.onNodeWithText("#60 of 80 · top 75%").assertIsDisplayed()
    }

    @Test
    fun the_relative_position_counts_from_the_top_so_a_better_placing_reads_lower() {
        setDetailContent(scoreRow = scoreRow(composite = 42), sourceSymbols = rankedListOf(size = 80, place = 24))

        composeRule.onNodeWithText("#24 of 80 · top 30%").assertIsDisplayed()
    }

    @Test
    fun a_single_symbol_list_gets_an_ordinal_but_no_meaningless_percentile() {
        setDetailContent(scoreRow = scoreRow(composite = 42), sourceSymbols = listOf(SYMBOL))

        composeRule.onNodeWithText("#1 of 1").assertIsDisplayed()
    }

    @Test
    fun position_is_computed_off_the_source_list_rather_than_assumed() {
        assertEquals(
            listOf("#1 of 4 · top 25%", "#2 of 4 · top 50%", "#3 of 4 · top 75%", "#4 of 4 · top 100%"),
            (1..4).map { place ->
                rankPositionLabel(routeOf(rankedListOf(size = 4, place = place)))
            },
        )
    }

    /**
     * The case every other percentile test misses: a list whose size does not divide 100.
     *
     * Rank 1 of 3 is 33.33%. Rounding to nearest prints `top 33%`, and the top 33% of three names
     * holds 0.99 of a name — nobody. The label would claim a standing the list cannot support. Only
     * `ceil` names the smallest band that really contains this symbol.
     *
     * Every case in the tests above — 24 of 80, 60 of 80, the four quarters — divides exactly, so
     * `ceil` and `roundToInt` agree on all of them and none of them can tell the two apart.
     */
    @Test
    fun a_percentile_that_does_not_divide_evenly_rounds_to_a_band_that_holds_the_symbol() {
        assertEquals("#1 of 3 · top 34%", rankPositionLabel(routeOf(rankedListOf(size = 3, place = 1))))
    }

    @Test
    fun a_symbol_outside_any_ranked_list_has_no_position_at_all() {
        assertEquals(null, rankPositionLabel(routeOf(sourceSymbols = listOf("OTHER.BA"))))
    }

    private fun routeOf(
        sourceSymbols: List<String>,
        subtab: DetailSubtab = DetailSubtab.Snapshot,
    ) = DetailRoute(
        symbol = SYMBOL,
        sourceTab = DetailSourceTab.Opportunities,
        sourceSymbols = sourceSymbols,
        subtab = subtab,
    )

    /** A ranked list of [size] symbols with the symbol under test sitting at 1-based [place]. */
    private fun rankedListOf(size: Int, place: Int) = (1..size).map { position ->
        if (position == place) SYMBOL else "SYM$position.BA"
    }

    private fun setDetailContent(
        scoreRow: OpportunityListRow?,
        scoringModel: OpportunityScoringModel = OpportunityScoringModel.AggressiveV2,
        sourceSymbols: List<String> = listOf(SYMBOL),
        subtab: DetailSubtab = DetailSubtab.Snapshot,
        onAction: (DashboardAction) -> Unit = { },
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = routeOf(sourceSymbols, subtab),
                    detail = null,
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    scoreRow = scoreRow,
                    scoringModel = scoringModel,
                    onAction = onAction,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun scoreRow(
        composite: Int,
        fundamentals: Int? = 20,
        technical: Int? = 20,
        forecast: Int? = 20,
        fundamentalsSignals: List<String> = emptyList(),
        technicalSignals: List<String> = emptyList(),
        forecastSignals: List<String> = emptyList(),
        regimeSignals: List<String> = emptyList(),
    ) = OpportunityListRow(
        symbol = SYMBOL,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 5_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        fundamentalsScore = fundamentals,
        technicalScore = technical,
        forecastScore = forecast,
        compositeScore = composite,
        coverageCount = listOfNotNull(fundamentals, technical, forecast).size,
        fundamentalsSignals = fundamentalsSignals,
        technicalSignals = technicalSignals,
        forecastSignals = forecastSignals,
        regimeSignals = regimeSignals,
    )

    private companion object {
        const val SYMBOL = "TGNO4.BA"
    }
}
