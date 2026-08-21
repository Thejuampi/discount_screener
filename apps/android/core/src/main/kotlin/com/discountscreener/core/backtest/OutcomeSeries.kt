package com.discountscreener.core.backtest

import com.discountscreener.core.model.HistoricalCandle

/**
 * One named point-in-time score series — a model's composite, one of its buckets, or the
 * market-dimension-free base — ready to be judged against what happened next.
 *
 * The name is the report's row label, so it carries the model and the metric
 * (`AggressiveV4/composite`), never just one of them: two models journalled on the same days are
 * the comparison this whole layer exists to make.
 */
data class ScoreSeries(
    val name: String,
    val scores: List<DatedScore>,
)

/**
 * One series' verdict at one horizon.
 *
 * [insufficient] is a first-class answer, not an error: a journal younger than the horizon cannot
 * name a spread, and pretending otherwise with a small-sample number is the one failure this
 * layer must never commit.
 */
data class SeriesOutcome(
    val seriesName: String,
    val horizonBars: Int,
    val report: ForwardReturnReport,
) {
    /** Null whenever either tenth refused a centre — the same rule [ForwardReturnReport] applies. */
    val spreadBps: Int?
        get() = report.topMinusBottomBps

    val insufficient: Boolean
        get() = report.topMinusBottomBps == null
}

/**
 * Every series × horizon verdict, with the horizons carried beside them so a renderer never has
 * to guess which bars a number belongs to.
 */
data class OutcomeReport(
    val horizons: List<Int>,
    val outcomes: List<SeriesOutcome>,
) {
    fun forSeries(seriesName: String): List<SeriesOutcome> =
        outcomes.filter { it.seriesName == seriesName }

    fun spread(seriesName: String, horizonBars: Int): Int? =
        outcomes.firstOrNull { it.seriesName == seriesName && it.horizonBars == horizonBars }?.spreadBps
}

/**
 * The forward-return verdict for every series at every requested horizon.
 *
 * A thin fan-out over [forwardReturnByDecile] on purpose. That function owns every honesty rule
 * this measurement depends on — entry on the bar after the score, refusal under ten observations,
 * drops named by reason — and a second implementation of any of them would be a place where the
 * two copies could disagree about what happened.
 */
fun outcomeReport(
    series: List<ScoreSeries>,
    candlesBySymbol: Map<String, List<HistoricalCandle>>,
    horizons: List<Int> = listOf(21, 63, 126),
): OutcomeReport {
    require(horizons.isNotEmpty()) { "no horizons asked, no verdicts owed" }
    var outcomes = series.flatMap { one ->
        horizons.map { horizon ->
            SeriesOutcome(
                seriesName = one.name,
                horizonBars = horizon,
                report = forwardReturnByDecile(one.scores, candlesBySymbol, horizon),
            )
        }
    }
    return OutcomeReport(horizons = horizons, outcomes = outcomes)
}
