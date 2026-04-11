package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSignal
import com.discountscreener.core.model.ExternalSignalStatus
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
                val (fundamentalsScore, fundamentalsSignals) = scoreFundamentals(detail)
                val (technicalScore, technicalSignals) = scoreTechnicals(
                    preferredChartSummary(context.chartSummariesBySymbol[detail.symbol]),
                )
                val (forecastScore, forecastSignals) = scoreForecasts(
                    detail = detail,
                    analysis = context.analysesBySymbol[detail.symbol],
                )
                val coverageCount = listOf(fundamentalsScore, technicalScore, forecastScore).count { it != null }
                OpportunityRow(
                    symbol = detail.symbol,
                    marketPriceCents = detail.marketPriceCents,
                    intrinsicValueCents = detail.intrinsicValueCents,
                    gapBps = detail.gapBps,
                    upsideBps = detail.upsideBps,
                    confidence = detail.confidence,
                    isWatched = detail.isWatched,
                    fundamentalsScore = fundamentalsScore,
                    technicalScore = technicalScore,
                    forecastScore = forecastScore,
                    compositeScore = (fundamentalsScore ?: 0) + (technicalScore ?: 0) + (forecastScore ?: 0),
                    coverageCount = coverageCount,
                    fundamentalsSignals = fundamentalsSignals,
                    technicalSignals = technicalSignals,
                    forecastSignals = forecastSignals,
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
