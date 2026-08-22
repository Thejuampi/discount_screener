package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.discountscreener.android.StuckTestWatchdog
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.DetailSubtab
import com.discountscreener.android.presentation.dashboard.EvRangeRailModel
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensQualifier
import com.discountscreener.android.presentation.dashboard.QuantLensSectionUi
import com.discountscreener.android.presentation.dashboard.QuantLensUiState
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.OutcomeConfidence
import com.discountscreener.core.model.QuantLensLensId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The range rail belongs to the tab the reader opened to look at valuation.
 *
 * On the Snapshot header it drew only for a scenario-weighted name. A name whose sources were in
 * tension got the same header with no rail, so two tickers opened side by side answered the same
 * question with two different screens. One place, one answer.
 */
@RunWith(RobolectricTestRunner::class)
class RangeRailBelongsToTheLensTabTest {
    /** Prints every thread's stack if this test stalls, so the next hang arrives with evidence. */
    @get:Rule
    val stuckTestWatchdog = StuckTestWatchdog()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun the_snapshot_tab_draws_no_range_rail() {
        setDetailContent(DetailSubtab.Snapshot)

        composeRule.onAllNodesWithTag(EV_RANGE_RAIL_TAG).assertCountEquals(0)
    }

    /**
     * The instrument check for the case above. The same lens state is fed to both tests, so an
     * empty Snapshot only means something once this one proves the fixture really carries a rail.
     */
    @Test
    fun the_lens_tab_draws_the_range_rail_from_the_same_lens_state() {
        setDetailContent(DetailSubtab.Lens)

        composeRule.onAllNodesWithTag(EV_RANGE_RAIL_TAG).assertCountEquals(1)
    }

    private fun setDetailContent(subtab: DetailSubtab) {
        composeRule.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = DetailRoute(
                        symbol = SYMBOL,
                        sourceTab = DetailSourceTab.Opportunities,
                        sourceSymbols = listOf(SYMBOL),
                        subtab = subtab,
                    ),
                    detail = null,
                    charts = emptyMap<ChartRange, List<HistoricalCandle>>(),
                    history = emptyList(),
                    alerts = emptyList(),
                    quantLens = LENS,
                    scoreRow = SCORE_ROW,
                    onAction = { _: DashboardAction -> },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val SYMBOL = "EME"

        /** The score header only draws for a ranked symbol, so the Snapshot case needs one. */
        val SCORE_ROW = OpportunityListRow(
            symbol = SYMBOL,
            marketPriceCents = 78_041L,
            intrinsicValueCents = 104_700L,
            outcomeConfidence = OutcomeConfidence.Wide,
            outcomeWidthBps = 8_100,
            gapBps = 3_400,
            confidence = ConfidenceBand.High,
            isWatched = false,
            freshness = RowFreshness.Loading,
            fundamentalsScore = 43,
            technicalScore = 7,
            forecastScore = 29,
            compositeScore = 29,
            coverageCount = 3,
        )

        val LENS = QuantLensUiState(
            headerChips = emptyList(),
            sections = listOf(
                QuantLensSectionUi(
                    lensId = QuantLensLensId.ExpectedValueRange,
                    title = "Valuation decision",
                    chip = QuantLensChipUi(
                        QuantLensLensId.ExpectedValueRange,
                        "Upside 24%",
                        QuantLensQualifier.Positive,
                    ),
                    primaryLine = "Weighted upside 24%",
                    evRailModel = EvRangeRailModel(
                        lowUpsideBps = 800,
                        weightedUpsideBps = 2_400,
                        highUpsideBps = 4_000,
                        crossesZero = false,
                        isStale = false,
                    ),
                ),
            ),
        )
    }
}
