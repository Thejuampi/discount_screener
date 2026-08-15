package com.discountscreener.core.plan

import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeftoverSignalEngineTest {
    @Test
    fun leftover_above_five_percent_is_out() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(streetFairValueCents = 10_600),
            leftoverTape(),
        )
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun leftover_at_five_percent_without_fade_is_review() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(streetFairValueCents = 10_500),
            leftoverTape(rsi = 40.0, rsiSlope = 0.4, histogram = -10.0, histSlope = 4.0),
        )
        assertEquals(DipLane.Almost, setup.lane)
    }

    @Test
    fun leftover_at_five_percent_without_fade_is_never_primary() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(streetFairValueCents = 10_500),
            leftoverTape(rsi = 40.0, rsiSlope = 0.4, histogram = -10.0, histSlope = 4.0),
        )
        assertTrue(setup.lane != DipLane.Now)
    }

    @Test
    fun leftover_just_over_five_percent_is_out() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(streetFairValueCents = 10_501),
            leftoverTape(),
        )
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun leftover_and_fade_and_stretch_is_primary() {
        var setup = LeftoverSignalEngine.classify(leftoverInput(), leftoverTape())
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun last_above_target_with_fade_is_primary() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(streetFairValueCents = 9_000),
            leftoverTape(),
        )
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun missing_street_is_out() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(streetFairValueCents = 0),
            leftoverTape(),
        )
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun thin_street_is_out() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(analystCoverageCount = 0),
            leftoverTape(),
        )
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun missing_tape_is_out() {
        var setup = LeftoverSignalEngine.classify(leftoverInput(), tape = null)
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun dumped_more_than_two_atr_is_out() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(),
            leftoverTape(dipAtrUnits = 2.1),
        )
        assertEquals(DipLane.Out, setup.lane)
    }

    @Test
    fun fade_without_near_high_is_review() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(),
            leftoverTape(dipAtrUnits = 1.4),
        )
        assertEquals(DipLane.Almost, setup.lane)
    }

    @Test
    fun flat_rsi_slope_is_not_rsi_fade() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(),
            leftoverTape(rsiSlope = 0.0, histogram = -8.0, histSlope = 3.0),
        )
        assertEquals(DipLane.Almost, setup.lane)
    }

    @Test
    fun rising_rsi_is_not_rsi_fade() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(),
            leftoverTape(rsiSlope = 0.4, histogram = -8.0, histSlope = 3.0),
        )
        assertEquals(DipLane.Almost, setup.lane)
    }

    @Test
    fun missing_f_can_still_be_primary() {
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(fundamentalsScore = null),
            leftoverTape(),
        )
        assertEquals(DipLane.Now, setup.lane)
    }

    @Test
    fun residual_income_is_a_tag() {
        var dcf = leftoverDcf(ValuationModel.ResidualIncomeEquity, BusinessClass.FinancialServices)
        var setup = LeftoverSignalEngine.classify(leftoverInput(dcf = dcf), leftoverTape())
        assertEquals("Residual income", setup.modelLabel)
    }

    @Test
    fun tension_is_kept() {
        var dcf = leftoverDcf(ValuationModel.FcffWacc, BusinessClass.OperatingNonFinancial, base = 18_000)
        var setup = LeftoverSignalEngine.classify(
            leftoverInput(streetFairValueCents = 13_000, dcf = dcf),
            leftoverTape(),
        )
        assertEquals(AnchorRelation.Tension, setup.valuationRelation)
    }

    @Test
    fun rank_orders_tighter_leftover_first() {
        var wide = LeftoverSignalEngine.classify(
            leftoverInput(symbol = "WIDE", streetFairValueCents = 10_400),
            leftoverTape(),
        )
        var tight = LeftoverSignalEngine.classify(
            leftoverInput(symbol = "TITE", streetFairValueCents = 10_100),
            leftoverTape(),
        )
        var board = LeftoverSignalEngine.rank(listOf(wide, tight), universeName = "qa")
        assertEquals("TITE", board.now.first().symbol)
    }

    @Test
    fun rank_uses_stretch_after_leftover() {
        var far = LeftoverSignalEngine.classify(
            leftoverInput(symbol = "FAR"),
            leftoverTape(dipAtrUnits = 0.9),
        )
        var near = LeftoverSignalEngine.classify(
            leftoverInput(symbol = "NEAR"),
            leftoverTape(dipAtrUnits = 0.2),
        )
        var board = LeftoverSignalEngine.rank(listOf(far, near), universeName = "qa")
        assertEquals("NEAR", board.now.first().symbol)
    }

    @Test
    fun rank_uses_horizon_after_stretch() {
        var drag = LeftoverSignalEngine.classify(
            leftoverInput(symbol = "DRAG"),
            leftoverTape(),
            MacdTape(12.0, 4.0, 1.0, MacdPhase.Flipped),
        )
        var align = LeftoverSignalEngine.classify(
            leftoverInput(symbol = "BOTH"),
            leftoverTape(),
            MacdTape(12.0, -5.0, -1.0, MacdPhase.Flipped),
        )
        var board = LeftoverSignalEngine.rank(listOf(drag, align), universeName = "qa")
        assertEquals("BOTH", board.now.first().symbol)
    }

    @Test
    fun rank_does_not_prefer_two_fade_flags() {
        var both = LeftoverSignalEngine.classify(
            leftoverInput(symbol = "ZED"),
            leftoverTape(),
        )
        var rsiOnly = LeftoverSignalEngine.classify(
            leftoverInput(symbol = "AAA"),
            leftoverTape(histogram = -12.0, histSlope = 4.0),
        )
        var board = LeftoverSignalEngine.rank(listOf(both, rsiOnly), universeName = "qa")
        assertEquals("AAA", board.now.first().symbol)
    }

    @Test
    fun now_cap_is_one_hundred_twenty() {
        var setups = (1..121).map { index ->
            LeftoverSignalEngine.classify(leftoverInput(symbol = "N$index"), leftoverTape())
        }
        var board = LeftoverSignalEngine.rank(setups, universeName = "qa")
        assertEquals(120, board.now.size)
    }

    @Test
    fun review_cap_is_eighty() {
        var setups = (1..81).map { index ->
            LeftoverSignalEngine.classify(
                leftoverInput(symbol = "R$index"),
                leftoverTape(rsi = 40.0, rsiSlope = 0.3, histogram = -10.0, histSlope = 2.0),
            )
        }
        var board = LeftoverSignalEngine.rank(setups, universeName = "qa")
        assertEquals(80, board.later.size)
    }

    @Test
    fun empty_primary_stays_empty() {
        var review = LeftoverSignalEngine.classify(
            leftoverInput(),
            leftoverTape(rsi = 40.0, rsiSlope = 0.3, histogram = -10.0, histSlope = 2.0),
        )
        var board = LeftoverSignalEngine.rank(listOf(review), universeName = "qa")
        assertTrue(board.now.isEmpty())
    }

    @Test
    fun universe_name_is_the_profile() {
        var board = LeftoverSignalEngine.rank(
            listOf(LeftoverSignalEngine.classify(leftoverInput(), leftoverTape())),
            universeName = "sp500",
        )
        assertEquals("sp500", board.universeName)
    }

    @Test
    fun leftover_copy_names_fading_horizons() {
        var line = LeftoverCopy.horizonLine(MacdHorizonScore.ALIGN)
        assertEquals("1Y and 5Y MACD are fading.", line)
    }

    @Test
    fun leftover_copy_names_the_12_month_target() {
        var line = LeftoverCopy.streetLine(400)
        assertTrue(line.contains("12-month target"))
    }
}

private fun leftoverInput(
    symbol: String = "LEFT",
    fundamentalsScore: Int? = 20,
    streetFairValueCents: Long = 10_200,
    analystCoverageCount: Int? = 8,
    dcf: DcfAnalysis? = null,
): DipRowInput = DipRowInput(
    symbol = symbol,
    fundamentalsScore = fundamentalsScore,
    marketPriceCents = 10_000,
    streetFairValueCents = streetFairValueCents,
    analystCoverageCount = analystCoverageCount,
    dcf = dcf,
)

private fun leftoverTape(
    dipAtrUnits: Double = 0.4,
    rsi: Double = 68.0,
    rsiSlope: Double = -0.6,
    rsiAccel: Double = -0.2,
    histogram: Double = 12.0,
    histSlope: Double = -5.0,
    histAccel: Double = -1.0,
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

private fun leftoverDcf(
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
