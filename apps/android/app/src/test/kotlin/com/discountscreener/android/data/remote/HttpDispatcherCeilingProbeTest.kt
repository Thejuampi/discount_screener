package com.discountscreener.android.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many requests the shipped HTTP client really lets reach one host at once: all of them.
 *
 * This probe was written to confirm the opposite. OkHttp's `Dispatcher` defaults to five requests
 * per host, and the guess was that it sat under [AdaptiveRequestWindow] and made every reading
 * taken from the window a reading of a queue. The measurement said forty in flight on one host,
 * because `maxRequestsPerHost` bounds `enqueue()` and this app calls `execute()`, which opens the
 * connection on the calling thread and never reaches the dispatcher.
 *
 * So the client bounds nothing, and [RequestGovernor] is the only thing that decides what Yahoo is
 * asked. That is the claim this test holds: a day when someone moves to `enqueue()` or sets a
 * per-host limit, this goes red and the governor stops being the whole story.
 *
 * The interceptor answers without a socket, so no call leaves the machine.
 */
class HttpDispatcherCeilingProbeTest {

    @Test
    fun the_shipped_client_bounds_nothing_and_leaves_the_ceiling_to_the_governor() {
        var inFlight = AtomicInteger(0)
        var peak = AtomicInteger(0)
        var client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    var now = inFlight.incrementAndGet()
                    peak.getAndUpdate { seen -> maxOf(seen, now) }
                    Thread.sleep(HOLD_MILLIS)
                    inFlight.decrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        var peakOneHost = fire(client, CALLS) { index -> "$ONE_HOST/$index" }
        inFlight.set(0)
        peak.set(0)
        var peakManyHosts = fire(client, CALLS) { index -> "${HOSTS[index % HOSTS.size]}/$index" }

        println(
            buildString {
                appendLine("HTTP client ceiling probe: $CALLS calls asked for at once")
                appendLine("Dispatcher: whatever the shipped builder leaves in place.")
                appendLine("Peak in flight, one host: $peakOneHost")
                appendLine("Peak in flight, ${HOSTS.size} hosts: $peakManyHosts")
                appendLine("Window ceiling asked for: ${AdaptiveRequestWindow.DEFAULT_MAX_WINDOW}")
            },
        )

        assertTrue(
            "one host took only $peakOneHost at once, so something under the governor is bounding calls",
            peakOneHost >= AdaptiveRequestWindow.DEFAULT_MAX_WINDOW,
        )
    }

    private fun fire(client: OkHttpClient, calls: Int, url: (Int) -> String): Int {
        var pool = Executors.newFixedThreadPool(calls)
        var start = CountDownLatch(1)
        var done = CountDownLatch(calls)
        var inFlight = AtomicInteger(0)
        var peak = AtomicInteger(0)
        var watcher = Thread {
            while (done.count > 0) {
                peak.getAndUpdate { seen -> maxOf(seen, inFlight.get()) }
                Thread.sleep(1)
            }
        }
        try {
            repeat(calls) { index ->
                pool.execute {
                    start.await()
                    var request = Request.Builder().url(url(index)).build()
                    inFlight.incrementAndGet()
                    runCatching { client.newCall(request).execute().use { it.body?.close() } }
                    inFlight.decrementAndGet()
                    done.countDown()
                }
            }
            watcher.start()
            start.countDown()
            done.await()
            watcher.join()
            return peak.get()
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        private const val CALLS = 40
        private const val HOLD_MILLIS = 150L
        private const val ONE_HOST = "https://query1.finance.yahoo.com/v10/finance/quoteSummary"
        private val HOSTS = listOf(
            "https://query1.finance.yahoo.com/v10/finance/quoteSummary",
            "https://query2.finance.yahoo.com/v10/finance/quoteSummary",
            "https://finance.yahoo.com/quote",
        )
    }
}
