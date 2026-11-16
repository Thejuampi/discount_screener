package com.discountscreener.android.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CancellableCallTest {
    private val request = Request.Builder().url("https://example.invalid/").build()
    private fun response(body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    private fun realSource(): BufferedSource {
        val buffer = Buffer().writeUtf8("answer")
        // Buffer.close() is a no-op. This wrapper reproduces RealBufferedSource's closed check.
        return object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long) = buffer.read(sink, byteCount)
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }

    @Test
    fun successful_body_is_returned() = runBlocking {
        val call = OkHttpClient().newCall(request)
        assertEquals("answer", call.readTextCancellable(response("answer".toResponseBody())))
    }

    @Test
    fun closed_body_failure_reaches_active_caller() = runBlocking {
        val source = realSource()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        body.close()
        val uncaught = AtomicReference<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name == "yahoo-body-reader") uncaught.set(error)
            else previous?.uncaughtException(thread, error)
        }
        try {
            val error = supervisorScope {
                val result = async { OkHttpClient().newCall(request).readTextCancellable(response(body)) }
                try {
                    withTimeout(2_000) { result.await() }
                    null
                } catch (error: Exception) { error } finally { result.cancel() }
            }
            assertEquals(IllegalStateException::class.java, error?.javaClass)
            assertEquals("closed", error?.message)
            assertNull("Reader thread must not crash", uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun io_failure_reaches_active_caller() = runBlocking {
        val expected = IOException("read failed")
        assertRecoveredFailure(expected, readFailure(expected, false))
    }

    @Test
    fun runtime_failure_reaches_active_caller() = runBlocking {
        val expected = IllegalArgumentException("invalid source")
        assertRecoveredFailure(expected, readFailure(expected, false))
    }

    @Test
    fun externally_cancelled_call_converts_closed_source_to_cancellation() = runBlocking {
        val expected = IllegalStateException("closed")
        val actual = readFailure(expected, true)
        assertTrue(actual is CancellationException)
        assertEquals("call cancelled", actual?.message)
        assertTrue("Cancellation must retain the original read failure",
            generateSequence(actual?.cause) { it.cause }.any { it === expected })
    }

    private fun assertRecoveredFailure(expected: Exception, actual: Throwable?) {
        // Coroutine debug mode copies exceptions to recover the suspended caller's stack.
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        assertTrue("The cause chain must retain the original read failure",
            generateSequence(actual) { it.cause }.any { it === expected })
    }

    private suspend fun readFailure(expected: Exception, cancelCall: Boolean): Throwable? = supervisorScope {
        val call = OkHttpClient().newCall(request)
        val source = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long { throw expected }
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        if (cancelCall) call.cancel()
        val result = async { call.readTextCancellable(response(body)) }
        try {
            withTimeout(2_000) { result.await() }
            null
        } catch (error: Exception) { error } finally { result.cancel() }
    }

    @Test
    fun cancellation_before_source_selection_does_not_crash_reader() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val uncaught = AtomicReference<Throwable>()
        val source = realSource()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource {
                if (Thread.currentThread().name == "yahoo-body-reader") {
                    reader.set(Thread.currentThread())
                    Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
                return source
            }
        }
        val call = OkHttpClient().newCall(request)
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.readTextCancellable(response(body)) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            result.cancel()
        } finally { release.countDown() }
        reader.get()?.join(2_000)
        assertFalse(reader.get()?.isAlive ?: false)
        assertTrue(result.isCancelled)
        assertTrue(call.isCanceled())
        assertNull("Cancellation must not escape the reader thread", uncaught.get())
    }
}
