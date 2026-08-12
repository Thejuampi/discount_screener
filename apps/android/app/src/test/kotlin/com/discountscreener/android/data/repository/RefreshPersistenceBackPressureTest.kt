package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.PersistedIssueRecord
import com.discountscreener.android.data.persistence.RawCapture
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.persistence.SymbolRevisionInput
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * How many persists the refresh is allowed to have in flight at once.
 *
 * The refresh used to hand each symbol's write to `repositoryScope.launch` and move on. On the
 * twenty-symbol profile that is invisible — the writes finish as fast as they arrive — so nothing
 * in this suite could fail on it. On the 501-symbol universe it is an unbounded producer feeding a
 * consumer that serialises on one SQLite writer: measured on emulator-5554, native memory went from
 * 12 MB to 1.69 GB in twenty seconds and the allocator aborted the process. The same run capped at
 * twenty symbols sat flat at 18 MB, which is what ruled out data volume and named the pile-up.
 *
 * A test that asserts the data landed cannot fail on that defect, because with the bug the data
 * still lands — right up until the process dies. The observable difference is concurrency, so that
 * is what this counts. The fake suspends inside the write, as a real one does; without that, every
 * launched coroutine would run to completion before the next was dispatched and a peak of one would
 * prove nothing.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshPersistenceBackPressureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun a_refresh_never_has_two_persists_in_flight_at_once() = runTest(dispatcher) {
        val store = CountingStateStore(context, dispatcher)
        try {
            val repository = DefaultDashboardRepository(
                stateStore = store,
                profileCatalog = ProfileCatalog(context.assets),
                yahooClient = UnreachableYahooFinanceClient(),
                universeCatalog = UniverseCatalog(context.assets),
                nowProvider = { NOW },
                ioDispatcher = dispatcher,
                defaultProfile = DefaultDashboardRepository.QA_PROFILE,
            )
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, MODEL)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, MODEL)
            advanceUntilIdle()

            assertEquals(
                "the refresh persisted ${store.peakInFlight} deltas at once over ${store.writes} writes",
                1,
                store.peakInFlight,
            )
        } finally {
            store.close()
        }
    }

    /** Counts overlap rather than content: what the pile-up costs is coroutines, not rows. */
    private class CountingStateStore(
        context: Context,
        ioDispatcher: CoroutineDispatcher,
    ) : SQLiteStateStore(context, ioDispatcher = ioDispatcher) {
        var peakInFlight: Int = 0
            private set
        var writes: Int = 0
            private set

        private var inFlight = 0

        override suspend fun persistBatch(
            rawCaptures: List<RawCapture>,
            revisions: List<SymbolRevisionInput>,
        ) = counted { super.persistBatch(rawCaptures, revisions) }

        override suspend fun replaceIssues(issues: List<PersistedIssueRecord>) =
            counted { super.replaceIssues(issues) }

        private suspend fun counted(write: suspend () -> Unit) {
            inFlight += 1
            writes += 1
            peakInFlight = maxOf(peakInFlight, inFlight)
            try {
                // A real write suspends; without a suspension point here every fire-and-forget
                // launch would run start to finish before the next one was dispatched, and the
                // overlap this test exists to see would never be observable.
                yield()
                write()
            } finally {
                inFlight -= 1
            }
        }
    }

    /**
     * Every fetch fails, which is the cheapest way to make the refresh persist once per symbol:
     * a failure still produces a delta, and it costs the test no market fixture to maintain.
     */
    private class UnreachableYahooFinanceClient : YahooFinanceClient(httpClient = offlineHttpClient()) {
        override suspend fun fetchSymbol(symbol: String): Nothing = throw IOException("offline")
    }

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        const val NOW = 1_700_000_000L
        val MODEL = OpportunityScoringModel.AggressiveV3
    }
}
