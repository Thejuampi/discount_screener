package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ComputationArea
import com.discountscreener.core.model.ComputationFailure
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.CorrelationRiskBand
import com.discountscreener.core.model.EvidenceStrengthBand
import com.discountscreener.core.model.ExpectedValueRangeSource
import com.discountscreener.core.model.ExpectedValueRangeBand
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.QuantLensCorrelationRisk
import com.discountscreener.core.model.QuantLensCorrelationPair
import com.discountscreener.core.model.QuantLensEvidenceStrength
import com.discountscreener.core.model.QuantLensExpectedValueRange
import com.discountscreener.core.model.QuantLensHorizon
import com.discountscreener.core.model.QuantLensHorizonBaseline
import com.discountscreener.core.model.QuantLensHorizonContext
import com.discountscreener.core.model.QuantLensInput
import com.discountscreener.core.model.QuantLensModelVersion
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.QuantLensReport
import com.discountscreener.core.model.QuantLensSimilarSetups
import com.discountscreener.core.model.QuantLensTrendReliability
import com.discountscreener.core.model.SimilarSetupMatch
import com.discountscreener.core.model.SimilarSetupsBand
import com.discountscreener.core.model.TrendReliabilityBand
import com.discountscreener.core.model.captureComputationResult
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

object QuantLensEngine {
    private const val MIN_HORIZON_WINDOWS = 10
    private const val MIN_QUANT_LENS_BPS = -100_000
    private const val MAX_QUANT_LENS_BPS = 100_000

    fun analyze(input: QuantLensInput): ComputationResult<QuantLensReport> = captureComputationResult(
        area = ComputationArea.QuantLens,
        code = "quant_lens_analysis_failed",
        symbol = input.detail.symbol,
        message = { error -> error.message ?: "Quant Lens analysis failed." },
    ) {
        val evidenceStrength = analyzeEvidenceStrength(input)
        val expectedValueRange = analyzeExpectedValueRange(input)
        val correlationRisk = analyzeCorrelationRisk(input)
        val trendReliability = analyzeTrendReliability(input)
        val horizonContext = analyzeHorizonContext(input)
        val similarSetups = analyzeSimilarSetups(input, evidenceStrength, expectedValueRange, trendReliability)

        QuantLensReport(
            symbol = input.detail.symbol,
            selectedRange = input.selectedRange,
            computedAtEpochSeconds = input.nowEpochSeconds,
            modelVersion = QuantLensModelVersion.CURRENT,
            inputFingerprint = input.inputFingerprint,
            primaryStatus = combinedStatus(
                evidenceStrength.primaryStatus,
                expectedValueRange.primaryStatus,
                correlationRisk.primaryStatus,
                trendReliability.primaryStatus,
                horizonContext.primaryStatus,
                similarSetups.primaryStatus,
            ),
            evidenceStrength = evidenceStrength,
            expectedValueRange = expectedValueRange,
            correlationRisk = correlationRisk,
            trendReliability = trendReliability,
            horizonContext = horizonContext,
            similarSetups = similarSetups,
            notices = listOf(QuantLensReasonCode.ScaffoldPending),
        )
    }

    private fun analyzeEvidenceStrength(input: QuantLensInput): QuantLensEvidenceStrength {
        val detail = input.detail
        if (detail.marketPriceCents <= 0L) {
            return QuantLensEvidenceStrength(
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                band = EvidenceStrengthBand.Unavailable,
                reasonCodes = listOf(QuantLensReasonCode.MissingMarketPrice),
            )
        }
        if (detail.intrinsicValueCents <= 0L && analystAnchors(detail = detail).isEmpty()) {
            return QuantLensEvidenceStrength(
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                band = EvidenceStrengthBand.Unavailable,
                reasonCodes = listOf(QuantLensReasonCode.MissingBaseSignal),
            )
        }

        val supportCount = listOfNotNull(
            detail.upsideBps.takeIf { it >= detail.minimumGapBps },
            detail.externalSignalFairValueCents?.takeIf { it > detail.marketPriceCents },
            input.dcfAnalysis?.baseIntrinsicValueCents?.takeIf { it > detail.marketPriceCents },
            input.chartSummaries[input.selectedRange]?.takeIf { it.candleCount >= 20 },
            input.opportunityRows.find { it.symbol == detail.symbol }?.takeIf { it.coverageCount >= 2 },
        ).size
        val conflictCount = listOfNotNull(
            detail.externalSignalFairValueCents?.takeIf { it in 1 until detail.marketPriceCents },
            input.dcfAnalysis?.baseIntrinsicValueCents?.takeIf { it in 1 until detail.marketPriceCents },
        ).size
        val neutralCount = (5 - supportCount - conflictCount).coerceAtLeast(0)

        val primaryStatus = when {
            supportCount + conflictCount < 2 -> QuantLensPrimaryStatus.Sparse
            conflictCount > 0 -> QuantLensPrimaryStatus.Available
            supportCount >= 3 -> QuantLensPrimaryStatus.Available
            else -> QuantLensPrimaryStatus.Provisional
        }
        val band = when {
            primaryStatus == QuantLensPrimaryStatus.Sparse -> EvidenceStrengthBand.Sparse
            conflictCount > 0 -> EvidenceStrengthBand.Mixed
            supportCount >= 3 -> EvidenceStrengthBand.Strong
            else -> EvidenceStrengthBand.Provisional
        }

        return QuantLensEvidenceStrength(
            primaryStatus = primaryStatus,
            band = band,
            strengthBps = ((supportCount * 2_500) - (conflictCount * 2_000)).coerceIn(0, 10_000),
            supportCount = supportCount,
            conflictCount = conflictCount,
            neutralCount = neutralCount,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        )
    }

    private fun analyzeHorizonContext(input: QuantLensInput): QuantLensHorizonContext {
        val horizons = listOf(
            HorizonSpec(QuantLensHorizon.FiveMinutes, ChartRange.Day, lagCandles = 1),
            HorizonSpec(QuantLensHorizon.OneDay, ChartRange.Month, lagCandles = 1),
            HorizonSpec(QuantLensHorizon.ThreeMonths, ChartRange.FiveYears, lagCandles = 3),
        ).map { spec -> analyzeHorizonBaseline(input, spec) }
        val primaryStatus = when {
            horizons.all { it.primaryStatus == QuantLensPrimaryStatus.Available } -> QuantLensPrimaryStatus.Available
            horizons.any { it.primaryStatus == QuantLensPrimaryStatus.Available } -> QuantLensPrimaryStatus.Partial
            horizons.any { it.primaryStatus == QuantLensPrimaryStatus.Insufficient } -> QuantLensPrimaryStatus.Insufficient
            else -> QuantLensPrimaryStatus.Unavailable
        }
        return QuantLensHorizonContext(
            primaryStatus = primaryStatus,
            horizons = horizons,
            reasonCodes = horizons.flatMap { it.reasonCodes }.distinct(),
        )
    }

    private fun analyzeHorizonBaseline(
        input: QuantLensInput,
        spec: HorizonSpec,
    ): QuantLensHorizonBaseline {
        val sourceCandles = input.selectedCandlesByRange[spec.sourceRange]
        if (sourceCandles.isNullOrEmpty()) {
            return horizonBaseline(
                spec = spec,
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                sampleCount = 0,
                reasonCodes = listOf(QuantLensReasonCode.MissingHorizonCandles),
            )
        }

        val validCandles = sourceCandles
            .filter { it.closeCents > 0L }
            .let(::canonicalizeCandlesByEpoch)
        if (validCandles.isEmpty()) {
            return horizonBaseline(
                spec = spec,
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                sampleCount = 0,
                reasonCodes = listOf(QuantLensReasonCode.InvalidHorizonClose),
            )
        }

        val absoluteMovesBps = (spec.lagCandles until validCandles.size).map { index ->
            absoluteMoveBps(
                baseCloseCents = validCandles[index - spec.lagCandles].closeCents,
                currentCloseCents = validCandles[index].closeCents,
            )
        }
        if (absoluteMovesBps.size < MIN_HORIZON_WINDOWS) {
            return horizonBaseline(
                spec = spec,
                primaryStatus = QuantLensPrimaryStatus.Insufficient,
                sampleCount = absoluteMovesBps.size,
                reasonCodes = listOf(QuantLensReasonCode.InsufficientHorizonSamples),
            )
        }

        val sortedMoves = absoluteMovesBps.sorted()
        return horizonBaseline(
            spec = spec,
            primaryStatus = QuantLensPrimaryStatus.Available,
            sampleCount = sortedMoves.size,
            medianAbsoluteMoveBps = nearestRank(sortedMoves, percentile = 0.50),
            p25AbsoluteMoveBps = nearestRank(sortedMoves, percentile = 0.25),
            p75AbsoluteMoveBps = nearestRank(sortedMoves, percentile = 0.75),
            reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
        )
    }

    private fun horizonBaseline(
        spec: HorizonSpec,
        primaryStatus: QuantLensPrimaryStatus,
        sampleCount: Int,
        medianAbsoluteMoveBps: Int? = null,
        p25AbsoluteMoveBps: Int? = null,
        p75AbsoluteMoveBps: Int? = null,
        reasonCodes: List<QuantLensReasonCode>,
    ) = QuantLensHorizonBaseline(
        horizon = spec.horizon,
        primaryStatus = primaryStatus,
        sourceRange = spec.sourceRange,
        lagCandles = spec.lagCandles,
        sampleCount = sampleCount,
        medianAbsoluteMoveBps = medianAbsoluteMoveBps,
        p25AbsoluteMoveBps = p25AbsoluteMoveBps,
        p75AbsoluteMoveBps = p75AbsoluteMoveBps,
        reasonCodes = reasonCodes,
    )

    private fun absoluteMoveBps(baseCloseCents: Long, currentCloseCents: Long): Int {
        val returnBps = (((currentCloseCents - baseCloseCents).toDouble() / baseCloseCents.toDouble()) * 10_000.0)
            .roundToInt()
            .coerceIn(-100_000, 100_000)
        return abs(returnBps)
    }

    private fun nearestRank(sortedValues: List<Int>, percentile: Double): Int {
        val rank = ceil(percentile * sortedValues.size).toInt().coerceIn(1, sortedValues.size)
        return sortedValues[rank - 1]
    }

    private fun canonicalizeCandlesByEpoch(candles: List<HistoricalCandle>): List<HistoricalCandle> = candles
        .sortedWith(
            compareBy<HistoricalCandle> { it.epochSeconds }
                .thenBy { it.openCents }
                .thenBy { it.highCents }
                .thenBy { it.lowCents }
                .thenBy { it.closeCents }
                .thenBy { it.volume },
        )
        .distinctBy { it.epochSeconds }

    private fun analyzeExpectedValueRange(input: QuantLensInput): QuantLensExpectedValueRange {
        val detail = input.detail
        val marketPriceCents = detail.marketPriceCents
        if (marketPriceCents <= 0L) {
            return QuantLensExpectedValueRange(
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                band = ExpectedValueRangeBand.Unavailable,
                reasonCodes = listOf(QuantLensReasonCode.MissingMarketPrice),
            )
        }

        val dcfAnchors = normalizedScenarioAnchors(
            anchors = input.dcfAnalysis?.let {
                listOf(it.bearIntrinsicValueCents, it.baseIntrinsicValueCents, it.bullIntrinsicValueCents)
            }.orEmpty(),
            source = "DCF",
            symbol = detail.symbol,
        )
        val analystAnchors = normalizedScenarioAnchors(
            anchors = analystAnchors(detail),
            source = "analyst",
            symbol = detail.symbol,
        )

        val sourceAndAnchors = when {
            dcfAnchors.size == 3 -> ExpectedValueRangeSource.Dcf to dcfAnchors
            analystAnchors.size == 3 -> ExpectedValueRangeSource.Analyst to analystAnchors
            else -> null
        }

        if (sourceAndAnchors == null) {
            return QuantLensExpectedValueRange(
                primaryStatus = QuantLensPrimaryStatus.Sparse,
                band = if (dcfAnchors.isNotEmpty() || analystAnchors.isNotEmpty()) {
                    ExpectedValueRangeBand.ReferenceOnly
                } else {
                    ExpectedValueRangeBand.Sparse
                },
                reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
            )
        }

        val (source, anchors) = sourceAndAnchors
        val low = anchors[0]
        val base = anchors[1]
        val high = anchors[2]
        val weighted = ((low + (base * 2) + high) / 4L).coerceAtLeast(0L)
        val weightedUpsideBps = boundedQuantLensBps(checkedUpsideBps(marketPriceCents, weighted)) ?: 0
        val spreadBps = boundedQuantLensBps(checkedUpsideBps(low.coerceAtLeast(1L), high), min = 0) ?: 0

        return QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Available,
            band = ExpectedValueRangeBand.ScenarioWeighted,
            source = source,
            weightedFairValueCents = weighted,
            weightedUpsideBps = weightedUpsideBps,
            lowFairValueCents = low,
            highFairValueCents = high,
            spreadBps = spreadBps,
            reasonCodes = listOf(QuantLensReasonCode.CompleteScenarioAnchors),
        )
    }

    private fun analyzeCorrelationRisk(input: QuantLensInput): QuantLensCorrelationRisk {
        val selectedCandles = input.selectedCandlesByRange[input.selectedRange].orEmpty()
        val selectedReturns = returnsByEpoch(selectedCandles)
        val sufficientUniverseCount = input.correlationSeries.count {
            it.range == input.selectedRange && returnsByEpoch(it.candles).size >= 30
        } + if (selectedReturns.size >= 30) 1 else 0
        if (selectedReturns.size < 30 || sufficientUniverseCount < 3) {
            return QuantLensCorrelationRisk(
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                band = CorrelationRiskBand.Unavailable,
                reasonCodes = listOf(QuantLensReasonCode.InsufficientLocalHistory),
            )
        }

        val pairs = input.correlationSeries
            .asSequence()
            .filter { it.symbol != input.detail.symbol && it.range == input.selectedRange }
            .mapNotNull { series ->
                val correlation = pairCorrelationBps(selectedReturns, returnsByEpoch(series.candles)) ?: return@mapNotNull null
                QuantLensCorrelationPair(
                    symbol = series.symbol,
                    correlationBps = correlation.first,
                    overlapCount = correlation.second,
                    range = input.selectedRange,
                )
            }
            .sortedWith(compareByDescending<QuantLensCorrelationPair> { abs(it.correlationBps) }.thenBy { it.symbol })
            .toList()

        if (pairs.isEmpty()) {
            return QuantLensCorrelationRisk(
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                band = CorrelationRiskBand.Unavailable,
                reasonCodes = listOf(QuantLensReasonCode.InsufficientLocalHistory),
            )
        }

        val elevatedPairs = pairs.count { abs(it.correlationBps) >= 7_000 }
        val highest = pairs.maxOf { abs(it.correlationBps) }
        val band = when {
            highest >= 8_500 || elevatedPairs >= 2 -> CorrelationRiskBand.High
            elevatedPairs == 1 -> CorrelationRiskBand.Elevated
            pairs.size < 2 -> CorrelationRiskBand.Sparse
            else -> CorrelationRiskBand.Low
        }
        val primaryStatus = if (band == CorrelationRiskBand.Sparse) {
            QuantLensPrimaryStatus.Sparse
        } else {
            QuantLensPrimaryStatus.Available
        }

        return QuantLensCorrelationRisk(
            primaryStatus = primaryStatus,
            band = band,
            topPairs = pairs.take(3),
            validPairCount = pairs.size,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        )
    }

    private fun analyzeTrendReliability(input: QuantLensInput): QuantLensTrendReliability {
        val candles = input.selectedCandlesByRange[input.selectedRange].orEmpty()
            .filter { it.closeCents > 0L }
            .let(::canonicalizeCandlesByEpoch)
        if (candles.size < 20) {
            return QuantLensTrendReliability(
                primaryStatus = QuantLensPrimaryStatus.Insufficient,
                band = TrendReliabilityBand.Insufficient,
                sampleCount = candles.size,
                reasonCodes = listOf(QuantLensReasonCode.InsufficientTrendSamples),
            )
        }

        val fit = leastSquares(candles.map { it.closeCents.toDouble() })
        val start = fit.intercept
        val end = fit.intercept + fit.slope * (candles.lastIndex)
        val movementBps = if (start <= 0.0) 0 else (((end - start) / start) * 10_000.0).roundToInt()
        val rSquaredBps = (fit.rSquared * 10_000.0).roundToInt().coerceIn(0, 10_000)
        val band = when {
            abs(movementBps) < 200 -> TrendReliabilityBand.Flat
            rSquaredBps >= 6_000 -> TrendReliabilityBand.Reliable
            rSquaredBps >= 3_500 -> TrendReliabilityBand.Moderate
            else -> TrendReliabilityBand.Noisy
        }

        return QuantLensTrendReliability(
            primaryStatus = QuantLensPrimaryStatus.Available,
            band = band,
            sampleCount = candles.size,
            rSquaredBps = rSquaredBps,
            movementBps = movementBps,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        )
    }

    private fun analyzeSimilarSetups(
        input: QuantLensInput,
        evidenceStrength: QuantLensEvidenceStrength,
        expectedValueRange: QuantLensExpectedValueRange,
        trendReliability: QuantLensTrendReliability,
    ): QuantLensSimilarSetups {
        val target = input.comparableUniverse.find { it.symbol == input.detail.symbol }
            ?: comparableFromSelected(input, evidenceStrength, expectedValueRange, trendReliability)
        val matches = input.comparableUniverse
            .asSequence()
            .filter { it.symbol != input.detail.symbol }
            .mapNotNull { comparable ->
                similarDistance(target, comparable)?.let { distance ->
                    val similarity = (10_000 - distance).coerceIn(0, 10_000)
                    SimilarSetupMatch(
                        symbol = comparable.symbol,
                        similarityBps = similarity,
                        distanceBps = distance,
                        sharedFeatureCount = sharedFeatureCount(target, comparable),
                        compositeScore = comparable.opportunityScore,
                        reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
                    )
                }
            }
            .sortedWith(
                compareBy<SimilarSetupMatch> { it.distanceBps }
                    .thenByDescending { it.compositeScore ?: Int.MIN_VALUE }
                    .thenBy { it.symbol },
            )
            .toList()

        if (matches.size < 3) {
            return QuantLensSimilarSetups(
                primaryStatus = QuantLensPrimaryStatus.Sparse,
                band = SimilarSetupsBand.Sparse,
                qualifyingComparableCount = matches.size,
                reasonCodes = listOf(QuantLensReasonCode.InsufficientComparables),
            )
        }

        return QuantLensSimilarSetups(
            primaryStatus = QuantLensPrimaryStatus.Available,
            band = SimilarSetupsBand.Available,
            qualifyingComparableCount = matches.size,
            matches = matches.take(3),
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        )
    }

    private fun analystAnchors(detail: com.discountscreener.core.model.SymbolDetail): List<Long> =
        listOfNotNull(
            detail.externalSignalLowFairValueCents,
            detail.weightedExternalSignalFairValueCents ?: detail.externalSignalFairValueCents,
            detail.externalSignalHighFairValueCents,
        ).filter { it > 0L }

    private fun normalizedScenarioAnchors(
        anchors: List<Long>,
        source: String,
        symbol: String,
    ): List<Long> {
        if (anchors.isEmpty()) {
            return emptyList()
        }
        val positiveAnchors = anchors.filter { it > 0L }
        if (positiveAnchors.size < anchors.size) {
            return positiveAnchors
        }
        if (positiveAnchors.size < 3) {
            return positiveAnchors
        }
        if (positiveAnchors.size != 3) {
            throw invalidScenarioAnchors(source, symbol, positiveAnchors)
        }
        return positiveAnchors.sorted()
    }

    private fun invalidScenarioAnchors(
        source: String,
        symbol: String,
        anchors: List<Long>,
    ): IllegalStateException = IllegalStateException(
        "$source scenario anchors could not be normalized for $symbol: ${anchors.joinToString()}",
    )

    private fun boundedQuantLensBps(value: Int?, min: Int = MIN_QUANT_LENS_BPS, max: Int = MAX_QUANT_LENS_BPS): Int? =
        value?.coerceIn(min, max)

    private fun returnsByEpoch(candles: List<HistoricalCandle>): Map<Long, Double> =
        candles
            .filter { it.closeCents > 0L }
            .let(::canonicalizeCandlesByEpoch)
            .zipWithNext()
            .associate { (previous, current) ->
                current.epochSeconds to ((current.closeCents - previous.closeCents).toDouble() / previous.closeCents.toDouble())
            }

    private fun pairCorrelationBps(
        selectedReturns: Map<Long, Double>,
        candidateReturns: Map<Long, Double>,
    ): Pair<Int, Int>? {
        val epochs = selectedReturns.keys.intersect(candidateReturns.keys).sorted()
        if (epochs.size < 30) return null
        val xs = epochs.map { selectedReturns.getValue(it) }
        val ys = epochs.map { candidateReturns.getValue(it) }
        val correlation = pearson(xs, ys) ?: return null
        return (correlation * 10_000.0).roundToInt().coerceIn(-10_000, 10_000) to epochs.size
    }

    private fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val xMean = xs.average()
        val yMean = ys.average()
        var numerator = 0.0
        var xDenominator = 0.0
        var yDenominator = 0.0
        xs.indices.forEach { index ->
            val x = xs[index] - xMean
            val y = ys[index] - yMean
            numerator += x * y
            xDenominator += x * x
            yDenominator += y * y
        }
        val denominator = sqrt(xDenominator * yDenominator)
        if (denominator == 0.0) return null
        return numerator / denominator
    }

    private data class Fit(val slope: Double, val intercept: Double, val rSquared: Double)

    private data class HorizonSpec(
        val horizon: QuantLensHorizon,
        val sourceRange: ChartRange,
        val lagCandles: Int,
    )

    private fun leastSquares(values: List<Double>): Fit {
        val xMean = values.indices.average()
        val yMean = values.average()
        var numerator = 0.0
        var denominator = 0.0
        values.indices.forEach { index ->
            val x = index - xMean
            numerator += x * (values[index] - yMean)
            denominator += x * x
        }
        val slope = if (denominator == 0.0) 0.0 else numerator / denominator
        val intercept = yMean - slope * xMean
        var total = 0.0
        var residual = 0.0
        values.indices.forEach { index ->
            val predicted = intercept + slope * index
            total += (values[index] - yMean) * (values[index] - yMean)
            residual += (values[index] - predicted) * (values[index] - predicted)
        }
        val rSquared = if (total == 0.0) 1.0 else (1.0 - residual / total).coerceIn(0.0, 1.0)
        return Fit(slope = slope, intercept = intercept, rSquared = rSquared)
    }

    private fun comparableFromSelected(
        input: QuantLensInput,
        evidenceStrength: QuantLensEvidenceStrength,
        expectedValueRange: QuantLensExpectedValueRange,
        trendReliability: QuantLensTrendReliability,
    ) = com.discountscreener.core.model.QuantLensComparable(
        symbol = input.detail.symbol,
        valuationUpsideBps = expectedValueRange.weightedUpsideBps ?: input.detail.upsideBps,
        evidenceStrengthBps = evidenceStrength.strengthBps,
        opportunityScore = input.opportunityRows.find { it.symbol == input.detail.symbol }?.compositeScore,
        trendReliabilityBps = trendReliability.rSquaredBps,
        evSpreadBps = expectedValueRange.spreadBps,
    )

    private fun similarDistance(
        target: com.discountscreener.core.model.QuantLensComparable,
        candidate: com.discountscreener.core.model.QuantLensComparable,
    ): Int? {
        val pairs = listOfNotNull(
            weightedFeature(target.valuationUpsideBps, candidate.valuationUpsideBps, min = -10_000, max = 30_000, weight = 3),
            weightedFeature(target.evidenceStrengthBps, candidate.evidenceStrengthBps, min = 0, max = 10_000, weight = 3),
            weightedFeature(target.opportunityScore, candidate.opportunityScore, min = -100, max = 100, weight = 2),
            weightedFeature(target.trendReliabilityBps, candidate.trendReliabilityBps, min = 0, max = 10_000, weight = 1),
            weightedFeature(target.evSpreadBps, candidate.evSpreadBps, min = 0, max = 20_000, weight = 1),
        )
        if (pairs.size < 3) return null
        val weightedSquared = pairs.sumOf { (delta, weight) -> delta * delta * weight }
        val totalWeight = pairs.sumOf { it.second }
        return (sqrt(weightedSquared / totalWeight) * 10_000.0).roundToInt().coerceIn(0, 10_000)
    }

    private fun weightedFeature(
        target: Int?,
        candidate: Int?,
        min: Int,
        max: Int,
        weight: Int,
    ): Pair<Double, Int>? {
        if (target == null || candidate == null) return null
        val span = (max - min).toDouble()
        val targetNormalized = (target.coerceIn(min, max) - min) / span
        val candidateNormalized = (candidate.coerceIn(min, max) - min) / span
        return abs(targetNormalized - candidateNormalized) to weight
    }

    private fun sharedFeatureCount(
        target: com.discountscreener.core.model.QuantLensComparable,
        candidate: com.discountscreener.core.model.QuantLensComparable,
    ): Int = listOf(
        target.valuationUpsideBps to candidate.valuationUpsideBps,
        target.evidenceStrengthBps to candidate.evidenceStrengthBps,
        target.opportunityScore to candidate.opportunityScore,
        target.trendReliabilityBps to candidate.trendReliabilityBps,
        target.evSpreadBps to candidate.evSpreadBps,
    ).count { (left, right) -> left != null && right != null }

    private fun combinedStatus(vararg statuses: QuantLensPrimaryStatus): QuantLensPrimaryStatus =
        when {
            statuses.any { it == QuantLensPrimaryStatus.Available } -> QuantLensPrimaryStatus.Available
            statuses.any { it == QuantLensPrimaryStatus.Provisional || it == QuantLensPrimaryStatus.Partial } ->
                QuantLensPrimaryStatus.Provisional
            statuses.any { it == QuantLensPrimaryStatus.Sparse || it == QuantLensPrimaryStatus.Insufficient } ->
                QuantLensPrimaryStatus.Sparse
            else -> QuantLensPrimaryStatus.Unavailable
        }
}
