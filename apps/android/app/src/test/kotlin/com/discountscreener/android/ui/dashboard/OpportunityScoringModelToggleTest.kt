package com.discountscreener.android.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.discountscreener.android.StuckTestWatchdog
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpportunityScoringModelToggleTest {
    /** Prints every thread's stack if this test stalls, so the next hang arrives with evidence. */
    @get:Rule
    val stuckTestWatchdog = StuckTestWatchdog()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    /**
     * Named for what the body does — walk [opportunityScoringModelChipOrder] — rather than for a
     * count. It said "four" while five chips shipped, and a name with a number in it goes stale
     * every time a model is added, silently, because nothing compiles against a test's name.
     */
    @Test
    fun opportunities_tab_exposes_every_scoring_model_chip() {
        setOpportunitiesContent(
            selected = OpportunityScoringModel.AggressiveV2,
            onAction = { },
        )

        opportunityScoringModelChipOrder.forEach { model ->
            composeRule
                .onNodeWithText(model.chipLabel())
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun selected_scoring_model_chip_reports_selected_state() {
        setOpportunitiesContent(
            selected = OpportunityScoringModel.AggressiveV2,
            onAction = { },
        )

        composeRule.onNodeWithText("Aggressive V2").assertIsSelected()
        composeRule.onNodeWithText("Aggressive V3").assertIsNotSelected()
        composeRule.onNodeWithText("Aggressive").assertIsNotSelected()
        composeRule
            .onNodeWithText("Legacy")
            .performScrollTo()
            .assertIsNotSelected()
    }

    @Test
    fun legacy_chip_is_selected_when_legacy_model_is_active() {
        setOpportunitiesContent(
            selected = OpportunityScoringModel.Legacy,
            onAction = { },
        )

        composeRule
            .onNodeWithText("Legacy")
            .performScrollTo()
            .assertIsSelected()
        composeRule.onNodeWithText("Aggressive V2").assertIsNotSelected()
        composeRule.onNodeWithText("Aggressive V3").assertIsNotSelected()
        composeRule.onNodeWithText("Aggressive").assertIsNotSelected()
    }

    @Test
    fun tapping_legacy_chip_dispatches_set_scoring_model() {
        val actions = mutableListOf<DashboardAction>()
        setOpportunitiesContent(
            selected = OpportunityScoringModel.AggressiveV2,
            onAction = actions::add,
        )

        composeRule
            .onNodeWithText("Legacy")
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.Legacy)),
            actions,
        )
    }

    /**
     * Two claims, two tests. They were one, and a failure could not say which had broken: a
     * renamed label and a model missing from the strip are different defects with different fixes.
     */
    @Test
    fun the_chips_read_newest_model_first() {
        assertEquals(
            listOf("Aggressive V4", "Aggressive V3", "Aggressive V2", "Aggressive", "Legacy"),
            opportunityScoringModelChipOrder.map { it.chipLabel() },
        )
    }

    /** A model with no chip is a model the user cannot reach. */
    @Test
    fun every_scoring_model_has_a_chip() {
        assertEquals(
            OpportunityScoringModel.entries.toSet(),
            opportunityScoringModelChipOrder.toSet(),
        )
    }

    private fun setOpportunitiesContent(
        selected: OpportunityScoringModel,
        onAction: (DashboardAction) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        loading = false,
                        startupPhase = DashboardStartupPhase.Ready,
                        currentProfile = "merval",
                        opportunityScoringModel = selected,
                        opportunityRows = listOf(sampleOpportunityRow()),
                    ),
                    onAction = onAction,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun sampleOpportunityRow() = OpportunityListRow(
        symbol = "TGNO4.BA",
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 5_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        compositeScore = 34,
        coverageCount = 3,
    )
}
