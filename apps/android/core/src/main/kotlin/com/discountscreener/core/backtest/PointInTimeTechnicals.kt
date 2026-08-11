package com.discountscreener.core.backtest

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle

/**
 * The technical bucket, re-scored at every bar, from the bars up to and including that one.
 *
 * This is where look-ahead bias would enter the retrospective, so it is the one place the property
 * is worth pinning: the score as of a bar must not move when a later bar changes. The production
 * scorer reads a [com.discountscreener.core.model.ChartRangeSummary], which is derived from a whole
 * series, so replaying it correctly is a matter of what is handed in — nothing here reaches for a
 * bar the caller did not ask for, and [scoresOverHistory] never passes one.
 *
 * V3 and V4 share this bucket. Whatever it says about forward returns is therefore a finding about
 * both models, not evidence for either one over the other.
 */
object PointInTimeTechnicals {
    /**
     * EMA200 needs two hundred bars before it is an average of two hundred bars. Scoring earlier
     * would not fail — the summary reports whatever the series can support — it would quietly grade
     * early history on a shorter trend than late history, and the decile split would then partly
     * sort by how old the observation is.
     */
    const val MIN_WARMUP_BARS = 200

    /**
     * One score per bar from [warmupBars] onward, each from that bar's own history.
     *
     * [range] is carried into the summary for completeness and does not enter the technical score;
     * the bucket reads closes, EMAs, MACD, RSI and volume, never the range label.
     */
    fun scoresOverHistory(
        symbol: String,
        candles: List<HistoricalCandle>,
        warmupBars: Int = MIN_WARMUP_BARS,
        range: ChartRange = ChartRange.Year,
    ): List<DatedScore> {
        require(warmupBars >= 0) { "a warmup of $warmupBars bars is not a warmup" }
        var ordered = candles.sortedBy { it.epochSeconds }
        return ordered.indices.drop(warmupBars).mapNotNull { last ->
            scoreAt(symbol, ordered.subList(0, last + 1), range)
        }
    }

    /**
     * The score as of the final bar of [history], which is the only bar this function treats as the
     * present. Public so a caller can ask for one date without replaying a series.
     */
    fun scoreAt(symbol: String, history: List<HistoricalCandle>, range: ChartRange = ChartRange.Year): DatedScore? {
        var asOf = history.lastOrNull() ?: return null
        var summary = ChartAnalysis.buildSummary(range, history, asOf.epochSeconds)
        var score = OpportunityEngine.aggressiveV3TechnicalScore(summary).first ?: return null
        return DatedScore(symbol = symbol, scoredAtEpochSeconds = asOf.epochSeconds, score = score)
    }
}
