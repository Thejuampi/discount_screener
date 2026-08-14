package com.discountscreener.core.plan

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DipSignalEngineTest {
    @Test
    fun missing_tape_is_not_now() {
        var setup = DipSignalEngine.classify(goodInput(), tape = null)
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun shallow_dip_is_not_now() {
        var setup = DipSignalEngine.classify(goodInput(), tape = goodTape(dipAtrUnits = 0.4))
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun null_f_is_not_now() {
        var setup = DipSignalEngine.classify(goodInput().copy(fundamentalsScore = null), goodTape())
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun knife_is_not_now() {
        var tape = goodTape(rsiSlope = -1.0, rsiAccel = -0.5, histSlope = -10.0, histAccel = -4.0)
        var setup = DipSignalEngine.classify(goodInput(), tape)
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun hot_rsi_is_not_now() {
        var setup = DipSignalEngine.classify(goodInput(), goodTape(rsi = 62.0))
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun flipped_macd_is_never_now() {
        var tape = goodTape(histogram = 40.0, macdPhase = MacdPhase.Flipped)
        var setup = DipSignalEngine.classify(goodInput(), tape)
        assertTrue(setup.lane != DipLane.Now)
    }

    @Test
    fun street_between_15_and_20_is_almost() {
        var input = goodInput().copy(streetFairValueCents = 11_600)
        var setup = DipSignalEngine.classify(input, goodTape())
        assertEquals(DipLane.Almost, setup.lane)
    }

    @Test
    fun missing_street_is_out() {
        var setup = DipSignalEngine.classify(goodInput().copy(streetFairValueCents = 0), goodTape())
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun death_cross_stays_in_when_and_holds() {
        var input = goodInput().copy(technicalSignals = listOf("50/200-"))
        var setup = DipSignalEngine.classify(input, goodTape())
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun declining_year_tape_is_a_death_cross() {
        var tape = DipSignalEngine.measureTape(decliningCandles(220))!!
        assertTrue(tape.deathCross)
    }

    @Test
    fun death_cross_from_tape_is_tagged() {
        var setup = DipSignalEngine.classify(goodInput(), goodTape(deathCross = true))
        assertTrue(setup.tags.contains("death_cross"))
    }

    @Test
    fun death_cross_stays_in_the_evidence_list() {
        var setup = DipSignalEngine.classify(goodInput(), goodTape(deathCross = true))
        assertTrue(setup.evidence.any { it.contains("Death cross") })
    }

    @Test
    fun rsi_slope_matches_chart_analysis() {
        var candles = risingCandles(80)
        var tape = DipSignalEngine.measureTape(candles)!!
        var expected = ChartAnalysis.rsiAnalysis(candles)!!.latestSlope
        assertEquals(expected, tape.rsiSlope)
    }

    @Test
    fun negative_chart_rsi_slope_is_not_now() {
        var setup = DipSignalEngine.classify(goodInput(), goodTape(rsiSlope = -0.26, rsiAccel = 0.30))
        assertTrue(setup.lane != DipLane.Now)
    }

    @Test
    fun complete_and_is_now() {
        var setup = DipSignalEngine.classify(goodInput(), goodTape())
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun rank_puts_imminent_before_turning() {
        var turning = DipSignalEngine.classify(goodInput().copy(symbol = "TURN"), goodTape(macdPhase = MacdPhase.Turning))
        var imminent = DipSignalEngine.classify(goodInput().copy(symbol = "NEAR"), goodTape(macdPhase = MacdPhase.Imminent))
        var board = DipSignalEngine.rank(listOf(turning, imminent))
        assertEquals("NEAR", board.now.first().symbol)
    }

    @Test
    fun rank_does_not_use_composite_order() {
        var weakF = DipSignalEngine.classify(goodInput().copy(symbol = "WEAK", fundamentalsScore = 5), goodTape())
        var strongF = DipSignalEngine.classify(goodInput().copy(symbol = "FIRM", fundamentalsScore = 40), goodTape())
        var board = DipSignalEngine.rank(listOf(weakF, strongF))
        assertEquals("FIRM", board.now.first().symbol)
    }

    @Test
    fun now_cap_is_six() {
        var setups = (1..8).map { index ->
            DipSignalEngine.classify(goodInput().copy(symbol = "N$index"), goodTape())
        }
        var board = DipSignalEngine.rank(setups)
        assertEquals(6, board.now.size)
    }

    @Test
    fun residual_income_is_not_labeled_dcf() {
        var dcf = sampleDcf(ValuationModel.ResidualIncomeEquity, BusinessClass.FinancialServices)
        var setup = DipSignalEngine.classify(goodInput().copy(dcf = dcf), goodTape())
        assertEquals("Residual income", setup.modelLabel)
    }

    @Test
    fun tension_is_kept() {
        var dcf = sampleDcf(ValuationModel.FcffWacc, BusinessClass.OperatingNonFinancial, base = 18_000)
        var setup = DipSignalEngine.classify(goodInput().copy(dcf = dcf), goodTape())
        assertEquals(AnchorRelation.Tension, setup.valuationRelation)
    }

    @Test
    fun empty_now_stays_empty() {
        var almost = DipSignalEngine.classify(goodInput().copy(streetFairValueCents = 11_600), goodTape())
        var board = DipSignalEngine.rank(listOf(almost))
        assertTrue(board.now.isEmpty())
    }

    @Test
    fun measure_tape_fails_closed_on_short_series() {
        var tape = DipSignalEngine.measureTape(flatCandles(10))
        assertNull(tape)
    }

    @Test
    fun macd_histogram_matches_chart_analysis() {
        var candles = flatCandles(80)
        var tape = DipSignalEngine.measureTape(candles)!!
        var expected = ChartAnalysis.macdSeries(candles.map { it.closeCents.toDouble() }).histogram.last()
        assertEquals(expected, tape.histogram)
    }

    @Test
    fun street_copy_names_the_12_month_target() {
        var line = DipCopy.streetLine(2_400)
        assertTrue(line.contains("12-month target"))
    }

    @Test
    fun lookback_slope_is_last_minus_n() {
        var slope = DipSignalEngine.lookbackDiff(listOf(1.0, 2.0, 3.0, 7.0), n = 3)
        assertEquals(6.0, slope)
    }
}

private fun goodInput(): DipRowInput = DipRowInput(
    symbol = "DIP",
    fundamentalsScore = 20,
    marketPriceCents = 10_000,
    streetFairValueCents = 13_000,
    analystCoverageCount = 8,
)

private fun goodTape(
    dipAtrUnits: Double = 1.6,
    rsi: Double = 34.0,
    rsiSlope: Double = 0.8,
    rsiAccel: Double = 0.2,
    histogram: Double = -15.0,
    histSlope: Double = 8.0,
    histAccel: Double = 3.0,
    macdPhase: MacdPhase = MacdPhase.Imminent,
    deathCross: Boolean = false,
): DipTape = DipTape(
    atrCents = 200,
    high20dCents = 13_200,
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

private fun decliningCandles(count: Int): List<HistoricalCandle> =
    (1..count).map { index ->
        HistoricalCandle(
            epochSeconds = 1_700_000_000L + index * 86_400L,
            openCents = 20_000L - index * 40L,
            highCents = 20_080L - index * 40L,
            lowCents = 19_920L - index * 40L,
            closeCents = 19_960L - index * 40L,
            volume = 1_000_000,
        )
    }

private fun risingCandles(count: Int): List<HistoricalCandle> =
    (1..count).map { index ->
        HistoricalCandle(
            epochSeconds = 1_700_000_000L + index * 86_400L,
            openCents = 10_000L + index * 20L,
            highCents = 10_080L + index * 20L,
            lowCents = 9_920L + index * 20L,
            closeCents = 10_040L + index * 20L,
            volume = 1_000_000,
        )
    }

private fun sampleDcf(
    model: ValuationModel,
    businessClass: BusinessClass,
    base: Long = 12_000,
): DcfAnalysis = DcfAnalysis(
    bearIntrinsicValueCents = base - 1_000,
    baseIntrinsicValueCents = base,
    bullIntrinsicValueCents = base + 1_000,
    waccBps = 900,
    baseGrowthBps = 300,
    netDebtDollars = 0,
    businessClass = businessClass,
    model = model,
)

private fun flatCandles(count: Int): List<HistoricalCandle> =
    (1..count).map { index ->
        HistoricalCandle(
            epochSeconds = 1_700_000_000L + index * 86_400L,
            openCents = 10_000,
            highCents = 10_080,
            lowCents = 9_920,
            closeCents = 10_000,
            volume = 1_000_000,
        )
    }
