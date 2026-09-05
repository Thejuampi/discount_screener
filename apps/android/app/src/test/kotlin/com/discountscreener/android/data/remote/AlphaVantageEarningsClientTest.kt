package com.discountscreener.android.data.remote

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlphaVantageEarningsClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun a_missing_key_yields_no_history() {
        assertTrue(client().quarters("IBM").isEmpty())
    }

    @Test
    fun ibm_bodies_join_into_sue_quarters() {
        var client = client(
            cannedHttpClient(
                listOf(
                    "function=EARNINGS_ESTIMATES" to ESTIMATES,
                    "function=EARNINGS&" to EARNINGS,
                ),
            ),
        )
        client.saveKey("demo")
        assertEquals(LocalDate.of(2026, 6, 30), client.quarters("IBM").first().fiscalEnd)
    }

    @Test
    fun a_cold_ticker_asks_for_earnings_and_estimates() {
        var functions = mutableListOf<String>()
        var client = client(recordingHttp(functions))
        client.saveKey("k")
        client.quarters("IBM")
        assertEquals(listOf("EARNINGS", "ESTIMATES"), functions)
    }

    @Test
    fun a_fresh_cache_never_hits_the_network() {
        seedCache()
        var client = client()
        client.saveKey("k")
        assertEquals(LocalDate.of(2026, 6, 30), client.quarters("IBM").first().fiscalEnd)
    }

    @Test
    fun a_blank_key_deletes_the_file() {
        var client = client()
        client.saveKey("demo")
        client.saveKey("  ")
        assertFalse(keyFile().isFile)
    }

    @Test
    fun a_spent_budget_falls_back_to_stale_cache() {
        seedCache(ageDays = 8)
        budgetFile().writeText("""{"dayEpoch":$DAY,"lastCallEpoch":$NOW,"callsToday":25}""")
        var client = client()
        client.saveKey("k")
        assertEquals(LocalDate.of(2026, 6, 30), client.quarters("IBM").first().fiscalEnd)
    }

    private fun seedCache(ageDays: Long = 0) {
        var cache = cacheDir()
        var earnings = File(cache, "IBM-EARNINGS.json").also { it.writeText(EARNINGS) }
        var estimates = File(cache, "IBM-EARNINGS_ESTIMATES.json").also { it.writeText(ESTIMATES) }
        if (ageDays > 0) {
            var stamp = NOW * 1_000L - TimeUnit.DAYS.toMillis(ageDays)
            earnings.setLastModified(stamp)
            estimates.setLastModified(stamp)
        }
    }

    private fun cacheDir() = File(folder.root, "cache").also { it.mkdirs() }

    private fun keyFile() = File(folder.root, "alphavantage.key")

    private fun budgetFile() = File(folder.root, "budget.json")

    private fun client(http: OkHttpClient = offlineHttpClient()) = AlphaVantageEarningsClient(
        cacheDir = cacheDir(),
        keyFile = keyFile(),
        budgetFile = budgetFile(),
        now = { NOW },
        httpClient = http,
    )

    private fun recordingHttp(seen: MutableList<String>): OkHttpClient {
        var json = "application/json".toMediaType()
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    var request = chain.request()
                    var url = request.url.toString()
                    seen += if (url.contains("EARNINGS_ESTIMATES")) "ESTIMATES" else "EARNINGS"
                    var body = if (url.contains("EARNINGS_ESTIMATES")) ESTIMATES else EARNINGS
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(json))
                        .build()
                },
            )
            .build()
    }

    private companion object {
        const val NOW = 1_787_770_800L
        val DAY = Instant.ofEpochSecond(NOW)
            .atZone(ZoneId.of("America/New_York"))
            .toLocalDate()
            .toEpochDay()
        const val EARNINGS =
            """{"symbol":"IBM","quarterlyEarnings":[{"fiscalDateEnding":"2026-06-30","reportedDate":"2026-07-22","reportedEPS":"2.93","estimatedEPS":"2.93"}]}"""
        const val ESTIMATES =
            """{"symbol":"IBM","estimates":[{"date":"2026-06-30","horizon":"fiscal quarter","eps_estimate_average":"2.9331","eps_estimate_high":"2.96","eps_estimate_low":"2.9279","eps_estimate_analyst_count":"9"}]}"""
    }
}
