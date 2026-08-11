package com.discountscreener.android.data.debug

import com.discountscreener.core.backtest.DatedScore
import com.discountscreener.core.backtest.ForwardReturnReport
import com.discountscreener.core.backtest.PointInTimeTechnicals
import com.discountscreener.core.backtest.forwardReturnByDecile
import com.discountscreener.core.model.HistoricalCandle

/**
 * The retrospective, rendered as text a commit message can quote.
 *
 * Debug-only, like the score export before it, and for the same reason: the claim this effort has
 * to show — does a technical score precede a return, and by how much — cannot be asserted from a
 * unit test. It has to be run against real bars off a real device.
 *
 * **Every number here is about the technicals bucket, which V3 and V4 share.** Nothing in this
 * report distinguishes the two models. A flat result is a finding about both, and it is worth more
 * than a favourable one, so the report states the spread whatever it says.
 */
object RetrospectiveReport {
    /** One month, one quarter, half a year, in trading days. */
    val HORIZONS = listOf(21, 63, 126)

    fun build(candlesBySymbol: Map<String, List<HistoricalCandle>>): String {
        var scores = candlesBySymbol.flatMap { (symbol, bars) ->
            PointInTimeTechnicals.scoresOverHistory(symbol, bars)
        }
        return buildString {
            appendLine("retrospective — technicals bucket, shared by AggressiveV3 and AggressiveV4")
            appendLine(
                "symbols=${candlesBySymbol.size} " +
                    "bars=${candlesBySymbol.values.sumOf { it.size }} " +
                    "scores=${scores.size} " +
                    "warmup=${PointInTimeTechnicals.MIN_WARMUP_BARS}",
            )
            if (scores.isEmpty()) {
                appendLine("no scored dates — the stored series is shorter than the warmup")
                return@buildString
            }
            appendLine("score range: ${scores.minOf { it.score }}..${scores.maxOf { it.score }}")
            HORIZONS.forEach { horizon ->
                appendLine()
                append(section(scores, candlesBySymbol, horizon))
            }
        }
    }

    private fun section(
        scores: List<DatedScore>,
        candlesBySymbol: Map<String, List<HistoricalCandle>>,
        horizonBars: Int,
    ): String {
        var report = forwardReturnByDecile(scores, candlesBySymbol, horizonBars)
        return buildString {
            appendLine("horizon $horizonBars bars")
            appendLine(accounting(report))
            appendLine("  decile   n      score       centre bps")
            report.deciles.forEach { decile ->
                appendLine(
                    "  %-8d %-6d %-11s %s".format(
                        decile.decile,
                        decile.observationCount,
                        "${decile.lowestScore}..${decile.highestScore}",
                        decile.centreForwardReturnBps?.toString() ?: "refused",
                    ),
                )
            }
            appendLine("  top-minus-bottom: ${report.topMinusBottomBps ?: "refused"} bps")
        }
    }

    /**
     * Named before the table, not after it. A retrospective that silently discarded what it could
     * not price would read exactly like one that priced everything, and the reader would have no
     * way to know which they were looking at.
     */
    private fun accounting(report: ForwardReturnReport) =
        "  held=${report.heldCount} dropped: " +
            "no-entry-bar=${report.droppedNoEntryBar} " +
            "no-exit-bar=${report.droppedNoExitBar} " +
            "unpriced-entry=${report.droppedUnpricedEntry}"
}
