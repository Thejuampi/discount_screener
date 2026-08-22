package com.discountscreener.android.domain.usecase

import com.discountscreener.android.data.debug.OutcomeReportBuilder
import com.discountscreener.android.data.market.DailyCandleSource
import com.discountscreener.android.domain.model.ScoreJournalRow
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the report landed, and how much journal it had to read. */
data class OutcomeResult(val path: String, val rowCount: Int, val symbolCount: Int)

/**
 * The journal's reader side. Narrow for the same reason [DailyCandleSource] is: the store writes
 * scores on every refresh and this use case reads them once on demand, and one combined interface
 * would hand each side a method it has no business calling.
 */
fun interface ScoreJournalSource {
    suspend fun load(): List<ScoreJournalRow>
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
 * Runs the outcome measurement over the score journal and writes the report.
 *
 * This is the reading half of the journal's reason to exist: the app records what each model said
 * on the day it said it, and this joins those rows to the daily bars that followed. Same private-
 * storage discipline as the retrospective — readable with
 * `adb exec-out run-as <applicationId> cat files/<name>.txt`.
 */
class RunOutcomeReportUseCase(
    private val journalSource: ScoreJournalSource,
    private val candleSource: DailyCandleSource,
    private val streetDiagnosticSource: StreetDiagnosticSource,
    private val exportDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(profile: String): OutcomeResult = withContext(ioDispatcher) {
        var rows = journalSource.load()
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
