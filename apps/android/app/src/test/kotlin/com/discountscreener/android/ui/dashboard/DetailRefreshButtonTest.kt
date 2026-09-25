package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailRefreshButtonTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun load_button_requests_a_refresh_for_an_uncached_ticker() {
        val actions = mutableListOf<DashboardAction>()
        render(refreshing = false, actions = actions)

        composeRule.onNodeWithText("Load").performClick()

        assertEquals(listOf(DashboardAction.RefreshDetail), actions)
    }

    @Test
    fun button_is_disabled_during_ticker_refresh() {
        render(refreshing = true, actions = mutableListOf())

        composeRule.onNodeWithText("Loading").assertIsNotEnabled()
    }

    private fun render(refreshing: Boolean, actions: MutableList<DashboardAction>) {
        composeRule.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = DetailRoute("AAPL", DetailSourceTab.Tracked, listOf("AAPL")),
                    detail = null,
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    detailRefreshing = refreshing,
                    onAction = actions::add,
                )
            }
        }
    }
}
