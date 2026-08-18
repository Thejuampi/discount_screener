package com.discountscreener.core.engine

import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.HonestPathInputs
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ValuationHonesty
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
    fun `unclassified with complete street stays unavailable`() {
        var judgment = ValuationJudgmentAssembler.assemble(
            completeStreet("ETF1"),
            dcfAnalysis = null,
        )
        assertNull(judgment.primaryCents)
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

    @Test
    fun snapshot_copies_the_engine_refuse_reason() {
        var reason = "valuation unavailable: fcff unavailable: interest is missing for 2024-09-28"
        var analysis = DcfAnalysis(
            bearIntrinsicValueCents = 0L,
            baseIntrinsicValueCents = 0L,
            bullIntrinsicValueCents = 0L,
            waccBps = 0,
            baseGrowthBps = 0,
            netDebtDollars = 0L,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.None,
            valuationUnavailableReason = reason,
        )
        var snap = ValuationJudgmentAssembler.snapshot(
            ValuationJudgmentAssembler.assemble(
                completeStreet(
                    "AAPL",
                    fundamentals = FundamentalSnapshot(
                        symbol = "AAPL",
                        sectorName = "Technology",
                        industryName = "Consumer Electronics",
                    ),
                ),
                analysis,
            ),
            dcfAnalysis = analysis,
        )
        assertEquals(reason, snap.identityUnavailableReason)
    }

    @Test
    fun snapshot_copies_debt_stock_caveat() {
        var analysis = pricedFcff().copy(
            reasonCodes = listOf(
                "model=fcff_wacc",
                "debt_stock=filed_year_end_instant",
            ),
        )
        var snap = impliedSnapshot(analysis)
        assertEquals(
            "Debt stock is the filed year-end instant.",
            snap.identityCaveatLines.single { it.startsWith("Debt stock") },
        )
    }

    @Test
    fun snapshot_copies_cost_of_debt_caveat() {
        var analysis = pricedFcff().copy(
            reasonCodes = listOf(
                "model=fcff_wacc",
                "cost_of_debt_source=rated_or_synthetic_spread",
                "coverage_synthetic=median_spread:59",
            ),
        )
        var snap = impliedSnapshot(analysis)
        assertEquals(
            "Cost of debt is a coverage synthetic from filed interest.",
            snap.identityCaveatLines.single { it.startsWith("Cost of debt") },
        )
    }

    @Test
    fun snapshot_names_factory_plus_lender() {
        var analysis = pricedFcff().copy(
            model = ValuationModel.ComponentSum,
            reasonCodes = listOf("model=component_sum", "component_sotp=$COMPONENT_SOTP_VERSION"),
        )
        var snap = impliedSnapshot(analysis)
        assertEquals(
            "Value is factory cash plus the lender book.",
            snap.identityCaveatLines.single(),
        )
    }

    @Test
    fun snapshot_names_current_instrument_yield_bps() {
        var analysis = pricedFcff().copy(
            reasonCodes = listOf(
                "model=fcff_wacc",
                "cost_of_debt_source=market_yield",
                "cost_of_debt_bps=471",
                "market_yield=current_instrument",
            ),
        )
        var snap = impliedSnapshot(analysis)
        assertEquals(
            "Cost of debt is the current instrument yield, 471 bps.",
            snap.identityCaveatLines.single { it.startsWith("Cost of debt") },
        )
    }

    @Test
    fun snapshot_copies_estimated_interest_caveat() {
        var analysis = pricedFcff().copy(
            reasonCodes = listOf(
                "model=fcff_wacc",
                "interest=estimated:own_effective_rate:medium:2024-09-28,2025-09-27",
            ),
        )
        var snap = impliedSnapshot(analysis)
        assertEquals(
            listOf(
                "Interest for 2024-09-28, 2025-09-27 is an estimate from this issuer's last filed coupon and debt. Confidence is Medium. A later filed tag replaces the estimate.",
            ),
            snap.identityCaveatLines,
        )
    }

    @Test
    fun snapshot_keeps_working_mode_honest() {
        var snap = impliedSnapshot()
        assertEquals(ValuationHonesty.Honest, snap.honestyMode)
    }

    @Test
    fun snapshot_tags_street_implied_knobs_as_non_honest() {
        var knobs = requireNotNull(impliedSnapshot().streetImplied).knobs
        assertEquals(true, knobs.isNotEmpty() && knobs.all { it.honesty == ValuationHonesty.NonHonest })
    }

    @Test
    fun list_row_snapshot_skips_street_knob_inversion() {
        assertEquals(
            null,
            impliedSnapshot(includeStreetImplied = false).streetImplied,
        )
    }

    @Test
    fun snapshot_horizon_stays_honest_identity() {
        var analysis = pricedFcff()
        var snap = impliedSnapshot(analysis)
        assertEquals(analysis.baseIntrinsicValueCents, snap.horizonPriceCents)
    }

    private fun impliedSnapshot(
        analysis: DcfAnalysis = pricedFcff(),
        includeStreetImplied: Boolean = true,
    ) =
        ValuationJudgmentAssembler.snapshot(
            ValuationJudgmentAssembler.assemble(
                completeStreet(
                    "CMCSA",
                    fundamentals = FundamentalSnapshot(
                        symbol = "CMCSA",
                        sectorName = "Communication Services",
                        industryName = "Entertainment",
                        sharesOutstanding = 100_000_000L,
                    ),
                ),
                analysis,
            ),
            lastPriceCents = 3_000L,
            sharesOutstanding = 100_000_000L,
            includeStreetImplied = includeStreetImplied,
        )

    private fun pricedFcff(): DcfAnalysis {
        var startMarginBps = 1_500
        var stableMarginBps = 1_500
        var priced = requireNotNull(
            FcffFadePricer.equityCentsPerShare(
                latestRevenueDollars = 10_000_000_000.0,
                fcffMarginBps = startMarginBps,
                stableFcffMarginBps = stableMarginBps,
                revenueGrowthBps = 400,
                currentShares = 100_000_000.0,
                netDebtDollars = 0L,
                gStableBps = 300,
                discountRateBps = 800,
                growthFadeExponent = 1.0,
                holdYears = 0,
                fadeYears = 5,
            ),
        )
        return DcfAnalysis(
            bearIntrinsicValueCents = priced - 100,
            baseIntrinsicValueCents = priced,
            bullIntrinsicValueCents = priced + 100,
            waccBps = 800,
            baseGrowthBps = 400,
            netDebtDollars = 0L,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.FcffWacc,
            discountRateKind = DiscountRateKind.Wacc,
            stableGrowthBps = 300,
            latestRevenueDollars = 10_000_000_000L,
            honesty = ValuationHonesty.Honest,
            honestPath = HonestPathInputs(
                holdYears = 0,
                fadeYears = 5,
                startMarginBps = startMarginBps,
                stableMarginBps = stableMarginBps,
                fadeExponentHundredths = 100,
            ),
        )
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
