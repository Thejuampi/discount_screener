package com.discountscreener.android.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.DetailSubtab
import com.discountscreener.android.presentation.dashboard.HistorySubview
import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.android.ui.dashboard.DashboardScreen
import com.discountscreener.android.ui.dashboard.DetailScreen
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DiscountScreenerAppBackNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun system_back_from_detail_dispatches_existing_back_action() {
        val actions = mutableListOf<DashboardAction>()
        composeRule.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = DetailRoute(
                        symbol = "AAPL",
                        sourceTab = DetailSourceTab.Tracked,
                        sourceSymbols = listOf("AAPL", "MSFT"),
                        subtab = DetailSubtab.History,
                        historySubview = HistorySubview.Table,
                        replayOffset = 4,
                    ),
                    detail = null,
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    onAction = actions::add,
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(DashboardAction.BackFromDetail), actions)
        assertFalse(composeRule.activity.isFinishing)
    }

    @Test
    fun dashboard_root_back_keeps_default_activity_finish_behavior() {
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        loading = false,
                        startupPhase = DashboardStartupPhase.Ready,
                    ),
                    onAction = { },
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(composeRule.activity.isFinishing)
    }

    @Test
    fun system_back_from_detail_clears_search_before_leaving_detail() {
        val actions = mutableListOf<DashboardAction>()
        composeRule.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = DetailRoute(
                        symbol = "AAPL",
                        sourceTab = DetailSourceTab.Tracked,
                        sourceSymbols = listOf("AAPL", "MSFT"),
                    ),
                    detail = null,
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    tickerSearchQuery = "AA",
                    tickerSearchSuggestions = listOf(TickerSearchSuggestion(symbol = "AAPL")),
                    tickerSearchExpanded = true,
                    onAction = actions::add,
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(DashboardAction.ClearTickerSearch), actions)
        assertFalse(composeRule.activity.isFinishing)
    }

    @Test
    fun dashboard_back_clears_search_before_finishing_activity() {
        val actions = mutableListOf<DashboardAction>()
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        loading = false,
                        startupPhase = DashboardStartupPhase.Ready,
                        tickerSearchQuery = "AA",
                        tickerSearchSuggestions = listOf(TickerSearchSuggestion(symbol = "AAPL")),
                        tickerSearchExpanded = true,
                    ),
                    onAction = actions::add,
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(DashboardAction.ClearTickerSearch), actions)
        assertFalse(composeRule.activity.isFinishing)
    }
}
