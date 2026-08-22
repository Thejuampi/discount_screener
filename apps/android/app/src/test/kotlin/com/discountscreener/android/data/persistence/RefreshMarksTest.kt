package com.discountscreener.android.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.model.MarketSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The marks a same-day refresh reads to decide what to leave on file. A quoteSummary is the
 * symbol's own data and moves the mark; a batch price is a price and nothing else, and must not.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshMarksTest {
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
    fun a_quote_summary_capture_is_the_quoted_at_mark() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(listOf(snapshotCapture("AAPL", scopeKey = null, capturedAt = 100)), emptyList())

            assertEquals(100L, store.loadRefreshMarks()["AAPL"]?.quotedAtEpochSeconds)
        } finally {
            store.close()
        }
    }

    /**
     * The batch price lands on every warm row at every launch. If it counted, the mark would roll
     * forward for ever and no row would buy its own quote again.
     */
    @Test
    fun a_batch_quote_capture_never_counts_as_quoted_at() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(listOf(snapshotCapture("AAPL", scopeKey = null, capturedAt = 100)), emptyList())
            store.persistBatch(listOf(snapshotCapture("AAPL", scopeKey = "batch-quote", capturedAt = 500)), emptyList())

            assertEquals(100L, store.loadRefreshMarks()["AAPL"]?.quotedAtEpochSeconds)
        } finally {
            store.close()
        }
    }

    private fun snapshotCapture(symbol: String, scopeKey: String?, capturedAt: Long) = RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.Snapshot,
        scopeKey = scopeKey,
        capturedAt = capturedAt,
        payload = RawCapturePayload.Snapshot(
            MarketSnapshot(
                symbol = symbol,
                profitable = true,
                marketPriceCents = 10_000,
                intrinsicValueCents = 12_000,
            ),
        ),
    )

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
    }
}
