package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSignal
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.OpportunityRow
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ViewFilter
import java.math.BigInteger

private const val DCF_OPPORTUNITY_THRESHOLD_BPS = 2_000
private const val DCF_EXPENSIVE_THRESHOLD_BPS = -1_000

data class OpportunityContext(
    val filter: ViewFilter = ViewFilter(),
    val chartSummariesBySymbol: Map<String, Map<ChartRange, ChartRangeSummary>> = emptyMap(),
    val analysesBySymbol: Map<String, DcfAnalysis> = emptyMap(),
    val scoringModel: OpportunityScoringModel = OpportunityScoringModel.Legacy,
)

data class OpportunityScoreBreakdown(
    val fundamentalsScore: Int?,
    val technicalScore: Int?,
    val forecastScore: Int?,
    val compositeScore: Int,
    val coverageCount: Int,
    val fundamentalsSignals: List<String>,
    val technicalSignals: List<String>,
    val forecastSignals: List<String>,
)

object OpportunityEngine {
    fun buildRows(
        reportingEngine: ReportingEngine,
        context: OpportunityContext = OpportunityContext(),
    ): List<OpportunityRow> {
        val rows = reportingEngine
            .filteredRows(reportingEngine.symbolCount().coerceAtLeast(1), context.filter)
            .asSequence()
            .filter { it.isQualified }
            .mapNotNull { candidate ->
                val detail = reportingEngine.detail(candidate.symbol) ?: return@mapNotNull null
                val score = scoreWithModel(
                    detail = detail,
                    summary = preferredChartSummary(context.chartSummariesBySymbol[detail.symbol]),
                    analysis = context.analysesBySymbol[detail.symbol],
                    model = context.scoringModel,
                )
                OpportunityRow(
                    symbol = detail.symbol,
                    marketPriceCents = detail.marketPriceCents,
                    intrinsicValueCents = detail.intrinsicValueCents,
                    gapBps = detail.gapBps,
                    upsideBps = detail.upsideBps,
                    confidence = detail.confidence,
                    isWatched = detail.isWatched,
                    fundamentalsScore = score.fundamentalsScore,
                    technicalScore = score.technicalScore,
                    forecastScore = score.forecastScore,
                    compositeScore = score.compositeScore,
                    coverageCount = score.coverageCount,
                    fundamentalsSignals = score.fundamentalsSignals,
                    technicalSignals = score.technicalSignals,
                    forecastSignals = score.forecastSignals,
                    companyName = detail.companyName,
                )
            }
            .toMutableList()

        rows.sortWith(
            compareByDescending<OpportunityRow> { it.compositeScore }
                .thenByDescending { it.coverageCount }
                .thenByDescending { confidenceRankValue(it.confidence) }
                .thenByDescending { it.upsideBps }
                .thenBy { it.symbol },
        )
        return rows
    }

    fun scoreWithModel(
        detail: SymbolDetail,
        summary: ChartRangeSummary?,
        analysis: DcfAnalysis?,
        model: OpportunityScoringModel,
    ): OpportunityScoreBreakdown {
        val (fundamentalsScore, fundamentalsSignals) = when (model) {
            OpportunityScoringModel.Legacy -> scoreFundamentals(detail)
            OpportunityScoringModel.Aggressive -> aggressiveFundamentalsScore(detail)
        }
        val (technicalScore, technicalSignals) = when (model) {
            OpportunityScoringModel.Legacy -> scoreTechnicals(summary)
            OpportunityScoringModel.Aggressive -> aggressiveTechnicalScore(summary)
        }
        val (forecastScore, forecastSignals) = when (model) {
            OpportunityScoringModel.Legacy -> scoreForecasts(detail, analysis)
            OpportunityScoringModel.Aggressive -> aggressiveForecastScore(detail, analysis)
        }
        val coverageCount = listOf(fundamentalsScore, technicalScore, forecastScore).count { it != null }

        return OpportunityScoreBreakdown(
            fundamentalsScore = fundamentalsScore,
            technicalScore = technicalScore,
            forecastScore = forecastScore,
            compositeScore = (fundamentalsScore ?: 0) + (technicalScore ?: 0) + (forecastScore ?: 0),
            coverageCount = coverageCount,
            fundamentalsSignals = fundamentalsSignals,
            technicalSignals = technicalSignals,
            forecastSignals = forecastSignals,
        )
    }

    fun scoreFundamentals(detail: SymbolDetail): Pair<Int?, List<String>> {
        val fundamentals = detail.fundamentals ?: return null to emptyList()
        var score = 0
        val signals = mutableListOf<String>()

        if ((fundamentals.freeCashFlowDollars ?: 0) > 0) {
            score += 1
            signals += "FCF+"
        }
        if ((fundamentals.operatingCashFlowDollars ?: 0) > 0) {
            score += 1
            signals += "OCF+"
        }
        if ((fundamentals.returnOnEquityBps ?: Int.MIN_VALUE) >= 1_000) {
            score += 1
            signals += "ROE>10"
        }
        val balanceOk = (fundamentals.debtToEquityHundredths?.let { it <= 100 } ?: false) ||
            (
                fundamentals.totalCashDollars != null &&
                    fundamentals.totalDebtDollars != null &&
                    fundamentals.totalCashDollars >= fundamentals.totalDebtDollars
                )
        if (balanceOk) {
            score += 1
            signals += "Balance"
        }
        if ((fundamentals.earningsGrowthBps ?: 0) > 0) {
            score += 1
            signals += "Growth+"
        }
        return score to signals
    }

    fun scoreTechnicals(summary: ChartRangeSummary?): Pair<Int?, List<String>> {
        summary ?: return null to emptyList()
        val latestCloseCents = summary.latestCloseCents ?: return 0 to emptyList()
        var score = 0
        val signals = mutableListOf<String>()

        if (summary.ema20Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA20"
        }
        if (summary.ema50Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA50"
        }
        if (summary.ema200Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA200"
        }
        if (summary.ema20Cents != null && summary.ema50Cents != null && summary.ema20Cents > summary.ema50Cents) {
            score += 1
            signals += "EMA20>50"
        }
        if (
            (summary.macdCents != null && summary.signalCents != null && summary.macdCents > summary.signalCents) ||
            (summary.histogramCents?.let { it > 0 } == true)
        ) {
            score += 1
            signals += "MACD+"
        }
        return score to signals
    }

    fun scoreForecasts(detail: SymbolDetail, analysis: DcfAnalysis?): Pair<Int?, List<String>> {
        var available = false
        var score = 0
        val signals = mutableListOf<String>()

        if (detail.externalStatus == ExternalSignalStatus.Supportive) {
            available = true
            score += 1
            signals += "Supportive"
        }
        if ((detail.analystOpinionCount ?: 0) >= 5) {
            available = true
            score += 1
            signals += "5+Analysts"
        }
        if (detail.recommendationMeanHundredths?.let { it <= 200 } == true) {
            available = true
            score += 1
            signals += "Rec<=2.0"
        }
        detail.weightedExternalSignalFairValueCents?.let { weightedFairValue ->
            available = true
            if ((checkedUpsideBps(detail.marketPriceCents, weightedFairValue) ?: 0) >= 3_000) {
                score += 1
                signals += "Weighted+"
            }
        }
        if (analysis != null) {
            available = true
            when (dcfSignal(analysis, detail.marketPriceCents)) {
                DcfSignal.Opportunity -> {
                    score += 1
                    signals += "DCF+"
                }
                DcfSignal.Expensive -> {
                    score -= 1
                    signals += "DCF-"
                }
                DcfSignal.Fair -> Unit
            }
        }
        return if (available) score to signals else null to emptyList()
    }

    private fun aggressiveFundamentalsScore(detail: SymbolDetail): Pair<Int?, List<String>> {
        val fundamentals = detail.fundamentals ?: return null to emptyList()
        var score = 0
        val signals = mutableListOf<String>()

        if ((fundamentals.freeCashFlowDollars ?: 0) > 0) {
            score += 2
            signals += "FCF+2"
        } else {
            score -= 2
            signals += "FCF-2"
        }
        if ((fundamentals.operatingCashFlowDollars ?: 0) > 0) {
            score += 1
            signals += "OCF+1"
        } else {
            score -= 1
            signals += "OCF-1"
        }

        val roeBps = fundamentals.returnOnEquityBps ?: 0
        when {
            roeBps >= 2_000 -> {
                score += 2
                signals += "ROE20+"
            }
            roeBps >= 1_000 -> {
                score += 1
                signals += "ROE10+"
            }
            roeBps < 0 -> {
                score -= 2
                signals += "ROE-"
            }
        }

        val balanceOk = (fundamentals.debtToEquityHundredths?.let { it <= 100 } ?: false) ||
            (
                fundamentals.totalCashDollars != null &&
                    fundamentals.totalDebtDollars != null &&
                    fundamentals.totalCashDollars >= fundamentals.totalDebtDollars
                )
        if (balanceOk) {
            score += 2
            signals += "Balance+2"
        } else {
            score -= 2
            signals += "Balance-2"
        }

        val growthBps = fundamentals.earningsGrowthBps ?: 0
        when {
            growthBps >= 1_000 -> {
                score += 2
                signals += "Growth10+"
            }
            growthBps > 0 -> {
                score += 1
                signals += "Growth+"
            }
            growthBps < 0 -> {
                score -= 2
                signals += "Growth-"
            }
        }

        return score to signals
    }

    private fun aggressiveTechnicalScore(summary: ChartRangeSummary?): Pair<Int?, List<String>> {
        summary ?: return null to emptyList()
        val latestCloseCents = summary.latestCloseCents ?: return 0 to emptyList()
        var score = 0
        val signals = mutableListOf<String>()

        if (summary.ema20Cents?.let { latestCloseCents > it } == true) {
            score += 2
            signals += ">EMA20+2"
        } else if (summary.ema20Cents != null) {
            score -= 2
            signals += "<EMA20-2"
        }
        if (summary.ema50Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA50+1"
        }
        if (summary.ema200Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA200+1"
        }
        if (summary.ema20Cents != null && summary.ema50Cents != null && summary.ema20Cents > summary.ema50Cents) {
            score += 1
            signals += "EMA20>50"
        }
        if (
            (summary.macdCents != null && summary.signalCents != null && summary.macdCents > summary.signalCents) ||
            (summary.histogramCents?.let { it > 0 } == true)
        ) {
            score += 1
            signals += "MACD+"
        } else if (summary.histogramCents != null || summary.macdCents != null) {
            score -= 2
            signals += "MACD-"
        }

        return score to signals
    }

    private fun aggressiveForecastScore(detail: SymbolDetail, analysis: DcfAnalysis?): Pair<Int?, List<String>> {
        var available = false
        var score = 0
        val signals = mutableListOf<String>()

        when (detail.externalStatus) {
            ExternalSignalStatus.Supportive -> {
                available = true
                score += 2
                signals += "Support+2"
            }
            ExternalSignalStatus.Divergent -> {
                available = true
                score -= 2
                signals += "Divergent-2"
            }
            ExternalSignalStatus.Stale, ExternalSignalStatus.Missing -> Unit
        }

        val analystCount = detail.analystOpinionCount ?: 0
        when {
            analystCount >= 10 -> {
                available = true
                score += 2
                signals += "Analysts10+"
            }
            analystCount >= 5 -> {
                available = true
                score += 1
                signals += "Analysts5+"
            }
        }

        detail.recommendationMeanHundredths?.let { recommendation ->
            available = true
            when {
                recommendation <= 170 -> {
                    score += 2
                    signals += "Rec1.7+"
                }
                recommendation <= 220 -> {
                    score += 1
                    signals += "Rec2.2+"
                }
                recommendation >= 300 -> {
                    score -= 2
                    signals += "Rec3.0-"
                }
            }
        }

        detail.weightedExternalSignalFairValueCents?.let { weightedFairValue ->
            available = true
            when (val upsideBps = checkedUpsideBps(detail.marketPriceCents, weightedFairValue) ?: 0) {
                in 5_000..Int.MAX_VALUE -> {
                    score += 3
                    signals += "Weighted50+"
                }
                in 3_000..4_999 -> {
                    score += 2
                    signals += "Weighted30+"
                }
                in Int.MIN_VALUE..-1 -> {
                    score -= 2
                    signals += "Weighted-"
                }
            }
        }

        if (analysis != null) {
            available = true
            when (val marginBps = dcfMarginOfSafetyBps(analysis, detail.marketPriceCents) ?: 0) {
                in 4_000..Int.MAX_VALUE -> {
                    score += 4
                    signals += "DCF40+"
                }
                in 2_000..3_999 -> {
                    score += 2
                    signals += "DCF20+"
                }
                in Int.MIN_VALUE..-1_001 -> {
                    score -= 3
                    signals += "DCF-"
                }
            }
        }

        return if (available) score to signals else null to emptyList()
    }

    fun dcfSignal(analysis: DcfAnalysis, marketPriceCents: Long): DcfSignal = when {
        (dcfMarginOfSafetyBps(analysis, marketPriceCents) ?: Int.MIN_VALUE) >= DCF_OPPORTUNITY_THRESHOLD_BPS -> DcfSignal.Opportunity
        (dcfMarginOfSafetyBps(analysis, marketPriceCents) ?: Int.MIN_VALUE) < DCF_EXPENSIVE_THRESHOLD_BPS -> DcfSignal.Expensive
        else -> DcfSignal.Fair
    }

    private fun dcfMarginOfSafetyBps(analysis: DcfAnalysis, marketPriceCents: Long): Int? {
        if (analysis.baseIntrinsicValueCents <= 0 || marketPriceCents <= 0) {
            return null
        }
        val intrinsic = BigInteger.valueOf(analysis.baseIntrinsicValueCents)
        val market = BigInteger.valueOf(marketPriceCents)
        val scaled = ((intrinsic - market) * BigInteger.valueOf(10_000L)) / intrinsic
        return scaled.coerceIn(BigInteger.valueOf(Int.MIN_VALUE.toLong()), BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }

    private fun preferredChartSummary(summaries: Map<ChartRange, ChartRangeSummary>?): ChartRangeSummary? {
        summaries ?: return null
        return summaries[ChartRange.Year] ?: summaries.values.maxByOrNull { it.candleCount }
    }

    private fun confidenceRankValue(confidence: ConfidenceBand): Double = when (confidence) {
        ConfidenceBand.Low -> 0.0
        ConfidenceBand.Provisional -> 1.0
        ConfidenceBand.High -> 2.0
    }

    private fun roundedDivision(numerator: java.math.BigInteger, denominator: java.math.BigInteger): java.math.BigInteger {
        if (denominator == java.math.BigInteger.ZERO) return java.math.BigInteger.ZERO
        val quotient = numerator / denominator
        val remainder = numerator % denominator
        val doubled = remainder.abs() * BigInteger.valueOf(2L)
        return if (doubled >= denominator.abs()) {
            quotient + if (numerator.signum() == denominator.signum()) java.math.BigInteger.ONE else java.math.BigInteger.ONE.negate()
        } else {
            quotient
        }
    }
}
