package com.discountscreener.android.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    private val lock = Mutex()
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
        return lock.withLock {
            // Checked again inside the lock: while this caller waited, the winner of the race
            // already paid for the handshake, and a second one would only annoy Yahoo.
            currentCrumbOrNull() ?: withContext(Dispatchers.IO) {
                bootstrapCookies()
                val crumb = fetchCrumb()
                state.set(CrumbState(crumb = crumb, obtainedAtEpochMs = clock()))
                crumb
            }
        }
    }

    private fun bootstrapCookies() {
        val request = Request.Builder()
            .url(BOOTSTRAP_URL)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            // Cookies land in the shared CookieJar even when Yahoo returns non-2xx occasionally.
            if (!response.isSuccessful && response.code !in 300..399) {
                // Still allow crumb attempt if cookies were set; only hard-fail when body path is required later.
            }
        }
    }

    private fun fetchCrumb(): String {
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
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty().trim()
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} for $url: $body")
                    }
                    if (body.isBlank() || body.startsWith("{") || body.length > 80) {
                        throw IOException("invalid crumb payload from $url")
                    }
                    return body
                }
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("failed to obtain Yahoo crumb")
    }

    companion object {
        private const val BOOTSTRAP_URL = "https://finance.yahoo.com/"
        private val CRUMB_URLS = listOf(
            "https://query2.finance.yahoo.com/v1/test/getcrumb",
            "https://query1.finance.yahoo.com/v1/test/getcrumb",
        )
    }
}
