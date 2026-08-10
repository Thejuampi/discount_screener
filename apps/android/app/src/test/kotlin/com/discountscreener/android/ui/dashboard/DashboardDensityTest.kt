package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowExplanationKind
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensQualifier
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QuantLensLensId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How much of the viewport reaches the tickers.
 *
 * A ranked list that shows five names on a phone is a worse instrument than one that shows eight,
 * and the difference here was entirely chrome and half-empty strips rather than information. These
 * tests assert the two arrangements that bought it back, by geometry rather than by eye: a control
 * that slips onto a line of its own, or a chip strip that reserves a line whether or not it needs
 * one, is exactly the regression that would otherwise go unnoticed until someone counted rows again.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default screen is narrower than any phone this ships to, and "do these fit on one
// line" is a question only a real width can answer. 411x891dp is the emulator the layout was
// measured on; a narrower default would have failed a layout that is correct on every device.
@Config(qualifiers = "w411dp-h891dp")
class DashboardDensityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun the_model_chips_and_the_market_switch_share_one_line() {
        setControlsContent()

        val chips = composeRule.onNodeWithText("Aggressive V3").getUnclippedBoundsInRoot()
        val switch = composeRule.onNodeWithText("Market on").getUnclippedBoundsInRoot()

        assertTrue(
            "switch top ${switch.top} should sit above the chips' bottom ${chips.bottom}",
            switch.top < chips.bottom,
        )
    }

    /**
     * A model with no fourth bucket has no switch to place, and must not leave a gap where one
     * would have gone — the chips take the whole width instead.
     */
    @Test
    fun a_model_without_a_market_dimension_shows_no_switch_at_all() {
        setControlsContent(model = OpportunityScoringModel.AggressiveV2)

        composeRule.onNodeWithText("Market on").assertDoesNotExist()
    }

    @Test
    fun a_lens_chip_shares_its_line_with_the_row_badges() {
        setListContent()

        val badge = composeRule.onNodeWithText("Act").getUnclippedBoundsInRoot()
        val lens = composeRule.onNodeWithText("+ Strong signals").getUnclippedBoundsInRoot()

        assertTrue(
            "lens chip top ${lens.top} should sit above the badge's bottom ${badge.bottom}",
            lens.top < badge.bottom,
        )
    }

    /**
     * The freshness state and its age were two pills side by side saying one thing. They are one
     * pill now, which is what makes room for the lens chips on the same line.
     */
    @Test
    fun freshness_and_its_age_read_as_one_badge() {
        setListContent()

        composeRule.onNodeWithText("Updated now").assertExists()
    }

    /**
     * The widest badge on the strip said that nothing had happened. Its absence says the same, and
     * costs no pixels — but only while every other explanation still speaks for itself.
     */
    @Test
    fun a_row_where_nothing_moved_spends_no_width_saying_so() {
        setListContent(explanation = RowExplanationKind.NoMeaningfulChange)

        composeRule.onNodeWithText("No meaningful change").assertDoesNotExist()
    }

    @Test
    fun a_row_with_nothing_to_compare_against_still_says_so() {
        setListContent(explanation = RowExplanationKind.NoBaseline)

        composeRule.onNodeWithText("No baseline").assertExists()
    }

    private fun setControlsContent(model: OpportunityScoringModel = OpportunityScoringModel.AggressiveV3) {
        setContent {
            ScoringControlsRow(selected = model, regimeScoringEnabled = true, onAction = { })
        }
    }

    private fun setListContent(explanation: RowExplanationKind? = null) {
        setContent {
            OpportunityList(
                rows = listOf(row(explanation)),
                scoringModel = OpportunityScoringModel.AggressiveV3,
                quantLensChipsBySymbol = mapOf(
                    SYMBOL to listOf(
                        QuantLensChipUi(QuantLensLensId.EvidenceStrength, "Strong signals", QuantLensQualifier.Positive),
                    ),
                ),
                onAction = { },
            )
        }
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { DiscountScreenerTheme { content() } }
        composeRule.waitForIdle()
    }

    private fun row(explanation: RowExplanationKind? = null) = OpportunityListRow(
        symbol = SYMBOL,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 5_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        fundamentalsScore = 20,
        technicalScore = 20,
        forecastScore = 20,
        compositeScore = 34,
        compositeScoreBase = 34,
        coverageCount = 3,
        decisionState = RowDecisionState.Act,
        freshness = RowFreshness.Updated,
        // Seconds, not millis, and read against the wall clock: anything under a minute old reads
        // "now", so the label is stable without a clock seam that only this test would use.
        freshnessAsOfEpochSeconds = System.currentTimeMillis() / 1000L,
        explanation = explanation,
    )

    private companion object {
        const val SYMBOL = "ACME.BA"
    }
}
