package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.discountscreener.android.StuckTestWatchdog
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpportunityListValuationStanceTest {
    @get:Rule
    val stuckTestWatchdog = StuckTestWatchdog()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun disputed_row_does_not_name_a_single_discount() {
        setList(gapBps = null, stance = "Disputed")

        composeRule.onNodeWithText("Disc", substring = true).assertDoesNotExist()
    }

    @Test
    fun disputed_row_shows_the_stance_token() {
        setList(gapBps = null, stance = "Disputed")

        composeRule.onNodeWithText("Disputed").assertIsDisplayed()
    }

    private fun setList(gapBps: Int?, stance: String) {
        composeRule.setContent {
            DiscountScreenerTheme {
                OpportunityList(
                    rows = listOf(
                        OpportunityListRow(
                            symbol = "NVDA",
                            marketPriceCents = 10_000L,
                            intrinsicValueCents = 15_000L,
                            gapBps = gapBps,
                            upsideBps = null,
                            confidence = ConfidenceBand.High,
                            isWatched = false,
                            compositeScore = 20,
                            coverageCount = 3,
                            valuationStanceLabel = stance,
                        ),
                    ),
                    scoringModel = OpportunityScoringModel.AggressiveV3,
                    onAction = { },
                )
            }
        }
        composeRule.waitForIdle()
    }
}
