package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.earnings.EXCHANGE_ZONE
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.portfolio.Closeness
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.nySessionDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class PositionsPresentationTest {

    @Test
    fun phyl_is_an_off_feed_row() {
        var rows = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        )
        assertEquals(
            listOf("PHYL" to true),
            rows.map { it.symbol to (it.opportunity == null) },
        )
    }

    @Test
    fun phyl_qty_is_shares() {
        var row = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals("1273", row.quantityLabel)
    }

    @Test
    fun opps_row_wins_over_universe() {
        var universe = opp("AMZN", confidence = ConfidenceBand.High)
        var opps = opp("AMZN", confidence = ConfidenceBand.Low)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = scoredLots(listOf(universe), listOf(opps)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(opps, row.opportunity)
    }

    @Test
    fun amzn_keeps_the_scored_row_pointer() {
        var scored = opp("AMZN")
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(scored),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(scored, row.opportunity)
    }

    @Test
    fun amzn_qty_is_shares() {
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals("36.2954", row.quantityLabel)
    }

    @Test
    fun tagged_sorts_ahead_of_blank() {
        var rows = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L), lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = mapOf("AMZN" to LocalDate.of(2026, 9, 7)),
            today = LocalDate.of(2026, 9, 7),
        )
        assertEquals(listOf("AMZN", "PHYL"), rows.map { it.symbol })
    }

    @Test
    fun prefix_lot_does_not_take_the_amzn_strip() {
        var row = projectPositions(
            lots = listOf(lot("A", 100_000L, 10_000L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(null, row.opportunity)
    }

    @Test
    fun scored_row_with_no_date_is_none() {
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.None, row.closeness)
    }

    @Test
    fun sort_is_closeness_then_ticker() {
        var monday = LocalDate.of(2026, 9, 7)
        var rows = projectPositions(
            lots = listOf(
                lot("PHYL", 12_730_000L, 3_528L),
                lot("AMZN", 362_954L, 21_403L),
                lot("MSFT", 100_000L, 10_000L),
            ),
            scored = listOf(opp("AMZN"), opp("MSFT")),
            upcomingReport = mapOf(
                "MSFT" to monday,
                "AMZN" to LocalDate.of(2026, 9, 14),
            ),
            today = monday,
        )
        assertEquals(listOf("MSFT", "AMZN", "PHYL"), rows.map { it.symbol })
    }

    @Test
    fun blank_closeness_sorts_by_ticker() {
        var rows = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L), lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        )
        assertEquals(listOf("AMZN", "PHYL"), rows.map { it.symbol })
    }

    @Test
    fun empty_lots_paint_no_rows() {
        assertEquals(
            emptyList<String>(),
            projectPositions(emptyList(), emptyList(), emptyMap(), LocalDate.of(2026, 9, 7)).map { it.symbol },
        )
    }

    @Test
    fun presented_gate_log_date_is_today() {
        var monday = LocalDate.of(2026, 9, 7)
        var yahooLater = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var gate = presentEarningsGate(
            events = listOf(event("AMZN", monday)),
            damagedLines = 0,
            today = monday,
        )
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = yahooLater)),
            upcomingReport = upcomingReportDates(gate),
            today = monday,
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun settled_ny_today_print_is_still_today() {
        var monday = LocalDate.of(2026, 9, 7)
        var gate = presentEarningsGate(
            events = listOf(event("AMZN", monday)),
            damagedLines = 0,
            today = monday.plusDays(1),
        )
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = emptyList(),
            upcomingReport = upcomingReportDates(gate),
            today = monday,
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun log_date_beats_yahoo_epoch() {
        var monday = LocalDate.of(2026, 9, 7)
        var yahooLater = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = yahooLater)),
            upcomingReport = mapOf("AMZN" to monday),
            today = monday,
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun yahoo_epoch_is_the_fallback() {
        var tomorrow = LocalDateTime.of(2026, 9, 8, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = tomorrow)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.Tomorrow, row.closeness)
    }

    @Test
    fun yahoo_utc_midnight_uses_ny_session_day() {
        var midnight = LocalDateTime.of(2026, 9, 8, 0, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = midnight)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun past_yahoo_epoch_is_none() {
        var past = LocalDateTime.of(2026, 9, 6, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = past)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.None, row.closeness)
    }

    @Test
    fun off_feed_ignores_yahoo() {
        var tomorrow = LocalDateTime.of(2026, 9, 8, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = tomorrow)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.None, row.closeness)
    }

    @Test
    fun madrid_clock_does_not_move_ny_today() {
        var nyNoon = LocalDateTime.of(2026, 9, 7, 12, 0)
            .atZone(EXCHANGE_ZONE)
            .toInstant()
            .epochSecond
        assertEquals(LocalDate.of(2026, 9, 7), nySessionDay(nyNoon))
    }

    private fun event(symbol: String, report: LocalDate) = EarningsEventRecord(
        pre = PreReport(
            symbol = symbol,
            reportEpochDay = report.toEpochDay(),
            timing = ReportTiming.AfterClose,
            priceCents = 10_000L,
        ),
    )

    private fun lot(symbol: String, qty: Long, cost: Long) = PortfolioLot(symbol, qty, cost, null)

    private fun opp(
        symbol: String,
        nextEarningsEpoch: Long? = null,
        confidence: ConfidenceBand = ConfidenceBand.High,
    ) = OpportunityListRow(
        symbol = symbol,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        nextEarningsEpoch = nextEarningsEpoch,
        gapBps = 5_000,
        confidence = confidence,
        isWatched = false,
        compositeScore = 40,
        coverageCount = 3,
    )
}
