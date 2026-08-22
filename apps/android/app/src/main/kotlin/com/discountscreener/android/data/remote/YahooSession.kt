package com.discountscreener.android.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared Yahoo cookie + crumb session used by quoteSummary (and similar) JSON APIs.
 *
 * Bootstrap mirrors the Yahoo web app:
 * 1. Visit finance.yahoo.com to obtain A1/A3 cookies
 * 2. GET /v1/test/getcrumb with those cookies
 *
 * The two calls run once and everybody else waits for that one run. The wait used to be a
 * `synchronized` block, which parks an operating-system thread rather than suspending a coroutine:
 * at every twelve-minute expiry each symbol in flight took a thread out of the IO pool and held it
 * across two network calls. A coroutine [Mutex] gives the thread back while the caller waits.
 */
internal class YahooSession(
    private val httpClient: OkHttpClient,
    private val userAgent: String,
    private val crumbTtlMillis: Long = TimeUnit.MINUTES.toMillis(12),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class CrumbState(
        val crumb: String,
        val obtainedAtEpochMs: Long,
    )

    private val mutex = Mutex()
    private val state = AtomicReference<CrumbState?>(null)

    fun clear() {
        state.set(null)
    }

    fun currentCrumbOrNull(): String? {
        val current = state.get() ?: return null
        if (clock() - current.obtainedAtEpochMs > crumbTtlMillis) return null
        return current.crumb
    }

    suspend fun ensureCrumb(): String {
        currentCrumbOrNull()?.let { return it }
        mutex.withLock {
            // Checked again inside the lock: while this caller waited, the winner of the race
            // already paid for the handshake, and a second one would only annoy Yahoo.
            currentCrumbOrNull()?.let { return it }
            bootstrapCookies()
            val crumb = fetchCrumb()
            state.set(CrumbState(crumb = crumb, obtainedAtEpochMs = clock()))
            return crumb
        }
    }

    private suspend fun bootstrapCookies() {
        val request = Request.Builder()
            .url(BOOTSTRAP_URL)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        executeCancellable(request).use { response ->
            // Cookies land in the shared CookieJar even when Yahoo returns non-2xx occasionally.
            if (!response.isSuccessful && response.code !in 300..399) {
                // Still allow crumb attempt if cookies were set; only hard-fail when body path is required later.
            }
        }
    }

    private suspend fun fetchCrumb(): String {
        var lastError: IOException? = null
        for (url in CRUMB_URLS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .get()
                    .build()
                val call = httpClient.newCall(request)
                executeCancellable(call).use { response ->
                    val body = readBodyCancellable(call, response).trim()
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} for $url: $body")
                    }
                    if (body.isBlank() || body.startsWith("{") || body.length > 80) {
                        throw IOException("invalid crumb payload from $url")
                    }
                    return body
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("failed to obtain Yahoo crumb")
    }

    private suspend fun executeCancellable(request: Request): okhttp3.Response {
        return executeCancellable(httpClient.newCall(request))
    }

    private suspend fun executeCancellable(call: Call): okhttp3.Response {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive || call.isCanceled() || canceledIo(e)) {
                        if (continuation.isActive) {
                            continuation.cancel(CancellationException("Yahoo call cancelled", e))
                        }
                    } else {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (continuation.isActive) {
                        continuation.resume(response) { _, _, _ -> response.close() }
                    } else {
                        response.close()
                    }
                }
            })
        }
    }

    private suspend fun readBodyCancellable(call: Call, response: okhttp3.Response): String =
        suspendCancellableCoroutine { continuation ->
            val reader = Thread({
                try {
                    val text = response.body?.string().orEmpty()
                    if (continuation.isActive) {
                        continuation.resume(text)
                    }
                } catch (error: IOException) {
                    if (!continuation.isActive) {
                        return@Thread
                    }
                    if (call.isCanceled() || canceledIo(error)) {
                        continuation.cancel(CancellationException("Yahoo call cancelled", error))
                    } else {
                        continuation.resumeWithException(error)
                    }
                }
            }, "yahoo-crumb-body")
            continuation.invokeOnCancellation {
                call.cancel()
                response.close()
            }
            reader.start()
        }

    companion object {
        private const val BOOTSTRAP_URL = "https://finance.yahoo.com/"
        private val CRUMB_URLS = listOf(
            "https://query2.finance.yahoo.com/v1/test/getcrumb",
            "https://query1.finance.yahoo.com/v1/test/getcrumb",
        )
    }
}
