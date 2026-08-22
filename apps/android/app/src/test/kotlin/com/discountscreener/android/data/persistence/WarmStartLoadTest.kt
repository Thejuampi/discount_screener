package com.discountscreener.android.data.persistence

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.PriceHistoryPoint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WarmStartLoadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun persist_chart_does_not_write_raw_capture() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(listOf(chartCapture("AAPL", capturedAt = 100)), emptyList())
            store.persistBatch(listOf(chartCapture("AAPL", capturedAt = 200)), emptyList())

            assertEquals(0, rawCaptureCount(store))
        } finally {
            store.close()
        }
    }

    @Test
    fun persist_chart_writes_pricing_candles() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(listOf(chartCapture("AAPL")), emptyList())

            assertEquals(1, pricingCandleCount(store))
        } finally {
            store.close()
        }
    }

    @Test
    fun persist_year_does_not_drop_month_pricing() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(listOf(chartCapture("AAPL", range = ChartRange.Month)), emptyList())
            store.persistBatch(listOf(chartCapture("AAPL", range = ChartRange.Year)), emptyList())

            assertEquals(2, pricingCandleCount(store))
        } finally {
            store.close()
        }
    }

    @Test
    fun reclaim_drops_raw_rows_that_latest_no_longer_points_at() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(listOf(chartCapture("AAPL", capturedAt = 100)), emptyList())
            store.writableDatabase.execSQL(
                "INSERT INTO raw_capture (symbol, capture_kind, scope_key, captured_at, payload_json) " +
                    "VALUES ('AAPL', 'snapshot', NULL, 50, '{}')",
            )

            assertEquals(1, store.reclaimPersistenceSpace())
        } finally {
            store.close()
        }
    }

    @Test
    fun reclaim_drops_leftover_chart_json_even_when_latest_points_at_it() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.writableDatabase.execSQL(
                "INSERT INTO raw_capture (symbol, capture_kind, scope_key, captured_at, payload_json) " +
                    "VALUES ('AAPL', 'chart-candles', 'Year', 50, '{}')",
            )
            store.writableDatabase.execSQL(
                "INSERT INTO raw_latest (symbol, capture_key, capture_id) VALUES ('AAPL', 'chart:Year', 1)",
            )

            store.reclaimPersistenceSpace()

            assertEquals(0, chartRawCount(store))
        } finally {
            store.close()
        }
    }

    @Test
    fun reclaim_copies_latest_chart_json_into_pricing_before_delete() = runTest {
        var store = SQLiteStateStore(context)
        try {
            var payload = Json.encodeToString(
                RawCapturePayload.serializer(),
                chartCapture("AAPL").payload,
            )
            var captureId = store.writableDatabase.insertOrThrow(
                "raw_capture",
                null,
                ContentValues().apply {
                    put("symbol", "AAPL")
                    put("capture_kind", "chart-candles")
                    put("scope_key", "Year")
                    put("captured_at", 50)
                    put("payload_json", payload)
                },
            )
            store.writableDatabase.insertOrThrow(
                "raw_latest",
                null,
                ContentValues().apply {
                    put("symbol", "AAPL")
                    put("capture_key", "chart:Year")
                    put("capture_id", captureId)
                },
            )

            store.reclaimPersistenceSpace()

            assertEquals(
                10_050L,
                store.loadPricingHistory("AAPL").single { it.range == ChartRange.Year }.candles.single().closeCents,
            )
        } finally {
            store.close()
        }
    }

    /**
     * Each write here moves the price, so each one is a revision the history must file. A repeat
     * of the payload before it is dropped instead of filed, and would never reach the cap.
     */
    @Test
    fun persist_batch_caps_revision_history_at_max() = runTest {
        var store = SQLiteStateStore(context)
        try {
            repeat(SQLiteStateStore.MAX_REVISION_HISTORY + 1) { index ->
                store.persistBatch(
                    emptyList(),
                    listOf(
                        revision(
                            "AAPL",
                            evaluatedAt = 1_700_000_000L + index,
                            marketPriceCents = 10_000L + index,
                        ),
                    ),
                )
            }

            assertEquals(SQLiteStateStore.MAX_REVISION_HISTORY.toLong(), revisionCount(store))
        } finally {
            store.close()
        }
    }

    @Test
    fun warm_start_does_not_load_chart_candles() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(listOf(chartCapture("AAPL")), listOf(revision("AAPL")))

            assertEquals(emptyList<PersistedChartRecord>(), store.loadWarmStart(listOf("AAPL")).chartCache)
        } finally {
            store.close()
        }
    }

    @Test
    fun warm_start_loads_only_the_requested_symbols() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(
                emptyList(),
                listOf(revision("AAPL"), revision("MSFT")),
            )

            assertEquals(listOf("AAPL"), store.loadWarmStart(listOf("AAPL")).symbolStates.map { it.symbol })
        } finally {
            store.close()
        }
    }

    @Test
    fun warm_start_restores_chart_summaries_from_the_payload() = runTest {
        var summary = ChartRangeSummary(
            range = ChartRange.Year,
            capturedAt = 200,
            candleCount = 12,
            latestCloseCents = 11_000,
        )
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(
                emptyList(),
                listOf(revision("AAPL", chartSummaries = listOf(summary))),
            )

            assertEquals(
                listOf(summary),
                store.loadWarmStart(listOf("AAPL")).symbolStates.single().chartSummaries,
            )
        } finally {
            store.close()
        }
    }

    private fun rawCaptureCount(store: SQLiteStateStore): Long =
        store.writableDatabase.compileStatement("SELECT COUNT(*) FROM raw_capture").simpleQueryForLong()

    private fun chartRawCount(store: SQLiteStateStore): Long =
        store.writableDatabase.compileStatement(
            "SELECT COUNT(*) FROM raw_capture WHERE capture_kind = 'chart-candles'",
        ).simpleQueryForLong()

    private fun pricingCandleCount(store: SQLiteStateStore): Long =
        store.writableDatabase.compileStatement("SELECT COUNT(*) FROM pricing_candle").simpleQueryForLong()

    private fun revisionCount(store: SQLiteStateStore): Long =
        store.writableDatabase.compileStatement("SELECT COUNT(*) FROM symbol_revision").simpleQueryForLong()

    private fun chartCapture(
        symbol: String,
        capturedAt: Long = 100,
        range: ChartRange = ChartRange.Year,
    ) = RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.ChartCandles,
        scopeKey = range.name,
        capturedAt = capturedAt,
        payload = RawCapturePayload.Chart(
            range = range,
            candles = listOf(
                HistoricalCandle(
                    epochSeconds = 1_700_000_000L,
                    openCents = 10_000,
                    highCents = 10_100,
                    lowCents = 9_900,
                    closeCents = 10_050,
                    volume = 1_000,
                ),
            ),
        ),
    )

    private fun revision(
        symbol: String,
        chartSummaries: List<ChartRangeSummary> = emptyList(),
        evaluatedAt: Long = 1_700_000_000L,
        marketPriceCents: Long = 10_000,
    ) = SymbolRevisionInput(
        symbol = symbol,
        evaluatedAt = evaluatedAt,
        lastSequence = 1,
        updateCount = 1,
        priceHistory = listOf(PriceHistoryPoint(sequence = 1, marketPriceCents = marketPriceCents)),
        payload = EvaluatedSymbolState(
            snapshot = MarketSnapshot(
                symbol = symbol,
                profitable = true,
                marketPriceCents = marketPriceCents,
                intrinsicValueCents = 12_000,
            ),
            chartSummaries = chartSummaries,
        ),
    )

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
    }
}
