package com.discountscreener.android.data.debug

import com.discountscreener.core.model.HistoricalCandle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The report is what the claim is read from, so what it must never do is go quiet.
 *
 * A retrospective that found nothing and a retrospective that never ran look the same to a reader
 * unless the text says which happened. Both cases are asserted here.
 */
class RetrospectiveReportTest {

    /** One line per horizon, always, whatever the number on it turns out to be. */
    @Test
    fun every_horizon_reports_a_top_minus_bottom_line() {
        var report = RetrospectiveReport.build(mapOf(SYMBOL to rising(BARS)))

        assertEquals(
            RetrospectiveReport.HORIZONS.size,
            report.lines().count { it.contains("top-minus-bottom") },
        )
    }

    /**
     * An empty store is not a null result and must not read as one. Before the first market refresh
     * lands there are no bars at all, and "no signal" would be a lie about data that does not exist.
     */
    @Test
    fun a_store_with_no_bars_says_so_rather_than_reporting_no_signal() {
        var report = RetrospectiveReport.build(emptyMap())

        assertEquals(1, report.lines().count { it.contains("shorter than the warmup") })
    }

    private fun rising(bars: Int) = List(bars) { bar ->
        var cents = 10_000L + bar * 20L + (bar % 7) * 15L
        HistoricalCandle(
            epochSeconds = FIRST_BAR + bar * 86_400L,
            openCents = cents,
            highCents = cents + 50L,
            lowCents = cents - 50L,
            closeCents = cents,
            volume = 1_000L,
        )
    }

    private companion object {
        const val SYMBOL = "AAPL"
        const val FIRST_BAR = 1_600_000_000L

        /** Warmup plus a horizon plus enough scored dates to cut into tenths. */
        const val BARS = 400
    }
}
