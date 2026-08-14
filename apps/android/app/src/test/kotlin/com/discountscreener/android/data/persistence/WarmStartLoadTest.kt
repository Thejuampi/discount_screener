package com.discountscreener.android.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.PriceHistoryPoint
import kotlinx.coroutines.test.runTest
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
    fun a_second_chart_capture_for_the_same_key_leaves_one_raw_row() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(listOf(chartCapture("AAPL", capturedAt = 100)), emptyList())
            store.persistBatch(listOf(chartCapture("AAPL", capturedAt = 200)), emptyList())

            assertEquals(1, rawCaptureCount(store))
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
                    "VALUES ('AAPL', 'chart-candles', 'Year', 50, '{}')",
            )

            assertEquals(1, store.reclaimRawCaptureSpace())
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

    private fun chartCapture(symbol: String, capturedAt: Long = 100) = RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.ChartCandles,
        scopeKey = ChartRange.Year.name,
        capturedAt = capturedAt,
        payload = RawCapturePayload.Chart(
            range = ChartRange.Year,
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
    ) = SymbolRevisionInput(
        symbol = symbol,
        evaluatedAt = 1_700_000_000L,
        lastSequence = 1,
        updateCount = 1,
        priceHistory = listOf(PriceHistoryPoint(sequence = 1, marketPriceCents = 10_000)),
        payload = EvaluatedSymbolState(
            snapshot = MarketSnapshot(
                symbol = symbol,
                profitable = true,
                marketPriceCents = 10_000,
                intrinsicValueCents = 12_000,
            ),
            chartSummaries = chartSummaries,
        ),
    )

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
    }
}
