package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport
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
        for (symbol in symbols) {
            val cap = symbol.fundamentals?.marketCapDollars ?: continue
            if (cap <= 0L) continue
            totalMarketCapDollars += cap
            weightedCurrentNumerator += symbol.marketPriceCents.toDouble() * cap.toDouble()
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
            totalSymbols = symbols.size,
            scenarios = scenarios,
            computedAtEpochSeconds = nowEpochSeconds,
        )
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
        EstimateScenario.BearDcf -> dcfBySymbol[symbol.symbol]?.bearIntrinsicValueCents
        EstimateScenario.BaseDcf -> dcfBySymbol[symbol.symbol]?.baseIntrinsicValueCents
        EstimateScenario.BullDcf -> dcfBySymbol[symbol.symbol]?.bullIntrinsicValueCents
        EstimateScenario.AnalystLow -> symbol.externalSignalLowFairValueCents
        EstimateScenario.AnalystHigh -> symbol.externalSignalHighFairValueCents
    }
}
