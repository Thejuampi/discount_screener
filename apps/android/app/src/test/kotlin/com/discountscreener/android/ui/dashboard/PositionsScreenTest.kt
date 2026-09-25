package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.PositionsRow
import com.discountscreener.android.presentation.dashboard.PositionsBookSummary
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensQualifier
import com.discountscreener.android.presentation.dashboard.projectPositions
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.portfolio.Closeness
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.PortfolioQuote
import java.io.File
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PositionsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pos_empty_shows_no_lots() {
        render(positionsState(emptyList()))

        composeRule.onNodeWithText("No lots").assertIsDisplayed()
    }

    @Test
    fun pos_import_nonempty_keeps_import_book() {
        render(positionsState(listOf(amznScoredRow())))

        composeRule.onNodeWithContentDescription("Positions menu").performClick()
        composeRule.onNodeWithTag(POSITIONS_GATE_IMPORT).assertIsDisplayed()
    }

    @Test
    fun pos_phyl_paints_qty_and_cost() {
        render(positionsState(listOf(phylRow())))

        composeRule.onNodeWithContentDescription("Show position facts for PHYL").performClick()
        composeRule.onNodeWithText("Shares 1273").assertIsDisplayed()
        composeRule.onNodeWithText("Average cost $35.28").assertIsDisplayed()
    }

    @Test
    fun pos_phyl_omits_held() {
        render(positionsState(listOf(phylRow())))

        composeRule.onNodeWithText("Held").assertDoesNotExist()
    }

    @Test
    fun pos_phyl_omits_the_dash_chip() {
        render(positionsState(listOf(phylRow())))

        composeRule.onNodeWithText("Lens loading").assertDoesNotExist()
    }

    @Test
    fun pos_amzn_paints_qty_and_cost() {
        render(positionsState(listOf(amznScoredRow())))

        composeRule.onNodeWithContentDescription("Show position facts for AMZN").performClick()
        composeRule.onNodeWithText("Shares 36.2954").assertIsDisplayed()
        composeRule.onNodeWithText("Average cost $214.03").assertIsDisplayed()
    }

    @Test
    fun pos_flags_amzn_shows_compact_research_and_score() {
        render(positionsState(listOf(amznScoredRow()), chips = amznChips()))
        var expected = listOf(
            "Check data · Missing analysis",
            "Score 40 · V3",
        )

        assertEquals(expected, expected.filter(::shown))
    }

    @Test
    fun positions_omits_the_held_mark_on_a_scored_lot() {
        render(positionsState(listOf(amznScoredRow(held = true))))

        composeRule.onNodeWithText("Held").assertDoesNotExist()
    }

    @Test
    fun close_tz_paints_today() {
        var prior = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        try {
            render(positionsState(listOf(phylRow(closeness = Closeness.Today))))

            composeRule.onNodeWithText("Today").assertIsDisplayed()
        } finally {
            TimeZone.setDefault(prior)
        }
    }

    @Test
    fun close_tomorrow_paints_tomorrow() {
        render(positionsState(listOf(phylRow(closeness = Closeness.Tomorrow))))

        composeRule.onNodeWithText("Tomorrow").assertIsDisplayed()
    }

    @Test
    fun close_week_paints_this_week() {
        render(positionsState(listOf(phylRow(closeness = Closeness.ThisWeek))))

        composeRule.onNodeWithText("This week").assertIsDisplayed()
    }

    @Test
    fun close_later_paints_later() {
        render(positionsState(listOf(phylRow(closeness = Closeness.Later))))

        composeRule.onNodeWithText("Later").assertIsDisplayed()
    }

    @Test
    fun close_none_omits_the_tag() {
        render(positionsState(listOf(phylRow())))
        var tags = listOf("Today", "Tomorrow", "This week", "Later")

        assertEquals(emptyList<String>(), tags.filter(::shown))
    }

    @Test
    fun pos_tap_phyl_is_a_no_op() {
        var actions = mutableListOf<DashboardAction>()
        render(positionsState(listOf(phylRow())), onAction = { actions += it })
        composeRule.onNodeWithText("PHYL").performClick()

        assertEquals(emptyList<DashboardAction>(), actions)
    }

    @Test
    fun scored_lot_tap_opens_detail() {
        var actions = mutableListOf<DashboardAction>()
        render(positionsState(listOf(amznScoredRow())), onAction = { actions += it })
        composeRule.onNodeWithText("AMZN").performClick()

        assertEquals(listOf(DashboardAction.OpenDetail("AMZN")), actions)
    }

    @Test
    fun positions_compose_does_not_own_the_clock() {
        var candidates = listOf(
            File("src/main/kotlin/com/discountscreener/android/ui/dashboard"),
            File("app/src/main/kotlin/com/discountscreener/android/ui/dashboard"),
        )
        var dir = candidates.first { it.exists() }
        var text = File(dir, "DashboardLists.kt").readText() + File(dir, "DashboardScreen.kt").readText()

        assertEquals(false, "LocalDate.now" in text)
    }

    @Test
    fun positions_summary_shows_stock_value_pl_and_two_decimal_weight() {
        val rows = projectPositions(
            lots = listOf(
                PortfolioLot("AAA", 10_000L, 5_000L, null),
                PortfolioLot("BBB", 30_000L, 10_000L, null),
            ),
            scored = listOf(scored("AAA"), scored("BBB")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf(
                "AAA" to PortfolioQuote(10_000L),
                "BBB" to PortfolioQuote(10_000L),
            ),
        )
        render(positionsState(rows))

        composeRule.onNodeWithText("Stock value").assertIsDisplayed()
        composeRule.onNodeWithText("$400.00").assertIsDisplayed()
        composeRule.onNodeWithText("Unrealized P/L").assertIsDisplayed()
        composeRule.onNodeWithText("$50.00").assertIsDisplayed()
        composeRule.onNodeWithText("25.00%").assertIsDisplayed()
        composeRule.onNodeWithText("75.00%").assertIsDisplayed()
    }

    @Test
    fun positions_summary_discloses_stored_quote_and_lot_status() {
        val rows = projectPositions(
            lots = listOf(PortfolioLot("AAA", 10_000L, 5_000L, null)),
            scored = listOf(scored("AAA")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L, isCurrent = false)),
        )
        render(positionsState(rows))

        composeRule.onNodeWithText("Some quote ages are unconfirmed").assertIsDisplayed()
        composeRule.onNodeWithText("Score 40 · V3 · Stored").assertExists()
        composeRule.onNodeWithContentDescription("Show position facts for AAA").performClick()
        composeRule.onNodeWithText("Quote status Unconfirmed").assertIsDisplayed()
    }

    @Test
    fun positions_summary_explains_aggregate_overflow_without_losing_coverage() {
        composeRule.setContent {
            DiscountScreenerTheme {
                PositionsSummary(
                    PositionsBookSummary(
                        totalValueCents = null,
                        valueCoverage = com.discountscreener.core.portfolio.Coverage.Complete,
                        valueEligibleLots = 1,
                        totalLots = 1,
                        profitLossCents = null,
                        profitLossBps = null,
                        profitLossCoverage = com.discountscreener.core.portfolio.Coverage.Complete,
                        profitLossEligibleLots = 1,
                        hasNonCurrentQuotes = false,
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Unavailable: total exceeds supported range").assertIsDisplayed()
        composeRule.onNodeWithText("P/L % unavailable: total exceeds supported range").assertIsDisplayed()
        composeRule.onNodeWithText("Value coverage: Complete (1/1)").assertIsDisplayed()
        composeRule.onNodeWithText("P/L coverage: Complete (1/1)").assertIsDisplayed()
    }

    @Test
    fun positions_summary_keeps_zero_cost_percentage_reason_separate_from_overflow() {
        composeRule.setContent {
            DiscountScreenerTheme {
                PositionsSummary(
                    PositionsBookSummary(
                        totalValueCents = 10_000L,
                        valueCoverage = com.discountscreener.core.portfolio.Coverage.Complete,
                        valueEligibleLots = 1,
                        totalLots = 1,
                        profitLossCents = 5_000L,
                        profitLossBps = null,
                        profitLossCoverage = com.discountscreener.core.portfolio.Coverage.Complete,
                        profitLossEligibleLots = 1,
                        hasNonCurrentQuotes = false,
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("P/L % unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("P/L % unavailable: total exceeds supported range").assertDoesNotExist()
    }

    @Test
    fun positions_partial_book_shows_coverage_and_unavailable_weights() {
        val rows = projectPositions(
            lots = listOf(
                PortfolioLot("AAA", 10_000L, 5_000L, null),
                PortfolioLot("BBB", 30_000L, 10_000L, null),
            ),
            scored = listOf(scored("AAA")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        )
        render(positionsState(rows))

        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasText("Value coverage: Partial (1/2)"))
        composeRule.onNodeWithText("Value coverage: Partial (1/2)").assertIsDisplayed()
        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasTestTag("$POSITION_ROW_PREFIX${rows[1].symbol}:${rows[1].inputIndex}"))
        composeRule.onNodeWithContentDescription("Show position facts for BBB").performClick()
        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasText("Weight unavailable: Incomplete book"))
        composeRule.onNodeWithText("Weight unavailable: Incomplete book").assertIsDisplayed()
        composeRule.onNodeWithTag(POSITIONS_LIST)
            .performScrollToNode(hasText("Price unavailable: Missing quote"))
        composeRule.onNodeWithText("Price unavailable: Missing quote").assertIsDisplayed()
    }

    @Test
    fun positions_local_facts_expand_without_dispatching_off_feed_detail() {
        val rows = projectPositions(
            lots = listOf(PortfolioLot("PHYL", 12_730_000L, 3_528L, null)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf("PHYL" to PortfolioQuote(4_000L)),
        )
        val actions = mutableListOf<DashboardAction>()
        render(positionsState(rows), onAction = { actions += it })

        composeRule.onNodeWithContentDescription("Show position facts for PHYL").performClick()
        composeRule.onNodeWithText("Shares 1273").assertIsDisplayed()
        composeRule.onNodeWithText("Average cost $35.28").assertIsDisplayed()
        assertEquals(emptyList<DashboardAction>(), actions)
    }

    @Test
    fun positions_menu_keeps_import_and_sort_choices() {
        render(positionsState(listOf(amznScoredRow())))

        composeRule.onNodeWithContentDescription("Positions menu").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Largest position").get(1).assertExists()
        composeRule.onNodeWithText("Needs review").assertExists()
        composeRule.onNodeWithText("Earnings soon").assertExists()
        composeRule.onNodeWithTag(POSITIONS_GATE_IMPORT).assertIsDisplayed()
    }

    @Test
    fun positions_narrow_layout_keeps_reason_and_required_values_visible() {
        val rows = projectPositions(
            lots = listOf(PortfolioLot("PHYL", 12_730_000L, 3_528L, null)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = emptyMap(),
        )
        render(positionsState(rows))

        composeRule.onNodeWithTag(POSITIONS_LIST).performScrollToNode(hasText("PHYL"))
        composeRule.onNodeWithText("PHYL").assertIsDisplayed()
        composeRule.onNodeWithText("Check data · Missing quote").assertIsDisplayed()
    }

    @Test
    fun positions_money_keeps_large_cent_values_exact() {
        assertEquals("$92,233,720,368,547,758.07", positionMoney(Long.MAX_VALUE))
    }

    private fun shown(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun render(state: DashboardUiState, onAction: (DashboardAction) -> Unit = { }) {
        composeRule.setContent {
            DiscountScreenerTheme {
                DashboardScreen(state = state, onAction = onAction)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun positionsState(
        rows: List<PositionsRow>,
        chips: Map<String, List<QuantLensChipUi>> = emptyMap(),
    ) = DashboardUiState(
        loading = false,
        currentTab = DashboardTab.Positions,
        startupPhase = DashboardStartupPhase.Ready,
        opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
        positionsRows = rows,
        rowQuantLensChipsBySymbol = chips,
    )

    private fun phylRow(closeness: Closeness = Closeness.None) = PositionsRow(
        symbol = "PHYL",
        quantityLabel = "1273",
        avgCostCents = 3_528L,
        closeness = closeness,
        opportunity = null,
    )

    private fun amznScoredRow(
        closeness: Closeness = Closeness.None,
        held: Boolean = false,
    ) = PositionsRow(
        symbol = "AMZN",
        quantityLabel = "36.2954",
        avgCostCents = 21_403L,
        closeness = closeness,
        opportunity = scored("AMZN", held = held),
    )

    private fun scored(symbol: String, held: Boolean = false) = OpportunityListRow(
        symbol = symbol,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 5_000,
        upsideBps = 5_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        fundamentalsScore = 20,
        technicalScore = 18,
        forecastScore = 12,
        compositeScore = 40,
        coverageCount = 3,
        decisionState = RowDecisionState.Act,
        freshness = RowFreshness.Updated,
        held = held,
    )

    private fun amznChips() = mapOf(
        "AMZN" to listOf(
            QuantLensChipUi(QuantLensLensId.EvidenceStrength, "Strong signals", QuantLensQualifier.Positive),
        ),
    )
}
