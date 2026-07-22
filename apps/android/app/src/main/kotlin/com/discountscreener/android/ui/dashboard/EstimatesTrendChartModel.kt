package com.discountscreener.android.ui.dashboard

import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport

internal data class EstimatesTrendPoint(
    val epochSeconds: Long,
    val baseUpsidePct: Float,
    val bearUpsidePct: Float?,
    val bullUpsidePct: Float?,
)

/**
 * Simplified index-forecast chart model.
 *
 * Primary series is Base DCF implied upside (%). Bear/Bull form a confidence band.
 * Analyst scenarios stay off the chart so the plot stays readable.
 * Dense snapshot history is downsampled so micro enrichment noise does not paint spaghetti.
 */
internal data class EstimatesTrendChartModel(
    val points: List<EstimatesTrendPoint>,
    val minUpside: Float,
    val maxUpside: Float,
    val minEpoch: Long,
    val maxEpoch: Long,
    val latestBaseUpsidePct: Float,
    val latestBearUpsidePct: Float?,
    val latestBullUpsidePct: Float?,
    val baseChangePts: Float,
) {
    val drawMinUpside: Float
        get() {
            val paddedMin = minUpside - padding(minUpside, maxUpside)
            return if (minUpside > 0f && minUpside < 8f) 0f else paddedMin
        }

    val drawMaxUpside: Float
        get() {
            val paddedMax = maxUpside + padding(minUpside, maxUpside)
            return if (maxUpside < 0f && maxUpside > -8f) 0f else paddedMax
        }

    val epochSpan: Long = (maxEpoch - minEpoch).coerceAtLeast(1L)

    val yTickLabels: List<Float>
        get() {
            val lo = drawMinUpside
            val hi = drawMaxUpside
            if (hi <= lo) return listOf(0f)
            val mid = (lo + hi) / 2f
            return listOf(hi, mid, lo)
        }

    companion object {
        /** Soft cap so daily micro-snapshots don't overwhelm a phone-width canvas. */
        internal const val MAX_CHART_POINTS = 36

        fun from(history: List<IndexEstimatesReport>): EstimatesTrendChartModel? {
            if (history.size < 2) return null
            val rawPoints = history.mapNotNull { report ->
                val base = report.scenarios.find { it.scenario == EstimateScenario.BaseDcf }
                    ?.impliedUpsideBps?.div(100f) ?: return@mapNotNull null
                val bear = report.scenarios.find { it.scenario == EstimateScenario.BearDcf }
                    ?.impliedUpsideBps?.div(100f)
                val bull = report.scenarios.find { it.scenario == EstimateScenario.BullDcf }
                    ?.impliedUpsideBps?.div(100f)
                EstimatesTrendPoint(
                    epochSeconds = report.computedAtEpochSeconds,
                    baseUpsidePct = base,
                    bearUpsidePct = bear,
                    bullUpsidePct = bull,
                )
            }
            val points = downsample(rawPoints, MAX_CHART_POINTS)
            if (points.size < 2) return null
            val bandValues = points.flatMap { point ->
                listOfNotNull(point.baseUpsidePct, point.bearUpsidePct, point.bullUpsidePct)
            }
            val first = points.first()
            val latest = points.last()
            return EstimatesTrendChartModel(
                points = points,
                minUpside = bandValues.minOrNull() ?: 0f,
                maxUpside = bandValues.maxOrNull() ?: 0f,
                minEpoch = points.minOf { it.epochSeconds },
                maxEpoch = points.maxOf { it.epochSeconds },
                latestBaseUpsidePct = latest.baseUpsidePct,
                latestBearUpsidePct = latest.bearUpsidePct,
                latestBullUpsidePct = latest.bullUpsidePct,
                baseChangePts = latest.baseUpsidePct - first.baseUpsidePct,
            )
        }

        /**
         * Keeps first/last and evenly samples the middle when history is dense.
         * Prefer time-uniform sampling so long quiet stretches don't collapse.
         */
        internal fun downsample(
            points: List<EstimatesTrendPoint>,
            maxPoints: Int,
        ): List<EstimatesTrendPoint> {
            if (points.size <= maxPoints || maxPoints < 2) return points
            val first = points.first()
            val last = points.last()
            val span = (last.epochSeconds - first.epochSeconds).coerceAtLeast(1L)
            val innerSlots = maxPoints - 2
            val selected = linkedMapOf<Int, EstimatesTrendPoint>()
            selected[0] = first
            selected[points.lastIndex] = last
            for (slot in 1..innerSlots) {
                val targetEpoch = first.epochSeconds + (span * slot) / (innerSlots + 1)
                val nearestIndex = points.indices.minBy { index ->
                    kotlin.math.abs(points[index].epochSeconds - targetEpoch)
                }
                if (nearestIndex != 0 && nearestIndex != points.lastIndex) {
                    selected[nearestIndex] = points[nearestIndex]
                }
            }
            return selected.toSortedMap().values.toList()
        }

        private fun padding(min: Float, max: Float): Float {
            val raw = (max - min).coerceAtLeast(4f)
            return raw * 0.12f
        }
    }
}

internal data class EstimatesScenarioView(
    val scenario: EstimateScenario,
    val impliedUpsideBps: Int,
    val coverageCount: Int,
)

internal data class EstimatesHeroSummary(
    val profileLabel: String,
    val baseUpsideBps: Int?,
    val bearUpsideBps: Int?,
    val bullUpsideBps: Int?,
    val analystLowBps: Int?,
    val analystHighBps: Int?,
    val baseCoverageCount: Int?,
    val analystCoverageCount: Int?,
    val verdict: EstimatesVerdict,
    val coverageLabel: String,
    val coverageDetail: String,
    val coverageStatus: com.discountscreener.core.model.DcfCoverageStatus,
)

internal enum class EstimatesVerdict {
    Undervalued,
    Fair,
    Overvalued,
    Unknown,
}

internal fun buildEstimatesHeroSummary(report: IndexEstimatesReport): EstimatesHeroSummary {
    val byScenario = report.scenarios.associateBy { it.scenario }
    val base = byScenario[EstimateScenario.BaseDcf]
    val bear = byScenario[EstimateScenario.BearDcf]
    val bull = byScenario[EstimateScenario.BullDcf]
    val analystLow = byScenario[EstimateScenario.AnalystLow]
    val analystHigh = byScenario[EstimateScenario.AnalystHigh]
    val coverage = report.dcfCoverage
    val notEligible = coverage.sourceDistribution.notEligibleCount
    return EstimatesHeroSummary(
        profileLabel = report.profileName.uppercase(),
        baseUpsideBps = base?.impliedUpsideBps,
        bearUpsideBps = bear?.impliedUpsideBps,
        bullUpsideBps = bull?.impliedUpsideBps,
        analystLowBps = analystLow?.impliedUpsideBps,
        analystHighBps = analystHigh?.impliedUpsideBps,
        baseCoverageCount = base?.coverageCount,
        analystCoverageCount = maxOf(analystLow?.coverageCount ?: 0, analystHigh?.coverageCount ?: 0)
            .takeIf { it > 0 },
        verdict = verdictFor(base?.impliedUpsideBps),
        coverageLabel = when (coverage.status) {
            com.discountscreener.core.model.DcfCoverageStatus.Ready -> "Ready"
            com.discountscreener.core.model.DcfCoverageStatus.Provisional -> "Provisional"
            com.discountscreener.core.model.DcfCoverageStatus.Partial -> "Partial"
            com.discountscreener.core.model.DcfCoverageStatus.LowConfidence -> "Low confidence"
            com.discountscreener.core.model.DcfCoverageStatus.Unavailable -> "Unavailable"
        },
        coverageDetail = buildString {
            append("${coverage.coveredSymbols}/${coverage.totalEligibleSymbols} DCF")
            if (notEligible > 0) append(" · $notEligible n/a")
        },
        coverageStatus = coverage.status,
    )
}

internal fun verdictFor(baseUpsideBps: Int?): EstimatesVerdict = when {
    baseUpsideBps == null -> EstimatesVerdict.Unknown
    baseUpsideBps >= 500 -> EstimatesVerdict.Undervalued
    baseUpsideBps <= -500 -> EstimatesVerdict.Overvalued
    else -> EstimatesVerdict.Fair
}

internal fun verdictSentence(verdict: EstimatesVerdict, baseUpsideBps: Int?): String = when (verdict) {
    EstimatesVerdict.Undervalued ->
        "Index looks undervalued on Base DCF (${formatSignedPctBps(baseUpsideBps)})"
    EstimatesVerdict.Overvalued ->
        "Index looks overvalued on Base DCF (${formatSignedPctBps(baseUpsideBps)})"
    EstimatesVerdict.Fair ->
        "Index looks roughly fair on Base DCF (${formatSignedPctBps(baseUpsideBps)})"
    EstimatesVerdict.Unknown ->
        "Base DCF upside not available yet"
}

internal fun formatSignedPctBps(bps: Int?): String {
    if (bps == null) return "—"
    val pct = bps / 100.0
    return if (pct >= 0) "+%.1f%%".format(pct) else "%.1f%%".format(pct)
}
