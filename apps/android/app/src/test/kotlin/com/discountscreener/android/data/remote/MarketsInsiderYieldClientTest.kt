package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.parseMarketsInsiderBondTable
import com.discountscreener.core.engine.parseMarketsInsiderBorrowerId
import com.discountscreener.core.engine.selectIssuerMarketYield
import java.time.LocalDate
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class MarketsInsiderYieldClientTest {
    @Test
    fun table_selects_the_usd_four_to_fifteen_year_median() {
        var quotes = parseMarketsInsiderBondTable(fixture("marketsinsider/apple-bond-table.html"))
        var point = selectIssuerMarketYield(quotes, "2026-08-16")
        assertEquals(471, point?.yieldBps)
    }

    @Test
    fun table_drops_a_dash_yield() {
        var quotes = parseMarketsInsiderBondTable(fixture("marketsinsider/apple-bond-table.html"))
        assertEquals(false, quotes.any { it.maturityDate == "2026-08-04" })
    }

    @Test
    fun borrower_id_matches_the_issuer_name() {
        assertEquals(
            "20821",
            parseMarketsInsiderBorrowerId(fixture("marketsinsider/borrower-options.html"), "Apple Inc."),
        )
    }

    @Test
    fun unknown_issuer_name_is_empty() {
        assertNull(
            parseMarketsInsiderBorrowerId(fixture("marketsinsider/borrower-options.html"), "Not A Company"),
        )
    }

    @Test
    fun lookup_returns_empty_when_the_name_is_blank() {
        var client = MarketsInsiderYieldClient(http = fixtureHttp())
        assertNull(client.lookup("AAPL", "  "))
    }

    @Test
    fun lookup_selects_from_the_captured_finder_and_table() {
        var client = MarketsInsiderYieldClient(
            http = fixtureHttp(),
            clock = { LocalDate.parse("2026-08-16") },
        )
        assertEquals(471, client.lookup("AAPL", "Apple Inc.")?.yieldBps)
    }

    @Test
    fun live_aapl_instrument_yield_stays_in_policy_range() {
        assumeTrue("true" == System.getenv("DS_QUANT_LIVE"))
        var point = MarketsInsiderYieldClient().lookup("AAPL", "Apple Inc.")
        assertEquals(true, point != null && point.yieldBps in 200..1_500)
    }

    private fun fixture(path: String): String {
        var stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing fixture $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun fixtureHttp(): OkHttpClient {
        var finder = fixture("marketsinsider/borrower-options.html")
        var table = fixture("marketsinsider/apple-bond-table.html")
        var interceptor = Interceptor { chain ->
            var url = chain.request().url.toString()
            var body = if (url.contains("borrower=")) table else finder
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/html".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }
}
