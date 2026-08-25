package com.discountscreener.core.plan

import com.discountscreener.core.engine.ValuationPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossSignalEngineTest {
    @Test
    fun at_cross_is_zero_bars() {
        assertEquals(0, CrossSignalEngine.barsSinceGoldenCross(listOf(-2.0, -0.5, 0.4)))
    }

    @Test
    fun three_positive_bars_after_cross_is_three() {
        assertEquals(3, CrossSignalEngine.barsSinceGoldenCross(listOf(-1.0, 0.2, 0.5, 0.8, 1.1)))
    }

    @Test
    fun still_negative_histogram_is_null() {
        assertEquals(null, CrossSignalEngine.barsSinceGoldenCross(listOf(-2.0, -0.4, 0.0)))
    }

    @Test
    fun entire_positive_series_is_the_length() {
        assertEquals(3, CrossSignalEngine.barsSinceGoldenCross(listOf(0.2, 0.4, 0.6)))
    }

    @Test
    fun at_cross_is_now() {
        var setup = CrossSignalEngine.classify(goodCrossInput(), goodCrossTape(), barsSinceCross = 0)
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun three_bars_is_now() {
        var setup = CrossSignalEngine.classify(goodCrossInput(), goodCrossTape(), barsSinceCross = 3)
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun four_bars_is_out() {
        var setup = CrossSignalEngine.classify(goodCrossInput(), goodCrossTape(), barsSinceCross = 4)
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun three_bars_max_two_is_out() {
        var patched = ValuationPolicy.current.withCrossFlippedBars(2)
        ValuationPolicy.use(patched) {
            var setup = CrossSignalEngine.classify(goodCrossInput(), goodCrossTape(), barsSinceCross = 3)
            assertEquals(DipLane.Out, setup.lane)
        }
    }

    @Test
    fun three_bars_max_four_is_now() {
        var patched = ValuationPolicy.current.withCrossFlippedBars(4)
        ValuationPolicy.use(patched) {
            var setup = CrossSignalEngine.classify(goodCrossInput(), goodCrossTape(), barsSinceCross = 3)
            assertEquals(DipLane.Now, setup.lane)
        }
    }

    @Test
    fun complete_and_is_now() {
        var setup = CrossSignalEngine.classify(goodCrossInput(), goodCrossTape(), barsSinceCross = 0)
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun street_almost_is_almost() {
        var input = goodCrossInput().copy(streetFairValueCents = 11_600)
        var setup = CrossSignalEngine.classify(input, goodCrossTape(), barsSinceCross = 0)
        assertEquals(DipLane.Almost, setup.lane)
    }

    @Test
    fun street_low_is_out() {
        var input = goodCrossInput().copy(streetFairValueCents = 11_400)
        var setup = CrossSignalEngine.classify(input, goodCrossTape(), barsSinceCross = 0)
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun missing_f_is_out() {
        var setup = CrossSignalEngine.classify(
            goodCrossInput().copy(fundamentalsScore = null),
            goodCrossTape(),
            barsSinceCross = 0,
        )
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun fading_hist_is_out() {
        var tape = goodCrossTape(histSlope = -4.0, histAccel = -1.0)
        var setup = CrossSignalEngine.classify(goodCrossInput(), tape, barsSinceCross = 0)
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun rsi_hot_is_out() {
        var setup = CrossSignalEngine.classify(goodCrossInput(), goodCrossTape(rsi = 62.0), barsSinceCross = 0)
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun still_negative_is_out() {
        var tape = goodCrossTape(histogram = -15.0, macdPhase = MacdPhase.Turning)
        var setup = CrossSignalEngine.classify(goodCrossInput(), tape, barsSinceCross = null)
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun shallow_dip_can_still_be_now() {
        var setup = CrossSignalEngine.classify(
            goodCrossInput(),
            goodCrossTape(dipAtrUnits = 0.2),
            barsSinceCross = 0,
        )
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun empty_now_stays_empty() {
        var almost = CrossSignalEngine.classify(
            goodCrossInput().copy(streetFairValueCents = 11_600),
            goodCrossTape(),
            barsSinceCross = 0,
        )
        var board = CrossSignalEngine.rank(listOf(almost))
        assertTrue(board.now.isEmpty())
    }

    @Test
    fun rank_puts_zero_bars_ahead_of_three() {
        var late = CrossSignalEngine.classify(goodCrossInput().copy(symbol = "LATE"), goodCrossTape(), 3)
        var now = CrossSignalEngine.classify(goodCrossInput().copy(symbol = "NOW"), goodCrossTape(), 0)
        var board = CrossSignalEngine.rank(listOf(late, now))
        assertEquals("NOW", board.now.first().symbol)
    }

    @Test
    fun evaluate_without_candles_is_out() {
        var setup = CrossSignalEngine.evaluate(goodCrossInput())
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun macd_copy_names_the_cross_bar() {
        assertEquals("MACD is at the golden cross.", CrossCopy.macdLine(0, MacdPhase.Flipped))
    }
}

private fun goodCrossInput(): DipRowInput = DipRowInput(
    symbol = "X",
    fundamentalsScore = 20,
    marketPriceCents = 10_000,
    streetFairValueCents = 13_000,
    analystCoverageCount = 8,
)

private fun goodCrossTape(
    dipAtrUnits: Double = 0.4,
    rsi: Double = 40.0,
    rsiSlope: Double = 0.4,
    rsiAccel: Double = 0.1,
    histogram: Double = 12.0,
    histSlope: Double = 8.0,
    histAccel: Double = 3.0,
    macdPhase: MacdPhase = MacdPhase.Flipped,
    deathCross: Boolean = false,
): DipTape = DipTape(
    atrCents = 200,
    high20dCents = 10_800,
    lastCloseCents = 10_000,
    dipAtrUnits = dipAtrUnits,
    rsi = rsi,
    rsiSlope = rsiSlope,
    rsiAccel = rsiAccel,
    histogram = histogram,
    histSlope = histSlope,
    histAccel = histAccel,
    macdPhase = macdPhase,
    deathCross = deathCross,
)
