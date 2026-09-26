package com.discountscreener.android.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Paired upstream captures. Each pair contains identical consumed values before normalization. */
@RunWith(Parameterized::class)
class YahooCompactPayloadParityTest(private val symbol: String) {
    @Test
    fun compact_summary_preserves_every_consumed_value_and_missing_reason() {
        val baselineIssues = mutableListOf<ProviderDiagnostic>()
        val compactIssues = mutableListOf<ProviderDiagnostic>()
        val baseline = parseQuoteSummary(
            compactFixture("summary-original-$symbol"), symbol, null, baselineIssues, NOW,
        )
        val compact = parseQuoteSummary(
            compactFixture("summary-slim-$symbol"), symbol, null, compactIssues, NOW,
        )

        assertEquals(baseline, compact)
        assertEquals(baselineIssues, compactIssues)
    }

    @Test
    fun selected_quote_fields_preserve_every_consumed_value() {
        val mapping = mapOf(symbol to symbol.replace('-', '.'))
        val baseline = parseQuoteBatch(compactFixture("quotes-default"), mapping, NOW)
        val compact = parseQuoteBatch(compactFixture("quotes-fields"), mapping, NOW)

        assertEquals(1, baseline.size)
        assertEquals(baseline, compact)
    }

    companion object {
        private const val NOW = 1_790_294_400L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun symbols(): List<String> = listOf("AAPL", "MSFT", "JPM", "BRK-B", "TSM", "SPY")
    }
}

class YahooCompactPayloadRequestTest {
    @Test
    fun dashboard_requests_only_its_modules_without_display_formats() = runTest {
        val url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/AAPL?" +
            "modules=price%2CfinancialData%2CsummaryDetail%2CdefaultKeyStatistics%2CsummaryProfile%2C" +
            "recommendationTrend%2CcalendarEvents&formatted=false&crumb=testcrumb"
        val client = compactClient(url, compactFixture("summary-slim-AAPL").toString())

        val result = client.fetchSymbol("AAPL")

        assertNotNull(result.snapshot)
        assertEquals("technology", result.fundamentals?.sectorKey)
    }

    @Test
    fun consensus_requests_only_earnings_trend() = runTest {
        val url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/AAPL?" +
            "modules=earningsTrend&formatted=true&crumb=testcrumb"
        val client = compactClient(url, compactFixture("summary-original-AAPL").toString())

        assertNotNull(client.fetchConsensus("AAPL"))
    }

    @Test
    fun batch_quotes_request_only_consumed_fields() = runTest {
        val url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=AAPL%2CBRK-B&" +
            "fields=symbol%2ClongName%2CshortName%2CregularMarketPrice%2CepsTrailingTwelveMonths%2C" +
            "earningsTimestamp%2CearningsTimestampStart%2CearningsTimestampEnd&formatted=false&crumb=testcrumb"
        val client = compactClient(url, compactFixture("quotes-fields").toString())

        assertEquals(setOf("AAPL", "BRK.B"), client.fetchQuotes(listOf("AAPL", "BRK.B")).keys)
    }
}

private fun compactFixture(name: String): JsonObject = Json.parseToJsonElement(
    requireNotNull(YahooCompactPayloadRequestTest::class.java.getResource("/yahoo/payload-2026-09-25/$name.json"))
        .readText(),
).jsonObject

/** Exact routes keep the test offline and make unexpected fallback calls fail. */
private fun compactClient(expectedUrl: String, body: String): YahooFinanceClient = YahooFinanceClient(
    httpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val answer = when (val url = request.url.toString()) {
            "https://finance.yahoo.com/" -> "<html></html>"
            "https://query2.finance.yahoo.com/v1/test/getcrumb" -> "testcrumb"
            expectedUrl -> body
            else -> throw AssertionError("Unexpected Yahoo request: $url")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(answer.toResponseBody()).build()
    }.build(),
)
