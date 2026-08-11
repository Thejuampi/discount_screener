package com.discountscreener.core.backtest

import com.discountscreener.core.model.HistoricalCandle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The instrument, checked in both directions before it is allowed to report anything.
 *
 * An evaluator exercised only on a series built to succeed cannot be trusted when it says a model
 * has no forward signal — and that is the answer most likely to come back. So the first two tests
 * are a matched pair on the *same* returns: once arranged to follow the score, once arranged to be
 * independent of it. Both spreads are exact, not bounded, because both fixtures are constructed
 * rather than sampled.
 */
class ForwardReturnTest {

    /**
     * Signal, and the evaluator finds it. Score `i` precedes a forward return of `i × 20` bps, so
     * the bottom tenth centres on 90 bps, the top tenth on 1890, and the spread is 1800 by
     * construction.
     */
    @Test
    fun a_score_that_precedes_a_rise_shows_a_positive_top_minus_bottom_spread() {
        var report = evaluate(returnBpsFor = { rank -> rank * 20 })

        assertEquals(1_800, report.topMinusBottomBps)
    }

    /**
     * No signal, and the evaluator says so — exactly, not approximately.
     *
     * The returns are the same ten values as above; only their arrangement changes. Each tenth of
     * the score range receives one of each, so every tenth holds an identical multiset and every
     * centre is the same number. A spread of anything other than zero here means the decile
     * assignment is reading the return, which is the defect this test exists to catch.
     */
    @Test
    fun scores_arranged_independently_of_the_returns_show_no_spread() {
        var report = evaluate(returnBpsFor = { rank -> (rank % 10) * 20 })

        assertEquals(0, report.topMinusBottomBps)
    }

    /**
     * The horizon is a parameter, and this is what makes it one.
     *
     * Every other test here holds for a single bar, so a `forwardReturnByDecile` that ignored
     * [horizonBars] and always exited at the next bar would leave all of them green. Measured, not
     * supposed: that mutation survived until this test existed. The fixture's second exit bar is
     * twice the move of the first, so the spread doubles to 3600 and nothing else changes.
     */
    @Test
    fun a_longer_horizon_reaches_a_later_bar() {
        var report = evaluate(returnBpsFor = { rank -> rank * 20 }, horizonBars = 2)

        assertEquals(3_600, report.topMinusBottomBps)
    }

    /**
     * Entry is the bar *after* the scoring date, and this is the test that says so.
     *
     * A score read off day D's close cannot also be filled at day D's close — that trade needs the
     * closing price twice, once to decide and once to buy, and every backtest that allows it gets a
     * free look at the move it is measuring. Here the scoring bar closes at half the entry bar, so
     * the free look is worth about a hundred percent: the bottom tenth centres on 90 bps when the
     * entry is the following bar, and on 10180 bps when it is not.
     */
    @Test
    fun the_bar_at_the_scoring_date_is_not_the_entry() {
        var report = evaluate(returnBpsFor = { rank -> rank * 20 }, scoringBarCents = ENTRY_CENTS / 2)

        assertEquals(90, report.deciles.first().centreForwardReturnBps)
    }

    /**
     * A backtest that quietly discards what it cannot price reads exactly like one that priced
     * everything. Two symbols here have no bar left after the horizon, and the report names them.
     */
    @Test
    fun an_observation_with_no_bar_left_after_the_horizon_is_dropped_and_counted() {
        var report = forwardReturnByDecile(
            scores = listOf(DatedScore("SHORT", DAY, 10), DatedScore("ALSO_SHORT", DAY, 20)),
            candlesBySymbol = mapOf(
                "SHORT" to listOf(candle(DAY, 10_000L), candle(2 * DAY, 11_000L)),
                "ALSO_SHORT" to listOf(candle(DAY, 10_000L), candle(2 * DAY, 9_000L)),
            ),
            horizonBars = 5,
        )

        assertEquals(2, report.droppedNoExitBar)
    }

    /**
     * One hundred observations, one symbol each, one score each, scores 0..99. Every symbol gets a
     * scoring bar, an entry bar and two exit bars, so nothing is dropped and the deciles come out
     * ten apiece — the arrangement of returns is the only thing the two headline tests vary. The
     * second exit bar carries twice the first's move, which is what gives the horizon something to
     * be right or wrong about.
     */
    private fun evaluate(
        returnBpsFor: (Int) -> Int,
        scoringBarCents: Long = ENTRY_CENTS,
        horizonBars: Int = 1,
    ): ForwardReturnReport {
        var symbols = List(OBSERVATIONS) { rank -> "S%03d".format(rank) }
        return forwardReturnByDecile(
            scores = symbols.mapIndexed { rank, symbol -> DatedScore(symbol, DAY, rank) },
            candlesBySymbol = symbols.mapIndexed { rank, symbol ->
                var moveCents = ENTRY_CENTS * returnBpsFor(rank) / 10_000L
                symbol to listOf(
                    candle(DAY, scoringBarCents),
                    candle(2 * DAY, ENTRY_CENTS),
                    candle(3 * DAY, ENTRY_CENTS + moveCents),
                    candle(4 * DAY, ENTRY_CENTS + 2 * moveCents),
                )
            }.toMap(),
            horizonBars = horizonBars,
        )
    }

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
        const val OBSERVATIONS = 100

        /** Ten thousand cents, so a return in basis points lands on a whole cent. */
        const val ENTRY_CENTS = 10_000L
    }
}
