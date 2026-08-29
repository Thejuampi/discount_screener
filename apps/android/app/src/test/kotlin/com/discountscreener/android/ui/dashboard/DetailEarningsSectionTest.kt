package com.discountscreener.android.ui.dashboard

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.EarningsEventRowUi
import com.discountscreener.android.presentation.dashboard.eventsFor
import com.discountscreener.android.presentation.dashboard.presentEarningsGate
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.decisionOf
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DetailEarningsSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun the_detail_of_a_logged_ticker_shows_its_earnings_section() {
        render(eventsOf("LVS"))

        composeRule.onNodeWithTag(DETAIL_SNAPSHOT_LIST)
            .performScrollToNode(hasTestTag(DETAIL_EARNINGS_SECTION))

        composeRule.onNodeWithTag(DETAIL_EARNINGS_SECTION).assertIsDisplayed()
    }

    @Test
    fun the_detail_of_a_logged_ticker_shows_the_move_the_market_is_paying_for() {
        render(eventsOf("LVS"))

        composeRule.onNodeWithTag(DETAIL_SNAPSHOT_LIST).performScrollToNode(hasText("7.01%"))

        composeRule.onNodeWithText("7.01%").assertIsDisplayed()
    }

    @Test
    fun the_detail_of_a_ticker_the_log_never_saw_shows_no_earnings_section() {
        render(emptyList())

        composeRule.onNodeWithTag(DETAIL_EARNINGS_SECTION).assertDoesNotExist()
    }

    @Test
    fun another_tickers_event_never_reaches_this_detail() {
        render(eventsOf("AVGO"))

        composeRule.onNodeWithTag(DETAIL_EARNINGS_SECTION).assertDoesNotExist()
    }

    @Test
    fun a_report_beyond_the_capture_window_says_the_gate_prices_it_closer_in() {
        assertEquals(
            "Earnings gate: the chain is priced inside 10 days of the report.",
            earningsGateAbsence(epochOf(30), NOW, UTC),
        )
    }

    @Test
    fun a_report_inside_the_window_with_no_record_says_the_pass_has_not_landed() {
        assertEquals(
            "Earnings gate: inside the window, still unpriced. A pass has to land with the market open.",
            earningsGateAbsence(epochOf(4), NOW, UTC),
        )
    }

    @Test
    fun a_report_on_the_last_day_of_the_window_still_counts_as_inside_it() {
        assertTrue(earningsGateAbsence(epochOf(10), NOW, UTC).contains("inside the window"))
    }

    @Test
    fun a_ticker_with_no_report_date_says_there_is_nothing_to_price() {
        assertEquals(
            "Earnings gate: no report date yet, so nothing to price.",
            earningsGateAbsence(null, NOW, UTC),
        )
    }

    @Test
    fun a_report_date_that_already_passed_reads_as_no_date_at_all() {
        assertEquals(
            "Earnings gate: no report date yet, so nothing to price.",
            earningsGateAbsence(epochOf(-3), NOW, UTC),
        )
    }

    @Test
    fun the_detail_of_a_ticker_the_log_never_saw_says_why_it_has_no_event() {
        render(emptyList())

        composeRule.onNodeWithTag(DETAIL_SNAPSHOT_LIST)
            .performScrollToNode(hasTestTag(DETAIL_EARNINGS_ABSENT))

        composeRule.onNodeWithTag(DETAIL_EARNINGS_ABSENT).assertIsDisplayed()
    }

    private fun epochOf(days: Long): Long = TODAY.plusDays(days).atStartOfDay(UTC).toEpochSecond()

    private fun render(events: List<EarningsEventRowUi>) {
        composeRule.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = DetailRoute(
                        symbol = "LVS",
                        sourceTab = DetailSourceTab.Tracked,
                        sourceSymbols = listOf("LVS"),
                    ),
                    detail = null,
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    earningsEvents = events,
                    onAction = {},
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun eventsOf(symbol: String): List<EarningsEventRowUi> {
        var pre = PreReport(
            symbol = symbol,
            reportEpochDay = TODAY.plusDays(3).toEpochDay(),
            timing = ReportTiming.AfterClose,
            priceCents = 4_424L,
            dcfFairValueCents = 42_633L,
            impliedMoveBps = 701,
            eventImpliedMoveBps = 677,
            normalDailyMoveBps = 180,
            medianAbsoluteAbnormalReturnBps = 400,
            riskRatioBps = 17_525,
        )
        var gate = presentEarningsGate(
            events = listOf(EarningsEventRecord(pre = pre, decision = decisionOf(pre))),
            damagedLines = 0,
            today = TODAY,
        )
        return gate.eventsFor(symbol)
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 23)
        val UTC: ZoneId = ZoneId.of("UTC")
        val NOW: Long = TODAY.atStartOfDay(UTC).toEpochSecond()
    }
}
