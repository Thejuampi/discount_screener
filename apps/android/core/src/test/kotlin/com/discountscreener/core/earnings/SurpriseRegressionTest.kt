package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurpriseRegressionTest {

    @Test
    fun a_short_history_is_unavailable() {
        var fit = fitSurpriseRegression(series(15))
        assertEquals("short_history", (fit as SurpriseFit.Unavailable).reason)
    }

    @Test
    fun sixteen_quarters_fit_the_slope_in_ar_per_dispersion() {
        var fit = fitSurpriseRegression(series(16))
        assertEquals(300, (fit as SurpriseFit.Ready).sueSlopeArBps)
    }

    @Test
    fun a_symmetric_series_does_not_turn_on_the_positive_dummy() {
        var fit = fitSurpriseRegression(series(16))
        assertEquals(false, (fit as SurpriseFit.Ready).asymmetric)
    }

    @Test
    fun a_quarter_joins_the_abnormal_return_on_its_report_day() {
        var quarter = SueQuarter(
            fiscalEnd = LocalDate.of(2026, 6, 30),
            reportedOn = LocalDate.of(2026, 7, 22),
            actualEps = 2.93,
            meanEps = 2.65,
            lowEps = 2.50,
            highEps = 2.80,
            sueBps = 10_000,
            revenueSurpriseBps = null,
        )
        var returns = listOf(
            DatedAbnormalReturn(LocalDate.of(2026, 7, 22), 412),
        )
        assertEquals(412, joinSueWithReturns(listOf(quarter), returns).single().abnormalReturnBps)
    }

    @Test
    fun a_return_outside_the_match_window_is_dropped() {
        var quarter = SueQuarter(
            fiscalEnd = LocalDate.of(2026, 6, 30),
            reportedOn = LocalDate.of(2026, 7, 22),
            actualEps = 2.93,
            meanEps = 2.65,
            lowEps = 2.50,
            highEps = 2.80,
            sueBps = 10_000,
            revenueSurpriseBps = null,
        )
        var returns = listOf(
            DatedAbnormalReturn(LocalDate.of(2026, 8, 10), 412),
        )
        assertTrue(joinSueWithReturns(listOf(quarter), returns).isEmpty())
    }

    private fun series(n: Int): List<SurpriseObservation> =
        List(n) { index ->
            var sue = (index - n / 2) * 2_000
            SurpriseObservation(
                reportDate = LocalDate.of(2022, 1, 1).plusMonths(index * 3L),
                sueBps = sue,
                abnormalReturnBps = (sue * 300.0 / 10_000.0).toInt(),
            )
        }
}
