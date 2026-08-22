package com.discountscreener.android.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The batch quote endpoint, `v7/finance/quote?symbols=A,B,C`, answers many symbols in one call.
 *
 * The fixture is a live capture of 2026-08-18 for `AAPL,BRK-B,BF.B,SATS`: two priced rows, one
 * empty shell for a symbol asked under its app spelling, and one live equity the endpoint does not
 * serve at all. Those are the three shapes a caller has to tell apart.
 */
class QuoteBatchTest {

    private val requestMap = mapOf("AAPL" to "AAPL", "BRK-B" to "BRK.B", "BF.B" to "BF.B", "SATS" to "SATS")

    /** Priced rows come back under the app's spelling; the shell and the unserved symbol do not come back. */
    @Test
    fun a_batch_answer_yields_the_priced_rows_under_their_app_symbols() {
        var entries = parseQuoteBatch(loadBatchFixture(), requestMap, nowEpochSeconds = NOW)

        assertEquals(setOf("AAPL", "BRK.B"), entries.keys)
    }

    @Test
    fun a_batch_row_carries_the_price_in_cents() {
        var entries = parseQuoteBatch(loadBatchFixture(), requestMap, nowEpochSeconds = NOW)

        assertEquals(50_296L, entries["BRK.B"]?.marketPriceCents)
    }

    /** Yahoo sends the last report and the next window; the next date ahead of now is the one kept. */
    @Test
    fun a_batch_row_carries_the_next_earnings_date_ahead_of_now() {
        var entries = parseQuoteBatch(loadBatchFixture(), requestMap, nowEpochSeconds = NOW)

        assertEquals(1_793_304_000L, entries["AAPL"]?.nextEarningsEpoch)
    }

    @Test
    fun a_batch_row_reads_profitability_from_trailing_eps() {
        var entries = parseQuoteBatch(loadBatchFixture(), requestMap, nowEpochSeconds = NOW)

        assertEquals(true, entries["AAPL"]?.profitable)
    }

    /** The 401 body Yahoo sends without a crumb is an error document, and must not read as "no rows". */
    @Test
    fun an_error_document_is_refused_rather_than_read_as_empty() {
        var body = """{"finance":{"result":null,"error":{"code":"Unauthorized","description":"User is unable to access this feature"}}}"""

        assertThrows(IllegalArgumentException::class.java) {
            parseQuoteBatch(Json.parseToJsonElement(body).jsonObject, requestMap, nowEpochSeconds = NOW)
        }
    }

    /**
     * A server that refuses a long list outright: the client halves the batch and asks again, so
     * every symbol is still priced and nothing is thrown at the caller.
     */
    @Test
    fun a_refused_batch_is_split_until_the_server_answers() = runTest {
        var symbols = (1..20).map { index -> "S$index" }
        var client = YahooFinanceClient(httpClient = batchServer(maxSymbolsPerCall = 12))

        var entries = client.fetchQuotes(symbols)

        assertEquals(symbols.toSet(), entries.keys)
    }

    /**
     * A server that answers `HTTP 400` to a list longer than [maxSymbolsPerCall] and prices every
     * symbol of a shorter one. Each priced row is the shape the live endpoint sends.
     */
    private fun batchServer(maxSymbolsPerCall: Int): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            var request = chain.request()
            var path = request.url.encodedPath
            when {
                path.contains("getcrumb") -> ok(request, "testcrumb")
                path.contains("/v7/finance/quote") -> {
                    var asked = request.url.queryParameter("symbols").orEmpty().split(',')
                    if (asked.size > maxSymbolsPerCall) {
                        answer(request, 400, """{"finance":{"result":null,"error":{"code":"Bad Request","description":"too many symbols"}}}""")
                    } else {
                        var rows = asked.joinToString(",") { symbol ->
                            """{"symbol":"$symbol","regularMarketPrice":100.5,"longName":"$symbol Holdings"}"""
                        }
                        ok(request, """{"quoteResponse":{"result":[$rows],"error":null}}""")
                    }
                }
                else -> ok(request, "<html></html>")
            }
        }
        .build()

    private fun ok(request: Request, body: String): Response = answer(request, 200, body)

    private fun answer(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Bad Request")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun loadBatchFixture(): JsonObject {
        var stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("yahoo/quote/batch.json")) {
            "missing fixture yahoo/quote/batch.json"
        }
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private companion object {
        /** 2026-08-18: after AAPL's last report (2026-07-30) and before its next window (2026-10-29). */
        const val NOW = 1_787_000_000L
    }
}
