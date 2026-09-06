package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.discountscreener.android.ui.verticalList
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.portfolio.ImportPlan
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.RefuseReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ImportBookScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun the_system_tab_offers_import_book() {
        render(DashboardUiState(loading = false, currentTab = DashboardTab.System, startupPhase = DashboardStartupPhase.Ready))

        composeRule.onNode(verticalList()).performScrollToNode(hasText("Import book"))
        composeRule.onNodeWithTag(SYSTEM_GATE_IMPORT).assertIsDisplayed()
    }

    @Test
    fun a_replace_plan_shows_confirm() {
        render(
            DashboardUiState(
                loading = false,
                startupPhase = DashboardStartupPhase.Ready,
                importBookPlan = ImportPlan.ConfirmHoldingsReplace(
                    format = "J.P. Morgan",
                    asOf = "2026-08-31",
                    positions = listOf(PortfolioLot("AMZN", 100_000, 20_000, null)),
                    remove = emptyList(),
                    ignored = 0,
                ),
            ),
        )

        composeRule.onNodeWithText("Confirm").assertIsDisplayed()
    }

    @Test
    fun a_refuse_plan_hides_confirm() {
        render(
            DashboardUiState(
                loading = false,
                startupPhase = DashboardStartupPhase.Ready,
                importBookPlan = ImportPlan.Refuse(RefuseReason.LedgerApplyUnsupported, "Coinbase"),
            ),
        )

        composeRule.onNodeWithText("Confirm").assertDoesNotExist()
    }

    @Test
    fun a_held_opportunity_shows_the_held_mark() {
        render(
            DashboardUiState(
                loading = false,
                startupPhase = DashboardStartupPhase.Ready,
                currentProfile = "qa",
                opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
                opportunityRows = listOf(
                    OpportunityListRow(
                        symbol = "AMZN",
                        marketPriceCents = 10_000L,
                        intrinsicValueCents = 15_000L,
                        gapBps = 5_000,
                        confidence = ConfidenceBand.High,
                        isWatched = false,
                        compositeScore = 40,
                        coverageCount = 3,
                        held = true,
                        scoreRank = 2,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Held").assertIsDisplayed()
    }

    @Test
    fun a_held_tracked_row_shows_the_held_mark() {
        render(
            DashboardUiState(
                loading = false,
                startupPhase = DashboardStartupPhase.Ready,
                currentTab = DashboardTab.Tracked,
                trackedRows = listOf(TrackedSymbolRow(symbol = "AMZN", held = true)),
            ),
        )

        composeRule.onNodeWithText("Held").assertIsDisplayed()
    }

    private fun render(state: DashboardUiState) {
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(state = state, onAction = { })
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }
}
