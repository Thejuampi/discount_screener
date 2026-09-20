package com.discountscreener.android.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.projectPositions
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.PortfolioQuote
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class DashboardStateRetentionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun positions_sort_survives_detail_round_trip() {
        val rows = projectRows()
        composeRule.setContent {
            var currentTab by remember { mutableStateOf(DashboardTab.Positions) }
            var detailRoute by remember { mutableStateOf<DetailRoute?>(null) }
            val state = DashboardUiState(
                loading = false,
                currentTab = currentTab,
                detailRoute = detailRoute,
                startupPhase = DashboardStartupPhase.Ready,
                opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
                positionsRows = rows,
            )
            DiscountScreenerTheme {
                DashboardRouteContent(
                    state = state,
                    earningsToday = LocalDate.of(2026, 9, 7),
                    onAction = { action ->
                        when (action) {
                            is DashboardAction.OpenDetail -> detailRoute = DetailRoute(
                                symbol = action.symbol,
                                sourceTab = DetailSourceTab.Opportunities,
                                sourceSymbols = listOf(action.symbol),
                            )
                            DashboardAction.BackFromDetail -> detailRoute = null
                            is DashboardAction.SelectTab -> currentTab = action.tab
                            else -> Unit
                        }
                    },
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Positions menu").performClick()
        composeRule.onNodeWithText("Needs review").performClick()
        composeRule.onNodeWithText("AMZN").performClick()
        composeRule.onAllNodesWithText("Back").get(0).performClick()
        composeRule.onNodeWithText("Needs review").assertIsDisplayed()

    }

    @Test
    fun positions_sort_survives_tab_round_trip() {
        val rows = projectRows()
        composeRule.setContent {
            var currentTab by remember { mutableStateOf(DashboardTab.Positions) }
            var detailRoute by remember { mutableStateOf<DetailRoute?>(null) }
            val state = DashboardUiState(
                loading = false,
                currentTab = currentTab,
                detailRoute = detailRoute,
                startupPhase = DashboardStartupPhase.Ready,
                opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
                positionsRows = rows,
            )
            DiscountScreenerTheme {
                DashboardRouteContent(
                    state = state,
                    earningsToday = LocalDate.of(2026, 9, 7),
                    onAction = { action ->
                        when (action) {
                            is DashboardAction.SelectTab -> currentTab = action.tab
                            else -> Unit
                        }
                    },
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Positions menu").performClick()
        composeRule.onNodeWithText("Needs review").performClick()
        composeRule.onNodeWithText("Upside", substring = true).performClick()
        composeRule.onNodeWithText("Positions", substring = true).performClick()
        composeRule.onNodeWithText("Needs review").assertIsDisplayed()
    }

    @Test
    fun positions_detail_tap_shows_matching_facts_and_whole_book_weight() {
        val rows = projectDetailRows()
        composeRule.setContent {
            var currentTab by remember { mutableStateOf(DashboardTab.Positions) }
            var detailRoute by remember { mutableStateOf<DetailRoute?>(null) }
            val state = DashboardUiState(
                loading = false,
                currentTab = currentTab,
                detailRoute = detailRoute,
                startupPhase = DashboardStartupPhase.Ready,
                opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
                positionsRows = rows,
            )
            DiscountScreenerTheme {
                DashboardRouteContent(
                    state = state,
                    earningsToday = LocalDate.of(2026, 9, 7),
                    onAction = { action ->
                        when (action) {
                            is DashboardAction.OpenDetail -> detailRoute = DetailRoute(
                                symbol = action.symbol,
                                sourceTab = DetailSourceTab.Opportunities,
                                sourceSymbols = listOf(action.symbol),
                            )
                            DashboardAction.BackFromDetail -> detailRoute = null
                            is DashboardAction.SelectTab -> currentTab = action.tab
                            else -> Unit
                        }
                    },
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("AMZN").performClick()
        composeRule.onNodeWithText("Position lot 1").assertIsDisplayed()
        composeRule.onNodeWithText("Weight 25.00%").assertIsDisplayed()
        composeRule.onNodeWithText("Price $100.00").assertIsDisplayed()
    }

    private fun projectRows() = projectPositions(
        lots = listOf(
            PortfolioLot("PHYL", 10_000L, 1_000L, null),
            PortfolioLot("AMZN", 20_000L, 5_000L, null),
        ),
        scored = listOf(
            OpportunityListRow(
                symbol = "AMZN",
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
            ),
        ),
        upcomingReport = emptyMap(),
        today = LocalDate.of(2026, 9, 7),
        quotes = mapOf("AMZN" to PortfolioQuote(10_000L)),
    )

    private fun projectDetailRows() = projectPositions(
        lots = listOf(
            PortfolioLot("AMZN", 10_000L, 5_000L, null),
            PortfolioLot("OTHER", 30_000L, 5_000L, null),
        ),
        scored = listOf(
            OpportunityListRow(
                symbol = "AMZN",
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
            ),
        ),
        upcomingReport = emptyMap(),
        today = LocalDate.of(2026, 9, 7),
        quotes = mapOf(
            "AMZN" to PortfolioQuote(10_000L),
            "OTHER" to PortfolioQuote(10_000L),
        ),
    )
}
