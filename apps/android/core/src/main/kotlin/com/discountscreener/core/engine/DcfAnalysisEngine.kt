package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ValuationModel
import com.discountscreener.core.model.WaccFieldSource
import com.discountscreener.core.model.WaccInputProvenance
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Valuation model family (parity with Windows `dcf_model.rs`).
 * Financial services → residual income; operating → FCFF with growth fade.
 * See `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`.
 */
private const val ENGINE_VERSION = "valuation-model-family/1"
private const val MODEL_POLICY_VERSION = "business-class-policy/1"
private const val DEFAULT_RF_BPS = 430
private const val DEFAULT_ERP_BPS = 450
private const val DEFAULT_TAX_RATE_BPS = 2_100
private const val DEFAULT_COST_OF_DEBT_BPS = 550
private const val DEFAULT_RETENTION_BPS = 7_000
private const val BETA_COMPANY_WEIGHT = 0.67
private const val BETA_INDUSTRY_WEIGHT = 0.33
private const val DEFAULT_INDUSTRY_BETA_MILLIS = 1_000
private const val PROJECTION_YEARS = 5
private const val COE_SCENARIO_BAND_BPS = 75
private const val ROE_BEAR_HAIRCUT_BPS = 300
private const val ROE_BULL_BOOST_BPS = 200
private const val GROWTH_RECENT_WINDOW = 4
private const val STABLE_GROWTH_RF_BUFFER_BPS = 100
/** Long-run nominal economy growth ceiling (bps); g_stable ≤ min(this, rf − buffer). */
private const val MACRO_STABLE_GROWTH_BPS = 300
private const val MIN_STABLE_GROWTH_BPS = 50
private const val GORDON_RATE_EPSILON_BPS = 50
private const val MIN_COST_OF_DEBT_BPS = 200
private const val MAX_COST_OF_DEBT_BPS = 1_200

data class MarketParams(
    val rfBps: Int = DEFAULT_RF_BPS,
    val erpBps: Int = DEFAULT_ERP_BPS,
    val provisional: Boolean = true,
) {
    fun stableGrowthBps(): Int =
        minOf(MACRO_STABLE_GROWTH_BPS, rfBps - STABLE_GROWTH_RF_BUFFER_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)
}

private data class ResolvedWacc(
    val waccBps: Int,
    val inputs: WaccInputProvenance,
)

object DcfAnalysisEngine {
    fun classifyBusiness(
        sectorName: String?,
        industryName: String?,
        sectorKey: String? = null,
        industryKey: String? = null,
        assetNotEquity: Boolean = false,
    ): BusinessClass {
        if (assetNotEquity) return BusinessClass.NotEligible
        val blob = listOfNotNull(sectorName, industryName, sectorKey, industryKey)
            .joinToString(" ")
            .lowercase()
        return if (isFinancialServicesText(blob)) {
            BusinessClass.FinancialServices
        } else {
            BusinessClass.OperatingNonFinancial
        }
    }

    fun compute(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long? = null,
        marketParams: MarketParams = MarketParams(),
        assetNotEquity: Boolean = false,
    ): Result<DcfAnalysis> = runCatching {
        when (
            classifyBusiness(
                fundamentals.sectorName,
                fundamentals.industryName,
                fundamentals.sectorKey,
                fundamentals.industryKey,
                assetNotEquity,
            )
        ) {
            BusinessClass.NotEligible ->
                error("valuation not eligible for this asset class")
            BusinessClass.FinancialServices ->
                residualIncome(fundamentals, marketPriceCents, marketParams)
            BusinessClass.OperatingNonFinancial ->
                fcffWacc(fundamentals, timeseries, marketPriceCents, marketParams)
        }
    }

    private fun isFinancialServicesText(blob: String): Boolean {
        val keys = listOf(
            "financial", "insurance", "bank", "banks", "capital markets",
            "asset management", "credit services", "mortgage finance", "reinsurance",
            "life insurance", "property & casualty", "property and casualty",
            "diversified financial", "financial conglomerate", "brokerage",
            "investment banking", "savings & loan", "thrift",
        )
        return keys.any { blob.contains(it) }
    }

    private fun residualIncome(
        fundamentals: FundamentalSnapshot,
        marketPriceCents: Long?,
        marketParams: MarketParams,
    ): DcfAnalysis {
        val shares = latestShareCount(fundamentals, FundamentalTimeseries())
            ?: error("DCF unavailable: share count is missing.")
        val bvpsCents = resolveBookValuePerShareCents(fundamentals, marketPriceCents)
            ?: error("DCF unavailable: book equity is missing.")
        val book0 = (bvpsCents / 100.0) * shares
        require(book0.isFinite() && book0 > 0.0) { "book equity is not positive" }
        val roe0Bps = fundamentals.returnOnEquityBps
            ?.takeIf { it > 0 && it < 10_000 }
            ?: error("DCF unavailable: return on equity is missing or invalid.")

        val (reBase, betaSource, betaProv) = costOfEquityBps(fundamentals, marketParams)
        val retention = DEFAULT_RETENTION_BPS / 10_000.0

        val bear = riScenario(
            book0, shares,
            (roe0Bps - ROE_BEAR_HAIRCUT_BPS).coerceAtLeast(100),
            reBase + COE_SCENARIO_BAND_BPS,
            retention * 0.9,
        ) ?: error("bear residual income invalid")
        val base = riScenario(book0, shares, roe0Bps, reBase, retention)
            ?: error("base residual income invalid")
        val bull = riScenario(
            book0, shares,
            (roe0Bps + ROE_BULL_BOOST_BPS).coerceAtMost(9_000),
            (reBase - COE_SCENARIO_BAND_BPS).coerceAtLeast(marketParams.rfBps + 50),
            retention.coerceAtMost(0.85),
        ) ?: error("bull residual income invalid")

        val reasons = buildList {
            add("model=residual_income_equity")
            add("business_class=financial_services")
            add("terminal_roe_fades_to_cost_of_equity")
            if (marketParams.provisional) add("market_params=provisional")
        }

        return DcfAnalysis(
            bearIntrinsicValueCents = bear,
            baseIntrinsicValueCents = base,
            bullIntrinsicValueCents = bull,
            waccBps = reBase,
            baseGrowthBps = ((roe0Bps / 10_000.0) * retention * 10_000.0).roundToInt(),
            netDebtDollars = 0L,
            waccInputs = WaccInputProvenance(
                beta = betaSource,
                waccClamped = betaProv || marketParams.provisional,
            ),
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = BusinessClass.FinancialServices,
            model = ValuationModel.ResidualIncomeEquity,
            discountRateKind = DiscountRateKind.CostOfEquity,
            stableGrowthBps = marketParams.stableGrowthBps()
                .coerceAtMost(reBase - GORDON_RATE_EPSILON_BPS),
            bookValuePerShareCents = bvpsCents,
            roe0Bps = roe0Bps,
            reasonCodes = reasons,
        )
    }

    private fun riScenario(
        book0: Double,
        shares: Double,
        roe0Bps: Int,
        reBps: Int,
        retention: Double,
    ): Long? {
        if (book0 <= 0.0 || shares <= 0.0 || reBps <= 0) return null
        val re = reBps / 10_000.0
        val roe0 = roe0Bps / 10_000.0
        val roeStable = re
        var book = book0
        var pvRi = 0.0
        for (t in 1..PROJECTION_YEARS) {
            val w = t.toDouble() / PROJECTION_YEARS
            val roeT = roe0 * (1.0 - w) + roeStable * w
            val excess = (roeT - re) * book
            pvRi += excess / (1.0 + re).pow(t)
            book *= 1.0 + roeT * retention
            if (!book.isFinite() || book <= 0.0) return null
        }
        val equity = book0 + pvRi
        if (!equity.isFinite() || equity <= 0.0) return null
        return ((equity / shares) * 100.0).roundToLong()
    }

    private fun resolveBookValuePerShareCents(
        fundamentals: FundamentalSnapshot,
        marketPriceCents: Long?,
    ): Long? {
        fundamentals.bookValuePerShareCents?.takeIf { it > 0 }?.let { return it }
        val price = marketPriceCents?.takeIf { it > 0 }?.div(100.0) ?: return null
        val pb = fundamentals.priceToBookHundredths?.takeIf { it > 0 }?.div(100.0) ?: return null
        if (pb <= 0.0) return null
        val bvps = price / pb
        if (!bvps.isFinite() || bvps <= 0.0) return null
        return (bvps * 100.0).roundToLong()
    }

    private fun fcffWacc(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long?,
        marketParams: MarketParams,
    ): DcfAnalysis {
        require(timeseries.freeCashFlow.size >= 3) {
            "DCF unavailable: need at least 3 annual free cash flow points."
        }
        val latestFcf = timeseries.freeCashFlow.lastOrNull()?.value?.takeIf { it > 0.0 }
            ?: error("DCF unavailable: latest annual free cash flow is not positive.")
        val currentShares = latestShareCount(fundamentals, timeseries)
            ?: error("DCF unavailable: share count is missing.")
        val gNear = recentFcfGrowthBps(timeseries)
            ?: error("DCF unavailable: insufficient positive free cash flow history for growth.")
        val resolvedWacc = deriveWacc(fundamentals, timeseries, marketPriceCents, marketParams)
        val netDebtDollars = (fundamentals.totalDebtDollars ?: 0L) - (fundamentals.totalCashDollars ?: 0L)
        val gStable = marketParams.stableGrowthBps()
            .coerceAtMost(resolvedWacc.waccBps - GORDON_RATE_EPSILON_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)

        val bearNear = (gNear - 400).coerceAtLeast(-1_200)
        val bullNear = (gNear + 400).coerceAtMost(2_400)

        val bear = discountedFcffFade(latestFcf, currentShares, netDebtDollars, bearNear, gStable, resolvedWacc.waccBps)
            ?: error("DCF unavailable: bear scenario produced an invalid value.")
        val base = discountedFcffFade(latestFcf, currentShares, netDebtDollars, gNear, gStable, resolvedWacc.waccBps)
            ?: error("DCF unavailable: base scenario produced an invalid value.")
        val bull = discountedFcffFade(latestFcf, currentShares, netDebtDollars, bullNear, gStable, resolvedWacc.waccBps)
            ?: error("DCF unavailable: bull scenario produced an invalid value.")

        val reasons = buildList {
            add("model=fcff_wacc")
            add("business_class=operating_non_financial")
            add("growth=recent_window_fade_to_stable")
            if (marketParams.provisional) add("market_params=provisional")
        }

        return DcfAnalysis(
            bearIntrinsicValueCents = bear,
            baseIntrinsicValueCents = base,
            bullIntrinsicValueCents = bull,
            waccBps = resolvedWacc.waccBps,
            baseGrowthBps = gNear,
            netDebtDollars = netDebtDollars,
            waccInputs = resolvedWacc.inputs,
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.FcffWacc,
            discountRateKind = DiscountRateKind.Wacc,
            stableGrowthBps = gStable,
            bookValuePerShareCents = fundamentals.bookValuePerShareCents,
            roe0Bps = fundamentals.returnOnEquityBps,
            reasonCodes = reasons,
        )
    }

    private fun recentFcfGrowthBps(timeseries: FundamentalTimeseries): Int? {
        val positive = timeseries.freeCashFlow.filter { it.value > 0.0 }
        if (positive.size < 2) return null
        val window = if (positive.size > GROWTH_RECENT_WINDOW) {
            positive.takeLast(GROWTH_RECENT_WINDOW)
        } else {
            positive
        }
        val first = window.first()
        val last = window.last()
        val years = elapsedYearsBetween(first.asOfDate, last.asOfDate)
            ?.takeIf { it > 0.0 }
            ?: (window.size - 1).toDouble().coerceAtLeast(1.0)
        if (first.value <= 0.0) return null
        val cagr = (last.value / first.value).pow(1.0 / years) - 1.0
        return if (cagr.isFinite()) (cagr * 10_000.0).roundToInt() else null
    }

    private fun discountedFcffFade(
        latestFcfDollars: Double,
        currentShares: Double,
        netDebtDollars: Long,
        gNearBps: Int,
        gStableBps: Int,
        waccBps: Int,
    ): Long? {
        if (latestFcfDollars <= 0.0 || currentShares <= 0.0 || gStableBps >= waccBps) return null
        val wacc = waccBps / 10_000.0
        val gNear = gNearBps / 10_000.0
        val gStable = gStableBps / 10_000.0
        var projected = latestFcfDollars
        var presentValue = 0.0
        for (year in 1..PROJECTION_YEARS) {
            val w = year.toDouble() / PROJECTION_YEARS
            val g = gNear * (1.0 - w) + gStable * w
            projected *= 1.0 + g
            presentValue += projected / (1.0 + wacc).pow(year)
        }
        val terminalCashFlow = projected * (1.0 + gStable)
        val terminalValue = terminalCashFlow / (wacc - gStable)
        val enterpriseValue = presentValue + terminalValue / (1.0 + wacc).pow(PROJECTION_YEARS)
        val equityValue = enterpriseValue - netDebtDollars
        if (!equityValue.isFinite() || equityValue <= 0.0) return null
        return ((equityValue / currentShares) * 100.0).roundToLong()
    }

    private fun latestShareCount(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
    ): Double? = timeseries.dilutedAverageShares.lastOrNull()?.value?.takeIf { it > 0.0 }
        ?: fundamentals.sharesOutstanding?.toDouble()

    private fun elapsedYearsBetween(start: String, end: String): Double? {
        val startDate = parseYmd(start) ?: return null
        val endDate = parseYmd(end) ?: return null
        val elapsedDays = endDate.toEpochDay() - startDate.toEpochDay()
        return if (elapsedDays > 0) elapsedDays / 365.2425 else null
    }

    private fun parseYmd(value: String): java.time.LocalDate? =
        runCatching { java.time.LocalDate.parse(value) }.getOrNull()

    private fun industryBetaMillis(fundamentals: FundamentalSnapshot): Int {
        val blob = listOfNotNull(
            fundamentals.sectorName,
            fundamentals.industryName,
            fundamentals.sectorKey,
        ).joinToString(" ").lowercase()
        return when {
            blob.contains("utilit") -> 600
            blob.contains("consumer staples") || blob.contains("consumer defensive") -> 700
            blob.contains("health") || blob.contains("pharma") -> 900
            blob.contains("technolog") || blob.contains("software") || blob.contains("semiconductor") -> 1_200
            blob.contains("energy") -> 1_100
            blob.contains("financial") || blob.contains("insurance") || blob.contains("bank") -> 900
            blob.contains("real estate") || blob.contains("reit") -> 850
            else -> DEFAULT_INDUSTRY_BETA_MILLIS
        }
    }

    private fun costOfEquityBps(
        fundamentals: FundamentalSnapshot,
        marketParams: MarketParams,
    ): Triple<Int, WaccFieldSource, Boolean> {
        val industry = industryBetaMillis(fundamentals) / 1_000.0
        val (raw, source, provisional) = when (val b = fundamentals.betaMillis) {
            null -> Triple(industry, WaccFieldSource.Default, true)
            else -> if (b > 0) {
                val company = b / 1_000.0
                val shrunk = BETA_COMPANY_WEIGHT * company + BETA_INDUSTRY_WEIGHT * industry
                Triple(shrunk, WaccFieldSource.IndustryShrink, false)
            } else {
                Triple(industry, WaccFieldSource.Default, true)
            }
        }
        val re = marketParams.rfBps + (raw * marketParams.erpBps).roundToInt()
        return Triple(
            re.coerceAtLeast(marketParams.rfBps + 50),
            source,
            provisional || marketParams.provisional,
        )
    }

    private fun resolveMarketCapDollars(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long?,
    ): Pair<Double, WaccFieldSource>? {
        fundamentals.marketCapDollars?.takeIf { it > 0L }?.let { reported ->
            return reported.toDouble() to WaccFieldSource.Reported
        }
        val shares = latestShareCount(fundamentals, timeseries) ?: return null
        val priceCents = marketPriceCents?.takeIf { it > 0L } ?: return null
        val derived = (priceCents / 100.0) * shares
        if (!derived.isFinite() || derived <= 0.0) return null
        return derived to WaccFieldSource.DerivedPriceTimesShares
    }

    private fun deriveWacc(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long?,
        marketParams: MarketParams,
    ): ResolvedWacc {
        val (marketCap, marketCapSource) = resolveMarketCapDollars(fundamentals, timeseries, marketPriceCents)
            ?: error("DCF unavailable: market cap is missing.")
        val (costOfEquityBps, betaSource, betaProv) = costOfEquityBps(fundamentals, marketParams)

        val totalDebtSource =
            if (fundamentals.totalDebtDollars != null) WaccFieldSource.Reported else WaccFieldSource.AssumedZero
        val totalCashSource =
            if (fundamentals.totalCashDollars != null) WaccFieldSource.Reported else WaccFieldSource.AssumedZero
        val totalDebt = (fundamentals.totalDebtDollars ?: 0L).coerceAtLeast(0).toDouble()
        val totalCash = (fundamentals.totalCashDollars ?: 0L).coerceAtLeast(0).toDouble()
        val netDebt = (totalDebt - totalCash).coerceAtLeast(0.0)
        val debtWeightBase = marketCap + netDebt
        val equityWeight = if (debtWeightBase > 0.0) marketCap / debtWeightBase else 1.0
        val debtWeight = if (debtWeightBase > 0.0) netDebt / debtWeightBase else 0.0

        val latestInterestExpense = timeseries.interestExpense.lastOrNull()?.value?.absoluteValue
        val costOfDebtSource: WaccFieldSource
        val costOfDebtBps = if (totalDebt > 0.0) {
            if (latestInterestExpense != null) {
                costOfDebtSource = WaccFieldSource.InterestOverDebt
                ((latestInterestExpense / totalDebt) * 10_000.0).roundToInt()
                    .coerceIn(MIN_COST_OF_DEBT_BPS, MAX_COST_OF_DEBT_BPS)
            } else {
                costOfDebtSource = WaccFieldSource.Default
                DEFAULT_COST_OF_DEBT_BPS
            }
        } else {
            costOfDebtSource = WaccFieldSource.Reported
            DEFAULT_COST_OF_DEBT_BPS
        }

        val taxRateSource =
            if (timeseries.taxRateForCalcs.isNotEmpty()) WaccFieldSource.Reported else WaccFieldSource.Default
        val taxRateBps = (timeseries.taxRateForCalcs.lastOrNull()?.value?.times(10_000.0)?.roundToInt()
            ?: DEFAULT_TAX_RATE_BPS)
            .coerceIn(0, 3_500)
        val afterTaxCostOfDebtBps = (costOfDebtBps * (1.0 - taxRateBps / 10_000.0)).roundToInt()
        val weighted = (equityWeight * costOfEquityBps) + (debtWeight * afterTaxCostOfDebtBps)
        val waccBps = weighted.roundToInt()

        return ResolvedWacc(
            waccBps = waccBps,
            inputs = WaccInputProvenance(
                marketCap = marketCapSource,
                beta = betaSource,
                totalDebt = totalDebtSource,
                totalCash = totalCashSource,
                costOfDebt = costOfDebtSource,
                taxRate = taxRateSource,
                waccClamped = betaProv || marketParams.provisional,
            ),
        )
    }
}
