package com.discountscreener.android.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.domain.model.ScoreJournalRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The table that will eventually say whether V4 is better than V3, rather than only cleaner.
 *
 * Every test reopens the store before reading. The journal exists to be read weeks after it was
 * written, so a same-instance round trip would prove the wrong thing.
 */
@RunWith(RobolectricTestRunner::class)
class ScoreJournalStoreTest {
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
    fun a_scoring_pass_survives_a_cold_start() = runTest {
        append(listOf(row()))

        assertEquals(listOf(row()), load())
    }

    /**
     * The point of the whole table. If a pass under V4 replaced the same day's pass under V3, the
     * two models could never be compared on the same day, and the journal would answer nothing.
     */
    @Test
    fun the_two_models_are_kept_side_by_side_for_the_same_symbol_and_day() = runTest {
        append(listOf(row(model = "AggressiveV3"), row(model = "AggressiveV4", composite = 51)))

        assertEquals(listOf("AggressiveV3", "AggressiveV4"), load().map { it.scoringModel })
    }

    /** A second pass on the same day under the same model is a correction, not a second opinion. */
    @Test
    fun a_repeated_pass_at_the_same_second_replaces_rather_than_duplicates() = runTest {
        append(listOf(row(composite = 45)))
        append(listOf(row(composite = 77)))

        assertEquals(listOf(77), load().map { it.compositeScore })
    }

    @Test
    fun a_row_older_than_the_retention_window_is_dropped() = runTest {
        append(listOf(row(scoredAt = NOW - 100L), row(scoredAt = NOW)))

        assertEquals(listOf(NOW), load().map { it.scoredAtEpochSeconds })
    }

    /**
     * A retention sweep that deletes silently reads, from the outside, exactly like a journal that
     * was never written. The count is what the repository logs.
     */
    @Test
    fun the_sweep_reports_how_many_rows_it_dropped() = runTest {
        append(
            rows = listOf(row(scoredAt = NOW - 100L), row(symbol = "MSFT", scoredAt = NOW - 100L)),
            retentionSeconds = 1_000L,
        )

        assertEquals(2, append(listOf(row())))
    }

    /** A bucket that did not report is absent, not zero — zero is a score the engine can produce. */
    @Test
    fun an_absent_bucket_reads_back_as_absent() = runTest {
        append(listOf(row(regime = null)))

        assertEquals(listOf(null), load().map { it.regimeScore })
    }

    private suspend fun append(
        rows: List<ScoreJournalRow>,
        retentionSeconds: Long = RETENTION_SECONDS,
    ): Int {
        val store = SQLiteStateStore(context)
        try {
            return store.appendScoreJournal(rows, retentionSeconds, NOW)
        } finally {
            store.close()
        }
    }

    private suspend fun load(): List<ScoreJournalRow> {
        val store = SQLiteStateStore(context)
        try {
            return store.loadScoreJournal()
        } finally {
            store.close()
        }
    }

    private fun row(
        symbol: String = "AAPL",
        model: String = "AggressiveV4",
        scoredAt: Long = NOW,
        regime: Int? = 18,
        composite: Int = 45,
    ) = ScoreJournalRow(
        symbol = symbol,
        scoringModel = model,
        scoredAtEpochSeconds = scoredAt,
        fundamentalsScore = 20,
        technicalScore = 22,
        forecastScore = 19,
        regimeScore = regime,
        compositeScore = composite,
        compositeScoreBase = 40,
        marketPriceCents = 10_000L,
    )

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        const val NOW = 1_700_000_000L
        const val RETENTION_SECONDS = 50L
    }
}
