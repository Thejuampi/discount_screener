package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.PersistenceBootstrap
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.QuoteBatchEntry
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.ScoringPreferences
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WarmStartFailurePreservesDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
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
    fun failed_restore_preserves_saved_data_and_blocks_provider_writes_until_retry() = runBlocking {
        val store = FailingReadStore(context)
        val yahoo = CountingYahoo()
        val repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = yahoo,
            universeCatalog = UniverseCatalog(context.assets),
            defaultProfile = "qa",
        )
        try {
            store.replaceTrackedSymbols(listOf("AAPL"))
            store.replaceWatchlist(listOf("AAPL"))
            store.failReads = true

            val failed = repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = true)
            repository.toggleWatchlist("AAPL", ViewFilter(), null, ChartRange.Year, model)
            repository.addSymbols("ZZZZ", ViewFilter(), null, ChartRange.Year, model)
            repository.refreshDetail("AAPL", ViewFilter(), ChartRange.Year, model)

            assertEquals(0, yahoo.calls.get())
            store.failReads = false
            assertEquals(listOf("AAPL"), store.loadWarmStart().watchlist)
            assertEquals(listOf("AAPL"), store.loadWarmStart().trackedSymbols)
            assertEquals(DashboardStartupPhase.RestoreFailed, failed.startupPhase)
            assertTrue(failed.statusMessage.orEmpty().contains("saved data"))

            val retried = repository.refreshAll(ViewFilter(), null, ChartRange.Year, model, force = true)
            assertEquals(listOf("AAPL"), retried.watchlistSymbols)
        } finally {
            store.failReads = false
            repository.clearAllData()
            store.close()
        }
    }

    @Test
    fun bootstrap_retries_a_failed_read_before_scheduling_live_work() = runBlocking {
        val store = FailingReadStore(context)
        val repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = CountingYahoo(),
            universeCatalog = UniverseCatalog(context.assets),
            defaultProfile = "qa",
        )
        try {
            store.replaceWatchlist(listOf("AAPL"))
            store.failReads = true
            assertEquals(
                DashboardStartupPhase.RestoreFailed,
                repository.bootstrap(ViewFilter(), null, ChartRange.Year, model).startupPhase,
            )

            store.failReads = false
            val recovered = repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            assertEquals(listOf("AAPL"), recovered.watchlistSymbols)
        } finally {
            store.failReads = false
            repository.clearAllData()
            store.close()
        }
    }

    @Test
    fun delayed_bootstrap_read_cannot_replace_a_newer_profile() = runBlocking {
        val store = DelayedFirstReadStore(context)
        val repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = CountingYahoo(),
            universeCatalog = UniverseCatalog(context.assets),
            defaultProfile = "qa",
        )
        try {
            val first = async { repository.bootstrap(ViewFilter(), null, ChartRange.Year, model) }
            store.firstReadStarted.await()
            repository.selectProfile("merval", ViewFilter(), ChartRange.Year, model)
            store.releaseFirstRead.complete(Unit)
            first.await()

            val settled = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            assertEquals("merval", settled.currentProfile)
            assertTrue(settled.trackedSymbols.contains("GGAL.BA"))
        } finally {
            store.releaseFirstRead.complete(Unit)
            repository.clearAllData()
            store.close()
        }
    }

    @Test
    fun delayed_failed_read_cannot_mark_a_newer_profile_as_restore_failed() = runBlocking {
        val store = DelayedFirstReadStore(context).apply { firstReadFails = true }
        val repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = CountingYahoo(),
            universeCatalog = UniverseCatalog(context.assets),
            defaultProfile = "qa",
        )
        try {
            val first = async { repository.bootstrap(ViewFilter(), null, ChartRange.Year, model) }
            store.firstReadStarted.await()
            repository.selectProfile("merval", ViewFilter(), ChartRange.Year, model)
            store.releaseFirstRead.complete(Unit)
            first.await()

            val settled = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            assertEquals("merval", settled.currentProfile)
            assertTrue(settled.startupPhase != DashboardStartupPhase.RestoreFailed)
        } finally {
            store.releaseFirstRead.complete(Unit)
            repository.clearAllData()
            store.close()
        }
    }

    @Test
    fun profile_switch_uses_the_supplied_scoring_model_without_another_saved_read() = runBlocking {
        val store = FailingReadStore(context)
        val passRegistered = CompletableDeferred<Unit>()
        val repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = CountingYahoo(),
            universeCatalog = UniverseCatalog(context.assets),
            defaultProfile = "qa",
            afterRefreshPassRegistered = { passRegistered.complete(Unit) },
        )
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            store.failPreferences = true

            repository.selectProfile("merval", ViewFilter(), ChartRange.Year, model)
            withTimeout(5_000) { passRegistered.await() }
            assertEquals("merval", repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model).currentProfile)
        } finally {
            repository.clearAllData()
            store.close()
        }
    }

    private class FailingReadStore(context: Context) : SQLiteStateStore(context, databaseFileName = DB_NAME) {
        var failReads = false
        var failPreferences = false

        override suspend fun loadWarmStart(symbols: Collection<String>?): PersistenceBootstrap {
            if (failReads) throw IOException("temporary SQLite read failure")
            return super.loadWarmStart(symbols)
        }

        override suspend fun loadScoringPreferences(): ScoringPreferences {
            if (failPreferences) throw IOException("temporary scoring read failure")
            return super.loadScoringPreferences()
        }
    }

    private class DelayedFirstReadStore(context: Context) : SQLiteStateStore(context, databaseFileName = DB_NAME) {
        val firstReadStarted = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        var firstReadFails = false
        private val calls = AtomicInteger()

        override suspend fun loadWarmStart(symbols: Collection<String>?): PersistenceBootstrap {
            if (calls.incrementAndGet() == 1) {
                firstReadStarted.complete(Unit)
                withContext(NonCancellable) { releaseFirstRead.await() }
                if (firstReadFails) throw IOException("late SQLite read failure")
            }
            return super.loadWarmStart(symbols)
        }
    }

    private class CountingYahoo : OfflineYahooClient() {
        val calls = AtomicInteger()

        override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> {
            calls.incrementAndGet()
            return emptyMap()
        }

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            calls.incrementAndGet()
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = null,
                externalSignal = null,
                fundamentals = null,
                coverage = ProviderCoverage(
                    core = ProviderComponentState.Missing,
                    external = ProviderComponentState.Missing,
                    fundamentals = ProviderComponentState.Missing,
                ),
                diagnostics = emptyList(),
            )
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> =
            emptyList()
    }

    private companion object {
        const val DB_NAME = "warm_start_failure_preserves_data.sqlite3"
    }
}
