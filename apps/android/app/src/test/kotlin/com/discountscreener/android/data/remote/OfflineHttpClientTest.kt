package com.discountscreener.android.data.remote

import okhttp3.Request
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The guard is machinery that only ever runs when something is wrong, so it needs its own check.
 *
 * The failure mode it must survive is a well-meaning edit. `Interceptor.intercept` declares
 * `throws IOException`, so the natural thing to throw here is an `IOException` — and the clients
 * this guards catch `IOException` in nine places and bare `Exception` in two more. Under that
 * change the guard still "fires", the call still returns a diagnostic, the test still passes, and a
 * live network call goes back to being invisible. [AssertionError] is an `Error`, so no catch in the
 * production path can absorb it.
 */
class OfflineHttpClientTest {
    @Test
    fun a_request_through_the_offline_client_fails_the_test_rather_than_the_call() {
        val call = offlineHttpClient()
            .newCall(Request.Builder().url("https://query1.finance.yahoo.com/v8/finance/chart/AAPL").build())

        assertThrows(AssertionError::class.java) { call.execute() }
    }
}
