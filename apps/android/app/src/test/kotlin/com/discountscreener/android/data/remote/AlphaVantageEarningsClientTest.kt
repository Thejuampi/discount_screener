package com.discountscreener.android.data.remote

import java.io.File
import java.util.concurrent.TimeUnit
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
        var client = client { url ->
            if (url.contains("EARNINGS_ESTIMATES")) ESTIMATES else EARNINGS
        }
        client.saveKey("demo")
        assertEquals(java.time.LocalDate.of(2026, 6, 30), client.quarters("IBM").first().fiscalEnd)
    }

    @Test
    fun a_cold_ticker_asks_for_earnings_and_estimates() {
        var functions = mutableListOf<String>()
        var client = client { url ->
            functions += if (url.contains("EARNINGS_ESTIMATES")) "ESTIMATES" else "EARNINGS"
            if (url.contains("EARNINGS_ESTIMATES")) ESTIMATES else EARNINGS
        }
        client.saveKey("k")
        client.quarters("IBM")
        assertEquals(listOf("EARNINGS", "ESTIMATES"), functions)
    }

    @Test
    fun a_fresh_cache_never_hits_the_network() {
        var calls = 0
        seedCache()
        var client = client { calls++; error("no network") }
        client.saveKey("k")
        client.quarters("IBM")
        assertEquals(0, calls)
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
        var client = client { error("no network") }
        client.saveKey("k")
        assertEquals(java.time.LocalDate.of(2026, 6, 30), client.quarters("IBM").first().fiscalEnd)
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

    private fun client(get: (String) -> String = { error("no network") }) = AlphaVantageEarningsClient(
        cacheDir = cacheDir(),
        keyFile = keyFile(),
        budgetFile = budgetFile(),
        now = { NOW },
        get = get,
    )

    private companion object {
        const val NOW = 1_787_770_800L
        val DAY = java.time.Instant.ofEpochSecond(NOW)
            .atZone(java.time.ZoneId.of("America/New_York"))
            .toLocalDate()
            .toEpochDay()
        const val EARNINGS =
            """{"symbol":"IBM","quarterlyEarnings":[{"fiscalDateEnding":"2026-06-30","reportedDate":"2026-07-22","reportedEPS":"2.93","estimatedEPS":"2.93"}]}"""
        const val ESTIMATES =
            """{"symbol":"IBM","estimates":[{"date":"2026-06-30","horizon":"fiscal quarter","eps_estimate_average":"2.9331","eps_estimate_high":"2.96","eps_estimate_low":"2.9279","eps_estimate_analyst_count":"9"}]}"""
    }
}
