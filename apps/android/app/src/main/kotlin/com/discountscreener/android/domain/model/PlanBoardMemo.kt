package com.discountscreener.android.domain.model

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.plan.CrossSignalEngine
import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.DipSetup
import com.discountscreener.core.plan.DipSignalEngine
import com.discountscreener.core.plan.LeftoverSignalEngine
import com.discountscreener.core.plan.PlanBoard

/**
 * Reuses leftover and dip boards when the input fingerprint is unchanged.
 * Snapshot rebuilds were re-running MACD on every symbol.
 */
class PlanBoardMemo {
    private var leftoverKey: String? = null
    private var leftoverBoard: PlanBoard? = null
    private var dipOppsKey: String? = null
    private var dipOppsBoard: PlanBoard? = null
    private var dipProfileKey: String? = null
    private var dipProfileBoard: PlanBoard? = null
    private var crossOppsKey: String? = null
    private var crossOppsBoard: PlanBoard? = null
    private var crossProfileKey: String? = null
    private var crossProfileBoard: PlanBoard? = null

    fun leftover(
        inputs: List<DipRowInput>,
        universeName: String,
        evaluate: (DipRowInput) -> DipSetup = LeftoverSignalEngine::evaluate,
    ): PlanBoard {
        var key = fingerprint(universeName, inputs)
        leftoverBoard?.let { cached ->
            if (leftoverKey == key) return cached
        }
        var board = LeftoverBoardAssembler.assemble(inputs, universeName, evaluate)
        leftoverKey = key
        leftoverBoard = board
        return board
    }

    fun dipOpportunities(
        rows: List<OpportunityListRow>,
        yearCandlesBySymbol: Map<String, List<HistoricalCandle>>,
        fiveYearCandlesBySymbol: Map<String, List<HistoricalCandle>>,
        dcfBySymbol: Map<String, DcfAnalysis>,
    ): PlanBoard {
        var key = opportunityFingerprint(
            universeName = "opps",
            rows = rows,
            yearCandlesBySymbol = yearCandlesBySymbol,
            fiveYearCandlesBySymbol = fiveYearCandlesBySymbol,
            dcfBySymbol = dcfBySymbol,
        )
        dipOppsBoard?.let { cached ->
            if (dipOppsKey == key) return cached
        }
        var board = PlanBoardAssembler.assemble(
            rows = rows,
            yearCandlesBySymbol = yearCandlesBySymbol,
            fiveYearCandlesBySymbol = fiveYearCandlesBySymbol,
            dcfBySymbol = dcfBySymbol,
        )
        dipOppsKey = key
        dipOppsBoard = board
        return board
    }

    fun dipProfile(
        inputs: List<DipRowInput>,
        universeName: String,
        evaluate: (DipRowInput) -> DipSetup = DipSignalEngine::evaluate,
    ): PlanBoard {
        var key = fingerprint(universeName, inputs)
        dipProfileBoard?.let { cached ->
            if (dipProfileKey == key) return cached
        }
        var board = PlanBoardAssembler.assemble(inputs = inputs, universeName = universeName, evaluate = evaluate)
        dipProfileKey = key
        dipProfileBoard = board
        return board
    }

    fun crossOpportunities(
        rows: List<OpportunityListRow>,
        yearCandlesBySymbol: Map<String, List<HistoricalCandle>>,
        fiveYearCandlesBySymbol: Map<String, List<HistoricalCandle>>,
        dcfBySymbol: Map<String, DcfAnalysis>,
    ): PlanBoard {
        var key = opportunityFingerprint(
            universeName = "opps",
            rows = rows,
            yearCandlesBySymbol = yearCandlesBySymbol,
            fiveYearCandlesBySymbol = fiveYearCandlesBySymbol,
            dcfBySymbol = dcfBySymbol,
        )
        crossOppsBoard?.let { cached ->
            if (crossOppsKey == key) return cached
        }
        var board = CrossBoardAssembler.assemble(
            rows = rows,
            yearCandlesBySymbol = yearCandlesBySymbol,
            fiveYearCandlesBySymbol = fiveYearCandlesBySymbol,
            dcfBySymbol = dcfBySymbol,
        )
        crossOppsKey = key
        crossOppsBoard = board
        return board
    }

    fun crossProfile(
        inputs: List<DipRowInput>,
        universeName: String,
        evaluate: (DipRowInput) -> DipSetup = CrossSignalEngine::evaluate,
    ): PlanBoard {
        var key = fingerprint(universeName, inputs)
        crossProfileBoard?.let { cached ->
            if (crossProfileKey == key) return cached
        }
        var board = CrossBoardAssembler.assemble(inputs = inputs, universeName = universeName, evaluate = evaluate)
        crossProfileKey = key
        crossProfileBoard = board
        return board
    }

    fun clear() {
        leftoverKey = null
        leftoverBoard = null
        dipOppsKey = null
        dipOppsBoard = null
        dipProfileKey = null
        dipProfileBoard = null
        crossOppsKey = null
        crossOppsBoard = null
        crossProfileKey = null
        crossProfileBoard = null
    }

    companion object {
        fun fingerprint(universeName: String, inputs: List<DipRowInput>): String {
            return buildString(universeName.length + inputs.size * 48) {
                append(universeName)
                inputs.forEach { input ->
                    append('\u0001')
                    append(input.symbol)
                    append('\u0002')
                    append(input.marketPriceCents)
                    append('\u0002')
                    append(input.streetFairValueCents)
                    append('\u0002')
                    append(input.fundamentalsScore ?: Int.MIN_VALUE)
                    append('\u0002')
                    append(input.analystCoverageCount ?: Int.MIN_VALUE)
                    append('\u0002')
                    append(input.technicalSignals.joinToString(","))
                    appendCandleTail(input.candles)
                    appendCandleTail(input.horizonCandles)
                    appendDcf(input.dcf)
                }
            }
        }

        fun opportunityFingerprint(
            universeName: String,
            rows: List<OpportunityListRow>,
            yearCandlesBySymbol: Map<String, List<HistoricalCandle>>,
            fiveYearCandlesBySymbol: Map<String, List<HistoricalCandle>>,
            dcfBySymbol: Map<String, DcfAnalysis>,
        ): String {
            return buildString(universeName.length + rows.size * 48) {
                append(universeName)
                rows.forEach { row ->
                    append('\u0001')
                    append(row.symbol)
                    append('\u0002')
                    append(row.marketPriceCents)
                    append('\u0002')
                    append(row.intrinsicValueCents)
                    append('\u0002')
                    append(row.fundamentalsScore ?: Int.MIN_VALUE)
                    append('\u0002')
                    append(row.analystCoverageCount ?: Int.MIN_VALUE)
                    append('\u0002')
                    append(row.technicalSignals.joinToString(","))
                    appendCandleTail(yearCandlesBySymbol[row.symbol].orEmpty())
                    appendCandleTail(fiveYearCandlesBySymbol[row.symbol].orEmpty())
                    appendDcf(dcfBySymbol[row.symbol])
                }
            }
        }

        private fun StringBuilder.appendCandleTail(candles: List<HistoricalCandle>) {
            var last = candles.lastOrNull()
            append('\u0002')
            append(candles.size)
            append('\u0002')
            append(last?.epochSeconds ?: 0L)
            append('\u0002')
            append(last?.closeCents ?: 0L)
            append('\u0002')
            append(last?.volume ?: 0L)
        }

        private fun StringBuilder.appendDcf(dcf: DcfAnalysis?) {
            append('\u0002')
            append(dcf?.baseIntrinsicValueCents ?: 0L)
            append('\u0002')
            append(dcf?.engineVersion.orEmpty())
            append('\u0002')
            append(dcf?.modelPolicyVersion.orEmpty())
        }
    }
}
