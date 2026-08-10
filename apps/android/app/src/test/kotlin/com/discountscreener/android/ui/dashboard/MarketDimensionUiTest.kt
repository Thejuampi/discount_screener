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

        composeRule.onNodeWithText("Market context applies to Aggressive V3 only.").assertIsDisplayed()
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

    // ── The switch ───────────────────────────────────────────────────────────

    @Test
    fun the_switch_reports_the_state_it_is_in() {
        setOpportunitiesContent(row = listRow(), regimeScoringEnabled = true)

        composeRule.onNodeWithText("Market context on").assertIsSelected()
    }

    @Test
    fun the_switch_reports_being_off() {
        setOpportunitiesContent(row = listRow(), regimeScoringEnabled = false)

        composeRule.onNodeWithText("Market context off").assertIsNotSelected()
    }

    @Test
    fun tapping_the_switch_asks_for_the_opposite_of_what_is_set() {
        val actions = mutableListOf<DashboardAction>()
        setOpportunitiesContent(row = listRow(), regimeScoringEnabled = true, onAction = actions::add)

        composeRule.onNodeWithText("Market context on").performClick()

        assertEquals(listOf(DashboardAction.SetRegimeScoringEnabled(false)), actions)
    }

    @Test
    fun the_switch_reaches_the_view_model_from_inside_detail_too() {
        val actions = mutableListOf<DashboardAction>()
        setDetailContent(row = includedRow(base = 31, final = 39), onAction = actions::add)

        composeRule.onNodeWithText("Market context on").performClick()

        assertEquals(listOf(DashboardAction.SetRegimeScoringEnabled(false)), actions)
    }

    /** A control with no effect is worse than no control: it is only shown where it has one. */
    @Test
    fun the_switch_is_absent_on_a_model_that_has_no_fourth_dimension() {
        setOpportunitiesContent(row = listRow(), model = OpportunityScoringModel.AggressiveV2)

        composeRule.onNodeWithText("Market context on").assertDoesNotExist()
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
    }
}
