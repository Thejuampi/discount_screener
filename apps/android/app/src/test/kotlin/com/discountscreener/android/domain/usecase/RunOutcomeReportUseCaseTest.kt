package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.model.ScoreJournalRow
import com.discountscreener.core.model.HistoricalCandle
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading half of the score journal, wired to fakes so no database is needed to prove the
 * join: journal rows in, daily bars beside them, one report file out.
 */
class RunOutcomeReportUseCaseTest {

    @Test
    fun the_report_is_written_and_the_counts_are_honest() = runTest {
        var directory = tempDirectory()
        var useCase = RunOutcomeReportUseCase(
            journalSource = { listOf(row("AAPL"), row("MSFT", model = "AggressiveV3")) },
            candleSource = TwoBars,
            streetDiagnosticSource = { mapOf("AAPL" to 1_500) },
            exportDirectory = directory,
        )

        var result = useCase("qa")

        assertEquals(2, result.rowCount)
        assertEquals(2, result.symbolCount)
        var report = File(result.path).readText()
        assertTrue(report.startsWith("outcome report — profile qa"))
        assertTrue("== AggressiveV4 ==" in report)
        assertTrue("== AggressiveV3 ==" in report)
        assertTrue("[DIAGNOSTIC ONLY]" in report)
    }

    private fun row(symbol: String, model: String = "AggressiveV4") = ScoreJournalRow(
        symbol = symbol,
        scoringModel = model,
        scoredAtEpochSeconds = 1_700_000_000L,
        fundamentalsScore = 20,
        technicalScore = 22,
        forecastScore = 19,
        regimeScore = 18,
        compositeScore = 45,
        compositeScoreBase = 40,
        marketPriceCents = 10_000L,
    )

    private fun tempDirectory(): File =
        File(System.getProperty("java.io.tmpdir")!!).also { it.mkdirs() }

    private object TwoBars : com.discountscreener.android.data.market.DailyCandleSource {
        override suspend fun loadBacktestCandles(): Map<String, List<HistoricalCandle>> = mapOf(
            "AAPL" to listOf(candle(1), candle(2)),
            "MSFT" to listOf(candle(1), candle(2)),
        )
    }
}

private fun candle(bar: Long) = HistoricalCandle(
    epochSeconds = 1_700_000_000L + bar * 86_400L,
    openCents = 10_000L,
    highCents = 10_000L,
    lowCents = 10_000L,
    closeCents = 10_000L + bar,
    volume = 1_000L,
)
