package com.discountscreener.android.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChartBatchPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = context.deleteDatabase(DB_NAME).let { }
    @After fun tearDown() = context.deleteDatabase(DB_NAME).let { }

    @Test
    fun repeated_chart_captures_in_one_batch_merge_in_order_and_keep_each_range() = runTest {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        try {
            store.persistBatch(
                listOf(chart("AAA", ChartRange.Year, 1, 2), chart("BBB", ChartRange.Year, 1, 2)),
                emptyList(),
            )
            store.persistBatch(
                listOf(
                    chart("AAA", ChartRange.Year, 2, 3),
                    chart("BBB", ChartRange.Year, 2, 3),
                    chart("AAA", ChartRange.Year, 3, 4),
                    chart("AAA", ChartRange.Month, 7, 8),
                ),
                emptyList(),
            )

            val aaa = store.loadPricingHistory("AAA").associateBy { it.range }
            val bbb = store.loadPricingHistory("BBB").associateBy { it.range }
            assertEquals((1..4).map { barEpoch(ChartRange.Year, it) }, aaa.getValue(ChartRange.Year).candles.map { it.epochSeconds })
            assertEquals((7..8).map { barEpoch(ChartRange.Month, it) }, aaa.getValue(ChartRange.Month).candles.map { it.epochSeconds })
            assertEquals((1..3).map { barEpoch(ChartRange.Year, it) }, bbb.getValue(ChartRange.Year).candles.map { it.epochSeconds })
        } finally {
            store.close()
        }
    }

    @Test
    fun batch_cleanup_keeps_the_latest_capture_for_each_symbol() = runTest {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        try {
            store.persistBatch(listOf(snapshot("AAA", 100), snapshot("BBB", 200)), emptyList())
            store.persistBatch(listOf(snapshot("AAA", 110), snapshot("BBB", 210)), emptyList())

            val db = store.readableDatabase
            assertEquals(2L, db.compileStatement("SELECT COUNT(*) FROM raw_capture").simpleQueryForLong())
            assertEquals(2L, db.compileStatement("SELECT COUNT(*) FROM raw_latest").simpleQueryForLong())
            assertEquals(110L, latestPrice(db, "AAA"))
            assertEquals(210L, latestPrice(db, "BBB"))
        } finally {
            store.close()
        }
    }

    @Test
    fun batch_cleanup_preserves_501_symbols_across_sql_parameter_chunks() = runTest {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        try {
            val symbols = (1..501).map { index -> "S$index" }
            store.persistBatch(symbols.map { snapshot(it, 100) }, emptyList())
            store.persistBatch(symbols.map { snapshot(it, 110) }, emptyList())

            assertEquals(501L, store.readableDatabase.compileStatement("SELECT COUNT(*) FROM raw_capture").simpleQueryForLong())
            assertEquals(110L, latestPrice(store.readableDatabase, symbols.last()))
        } finally {
            store.close()
        }
    }

    private fun chart(symbol: String, range: ChartRange, first: Int, last: Int) = RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.ChartCandles,
        scopeKey = range.name,
        capturedAt = NOW,
        payload = RawCapturePayload.Chart(range, (first..last).map { index ->
            HistoricalCandle(barEpoch(range, index), 100, 100, 100, 100, 1)
        }),
    )

    private fun barEpoch(range: ChartRange, index: Int): Long =
        index.toLong() * if (range == ChartRange.Year) 7L * 86_400L else 86_400L

    private fun snapshot(symbol: String, price: Long) = RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.Snapshot,
        scopeKey = null,
        capturedAt = NOW,
        payload = RawCapturePayload.Snapshot(MarketSnapshot(symbol, profitable = true, marketPriceCents = price, intrinsicValueCents = price)),
    )

    private fun latestPrice(db: android.database.sqlite.SQLiteDatabase, symbol: String): Long =
        db.rawQuery(
            """SELECT payload_json FROM raw_capture JOIN raw_latest ON raw_capture.id = raw_latest.capture_id
                WHERE raw_latest.symbol = ?""",
            arrayOf(symbol),
        ).use { cursor ->
            check(cursor.moveToFirst())
            val payload = kotlinx.serialization.json.Json.decodeFromString<RawCapturePayload>(cursor.getString(0))
            (payload as RawCapturePayload.Snapshot).value.marketPriceCents
        }

    private companion object {
        const val DB_NAME = "chart_batch_persistence_test.sqlite3"
        const val NOW = 1_700_000_000L
    }
}
