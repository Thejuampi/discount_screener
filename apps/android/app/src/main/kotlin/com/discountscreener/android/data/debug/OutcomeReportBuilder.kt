package com.discountscreener.android.data.debug

import com.discountscreener.android.domain.model.ScoreJournalRow
import com.discountscreener.core.backtest.DatedScore
import com.discountscreener.core.backtest.ScoreAblationObservation
import com.discountscreener.core.backtest.ScoreSeries
import com.discountscreener.core.backtest.V5OutcomeAblations
import com.discountscreener.core.backtest.outcomeReport
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.OpportunityScoringModel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The outcome report: what each journalled model's scores predicted, and what happened next.
 *
 * Built on the score journal — the only point-in-time record of fundamentals, forecast, regime and
 * composite the app has — so every bucket can be judged, where the retrospective can replay the
 * technicals alone. Model sections show their own history. The paired experiment requires exact
 * V2/V5 symbol-time matches.
 *
 * **The street is a context column, never an input.** Analyst upside is printed once per report,
 * labeled `[DIAGNOSTIC ONLY]`, and no spread on this page can move when it changes. That property
 * is pinned by test, because a scoreboard that could nudge the game would not be a scoreboard.
 */
object OutcomeReportBuilder {
    /** One month, one quarter, half a year, in trading days. */
    val HORIZONS = listOf(21, 63, 126)

    data class Inputs(
        val profile: String,
        val generatedAtEpochSeconds: Long,
        val rows: List<ScoreJournalRow>,
        val candlesBySymbol: Map<String, List<HistoricalCandle>>,
        /** Street upside per symbol in bps. Shown as context; read by nothing. */
        val streetUpsideBpsBySymbol: Map<String, Int>,
    )

    fun build(inputs: Inputs): String {
        var models = inputs.rows.groupBy { it.scoringModel }
        var measuredSymbols = inputs.rows.map { it.symbol }.distinct()
            .filter { it in inputs.candlesBySymbol }
        return buildString {
            appendLine("outcome report — profile ${inputs.profile} — ${date(inputs.generatedAtEpochSeconds)}")
            if (inputs.rows.isEmpty()) {
                appendLine("no journal rows yet — every completed refresh adds paired V1 through V5 passes")
                return@buildString
            }
            appendLine(
                "journal rows: " + models.entries
                    .sortedBy { modelOrder(it.key) }
                    .joinToString(" ") { (model, rows) -> "$model=${rows.size}" },
            )
            appendLine("symbols with daily bars: ${measuredSymbols.size}")
            appendLine(streetLine(measuredSymbols, inputs.streetUpsideBpsBySymbol))
            models.entries
                .sortedBy { modelOrder(it.key) }
                .forEach { (model, rows) -> append(modelSection(model, rows, inputs.candlesBySymbol)) }
            if ("AggressiveV2" in models || "AggressiveV5" in models) {
                append(pairedExperimentSection(inputs.rows, inputs.candlesBySymbol))
            }
        }
    }

    private fun modelSection(
        model: String,
        rows: List<ScoreJournalRow>,
        candlesBySymbol: Map<String, List<HistoricalCandle>>,
    ): String = buildString {
        appendLine()
        appendLine("== $model ==")
        appendLine("window: ${date(rows.minOf { it.scoredAtEpochSeconds })}..${date(rows.maxOf { it.scoredAtEpochSeconds })}")
        seriesFor(model, rows).forEach { series ->
            appendLine("-- ${series.name.substringAfter('/')} --")
            var report = outcomeReport(listOf(series), candlesBySymbol, HORIZONS)
            HORIZONS.forEachIndexed { index, horizon ->
                var outcome = report.outcomes[index]
                appendLine(horizonLine(horizon, outcome))
            }
        }
    }

    private fun horizonLine(horizon: Int, outcome: com.discountscreener.core.backtest.SeriesOutcome): String {
        var report = outcome.report
        var accounting = "held=${report.heldCount} dropped: " +
            "no-entry-bar=${report.droppedNoEntryBar} " +
            "no-exit-bar=${report.droppedNoExitBar} " +
            "unpriced-entry=${report.droppedUnpricedEntry} " +
            "same-entry-bar=${report.droppedSameEntryBar}"
        var spread = outcome.spreadBps
        if (spread == null) {
            return "horizon $horizon bars: insufficient observations to name a spread ($accounting)"
        }
        return "horizon $horizon bars: top-minus-bottom $spread bps ($accounting)"
    }

    private fun pairedExperimentSection(
        rows: List<ScoreJournalRow>,
        candlesBySymbol: Map<String, List<HistoricalCandle>>,
    ): String = buildString {
        val experiment = V5OutcomeAblations.build(rows.mapNotNull { row -> row.toAblationObservation() })
        appendLine()
        appendLine("== Paired V2/V5 experiment ==")
        appendLine("paired rows: ${experiment.pairedRowCount}")
        if (experiment.pairedRowCount == 0) {
            appendLine("no exact symbol-time pairs; legacy rows cannot support a controlled comparison")
            return@buildString
        }
        experiment.series.forEach { series ->
            appendLine("-- ${series.name} --")
            var report = outcomeReport(listOf(series), candlesBySymbol, HORIZONS)
            HORIZONS.forEachIndexed { index, horizon ->
                appendLine(horizonLine(horizon, report.outcomes[index]))
            }
        }
    }

    private fun ScoreJournalRow.toAblationObservation(): ScoreAblationObservation? {
        val model = runCatching { OpportunityScoringModel.valueOf(scoringModel) }.getOrNull()
            ?: return null
        return ScoreAblationObservation(
            symbol = symbol,
            model = model,
            scoredAtEpochSeconds = scoredAtEpochSeconds,
            fundamentalsScore = fundamentalsScore,
            technicalScore = technicalScore,
            forecastScore = forecastScore,
            regimeScore = regimeScore,
            compositeScore = compositeScore,
            compositeScoreBase = compositeScoreBase,
        )
    }

    /**
     * One series per metric the journal carries. A bucket that did not report on a row is absent
     * from that row's contribution — absent is absent, not zero, and zero is a score the engine
     * can produce.
     */
    private fun seriesFor(model: String, rows: List<ScoreJournalRow>): List<ScoreSeries> = listOf(
        metric("$model/composite") { it.compositeScore },
        metric("$model/compositeBase") { it.compositeScoreBase },
        metric("$model/fundamentals") { it.fundamentalsScore },
        metric("$model/technical") { it.technicalScore },
        metric("$model/forecast") { it.forecastScore },
        metric("$model/regime") { it.regimeScore },
    ).mapNotNull { (metricName, extract) ->
        var scores = rows.mapNotNull { row ->
            extract(row)?.let { DatedScore(row.symbol, row.scoredAtEpochSeconds, it) }
        }
        if (scores.isEmpty()) null else ScoreSeries(metricName, scores)
    }

    private fun metric(name: String, extract: (ScoreJournalRow) -> Int?) =
        name to extract

    private fun streetLine(symbols: List<String>, streetUpsideBpsBySymbol: Map<String, Int>): String {
        var upsides = symbols.mapNotNull { streetUpsideBpsBySymbol[it] }.sorted()
        if (upsides.isEmpty()) {
            return "street median upside: unavailable [DIAGNOSTIC ONLY]"
        }
        var median = if (upsides.size % 2 == 1) {
            upsides[upsides.size / 2]
        } else {
            (upsides[upsides.size / 2 - 1] + upsides[upsides.size / 2]) / 2
        }
        return "street median upside (measured universe): $median bps [DIAGNOSTIC ONLY]"
    }

    /** Newest model first; anything unlisted keeps alphabetical order behind the known ones. */
    private fun modelOrder(model: String): Int =
        listOf("AggressiveV5", "AggressiveV4", "AggressiveV3", "AggressiveV2", "Aggressive")
            .indexOf(model)
            .let { if (it < 0) Int.MAX_VALUE else it }

    private fun date(epochSeconds: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC))
}
