package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.PositionsRow
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensQualifier
import com.discountscreener.android.presentation.dashboard.projectPositions
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.portfolio.Closeness
import com.discountscreener.core.portfolio.PortfolioLot
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

        composeRule.onNodeWithTag(POSITIONS_GATE_IMPORT).assertIsDisplayed()
    }

    @Test
    fun pos_phyl_paints_qty_and_cost() {
        render(positionsState(listOf(phylRow())))

        composeRule.onNodeWithText("1273 · $35.28").assertIsDisplayed()
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

        composeRule.onNodeWithText("36.2954 · $214.03").assertIsDisplayed()
    }

    @Test
    fun pos_flags_amzn_matches_the_opps_strip() {
        render(positionsState(listOf(amznScoredRow()), chips = amznChips()))
        var expected = listOf(
            "Act",
            "F 20",
            "T 18",
            "Fc 12",
            "Disc 50.00%",
            "Upside 50.00%",
            "Conf high",
            "+ Strong signals",
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
    fun pos_sort_ordinal_paints_today_then_later_then_blank() {
        var monday = LocalDate.of(2026, 9, 7)
        var rows = projectPositions(
            lots = listOf(
                PortfolioLot("PHYL", 12_730_000L, 3_528L, null),
                PortfolioLot("AMZN", 362_954L, 21_403L, null),
                PortfolioLot("MSFT", 100_000L, 10_000L, null),
            ),
            scored = listOf(scored("AMZN"), scored("MSFT")),
            upcomingReport = mapOf(
                "MSFT" to monday,
                "AMZN" to LocalDate.of(2026, 9, 14),
            ),
            today = monday,
        )
        render(positionsState(rows))
        var msft = composeRule.onNodeWithText("MSFT").getUnclippedBoundsInRoot()
        var amzn = composeRule.onNodeWithText("AMZN").getUnclippedBoundsInRoot()
        var phyl = composeRule.onNodeWithText("PHYL").getUnclippedBoundsInRoot()

        assertTrue(msft.top < amzn.top && amzn.top < phyl.top)
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
