package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Where the time of a profile switch goes, measured before anything is changed to make it shorter.
 *
 * DEF-08 says the switch is slow and names four suspects. This class tests none of them; it prints
 * what each stage costs, so the fix can be chosen from numbers. The provider is offline and the
 * database is [BENCH_DB_NAME], never the live file.
 *
 * What the readings are: JVM and Robolectric milliseconds. The absolute values are not a phone's.
 * The share each stage takes of the switch is the part that carries over, and the plan asks for
 * that share.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfileSwitchTimingBenchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val model = OpportunityScoringModel.Legacy
    private val logger = StageRecordingLogger()

    @Before
    fun setUp() {
        deleteBenchFiles()
    }

    @After
    fun tearDown() {
        deleteBenchFiles()
    }

    @Test
    fun a_profile_switch_reports_what_each_stage_costs() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = BENCH_DB_NAME)
        val repository = repository(store, OfflineYahooClient())
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            // Fill the store first. A switch onto an empty profile reads nothing, and reading is
            // one of the stages under suspicion.
            repository.selectProfile(SEEDED_PROFILE, ViewFilter(), ChartRange.Year, model)
            awaitLive(repository)
            settle()

            val cold = measureSwitch(repository, COLD_PROFILE)
            settle()
            val warm = measureSwitch(repository, SEEDED_PROFILE)
            val large = measureSwitch(repository, LARGE_PROFILE)

            writeReport(cold = cold, warm = warm, large = large)

            assertTrue(
                "the switch reported no stage timings: ${logger.lines()}",
                warm.keys.containsAll(REPORTED_STAGES),
            )
        } finally {
            // Nothing of this test may outlive it: Robolectric drops the database connections when
            // the test returns, and a write that arrives after that is reported against the class
            // that runs next. Cancel, drain the dispatcher, then close.
            runCatching { repository.clearAllData() }
            settle()
            store.close()
        }
    }

    /**
     * What the refresh costs when the provider answers at once.
     *
     * Every millisecond here is local: fetch, score, persist, with no socket in the way. It says
     * whether the pipeline is slow on its own, which is the question the concurrency constant
     * cannot be judged without.
     */
    @Test
    fun a_refresh_round_reports_what_it_costs_with_an_instant_provider() = runTest(dispatcher) {
        val store = SQLiteStateStore(context, ioDispatcher = dispatcher, databaseFileName = BENCH_DB_NAME)
        val repository = repository(store, OfflineYahooClient())
        try {
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, model)
            logger.clear()
            repository.selectProfile(SEEDED_PROFILE, ViewFilter(), ChartRange.Year, model)
            awaitLive(repository)
            settle()

            val samples = logger.stageSamples()
            val perSymbol = samples["refresh.symbol"].orEmpty().sorted()
            val report = buildString {
                appendLine("Refresh round bench (DEF-08, step 1: measure)")
                appendLine("Profile: $SEEDED_PROFILE. Provider: offline fake, answers at once.")
                appendLine("Concurrency: the shipped constant. Every reading below is local work.")
                appendLine()
                appendLine("| Reading | ms |")
                appendLine("| --- | --- |")
                appendLine("| first symbol applied | ${samples["refresh.first-symbol"]?.first() ?: "-"} |")
                appendLine("| round total | ${samples["refresh.round"]?.first() ?: "-"} |")
                appendLine("| per symbol, fastest | ${perSymbol.firstOrNull() ?: "-"} |")
                appendLine("| per symbol, middle | ${perSymbol.getOrNull(perSymbol.size / 2) ?: "-"} |")
                appendLine("| per symbol, slowest | ${perSymbol.lastOrNull() ?: "-"} |")
                appendLine("| symbols fetched | ${perSymbol.size} |")
            }
            println(report)
            runCatching { File(context.cacheDir, "refresh-round-bench-report.txt").writeText(report) }

            assertTrue("the refresh reported no per-symbol timings", perSymbol.isNotEmpty())
        } finally {
            // Nothing of this test may outlive it: Robolectric drops the database connections when
            // the test returns, and a write that arrives after that is reported against the class
            // that runs next. Cancel, drain the dispatcher, then close.
            runCatching { repository.clearAllData() }
            settle()
            store.close()
        }
    }

    /**
     * Runs one switch and returns the stage readings it wrote, in milliseconds.
     *
     * The log is cleared first, so every reading belongs to the switch just made. Only the
     * `switch.` stages are kept: the refresh that the switch starts runs in the background, and its
     * lines would land inside the next switch's window.
     */
    private suspend fun measureSwitch(
        repository: DefaultDashboardRepository,
        profile: String,
    ): Map<String, Long> {
        logger.clear()
        repository.selectProfile(profile, ViewFilter(), ChartRange.Year, model)
        return logger.stageMillis().filterKeys { stage -> stage.startsWith("switch.") }
    }

    private fun settle() {
        repeat(20) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    private fun repository(store: SQLiteStateStore, client: YahooFinanceClient) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { 1_700_000_000L },
        ioDispatcher = dispatcher,
        logger = logger,
    )

    private suspend fun awaitLive(repository: DefaultDashboardRepository, timeoutMs: Long = 8_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
        while (snapshot.trackedRows.none { it.state == TrackedRowState.Live }) {
            if (System.currentTimeMillis() >= deadline) {
                return
            }
            Thread.sleep(10)
            dispatcher.scheduler.advanceUntilIdle()
            snapshot = repository.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
        }
    }

    private fun writeReport(cold: Map<String, Long>, warm: Map<String, Long>, large: Map<String, Long>) {
        val report = buildString {
            appendLine("Profile switch timing bench (DEF-08, step 1: measure)")
            appendLine("Database: $BENCH_DB_NAME (isolated). Provider: offline fake. JVM + Robolectric.")
            appendLine("Seeded: $SEEDED_PROFILE. Cold: $COLD_PROFILE. Large: $LARGE_PROFILE (no rows on file).")
            appendLine()
            appendLine("| Stage | $COLD_PROFILE cold ms | $SEEDED_PROFILE warm ms | $LARGE_PROFILE ms |")
            appendLine("| --- | --- | --- | --- |")
            REPORTED_STAGES.forEach { stage ->
                appendLine("| $stage | ${cold[stage] ?: "-"} | ${warm[stage] ?: "-"} | ${large[stage] ?: "-"} |")
            }
        }
        println(report)
        runCatching { File(context.cacheDir, "profile-switch-bench-report.txt").writeText(report) }
    }

    private fun deleteBenchFiles() {
        val base = context.getDatabasePath(BENCH_DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    companion object {
        private const val BENCH_DB_NAME = "profile_switch_bench.sqlite3"
        private const val SEEDED_PROFILE = "dow"
        private const val COLD_PROFILE = "merval"
        private const val LARGE_PROFILE = "sp500"

        private val REPORTED_STAGES = listOf(
            "switch.resolve-symbols",
            "switch.cancel-active-work",
            "switch.load-warm-start",
            "switch.adopt-profile",
            "switch.to-first-emit",
        )
    }
}
