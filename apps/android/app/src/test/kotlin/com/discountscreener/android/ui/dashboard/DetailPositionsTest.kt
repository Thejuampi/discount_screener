package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.PositionsRow
import com.discountscreener.android.presentation.dashboard.projectPositions
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.PortfolioQuote
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class DetailPositionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun detail_keeps_shared_book_weight_and_paired_pl_for_one_matching_lot() {
        val rows = projectPositions(
            lots = listOf(
                PortfolioLot("AMZN", 10_000L, 5_000L, null),
                PortfolioLot("OTHER", 30_000L, 5_000L, null),
            ),
            scored = listOf(scored("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf("OTHER" to PortfolioQuote(10_000L)),
        )
        render(rows.filter { it.symbol == "AMZN" })

        composeRule.onNodeWithText("Position lot 1").assertIsDisplayed()
        composeRule.onNodeWithText("Shares 1").assertIsDisplayed()
        composeRule.onNodeWithText("Price $100.00").assertIsDisplayed()
        composeRule.onNodeWithText("Unrealized P/L $50.00").assertIsDisplayed()
        composeRule.onNodeWithText("Weight 25.00%").assertIsDisplayed()
    }

    @Test
    fun detail_places_earnings_before_both_duplicate_lots_and_keeps_book_weights() {
        val rows = projectPositions(
            lots = listOf(
                PortfolioLot("AMZN", 10_000L, 5_000L, null),
                PortfolioLot("AMZN", 20_000L, 6_000L, null),
                PortfolioLot("OTHER", 30_000L, 5_000L, null),
            ),
            scored = listOf(scored("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf("OTHER" to PortfolioQuote(10_000L)),
        )
        render(rows.filter { it.symbol == "AMZN" })

        val earningsTop = composeRule.onNodeWithTag(DETAIL_EARNINGS_SECTION).getUnclippedBoundsInRoot().top
        val lotOneTop = composeRule.onNodeWithText("Position lot 1").getUnclippedBoundsInRoot().top
        val lotTwoTop = composeRule.onNodeWithText("Position lot 2").getUnclippedBoundsInRoot().top
        assertTrue(
            "earnings must precede both lots: $earningsTop, $lotOneTop, $lotTwoTop",
            earningsTop < lotOneTop && earningsTop < lotTwoTop,
        )
        composeRule.onNodeWithText("Shares 1").assertIsDisplayed()
        composeRule.onNodeWithText("Shares 2").assertIsDisplayed()
        composeRule.onNodeWithText("Weight 16.67%").assertIsDisplayed()
        composeRule.onNodeWithText("Weight 33.33%").assertIsDisplayed()
    }

    private fun render(rows: List<PositionsRow>) {
        composeRule.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = DetailRoute(
                        symbol = "AMZN",
                        sourceTab = DetailSourceTab.Opportunities,
                        sourceSymbols = listOf("AMZN"),
                    ),
                    detail = null,
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    positionRows = rows,
                    onAction = {},
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun scored(symbol: String): OpportunityListRow = OpportunityListRow(
        symbol = symbol,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        confidence = ConfidenceBand.High,
        isWatched = false,
        fundamentalsScore = 20,
        technicalScore = 18,
        forecastScore = 12,
        compositeScore = 40,
        coverageCount = 3,
        decisionState = RowDecisionState.Act,
        freshness = RowFreshness.Updated,
        valuationStanceLabel = "Identity",
    )
}
