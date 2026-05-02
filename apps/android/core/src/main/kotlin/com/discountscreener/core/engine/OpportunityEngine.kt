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
import kotlin.math.exp
import kotlin.math.roundToInt

private const val DCF_OPPORTUNITY_THRESHOLD_BPS = 2_000
private const val DCF_EXPENSIVE_THRESHOLD_BPS = -1_000

// AggressiveV2 tuning constants. Centralised so future calibration changes one place.
private const val V2_FUND_FCF_YIELD_LOWER = -0.02
private const val V2_FUND_FCF_YIELD_UPPER = 0.08
private const val V2_FUND_FCF_WEIGHT = 25.0
private const val V2_FUND_OCF_FALLBACK_WEIGHT = 10.0
private const val V2_FUND_ROE_LOWER_BPS = 0.0
private const val V2_FUND_ROE_UPPER_BPS = 2_000.0
private const val V2_FUND_ROE_WEIGHT = 20.0
private const val V2_FUND_GROWTH_LOWER_BPS = -500.0
private const val V2_FUND_GROWTH_UPPER_BPS = 1_500.0
private const val V2_FUND_GROWTH_WEIGHT = 15.0
private const val V2_FUND_BALANCE_DE_LOW = 30.0
private const val V2_FUND_BALANCE_DE_HIGH = 200.0
private const val V2_FUND_BALANCE_WEIGHT = 20.0
private const val V2_FUND_PE_LOW = 800.0
private const val V2_FUND_PE_HIGH = 3_500.0
private const val V2_FUND_PE_WEIGHT = 20.0

private const val V2_TECH_TREND_DELTA_BOUND = 0.10
private const val V2_TECH_TREND_20_50_WEIGHT = 24.0
private const val V2_TECH_TREND_50_200_WEIGHT = 21.0
private const val V2_TECH_TREND_PRICE_20_WEIGHT = 15.0
private const val V2_TECH_HISTOGRAM_BOUND = 0.005
private const val V2_TECH_HISTOGRAM_WEIGHT = 25.0
private const val V2_TECH_MACD_DIRECTION_WEIGHT = 15.0

private const val V2_FORECAST_UPSIDE_LOWER_BPS = -2_000.0
private const val V2_FORECAST_UPSIDE_UPPER_BPS = 5_000.0
private const val V2_FORECAST_VALUATION_WEIGHT = 50.0
private const val V2_FORECAST_REC_LOW_HUNDREDTHS = 150.0
private const val V2_FORECAST_REC_HIGH_HUNDREDTHS = 300.0
private const val V2_FORECAST_REC_WEIGHT = 15.0
private const val V2_FORECAST_MIN_ANALYST_OPINIONS = 3
private const val V2_FORECAST_FULL_ANALYST_OPINIONS = 15.0
private const val V2_FORECAST_BREADTH_WEIGHT = 20.0
private const val V2_FORECAST_UNCERTAINTY_BOUND = 0.6
private const val V2_FORECAST_UNCERTAINTY_WEIGHT = 10.0
private const val V2_FORECAST_FRESHNESS_WEIGHT = 5.0
private const val V2_FORECAST_FRESHNESS_HALF_LIFE_SECONDS = 14.0 * 86_400.0
private const val V2_FORECAST_DCF_RELIABILITY = 0.75
private const val V2_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT = 25.0

private const val V2_FUNDAMENTALS_FULL_WEIGHT = 100.0
private const val V2_TECHNICALS_FULL_WEIGHT = 100.0
private const val V2_FORECAST_FULL_WEIGHT =
    V2_FORECAST_VALUATION_WEIGHT +
        V2_FORECAST_REC_WEIGHT +
        V2_FORECAST_BREADTH_WEIGHT +
        V2_FORECAST_UNCERTAINTY_WEIGHT +
        V2_FORECAST_FRESHNESS_WEIGHT

private const val V2_COMPOSITE_COVERAGE_BONUS = 5
private const val V2_COMPOSITE_BOUND = 110

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
            OpportunityScoringModel.AggressiveV2 -> aggressiveV2FundamentalsScore(detail)
        }
        val (technicalScore, technicalSignals) = when (model) {
            OpportunityScoringModel.Legacy -> scoreTechnicals(summary)
            OpportunityScoringModel.Aggressive -> aggressiveTechnicalScore(summary)
            OpportunityScoringModel.AggressiveV2 -> aggressiveV2TechnicalScore(summary)
        }
        val (forecastScore, forecastSignals) = when (model) {
            OpportunityScoringModel.Legacy -> scoreForecasts(detail, analysis)
            OpportunityScoringModel.Aggressive -> aggressiveForecastScore(detail, analysis)
            OpportunityScoringModel.AggressiveV2 -> aggressiveV2ForecastScore(detail, analysis)
        }
        val coverageCount = listOf(fundamentalsScore, technicalScore, forecastScore).count { it != null }
        val composite = compositeScoreFor(model, fundamentalsScore, technicalScore, forecastScore, coverageCount)

        return OpportunityScoreBreakdown(
            fundamentalsScore = fundamentalsScore,
            technicalScore = technicalScore,
            forecastScore = forecastScore,
            compositeScore = composite,
            coverageCount = coverageCount,
            fundamentalsSignals = fundamentalsSignals,
            technicalSignals = technicalSignals,
            forecastSignals = forecastSignals,
        )
    }

    private fun compositeScoreFor(
        model: OpportunityScoringModel,
        fundamentals: Int?,
        technical: Int?,
        forecast: Int?,
        coverageCount: Int,
    ): Int = when (model) {
        OpportunityScoringModel.Legacy,
        OpportunityScoringModel.Aggressive,
        -> (fundamentals ?: 0) + (technical ?: 0) + (forecast ?: 0)

        OpportunityScoringModel.AggressiveV2 -> {
            if (coverageCount == 0) {
                0
            } else {
                val sum = (fundamentals ?: 0) + (technical ?: 0) + (forecast ?: 0)
                val mean = sum.toDouble() / coverageCount.toDouble()
                val bonus = V2_COMPOSITE_COVERAGE_BONUS * (coverageCount - 1)
                (mean + bonus).roundToInt().coerceIn(-V2_COMPOSITE_BOUND, V2_COMPOSITE_BOUND)
            }
        }
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

    // ----------------------------------------------------------------------------------
    // AggressiveV2 scoring model.
    //
    // Design contract:
    //  * Each bucket sub-score is normalised to [-100, +100] against the bucket's full
    //    evidence budget. Missing data does not become a negative signal, but sparse buckets
    //    also cannot saturate to 100 from a single positive datapoint.
    //  * Each sub-signal returns a smooth ramp in [-1, +1] between an explicit lower and
    //    upper bound, so there are no cliff transitions at thresholds (e.g. FCF $0).
    //  * Correlated signals are collapsed into one weighted contribution to avoid the
    //    triple-counting that V1 had with EMAs and the analyst-rating + weighted-upside
    //    + DCF margin trio.
    //  * Composite uses the model-aware coverage-weighted mean (see compositeScoreFor),
    //    so a symbol with one rich bucket cannot leapfrog a symbol with three sound
    //    buckets purely on raw bucket-scale headroom.
    // ----------------------------------------------------------------------------------

    internal fun aggressiveV2FundamentalsScore(detail: SymbolDetail): Pair<Int?, List<String>> {
        val fundamentals = detail.fundamentals ?: return null to emptyList()
        val acc = EvidenceAccumulator(V2_FUNDAMENTALS_FULL_WEIGHT)

        // Free cash flow yield (FCF / market cap). Falls back to OCF positivity only when
        // FCF is missing, so cash-flow gets one vote per symbol (no FCF/OCF double count).
        val fcfDollars = fundamentals.freeCashFlowDollars
        val marketCapDollars = fundamentals.marketCapDollars
        when {
            fcfDollars != null && marketCapDollars != null && marketCapDollars > 0L -> {
                val yieldFraction = fcfDollars.toDouble() / marketCapDollars.toDouble()
                acc.add(V2_FUND_FCF_WEIGHT, smoothRamp(yieldFraction, V2_FUND_FCF_YIELD_LOWER, V2_FUND_FCF_YIELD_UPPER), "FCFy")
            }
            fcfDollars != null -> {
                // No market cap -> grade FCF on sign alone.
                acc.add(V2_FUND_FCF_WEIGHT, if (fcfDollars > 0L) 1.0 else -1.0, "FCF")
            }
            else -> {
                val ocfDollars = fundamentals.operatingCashFlowDollars
                if (ocfDollars != null) {
                    acc.add(V2_FUND_OCF_FALLBACK_WEIGHT, if (ocfDollars > 0L) 1.0 else -1.0, "OCF")
                }
            }
        }

        fundamentals.returnOnEquityBps?.let { roeBps ->
            acc.add(V2_FUND_ROE_WEIGHT, smoothRamp(roeBps.toDouble(), V2_FUND_ROE_LOWER_BPS, V2_FUND_ROE_UPPER_BPS), "ROE")
        }

        fundamentals.earningsGrowthBps?.let { growthBps ->
            acc.add(V2_FUND_GROWTH_WEIGHT, smoothRamp(growthBps.toDouble(), V2_FUND_GROWTH_LOWER_BPS, V2_FUND_GROWTH_UPPER_BPS), "Growth")
        }

        // Balance: prefer D/E, fall back to cash >= debt.
        val deHundredths = fundamentals.debtToEquityHundredths
        if (deHundredths != null) {
            // Lower D/E is better -> negate the ramp.
            acc.add(V2_FUND_BALANCE_WEIGHT, -smoothRamp(deHundredths.toDouble(), V2_FUND_BALANCE_DE_LOW, V2_FUND_BALANCE_DE_HIGH), "D/E")
        } else {
            val cash = fundamentals.totalCashDollars
            val debt = fundamentals.totalDebtDollars
            if (cash != null && debt != null) {
                acc.add(V2_FUND_BALANCE_WEIGHT, if (cash >= debt) 1.0 else -0.5, "Bal")
            }
        }

        // Forward P/E (cheaper is better). Skip when negative or zero (loss-making, already
        // captured upstream by FCF / ROE and would otherwise mislead the ramp).
        fundamentals.forwardPeHundredths?.takeIf { it > 0 }?.let { peHundredths ->
            acc.add(V2_FUND_PE_WEIGHT, -smoothRamp(peHundredths.toDouble(), V2_FUND_PE_LOW, V2_FUND_PE_HIGH), "FwdPE")
        }

        return acc.normalizedScore() to acc.signals
    }

    internal fun aggressiveV2TechnicalScore(summary: ChartRangeSummary?): Pair<Int?, List<String>> {
        summary ?: return null to emptyList()
        val latestCloseCents = summary.latestCloseCents ?: return null to emptyList()
        val acc = EvidenceAccumulator(V2_TECHNICALS_FULL_WEIGHT)

        // Trend stack: three correlated EMA deltas, but each carries a sub-weight that
        // sums to 60. A clean uptrend can score the full +60, but partial alignment
        // contributes proportionally instead of ticking three independent boolean checkboxes.
        summary.ema20Cents?.takeIf { it > 0 }?.let { ema20 ->
            val delta = (latestCloseCents - ema20).toDouble() / ema20.toDouble()
            acc.add(V2_TECH_TREND_PRICE_20_WEIGHT, smoothRamp(delta, -V2_TECH_TREND_DELTA_BOUND, V2_TECH_TREND_DELTA_BOUND), "Px/20")
        }
        if (summary.ema20Cents != null && summary.ema50Cents != null && summary.ema50Cents > 0) {
            val delta = (summary.ema20Cents - summary.ema50Cents).toDouble() / summary.ema50Cents.toDouble()
            acc.add(V2_TECH_TREND_20_50_WEIGHT, smoothRamp(delta, -V2_TECH_TREND_DELTA_BOUND, V2_TECH_TREND_DELTA_BOUND), "20/50")
        }
        if (summary.ema50Cents != null && summary.ema200Cents != null && summary.ema200Cents > 0) {
            val delta = (summary.ema50Cents - summary.ema200Cents).toDouble() / summary.ema200Cents.toDouble()
            acc.add(V2_TECH_TREND_50_200_WEIGHT, smoothRamp(delta, -V2_TECH_TREND_DELTA_BOUND, V2_TECH_TREND_DELTA_BOUND), "50/200")
        }

        // Histogram momentum (continuous, magnitude-aware).
        if (summary.histogramCents != null && latestCloseCents > 0) {
            val ratio = summary.histogramCents.toDouble() / latestCloseCents.toDouble()
            acc.add(V2_TECH_HISTOGRAM_WEIGHT, smoothRamp(ratio, -V2_TECH_HISTOGRAM_BOUND, V2_TECH_HISTOGRAM_BOUND), "Hist")
        }

        // MACD direction confirmation: only consumed when both lines are present, otherwise
        // histogram already covered the momentum side.
        if (summary.macdCents != null && summary.signalCents != null) {
            val direction = when {
                summary.macdCents > summary.signalCents -> 1.0
                summary.macdCents < summary.signalCents -> -1.0
                else -> 0.0
            }
            acc.add(V2_TECH_MACD_DIRECTION_WEIGHT, direction, "MACD")
        }

        return acc.normalizedScore() to acc.signals
    }

    internal fun aggressiveV2ForecastScore(detail: SymbolDetail, analysis: DcfAnalysis?): Pair<Int?, List<String>> {
        val acc = EvidenceAccumulator(V2_FORECAST_FULL_WEIGHT)
        val sufficiencySignals = mutableListOf<String>()
        var reliableEvidenceWeight = 0.0
        var hasValuationAnchor = false

        val targetFairValue = preferredForecastFairValueCents(detail)
        val targetCount = targetAnalystCount(detail)
        val recommendationCount = recommendationAnalystCount(detail)
        val broadestAnalystCount = listOfNotNull(targetCount, recommendationCount).maxOrNull()
        val externalFreshness = freshnessMultiplier(detail)
        val externalStatusReliability = externalStatusReliability(detail.externalStatus)

        val valuationInputs = mutableListOf<WeightedForecastRamp>()
        targetFairValue?.let { fairValue ->
            val targetUpsideBps = checkedUpsideBps(detail.marketPriceCents, fairValue)
            val targetReliability = analystCoverageReliability(targetCount) * externalFreshness * externalStatusReliability
            when {
                targetUpsideBps == null -> Unit
                !hasSufficientAnalystCoverage(targetCount) -> {
                    sufficiencySignals += if (targetCount == null) "Cov?" else "Cov<${V2_FORECAST_MIN_ANALYST_OPINIONS}"
                }
                targetReliability > 0.0 -> {
                    valuationInputs += WeightedForecastRamp(
                        ramp = smoothRamp(targetUpsideBps.toDouble(), V2_FORECAST_UPSIDE_LOWER_BPS, V2_FORECAST_UPSIDE_UPPER_BPS),
                        reliability = targetReliability,
                    )
                }
            }
        }

        analysis?.let { dcf ->
            dcfMarginOfSafetyBps(dcf, detail.marketPriceCents)?.let { marginBps ->
                valuationInputs += WeightedForecastRamp(
                    ramp = smoothRamp(marginBps.toDouble(), V2_FORECAST_UPSIDE_LOWER_BPS, V2_FORECAST_UPSIDE_UPPER_BPS),
                    reliability = V2_FORECAST_DCF_RELIABILITY,
                )
            }
        }

        if (valuationInputs.isNotEmpty()) {
            val reliabilitySum = valuationInputs.sumOf { it.reliability }
            if (reliabilitySum > 0.0) {
                val blendedRamp = valuationInputs.sumOf { it.ramp * it.reliability } / reliabilitySum
                val weight = V2_FORECAST_VALUATION_WEIGHT * reliabilitySum.coerceAtMost(1.0)
                acc.add(weight, blendedRamp, "Val")
                reliableEvidenceWeight += weight
                hasValuationAnchor = true
            }
        }

        detail.recommendationMeanHundredths?.let { rec ->
            val recReliability = analystCoverageReliability(recommendationCount) * externalFreshness * externalStatusReliability
            if (!hasSufficientAnalystCoverage(recommendationCount)) {
                sufficiencySignals += if (recommendationCount == null) "RecCov?" else "RecCov<${V2_FORECAST_MIN_ANALYST_OPINIONS}"
            } else if (recReliability > 0.0) {
                val weight = V2_FORECAST_REC_WEIGHT * recReliability
                acc.add(weight, -smoothRamp(rec.toDouble(), V2_FORECAST_REC_LOW_HUNDREDTHS, V2_FORECAST_REC_HIGH_HUNDREDTHS), "Rec")
                reliableEvidenceWeight += weight
            }
        }

        broadestAnalystCount?.let { count ->
            acc.add(V2_FORECAST_BREADTH_WEIGHT, analystBreadthRamp(count), "Cov")
            reliableEvidenceWeight += V2_FORECAST_BREADTH_WEIGHT * analystCoverageReliability(count)
        }

        val targetReliabilityWithoutFreshness = analystCoverageReliability(targetCount) * externalStatusReliability
        val low = detail.externalSignalLowFairValueCents
        val high = detail.externalSignalHighFairValueCents
        val centre = targetFairValue
        if (low != null && high != null && centre != null && centre > 0L && high > low && targetReliabilityWithoutFreshness > 0.0) {
            val spreadFraction = (high - low).toDouble() / centre.toDouble()
            val weight = V2_FORECAST_UNCERTAINTY_WEIGHT * targetReliabilityWithoutFreshness
            acc.add(weight, -smoothRamp(spreadFraction, 0.0, V2_FORECAST_UNCERTAINTY_BOUND), "Unc")
            reliableEvidenceWeight += weight * externalFreshness
        }

        if (targetFairValue != null && hasSufficientAnalystCoverage(targetCount)) {
            val weight = V2_FORECAST_FRESHNESS_WEIGHT * analystCoverageReliability(targetCount)
            acc.add(weight, freshnessRamp(externalFreshness), "Fresh")
            reliableEvidenceWeight += weight * externalFreshness
        }

        val signals = (acc.signals + sufficiencySignals).distinct()
        if (!hasValuationAnchor || reliableEvidenceWeight < V2_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT) {
            return null to signals
        }

        val raw = acc.normalizedScore() ?: return null to signals
        return raw.coerceIn(-100, 100) to signals
    }

    private data class WeightedForecastRamp(
        val ramp: Double,
        val reliability: Double,
    )

    private fun preferredForecastFairValueCents(detail: SymbolDetail): Long? =
        detail.weightedExternalSignalFairValueCents ?: detail.externalSignalFairValueCents

    private fun targetAnalystCount(detail: SymbolDetail): Int? = when {
        detail.weightedExternalSignalFairValueCents != null -> detail.weightedAnalystCount ?: detail.analystOpinionCount
        detail.externalSignalFairValueCents != null -> detail.analystOpinionCount
        else -> null
    }

    private fun recommendationAnalystCount(detail: SymbolDetail): Int? {
        val trendCount = listOfNotNull(
            detail.strongBuyCount,
            detail.buyCount,
            detail.holdCount,
            detail.sellCount,
            detail.strongSellCount,
        ).sum().takeIf { it > 0 }
        return listOfNotNull(detail.analystOpinionCount, trendCount).maxOrNull()
    }

    private fun hasSufficientAnalystCoverage(count: Int?): Boolean =
        count != null && count >= V2_FORECAST_MIN_ANALYST_OPINIONS

    private fun analystCoverageReliability(count: Int?): Double {
        if (!hasSufficientAnalystCoverage(count)) return 0.0
        val progress = ((count!!.toDouble() - V2_FORECAST_MIN_ANALYST_OPINIONS.toDouble()) /
            (V2_FORECAST_FULL_ANALYST_OPINIONS - V2_FORECAST_MIN_ANALYST_OPINIONS.toDouble()))
            .coerceIn(0.0, 1.0)
        return 0.35 + (0.65 * progress)
    }

    private fun analystBreadthRamp(count: Int): Double {
        if (count < V2_FORECAST_MIN_ANALYST_OPINIONS) return -1.0
        val progress = ((count.toDouble() - V2_FORECAST_MIN_ANALYST_OPINIONS.toDouble()) /
            (V2_FORECAST_FULL_ANALYST_OPINIONS - V2_FORECAST_MIN_ANALYST_OPINIONS.toDouble()))
            .coerceIn(0.0, 1.0)
        return (-0.5 + (1.5 * progress)).coerceIn(-1.0, 1.0)
    }

    private fun externalStatusReliability(status: ExternalSignalStatus): Double = when (status) {
        ExternalSignalStatus.Supportive,
        ExternalSignalStatus.Divergent,
        -> 1.0
        ExternalSignalStatus.Stale -> 0.25
        ExternalSignalStatus.Missing -> 0.0
    }

    private fun freshnessRamp(multiplier: Double): Double = (2.0 * multiplier - 1.0).coerceIn(-1.0, 1.0)

    private fun freshnessMultiplier(detail: SymbolDetail): Double {
        val age = detail.externalSignalAgeSeconds ?: return 1.0
        if (age <= 0L) return 1.0
        return exp(-age.toDouble() / V2_FORECAST_FRESHNESS_HALF_LIFE_SECONDS)
    }

    /**
     * Smooth piecewise-linear ramp in [-1, +1].
     * Returns -1 at or below [lower], +1 at or above [upper], linear interpolation between.
     */
    internal fun smoothRamp(observed: Double, lower: Double, upper: Double): Double {
        require(upper > lower) { "smoothRamp requires upper ($upper) > lower ($lower)" }
        if (observed <= lower) return -1.0
        if (observed >= upper) return 1.0
        return 2.0 * (observed - lower) / (upper - lower) - 1.0
    }

    /** Tracks weighted-sum + observed evidence-weight; normalizes against the full bucket budget. */
    private class EvidenceAccumulator(private val normalizationWeight: Double) {
        private var weightedSum = 0.0
        private var evidenceWeight = 0.0
        val signals = mutableListOf<String>()

        init {
            require(normalizationWeight > 0.0) { "EvidenceAccumulator normalizationWeight must be positive" }
        }

        fun add(weight: Double, ramp: Double, label: String? = null) {
            require(weight > 0.0) { "EvidenceAccumulator weight must be positive" }
            val clamped = ramp.coerceIn(-1.0, 1.0)
            weightedSum += weight * clamped
            evidenceWeight += weight
            if (label != null) signals += "$label${signalSuffix(clamped)}"
        }

        fun normalizedScore(): Int? {
            if (evidenceWeight == 0.0) return null
            val normalized = (weightedSum / normalizationWeight) * 100.0
            return normalized.coerceIn(-100.0, 100.0).roundToInt()
        }

        private fun signalSuffix(r: Double): String = when {
            r >= 0.5 -> "++"
            r > 0.0 -> "+"
            r >= -0.5 -> "-"
            else -> "--"
        }
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
