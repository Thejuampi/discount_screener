package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.discountscreener.android.StuckTestWatchdog
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.android.ui.verticalList
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Opportunities list is ranked but used to render no placing — only rank *movement*. Discovery
 * already showed `#N`; these pin that Opportunities now does too, off the same shared composable.
 */
@RunWith(RobolectricTestRunner::class)
class OpportunityListRankOrdinalTest {
    /** Prints every thread's stack if this test stalls, so the next hang arrives with evidence. */
    @get:Rule
    val stuckTestWatchdog = StuckTestWatchdog()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun opportunity_rows_carry_their_placing() {
        setOpportunitiesContent(rowCount = 3)

        assertOrdinalReached("#1")
        assertOrdinalReached("#2")
        assertOrdinalReached("#3")
    }

    /**
     * The row card is clickable, so its semantics merge — one node holds both the ordinal and the
     * symbol. Matching on the pair is what makes this fail on a shuffle or an off-by-one, where
     * asserting the two texts separately would pass on any ordering.
     */
    @Test
    fun the_placing_follows_the_order_the_rows_arrive_in() {
        setOpportunitiesContent(rowCount = 2)

        composeRule.onNode(verticalList()).performScrollToNode(hasText("#2"))

        composeRule.onNode(hasText("#2") and hasText("SYM1.BA")).assertExists()
    }

    /** Scrolls the list until [ordinal] is composed, then requires it on screen. */
    private fun assertOrdinalReached(ordinal: String) {
        composeRule.onNode(verticalList()).performScrollToNode(hasText(ordinal))
        composeRule.onNodeWithText(ordinal).assertIsDisplayed()
    }

    private fun setOpportunitiesContent(rowCount: Int) {
        val rows = List(rowCount) { index ->
            OpportunityListRow(
                symbol = if (index == 0) "TOP.BA" else "SYM$index.BA",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 15_000L,
                gapBps = 5_000,
                confidence = ConfidenceBand.High,
                isWatched = false,
                compositeScore = 50 - index,
                coverageCount = 3,
            )
        }
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        loading = false,
                        startupPhase = DashboardStartupPhase.Ready,
                        currentProfile = "merval",
                        opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
                        opportunityRows = rows,
                    ),
                    onAction = { },
                )
            }
        }
        composeRule.waitForIdle()
    }
}
