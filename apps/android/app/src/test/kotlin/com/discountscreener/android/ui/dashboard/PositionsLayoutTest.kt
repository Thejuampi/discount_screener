package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.projectPositions
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.PortfolioQuote
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PositionsLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun positions_narrow_large_text_keeps_unavailable_reason_and_earnings_visible() {
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
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.5f)) {
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
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasTestTag("$POSITION_ROW_PREFIX${projected.symbol}:${projected.inputIndex}"))
        composeRule.onNodeWithContentDescription("Show position facts for PHYL").performClick()

        composeRule.onNodeWithText("PHYL").assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Value unavailable").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Check data · Provider quote is unavailable for this stored position lot",
        ).assertIsDisplayed()
        val listBounds = composeRule.onNodeWithTag(POSITIONS_LIST).getUnclippedBoundsInRoot()
        val reasonNode = composeRule.onNodeWithText(
            "Check data · Provider quote is unavailable for this stored position lot",
        )
        assertNoVisualOverflow(reasonNode)
        val reasonBounds = reasonNode.getUnclippedBoundsInRoot()
        assertTrue("research reason overflows horizontally", reasonBounds.left >= listBounds.left && reasonBounds.right <= listBounds.right)
        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasText("Weight unavailable: Incomplete book"))
        composeRule.onNodeWithText("Weight unavailable: Incomplete book").assertIsDisplayed()
        val rowBounds = composeRule
            .onNodeWithTag("$POSITION_ROW_PREFIX${projected.symbol}:${projected.inputIndex}")
            .getUnclippedBoundsInRoot()
        assertTrue("position row overflows horizontally", rowBounds.left >= listBounds.left && rowBounds.right <= listBounds.right)
    }

    @Test
    fun positions_320dp_large_text_keeps_current_priced_row_facts_readable() {
        val row = pricedRow(stored = false)
        renderPriced(row)

        assertPricedRow("Score 40 · V3", "Quote status Current")
    }

    @Test
    fun positions_320dp_large_text_keeps_stored_score_and_quote_disclosure_readable() {
        val row = pricedRow(stored = true)
        renderPriced(row)

        assertPricedRow("Score 40 · V3 · Stored", "Quote status Unconfirmed")
    }

    private fun renderPriced(row: com.discountscreener.android.presentation.dashboard.PositionsRow) {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1.5f)) {
                DiscountScreenerTheme {
                    DashboardScreen(
                        state = DashboardUiState(
                            loading = false,
                            currentTab = DashboardTab.Positions,
                            startupPhase = DashboardStartupPhase.Ready,
                            opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
                            positionsRows = listOf(row),
                        ),
                        onAction = {},
                    )
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Show position facts for AMZN").performClick()
    }

    private fun assertPricedRow(scoreText: String, quoteStatus: String) {
        val listBounds = composeRule.onNodeWithTag(POSITIONS_LIST).getUnclippedBoundsInRoot()
        val rowBounds = composeRule
            .onNodeWithTag("${POSITION_ROW_PREFIX}AMZN:0")
            .getUnclippedBoundsInRoot()
        listOf("AMZN", "$100.00", "100.00%", "Model: Undervalued", scoreText, "Stock value", "Unrealized P/L")
            .forEach { text ->
                val matches = composeRule.onAllNodesWithText(text, useUnmergedTree = true)
                val node = (0 until matches.fetchSemanticsNodes().size)
                    .map { matches.get(it) }
                    .firstOrNull { candidate ->
                        val bounds = candidate.getUnclippedBoundsInRoot()
                        bounds.top >= rowBounds.top && bounds.bottom <= rowBounds.bottom
                    }
                    ?: matches.get(0)
                node.assertIsDisplayed()
                assertNoVisualOverflow(node, "$text bounds=${node.getUnclippedBoundsInRoot()}")
                val bounds = node.getUnclippedBoundsInRoot()
                assertTrue("$text overflows horizontally", bounds.left >= listBounds.left && bounds.right <= listBounds.right)
            }
        composeRule.onNodeWithText(quoteStatus).assertIsDisplayed()
    }

    private fun pricedRow(stored: Boolean) = projectPositions(
        lots = listOf(PortfolioLot("AMZN", 10_000L, 5_000L, null)),
        scored = listOf(
            OpportunityListRow(
                symbol = "AMZN",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 15_000L,
                confidence = ConfidenceBand.High,
                isWatched = false,
                fundamentalsScore = 20,
                technicalScore = 18,
                forecastScore = 12,
                compositeScore = 40,
                coverageCount = 3,
                decisionState = RowDecisionState.Act,
                freshness = RowFreshness.Updated,
                valuationStanceLabel = "Identity",
            ),
        ),
        upcomingReport = emptyMap(),
        today = LocalDate.of(2026, 9, 7),
        quotes = mapOf("AMZN" to PortfolioQuote(10_000L, isCurrent = !stored)),
    ).single()

    private fun assertNoVisualOverflow(
        node: androidx.compose.ui.test.SemanticsNodeInteraction,
        label: String = "text",
    ) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertEquals(
            "visual overflow: $label size=${layout.size} lines=${layout.lineCount}",
            false,
            layout.hasVisualOverflow,
        )
    }
}
