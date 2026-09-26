package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.persistence.PersistenceBootstrap
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.QuoteBatchEntry
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddSymbolsDuringRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
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
    fun adding_a_ticker_during_a_profile_refresh_finishes_every_original_row() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = DB_NAME)
        val firstPassRegistered = CompletableDeferred<Unit>()
        val firstPassGate = CompletableDeferred<Unit>()
        val client = RecordingYahooClient()
        var registrations = 0
        val repository = repository(store, client, afterPassRegistered = {
            if (++registrations == 1) {
                firstPassRegistered.complete(Unit)
                firstPassGate.await()
            }
        })
        try {
            val originals = repository.bootstrap(filter, null, range, model).trackedSymbols.toSet()
            repository.refreshAll(filter, null, range, model, force = true)
            firstPassRegistered.await()

            assertEquals(originals.size, repository.currentSnapshot(filter, null, range, model).refreshTargetSymbols)
            val afterAdd = repository.addSymbols(NEW_SYMBOL, filter, null, range, model)
            assertEquals(originals.size + 1, afterAdd.refreshTargetSymbols)

            firstPassGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(client.fetchedSymbols.containsAll(originals + NEW_SYMBOL))
            val finished = repository.currentSnapshot(filter, null, range, model)
            assertTrue(finished.trackedRows.all { row -> row.state != TrackedRowState.Loading })
        } finally {
            firstPassGate.complete(Unit)
            runCatching { repository.clearAllData() }
            advanceUntilIdle()
            store.close()
        }
    }

    @Test
    fun adding_a_ticker_during_enrichment_keeps_the_complete_profile_in_the_next_pass() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = DB_NAME)
        val timeseriesStarted = CompletableDeferred<Unit>()
        val timeseriesGate = CompletableDeferred<Unit>()
        val client = RecordingYahooClient(timeseriesStarted, timeseriesGate)
        val repository = repository(store, client)
        try {
            val originals = repository.bootstrap(filter, null, range, model).trackedSymbols.toSet()
            repository.refreshAll(filter, null, range, model, force = true)
            timeseriesStarted.await()
            dispatcher.scheduler.runCurrent()
            assertEquals(DashboardStartupPhase.Ready, repository.currentSnapshot(filter, null, range, model).startupPhase)

            val afterAdd = repository.addSymbols(NEW_SYMBOL, filter, null, range, model)
            assertEquals(originals.size + 1, afterAdd.refreshTargetSymbols)

            timeseriesGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(client.fetchedSymbols.containsAll(originals + NEW_SYMBOL))
        } finally {
            timeseriesGate.complete(Unit)
            runCatching { repository.clearAllData() }
            advanceUntilIdle()
            store.close()
        }
    }

    @Test
    fun adding_a_ticker_while_idle_only_fetches_the_new_ticker() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = DB_NAME)
        val client = RecordingYahooClient()
        val repository = repository(store, client)
        try {
            repository.bootstrap(filter, null, range, model)
            val afterAdd = repository.addSymbols(NEW_SYMBOL, filter, null, range, model)
            assertEquals(1, afterAdd.refreshTargetSymbols)

            advanceUntilIdle()
            assertEquals(setOf(NEW_SYMBOL), client.fetchedSymbols.toSet())
        } finally {
            runCatching { repository.clearAllData() }
            advanceUntilIdle()
            store.close()
        }
    }

    @Test
    fun adding_an_existing_ticker_does_not_change_status_or_start_a_refresh() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = DB_NAME)
        val client = RecordingYahooClient()
        val repository = repository(store, client)
        try {
            val before = repository.bootstrap(filter, null, range, model)
            val existing = before.trackedSymbols.first()

            val after = repository.addSymbols(existing, filter, null, range, model)

            assertEquals(before.statusMessage, after.statusMessage)
            assertEquals(before.trackedSymbols, after.trackedSymbols)
            advanceUntilIdle()
            assertTrue(client.fetchedSymbols.isEmpty())
        } finally {
            runCatching { repository.clearAllData() }
            advanceUntilIdle()
            store.close()
        }
    }

    @Test
    fun manual_refresh_after_add_persistence_still_fetches_the_complete_profile() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = DB_NAME)
        val addScopeChosen = CompletableDeferred<Unit>()
        val releaseAdd = CompletableDeferred<Unit>()
        val client = RecordingYahooClient()
        val repository = repository(store, client, beforeAddRefreshStart = {
            addScopeChosen.complete(Unit)
            releaseAdd.await()
        })
        try {
            val originals = repository.bootstrap(filter, null, range, model).trackedSymbols.toSet()
            val add = async { repository.addSymbols(NEW_SYMBOL, filter, null, range, model) }
            addScopeChosen.await()

            repository.refreshAll(filter, null, range, model, force = true)
            releaseAdd.complete(Unit)
            add.await()
            advanceUntilIdle()

            assertTrue(client.fetchedSymbols.containsAll(originals + NEW_SYMBOL))
            val finished = repository.currentSnapshot(filter, null, range, model)
            assertTrue(finished.trackedRows.all { row -> row.state != TrackedRowState.Loading })
        } finally {
            releaseAdd.complete(Unit)
            runCatching { repository.clearAllData() }
            advanceUntilIdle()
            store.close()
        }
    }

    @Test
    fun add_during_profile_switch_is_refused_with_a_visible_reason() = runTest(dispatcher) {
        val switchReadStarted = CompletableDeferred<Unit>()
        val releaseSwitchRead = CompletableDeferred<Unit>()
        val store = DelayedSwitchStore(switchReadStarted, releaseSwitchRead)
        val client = RecordingYahooClient()
        val repository = repository(store, client)
        try {
            repository.bootstrap(filter, null, range, model)
            val switch = async { repository.selectProfile("dow", filter, range, model) }
            switchReadStarted.await()

            val duringSwitch = repository.addSymbols(NEW_SYMBOL, filter, null, range, model)
            assertEquals(DashboardStartupPhase.SwitchingProfile, duringSwitch.startupPhase)
            assertTrue(NEW_SYMBOL !in duringSwitch.trackedSymbols)
            assertTrue(duringSwitch.statusMessage.orEmpty().contains("profile switch", ignoreCase = true))

            releaseSwitchRead.complete(Unit)
            switch.await()
            advanceUntilIdle()
            assertTrue(NEW_SYMBOL !in store.loadWarmStart().trackedSymbols)
            assertTrue(NEW_SYMBOL !in repository.currentSnapshot(filter, null, range, model).trackedSymbols)
        } finally {
            releaseSwitchRead.complete(Unit)
            runCatching { repository.clearAllData() }
            advanceUntilIdle()
            store.close()
        }
    }

    private fun repository(
        store: SQLiteStateStore,
        client: RecordingYahooClient,
        afterPassRegistered: (suspend () -> Unit)? = null,
        beforeAddRefreshStart: (suspend () -> Unit)? = null,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        universeCatalog = UniverseCatalog(context.assets),
        secondaryTimeseriesProvider = CountingSecProvider(),
        nowProvider = { NOW_EPOCH },
        ioDispatcher = dispatcher,
        defaultProfile = DefaultDashboardRepository.QA_PROFILE,
        afterRefreshPassRegistered = afterPassRegistered,
        beforeAddRefreshStart = beforeAddRefreshStart,
    )

    private inner class DelayedSwitchStore(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = DB_NAME) {
        private var reads = 0

        override suspend fun loadWarmStart(symbols: Collection<String>?): PersistenceBootstrap {
            if (++reads == 2) {
                started.complete(Unit)
                release.await()
            }
            return super.loadWarmStart(symbols)
        }
    }

    private class RecordingYahooClient(
        private val timeseriesStarted: CompletableDeferred<Unit>? = null,
        private val timeseriesGate: CompletableDeferred<Unit>? = null,
    ) : YahooFinanceClient(httpClient = offlineHttpClient()) {
        val fetchedSymbols = mutableListOf<String>()

        override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> = emptyMap()

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            fetchedSymbols += symbol
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = "$symbol Holdings",
                    profitable = true,
                    marketPriceCents = 10_000L,
                    intrinsicValueCents = 12_000L,
                ),
                companyName = "$symbol Holdings",
                externalSignal = null,
                fundamentals = dcfFundamentals(symbol),
                coverage = ProviderCoverage(
                    core = ProviderComponentState.Fresh,
                    external = ProviderComponentState.Missing,
                    fundamentals = ProviderComponentState.Fresh,
                ),
                diagnostics = emptyList(),
            )
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> =
            listOf(HistoricalCandle(1_699_999_000L, 9_900L, 10_100L, 9_800L, 10_000L, 1_000L))

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
            timeseriesStarted?.complete(Unit)
            timeseriesGate?.await()
            return FundamentalTimeseries()
        }
    }

    private companion object {
        const val DB_NAME = "add_symbols_during_refresh.sqlite3"
        const val NEW_SYMBOL = "ZZZZ"
        const val NOW_EPOCH = 1_700_000_000L
    }
}
