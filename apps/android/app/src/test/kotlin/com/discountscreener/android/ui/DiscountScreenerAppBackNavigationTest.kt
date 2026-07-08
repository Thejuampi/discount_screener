package com.discountscreener.android.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DiscountScreenerAppBackNavigationTest {
    @Test
    fun system_back_from_detail_dispatches_existing_back_action() {
        val actions = mutableListOf<DashboardAction>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.setContent {
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

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(DashboardAction.BackFromDetail), actions)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun dashboard_root_back_keeps_default_activity_finish_behavior() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.setContent {
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

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun system_back_from_detail_clears_search_before_leaving_detail() {
        val actions = mutableListOf<DashboardAction>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.setContent {
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

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(DashboardAction.ClearTickerSearch), actions)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun dashboard_back_clears_search_before_finishing_activity() {
        val actions = mutableListOf<DashboardAction>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.setContent {
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

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(DashboardAction.ClearTickerSearch), actions)
        assertFalse(activity.isFinishing)
    }
}
