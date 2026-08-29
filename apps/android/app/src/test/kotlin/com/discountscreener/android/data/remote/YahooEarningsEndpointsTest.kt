package com.discountscreener.android.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YahooEarningsEndpointsTest {

    @Test
    fun the_option_chain_request_carries_the_yahoo_crumb() = runTest {
        var seen = mutableListOf<String>()
        client(seen).fetchOptionChain("LVS")

        assertTrue(seen.first { it.contains("/options/") }.contains("crumb=testcrumb"))
    }

    @Test
    fun the_option_chain_request_asks_for_the_expiry_it_was_given() = runTest {
        var seen = mutableListOf<String>()
        client(seen).fetchOptionChain("LVS", expiryEpochSeconds = 1_787_875_200L)

        assertTrue(seen.first { it.contains("/options/") }.contains("date=1787875200"))
    }

    @Test
    fun the_option_chain_answer_reads_back_as_a_ladder() = runTest {
        var chain = client(mutableListOf()).fetchOptionChain("LVS")

        assertEquals(listOf(44.0), chain?.rows?.map { it.strike })
    }

    @Test
    fun the_option_chain_answer_names_its_expiry() = runTest {
        var chain = client(mutableListOf()).fetchOptionChain("LVS")

        assertEquals(1, chain?.expiries?.size)
    }

    @Test
    fun the_consensus_comes_from_the_quote_summary_the_app_already_asks_for() = runTest {
        var seen = mutableListOf<String>()
        client(seen).fetchConsensus("LVS")

        assertTrue(seen.first { it.contains("quoteSummary") }.contains("earningsTrend"))
    }

    @Test
    fun the_consensus_of_the_reporting_quarter_reads_back() = runTest {
        var consensus = client(mutableListOf()).fetchConsensus("LVS")

        assertEquals(0.62, consensus?.avgEps)
    }

    @Test
    fun a_period_the_answer_does_not_carry_refuses() = runTest {
        var consensus = client(mutableListOf()).fetchConsensus("LVS", period = "+5y")

        assertEquals(null, consensus)
    }

    @Test
    fun an_answer_that_is_not_json_never_passes_for_a_consensus() = runTest {
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder()
                .addInterceptor(recording(mutableListOf(), quoteSummary = "<html>429</html>"))
                .build(),
        )

        assertThrows(Throwable::class.java) { runBlocking { client.fetchConsensus("LVS") } }
    }

    @Test
    fun an_empty_result_array_is_asked_again_with_a_fresh_cookie() = runTest {
        var seen = mutableListOf<String>()
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder()
                .addInterceptor(recording(seen, options = EMPTY_RESULT_BODY))
                .build(),
        )
        runCatching { client.fetchOptionChain("LVS") }

        assertEquals(2, seen.count { it.contains("/options/") })
    }

    @Test
    fun a_chain_endpoint_that_keeps_refusing_never_passes_for_a_ticker_without_options() = runTest {
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder()
                .addInterceptor(recording(mutableListOf(), options = EMPTY_RESULT_BODY))
                .build(),
        )

        assertThrows(Throwable::class.java) { runBlocking { client.fetchOptionChain("LVS") } }
    }

    @Test
    fun a_ticker_that_really_carries_no_options_is_never_asked_twice() = runTest {
        var seen = mutableListOf<String>()
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder()
                .addInterceptor(recording(seen, options = NO_OPTIONS_BODY))
                .build(),
        )
        client.fetchOptionChain("THIN")

        assertEquals(1, seen.count { it.contains("/options/") })
    }

    @Test
    fun the_report_date_is_asked_on_the_calendar_module_alone() = runTest {
        var seen = mutableListOf<String>()
        client(seen).fetchNextEarningsEpoch("LVS", nowEpochSeconds = 1_787_000_000L)

        assertEquals("calendarEvents", seen.first { it.contains("quoteSummary") }.substringAfter("modules=").substringBefore("&"))
    }

    @Test
    fun the_next_report_ahead_of_now_is_the_one_that_reads_back() = runTest {
        var epoch = client(mutableListOf(), quoteSummary = CALENDAR_BODY)
            .fetchNextEarningsEpoch("LVS", nowEpochSeconds = 1_787_000_000L)

        assertEquals(1_787_875_200L, epoch)
    }

    @Test
    fun a_calendar_with_no_date_on_it_reads_back_as_nothing() = runTest {
        var epoch = client(mutableListOf(), quoteSummary = EMPTY_CALENDAR_BODY)
            .fetchNextEarningsEpoch("LVS", nowEpochSeconds = 1_787_000_000L)

        assertEquals(null, epoch)
    }

    private fun client(seen: MutableList<String>, quoteSummary: String = QUOTE_SUMMARY_BODY) = YahooFinanceClient(
        httpClient = OkHttpClient.Builder().addInterceptor(recording(seen, quoteSummary)).build(),
    )

    private fun recording(
        seen: MutableList<String>,
        quoteSummary: String = QUOTE_SUMMARY_BODY,
        options: String = OPTIONS_BODY,
    ) = Interceptor { chain ->
        var request = chain.request()
        seen += request.url.toString()
        var payload = when {
            request.url.encodedPath.contains("getcrumb") -> "testcrumb"
            request.url.encodedPath.contains("/options/") -> options
            request.url.encodedPath.contains("quoteSummary") -> quoteSummary
            else -> "<html></html>"
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(payload.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private companion object {
        const val OPTIONS_BODY = """{"optionChain":{"result":[{"underlyingSymbol":"LVS",
            "expirationDates":[1787875200],"quote":{"regularMarketPrice":44.24},
            "options":[{"expirationDate":1787875200,
            "calls":[{"strike":44.0,"bid":1.60,"ask":1.72}],
            "puts":[{"strike":44.0,"bid":1.38,"ask":1.50}]}]}]}}"""

        const val EMPTY_RESULT_BODY = """{"optionChain":{"result":[],"error":null}}"""

        const val NO_OPTIONS_BODY = """{"optionChain":{"result":[{"underlyingSymbol":"THIN",
            "expirationDates":[],"options":[]}],"error":null}}"""

        const val CALENDAR_BODY = """{"quoteSummary":{"result":[{"calendarEvents":{"earnings":{
            "earningsDate":[{"raw":1787875200},{"raw":1788048000}]}}}],"error":null}}"""

        const val EMPTY_CALENDAR_BODY =
            """{"quoteSummary":{"result":[{"calendarEvents":{"earnings":{"earningsDate":[]}}}],"error":null}}"""

        const val QUOTE_SUMMARY_BODY = """{"quoteSummary":{"result":[{"earningsTrend":{"trend":[
            {"period":"0q","endDate":"2026-09-30","earningsEstimate":{"avg":{"raw":0.62},
            "low":{"raw":0.51},"high":{"raw":0.74},"numberOfAnalysts":{"raw":17}},
            "revenueEstimate":{"avg":{"raw":3050000000}}}]}}],"error":null}}"""
    }
}
