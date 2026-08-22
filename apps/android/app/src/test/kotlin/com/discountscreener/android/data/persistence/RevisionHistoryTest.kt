package com.discountscreener.android.data.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.model.MarketSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the revision history files, and what it refuses to file.
 *
 * A refresh used to file a revision per symbol whatever it found. On a two-week-old install
 * 29 809 of the 60 308 rows were a byte copy of the row before them, inside 216 MB of JSON that
 * grew by 23 MB a day, and every one of those bytes was written in front of the next result the
 * refresh wanted to apply.
 */
@RunWith(RobolectricTestRunner::class)
class RevisionHistoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    /** A refresh that found the same state as the last one records no change, so it files none. */
    @Test
    fun a_revision_equal_to_the_one_before_it_is_not_filed_again() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(emptyList(), listOf(revision(priceCents = 10_000, evaluatedAt = 100)))
            store.persistBatch(emptyList(), listOf(revision(priceCents = 10_000, evaluatedAt = 500)))

            assertEquals(1, store.loadRevisionHistory(SYMBOL).size)
        } finally {
            store.close()
        }
    }

    /** The history still keeps every change; only the repeats are dropped. */
    @Test
    fun a_revision_that_moved_is_filed_beside_the_one_before_it() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(emptyList(), listOf(revision(priceCents = 10_000, evaluatedAt = 100)))
            store.persistBatch(emptyList(), listOf(revision(priceCents = 10_000, evaluatedAt = 500)))
            store.persistBatch(emptyList(), listOf(revision(priceCents = 11_000, evaluatedAt = 900)))

            assertEquals(
                listOf(10_000L, 11_000L),
                store.loadRevisionHistory(SYMBOL).map { it.payload.snapshot?.marketPriceCents },
            )
        } finally {
            store.close()
        }
    }

    /** The row the screen reads is the newest state, whether or not the history took a copy. */
    @Test
    fun the_latest_row_carries_the_newest_state_even_when_no_revision_was_filed() = runTest {
        var store = SQLiteStateStore(context)
        try {
            store.persistBatch(emptyList(), listOf(revision(priceCents = 10_000, evaluatedAt = 100, updateCount = 1)))
            store.persistBatch(emptyList(), listOf(revision(priceCents = 10_000, evaluatedAt = 500, updateCount = 7)))

            assertEquals(7, store.loadWarmStart().symbolStates.first { it.symbol == SYMBOL }.updateCount)
        } finally {
            store.close()
        }
    }

    /**
     * The rows filed before the write path refused a repeat. On the install that started this,
     * 29 809 of 60 368 revisions were a byte copy of the row before them and held 109 MB.
     */
    @Test
    fun reclaim_drops_a_revision_a_later_one_repeats() = runTest {
        var store = SQLiteStateStore(context)
        try {
            fileRawRevision(store, priceCents = 10_000, evaluatedAt = 100)
            fileRawRevision(store, priceCents = 10_000, evaluatedAt = 500)

            store.reclaimPersistenceSpace()

            assertEquals(listOf(500L), store.loadRevisionHistory(SYMBOL).map { it.evaluatedAt })
        } finally {
            store.close()
        }
    }

    /** The clean-up reads content, so a revision that moved survives it. */
    @Test
    fun reclaim_keeps_a_revision_that_changed() = runTest {
        var store = SQLiteStateStore(context)
        try {
            fileRawRevision(store, priceCents = 10_000, evaluatedAt = 100)
            fileRawRevision(store, priceCents = 11_000, evaluatedAt = 500)

            store.reclaimPersistenceSpace()

            assertEquals(listOf(100L, 500L), store.loadRevisionHistory(SYMBOL).map { it.evaluatedAt })
        } finally {
            store.close()
        }
    }

    /**
     * A dangling `symbol_latest.revision_id` would leave the row the screen reads pointing at
     * nothing, so the clean-up leaves that revision alone whatever its content repeats.
     */
    @Test
    fun reclaim_never_drops_the_revision_the_latest_row_points_at() = runTest {
        var store = SQLiteStateStore(context)
        try {
            fileRawRevision(store, priceCents = 10_000, evaluatedAt = 100)
            fileRawRevision(store, priceCents = 10_000, evaluatedAt = 500)
            store.writableDatabase.execSQL(
                "UPDATE symbol_latest SET revision_id = (SELECT MIN(revision_id) FROM symbol_revision)",
            )

            store.reclaimPersistenceSpace()

            assertEquals(listOf(100L, 500L), store.loadRevisionHistory(SYMBOL).map { it.evaluatedAt })
        } finally {
            store.close()
        }
    }

    /**
     * The walk over every symbol costs seconds and can only find rows filed before the write path
     * refused a repeat, so it runs once. A refresh after it pays nothing.
     */
    @Test
    fun reclaim_walks_the_revisions_once() = runTest {
        var store = SQLiteStateStore(context)
        try {
            fileRawRevision(store, priceCents = 10_000, evaluatedAt = 100)
            store.reclaimPersistenceSpace()

            fileRawRevision(store, priceCents = 10_000, evaluatedAt = 500)
            store.reclaimPersistenceSpace()

            assertEquals(listOf(100L, 500L), store.loadRevisionHistory(SYMBOL).map { it.evaluatedAt })
        } finally {
            store.close()
        }
    }

    /**
     * File a revision the way the write path did before it checked for a repeat: a row every time,
     * with the latest pointer moved to it.
     */
    private fun fileRawRevision(store: SQLiteStateStore, priceCents: Long, evaluatedAt: Long) {
        var payloadJson = Json.encodeToString(
            EvaluatedSymbolState.serializer(),
            revision(priceCents, evaluatedAt).payload,
        )
        var db = store.writableDatabase
        var revisionId = db.insertOrThrow(
            "symbol_revision",
            null,
            ContentValues().apply {
                put("symbol", SYMBOL)
                put("evaluated_at", evaluatedAt)
                put("last_sequence", 1)
                put("update_count", 1)
                put("payload_json", payloadJson)
            },
        )
        db.insertWithOnConflict(
            "symbol_latest",
            null,
            ContentValues().apply {
                put("symbol", SYMBOL)
                put("revision_id", revisionId)
                put("evaluated_at", evaluatedAt)
                put("last_sequence", 1)
                put("update_count", 1)
                put("payload_json", payloadJson)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun revision(priceCents: Long, evaluatedAt: Long, updateCount: Int = 1) = SymbolRevisionInput(
        symbol = SYMBOL,
        evaluatedAt = evaluatedAt,
        lastSequence = 1,
        updateCount = updateCount,
        priceHistory = emptyList(),
        payload = EvaluatedSymbolState(
            snapshot = MarketSnapshot(
                symbol = SYMBOL,
                profitable = true,
                marketPriceCents = priceCents,
                intrinsicValueCents = 12_000,
            ),
        ),
    )

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        const val SYMBOL = "AAPL"
    }
}
