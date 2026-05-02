package com.discountscreener.android.ui.dashboard

import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport

internal data class EstimatesTrendSeries(
    val scenario: EstimateScenario,
    val points: List<Pair<Long, Float>>, // epochSeconds → upside %
)

internal data class EstimatesTrendChartModel(
    val series: List<EstimatesTrendSeries>,
    val minUpside: Float,
    val maxUpside: Float,
    val minEpoch: Long,
    val maxEpoch: Long,
) {
    val upsideSpan: Float = (maxUpside - minUpside).coerceAtLeast(1f)
    val epochSpan: Long = (maxEpoch - minEpoch).coerceAtLeast(1L)

    companion object {
        fun from(history: List<IndexEstimatesReport>): EstimatesTrendChartModel? {
            if (history.size < 2) return null
            val series = EstimateScenario.entries.map { scenario ->
                EstimatesTrendSeries(
                    scenario = scenario,
                    points = history.map { report ->
                        val bps = report.scenarios.find { it.scenario == scenario }
                            ?.impliedUpsideBps ?: 0
                        report.computedAtEpochSeconds to bps / 100f
                    },
                )
            }
            val allUpsides = series.flatMap { it.points.map { p -> p.second } }
            val allEpochs = series.flatMap { it.points.map { p -> p.first } }
            return EstimatesTrendChartModel(
                series = series,
                minUpside = allUpsides.minOrNull() ?: 0f,
                maxUpside = allUpsides.maxOrNull() ?: 0f,
                minEpoch = allEpochs.minOrNull() ?: 0L,
                maxEpoch = allEpochs.maxOrNull() ?: 0L,
            )
        }
    }
}
