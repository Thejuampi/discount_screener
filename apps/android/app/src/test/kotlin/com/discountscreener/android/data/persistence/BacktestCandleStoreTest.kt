package com.discountscreener.android.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.model.HistoricalCandle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dated prices the retrospective is measured against. Without these the score journal records
 * opinions with nothing to check them against.
 *
 * Two properties carry the weight here and neither is obvious from the code. The series has to
 * *grow* — a year of bars refetched forever would never become more than a year of history — and it
 * has to refuse to grow across a split, because a stored bar on the old basis next to a new bar on
 * the adjusted one is a fabricated fifty-percent move that no later analysis could detect.
 */
@RunWith(RobolectricTestRunner::class)
class BacktestCandleStoreTest {
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
    fun a_daily_series_survives_a_cold_start() = runTest {
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(1..3, PRICE_CENTS)), NOW) }

        assertEquals(series(1..3, PRICE_CENTS), load()[SYMBOL])
    }

    /**
     * The whole reason to keep these. Every fetch asks for one year, so a store that replaced the
     * series each time would hold one year forever and the retrospective could never reach further
     * back than the day the app was installed.
     */
    @Test
    fun a_bar_older_than_the_refetched_window_is_kept() = runTest {
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(1..3, PRICE_CENTS)), NOW) }
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(2..4, PRICE_CENTS)), NOW) }

        assertEquals((1..4).map { day(it) }, load().getValue(SYMBOL).map { it.epochSeconds })
    }

    /**
     * A split re-prices the whole fetched window and nothing before it. Keeping both bases would
     * put a 2-for-1 into the series as a −50% day, and every score dated near it would be graded
     * against a move that never happened.
     */
    @Test
    fun a_split_adjusted_refetch_drops_the_stale_basis() = runTest {
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(1..4, PRICE_CENTS)), NOW) }
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(2..5, PRICE_CENTS / 2)), NOW) }

        assertEquals((2..5).map { day(it) }, load().getValue(SYMBOL).map { it.epochSeconds })
    }

    /** Throwing away history is not a routine event, and a caller that cannot see it cannot log it. */
    @Test
    fun a_rebase_is_reported_rather_than_done_quietly() = runTest {
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(1..4, PRICE_CENTS)), NOW) }

        assertEquals(
            1,
            store { it.persistBacktestCandles(mapOf(SYMBOL to series(2..5, PRICE_CENTS / 2)), NOW) },
        )
    }

    /** The other direction, so the rebase count cannot pass by always being one. */
    @Test
    fun a_refetch_on_the_same_basis_is_not_a_rebase() = runTest {
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(1..4, PRICE_CENTS)), NOW) }

        assertEquals(
            0,
            store { it.persistBacktestCandles(mapOf(SYMBOL to series(2..5, PRICE_CENTS)), NOW) },
        )
    }

    /**
     * These bars share a table with the charts the user scrolls and must never be served as one.
     * They are daily where the year chart is weekly, so a leak would not look like an error — it
     * would look like a chart with five times too much detail and no way to tell.
     */
    @Test
    fun the_retrospective_series_is_not_a_chart_the_user_can_scroll() = runTest {
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(1..3, PRICE_CENTS)), NOW) }

        assertEquals(emptyList<PersistedChartRecord>(), store { it.loadPricingHistory(SYMBOL) })
    }

    /**
     * Clearing the warm start asks for fresh data. It does not ask to destroy the evidence, and the
     * score journal it is paired with is spared for the same reason.
     */
    @Test
    fun clearing_the_warm_start_keeps_the_retrospective_series() = runTest {
        store { it.persistBacktestCandles(mapOf(SYMBOL to series(1..3, PRICE_CENTS)), NOW) }
        store { it.resetWarmStartState() }

        assertEquals(series(1..3, PRICE_CENTS), load()[SYMBOL])
    }

    private suspend fun load(): Map<String, List<HistoricalCandle>> = store { it.loadBacktestCandles() }

    /** Every read reopens the store, because these rows exist to be read after the process is gone. */
    private suspend fun <T> store(block: suspend (SQLiteStateStore) -> T): T {
        val store = SQLiteStateStore(context)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    private fun series(days: IntRange, closeCents: Long) = days.map { bar ->
        HistoricalCandle(
            epochSeconds = day(bar),
            openCents = closeCents,
            highCents = closeCents + 50L,
            lowCents = closeCents - 50L,
            closeCents = closeCents,
            volume = 1_000L,
        )
    }

    private fun day(bar: Int) = FIRST_BAR + bar * 86_400L

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        const val SYMBOL = "AAPL"
        const val NOW = 1_700_000_000L
        const val FIRST_BAR = 1_600_000_000L
        const val PRICE_CENTS = 20_000L
    }
}
