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
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun opportunities_tab_exposes_all_four_scoring_model_chips() {
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

    @Test
    fun chip_labels_cover_every_scoring_model() {
        assertEquals(
            listOf("Aggressive V3", "Aggressive V2", "Aggressive", "Legacy"),
            opportunityScoringModelChipOrder.map { it.chipLabel() },
        )
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
