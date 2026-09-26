package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.CaptureKind
import com.discountscreener.android.data.persistence.EvaluatedSymbolState
import com.discountscreener.android.data.persistence.PersistenceBootstrap
import com.discountscreener.android.data.persistence.RawCapture
import com.discountscreener.android.data.persistence.RawCapturePayload
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.persistence.SymbolRevisionInput
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailHistoryHydrationRetryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val filter = ViewFilter()
    private val range = ChartRange.Year
    private val model = OpportunityScoringModel.Legacy

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun failed_revision_read_retries_and_restores_saved_history() = runBlocking {
        val store = FailOnceDetailStore(context)
        try {
            store.persistBatch(emptyList(), listOf(savedRevision()))
            val repository = repository(store)
            repository.bootstrap(filter, null, range, model)
            store.failRevision = true

            expectReadFailure { repository.loadCachedDetail(SYMBOL, filter, range, model) }
            val recovered = repository.loadCachedDetail(SYMBOL, filter, range, model)

            assertEquals(2, store.revisionReads)
            assertTrue(recovered.selectedHistory.any { it.detail.marketPriceCents == 10_000L })
        } finally {
            store.close()
        }
    }

    @Test
    fun failed_pricing_read_retries_and_restores_saved_chart() = runBlocking {
        val store = FailOnceDetailStore(context)
        try {
            store.persistBatch(
                listOf(RawCapture(
                    symbol = SYMBOL,
                    captureKind = CaptureKind.ChartCandles,
                    scopeKey = range.name,
                    capturedAt = 1_700_000_000L,
                    payload = RawCapturePayload.Chart(range, listOf(savedCandle())),
                )),
                emptyList(),
            )
            val repository = repository(store)
            repository.bootstrap(filter, null, range, model)
            store.failPricing = true

            expectReadFailure { repository.loadCachedDetail(SYMBOL, filter, range, model) }
            val recovered = repository.loadCachedDetail(SYMBOL, filter, range, model)

            assertEquals(2, store.pricingReads)
            assertEquals(listOf(savedCandle()), recovered.selectedCharts[range])
        } finally {
            store.close()
        }
    }

    @Test
    fun canceled_revision_read_releases_the_retry_marker() = runBlocking {
        val store = FailOnceDetailStore(context)
        try {
            store.persistBatch(emptyList(), listOf(savedRevision()))
            val repository = repository(store)
            repository.bootstrap(filter, null, range, model)
            store.blockRevisionOnce = true

            val firstRead = launch { repository.loadCachedDetail(SYMBOL, filter, range, model) }
            store.revisionReadStarted.await()
            firstRead.cancelAndJoin()
            val recovered = repository.loadCachedDetail(SYMBOL, filter, range, model)

            assertEquals(2, store.revisionReads)
            assertTrue(recovered.selectedHistory.any { it.detail.marketPriceCents == 10_000L })
        } finally {
            store.close()
        }
    }

    @Test
    fun second_revision_request_waits_and_retries_after_first_is_canceled() = runBlocking {
        val store = FailOnceDetailStore(context)
        try {
            store.persistBatch(emptyList(), listOf(savedRevision()))
            val repository = repository(store)
            repository.bootstrap(filter, null, range, model)
            store.blockRevisionOnce = true

            val firstRead = launch { repository.loadCachedDetail(SYMBOL, filter, range, model) }
            store.revisionReadStarted.await()
            val secondRead = async { repository.loadCachedDetail(SYMBOL, filter, range, model) }
            yield()
            assertTrue("The second request must wait for the active read", !secondRead.isCompleted)

            firstRead.cancelAndJoin()
            val recovered = withTimeout(5_000L) { secondRead.await() }

            assertEquals(2, store.revisionReads)
            assertTrue(recovered.selectedHistory.any { it.detail.marketPriceCents == 10_000L })
        } finally {
            store.close()
        }
    }

    @Test
    fun second_pricing_request_waits_and_retries_after_first_is_canceled() = runBlocking {
        val store = FailOnceDetailStore(context)
        try {
            store.persistBatch(
                listOf(RawCapture(
                    symbol = SYMBOL,
                    captureKind = CaptureKind.ChartCandles,
                    scopeKey = range.name,
                    capturedAt = 1_700_000_000L,
                    payload = RawCapturePayload.Chart(range, listOf(savedCandle())),
                )),
                emptyList(),
            )
            val repository = repository(store)
            repository.bootstrap(filter, null, range, model)
            store.blockPricingOnce = true

            val firstRead = launch { repository.loadCachedDetail(SYMBOL, filter, range, model) }
            store.pricingReadStarted.await()
            val secondRead = async { repository.loadCachedDetail(SYMBOL, filter, range, model) }
            yield()
            assertTrue("The second request must wait for the active read", !secondRead.isCompleted)

            firstRead.cancelAndJoin()
            val recovered = withTimeout(5_000L) { secondRead.await() }

            assertEquals(2, store.pricingReads)
            assertEquals(listOf(savedCandle()), recovered.selectedCharts[range])
        } finally {
            store.close()
        }
    }

    @Test
    fun failed_profile_switch_releases_waiters_from_the_previous_generation() = runBlocking {
        val store = FailOnceDetailStore(context)
        try {
            store.persistBatch(emptyList(), listOf(savedRevision()))
            val repository = repository(store)
            repository.bootstrap(filter, null, range, model)
            store.blockRevisionOnce = true

            val firstRead = launch { repository.loadCachedDetail(SYMBOL, filter, range, model) }
            try {
                store.revisionReadStarted.await()
                val waitingRead = async { repository.loadCachedDetail(SYMBOL, filter, range, model) }
                yield()
                assertTrue(!waitingRead.isCompleted)

                store.failWarmStartOnce = true
                repository.selectProfile("merval", filter, range, model)
                val afterSwitch = withTimeout(5_000L) { waitingRead.await() }

                assertEquals("qa", afterSwitch.currentProfile)
                assertEquals(1, store.revisionReads)
            } finally {
                firstRead.cancelAndJoin()
            }
        } finally {
            store.close()
        }
    }

    private suspend fun expectReadFailure(read: suspend () -> Unit) {
        try {
            read()
            fail("Expected a temporary detail history read failure")
        } catch (_: IOException) {
            Unit
        }
    }

    private fun repository(store: SQLiteStateStore) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = OfflineYahooClient(),
        universeCatalog = UniverseCatalog(context.assets),
        defaultProfile = "qa",
    )

    private fun savedRevision() = SymbolRevisionInput(
        symbol = SYMBOL,
        evaluatedAt = 1_700_000_000L,
        lastSequence = 1,
        updateCount = 1,
        priceHistory = emptyList(),
        payload = EvaluatedSymbolState(
            snapshot = MarketSnapshot(SYMBOL, "Shopify", true, 10_000L, 15_000L),
        ),
    )

    private fun savedCandle() = HistoricalCandle(
        epochSeconds = 1_699_999_000L,
        openCents = 9_900L,
        highCents = 10_100L,
        lowCents = 9_800L,
        closeCents = 10_000L,
        volume = 1_000L,
    )

    private class FailOnceDetailStore(context: Context) : SQLiteStateStore(context, databaseFileName = DB_NAME) {
        var failRevision = false
        var failPricing = false
        var blockRevisionOnce = false
        var blockPricingOnce = false
        var failWarmStartOnce = false
        val revisionReadStarted = CompletableDeferred<Unit>()
        val pricingReadStarted = CompletableDeferred<Unit>()
        var revisionReads = 0
        var pricingReads = 0

        override suspend fun loadWarmStart(symbols: Collection<String>?): PersistenceBootstrap {
            if (failWarmStartOnce) {
                failWarmStartOnce = false
                throw IOException("temporary warm-start read failure")
            }
            return super.loadWarmStart(symbols)
        }

        override suspend fun loadRevisionHistory(symbol: String): List<com.discountscreener.android.data.persistence.PersistedRevisionRecord> {
            revisionReads += 1
            if (blockRevisionOnce) {
                blockRevisionOnce = false
                revisionReadStarted.complete(Unit)
                awaitCancellation()
            }
            if (failRevision) {
                failRevision = false
                throw IOException("temporary revision read failure")
            }
            return super.loadRevisionHistory(symbol)
        }

        override suspend fun loadPricingHistory(symbol: String): List<com.discountscreener.android.data.persistence.PersistedChartRecord> {
            pricingReads += 1
            if (blockPricingOnce) {
                blockPricingOnce = false
                pricingReadStarted.complete(Unit)
                awaitCancellation()
            }
            if (failPricing) {
                failPricing = false
                throw IOException("temporary pricing read failure")
            }
            return super.loadPricingHistory(symbol)
        }
    }

    private companion object {
        const val DB_NAME = "detail_history_hydration_retry.sqlite3"
        const val SYMBOL = "SHOP"
    }
}
