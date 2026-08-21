package com.discountscreener.core.engine

import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValuationJudgmentAssemblerTest {
    @Test
    fun `solid model without street is identity`() {
        var judgment = ValuationJudgmentAssembler.assemble(
            detail("AAPL"),
            fcff(100_000L),
        )
        assertEquals(ValuationJudgmentStatus.Identity, judgment.status)
    }

    @Test
    fun `an unclassified name still names its complete street`() {
        var judgment = ValuationJudgmentAssembler.assemble(
            completeStreet("ETF1"),
            dcfAnalysis = null,
        )
        assertEquals(ValuationJudgmentStatus.Street, judgment.status)
    }

    @Test
    fun `operating missing drivers with complete street is street`() {
        var judgment = ValuationJudgmentAssembler.assemble(
            completeStreet(
                "NVDA",
                fundamentals = FundamentalSnapshot(
                    symbol = "NVDA",
                    sectorName = "Technology",
                    industryName = "Semiconductors",
                ),
            ),
            dcfAnalysis = null,
        )
        assertEquals(ValuationJudgmentStatus.Street, judgment.status)
    }

    @Test
    fun `financial none marker is missing drivers not illegal pair`() {
        var analysis = DcfAnalysis(
            bearIntrinsicValueCents = 0L,
            baseIntrinsicValueCents = 0L,
            bullIntrinsicValueCents = 0L,
            waccBps = 0,
            baseGrowthBps = 0,
            netDebtDollars = 0L,
            businessClass = BusinessClass.FinancialServices,
            model = ValuationModel.None,
            valuationUnavailableReason = "valuation unavailable: required annual driver evidence was exhausted",
        )
        var judgment = ValuationJudgmentAssembler.assemble(
            completeStreet(
                "JPM",
                fundamentals = FundamentalSnapshot(
                    symbol = "JPM",
                    sectorName = "Financial Services",
                    industryName = "Banks - Diversified",
                    returnOnEquityBps = 1_779,
                ),
            ),
            analysis,
        )
        assertEquals(
            Pair(ValuationJudgmentStatus.Street, false),
            Pair(judgment.status, judgment.reasonCodes.contains(ValuationJudgmentReason.IllegalModelPair)),
        )
    }

    @Test
    fun `snapshot copies identity primary cents`() {
        var judgment = ValuationJudgment(
            status = ValuationJudgmentStatus.Identity,
            relation = AnchorRelation.SingleSource,
            identity = fcff(100_000L),
            justifiedMultiple = null,
            street = null,
            primaryCents = 100_000L,
            reasonCodes = listOf(ValuationJudgmentReason.IdentityPrimary),
            policyVersion = ValuationJudgmentPolicy.POLICY_VERSION,
        )
        assertEquals(100_000L, ValuationJudgmentAssembler.snapshot(judgment).primaryCents)
    }

    @Test
    fun `snapshot names identity cash as our price`() {
        var analysis = fcff(10_691L).copy(
            waccBps = 1_316,
            baseGrowthBps = 2_500,
            stableGrowthBps = 370,
            netDebtDollars = -40_357_998_592L,
            latestRevenueDollars = 215_938_000_000L,
            normalizedFcffDollars = 97_388_038_000L,
            driverRegime = "secular_expansion",
            reasonCodes = listOf("market_params=rf:470,erp:442,school:implied_index,src:yahoo_tnx,prov:false"),
        )
        var judgment = ValuationJudgmentAssembler.assemble(
            completeStreet(
                "NVDA",
                fundamentals = FundamentalSnapshot(
                    symbol = "NVDA",
                    sectorName = "Technology",
                    industryName = "Semiconductors",
                    sharesOutstanding = 24_221_000_000L,
                ),
            ),
            analysis,
        )
        var snap = ValuationJudgmentAssembler.snapshot(
            judgment,
            lastPriceCents = 22_516L,
            sharesOutstanding = 24_221_000_000L,
        )
        assertEquals(10_691L, snap.horizonPriceCents)
    }

    private fun detail(
        symbol: String,
        fundamentals: FundamentalSnapshot = FundamentalSnapshot(symbol = symbol),
        low: Long? = null,
        base: Long? = null,
        high: Long? = null,
        analystCount: Int? = null,
    ): SymbolDetail = SymbolDetail(
        symbol = symbol,
        profitable = true,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 0L,
        gapBps = 0,
        minimumGapBps = 2_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalFairValueCents = base,
        externalSignalLowFairValueCents = low,
        externalSignalHighFairValueCents = high,
        weightedExternalSignalFairValueCents = base,
        weightedAnalystCount = analystCount,
        externalSignalAgeSeconds = 0L,
        externalSignalMaxAgeSeconds = 86_400L,
        analystOpinionCount = analystCount,
        fundamentals = fundamentals,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )

    private fun completeStreet(
        symbol: String,
        fundamentals: FundamentalSnapshot = FundamentalSnapshot(symbol = symbol),
    ): SymbolDetail = detail(
        symbol = symbol,
        fundamentals = fundamentals,
        low = 80_000L,
        base = 90_000L,
        high = 110_000L,
        analystCount = 12,
    )

    private fun fcff(base: Long): DcfAnalysis {
        var pad = (base / 10L).coerceAtLeast(1L)
        return DcfAnalysis(
            bearIntrinsicValueCents = base - pad,
            baseIntrinsicValueCents = base,
            bullIntrinsicValueCents = base + pad,
            waccBps = 1_000,
            baseGrowthBps = 400,
            netDebtDollars = 0L,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.FcffWacc,
            discountRateKind = DiscountRateKind.Wacc,
        )
    }
}
