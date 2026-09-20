package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.projectPositions
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.portfolio.PortfolioLot
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class PositionsSortTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun positions_sort_menu_orders_each_sort_choice() {
        val today = LocalDate.of(2026, 9, 7)
        val rows = projectPositions(
            lots = listOf(
                PortfolioLot("PHYL", 100_000L, 1_000L, null),
                PortfolioLot("AVOID", 300_000L, 1_000L, null),
                PortfolioLot("WATCH", 200_000L, 1_000L, null),
                PortfolioLot("ACT", 100_000L, 1_000L, null),
            ),
            scored = listOf(
                scored("AVOID", RowDecisionState.Avoid),
                scored("WATCH", RowDecisionState.Watch),
                scored("ACT", RowDecisionState.Act),
            ),
            upcomingReport = mapOf(
                "PHYL" to today.plusDays(1),
                "AVOID" to today.plusDays(4),
                "WATCH" to today.plusDays(10),
                "ACT" to today,
            ),
            today = today,
        )
        render(rows)

        assertOrder("AVOID", "WATCH", "ACT", "PHYL")
        selectSort("Needs review")
        assertOrder("PHYL", "AVOID", "WATCH", "ACT")
        selectSort("Earnings soon")
        assertOrder("ACT", "PHYL", "AVOID", "WATCH")
    }

    private fun selectSort(label: String) {
        composeRule.onNodeWithContentDescription("Positions menu").performClick()
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    private fun assertOrder(vararg symbols: String) {
        val tops = symbols.map { symbol -> composeRule.onNodeWithText(symbol).getUnclippedBoundsInRoot().top }
        assertTrue("unexpected order for ${symbols.toList()}: $tops", tops.zipWithNext().all { (a, b) -> a < b })
    }

    private fun render(rows: List<com.discountscreener.android.presentation.dashboard.PositionsRow>) {
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        loading = false,
                        currentTab = DashboardTab.Positions,
                        startupPhase = DashboardStartupPhase.Ready,
                        opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
                        positionsRows = rows,
                    ),
                    onAction = {},
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun scored(symbol: String, decision: RowDecisionState): OpportunityListRow = OpportunityListRow(
        symbol = symbol,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        confidence = com.discountscreener.core.model.ConfidenceBand.High,
        isWatched = false,
        fundamentalsScore = 20,
        technicalScore = 18,
        forecastScore = 12,
        compositeScore = 40,
        coverageCount = 3,
        decisionState = decision,
        freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
        valuationStanceLabel = "Identity",
    )
}
