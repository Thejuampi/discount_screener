package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.regime.MarketContextUnavailableReason
import com.discountscreener.core.regime.RegimeCause
import com.discountscreener.core.regime.RegimeCauseEffect
import com.discountscreener.core.regime.RegimeCauseFactor
import com.discountscreener.core.regime.RegimeScoreStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The fourth dimension, as a user meets it: a token in the list, a decomposition in detail, a
 * sentence when it is absent, and a switch that reaches the ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
class MarketDimensionUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    // ── The dense list row ───────────────────────────────────────────────────

    @Test
    fun an_included_dimension_gets_a_token_beside_the_other_three() {
        setOpportunitiesContent(row = listRow(status = RegimeScoreStatus.Included, regimeScore = 18))

        composeRule.onNodeWithText("Market 18").assertIsDisplayed()
    }

    /**
     * The three absent states share one rendering in the list — nothing — so each is asserted
     * separately. A single test over one of them would leave the other two free to leak a token.
     */
    @Test
    fun a_disabled_dimension_shows_no_token_in_the_list() {
        setOpportunitiesContent(row = listRow(status = RegimeScoreStatus.Disabled, regimeScore = 18))

        composeRule.onNodeWithText("Market 18").assertDoesNotExist()
    }

    @Test
    fun an_unavailable_dimension_shows_no_token_in_the_list() {
        setOpportunitiesContent(row = listRow(status = RegimeScoreStatus.Unavailable, regimeScore = 18))

        composeRule.onNodeWithText("Market 18").assertDoesNotExist()
    }

    @Test
    fun a_not_applicable_dimension_shows_no_token_in_the_list() {
        setOpportunitiesContent(row = listRow(status = RegimeScoreStatus.NotApplicable, regimeScore = 18))

        composeRule.onNodeWithText("Market 18").assertDoesNotExist()
    }

    // ── The detail section ───────────────────────────────────────────────────

    @Test
    fun detail_decomposes_the_score_into_base_context_and_final() {
        setDetailContent(row = includedRow(base = 31, final = 39))

        composeRule.onNodeWithText("Base 31 · Market +8 · Final 39").assertIsDisplayed()
    }

    /** A market that marked the name down must read as a subtraction, not an unsigned delta. */
    @Test
    fun a_market_that_hurt_the_score_says_so_with_a_sign() {
        setDetailContent(row = includedRow(base = 39, final = 31))

        composeRule.onNodeWithText("Base 39 · Market -8 · Final 31").assertIsDisplayed()
    }

    @Test
    fun detail_names_what_the_market_rewarded_and_what_it_marked_down() {
        setDetailContent(row = includedRow(base = 31, final = 39))

        composeRule.onNodeWithText("+ quality").assertIsDisplayed()
        composeRule.onNodeWithText("− extension").assertIsDisplayed()
    }

    /**
     * Eleven factors, eleven phrases. A duplicated branch would render two different reasons as the
     * same words, which reads as one reason counted twice.
     */
    @Test
    fun every_cause_the_fit_can_produce_has_its_own_words() {
        var labels = RegimeCauseFactor.entries.map { regimeCauseLabel(cause(it, RegimeCauseEffect.Support)) }

        assertEquals(RegimeCauseFactor.entries.size, labels.toSet().size)
    }

    /** The fourth-largest cause is, by construction, the one that changed the answer least. */
    @Test
    fun only_the_three_largest_causes_are_shown() {
        setDetailContent(row = includedRow(base = 31, final = 39))

        composeRule.onNodeWithText("− growth").assertDoesNotExist()
    }

    /** Largest by magnitude, not by position in the list the engine happened to build. */
    @Test
    fun the_smaller_causes_give_way_to_the_larger_ones() {
        setDetailContent(row = includedRow(base = 31, final = 39))

        composeRule.onNodeWithText("+ low beta").assertIsDisplayed()
    }

    /**
     * The detail header carries its own copy of the bucket row, so the list's token passing says
     * nothing about this one.
     */
    @Test
    fun detail_shows_the_fourth_bucket_beside_the_other_three() {
        setDetailContent(row = includedRow(base = 31, final = 39))

        composeRule.onNodeWithText("Market 18").assertIsDisplayed()
    }

    @Test
    fun detail_drops_the_fourth_token_when_the_dimension_is_absent() {
        setDetailContent(row = absentRow(RegimeScoreStatus.Disabled))

        composeRule.onNodeWithText("Market 18").assertDoesNotExist()
    }

    // ── Four states, four sentences ──────────────────────────────────────────

    @Test
    fun a_disabled_dimension_says_it_was_switched_off() {
        setDetailContent(row = absentRow(RegimeScoreStatus.Disabled))

        composeRule.onNodeWithText("Market context is switched off.").assertIsDisplayed()
    }

    @Test
    fun a_missing_market_reading_says_it_is_waiting_on_one() {
        setDetailContent(
            row = absentRow(
                RegimeScoreStatus.Unavailable,
                MarketContextUnavailableReason.MarketReadingUnavailable,
            ),
        )

        composeRule.onNodeWithText("No market reading yet — waiting on one.").assertIsDisplayed()
    }

    @Test
    fun a_name_the_fit_cannot_measure_says_so_rather_than_blaming_the_market() {
        setDetailContent(
            row = absentRow(
                RegimeScoreStatus.Unavailable,
                MarketContextUnavailableReason.InsufficientAssetData,
            ),
        )

        composeRule
            .onNodeWithText("Not enough data on this name to fit it to the market.")
            .assertIsDisplayed()
    }

    @Test
    fun a_model_without_the_dimension_names_the_model() {
        setDetailContent(
            row = absentRow(RegimeScoreStatus.NotApplicable),
            scoringModel = OpportunityScoringModel.AggressiveV2,
        )

        composeRule.onNodeWithText("Market context applies to Aggressive V3 and V4 only.").assertIsDisplayed()
    }

    /** Same status, different cause: an ETF on V3 must not be told to change model. */
    @Test
    fun an_asset_the_dimension_does_not_cover_names_the_asset() {
        setDetailContent(row = absentRow(RegimeScoreStatus.NotApplicable))

        composeRule.onNodeWithText("Market context is fitted to stocks; this is not one.").assertIsDisplayed()
    }

    @Test
    fun no_two_states_share_a_sentence() {
        val lines = listOfNotNull(
            marketDimensionStatusLine(RegimeScoreStatus.Disabled, null, MODEL),
            marketDimensionStatusLine(RegimeScoreStatus.NotApplicable, null, MODEL),
            marketDimensionStatusLine(RegimeScoreStatus.NotApplicable, null, OpportunityScoringModel.Legacy),
            marketDimensionStatusLine(
                RegimeScoreStatus.Unavailable,
                MarketContextUnavailableReason.MarketReadingUnavailable,
                MODEL,
            ),
            marketDimensionStatusLine(
                RegimeScoreStatus.Unavailable,
                MarketContextUnavailableReason.InsufficientAssetData,
                MODEL,
            ),
        )

        assertEquals(lines.size, lines.toSet().size)
    }

    @Test
    fun an_included_dimension_explains_nothing_because_there_is_nothing_to_explain() {
        assertEquals(null, marketDimensionStatusLine(RegimeScoreStatus.Included, null, MODEL))
    }

    // ── V4's agreement line ──────────────────────────────────────────────────

    @Test
    fun v4_detail_decomposes_the_score_into_centre_agreement_and_final() {
        setDetailContent(row = agreementRow(20, 20, 20, 20, final = 45), scoringModel = V4)

        composeRule.onNodeWithText("Centre 20 · Agreement 100% · Final 45").assertIsDisplayed()
    }

    /**
     * The percentage is a reading of `V4_SPREAD_FULL`, not a free-floating adjective. Two buckets
     * ten points apart sit a quarter of the way to the spread that pays nothing.
     */
    @Test
    fun the_agreement_percentage_is_measured_against_the_fitted_spread() {
        setDetailContent(row = agreementRow(30, 50, null, null, final = 45), scoringModel = V4)

        composeRule.onNodeWithText("Centre 40 · Agreement 74% · Final 45").assertIsDisplayed()
    }

    /**
     * Zero agreement is stated in words rather than as `0%`.
     *
     * The number the user acts on is the final score, and "the model is unsure" is the thing a
     * reader must not have to derive from a percentage sitting at its floor.
     */
    @Test
    fun divided_buckets_are_named_as_a_disagreement() {
        setDetailContent(row = agreementRow(-100, 100, null, null, final = 45), scoringModel = V4)

        composeRule.onNodeWithText("Centre 0 · Buckets disagree by 100 · Final 45").assertIsDisplayed()
    }

    /**
     * And the disagreement carries its size, because the percentage cannot.
     *
     * Agreement is clamped at the bottom, so this row and the one above both read 0% — one has its
     * buckets 40 points apart and the other 100. They are not the same row, and the row the model
     * is least sure about is where a reader most needs the magnitude. This test is what makes the
     * unclamped spread a rendered fact rather than a field nothing reads.
     */
    @Test
    fun the_size_of_a_disagreement_separates_two_rows_the_percentage_cannot() {
        setDetailContent(row = agreementRow(-40, 40, null, null, final = 45), scoringModel = V4)

        composeRule.onNodeWithText("Centre 0 · Buckets disagree by 40 · Final 45").assertIsDisplayed()
    }

    /**
     * V4's line goes where V3's line was, not above it.
     *
     * Both lines are individually correct, and printing both was still a defect: the reader met two
     * decompositions of one Final that share no term, and the word "Market" naming three different
     * quantities on one screen — the bucket's score, this delta, and the on/off toggle. Live QA
     * failed the build on it, so the exclusion is pinned here rather than left to the layout.
     */
    @Test
    fun v4_replaces_the_market_impact_line_rather_than_joining_it() {
        setDetailContent(row = twoLineRow(), scoringModel = V4)

        composeRule.onNodeWithText("Base 30 · Market +15 · Final 45").assertDoesNotExist()
    }

    /** And the exclusion is V4's alone — V3 keeps the line it has always shown. */
    @Test
    fun v3_still_shows_the_market_impact_line() {
        setDetailContent(row = twoLineRow(), scoringModel = MODEL)

        composeRule.onNodeWithText("Base 30 · Market +15 · Final 45").assertIsDisplayed()
    }

    /** One bucket also pays no bonus, and is a different fact: there is nobody to disagree with. */
    @Test
    fun a_single_bucket_is_not_reported_as_a_disagreement() {
        setDetailContent(row = agreementRow(20, null, null, null, final = 45), scoringModel = V4)

        composeRule.onNodeWithText("Centre 20 · One bucket only · Final 45").assertIsDisplayed()
    }

    /** No other model measures agreement, so no other model may claim a reading of it. */
    @Test
    fun only_v4_reports_an_agreement_line() {
        var row = agreementRow(20, 20, 20, 20, final = 45)

        assertEquals(
            List(OpportunityScoringModel.entries.size) { OpportunityScoringModel.entries[it] == V4 },
            OpportunityScoringModel.entries.map { v4AgreementLine(row, it) != null },
        )
    }

    // ── The switch ───────────────────────────────────────────────────────────

    @Test
    fun the_switch_reports_the_state_it_is_in() {
        setOpportunitiesContent(row = listRow(), regimeScoringEnabled = true)

        composeRule.onNodeWithText("Market on").assertIsSelected()
    }

    @Test
    fun the_switch_reports_being_off() {
        setOpportunitiesContent(row = listRow(), regimeScoringEnabled = false)

        composeRule.onNodeWithText("Market off").assertIsNotSelected()
    }

    @Test
    fun tapping_the_switch_asks_for_the_opposite_of_what_is_set() {
        val actions = mutableListOf<DashboardAction>()
        setOpportunitiesContent(row = listRow(), regimeScoringEnabled = true, onAction = actions::add)

        composeRule.onNodeWithText("Market on").performClick()

        assertEquals(listOf(DashboardAction.SetRegimeScoringEnabled(false)), actions)
    }

    @Test
    fun the_switch_reaches_the_view_model_from_inside_detail_too() {
        val actions = mutableListOf<DashboardAction>()
        setDetailContent(row = includedRow(base = 31, final = 39), onAction = actions::add)

        // Scrolled to first: the score header lives inside the Snapshot tab's lazy list now, so on a
        // short screen the switch can sit below the fold — where a click would land on nothing.
        composeRule.onNodeWithText("Market on").performScrollTo().performClick()

        assertEquals(listOf(DashboardAction.SetRegimeScoringEnabled(false)), actions)
    }

    /** A control with no effect is worse than no control: it is only shown where it has one. */
    @Test
    fun the_switch_is_absent_on_a_model_that_has_no_fourth_dimension() {
        setOpportunitiesContent(row = listRow(), model = OpportunityScoringModel.AggressiveV2)

        composeRule.onNodeWithText("Market on").assertDoesNotExist()
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun setOpportunitiesContent(
        row: OpportunityListRow,
        model: OpportunityScoringModel = MODEL,
        regimeScoringEnabled: Boolean = true,
        onAction: (DashboardAction) -> Unit = { },
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        loading = false,
                        startupPhase = DashboardStartupPhase.Ready,
                        currentProfile = "merval",
                        opportunityScoringModel = model,
                        regimeScoringEnabled = regimeScoringEnabled,
                        opportunityRows = listOf(row),
                    ),
                    onAction = onAction,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun setDetailContent(
        row: OpportunityListRow,
        scoringModel: OpportunityScoringModel = MODEL,
        onAction: (DashboardAction) -> Unit = { },
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = DetailRoute(
                        symbol = SYMBOL,
                        sourceTab = DetailSourceTab.Opportunities,
                        sourceSymbols = listOf(SYMBOL),
                    ),
                    detail = null,
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    scoreRow = row,
                    scoringModel = scoringModel,
                    regimeScoringEnabled = true,
                    onAction = onAction,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun listRow(
        status: RegimeScoreStatus = RegimeScoreStatus.Included,
        regimeScore: Int? = 18,
    ) = baseRow().copy(
        regimeScore = regimeScore,
        regimeStatus = status,
    )

    private fun includedRow(base: Int, final: Int) = baseRow().copy(
        regimeScore = 18,
        compositeScore = final,
        compositeScoreBase = base,
        coverageCount = 4,
        regimeStatus = RegimeScoreStatus.Included,
        // Deliberately not in magnitude order: a section that took the first three as given would
        // show growth and drop low beta, so the ranking is measured rather than assumed.
        regimeCauses = listOf(
            cause(RegimeCauseFactor.Growth, RegimeCauseEffect.Risk, 100),
            cause(RegimeCauseFactor.Quality, RegimeCauseEffect.Support, 900),
            cause(RegimeCauseFactor.Extension, RegimeCauseEffect.Risk, 700),
            cause(RegimeCauseFactor.LowBeta, RegimeCauseEffect.Support, 400),
        ),
    )

    /**
     * A row whose four buckets are stated one by one, because the agreement line is a reading of
     * exactly which ones reported and how far apart they are.
     */
    private fun agreementRow(
        fundamentals: Int?,
        technical: Int?,
        forecast: Int?,
        regime: Int?,
        final: Int,
    ) = baseRow().copy(
        fundamentalsScore = fundamentals,
        technicalScore = technical,
        forecastScore = forecast,
        regimeScore = regime,
        compositeScore = final,
        compositeScoreBase = final,
    )

    /** A row whose market bucket moved the score, so both candidate lines have something to say. */
    private fun twoLineRow() = agreementRow(20, 20, 20, 20, final = 45).copy(
        compositeScoreBase = 30,
        regimeStatus = RegimeScoreStatus.Included,
    )

    private fun absentRow(
        status: RegimeScoreStatus,
        reason: MarketContextUnavailableReason? = null,
    ) = baseRow().copy(
        regimeStatus = status,
        regimeUnavailableReason = reason,
    )

    private fun baseRow() = OpportunityListRow(
        symbol = SYMBOL,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 5_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        fundamentalsScore = 20,
        technicalScore = 20,
        forecastScore = 20,
        compositeScore = 34,
        compositeScoreBase = 34,
        coverageCount = 3,
    )

    private fun cause(
        factor: RegimeCauseFactor,
        effect: RegimeCauseEffect,
        contributionBps: Int = 100,
    ) = RegimeCause(factor = factor, effect = effect, contributionBps = contributionBps)

    private companion object {
        const val SYMBOL = "TGNO4.BA"
        val MODEL = OpportunityScoringModel.AggressiveV3
        val V4 = OpportunityScoringModel.AggressiveV4
    }
}
