package com.discountscreener.android.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one table the app cannot refill from a provider.
 *
 * Every test reopens the store before reading, because a note is written to be read on another day.
 */
@RunWith(RobolectricTestRunner::class)
class SymbolNoteStoreTest {
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
    fun a_note_survives_a_cold_start() = runTest {
        save("ULTA", "Target partnership ends in 2026.")

        assertEquals(mapOf("ULTA" to "Target partnership ends in 2026."), load())
    }

    @Test
    fun a_second_note_for_the_same_symbol_replaces_the_first() = runTest {
        save("ULTA", "first")
        save("ULTA", "second")

        assertEquals(listOf("second"), load().values.toList())
    }

    /** A cleared note leaves nothing behind, so no screen has to tell an empty note from no note. */
    @Test
    fun clearing_a_note_removes_it() = runTest {
        save("ULTA", "first")
        save("ULTA", "")

        assertEquals(emptyMap<String, String>(), load())
    }

    @Test
    fun a_note_of_only_spaces_is_a_cleared_note() = runTest {
        save("ULTA", "first")
        save("ULTA", "   ")

        assertEquals(emptyMap<String, String>(), load())
    }

    @Test
    fun notes_are_kept_per_symbol() = runTest {
        save("ULTA", "partnership")
        save("SNDK", "memory cycle")

        assertEquals(setOf("ULTA", "SNDK"), load().keys)
    }

    /**
     * The reason this table is not in the reset list. A refresh brings back every other row in the
     * file; nothing brings back a sentence the reader typed.
     */
    @Test
    fun a_note_survives_clearing_the_warm_start() = runTest {
        save("ULTA", "partnership")
        withStore { it.resetWarmStartState() }

        assertEquals(listOf("partnership"), load().values.toList())
    }

    /**
     * An install from before the table existed. Without the upgrade branch the store opens against
     * a file with no `symbol_note`, and the first read throws instead of answering "no notes yet".
     */
    @Test
    fun an_older_database_gains_the_table_on_upgrade() = runTest {
        var file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        var old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.version = 8
        old.close()

        save("ULTA", "partnership")

        assertEquals(listOf("partnership"), load().values.toList())
    }

    private suspend fun save(symbol: String, note: String) = withStore { it.saveSymbolNote(symbol, note) }

    private suspend fun load(): Map<String, String> = withStore { it.loadSymbolNotes() }

    private suspend fun <T> withStore(block: suspend (SQLiteStateStore) -> T): T {
        val store = SQLiteStateStore(context)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
    }
}
