package com.discountscreener.core.plan

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricePathEngineTest {
    @Test
    fun zero_price_returns_no_zone() {
        var est = PricePathEngine.estimate(PricePathInput(marketPriceCents = 0))
        assertNull(est.zone)
    }

    @Test
    fun extended_long_zone_sits_below_price() {
        var est = PricePathEngine.estimate(extendedLongInput())
        assertTrue(est.zone!!.highCents < 11_000L)
    }

    @Test
    fun zone_width_stays_inside_atr_bounds() {
        var atr = 200L
        var est = PricePathEngine.estimate(
            PricePathInput(
                marketPriceCents = 10_000,
                streetFairValueCents = 9_000,
                dcfValueCents = 8_000,
                analystLowCents = 7_000,
                gapBps = -2_000,
                daily = dailyAt(10_000, atr, 55.0, 9_800),
                candles = seriesMeanRevert(),
            ),
        )
        var width = est.zone!!.highCents - est.zone!!.lowCents
        var maxW = roundAway(atr.toDouble() * 1.50) + 2
        assertTrue(width <= maxW)
    }

    @Test
    fun compact_path_caps_risk_codes_at_three() {
        var compact = PricePathEngine.compact(PricePathEngine.estimate(noisyLongInput()))
        assertTrue(compact.riskCodes.size <= MAX_MOTIVES)
    }

    @Test
    fun in_zone_path_does_not_print_a_tiny_p20() {
        var est = PricePathEngine.estimate(
            PricePathInput(
                marketPriceCents = 10_000,
                streetFairValueCents = 10_050,
                dcfValueCents = 9_950,
                analystLowCents = 9_900,
                gapBps = -200,
                daily = dailyAt(10_000, 150, 45.0, 10_100),
                candles = seriesMeanRevert(),
                forecastScore = 25,
                technicalScore = 10,
            ),
        )
        assertTrue((est.timing.pTouch20d ?: 100) >= 40)
    }

    @Test
    fun financials_fcff_is_not_an_eligible_dcf_anchor() {
        var analysis = DcfAnalysis(
            bearIntrinsicValueCents = 10_000,
            baseIntrinsicValueCents = 12_000,
            bullIntrinsicValueCents = 14_000,
            waccBps = 900,
            baseGrowthBps = 300,
            netDebtDollars = 0,
            businessClass = BusinessClass.FinancialServices,
            model = ValuationModel.FcffWacc,
        )
        assertNull(eligibleDcfAnchorCents(analysis))
    }

    @Test
    fun residual_income_is_an_eligible_dcf_anchor() {
        var analysis = DcfAnalysis(
            bearIntrinsicValueCents = 10_000,
            baseIntrinsicValueCents = 12_000,
            bullIntrinsicValueCents = 14_000,
            waccBps = 900,
            baseGrowthBps = 300,
            netDebtDollars = 0,
            businessClass = BusinessClass.FinancialServices,
            model = ValuationModel.ResidualIncomeEquity,
        )
        assertEquals(12_000L, eligibleDcfAnchorCents(analysis))
    }

    @Test
    fun unclassified_model_value_is_not_an_anchor() {
        var analysis = DcfAnalysis(
            bearIntrinsicValueCents = 10_000,
            baseIntrinsicValueCents = 12_000,
            bullIntrinsicValueCents = 14_000,
            waccBps = 900,
            baseGrowthBps = 300,
            netDebtDollars = 0,
            businessClass = BusinessClass.Unclassified,
            model = ValuationModel.FcffWacc,
        )
        assertNull(eligibleDcfAnchorCents(analysis))
    }

    @Test
    fun atr_only_timing_does_not_publish_p20() {
        assertNull(publishableP20(TimingMethod.AtrDistance, 61))
    }

    @Test
    fun hybrid_timing_publishes_p20() {
        assertEquals(76, publishableP20(TimingMethod.Hybrid, 76))
    }
}

private fun seriesMeanRevert(): List<HistoricalCandle> {
    var px = 10_000L
    return (0 until 120).map { i ->
        var delta = when {
            i % 7 == 0 -> -80
            i % 5 == 0 -> 60
            else -> ((i * 17) % 21) - 10
        }
        px = (px + delta).coerceIn(8_000L, 12_000L)
        candle(px, i.toLong())
    }
}

private fun dailyAt(close: Long, atr: Long, rsi: Double, ema50: Long): PricePathDaily = PricePathDaily(
    ema50Cents = ema50,
    ema200Cents = ema50 - 200,
    rsi = rsi,
    bbLowerCents = close - atr,
    high52wCents = close + 3 * atr,
    low52wCents = close - 4 * atr,
    atrCents = atr,
)

private fun extendedLongInput(): PricePathInput = PricePathInput(
    marketPriceCents = 11_000,
    streetFairValueCents = 10_500,
    dcfValueCents = 10_400,
    analystLowCents = 10_200,
    gapBps = -500,
    daily = dailyAt(11_000, 150, 72.0, 10_200),
    candles = seriesMeanRevert(),
    nowEpoch = 1_710_000_000,
    forecastScore = 30,
    technicalScore = -15,
)

private fun noisyLongInput(): PricePathInput = PricePathInput(
    marketPriceCents = 11_500,
    streetFairValueCents = 10_000,
    dcfValueCents = 9_800,
    analystLowCents = 9_500,
    gapBps = 500,
    daily = dailyAt(11_500, 100, 80.0, 10_000),
    candles = seriesMeanRevert(),
    nextEarningsEpoch = 1_710_000_000 + 3 * 86_400,
    nowEpoch = 1_710_000_000,
    regimeRisk = true,
    forecastScore = 40,
    technicalScore = -20,
)
