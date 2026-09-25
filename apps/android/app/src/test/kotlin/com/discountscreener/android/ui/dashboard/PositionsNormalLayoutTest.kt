package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.projectPositions
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.portfolio.PortfolioLot
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PositionsNormalLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun positions_320dp_normal_text_keeps_required_facts_inside_the_row() {
        val today = LocalDate.of(2026, 9, 7)
        val projected = projectPositions(
            lots = listOf(PortfolioLot("PHYL", 12_730_000L, 3_528L, null)),
            scored = emptyList(),
            upcomingReport = mapOf("PHYL" to today),
            today = today,
        ).single().copy(
            researchReason = "Provider quote is unavailable for this stored position lot",
        )
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        loading = false,
                        currentTab = DashboardTab.Positions,
                        startupPhase = DashboardStartupPhase.Ready,
                        opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
                        positionsRows = listOf(projected),
                    ),
                    onAction = {},
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        val rowTag = "$POSITION_ROW_PREFIX${projected.symbol}:${projected.inputIndex}"
        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasTestTag(rowTag))
        composeRule.onNodeWithContentDescription("Show position facts for PHYL").performClick()

        composeRule.onNodeWithText("PHYL").assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Value unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Check data · Provider quote is unavailable for this stored position lot")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasText("Price unavailable: Missing quote"))
        composeRule.onNodeWithText("Price unavailable: Missing quote").assertIsDisplayed()
        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasText("Weight unavailable: Incomplete book"))
        composeRule.onNodeWithText("Weight unavailable: Incomplete book").assertIsDisplayed()

        val listBounds = composeRule.onNodeWithTag(POSITIONS_LIST).getUnclippedBoundsInRoot()
        val rowBounds = composeRule.onNodeWithTag(rowTag).getUnclippedBoundsInRoot()
        val reasonNode = composeRule.onNodeWithText(
            "Check data · Provider quote is unavailable for this stored position lot",
        )
        val layouts = mutableListOf<TextLayoutResult>()
        reasonNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(false, layouts.single().hasVisualOverflow)
        val reasonBounds = reasonNode.getUnclippedBoundsInRoot()
        assertTrue("position row overflows horizontally", rowBounds.left >= listBounds.left && rowBounds.right <= listBounds.right)
        assertTrue("research reason overflows horizontally", reasonBounds.left >= listBounds.left && reasonBounds.right <= listBounds.right)
    }
}
