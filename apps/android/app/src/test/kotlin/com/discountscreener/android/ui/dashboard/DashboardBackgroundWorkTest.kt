package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DashboardBackgroundWorkTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun background_progress_remains_visible_after_quote_refresh_reaches_total() {
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        startupPhase = DashboardStartupPhase.Ready,
                        refreshCompletedSymbols = 501,
                        refreshTargetSymbols = 501,
                        backgroundWorkMessage = "Enriching charts and model inputs 20/501",
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Enriching charts and model inputs 20/501").assertIsDisplayed()
    }

    @Test
    fun system_hides_feed_counter_after_refresh_finishes() {
        assertNull(systemFeedProgressLabel(DashboardUiState(
            startupPhase = DashboardStartupPhase.Ready,
            refreshCompletedSymbols = 0,
            refreshTargetSymbols = 20,
        )))
    }

    @Test
    fun restore_failure_shows_no_fake_feed_progress() {
        assertNull(systemFeedProgressLabel(DashboardUiState(
            startupPhase = DashboardStartupPhase.RestoreFailed,
            refreshCompletedSymbols = 0,
            refreshTargetSymbols = 20,
        )))
    }

    @Test
    fun restore_failure_message_is_visible_on_the_dashboard() {
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        loading = false,
                        startupPhase = DashboardStartupPhase.RestoreFailed,
                        statusMessage = "Could not read saved data. Data is preserved; retry Refresh.",
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Could not read saved data. Data is preserved; retry Refresh.").assertIsDisplayed()
    }

    @Test
    fun system_shows_feed_counter_during_refresh() {
        assertEquals("Progress: 5/20", systemFeedProgressLabel(DashboardUiState(
            startupPhase = DashboardStartupPhase.Refreshing,
            refreshCompletedSymbols = 5,
            refreshTargetSymbols = 20,
        )))
    }
}
