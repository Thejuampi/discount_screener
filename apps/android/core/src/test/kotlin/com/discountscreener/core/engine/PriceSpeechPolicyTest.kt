package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
class PriceSpeechPolicyTest {
    @Test
    fun `identity path at company wacc is 10691 cents`() {
        var cents = FcffFadePricer.equityCentsPerShare(
            latestRevenueDollars = 215_938_000_000.0,
            fcffMarginBps = 4_510,
            stableFcffMarginBps = 4_510,
            revenueGrowthBps = 2_500,
            currentShares = 24_221_000_000.0,
            netDebtDollars = -40_357_998_592L,
            gStableBps = 370,
            discountRateBps = 1_316,
            growthFadeExponent = 1.50,
            holdYears = 0,
            fadeYears = 10,
        )
        assertEquals(10_691L, cents)
    }

    @Test
    fun `forecast is identity cash`() {
        var speech = PriceSpeechPolicy.speak(
            lastPriceCents = 22_516L,
            streetTwelveMonthCents = 30_000L,
            analysis = nvdaPath(),
            sharesOutstanding = 24_221_000_000L,
        )
        assertEquals(10_691L, speech.expectedHorizonPriceCents)
    }

    private fun nvdaPath(): DcfAnalysis = DcfAnalysis(
        bearIntrinsicValueCents = 9_000L,
        baseIntrinsicValueCents = 10_691L,
        bullIntrinsicValueCents = 14_000L,
        waccBps = 1_316,
        baseGrowthBps = 2_500,
        netDebtDollars = -40_357_998_592L,
        businessClass = BusinessClass.OperatingNonFinancial,
        model = ValuationModel.FcffWacc,
        discountRateKind = DiscountRateKind.Wacc,
        stableGrowthBps = 370,
        latestRevenueDollars = 215_938_000_000L,
        normalizedFcffDollars = 97_388_038_000L,
        driverRegime = "secular_expansion",
        reasonCodes = listOf("market_params=rf:470,erp:442,school:implied_index,src:yahoo_tnx,prov:false"),
    )
}
