package com.discountscreener.android.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.domain.model.JournalFactors
import com.discountscreener.android.domain.model.ScoreJournalRow
import com.discountscreener.core.model.ScoreFactor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /**
     * The terms are the point of the point: bucket scores say which model won, the terms say why,
     * and only the terms can be put on trial one by one. They must survive a cold start with their
     * rates intact.
     */
    @Test
    fun factors_survive_a_cold_start_with_their_rates() = runTest {
        var withFactors = row().copy(
            factors = JournalFactors(
                fundamentals = listOf(ScoreFactor("FCFy", "FCFy++", 8, inputBps = 610)),
                technical = listOf(ScoreFactor("RSI", "RSI+", 4)),
                forecast = listOf(ScoreFactor("Val", "Val-", -6, inputBps = -1_200)),
            ),
        )
        append(listOf(withFactors))

        assertEquals(withFactors.factors, load().single().factors)
    }

    /** Rows written before factor capture existed read as absent, never as an empty term list.
     * Covered by [a_version_nine_journal_upgrades_and_keeps_its_rows], whose pre-upgrade AAPL row
     * is exactly that legacy case. */

    /**
     * The upgrade path, exercised for real: a version-9 database whose journal lacks the column is
     * opened by today's store, the ALTER runs, and both the migrated row and a fresh factor-bearing
     * row read back correctly.
     */
    @Test
    fun a_version_nine_journal_upgrades_and_keeps_its_rows() {
        context.deleteDatabase(DB_NAME)
        var v9 = object : SQLiteOpenHelper(context, DB_NAME, null, 9) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE score_journal (
                        symbol TEXT NOT NULL, scoring_model TEXT NOT NULL, scored_at INTEGER NOT NULL,
                        fundamentals_score INTEGER, technical_score INTEGER, forecast_score INTEGER,
                        regime_score INTEGER, composite_score INTEGER NOT NULL,
                        composite_score_base INTEGER NOT NULL, market_price_cents INTEGER NOT NULL,
                        PRIMARY KEY (symbol, scoring_model, scored_at)
                    )
                    """.trimIndent(),
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v9.writableDatabase.execSQL(
            """
            INSERT INTO score_journal VALUES ('AAPL','AggressiveV4',1700000000,
                20,22,19,18,45,40,10000)
            """.trimIndent(),
        )
        v9.close()

        runTest {
            append(
                listOf(
                    row(symbol = "MSFT").copy(
                        factors = JournalFactors(technical = listOf(ScoreFactor("RSI", "RSI+", 4))),
                    ),
                ),
            )

            var rows = load()
            assertEquals(null, rows.first { it.symbol == "AAPL" }.factors)
            assertNotNull(rows.first { it.symbol == "MSFT" }.factors)
        }
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
