package com.discountscreener.android.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.core.model.TipRanksForecast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class TipRanksPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SQLiteStateStore(context)

    @After fun tearDown() {
        store.close()
        context.deleteDatabase("discount_screener_state.sqlite3")
    }

    @Test fun `cache and usage persist independently from forecast attempt`() = runBlocking {
        store.saveTipRanksForecast(TipRanksForecast("TEST", "USD", 2, emptyList(), 100))
        store.saveTipRanksUsageSnapshot(TipRanksUsageRecord("2026-07", 12, 50, 101))
        val attempt = requireNotNull(store.reserveTipRanksForecastAttempt("2026-07", "TEST", 102))
        assertTrue(store.markTipRanksAttemptSent(attempt.id, 103))
        assertFalse(store.cancelReservedTipRanksAttempt(attempt.id))

        assertEquals("TEST", store.loadTipRanksForecast("test")?.symbol)
        assertEquals(12, store.loadLatestTipRanksUsageSnapshot("2026-07")?.providerUsed)
    }

    @Test fun `only reserved attempts can be cancelled and monthly cap is atomic`() = runBlocking {
        val reserved = requireNotNull(store.reserveTipRanksForecastAttempt("2026-08", "TEST", 100, monthlyLimit = 1))
        assertNull(store.reserveTipRanksForecastAttempt("2026-08", "NEXT", 101, monthlyLimit = 1))
        assertTrue(store.cancelReservedTipRanksAttempt(reserved.id))
        assertNotNull(store.reserveTipRanksForecastAttempt("2026-08", "NEXT", 102, monthlyLimit = 1))
    }
}
