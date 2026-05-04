package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfCoverageStatus
import com.discountscreener.core.model.DcfCoverageSummary
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DcfSourceDistribution
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.ScenarioEstimate
import com.discountscreener.core.model.SymbolDetail
import kotlin.math.roundToInt

object IndexEstimatesEngine {
    fun compute(
        symbols: List<SymbolDetail>,
        dcfBySymbol: Map<String, DcfAnalysis>,
        profileName: String,
        nowEpochSeconds: Long,
    ): IndexEstimatesReport {
        var totalMarketCapDollars = 0L
        var weightedCurrentNumerator = 0.0
        var eligibleSymbols = 0
        for (symbol in symbols) {
            val cap = symbol.fundamentals?.marketCapDollars ?: continue
            if (cap <= 0L) continue
            totalMarketCapDollars += cap
            weightedCurrentNumerator += symbol.marketPriceCents.toDouble() * cap.toDouble()
            eligibleSymbols++
        }
        val currentWeightedPriceCents = if (totalMarketCapDollars > 0L) {
            (weightedCurrentNumerator / totalMarketCapDollars).toLong()
        } else {
            0L
        }

        val scenarios = EstimateScenario.entries.map { scenario ->
            computeScenario(scenario, symbols, dcfBySymbol, totalMarketCapDollars, currentWeightedPriceCents)
        }

        return IndexEstimatesReport(
            profileName = profileName,
            currentWeightedPriceCents = currentWeightedPriceCents,
            totalSymbols = eligibleSymbols,
            scenarios = scenarios,
            computedAtEpochSeconds = nowEpochSeconds,
            dcfCoverage = computeDcfCoverage(symbols, dcfBySymbol),
        )
    }

    private fun computeDcfCoverage(
        symbols: List<SymbolDetail>,
        dcfBySymbol: Map<String, DcfAnalysis>,
    ): DcfCoverageSummary {
        val eligibleSymbols = symbols.filter { symbol ->
            (symbol.fundamentals?.marketCapDollars ?: 0L) > 0L
        }
        var analyses = eligibleSymbols.map { symbol -> symbol.symbol to dcfBySymbol[symbol.symbol] }
        val notEligibleSymbols = analyses.count { (_, analysis) -> analysis?.resolverState == ResolverState.NotEligible }
        val denominator = eligibleSymbols.size - notEligibleSymbols
        val coveredAnalyses = analyses.mapNotNull { (_, analysis) -> analysis }
            .filter(::isLiveCompleteDcf)
        val numerator = coveredAnalyses.size
        val coverageBps = if (denominator == 0 || numerator == 0) {
            0
        } else {
            numerator * 10_000 / denominator
        }
        return DcfCoverageSummary(
            totalEligibleSymbols = denominator,
            coveredSymbols = numerator,
            coverageBps = coverageBps,
            status = dcfCoverageStatus(denominator, numerator, coverageBps),
            sourceDistribution = DcfSourceDistribution(
                yahooCount = coveredAnalyses.count { it.source == DcfSource.YahooFinance },
                secCount = coveredAnalyses.count { it.source == DcfSource.SecEdgar },
                restoredCount = analyses.count { (_, analysis) -> analysis?.resolverState == ResolverState.RestoredOnly },
                uncertainCount = analyses.count { (_, analysis) -> analysis?.resolverState == ResolverState.ProviderUncertain },
                notEligibleCount = notEligibleSymbols,
                unknownCount = coveredAnalyses.count { it.source == null || it.source == DcfSource.Unknown },
                unavailableCount = analyses.count { (_, analysis) -> analysis == null || analysis.resolverState == ResolverState.Unavailable },
            ),
        )
    }

    private fun isLiveCompleteDcf(analysis: DcfAnalysis): Boolean =
        analysis.resolverState == ResolverState.Selected &&
            analysis.bearIntrinsicValueCents > 0L &&
            analysis.baseIntrinsicValueCents > 0L &&
            analysis.bullIntrinsicValueCents > 0L

    private fun dcfCoverageStatus(
        denominator: Int,
        numerator: Int,
        coverageBps: Int,
    ): DcfCoverageStatus = when {
        denominator == 0 || numerator == 0 -> DcfCoverageStatus.Unavailable
        coverageBps < 2_500 -> DcfCoverageStatus.LowConfidence
        coverageBps < 5_000 -> DcfCoverageStatus.Partial
        coverageBps < 9_500 -> DcfCoverageStatus.Provisional
        else -> DcfCoverageStatus.Ready
    }

    private fun computeScenario(
        scenario: EstimateScenario,
        symbols: List<SymbolDetail>,
        dcfBySymbol: Map<String, DcfAnalysis>,
        totalMarketCapDollars: Long,
        currentWeightedPriceCents: Long,
    ): ScenarioEstimate {
        var numerator = 0.0
        var denominatorCap = 0L
        var coverage = 0

        for (symbol in symbols) {
            val cap = symbol.fundamentals?.marketCapDollars ?: continue
            if (cap <= 0L) continue
            val fairValue = scenarioFairValue(scenario, symbol, dcfBySymbol) ?: continue
            numerator += fairValue.toDouble() * cap.toDouble()
            denominatorCap += cap
            coverage++
        }

        val weightedPrice = if (denominatorCap > 0L) (numerator / denominatorCap).toLong() else 0L
        val impliedUpside = if (currentWeightedPriceCents <= 0L || coverage == 0) {
            0
        } else {
            ((weightedPrice.toDouble() / currentWeightedPriceCents - 1.0) * 10_000).roundToInt()
        }

        return ScenarioEstimate(
            scenario = scenario,
            weightedPriceCents = weightedPrice,
            coverageCount = coverage,
            impliedUpsideBps = impliedUpside,
        )
    }

    private fun scenarioFairValue(
        scenario: EstimateScenario,
        symbol: SymbolDetail,
        dcfBySymbol: Map<String, DcfAnalysis>,
    ): Long? = when (scenario) {
        EstimateScenario.BearDcf -> dcfBySymbol[symbol.symbol]?.takeIf(::isLiveCompleteDcf)?.bearIntrinsicValueCents
        EstimateScenario.BaseDcf -> dcfBySymbol[symbol.symbol]?.takeIf(::isLiveCompleteDcf)?.baseIntrinsicValueCents
        EstimateScenario.BullDcf -> dcfBySymbol[symbol.symbol]?.takeIf(::isLiveCompleteDcf)?.bullIntrinsicValueCents
        EstimateScenario.AnalystLow -> symbol.externalSignalLowFairValueCents
        EstimateScenario.AnalystHigh -> symbol.externalSignalHighFairValueCents
    }
}
