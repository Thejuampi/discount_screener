package com.discountscreener.core.backtest

import com.discountscreener.core.model.HistoricalCandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Where look-ahead bias would enter the retrospective, and the one property worth pinning here.
 *
 * [ForwardReturnTest] checks that the *return* side does not peek. This checks the *score* side: a
 * score dated at a bar must be the score a reader would have had at that bar, whatever the series
 * does afterwards. Nothing else in the suite would notice the difference — a replay that scores
 * every date from the full series produces a complete, plausible, entirely worthless result.
 */
class PointInTimeTechnicalsTest {

    /**
     * The bars after the scoring date are violent enough to flip the answer, and the answer does not
     * move. Two hundred and ten bars of steady decline, then fifty bars that recover the whole fall:
     * the technical bucket reads the first as weak and the second as strong, so a scorer that read
     * the extended series would return a different number for the same date.
     */
    @Test
    fun a_later_bar_does_not_change_the_score_already_dated_before_it() {
        var asOf = declining().last().epochSeconds

        assertEquals(
            PointInTimeTechnicals.scoresOverHistory(SYMBOL, declining()).last(),
            PointInTimeTechnicals.scoresOverHistory(SYMBOL, declining() + recovering())
                .first { it.scoredAtEpochSeconds == asOf },
        )
    }

    /**
     * The falsification check for the test above, and it is not optional.
     *
     * If the decline and the recovery scored the same, [a_later_bar_does_not_change_the_score_already_dated_before_it]
     * would pass on a scorer that reads the whole series every time, and would be measuring nothing.
     * This asserts the fixture can tell the two apart.
     */
    @Test
    fun the_fixture_scores_the_recovery_differently_from_the_decline() {
        assertNotEquals(
            PointInTimeTechnicals.scoresOverHistory(SYMBOL, declining()).last().score,
            PointInTimeTechnicals.scoresOverHistory(SYMBOL, declining() + recovering()).last().score,
        )
    }

    /** A series shorter than the warmup has no bar old enough to score, and reports none. */
    @Test
    fun a_series_shorter_than_the_warmup_yields_no_scores() {
        assertEquals(
            emptyList(),
            PointInTimeTechnicals.scoresOverHistory(SYMBOL, declining().take(WARMUP_BARS)),
        )
    }

    /** 200.00 down to 95.50, one step a bar. Long enough that EMA200 is an average of 200 bars. */
    private fun declining() = List(WARMUP_BARS + 10) { bar ->
        candle(bar, START_CENTS - bar * DECLINE_STEP_CENTS)
    }

    /** The whole fall given back in a quarter of the time, starting where the decline stopped. */
    private fun recovering(): List<HistoricalCandle> {
        var from = declining().last()
        return List(RECOVERY_BARS) { step ->
            candle(WARMUP_BARS + 10 + step, from.closeCents + (step + 1) * RECOVERY_STEP_CENTS)
        }
    }

    private fun candle(bar: Int, closeCents: Long) = HistoricalCandle(
        epochSeconds = FIRST_BAR + bar * DAY,
        openCents = closeCents,
        highCents = closeCents + BAR_RANGE_CENTS,
        lowCents = closeCents - BAR_RANGE_CENTS,
        closeCents = closeCents,
        volume = 1_000L,
    )

    private companion object {
        const val SYMBOL = "TREND"
        const val DAY = 86_400L
        const val FIRST_BAR = 1_600_000_000L
        const val WARMUP_BARS = PointInTimeTechnicals.MIN_WARMUP_BARS
        const val RECOVERY_BARS = 50
        const val START_CENTS = 20_000L
        const val DECLINE_STEP_CENTS = 50L
        const val RECOVERY_STEP_CENTS = 220L
        const val BAR_RANGE_CENTS = 40L
    }
}
