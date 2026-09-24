package com.discountscreener.android.domain.usecase

import com.discountscreener.android.data.debug.OutcomeReportBuilder
import com.discountscreener.android.data.market.DailyCandleSource
import com.discountscreener.android.domain.model.ScoreJournalRow
import com.discountscreener.android.domain.model.JournalFactors
import com.discountscreener.android.domain.model.ScoringEvaluationSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the report landed, and how much journal it had to read. */
data class OutcomeResult(val path: String, val rowCount: Int, val symbolCount: Int)

/** Legacy reader for reports built before atomic evaluation snapshots existed. */
fun interface ScoreJournalSource {
    suspend fun load(): List<ScoreJournalRow>
}

/** Profile-aware source of atomic scoring cohorts. */
fun interface ScoringEvaluationSnapshotSource {
    suspend fun load(profile: String): List<ScoringEvaluationSnapshot>
}

/**
 * Street upside per symbol, in bps, for the report's context line.
 *
 * Diagnostic only by contract: the builder prints it labeled `[DIAGNOSTIC ONLY]`, and a test pins
 * that no spread can move when these numbers change. The street is the scoreboard here too.
 */
fun interface StreetDiagnosticSource {
    suspend fun upsideBpsBySymbol(): Map<String, Int>
}

/**
 * Runs the outcome measurement over profile-scoped atomic snapshots and writes the report.
 *
 * The app records what each model said on the day it said it.
 * This use case joins those rows to later daily bars. It keeps the retrospective's private-storage
 * discipline and remains readable with
 * `adb exec-out run-as <applicationId> cat files/<name>.txt`.
 */
class RunOutcomeReportUseCase(
    private val journalSource: ScoreJournalSource? = null,
    private val evaluationSnapshotSource: ScoringEvaluationSnapshotSource? = null,
    private val candleSource: DailyCandleSource,
    private val streetDiagnosticSource: StreetDiagnosticSource,
    private val exportDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(profile: String): OutcomeResult = withContext(ioDispatcher) {
        val rows = evaluationSnapshotSource
            ?.load(profile)
            ?.flatMap { snapshot -> snapshot.toJournalRows() }
            ?: journalSource?.load().orEmpty()
        var candles = candleSource.loadBacktestCandles()
        var street = streetDiagnosticSource.upsideBpsBySymbol()
        var target = File(exportDirectory, "outcome-$profile.txt")
        target.writeText(
            OutcomeReportBuilder.build(
                inputs = OutcomeReportBuilder.Inputs(
                    profile = profile,
                    generatedAtEpochSeconds = System.currentTimeMillis() / 1_000L,
                    rows = rows,
                    candlesBySymbol = candles,
                    streetUpsideBpsBySymbol = street,
                ),
            ),
        )
        OutcomeResult(target.absolutePath, rows.size, candles.size)
    }
}

private fun ScoringEvaluationSnapshot.toJournalRows(): List<ScoreJournalRow> = modelResults.flatMap { result ->
    result.rows.map { row ->
        ScoreJournalRow(
            symbol = row.symbol,
            scoringModel = result.model.name,
            scoredAtEpochSeconds = capturedAtEpochSeconds,
            fundamentalsScore = row.fundamentalsScore,
            technicalScore = row.technicalScore,
            forecastScore = row.forecastScore,
            regimeScore = row.regimeScore,
            compositeScore = row.compositeScore,
            compositeScoreBase = row.compositeScoreBase,
            marketPriceCents = row.marketPriceCents,
            factors = JournalFactors(
                fundamentals = row.fundamentalsFactors,
                technical = row.technicalFactors,
                forecast = row.forecastFactors,
            ),
        )
    }
}
