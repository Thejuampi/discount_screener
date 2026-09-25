package com.discountscreener.android.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.discountscreener.core.earnings.EXCHANGE_ZONE
import com.discountscreener.core.earnings.MARKET_OPENS

/**
 * The worker exists so a report is priced without the user opening the app. Each test here reads
 * what the worker did with the session it woke up in, never what the recorder wrote.
 */
@RunWith(RobolectricTestRunner::class)
class EarningsCaptureWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun a_run_inside_the_session_captures() {
        var worker = worker(at = LocalTime.of(11, 0))

        runBlocking { worker.doWork() }

        assertEquals(1, worker.captures)
    }

    @Test
    fun a_run_before_the_open_never_spends_a_request() {
        var worker = worker(at = MARKET_OPENS.minusMinutes(1))

        runBlocking { worker.doWork() }

        assertEquals(0, worker.captures)
    }

    @Test
    fun a_run_outside_the_session_still_succeeds_so_the_schedule_survives() {
        var worker = worker(at = LocalTime.of(4, 0))

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }

    @Test
    fun a_capture_that_reaches_a_dead_network_asks_to_be_run_again() {
        var worker = worker(at = LocalTime.of(11, 0), failure = IOException("offline"))

        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker.doWork() })
    }

    @Test
    fun a_capture_that_wrote_nothing_still_counts_as_a_healthy_run() {
        var worker = worker(at = LocalTime.of(11, 0), written = 0)

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }

    private fun worker(
        at: LocalTime,
        written: Int = 1,
        failure: Throwable? = null,
    ): RecordingWorker = TestListenableWorkerBuilder<RecordingWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = RecordingWorker(appContext, workerParameters, at, written, failure)
            },
        )
        .build()

    class RecordingWorker(
        context: Context,
        parameters: WorkerParameters,
        private val at: LocalTime,
        private val written: Int,
        private val failure: Throwable?,
    ) : EarningsCaptureWorker(context, parameters) {
        var captures = 0
            private set

        override fun now(): Instant = WEDNESDAY.atTime(at).atZone(EXCHANGE_ZONE).toInstant()

        override suspend fun capture(): Int {
            captures++
            failure?.let { throw it }
            return written
        }
    }

    private companion object {
        val WEDNESDAY: LocalDate = LocalDate.of(2026, 8, 26)
    }
}
