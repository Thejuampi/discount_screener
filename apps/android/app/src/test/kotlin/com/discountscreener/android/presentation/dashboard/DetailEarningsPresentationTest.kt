package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.decisionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class DetailEarningsPresentationTest {

    @Test
    fun a_priced_event_reuses_the_gate_card() {
        var events = presentEarningsGate(
            events = listOf(event("ORCL", TODAY.plusDays(3))),
            damagedLines = 0,
            today = TODAY,
        ).eventsFor("ORCL")
        var ui = presentDetailEarnings(
            symbol = "ORCL",
            events = events,
            calendarEpoch = null,
            scoreEpoch = null,
            today = TODAY,
            loading = false,
        )
        assertTrue(ui is DetailEarningsUi.Priced)
    }

    @Test
    fun a_dated_name_without_a_model_is_scheduled() {
        var later = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var ui = presentDetailEarnings(
            symbol = "BSX",
            events = emptyList(),
            calendarEpoch = later,
            scoreEpoch = null,
            today = TODAY,
            loading = false,
        )
        assertEquals(
            DetailEarningsUi.Scheduled(
                symbol = "BSX",
                reportDate = "2026-09-14",
                closenessLabel = "Later",
                body = SCHEDULED_LATER_BODY,
            ),
            ui,
        )
    }

    @Test
    fun a_name_with_no_date_is_a_quiet_card() {
        var ui = presentDetailEarnings(
            symbol = "PHYL",
            events = emptyList(),
            calendarEpoch = null,
            scoreEpoch = null,
            today = TODAY,
            loading = false,
        )
        assertEquals(
            DetailEarningsUi.Quiet(title = QUIET_EARNINGS_TITLE, body = QUIET_EARNINGS_BODY),
            ui,
        )
    }

    @Test
    fun loading_without_a_date_still_has_a_card() {
        var ui = presentDetailEarnings(
            symbol = "CAT",
            events = emptyList(),
            calendarEpoch = null,
            scoreEpoch = null,
            today = TODAY,
            loading = true,
        )
        assertEquals(DetailEarningsUi.Loading, ui)
    }

    @Test
    fun a_cached_date_beats_loading() {
        var later = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var ui = presentDetailEarnings(
            symbol = "CAT",
            events = emptyList(),
            calendarEpoch = later,
            scoreEpoch = null,
            today = TODAY,
            loading = true,
        )
        assertTrue(ui is DetailEarningsUi.Scheduled)
    }

    @Test
    fun another_tickers_event_does_not_price_this_detail() {
        var events = presentEarningsGate(
            events = listOf(event("AVGO", TODAY.plusDays(3))),
            damagedLines = 0,
            today = TODAY,
        ).eventsFor("CAT")
        var ui = presentDetailEarnings(
            symbol = "CAT",
            events = events,
            calendarEpoch = null,
            scoreEpoch = null,
            today = TODAY,
            loading = false,
        )
        assertTrue(ui is DetailEarningsUi.Quiet)
    }

    private fun event(symbol: String, report: LocalDate): EarningsEventRecord {
        var pre = PreReport(
            symbol = symbol,
            reportEpochDay = report.toEpochDay(),
            timing = ReportTiming.AfterClose,
            priceCents = 10_000L,
            dcfFairValueCents = 12_000L,
            impliedMoveBps = 700,
            eventImpliedMoveBps = 600,
            normalDailyMoveBps = 180,
            medianAbsoluteAbnormalReturnBps = 400,
            riskRatioBps = 15_000,
        )
        return EarningsEventRecord(pre = pre, decision = decisionOf(pre))
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 7)
    }
}
