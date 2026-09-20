package com.discountscreener.android.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.projectPositions
import com.discountscreener.android.ui.dashboard.DETAIL_SNAPSHOT_LIST
import com.discountscreener.android.ui.dashboard.POSITIONS_LIST
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.PortfolioQuote
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class DashboardRouteContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun route_without_detail_mounts_the_real_dashboard_positions_surface() {
        val rows = projectRows()
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardRouteContent(
                    state = state(rows),
                    earningsToday = LocalDate.of(2026, 9, 7),
                    onAction = {},
                )
            }
        }
        idle()

        composeRule.onNodeWithTag(POSITIONS_LIST).assertIsDisplayed()
        composeRule.onNodeWithText("Stock value").assertIsDisplayed()
    }

    @Test
    fun route_detail_filters_exact_symbol_and_keeps_duplicate_book_lots() {
        val rows = projectRows()
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardRouteContent(
                    state = state(rows).copy(
                        detailRoute = DetailRoute(
                            symbol = "amzn",
                            sourceTab = DetailSourceTab.Opportunities,
                            sourceSymbols = listOf("AMZN"),
                        ),
                    ),
                    earningsToday = LocalDate.of(2026, 9, 7),
                    onAction = {},
                )
            }
        }
        idle()

        composeRule.onNodeWithTag(DETAIL_SNAPSHOT_LIST).assertIsDisplayed()
        composeRule.onNodeWithText("Position lot 1").assertIsDisplayed()
        composeRule.onNodeWithText("Position lot 2").assertIsDisplayed()
        composeRule.onNodeWithText("Weight 16.67%").assertIsDisplayed()
        composeRule.onNodeWithText("Weight 33.33%").assertIsDisplayed()
        composeRule.onNodeWithText("Position lot 3").assertDoesNotExist()
    }

    private fun state(rows: List<com.discountscreener.android.presentation.dashboard.PositionsRow>) = DashboardUiState(
        loading = false,
        currentTab = DashboardTab.Positions,
        startupPhase = DashboardStartupPhase.Ready,
        opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
        positionsRows = rows,
    )

    private fun projectRows() = projectPositions(
        lots = listOf(
            PortfolioLot("AMZN", 10_000L, 5_000L, null),
            PortfolioLot("AMZN", 20_000L, 6_000L, null),
            PortfolioLot("OTHER", 30_000L, 5_000L, null),
        ),
        scored = emptyList(),
        upcomingReport = emptyMap(),
        today = LocalDate.of(2026, 9, 7),
        quotes = mapOf(
            "AMZN" to PortfolioQuote(10_000L),
            "OTHER" to PortfolioQuote(10_000L),
        ),
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }
}
