package com.discountscreener.core.harness

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YahooQuantLiveClientTest {
    @Test
    fun aapl_fixture_sector_is_technology() {
        var data = YahooQuantLiveClient(FixtureYahooTransport()).fetch("AAPL")
        assertEquals("Technology", data.fundamentals.sectorName)
    }

    @Test
    fun cached_miss_uses_yahoo_live_and_keeps_the_sector() {
        var cacheDir = Files.createTempDirectory("quant-harness-yahoo-miss")
        var data = QuantHarness.cached(
            client = YahooQuantLiveClient(FixtureYahooTransport()),
            cacheDir = cacheDir,
        ).load("AAPL")
        assertEquals("Technology", data.fundamentals.sectorName)
    }

    @Test
    fun cached_hit_rereads_the_yahoo_pack() {
        var cacheDir = Files.createTempDirectory("quant-harness-yahoo-hit")
        var source = QuantHarness.cached(
            client = YahooQuantLiveClient(FixtureYahooTransport()),
            cacheDir = cacheDir,
        )
        source.load("AAPL")
        var data = source.load("AAPL")
        assertEquals(true, data.cacheHit)
    }

    @Test
    fun timeseries_fills_later_interest_years_from_the_non_operating_series() {
        var body = """
            {"timeseries":{"result":[
              {"annualInterestExpense":[
                {"asOfDate":"2023-09-30","reportedValue":{"raw":3933000000}}
              ]},
              {"annualInterestExpenseNonOperating":[
                {"asOfDate":"2024-09-30","reportedValue":{"raw":4000000000}},
                {"asOfDate":"2025-09-30","reportedValue":{"raw":3500000000}}
              ]}
            ]}}
        """.trimIndent()
        var series = parseTimeseries(body)
        assertEquals(
            listOf("2023-09-30", "2024-09-30", "2025-09-30"),
            series.interestExpense.map { it.asOfDate },
        )
    }

    @Test
    fun missing_quote_summary_fails_closed() {
        assertFailsWith<IllegalStateException> {
            YahooQuantLiveClient(FixtureYahooTransport()).fetch("MISSING")
        }
    }
}
