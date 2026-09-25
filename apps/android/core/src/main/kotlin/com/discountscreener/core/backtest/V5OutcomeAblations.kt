package com.discountscreener.core.backtest

import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.OpportunityScoringModel
import kotlin.math.roundToInt

/** One model reading needed for a controlled V2/V5 outcome experiment. */
data class ScoreAblationObservation(
    val symbol: String,
    val model: OpportunityScoringModel,
    val scoredAtEpochSeconds: Long,
    val fundamentalsScore: Int?,
    val technicalScore: Int?,
    val forecastScore: Int?,
    val regimeScore: Int?,
    val compositeScore: Int,
    val compositeScoreBase: Int,
)

data class ScoreAblationResult(
    val pairedRowCount: Int,
    val series: List<ScoreSeries>,
)

/**
 * Controlled V2/V5 comparisons built only from exact symbol-time pairs.
 *
 * Price, fundamentals, analyst inputs, charts, and market regime can change between refreshes.
 * Exact pairs keep every series on one population.
 */
object V5OutcomeAblations {
    private const val COMPOSITE_BOUND = 110

    fun build(rows: List<ScoreAblationObservation>): ScoreAblationResult {
        val byModelAndKey = rows.associateBy { row ->
            ModelKey(row.model, row.symbol, row.scoredAtEpochSeconds)
        }
        val paired = rows.asSequence()
            .filter { row -> row.model == OpportunityScoringModel.AggressiveV5 }
            .mapNotNull { v5 ->
                val v2 = byModelAndKey[
                    ModelKey(OpportunityScoringModel.AggressiveV2, v5.symbol, v5.scoredAtEpochSeconds),
                ] ?: return@mapNotNull null
                Pair(v2, v5)
            }
            .sortedWith(compareBy({ it.second.scoredAtEpochSeconds }, { it.second.symbol }))
            .toList()

        return ScoreAblationResult(
            pairedRowCount = paired.size,
            series = listOf(
                series("paired-v2-composite", paired) { (v2, _) -> v2.compositeScore },
                series("paired-v5-composite", paired) { (_, v5) -> v5.compositeScore },
                series("v5-no-market", paired) { (_, v5) -> v5.compositeScoreBase },
                series("v5-mean-no-bonus-no-beta", paired) { (_, v5) ->
                    meanOfPresent(v5.fundamentalsScore, v5.technicalScore, v5.forecastScore, v5.regimeScore)
                },
                series("v5-fundamentals-v2-shell", paired) { (v2, v5) ->
                    v2Composite(v5.fundamentalsScore, v2.technicalScore, v2.forecastScore)
                },
            ),
        )
    }

    private fun series(
        name: String,
        paired: List<Pair<ScoreAblationObservation, ScoreAblationObservation>>,
        score: (Pair<ScoreAblationObservation, ScoreAblationObservation>) -> Int,
    ) = ScoreSeries(
        name = name,
        scores = paired.map { pair ->
            val v5 = pair.second
            DatedScore(v5.symbol, v5.scoredAtEpochSeconds, score(pair))
        },
    )

    private fun meanOfPresent(vararg scores: Int?): Int {
        val present = scores.filterNotNull()
        if (present.isEmpty()) return 0
        return (present.sum().toDouble() / present.size.toDouble()).roundToInt()
            .coerceIn(-COMPOSITE_BOUND, COMPOSITE_BOUND)
    }

    /** V2's exact composite, with V5 fundamentals substituted for V2 fundamentals. */
    private fun v2Composite(fundamentals: Int?, technical: Int?, forecast: Int?): Int =
        OpportunityEngine.compositeScoreFor(
            model = OpportunityScoringModel.AggressiveV2,
            fundamentals = fundamentals,
            technical = technical,
            forecast = forecast,
            regime = null,
            coverageCount = listOf(fundamentals, technical, forecast).count { it != null },
            betaMillis = null,
            betaHaircutMult = 1.0,
        )

    private data class ModelKey(
        val model: OpportunityScoringModel,
        val symbol: String,
        val scoredAtEpochSeconds: Long,
    )
}
