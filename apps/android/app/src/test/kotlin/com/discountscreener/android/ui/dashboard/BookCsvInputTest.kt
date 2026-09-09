package com.discountscreener.android.ui.dashboard

import android.net.Uri
import com.discountscreener.android.data.portfolio.BookCsvInputException
import com.discountscreener.android.data.portfolio.BookCsvReadGeneration
import com.discountscreener.android.data.portfolio.BookCsvInputReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookCsvInputTest {

    @Test
    fun reader_rejects_input_above_the_bound() {
        var reader = BookCsvInputReader(
            openStream = { ByteArrayInputStream("12345".toByteArray()) },
            maxBytes = 4,
        )

        var error = assertThrows(BookCsvInputException.TooLarge::class.java) {
            runBlocking { reader.read(Uri.parse("content://book")) }
        }

        assertEquals(4L, error.maxBytes)
    }

    @Test
    fun reader_honors_cancellation_before_open() {
        var opened = false
        var reader = BookCsvInputReader(
            openStream = {
                opened = true
                ByteArrayInputStream("csv".toByteArray())
            },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                coroutineContext.cancel()
                reader.read(Uri.parse("content://book"))
            }
        }
        assertEquals(false, opened)
    }

    @Test
    fun reader_runs_a_slow_document_provider_off_the_main_thread() = runBlocking {
        var openedOn: Thread? = null
        var reader = BookCsvInputReader(
            openStream = {
                openedOn = Thread.currentThread()
                Thread.sleep(25L)
                ByteArrayInputStream("csv".toByteArray())
            },
        )

        assertEquals("csv", reader.read(Uri.parse("content://book")))
        assertNotEquals(android.os.Looper.getMainLooper().thread, openedOn)
    }

    @Test
    fun reader_cancels_a_blocked_document_provider_after_open() = runBlocking(Dispatchers.Default) {
        var stream = BlockingInputStream()
        var reader = BookCsvInputReader(openStream = { stream })
        var job = launch { reader.read(Uri.parse("content://book")) }

        assertTrue(stream.opened.await(1, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(1_000L) { job.join() }
        assertTrue(stream.interrupted.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun cancel_then_new_read_does_not_accept_the_old_generation() {
        var generations = BookCsvReadGeneration()
        var old = generations.begin()

        generations.cancel()
        var current = generations.begin()

        assertEquals(false, generations.isCurrent(old))
        assertEquals(true, generations.isCurrent(current))
    }

    private class BlockingInputStream : InputStream() {
        val opened = CountDownLatch(1)
        val interrupted = CountDownLatch(1)

        override fun read(): Int {
            opened.countDown()
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1))
                return -1
            } catch (_: InterruptedException) {
                interrupted.countDown()
                throw InterruptedIOException("read interrupted")
            }
        }
    }
}
