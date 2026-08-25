package com.discountscreener.android.domain.model

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.plan.CrossSignalEngine
import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.DipSetup
import com.discountscreener.core.plan.DipSignalEngine
import com.discountscreener.core.plan.PlanBoard

object CrossBoardAssembler {
    fun assemble(
        rows: List<OpportunityListRow>,
        yearCandlesBySymbol: Map<String, List<HistoricalCandle>>,
        dcfBySymbol: Map<String, DcfAnalysis>,
        universeName: String = DipSignalEngine.UNIVERSE_OPPORTUNITIES,
        fiveYearCandlesBySymbol: Map<String, List<HistoricalCandle>> = emptyMap(),
    ): PlanBoard {
        var setups = rows.map { row ->
            CrossSignalEngine.evaluate(
                DipRowInput(
                    symbol = row.symbol,
                    companyName = row.companyName,
                    fundamentalsScore = row.fundamentalsScore,
                    marketPriceCents = row.marketPriceCents,
                    streetFairValueCents = row.intrinsicValueCents ?: 0L,
                    analystCoverageCount = row.analystCoverageCount,
                    technicalSignals = row.technicalSignals,
                    candles = yearCandlesBySymbol[row.symbol].orEmpty(),
                    horizonCandles = fiveYearCandlesBySymbol[row.symbol].orEmpty(),
                    dcf = dcfBySymbol[row.symbol],
                ),
            )
        }
        return CrossSignalEngine.rank(setups, universeName)
    }

    fun assemble(
        inputs: List<DipRowInput>,
        universeName: String,
        evaluate: (DipRowInput) -> DipSetup = CrossSignalEngine::evaluate,
    ): PlanBoard {
        var setups = inputs.map(evaluate)
        return CrossSignalEngine.rank(setups, universeName)
    }
}
