package com.discountscreener.android.data.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Isolated warm-start bench. The live file name is [LIVE_DB_NAME]. This class opens
 * [BENCH_DB_NAME] only, then deletes it. It never touches the product database.
 *
 * Default scale is small enough for CI. Set DS_WARMSTART_BENCH=1 for a phone-like file.
 */
@RunWith(RobolectricTestRunner::class)
class WarmStartImpactBenchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        deleteBenchFiles()
    }

    @After
    fun tearDown() {
        deleteBenchFiles()
    }

    @Test
    fun new_warm_start_is_faster_than_decoding_every_latest_chart() = runTest {
        var scale = benchScale()
        var store = SQLiteStateStore(context, databaseFileName = BENCH_DB_NAME)
        try {
            var seeded = seedBloatedDatabase(store, scale)
            var oldMs = medianMillis(RUNS) { loadLegacyLatestCharts(store) }
            var newMs = medianMillis(RUNS) {
                store.loadWarmStart(seeded.trackedSymbols)
            }
            var sizeBefore = databaseBytes()
            var rawBefore = rawCaptureCount(store)
            var deleted = store.reclaimPersistenceSpace()
            var sizeAfter = databaseBytes()
            var rawAfter = rawCaptureCount(store)
            writeReport(
                scale = scale,
                seeded = seeded,
                oldMs = oldMs,
                newMs = newMs,
                sizeBefore = sizeBefore,
                sizeAfter = sizeAfter,
                rawBefore = rawBefore,
                rawAfter = rawAfter,
                deleted = deleted,
            )

            assertTrue(
                "new warm start ${newMs}ms should beat legacy chart decode ${oldMs}ms",
                newMs < oldMs,
            )
        } finally {
            store.close()
        }
    }

    private fun benchScale(): BenchScale {
        var large = System.getProperty("dsWarmStartBench") == "1" ||
            System.getenv("DS_WARMSTART_BENCH") == "1"
        return if (large) PHONE_SCALE else CI_SCALE
    }

    private fun seedBloatedDatabase(store: SQLiteStateStore, scale: BenchScale): SeedStats {
        var payloadByRange = ChartRange.entries.associateWith { range ->
            json.encodeToString(
                RawCapturePayload.serializer(),
                RawCapturePayload.Chart(range, candles(scale.candlesFor(range))),
            )
        }
        var snapshotJson = json.encodeToString(
            MarketSnapshot("SEED", profitable = true, marketPriceCents = 10_000, intrinsicValueCents = 12_000),
        )
        var payloadJson = json.encodeToString(
            EvaluatedSymbolState.serializer(),
            EvaluatedSymbolState(
                snapshot = MarketSnapshot("SEED", profitable = true, marketPriceCents = 10_000, intrinsicValueCents = 12_000),
                chartSummaries = listOf(
                    ChartRangeSummary(ChartRange.Year, capturedAt = 1_700_000_000L, candleCount = 252, latestCloseCents = 10_000),
                ),
            ),
        )
        var db = store.writableDatabase
        var captureCount = 0
        var started = System.nanoTime()
        db.beginTransaction()
        try {
            repeat(scale.symbolCount) { index ->
                var symbol = "B%03d".format(index)
                ChartRange.entries.forEach { range ->
                    var latestId = 0L
                    repeat(scale.versions) { version ->
                        var values = ContentValues().apply {
                            put("symbol", symbol)
                            put("capture_kind", "chart-candles")
                            put("scope_key", range.name)
                            put("captured_at", 1_700_000_000L + version)
                            put("payload_json", payloadByRange.getValue(range))
                        }
                        latestId = db.insertOrThrow("raw_capture", null, values)
                        captureCount++
                    }
                    db.insertWithOnConflict(
                        "raw_latest",
                        null,
                        ContentValues().apply {
                            put("symbol", symbol)
                            put("capture_key", "chart:${range.name}")
                            put("capture_id", latestId)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                if (index < scale.trackedCount) {
                    db.insertWithOnConflict(
                        "symbol_latest",
                        null,
                        ContentValues().apply {
                            put("symbol", symbol)
                            put("revision_id", index + 1)
                            put("evaluated_at", 1_700_000_000L)
                            put("last_sequence", 1)
                            put("update_count", 1)
                            put("payload_json", payloadJson)
                            put("snapshot_json", snapshotJson)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return SeedStats(
            symbolCount = scale.symbolCount,
            trackedCount = scale.trackedCount,
            versions = scale.versions,
            rawRows = captureCount,
            trackedSymbols = (0 until scale.trackedCount).map { "B%03d".format(it) },
            seedMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    private fun loadLegacyLatestCharts(store: SQLiteStateStore): Int {
        var count = 0
        store.readableDatabase.rawQuery(
            """
                SELECT raw_capture.payload_json
                FROM raw_latest
                JOIN raw_capture ON raw_capture.id = raw_latest.capture_id
                WHERE raw_latest.capture_key LIKE 'chart:%'
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                json.decodeFromString(RawCapturePayload.serializer(), cursor.getString(0))
                count++
            }
        }
        return count
    }

    private fun candles(count: Int): List<HistoricalCandle> = List(count) { index ->
        HistoricalCandle(
            epochSeconds = 1_600_000_000L + index.toLong() * 86_400L,
            openCents = 10_000L + index,
            highCents = 10_100L + index,
            lowCents = 9_900L + index,
            closeCents = 10_050L + index,
            volume = 1_000L + index,
        )
    }

    private suspend fun medianMillis(runs: Int, block: suspend () -> Unit): Long {
        var samples = LongArray(runs)
        repeat(runs) { index ->
            var started = System.nanoTime()
            block()
            samples[index] = (System.nanoTime() - started) / 1_000_000
        }
        return samples.sorted()[runs / 2]
    }

    private fun rawCaptureCount(store: SQLiteStateStore): Long =
        store.writableDatabase.compileStatement("SELECT COUNT(*) FROM raw_capture").simpleQueryForLong()

    private fun databaseBytes(): Long {
        var dbFile = context.getDatabasePath(BENCH_DB_NAME)
        return listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    private fun writeReport(
        scale: BenchScale,
        seeded: SeedStats,
        oldMs: Long,
        newMs: Long,
        sizeBefore: Long,
        sizeAfter: Long,
        rawBefore: Long,
        rawAfter: Long,
        deleted: Int,
    ) {
        var speedup = if (newMs == 0L) oldMs.toDouble() else oldMs.toDouble() / newMs.toDouble()
        var report = buildString {
            appendLine("Warm-start impact bench")
            appendLine("Database file: $BENCH_DB_NAME (isolated; not $LIVE_DB_NAME)")
            appendLine("Scale: ${scale.label}")
            appendLine("Symbols: ${seeded.symbolCount}  tracked: ${seeded.trackedCount}  versions: ${seeded.versions}")
            appendLine("Seed: ${seeded.rawRows} raw_capture rows in ${seeded.seedMs} ms")
            appendLine()
            appendLine("| Metric | Before | After |")
            appendLine("| --- | --- | --- |")
            appendLine("| File size | ${formatMb(sizeBefore)} | ${formatMb(sizeAfter)} |")
            appendLine("| raw_capture rows | $rawBefore | $rawAfter |")
            appendLine("| Orphan rows deleted |  | $deleted |")
            appendLine("| Legacy load (decode every latest chart) | ${oldMs} ms |  |")
            appendLine("| New load (tracked rows, no chart JSON) | ${newMs} ms |  |")
            appendLine("| Speedup | ${"%.1f".format(speedup)}x |  |")
        }
        println(report)
        var reportPath = System.getProperty("dsWarmStartBenchReport")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("DS_WARMSTART_BENCH_REPORT")
        var outFiles = buildList {
            if (!reportPath.isNullOrBlank()) {
                add(File(reportPath))
            }
            add(File(context.cacheDir, "warmstart-bench-report.txt"))
        }
        outFiles.forEach { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(report)
            }
        }
    }

    private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)

    private fun deleteBenchFiles() {
        context.deleteDatabase(BENCH_DB_NAME)
        check(BENCH_DB_NAME != LIVE_DB_NAME) { "bench must not use the live database name" }
    }

    private data class BenchScale(
        val label: String,
        val symbolCount: Int,
        val trackedCount: Int,
        val versions: Int,
        val candlesByRange: Map<ChartRange, Int>,
    ) {
        fun candlesFor(range: ChartRange): Int = candlesByRange.getValue(range)
    }

    private data class SeedStats(
        val symbolCount: Int,
        val trackedCount: Int,
        val versions: Int,
        val rawRows: Int,
        val trackedSymbols: List<String>,
        val seedMs: Long,
    )

    private companion object {
        const val LIVE_DB_NAME = "discount_screener_state.sqlite3"
        const val BENCH_DB_NAME = "discount_screener_warmstart_bench.sqlite3"
        const val RUNS = 3
        val CI_SCALE = BenchScale(
            label = "ci",
            symbolCount = 40,
            trackedCount = 20,
            versions = 5,
            candlesByRange = ChartRange.entries.associateWith { 80 },
        )
        val PHONE_SCALE = BenchScale(
            label = "phone-like",
            symbolCount = 400,
            trackedCount = 20,
            versions = 10,
            candlesByRange = mapOf(
                ChartRange.Day to 78,
                ChartRange.Week to 26,
                ChartRange.Month to 22,
                ChartRange.Year to 252,
                ChartRange.FiveYears to 756,
                ChartRange.TenYears to 1260,
            ),
        )
    }
}
