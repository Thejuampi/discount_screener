package com.discountscreener.android.data.remote

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing an endpoint nobody publishes a contract for, so every test here is about what happens
 * when the shape is not what we expect. The reading feeds the action stance, and a wrong stance
 * re-scores the whole list — so unreadable must resolve to nothing rather than to a number.
 */
class CnnFearGreedClientTest {
    /** The live shape, trimmed to the fields read — captured from the endpoint on 2026-08-09. */
    @Test
    fun the_live_shape_parses_into_a_reading() {
        assertEquals(
            63.6857142857143 to "Greed",
            parse(liveBody())!!.let { it.score to it.rating },
        )
    }

    @Test
    fun the_previous_closes_are_carried_through() {
        assertEquals(59.7142857142857, parse(liveBody())!!.previousClose)
    }

    @Test
    fun an_iso_timestamp_becomes_an_epoch() {
        assertEquals(1786147187L, parse(liveBody())!!.fetchedAtEpoch)
    }

    /** `"greed"` is what the endpoint sends; the app displays `"Greed"`. */
    @Test
    fun a_lowercase_rating_is_title_cased() {
        assertEquals("Extreme Fear", parse(bodyOf(score = 10.0, rating = "\"extreme fear\""))!!.rating)
    }

    @Test
    fun a_missing_rating_is_derived_from_the_score() {
        assertEquals("Neutral", parse(bodyOf(score = 50.0, rating = "null"))!!.rating)
    }

    /**
     * Out of range is unreadable, not clampable. Clamping a garbled 999 to 100 would publish
     * "extreme greed" — the single reading that most moves the stance — with full confidence.
     */
    @Test
    fun a_score_outside_the_scale_is_refused_rather_than_clamped() {
        assertNull(parse(bodyOf(score = 999.0, rating = "\"greed\"")))
    }

    @Test
    fun a_response_with_no_reading_in_it_is_refused() {
        assertNull(parse("""{"fear_and_greed_historical":{"data":[]}}"""))
    }

    @Test
    fun a_body_that_is_not_json_is_refused() {
        assertNull(parse("<html>418 I'm a teapot</html>"))
    }

    @Test
    fun cancelled_cnn_body_read_aborts() = runTest {
        var started = java.util.concurrent.atomic.AtomicBoolean(false)
        var interceptor = Interceptor { chain ->
            started.set(true)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(SlowCnnBody())
                .build()
        }
        var client = CnnFearGreedClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )
        var job = launch {
            try {
                client.fetch(LocalDate.of(2026, 8, 17))
            } catch (_: CancellationException) {
            }
        }
        while (!started.get()) {
            kotlinx.coroutines.delay(10)
        }
        kotlinx.coroutines.delay(20)
        var startedAt = System.nanoTime()
        job.cancel()
        job.join()
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(
            "cancel waited ${elapsedMs}ms on a CNN body read",
            true,
            elapsedMs < 250,
        )
    }

    private fun parse(body: String) = parseFearGreed(body, JSON)

    private fun bodyOf(score: Double, rating: String) =
        """{"fear_and_greed":{"score":$score,"rating":$rating}}"""

    private fun liveBody() = """
        {
          "fear_and_greed": {
            "score": 63.6857142857143,
            "rating": "greed",
            "timestamp": "2026-08-07T23:59:47+00:00",
            "previous_close": 59.7142857142857,
            "previous_1_week": 45.2285714285714,
            "previous_1_month": 39.771428571428565
          },
          "fear_and_greed_historical": { "data": [{ "x": 1786233600000, "y": 63.6, "rating": "greed" }] }
        }
    """.trimIndent()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private class SlowCnnBody : okhttp3.ResponseBody() {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun contentType() = "application/json".toMediaType()
    override fun contentLength() = Long.MAX_VALUE
    override fun source(): okio.BufferedSource {
        return object : okio.Source {
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                var waited = 0
                while (waited < 10_000) {
                    if (closed.get()) {
                        throw java.io.IOException("Canceled")
                    }
                    Thread.sleep(20)
                    waited += 20
                }
                return -1
            }

            override fun timeout() = okio.Timeout.NONE

            override fun close() {
                closed.set(true)
            }
        }.buffer()
    }
}
