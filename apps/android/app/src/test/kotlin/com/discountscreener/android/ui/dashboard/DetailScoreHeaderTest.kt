package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.discountscreener.android.StuckTestWatchdog
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.DetailSubtab
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.engine.OUTCOME_CONFIDENCE_UNMEASURED_NOTE
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.OutcomeConfidence
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.ScoreFactor
import com.discountscreener.core.model.SymbolDetail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

    /**
     * Snapshot drew the header twice: once above the forecast section and once below it. A reader
     * met two model selectors on one screen and no way to tell which one the score followed.
     *
     * The tall viewport is what makes this bind. A `LazyColumn` composes what fits, so on a phone
     * screen the second header was never in the semantics tree and a count of one was true of a
     * screen that carries two.
     */
    @Test
    @Config(qualifiers = "w411dp-h4000dp")
    fun the_snapshot_tab_draws_the_score_header_once() {
        setDetailContent(scoreRow = scoreRow(composite = 42))

        composeRule.onAllNodesWithText("Legacy").assertCountEquals(1)
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
        setDetailContent(
            scoreRow = scoreRow(composite = 42, fundamentalsSignals = listOf("Mult§++")),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("Mult§++").performScrollTo().assertIsDisplayed()
    }

    /**
     * Its companion, and the one that makes the assertion above mean something. A row is displayed
     * whether or not any composable reads [OpportunityListRow.fundamentalsSignals] — a hardcoded
     * legend would satisfy the positive case alone. This one fails unless the tokens come from the
     * row.
     */
    @Test
    fun a_row_that_carries_no_fundamentals_signal_renders_no_signal_token() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, fundamentalsSignals = emptyList()),
            subtab = DetailSubtab.Score,
        )

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
    fun a_factor_shows_its_readable_name() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                fundamentalsFactors = listOf(ScoreFactor("Mult§", "Mult§++", 14)),
            ),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("Multiples vs sector").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_factor_shows_the_points_it_added_to_the_bucket() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                fundamentalsFactors = listOf(ScoreFactor("Mult§", "Mult§++", 14)),
            ),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("+14").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_weak_bucket_says_so_on_the_score_tab() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                fundamentals = -22,
                fundamentalsFactors = listOf(ScoreFactor("Mult", "Mult--", -22)),
            ),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("Fundamentals -22 · Weak").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun the_score_tab_shows_the_decision_once() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated)
                .copy(decisionState = RowDecisionState.Act),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
            detail = namedDetail(),
        )

        composeRule.onAllNodesWithText("Act").assertCountEquals(1)
    }

    @Test
    fun snapshot_keeps_the_decision_in_the_app_bar() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated)
                .copy(decisionState = RowDecisionState.Act),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            detail = namedDetail(),
        )

        composeRule.onNodeWithText("Act").assertIsDisplayed()
    }

    @Test
    fun the_score_tab_names_qualification_from_the_in_memory_row() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated).copy(
                qualification = QualificationStatus.Qualified,
                externalStatus = ExternalSignalStatus.Supportive,
                analystCoverageCount = 5,
            ),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
            detail = namedDetail().copy(
                symbol = "OLD.BA",
                qualification = QualificationStatus.Unprofitable,
                companyName = "Old Co",
            ),
        )

        composeRule.onNodeWithText("Qualified").assertIsDisplayed()
    }

    @Test
    fun the_score_tab_does_not_keep_the_previous_qualification() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated).copy(
                qualification = QualificationStatus.Qualified,
                externalStatus = ExternalSignalStatus.Supportive,
                analystCoverageCount = 5,
            ),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
            detail = namedDetail().copy(
                symbol = "OLD.BA",
                qualification = QualificationStatus.Unprofitable,
                companyName = "Old Co",
            ),
        )

        composeRule.onNodeWithText("Unprofitable").assertDoesNotExist()
    }

    @Test
    fun the_score_tab_names_external_coverage_from_the_in_memory_row() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated).copy(
                qualification = QualificationStatus.Qualified,
                externalStatus = ExternalSignalStatus.Supportive,
                analystCoverageCount = 5,
            ),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
            detail = namedDetail().copy(symbol = "OLD.BA", companyName = "Old Co"),
        )

        composeRule.onNodeWithText("Supportive · 5 ≥ 3").assertIsDisplayed()
    }

    @Test
    fun the_score_tab_offers_help_on_external() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated).copy(
                qualification = QualificationStatus.Qualified,
                externalStatus = ExternalSignalStatus.Supportive,
                analystCoverageCount = 5,
            ),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("(i)").assertIsDisplayed()
    }

    @Test
    fun the_external_help_opens_the_street_median_copy() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated).copy(
                qualification = QualificationStatus.Qualified,
                externalStatus = ExternalSignalStatus.Supportive,
                analystCoverageCount = 5,
            ),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("(i)").performClick()

        composeRule.onNodeWithText("Street median target versus price.", substring = true).assertIsDisplayed()
    }

    @Test
    fun the_app_bar_uses_the_row_company_name_while_detail_is_stale() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42).copy(companyName = "New Co"),
            detail = namedDetail().copy(symbol = "OLD.BA", companyName = "Old Co"),
        )

        composeRule.onNodeWithText("New Co").assertIsDisplayed()
    }

    @Test
    fun the_app_bar_does_not_keep_the_previous_company_name() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42).copy(companyName = "New Co"),
            detail = namedDetail().copy(symbol = "OLD.BA", companyName = "Old Co"),
        )

        composeRule.onNodeWithText("Old Co").assertDoesNotExist()
    }

    @Test
    fun snapshot_does_not_explain_confidence() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            detail = namedDetail(),
        )

        composeRule.onNodeWithText("Qualification").assertDoesNotExist()
    }

    @Test
    fun a_clear_trust_gate_is_omitted() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("Clear").assertDoesNotExist()
    }

    @Test
    fun the_score_tab_shows_the_act_cut_on_the_composite_gate() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("42 ≥ 30").assertIsDisplayed()
    }

    @Test
    fun the_score_tab_shows_the_trust_note_on_the_trust_gate() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                freshness = RowFreshness.Updated,
                trustNote = "No analyst target",
            ),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("No analyst target").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun snapshot_does_not_explain_the_decision() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, freshness = RowFreshness.Updated),
            scoringModel = OpportunityScoringModel.AggressiveV3,
        )

        composeRule.onNodeWithText("42 ≥ 30").assertDoesNotExist()
    }

    @Test
    fun the_score_tab_states_the_five_bands() {
        setDetailContent(scoreRow = scoreRow(composite = 42), subtab = DetailSubtab.Score)

        composeRule.onNodeWithText(SCORE_READING_LEGEND).performScrollTo().assertIsDisplayed()
    }

    /**
     * The last hop. Everything before this is a list of strings that no screen has to draw, and the
     * factor breakdown already drops a line whose points are zero from the points column.
     */
    @Test
    fun the_score_tab_states_what_the_quarter_figure_measures() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                fundamentalsFactors = listOf(
                    ScoreFactor("Pulse", "Pulse--", -5),
                    ScoreFactor("Pulse≠Trend", "Pulse≠Trend", 0),
                ),
            ),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText(PULSE_BASIS_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun the_score_tab_says_when_the_quarter_and_the_revenue_line_disagree() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                fundamentalsFactors = listOf(
                    ScoreFactor("Pulse", "Pulse--", -5),
                    ScoreFactor("Pulse≠Trend", "Pulse≠Trend", 0),
                ),
            ),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText(PULSE_TREND_DIVERGENCE_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_row_that_scored_no_quarter_carries_no_basis_note() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                fundamentalsFactors = listOf(ScoreFactor("FCFy", "FCFy+", 8)),
            ),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText(PULSE_BASIS_NOTE).assertDoesNotExist()
    }

    @Test
    fun snapshot_does_not_state_the_five_bands() {
        setDetailContent(scoreRow = scoreRow(composite = 42))

        composeRule.onNodeWithText(SCORE_READING_LEGEND).assertDoesNotExist()
    }

    @Test
    fun the_score_decomp_does_not_sit_on_snapshot() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                fundamentalsFactors = listOf(ScoreFactor("Mult§", "Mult§++", 14)),
            ),
        )

        composeRule.onNodeWithText("Multiples vs sector").assertDoesNotExist()
    }

    @Test
    fun the_score_tab_is_selected_when_the_decomp_is_open() {
        setDetailContent(scoreRow = scoreRow(composite = 42), subtab = DetailSubtab.Score)

        composeRule.onNodeWithText("Score").assertIsSelected()
    }

    @Test
    fun the_score_tab_marks_the_active_scoring_model() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42),
            scoringModel = OpportunityScoringModel.AggressiveV3,
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("V3").assertIsSelected()
    }

    @Test
    fun changing_the_model_inside_the_score_tab_drives_the_one_global_selection() {
        val actions = mutableListOf<DashboardAction>()
        setDetailContent(
            scoreRow = scoreRow(composite = 42),
            scoringModel = OpportunityScoringModel.AggressiveV2,
            subtab = DetailSubtab.Score,
            onAction = actions::add,
        )

        composeRule.onNodeWithText("V3").performClick()

        assertEquals(
            listOf(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.AggressiveV3)),
            actions,
        )
    }

    @Test
    fun a_technical_reading_reaches_the_screen() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, technicalSignals = listOf("RSI--")),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("RSI--").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_forecast_reading_reaches_the_screen() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, forecastSignals = listOf("Target++")),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("Target++").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_market_reading_reaches_the_screen() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42, regimeSignals = listOf("Liquidity+")),
            subtab = DetailSubtab.Score,
        )

        composeRule.onNodeWithText("Liquidity+").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detail_header_marks_the_active_scoring_model() {
        setDetailContent(
            scoreRow = scoreRow(composite = 42),
            scoringModel = OpportunityScoringModel.AggressiveV3,
        )

        composeRule.onNodeWithText("V3").performScrollTo().assertIsSelected()
    }

    @Test
    fun changing_the_model_inside_detail_drives_the_one_global_selection() {
        val actions = mutableListOf<DashboardAction>()
        setDetailContent(
            scoreRow = scoreRow(composite = 42),
            scoringModel = OpportunityScoringModel.AggressiveV2,
            onAction = actions::add,
        )

        composeRule.onNodeWithText("V3").performScrollTo().performClick()

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
            .onNodeWithText("Not in the ranked set under V3")
            .assertIsDisplayed()
    }

    @Test
    fun the_model_selector_stays_reachable_for_a_symbol_outside_the_ranked_set() {
        setDetailContent(scoreRow = null, scoringModel = OpportunityScoringModel.AggressiveV3)

        composeRule.onNodeWithText("V2").performScrollTo().assertIsDisplayed()
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

    private fun namedDetail() = SymbolDetail(
        symbol = SYMBOL,
        profitable = true,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 5_000,
        minimumGapBps = 1_500,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        weightedAnalystCount = 5,
        analystOpinionCount = 5,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        companyName = "Test Co",
    )

    /**
     * A report inside the window is the one fact that can beat the score in the next two weeks, so
     * it has to reach the header and not only the model.
     */
    @Test
    fun a_report_inside_the_window_warns_on_the_score_header() {
        setDetailContent(scoreRow = scoreRow(composite = 42, nextEarningsEpoch = daysFromNow(3)))

        composeRule.onNodeWithText(EARNINGS_SOON_NOTE).performScrollTo().assertIsDisplayed()
    }

    /** The warning has to be able to stay silent, or it says nothing when it does appear. */
    @Test
    fun a_report_beyond_the_window_carries_no_warning() {
        setDetailContent(scoreRow = scoreRow(composite = 42, nextEarningsEpoch = daysFromNow(60)))

        composeRule.onNodeWithText(EARNINGS_SOON_NOTE).assertDoesNotExist()
    }

    @Test
    fun a_symbol_with_no_earnings_date_shows_nothing_about_earnings() {
        setDetailContent(scoreRow = scoreRow(composite = 42))

        composeRule.onNodeWithText("Earnings", substring = true).assertDoesNotExist()
    }

    /**
     * The reading a name gets when every source prices it differently. It has to reach the header,
     * because the confidence band next to it can read High on exactly that name.
     */
    @Test
    fun a_wide_outcome_range_reaches_the_score_header() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                outcomeConfidence = OutcomeConfidence.Wide,
                outcomeWidthBps = 9_000,
            ),
        )

        composeRule.onNodeWithText("Outcome range · Wide · sources span 90% of the centre")
            .performScrollTo().assertIsDisplayed()
    }

    /** Its companion. A header that hardcoded one line would keep the case above green. */
    @Test
    fun a_narrow_outcome_range_reads_narrow_on_the_score_header() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                outcomeConfidence = OutcomeConfidence.Narrow,
                outcomeWidthBps = 1_800,
            ),
        )

        composeRule.onNodeWithText("Outcome range · Narrow · sources span 18% of the centre")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_narrow_outcome_range_says_what_it_did_not_read() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                outcomeConfidence = OutcomeConfidence.Narrow,
                outcomeWidthBps = 1_800,
            ),
        )

        composeRule.onNodeWithText(OUTCOME_CONFIDENCE_UNMEASURED_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_wide_outcome_range_does_not_repeat_the_caveat() {
        setDetailContent(
            scoreRow = scoreRow(
                composite = 42,
                outcomeConfidence = OutcomeConfidence.Wide,
                outcomeWidthBps = 9_000,
            ),
        )

        composeRule.onNodeWithText(OUTCOME_CONFIDENCE_UNMEASURED_NOTE).assertDoesNotExist()
    }

    // ── The reader's own note ────────────────────────────────────────────────

    @Test
    fun a_saved_note_is_shown_on_the_symbol_it_belongs_to() {
        setDetailContent(scoreRow = scoreRow(composite = 42), symbolNote = "Target partnership ends.")

        composeRule.onNodeWithText("Target partnership ends.").performScrollTo().assertIsDisplayed()
    }

    /**
     * A sentence under four scores reads as one of their inputs unless it says otherwise, and this
     * one has no weight anywhere in the engine.
     */
    @Test
    fun the_note_says_it_does_not_move_the_score() {
        setDetailContent(scoreRow = scoreRow(composite = 42))

        composeRule.onNodeWithText(SYMBOL_NOTE_HINT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun editing_the_note_and_saving_it_carries_the_typed_text() {
        var actions = mutableListOf<DashboardAction>()
        setDetailContent(
            scoreRow = scoreRow(composite = 42),
            symbolNote = "old",
            onAction = { action -> actions += action },
        )

        composeRule.onNodeWithText("old").performScrollTo().performTextClearance()
        composeRule.onNodeWithText(SYMBOL_NOTE_LABEL).performScrollTo().performTextInput("plant fire")
        composeRule.onNodeWithText(SYMBOL_NOTE_SAVE_LABEL).performScrollTo().performClick()

        assertEquals(listOf(DashboardAction.SaveSymbolNote(SYMBOL, "plant fire")), actions)
    }

    /** No change, nothing to save. The button is the only sign that a draft is unsaved. */
    @Test
    fun an_untouched_note_offers_nothing_to_save() {
        setDetailContent(scoreRow = scoreRow(composite = 42), symbolNote = "old")

        composeRule.onNodeWithText(SYMBOL_NOTE_SAVE_LABEL).assertDoesNotExist()
    }

    /**
     * A symbol outside the ranked set is the one a reader most likely has something to say about,
     * so the field cannot live inside the block that only renders with a score.
     */
    @Test
    fun a_symbol_with_no_score_still_takes_a_note() {
        setDetailContent(scoreRow = null, symbolNote = "watching the spin-off")

        composeRule.onNodeWithText("watching the spin-off").performScrollTo().assertIsDisplayed()
    }

    private fun setDetailContent(
        scoreRow: OpportunityListRow?,
        scoringModel: OpportunityScoringModel = OpportunityScoringModel.AggressiveV2,
        sourceSymbols: List<String> = listOf(SYMBOL),
        subtab: DetailSubtab = DetailSubtab.Snapshot,
        onAction: (DashboardAction) -> Unit = { },
        detail: SymbolDetail? = null,
        symbolNote: String = "",
    ) {
        composeRule.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = routeOf(sourceSymbols, subtab),
                    detail = detail,
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    scoreRow = scoreRow,
                    scoringModel = scoringModel,
                    symbolNote = symbolNote,
                    onAction = onAction,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun scoreRow(
        composite: Int,
        nextEarningsEpoch: Long? = null,
        outcomeConfidence: OutcomeConfidence = OutcomeConfidence.Unmeasured,
        outcomeWidthBps: Int? = null,
        fundamentals: Int? = 20,
        technical: Int? = 20,
        forecast: Int? = 20,
        fundamentalsSignals: List<String> = emptyList(),
        technicalSignals: List<String> = emptyList(),
        forecastSignals: List<String> = emptyList(),
        fundamentalsFactors: List<ScoreFactor> = emptyList(),
        regimeSignals: List<String> = emptyList(),
        freshness: RowFreshness = RowFreshness.Loading,
        trustNote: String? = null,
    ) = OpportunityListRow(
        symbol = SYMBOL,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        nextEarningsEpoch = nextEarningsEpoch,
        outcomeConfidence = outcomeConfidence,
        outcomeWidthBps = outcomeWidthBps,
        gapBps = 5_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        freshness = freshness,
        trustNote = trustNote,
        fundamentalsScore = fundamentals,
        technicalScore = technical,
        forecastScore = forecast,
        compositeScore = composite,
        coverageCount = listOfNotNull(fundamentals, technical, forecast).size,
        fundamentalsSignals = fundamentalsSignals,
        technicalSignals = technicalSignals,
        forecastSignals = forecastSignals,
        fundamentalsFactors = fundamentalsFactors,
        regimeSignals = regimeSignals,
    )

    private companion object {
        const val SYMBOL = "TGNO4.BA"

        /**
         * Offsets from the machine clock, because the header reads that clock and takes no
         * override. Only the warning is asserted, never the day count: the count is what moves
         * with the clock, and [EarningsMarkTest] pins it against two fixed instants instead.
         */
        fun daysFromNow(days: Long): Long = System.currentTimeMillis() / 1_000L + days * 86_400L
    }
}
