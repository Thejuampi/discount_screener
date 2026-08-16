package com.discountscreener.core.engine

import com.discountscreener.core.model.FundamentalSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkableIdentityWave1CharacterizationTest {
    @Test
    fun `W1-P02 NVDA capture fan is wider than 12000 bps vs own base`() {
        var width = ValuationDecisionPolicy.scenarioWidthBps(1_330L, 52_400L, 382_000L)
        assertTrue(width != null && width > 12_000)
    }

    @Test
    fun `W1-P03 META capture fan is wider than 12000 bps vs own base`() {
        var width = ValuationDecisionPolicy.scenarioWidthBps(35_100L, 134_000L, 716_000L)
        assertTrue(width != null && width > 12_000)
    }

    @Test
    fun `W1-P04 CI capture bases differ by more than 5000 bps`() {
        var gap = ValuationDecisionPolicy.differenceBps(19_200L, 34_300L)
        assertTrue(gap != null && gap > 5_000)
    }

    @Test
    fun `W1-E01 ACGL official gap is 2466 bps not a one-sided 28 percent`() {
        var gap = ValuationDecisionPolicy.differenceBps(8_663L, 11_100L)
        assertEquals(2_466, gap)
    }

    @Test
    fun `W1-R02 ACGL fixture shrink CoE is 681 bps`() {
        var fund = FundamentalSnapshot(
            symbol = "ACGL",
            sectorName = "Financial Services",
            industryName = "Insurance - Property & Casualty",
            betaMillis = 292,
        )
        var params = MarketParams(rfBps = 463, erpBps = 442, provisional = false)
        var resolved = DcfAnalysisEngine.resolveCostOfEquity(fund, params)
        assertEquals(681, resolved.costOfEquityBps)
    }
}
