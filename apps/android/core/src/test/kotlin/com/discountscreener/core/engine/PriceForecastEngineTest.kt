package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PriceForecastEngineTest {
    @Test
    fun `forecast is identity cash and does not read street`() {
        var speech = PriceForecastEngine.forecast(
            analysis = DcfAnalysis(
                bearIntrinsicValueCents = 9_000L,
                baseIntrinsicValueCents = 10_691L,
                bullIntrinsicValueCents = 14_000L,
                waccBps = 1_316,
                baseGrowthBps = 2_500,
                netDebtDollars = 0L,
                businessClass = BusinessClass.OperatingNonFinancial,
                model = ValuationModel.FcffWacc,
                discountRateKind = DiscountRateKind.Wacc,
            ),
            sharesOutstanding = 24_221_000_000L,
            lastPriceCents = 22_516L,
            streetTwelveMonthCents = 30_000L,
        )
        assertEquals(10_691L, speech.expectedHorizonPriceCents)
        assertFalse(speech.reasonCodes.any { it.contains("street_mix") || it.contains("street_w") })
    }
}
