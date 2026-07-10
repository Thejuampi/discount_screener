package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.WaccFieldSource
import com.discountscreener.core.model.WaccInputProvenance
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val RISK_FREE_RATE_BPS = 400
private const val EQUITY_RISK_PREMIUM_BPS = 500
private const val DEFAULT_TAX_RATE_BPS = 2_100
private const val DEFAULT_COST_OF_DEBT_BPS = 550
private const val MIN_COST_OF_DEBT_BPS = 200
private const val MAX_COST_OF_DEBT_BPS = 1_200
private const val MIN_WACC_BPS = 500
private const val MAX_WACC_BPS = 1_800
private const val DCF_PROJECTION_YEARS = 5
private const val BASE_GROWTH_MIN_BPS = -1_000
private const val BASE_GROWTH_MAX_BPS = 1_800
private const val SCENARIO_GROWTH_SPREAD_BPS = 400
private const val BEAR_GROWTH_MIN_BPS = -1_200
private const val BEAR_GROWTH_MAX_BPS = 1_400
private const val BULL_GROWTH_MIN_BPS = -400
private const val BULL_GROWTH_MAX_BPS = 2_400
private const val BEAR_TERMINAL_GROWTH_BPS = 200
private const val BASE_TERMINAL_GROWTH_BPS = 250
private const val BULL_TERMINAL_GROWTH_BPS = 300

private data class ResolvedWacc(
    val waccBps: Int,
    val inputs: WaccInputProvenance,
)

object DcfAnalysisEngine {
    fun compute(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long? = null,
    ): Result<DcfAnalysis> = runCatching {
        require(timeseries.freeCashFlow.size >= 3) {
            "DCF unavailable: need at least 3 annual free cash flow points."
        }

        val latestFcf = timeseries.freeCashFlow.lastOrNull()?.value?.takeIf { it > 0.0 }
            ?: error("DCF unavailable: latest annual free cash flow is not positive.")
        val currentShares = latestShareCount(fundamentals, timeseries)
            ?: error("DCF unavailable: share count is missing.")
        val fcfPerShare = freeCashFlowPerShareSeries(fundamentals, timeseries, currentShares)
        val rawBaseGrowthBps = deriveBaseGrowthBps(fcfPerShare)
            ?: error("DCF unavailable: insufficient positive free cash flow per share history.")
        val resolvedWacc = deriveWacc(fundamentals, timeseries, marketPriceCents)
        val netDebtDollars = (fundamentals.totalDebtDollars ?: 0L) - (fundamentals.totalCashDollars ?: 0L)

        val bearGrowthBps = (rawBaseGrowthBps - SCENARIO_GROWTH_SPREAD_BPS).coerceIn(BEAR_GROWTH_MIN_BPS, BEAR_GROWTH_MAX_BPS)
        val baseGrowthBps = rawBaseGrowthBps.coerceIn(BASE_GROWTH_MIN_BPS, BASE_GROWTH_MAX_BPS)
        val bullGrowthBps = (baseGrowthBps + SCENARIO_GROWTH_SPREAD_BPS).coerceIn(BULL_GROWTH_MIN_BPS, BULL_GROWTH_MAX_BPS)

        val bearIntrinsic = discountedIntrinsicValuePerShareCents(
            latestFcf,
            currentShares,
            netDebtDollars,
            bearGrowthBps,
            clampTerminalGrowthBps(BEAR_TERMINAL_GROWTH_BPS, resolvedWacc.waccBps),
            resolvedWacc.waccBps,
        ) ?: error("DCF unavailable: bear scenario produced an invalid value.")
        val baseIntrinsic = discountedIntrinsicValuePerShareCents(
            latestFcf,
            currentShares,
            netDebtDollars,
            baseGrowthBps,
            clampTerminalGrowthBps(BASE_TERMINAL_GROWTH_BPS, resolvedWacc.waccBps),
            resolvedWacc.waccBps,
        ) ?: error("DCF unavailable: base scenario produced an invalid value.")
        val bullIntrinsic = discountedIntrinsicValuePerShareCents(
            latestFcf,
            currentShares,
            netDebtDollars,
            bullGrowthBps,
            clampTerminalGrowthBps(BULL_TERMINAL_GROWTH_BPS, resolvedWacc.waccBps),
            resolvedWacc.waccBps,
        ) ?: error("DCF unavailable: bull scenario produced an invalid value.")

        DcfAnalysis(
            bearIntrinsicValueCents = bearIntrinsic,
            baseIntrinsicValueCents = baseIntrinsic,
            bullIntrinsicValueCents = bullIntrinsic,
            waccBps = resolvedWacc.waccBps,
            baseGrowthBps = baseGrowthBps,
            netDebtDollars = netDebtDollars,
            waccInputs = resolvedWacc.inputs,
        )
    }

    private fun latestShareCount(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
    ): Double? = timeseries.dilutedAverageShares.lastOrNull()?.value?.takeIf { it > 0.0 }
        ?: fundamentals.sharesOutstanding?.toDouble()

    private fun freeCashFlowPerShareSeries(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        currentShares: Double,
    ): List<Pair<String, Double>> = timeseries.freeCashFlow.mapNotNull { point ->
        val shares = shareCountForDate(timeseries, point.asOfDate)
            ?: fundamentals.sharesOutstanding?.toDouble()
            ?: currentShares
        if (shares <= 0.0) null else point.asOfDate to (point.value / shares)
    }

    private fun shareCountForDate(
        timeseries: FundamentalTimeseries,
        asOfDate: String,
    ): Double? = timeseries.dilutedAverageShares
        .asReversed()
        .firstOrNull { it.asOfDate <= asOfDate }
        ?.value
        ?.takeIf { it > 0.0 }

    private fun deriveBaseGrowthBps(points: List<Pair<String, Double>>): Int? {
        val latestIndex = points.indexOfLast { it.second > 0.0 }.takeIf { it >= 0 } ?: return null
        val firstIndex = points.indexOfFirst { it.second > 0.0 }.takeIf { it >= 0 } ?: return null
        val latest = points[latestIndex]
        val first = points[firstIndex]
        val years = elapsedYearsBetween(first.first, latest.first)?.takeIf { it > 0.0 } ?: (latestIndex - firstIndex).toDouble()
        if (years <= 0.0) return null
        val cagr = (latest.second / first.second).pow(1.0 / years) - 1.0
        return if (cagr.isFinite()) (cagr * 10_000.0).roundToInt() else null
    }

    private fun elapsedYearsBetween(start: String, end: String): Double? {
        val startDate = parseYmd(start) ?: return null
        val endDate = parseYmd(end) ?: return null
        val elapsedDays = endDate.toEpochDay() - startDate.toEpochDay()
        return if (elapsedDays > 0) elapsedDays / 365.2425 else null
    }

    private fun parseYmd(value: String): java.time.LocalDate? =
        runCatching { java.time.LocalDate.parse(value) }.getOrNull()

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
    ): ResolvedWacc {
        val (marketCap, marketCapSource) = resolveMarketCapDollars(fundamentals, timeseries, marketPriceCents)
            ?: error("DCF unavailable: market cap is missing.")
        val betaSource = if (fundamentals.betaMillis != null) WaccFieldSource.Reported else WaccFieldSource.Default
        val beta = (fundamentals.betaMillis ?: 1_000) / 1_000.0
        val costOfEquityBps = RISK_FREE_RATE_BPS + (beta * EQUITY_RISK_PREMIUM_BPS).roundToInt()

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
            // Debt weight is zero, so default cost does not affect WACC; keep UI quiet.
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
        val unclamped = weighted.roundToInt()
        val waccBps = unclamped.coerceIn(MIN_WACC_BPS, MAX_WACC_BPS)

        return ResolvedWacc(
            waccBps = waccBps,
            inputs = WaccInputProvenance(
                marketCap = marketCapSource,
                beta = betaSource,
                totalDebt = totalDebtSource,
                totalCash = totalCashSource,
                costOfDebt = costOfDebtSource,
                taxRate = taxRateSource,
                waccClamped = unclamped != waccBps,
            ),
        )
    }

    private fun clampTerminalGrowthBps(terminalGrowthBps: Int, waccBps: Int): Int =
        terminalGrowthBps.coerceAtMost(waccBps - 50).coerceAtLeast(50)

    private fun discountedIntrinsicValuePerShareCents(
        latestFcfDollars: Double,
        currentShares: Double,
        netDebtDollars: Long,
        growthBps: Int,
        terminalGrowthBps: Int,
        waccBps: Int,
    ): Long? {
        if (latestFcfDollars <= 0.0 || currentShares <= 0.0 || terminalGrowthBps >= waccBps) {
            return null
        }
        val growth = growthBps / 10_000.0
        val terminalGrowth = terminalGrowthBps / 10_000.0
        val wacc = waccBps / 10_000.0
        var projectedFcf = latestFcfDollars
        var presentValue = 0.0

        for (year in 1..DCF_PROJECTION_YEARS) {
            projectedFcf *= 1.0 + growth
            presentValue += projectedFcf / (1.0 + wacc).pow(year)
        }

        val terminalCashFlow = projectedFcf * (1.0 + terminalGrowth)
        val terminalValue = terminalCashFlow / (wacc - terminalGrowth)
        val enterpriseValue = presentValue + terminalValue / (1.0 + wacc).pow(DCF_PROJECTION_YEARS)
        val equityValue = enterpriseValue - netDebtDollars
        if (!equityValue.isFinite() || equityValue <= 0.0) return null
        return ((equityValue / currentShares) * 100.0).roundToLong()
    }
}
