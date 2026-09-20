package com.discountscreener.android.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.portfolio.PortfolioLot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PortfolioLotStoreTest {
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
    fun schema_version_is_eleven() = runTest {
        withStore { store ->
            assertEquals(11, store.readableDatabase.version)
        }
    }

    @Test
    fun a_version_ten_database_gains_the_lot_table() = runTest {
        var file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        var old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.version = 10
        old.close()

        withStore { store ->
            var names = store.readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'portfolio_lot'",
                emptyArray(),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            assertEquals("portfolio_lot", names)
        }
    }

    @Test
    fun a_confirmed_lot_survives_a_cold_start() = runTest {
        withStore { it.replacePortfolioBook(listOf(PHYL), "2026-08-31") }

        withStore { store ->
            assertEquals(listOf(PHYL) to "2026-08-31", store.loadPortfolioBook())
        }
    }

    @Test
    fun two_rows_for_one_ticker_store_as_one_lot() = runTest {
        withStore {
            it.replacePortfolioBook(
                listOf(AMZN, AMZN.copy(quantityTenThousandths = 50_000)),
                "2026-08-31",
            )
        }

        withStore { store ->
            assertEquals(1, store.loadPortfolioBook().first.size)
        }
    }

    @Test
    fun phyl_quantity_persists_as_ten_thousandths() = runTest {
        withStore { it.replacePortfolioBook(listOf(PHYL), "2026-08-31") }

        withStore { store ->
            assertEquals(12_730_000L, store.loadPortfolioBook().first.single().quantityTenThousandths)
        }
    }

    @Test
    fun amzn_fractional_quantity_persists_half_up() = runTest {
        withStore { it.replacePortfolioBook(listOf(AMZN_FRAC), "2026-08-31") }

        withStore { store ->
            assertEquals(362_954L, store.loadPortfolioBook().first.single().quantityTenThousandths)
        }
    }

    private suspend fun <T> withStore(block: suspend (SQLiteStateStore) -> T): T {
        var store = SQLiteStateStore(context)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        val PHYL = PortfolioLot("PHYL", 12_730_000L, 3_528L, null)
        val AMZN = PortfolioLot("AMZN", 100_000L, 20_000L, "2024-01-15")
        val AMZN_FRAC = PortfolioLot("AMZN", 362_954L, 21_403L, null)
    }
}
