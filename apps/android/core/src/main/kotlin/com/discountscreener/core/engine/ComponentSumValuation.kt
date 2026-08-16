package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ValuationModel
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object ComponentSumValuation {
    fun value(
        fundamentals: FundamentalSnapshot,
        parentTimeseries: FundamentalTimeseries,
        marketPriceCents: Long?,
        marketParams: MarketParams,
        peerCoupons: List<PeerCouponEvidence>,
        issuerYield: IssuerYieldPoint?,
        components: IssuerComponentSet,
    ): DcfAnalysis {
        var operating = components.operating
            ?: error("fcff unavailable: factory drivers missing on a mixed issuer")
        var financial = components.financial
            ?: error("fcff unavailable: lender book missing on a mixed issuer")
        var shares = fundamentals.sharesOutstanding?.takeIf { it > 0 }?.toDouble()
            ?: error("fcff unavailable: share count is missing")
        var lender = lenderEquity(financial, fundamentals, marketParams)
        var lenderPerShare = (lender.baseCents / shares).roundToLong()
        var lenderBearPerShare = (lender.bearCents / shares).roundToLong()
        var lenderBullPerShare = (lender.bullCents / shares).roundToLong()
        var cash = factoryCash(fundamentals, operating, financial)
        var factoryCap = factoryMarketCap(fundamentals, lender.baseCents)
        var factoryTs = factoryTimeseries(operating, parentTimeseries)
        var factoryFund = fundamentals.copy(
            marketCapDollars = factoryCap,
            totalDebtDollars = latest(operating.debt)?.roundToLong(),
            totalCashDollars = cash.dollars,
            returnOnEquityBps = null,
        )
        var factoryResult = if (factoryTs.freeCashFlow.isEmpty()) {
            Result.failure(IllegalStateException("factory_fcff=depreciation_missing"))
        } else {
            DcfAnalysisEngine.compute(
                fundamentals = factoryFund,
                timeseries = factoryTs,
                marketPriceCents = marketPriceCents,
                marketParams = marketParams,
                peerCoupons = peerCoupons,
                issuerYield = issuerYield,
                components = null,
            )
        }
        var factory = factoryResult.getOrNull()
        var factoryHole = factoryHoleCode(factoryResult.exceptionOrNull()?.message)
        if (factory == null && factoryHole == null) {
            error(factoryResult.exceptionOrNull()?.message ?: "fcff unavailable: factory component failed")
        }
        var reasons = buildList {
            add("model=component_sum")
            add("component_sotp=$COMPONENT_SOTP_VERSION")
            add("component=operating:fcff")
            add("component=financial:residual")
            add("operating_fcff=nopat_plus_da_minus_sustaining_capex")
            add("industry_operating_path=${IndustryOperatingPathPolicy.VERSION}")
            add("valuation_policy=${ValuationPolicy.VERSION}")
            add(cash.stamp)
            factoryHole?.let { add(it) }
            addAll(components.provenance)
            factory?.reasonCodes?.filter { !it.startsWith("model=") }?.let { addAll(it) }
            addAll(lender.reasons)
        }
        var factoryBear = factory?.bearIntrinsicValueCents ?: 0L
        var factoryBase = factory?.baseIntrinsicValueCents ?: 0L
        var factoryBull = factory?.bullIntrinsicValueCents ?: 0L
        if (factory != null) {
            return factory.copy(
                bearIntrinsicValueCents = (factoryBear + lenderBearPerShare).coerceAtLeast(0L),
                baseIntrinsicValueCents = (factoryBase + lenderPerShare).coerceAtLeast(0L),
                bullIntrinsicValueCents = (factoryBull + lenderBullPerShare).coerceAtLeast(0L),
                model = ValuationModel.ComponentSum,
                reasonCodes = reasons,
                valuationDriver = "component_sum",
                driverProvenance = factory.driverProvenance + listOf(
                    "model=component_sum",
                    "honesty=honest",
                ),
            )
        }
        var (reRaw, _, _) = DcfAnalysisEngine.costOfEquityBps(fundamentals, marketParams)
        return DcfAnalysis(
            bearIntrinsicValueCents = lenderBearPerShare.coerceAtLeast(0L),
            baseIntrinsicValueCents = lenderPerShare.coerceAtLeast(0L),
            bullIntrinsicValueCents = lenderBullPerShare.coerceAtLeast(0L),
            waccBps = reRaw,
            baseGrowthBps = 0,
            netDebtDollars = 0L,
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.ComponentSum,
            discountRateKind = DiscountRateKind.CostOfEquity,
            reasonCodes = reasons,
            valuationDriver = "component_sum",
            driverProvenance = listOf("model=component_sum", "honesty=honest"),
        )
    }

    private fun factoryHoleCode(message: String?): String? {
        if (message == null) return null
        if (message.startsWith("factory_fcff=depreciation_missing")) return "factory_fcff=depreciation_missing"
        if (message.startsWith("non_positive_normalized_fcff")) return "factory_fcff=non_positive_margin"
        return null
    }

    private data class LenderValue(
        val bearCents: Long,
        val baseCents: Long,
        val bullCents: Long,
        val reasons: List<String>,
    )

    private fun lenderEquity(
        financial: FinancialComponentDrivers,
        fundamentals: FundamentalSnapshot,
        marketParams: MarketParams,
    ): LenderValue {
        var book = latest(financial.bookEquity) ?: error("fcff unavailable: lender book is missing")
        var ni = latest(financial.netIncome) ?: error("fcff unavailable: lender income is missing")
        if (book <= 0.0 || ni <= 0.0) error("fcff unavailable: lender book or income is not positive")
        var roeBps = ((ni / book) * 10_000.0).roundToInt()
        var dividends = latest(financial.dividends)
        var retention = financial.retentionBps?.takeIf { it in 0..10_000 }?.div(10_000.0)
            ?: if (dividends != null && ni > 0.0) {
                (1.0 - (dividends / ni)).coerceIn(0.0, 1.0)
            } else {
                error("fcff unavailable: lender retention is missing")
            }
        var (reRaw, _, _) = DcfAnalysisEngine.costOfEquityBps(fundamentals, marketParams)
        var residualPath = ResidualPathPolicy.resolve(
            roe0Bps = roeBps,
            costOfEquityBps = reRaw,
            industry = "Credit Services",
            sector = "Financial Services",
        )
        var reBase = (reRaw + residualPath.discountAdjustBps).coerceAtLeast(marketParams.rfBps + 50)
        var longRun = ResidualIncomeMath.longRunRoeBps(roeBps, reBase, residualPath.franchiseSpreadBps)
        var stable = marketParams.stableGrowthBps()
        fun scenario(roe: Int, re: Int, ret: Double): Long =
            ResidualIncomeMath.valueEquityCents(
                book0 = book,
                roe0Bps = roe,
                costOfEquityBps = re,
                retention = ret,
                fadeYears = residualPath.fadeYears,
                longRunRoeBps = longRun,
                stableGrowthBps = stable,
            ) ?: error("fcff unavailable: lender residual income invalid")
        return LenderValue(
            bearCents = scenario((roeBps - 300).coerceAtLeast(100), reBase + 75, retention * 0.9),
            baseCents = scenario(residualPath.startingRoeBps, reBase, retention),
            bullCents = scenario((roeBps + 200).coerceAtMost(9_000), (reBase - 75).coerceAtLeast(marketParams.rfBps + 50), retention.coerceAtMost(0.85)),
            reasons = listOf(
                "lender_book=${book.roundToLong()}",
                "lender_roe=${roeBps}",
                "lender_source=${financial.source}",
            ),
        )
    }

    private fun factoryMarketCap(fundamentals: FundamentalSnapshot, lenderEquityCents: Long): Long {
        var parent = fundamentals.marketCapDollars ?: 0L
        var lenderDollars = lenderEquityCents / 100L
        return (parent - lenderDollars).coerceAtLeast(1L)
    }

    private fun factoryTimeseries(
        operating: OperatingComponentDrivers,
        parent: FundamentalTimeseries,
    ): FundamentalTimeseries {
        var tax = latest(parent.taxRateForCalcs) ?: 0.21
        if (tax > 1.0) tax /= 10_000.0
        var revenueByEnd = operating.revenue.associateBy { it.asOfDate }
        var ebitByEnd = operating.ebit.associateBy { it.asOfDate }
        var capexByEnd = operating.capex.associateBy { it.asOfDate }
        var daByEnd = operating.da.associateBy { it.asOfDate }
        var ends = (revenueByEnd.keys + ebitByEnd.keys + capexByEnd.keys + daByEnd.keys)
            .distinct()
            .sorted()
        var growthBps = revenueGrowthBps(operating.revenue)
        var ocf = mutableListOf<AnnualReportedValue>()
        var capex = mutableListOf<AnnualReportedValue>()
        var revenue = mutableListOf<AnnualReportedValue>()
        var fcf = mutableListOf<AnnualReportedValue>()
        for (end in ends) {
            var rev = revenueByEnd[end]?.value ?: continue
            var ebit = ebitByEnd[end]?.value ?: continue
            var spend = capexByEnd[end]?.value ?: continue
            var depreciation = daByEnd[end]?.value ?: continue
            if (rev <= 0.0) continue
            var nopat = ebit * (1.0 - tax)
            var intensity = ((kotlin.math.abs(spend) / rev) * 10_000.0).roundToInt()
            var sustainBps = SustainingCapex.intensityBps(intensity, growthBps)
            var sustain = rev * sustainBps / 10_000.0
            var interest = operating.interest.firstOrNull { it.asOfDate == end }?.value ?: 0.0
            var afterTaxInterest = interest * (1.0 - tax)
            var fcff = FactoryComponentCash.annualFcff(nopat, depreciation, sustain)
            ocf += AnnualReportedValue(
                end,
                nopat + depreciation - afterTaxInterest,
                source = DcfSource.SecEdgar,
                concept = "factory_nopat_plus_da",
            )
            capex += AnnualReportedValue(end, -sustain, source = DcfSource.SecEdgar, concept = "factory_sustaining_capex")
            revenue += AnnualReportedValue(end, rev, source = DcfSource.SecEdgar)
            fcf += AnnualReportedValue(end, fcff, source = DcfSource.SecEdgar)
        }
        return FundamentalTimeseries(
            freeCashFlow = fcf,
            operatingCashFlow = ocf,
            capitalExpenditure = capex,
            revenue = revenue,
            dilutedAverageShares = parent.dilutedAverageShares,
            interestExpense = operating.interest,
            pretaxIncome = parent.pretaxIncome,
            taxRateForCalcs = parent.taxRateForCalcs,
            totalDebt = operating.debt,
            marginalTaxRate = parent.marginalTaxRate,
            marketYieldBps = parent.marketYieldBps,
        )
    }

    private fun revenueGrowthBps(revenue: List<AnnualReportedValue>): Int {
        var sorted = revenue.sortedBy { it.asOfDate }
        if (sorted.size < 2) return 0
        var prior = sorted[sorted.lastIndex - 1].value
        var latest = sorted.last().value
        if (prior <= 0.0) return 0
        return ((latest / prior - 1.0) * 10_000.0).roundToInt()
    }

    private data class FactoryCash(
        val dollars: Long,
        val stamp: String,
    )

    private fun factoryCash(
        fundamentals: FundamentalSnapshot,
        operating: OperatingComponentDrivers,
        financial: FinancialComponentDrivers,
    ): FactoryCash {
        var segment = latest(operating.cash)?.roundToLong()
        if (segment != null) return FactoryCash(segment, "factory_cash=segment")
        var parent = fundamentals.totalCashDollars
        var lender = latest(financial.cash)?.roundToLong()
        if (parent != null && lender != null) {
            return FactoryCash((parent - lender).coerceAtLeast(0L), "factory_cash=parent_minus_lender")
        }
        return FactoryCash(0L, "factory_cash=missing")
    }

    private fun latest(rows: List<AnnualReportedValue>): Double? = rows.maxByOrNull { it.asOfDate }?.value
}
