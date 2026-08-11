package com.discountscreener.core.backtest

import com.discountscreener.core.math.robustCentre
import com.discountscreener.core.model.HistoricalCandle
import kotlin.math.roundToInt

/**
 * Does a score precede a return? The only question in this effort that history can answer today.
 *
 * There are no point-in-time fundamentals, no point-in-time analyst targets and no point-in-time
 * regime. Replaying those from today's values is look-ahead bias, and it produces a number that
 * looks like proof. Candles are dated, so the technical bucket — and only the technical bucket —
 * can be replayed honestly. See [PointInTimeTechnicals] for the half of this that does the scoring;
 * this half only measures what happened next.
 *
 * The evaluator is deliberately able to report *no* signal. An instrument tested only against a
 * series built to succeed cannot be trusted when it says nothing is there, and nothing-is-there is
 * the outcome most likely to matter here.
 */

/** One score, for one symbol, as of the close of one bar. */
data class DatedScore(
    val symbol: String,
    val scoredAtEpochSeconds: Long,
    val score: Int,
)

/**
 * @param decile 1 for the lowest-scoring tenth, [DECILE_COUNT] for the highest.
 * @param centreForwardReturnBps the trimmed centre of the tenth's forward returns, or null when the
 *   tenth cannot support a centre. **This is the typical name, not a portfolio return.** Forward
 *   returns are fat-tailed and one takeover moves a plain mean by more than the effect being
 *   measured, so [robustCentre] is what is reported; a caller wanting the return of *holding* the
 *   whole tenth is asking a different question and must compute it deliberately.
 */
data class DecileResult(
    val decile: Int,
    val observationCount: Int,
    val lowestScore: Int,
    val highestScore: Int,
    val centreForwardReturnBps: Int?,
)

/**
 * Every observation is accounted for, held or dropped, and the drops are named by reason.
 *
 * A backtest that quietly discards what it cannot price reads exactly like a backtest that priced
 * everything. These counts are the difference, and they belong in whatever reports the result.
 */
data class ForwardReturnReport(
    val horizonBars: Int,
    val deciles: List<DecileResult>,
    val heldCount: Int,
    val droppedNoEntryBar: Int,
    val droppedNoExitBar: Int,
    val droppedUnpricedEntry: Int,
) {
    /**
     * The headline: the top tenth's centre less the bottom tenth's. Null unless both tenths reported
     * a centre, because a headline computed from a refusal is worse than no headline.
     */
    val topMinusBottomBps: Int?
        get() {
            if (deciles.size < DECILE_COUNT) return null
            var top = deciles.last().centreForwardReturnBps ?: return null
            var bottom = deciles.first().centreForwardReturnBps ?: return null
            return top - bottom
        }
}

/** Ten tenths. Fewer observations than this cannot be cut into tenths and are refused. */
const val DECILE_COUNT = 10

/**
 * The forward return of each score decile, over [horizonBars] bars.
 *
 * **Entry is the close of the first bar strictly after the scoring date, not the scoring bar's own
 * close.** A score read off day D's close cannot also be traded at day D's close — that trade needs
 * the closing price twice, once to decide and once to fill, and a backtest that allows it earns a
 * free look at every move it is measuring. One bar of delay is the cheapest honest rule.
 *
 * Exit is [horizonBars] bars after the entry bar. Bars, not calendar days: the series decides what
 * a trading day is, and naming the parameter days would invite a caller to pass 30 for a month.
 *
 * Deciles are cut by rank over all held observations, ties broken by symbol and date so the split is
 * reproducible. **Heavily tied scores make the split meaningless rather than wrong** — if a model
 * gives most names the same score, its tenths differ only in the tiebreak, and any spread between
 * them is the tiebreak's, not the model's. [DecileResult.lowestScore] and
 * [DecileResult.highestScore] are reported so that condition is visible instead of inferred.
 */
fun forwardReturnByDecile(
    scores: List<DatedScore>,
    candlesBySymbol: Map<String, List<HistoricalCandle>>,
    horizonBars: Int,
): ForwardReturnReport {
    require(horizonBars > 0) { "a horizon of $horizonBars bars measures nothing" }
    var held = mutableListOf<Held>()
    var noEntryBar = 0
    var noExitBar = 0
    var unpricedEntry = 0
    var seriesCache = mutableMapOf<String, List<HistoricalCandle>>()

    scores.forEach { score ->
        var bars = seriesCache.getOrPut(score.symbol) {
            candlesBySymbol[score.symbol].orEmpty().sortedBy { it.epochSeconds }
        }
        var entryIndex = bars.indexOfFirst { it.epochSeconds > score.scoredAtEpochSeconds }
        if (entryIndex < 0) {
            noEntryBar++
            return@forEach
        }
        var exitIndex = entryIndex + horizonBars
        if (exitIndex > bars.lastIndex) {
            noExitBar++
            return@forEach
        }
        var entryCents = bars[entryIndex].closeCents
        if (entryCents <= 0L) {
            unpricedEntry++
            return@forEach
        }
        var exitCents = bars[exitIndex].closeCents
        held += Held(
            score = score,
            forwardReturnBps = (exitCents - entryCents).toDouble() / entryCents.toDouble() * 10_000.0,
        )
    }

    return ForwardReturnReport(
        horizonBars = horizonBars,
        deciles = cutIntoDeciles(held),
        heldCount = held.size,
        droppedNoEntryBar = noEntryBar,
        droppedNoExitBar = noExitBar,
        droppedUnpricedEntry = unpricedEntry,
    )
}

private class Held(val score: DatedScore, val forwardReturnBps: Double)

/**
 * Empty below [DECILE_COUNT] observations, on purpose. Ten tenths cut from seven rows would put one
 * observation in some tenths and none in others, and the report would name a spread between two
 * single trades.
 */
private fun cutIntoDeciles(held: List<Held>): List<DecileResult> {
    if (held.size < DECILE_COUNT) return emptyList()
    var ordered = held.sortedWith(
        compareBy({ it.score.score }, { it.score.symbol }, { it.score.scoredAtEpochSeconds }),
    )
    return ordered.withIndex()
        .groupBy { (index, _) -> index * DECILE_COUNT / ordered.size }
        .toSortedMap()
        .map { (bucket, entries) ->
            var members = entries.map { it.value }
            DecileResult(
                decile = bucket + 1,
                observationCount = members.size,
                lowestScore = members.first().score.score,
                highestScore = members.last().score.score,
                centreForwardReturnBps = robustCentre(members.map { it.forwardReturnBps })
                    ?.roundToInt(),
            )
        }
}
