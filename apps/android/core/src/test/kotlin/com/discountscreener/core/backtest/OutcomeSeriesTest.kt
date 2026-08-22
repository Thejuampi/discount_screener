package com.discountscreener.core.backtest

import com.discountscreener.core.model.HistoricalCandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The journal-to-outcome layer over [ForwardReturn]: many named score series, one report.
 *
 * It adds no math. Every honesty rule — no look-ahead entry, refusal under ten observations, named
 * drops — is inherited from `forwardReturnByDecile` by construction; this file only decides what a
 * set of series means when some of them cannot answer.
 */
class OutcomeSeriesTest {

    /** A real spread passes through untouched: same fixture as ForwardReturn's headline test. */
    @Test
    fun a_series_with_signal_reports_its_spread() {
        var outcome = evaluate(returnBpsFor = { rank -> rank * 20 }).single()

        assertEquals("V4/composite", outcome.seriesName)
        assertEquals(1_800, outcome.report.topMinusBottomBps)
        assertEquals(false, outcome.insufficient)
    }

    /** Below ten held observations no tenth can be cut, and the report says so instead of guessing. */
    @Test
    fun a_series_too_thin_to_cut_is_insufficient() {
        var outcomes = evaluate(observations = 9, returnBpsFor = { rank -> rank * 20 })

        assertTrue(outcomes.all { it.insufficient }, "nine observations must read insufficient")
    }

    /** Drops are named per series, so one thin symbol cannot vanish into another's totals. */
    @Test
    fun dropped_observations_are_counted_inside_their_own_series() {
        var outcomes = evaluate(
            observations = 12,
            returnBpsFor = { rank -> rank * 20 },
            shortSymbols = setOf("S000", "S001"),
        )

        assertTrue(outcomes.all { it.report.droppedNoExitBar == 2 })
    }

    /** The horizon list is the caller's: two horizons produce two verdicts for the same series. */
    @Test
    fun each_requested_horizon_produces_its_own_verdict() {
        var outcomes = evaluate(
            returnBpsFor = { rank -> rank * 20 },
            horizons = listOf(1, 2),
        )

        assertEquals(listOf(1, 2), outcomes.map { it.horizonBars }.distinct().sorted())
        assertEquals(3_600, outcomes.single { it.horizonBars == 2 }.report.topMinusBottomBps)
    }

    /** Two models journalled on the same days stay two series, never merged into one pool. */
    @Test
    fun separate_series_are_evaluated_separately() {
        // Identical score distributions, opposite worlds: an A-name rises with its rank and a
        // B-name falls with the same rank. Only the candle sets differ.
        var ranked = (0 until 100).map { rank -> Triple("A%03d".format(rank), "B%03d".format(rank), rank) }
        var candles = ranked.flatMap { (a, b, rank) ->
            listOf(
                a to bars(ENTRY_CENTS + 40L * rank),
                b to bars(ENTRY_CENTS - 40L * rank),
            )
        }.toMap()
        var report = outcomeReport(
            series = listOf(
                ScoreSeries(
                    "V4/composite",
                    ranked.map { (a, _, rank) -> DatedScore(a, DAY, rank) },
                ),
                ScoreSeries(
                    "V3/composite",
                    ranked.map { (_, b, rank) -> DatedScore(b, DAY, rank) },
                ),
            ),
            candlesBySymbol = candles,
            horizons = listOf(1),
        )

        var v4 = report.outcomes.single { it.seriesName.startsWith("V4") }
        var v3 = report.outcomes.single { it.seriesName.startsWith("V3") }
        assertTrue(v4.report.topMinusBottomBps!! > 0)
        assertTrue(v3.report.topMinusBottomBps!! < 0)
    }

    /** Scoring bar, entry bar, one exit bar. */
    private fun bars(exitCents: Long) = listOf(
        candle(DAY, ENTRY_CENTS),
        candle(2 * DAY, ENTRY_CENTS),
        candle(3 * DAY, exitCents),
    )

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /**
     * One series named like the builder names them. Every symbol gets a scoring bar, an entry bar
     * and [horizonBars] exit bars, so nothing is dropped unless [shortSymbols] says otherwise.
     */
    private fun evaluate(
        observations: Int = 100,
        returnBpsFor: (Int) -> Int,
        horizons: List<Int> = listOf(1),
        shortSymbols: Set<String> = emptySet(),
    ): List<SeriesOutcome> = outcomeReport(
        series = listOf(ScoreSeries("V4/composite", scores(observations, returnBpsFor))),
        candlesBySymbol = candlesFor(observations, returnBpsFor, shortSymbols, maxExitBars = horizons.max()),
        horizons = horizons,
    ).outcomes

    private fun scores(observations: Int, returnBpsFor: (Int) -> Int): List<DatedScore> =
        List(observations) { rank -> DatedScore("S%03d".format(rank), DAY, rank) }

    private fun candlesFor(
        observations: Int,
        returnBpsFor: (Int) -> Int,
        shortSymbols: Set<String> = emptySet(),
        maxExitBars: Int = 2,
    ): Map<String, List<HistoricalCandle>> = List(observations) { rank ->
        var symbol = "S%03d".format(rank)
        var moveCents = ENTRY_CENTS * returnBpsFor(rank) / 10_000L
        var bars = mutableListOf(
            candle(DAY, ENTRY_CENTS),
            candle(2 * DAY, ENTRY_CENTS),
        )
        if (symbol !in shortSymbols) {
            for (exit in 1..maxExitBars) {
                bars += candle((2 + exit) * DAY, ENTRY_CENTS + exit * moveCents)
            }
        }
        symbol to bars
    }.toMap()

    private fun candle(epochSeconds: Long, closeCents: Long) = HistoricalCandle(
        epochSeconds = epochSeconds,
        openCents = closeCents,
        highCents = closeCents,
        lowCents = closeCents,
        closeCents = closeCents,
        volume = 1_000L,
    )

    private companion object {
        const val DAY = 86_400L

        /** Ten thousand cents, so a return in basis points lands on a whole cent. */
        const val ENTRY_CENTS = 10_000L
    }
}
