package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.discountscreener.android.presentation.dashboard.EarningsGateUi
import com.discountscreener.android.presentation.dashboard.presentEarningsGate
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.PostReport
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.decisionOf
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EarningsGateScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun an_empty_log_tells_the_user_the_log_only_grows_forward() {
        render(EarningsGateUi())

        composeRule.onNodeWithText("No earnings events logged yet").assertIsDisplayed()
    }

    @Test
    fun a_log_still_being_read_says_so_instead_of_claiming_it_is_empty() {
        render(EarningsGateUi(), loading = true)

        composeRule.onNodeWithText("Reading the earnings log").assertIsDisplayed()
    }

    @Test
    fun a_captured_report_shows_the_ticker_it_belongs_to() {
        render(gate(day = 3))

        composeRule.onNodeWithText("LVS").assertIsDisplayed()
    }

    @Test
    fun a_captured_report_shows_the_cell_it_falls_in() {
        render(gate(day = 3))

        composeRule.onNodeWithText("Cheap, high event risk").assertIsDisplayed()
    }

    @Test
    fun a_captured_report_shows_the_move_the_market_is_paying_for() {
        render(gate(day = 3))

        composeRule.onNodeWithText("7.01%").assertIsDisplayed()
    }

    @Test
    fun a_captured_report_separates_the_event_move_from_the_whole_expiry() {
        render(gate(day = 3))

        composeRule.onNodeWithText("6.77% after 1.80% a day of quiet drift").assertIsDisplayed()
    }

    @Test
    fun a_captured_report_shows_the_action_and_the_size_it_carries() {
        render(gate(day = 3))

        composeRule.onNodeWithText("Hedge · 50%").assertIsDisplayed()
    }

    @Test
    fun a_report_still_ahead_is_shown_under_the_reporting_soon_heading() {
        render(gate(day = 3))

        composeRule.onNodeWithText("REPORTING SOON").assertIsDisplayed()
    }

    @Test
    fun a_report_already_priced_shows_the_move_the_index_did_not_explain() {
        render(gate(day = -3, abnormalReturnBps = 412))

        composeRule.onNodeWithTag(EARNINGS_GATE_LIST)
            .performScrollToNode(hasText("Abnormal move +4.12%"))

        composeRule.onNodeWithText("Abnormal move +4.12%").assertIsDisplayed()
    }

    @Test
    fun a_damaged_log_line_is_reported_and_never_hidden() {
        render(gate(day = 3, damaged = 2))

        composeRule.onNodeWithTag(EARNINGS_GATE_LIST)
            .performScrollToNode(hasText("2 unreadable line(s) in the log, skipped."))

        composeRule.onNodeWithText("2 unreadable line(s) in the log, skipped.").assertIsDisplayed()
    }

    @Test
    fun a_captured_report_shows_what_the_hedge_would_cost() {
        render(gate(day = 3, spread = 80))

        composeRule.onNodeWithText("0.80% of the position (44.00 / 42.00 puts)").assertIsDisplayed()
    }

    @Test
    fun a_hedge_too_dear_to_buy_shows_the_smaller_position_instead() {
        render(gate(day = 3, spread = 150))

        composeRule.onNodeWithText("Reduce · 50%").assertIsDisplayed()
    }

    private fun render(state: EarningsGateUi, loading: Boolean = false) {
        composeRule.setContent {
            DiscountScreenerTheme {
                EarningsGateScreen(state = state, loading = loading)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun gate(
        day: Long,
        abnormalReturnBps: Int? = null,
        damaged: Int = 0,
        spread: Int? = null,
    ): EarningsGateUi {
        var pre = PreReport(
            symbol = "LVS",
            reportEpochDay = TODAY.plusDays(day).toEpochDay(),
            timing = ReportTiming.AfterClose,
            priceCents = 4_424L,
            dcfFairValueCents = 42_633L,
            impliedMoveBps = 701,
            eventImpliedMoveBps = 677,
            normalDailyMoveBps = 180,
            medianAbsoluteAbnormalReturnBps = 400,
            riskRatioBps = 17_525,
            putSpreadCostBps = spread,
            hedgeLongStrikeCents = 4_400L,
            hedgeShortStrikeCents = 4_200L,
        )
        return presentEarningsGate(
            events = listOf(
                EarningsEventRecord(
                    pre = pre,
                    decision = decisionOf(pre),
                    post = abnormalReturnBps?.let { PostReport(abnormalReturnBps = it) },
                ),
            ),
            damagedLines = damaged,
            today = TODAY,
        )
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 23)
    }
}
