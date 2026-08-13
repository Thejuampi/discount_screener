package com.discountscreener.android.domain.usecase

import com.discountscreener.android.data.debug.RetrospectiveReport
import com.discountscreener.android.data.market.DailyCandleSource
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the report landed, and how many symbols it had bars for. */
data class RetrospectiveResult(val path: String, val symbolCount: Int)

/**
 * Runs the technicals retrospective over the stored daily bars and writes the report.
 *
 * Private storage, like the score export: a debug build is readable with
 * `adb exec-out run-as <applicationId> cat files/<name>.txt`, so no permission is needed.
 *
 * Takes a [DailyCandleSource] rather than the repository, and that is deliberate twice over. These
 * bars are not product state — nothing the user sees reads them — so a `backtestCandles()` accessor
 * on `DashboardRepository` would push a measurement concern into an interface every fake in the
 * suite implements. And the concrete store cannot be built in a plain JVM test, so depending on it
 * would have forced the whole view-model suite onto Robolectric to satisfy one debug button.
 */
class RunRetrospectiveUseCase(
    private val candleSource: DailyCandleSource,
    private val exportDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(profile: String): RetrospectiveResult = withContext(ioDispatcher) {
        var candles = candleSource.loadBacktestCandles()
        var target = File(exportDirectory, "retrospective-$profile.txt")
        target.writeText(RetrospectiveReport.build(candles))
        RetrospectiveResult(target.absolutePath, candles.size)
    }
}
