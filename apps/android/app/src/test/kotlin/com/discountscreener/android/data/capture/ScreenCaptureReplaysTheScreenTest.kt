package com.discountscreener.android.data.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.repository.DefaultDashboardRepository
import com.discountscreener.android.data.repository.WideGapYahooClient
import com.discountscreener.android.domain.logging.NoOpAppLogger
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.replay.ScreenReplay
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A captured file replays into the numbers the app had on screen.
 *
 * This is the claim the replay tool sells. An experiment measured on a captured file is only worth
 * reading if the file yields the app's own rows, so the check runs the real repository over a real
 * database, captures what it hands the engine, and then projects that file with no repository, no
 * store and no provider in the room. The rows have to agree name by name.
 *
 * Each row is compared on the price, the confidence and the tag the engine worked out, because the
 * engine derives all three from the request. A capture that dropped a field moves one of them. An
 * empty screen would let two empty maps agree, so [the_screen_under_test_has_rows_on_it] pins that
 * there was something to compare.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenCaptureReplaysTheScreenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = OpportunityScoringModel.Legacy
    private val directory: File = Files.createTempDirectory("screen-capture-replay").toFile()
    private lateinit var store: SQLiteStateStore
    private var repository: DefaultDashboardRepository? = null

    @Before
    fun setUp() {
        deleteFiles()
        store = SQLiteStateStore(context, databaseFileName = DB_NAME)
    }

    @After
    fun tearDown() {
        runBlocking {
            repository?.let { open -> runCatching { open.clearAllData() } }
            delay(SETTLE_MILLIS)
        }
        store.close()
        deleteFiles()
    }

    /** Without this, two empty maps would agree and the comparison below would prove nothing. */
    @Test
    fun the_screen_under_test_has_rows_on_it() = runBlocking {
        var run = capture()

        assertNotEquals(emptyMap<String, Cell>(), run.onScreen)
    }

    @Test
    fun the_capture_carries_every_row_on_screen() = runBlocking {
        var run = capture()

        assertEquals(run.onScreen.size, run.replayed.size)
    }

    @Test
    fun every_replayed_row_carries_the_numbers_the_app_showed() = runBlocking {
        var run = capture()

        assertEquals(run.onScreen, run.replayed)
    }

    private suspend fun capture(): Capture {
        var sink = ScreenCaptureSink(directory = directory, logger = NoOpAppLogger)
        var open = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = WideGapYahooClient(),
            universeCatalog = UniverseCatalog(context.assets),
            nowProvider = { NOW_EPOCH },
            defaultProfile = PROFILE,
            projectionCapture = sink::capture,
        )
        repository = open
        open.bootstrap(ViewFilter(), null, ChartRange.Year, model)
        open.refreshAll(ViewFilter(), null, ChartRange.Year, model)
        awaitSettled(open)

        // Armed here, and not at construction: the sink takes the first projection it is offered,
        // and the screen worth capturing is the one the user reads after the load settles.
        File(directory, ScreenCaptureSink.ARM_FILE_NAME).createNewFile()
        var onScreen = open.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            .trackedRows
            .associate { row ->
                row.symbol to Cell(
                    marketPriceCents = row.marketPriceCents,
                    confidence = row.confidence?.name,
                    decision = row.decisionState?.name,
                )
            }
        var file = File(directory, ScreenCaptureSink.REQUEST_FILE_NAME)
        if (!file.isFile) {
            fail("The armed sink wrote no capture while the repository projected the screen.")
        }
        var replayed = ScreenReplay.project(ScreenReplay.decodeRequest(file.readText()))
            .trackedRows
            .associate { row ->
                row.symbol to Cell(
                    marketPriceCents = row.marketPriceCents,
                    confidence = row.confidence.name,
                    decision = row.decision?.name,
                )
            }

        return Capture(onScreen = onScreen, replayed = replayed)
    }

    private suspend fun awaitSettled(open: DefaultDashboardRepository) {
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        while (true) {
            var snapshot = open.currentSnapshot(ViewFilter(), null, ChartRange.Year, model)
            if (snapshot.startupPhase == DashboardStartupPhase.Ready && !open.loadInFlight.value) return
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for a settled load; last phase=${snapshot.startupPhase}")
            }
            delay(POLL_MILLIS)
        }
    }

    private fun deleteFiles() {
        var base = context.getDatabasePath(DB_NAME)
        listOf(base.path, base.path + "-wal", base.path + "-shm").forEach { path -> File(path).delete() }
    }

    /** The three the engine works out for itself, so a request that lost a field moves one. */
    private data class Cell(
        val marketPriceCents: Long?,
        val confidence: String?,
        val decision: String?,
    )

    private data class Capture(
        val onScreen: Map<String, Cell>,
        val replayed: Map<String, Cell>,
    )

    private companion object {
        const val DB_NAME = "screen_capture_replays_the_screen.sqlite3"
        const val PROFILE = "qa"
        const val NOW_EPOCH = 1_700_000_000L
        const val POLL_MILLIS = 50L
        const val DEADLINE_MILLIS = 120_000L
        const val SETTLE_MILLIS = 300L
    }
}
