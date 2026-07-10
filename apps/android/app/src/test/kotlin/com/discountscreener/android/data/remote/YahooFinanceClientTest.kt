package com.discountscreener.android.data.remote

import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.MarketSnapshot
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YahooFinanceClientTest {

    @Test
    fun parses_quote_page_into_market_snapshot_and_external_signal() {
        val body = """
            <!doctype html><html><head><meta property="og:title" content="Apple Inc. (AAPL) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":191.11},\"targetMeanPrice\":{\"raw\":225.50},\"targetMedianPrice\":{\"raw\":223.00},\"targetLowPrice\":{\"raw\":180.00},\"targetHighPrice\":{\"raw\":260.00},\"numberOfAnalystOpinions\":{\"raw\":42},\"recommendationMean\":{\"raw\":1.85}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":6.42}},\"recommendationTrend\":{\"trend\":[{\"period\":\"0m\",\"strongBuy\":20,\"buy\":10,\"hold\":8,\"sell\":3,\"strongSell\":1}]}}";
            </script></body></html>
        """.trimIndent()

        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuotePage("AAPL", body, null, diagnostics)

        assertEquals(
            MarketSnapshot(
                symbol = "AAPL",
                companyName = "Apple Inc.",
                profitable = true,
                marketPriceCents = 19_111,
                intrinsicValueCents = 22_550,
            ),
            parsed.snapshot,
        )
        assertEquals(
            ExternalValuationSignal(
                symbol = "AAPL",
                fairValueCents = 22_300,
                ageSeconds = 0,
                lowFairValueCents = 18_000,
                highFairValueCents = 26_000,
                analystOpinionCount = 42,
                recommendationMeanHundredths = 185,
                strongBuyCount = 20,
                buyCount = 10,
                holdCount = 8,
                sellCount = 3,
                strongSellCount = 1,
                weightedFairValueCents = null,
                weightedAnalystCount = null,
            ),
            parsed.externalSignal,
        )
        assertEquals(642L, parsed.fundamentals?.trailingEpsCents)
        assertEquals(emptyList<ProviderDiagnostic>(), diagnostics)
    }

    @Test
    fun parses_extended_fundamentals_from_quote_page() {
        val body = """
            <!doctype html><html><head><meta property="og:title" content="NVIDIA Corporation (NVDA) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":912.34},\"targetMeanPrice\":{\"raw\":1050.00},\"targetMedianPrice\":{\"raw\":1040.00},\"returnOnEquity\":{\"raw\":0.44},\"ebitda\":{\"raw\":145000000000},\"totalDebt\":{\"raw\":120000000000},\"totalCash\":{\"raw\":70000000000},\"debtToEquity\":{\"raw\":180.55},\"freeCashflow\":{\"raw\":99500000000},\"operatingCashflow\":{\"raw\":118000000000},\"earningsGrowth\":{\"raw\":0.153}},\"defaultKeyStatistics\":{\"sharesOutstanding\":{\"raw\":15550000000},\"trailingPE\":{\"raw\":31.27},\"forwardPE\":{\"raw\":28.10},\"priceToBook\":{\"raw\":42.65},\"enterpriseValue\":{\"raw\":3075000000000},\"enterpriseToEbitda\":{\"raw\":21.21},\"beta\":{\"raw\":1.24},\"trailingEps\":{\"raw\":12.34}},\"assetProfile\":{\"sectorKey\":\"technology\",\"sectorDisp\":\"Technology\",\"industryKey\":\"semiconductors\",\"industryDisp\":\"Semiconductors\"}}";
            </script></body></html>
        """.trimIndent()

        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuotePage("NVDA", body, null, diagnostics)
        val fundamentals = parsed.fundamentals

        requireNotNull(fundamentals)
        assertEquals("Technology", fundamentals.sectorName)
        assertEquals("Semiconductors", fundamentals.industryName)
        assertEquals(15_550_000_000L, fundamentals.sharesOutstanding)
        assertEquals(3_127, fundamentals.trailingPeHundredths)
        assertEquals(2_810, fundamentals.forwardPeHundredths)
        assertEquals(4_400, fundamentals.returnOnEquityBps)
        assertEquals(1_234L, fundamentals.trailingEpsCents)
        assertEquals(emptyList<ProviderDiagnostic>(), diagnostics)
    }

    @Test
    fun extracts_embedded_json_with_braces_inside_strings() {
        val body = """prefix \"financialData\":{\"firm\":\"A{B}\",\"currentPrice\":{\"raw\":191.11}} suffix"""

        assertEquals(
            """{\"firm\":\"A{B}\",\"currentPrice\":{\"raw\":191.11}}""",
            extractEmbeddedJsonObject(body, FINANCIAL_DATA_MARKER),
        )
    }

    @Test
    fun parses_loews_corporation_name_for_single_letter_symbol_l() {
        val body = """
            <!doctype html><html><head><meta property="og:title" content="Loews Corporation (L) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":115.20},\"targetMeanPrice\":{\"raw\":125.00},\"targetMedianPrice\":{\"raw\":124.00}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":8.12}}}";
            </script></body></html>
        """.trimIndent()

        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuotePage("L", body, null, diagnostics)

        assertEquals("Loews Corporation", parsed.companyName)
        assertEquals("Loews Corporation", parsed.snapshot?.companyName)
        assertEquals(emptyList<ProviderDiagnostic>(), diagnostics)
    }

    @Test
    fun parses_company_name_from_title_tag_when_og_title_is_missing() {
        val body = """
            <!doctype html><html><head><title>Loews Corporation (L) Stock Price, News, Quote &amp; History - Yahoo Finance</title></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":115.20},\"targetMeanPrice\":{\"raw\":125.00},\"targetMedianPrice\":{\"raw\":124.00}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":8.12}}}";
            </script></body></html>
        """.trimIndent()

        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuotePage("L", body, null, diagnostics)

        assertEquals("Loews Corporation", parsed.companyName)
        assertEquals(emptyList<ProviderDiagnostic>(), diagnostics)
    }

    @Test
    fun unescapes_html_entities_in_company_name_for_single_letter_symbol_t() {
        val body = """
            <!doctype html><html><head><meta property="og:title" content="AT&amp;T Inc. (T) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":28.15},\"targetMeanPrice\":{\"raw\":30.00},\"targetMedianPrice\":{\"raw\":29.50}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":2.12}}}";
            </script></body></html>
        """.trimIndent()

        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuotePage("T", body, null, diagnostics)

        assertEquals("AT&T Inc.", parsed.companyName)
        assertEquals(emptyList<ProviderDiagnostic>(), diagnostics)
    }

    @Test
    fun keeps_company_name_when_core_snapshot_is_missing() {
        val body = """
            <!doctype html><html><head><title>Loews Corporation (L) Stock Price, News, Quote &amp; History - Yahoo Finance</title></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":115.20}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":8.12}}}";
            </script></body></html>
        """.trimIndent()

        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuotePage("L", body, null, diagnostics)

        assertEquals("Loews Corporation", parsed.companyName)
        assertNull(parsed.snapshot)
        assertEquals("core", diagnostics.firstOrNull()?.component)
    }

    @Test
    fun returns_missing_snapshot_when_quote_page_is_incomplete() {
        val body = """
            <!doctype html><html><head></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":191.11}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":6.42}}}";
            </script></body></html>
        """.trimIndent()

        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuotePage("AAPL", body, null, diagnostics)

        assertNull(parsed.snapshot)
        assertEquals("core", diagnostics.firstOrNull()?.component)
    }

    @Test
    fun resolve_market_cap_prefers_reported_value() {
        assertEquals(
            5_000_000_000L,
            resolveMarketCapDollars(
                reportedMarketCap = 5_000_000_000.0,
                sharesOutstanding = 100_000_000.0,
                marketPriceDollars = 10.0,
            ),
        )
    }

    @Test
    fun resolve_market_cap_falls_back_to_price_times_shares() {
        assertEquals(
            1_200_000_000L,
            resolveMarketCapDollars(
                reportedMarketCap = null,
                sharesOutstanding = 100_000_000.0,
                marketPriceDollars = 12.0,
            ),
        )
    }

    @Test
    fun resolve_market_cap_returns_null_when_fallback_inputs_missing() {
        assertNull(
            resolveMarketCapDollars(
                reportedMarketCap = null,
                sharesOutstanding = 100_000_000.0,
                marketPriceDollars = null,
            ),
        )
    }

    @Test
    fun yahoo_request_symbol_maps_share_class_dot_to_hyphen() {
        assertEquals("BF-B", yahooRequestSymbol("BF.B"))
    }

    @Test
    fun yahoo_request_symbol_keeps_exchange_suffix() {
        assertEquals("YPFD.BA", yahooRequestSymbol("YPFD.BA"))
    }

    @Test
    fun usable_company_name_rejects_null_string() {
        assertEquals(false, isUsableCompanyName("null"))
    }

    @Test
    fun parse_quote_summary_fixture_aapl_has_core_snapshot_and_company_name() {
        val root = loadQuoteSummaryFixture("AAPL")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "AAPL", null, diagnostics)
        assertEquals("Apple Inc.", parsed.companyName)
        assertEquals("Apple Inc.", parsed.snapshot?.companyName)
        assertEquals(31_339L, parsed.snapshot?.marketPriceCents)
    }

    @Test
    fun parse_quote_summary_fixture_l_resolves_loews_name() {
        val root = loadQuoteSummaryFixture("L")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "L", null, diagnostics)
        assertEquals("Loews Corporation", parsed.companyName)
    }

    @Test
    fun parse_quote_summary_fixture_aapl_has_external_targets() {
        val root = loadQuoteSummaryFixture("AAPL")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "AAPL", null, diagnostics)
        assertEquals(31_500L, parsed.externalSignal?.fairValueCents)
    }

    @Test
    fun parse_quote_summary_fixture_aapl_has_fundamentals_sector() {
        val root = loadQuoteSummaryFixture("AAPL")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "AAPL", null, diagnostics)
        assertEquals("Technology", parsed.fundamentals?.sectorName)
    }

    @Test
    fun parse_quote_summary_fixture_t_unescapes_company_name() {
        val root = loadQuoteSummaryFixture("T")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "T", null, diagnostics)
        assertEquals("AT&T Inc.", parsed.companyName)
    }

    @Test
    fun parse_quote_summary_fixture_brk_b_hyphenated_symbol() {
        val root = loadQuoteSummaryFixture("BRK-B")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "BRK-B", null, diagnostics)
        assertEquals("Berkshire Hathaway Inc.", parsed.companyName)
    }

    @Test
    fun parses_loews_long_name_from_chart_meta_when_quote_html_is_unavailable() {
        // Live sample shape from query1 chart/L (HTML quote/L returns HTTP 404).
        val root = chartMetaJson(
            symbol = "L",
            longName = "Loews Corporation",
            shortName = "Loews Corporation",
            price = 115.2,
        )
        assertEquals("Loews Corporation", parseChartCompanyName(root, "L"))
    }

    @Test
    fun prefers_chart_long_name_over_short_name() {
        val root = chartMetaJson(
            symbol = "C",
            longName = "Citigroup Inc.",
            shortName = "Citigroup, Inc.",
            price = 80.1,
        )
        assertEquals("Citigroup Inc.", parseChartCompanyName(root, "C"))
    }

    @Test
    fun uses_chart_short_name_when_long_name_is_blank() {
        val root = chartMetaJson(
            symbol = "F",
            longName = "",
            shortName = "Ford Motor Company",
            price = 12.5,
        )
        assertEquals("Ford Motor Company", parseChartCompanyName(root, "F"))
    }

    @Test
    fun rejects_chart_company_name_equal_to_symbol() {
        val root = chartMetaJson(symbol = "V", longName = "V", shortName = "V", price = 300.0)
        assertNull(parseChartCompanyName(root, "V"))
    }

    @Test
    fun rejects_numeric_junk_chart_short_name() {
        val root = chartMetaJson(symbol = "N", longName = "", shortName = "2075626", price = 1.0)
        assertNull(parseChartCompanyName(root, "N"))
    }

    @Test
    fun merges_chart_company_name_when_quote_context_lacks_name() {
        val merged = mergeCompanyName(
            quoteCompanyName = null,
            chartCompanyName = "Apple Inc.",
        )
        assertEquals("Apple Inc.", merged)
    }

    @Test
    fun keeps_quote_company_name_over_chart_fallback() {
        val merged = mergeCompanyName(
            quoteCompanyName = "AT&T Inc.",
            chartCompanyName = "AT&T Inc",
        )
        assertEquals("AT&T Inc.", merged)
    }

    @Test
    fun interceptor_adds_accept_language_when_absent() {
        val request = Request.Builder()
            .url("https://query1.finance.yahoo.com/v8/finance/chart/AAPL")
            .header("User-Agent", "test-ua")
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        var captured: Request? = null
        val chain = TestChain(request) { captured = it }
        try {
            BROWSER_DEFAULT_HEADERS_INTERCEPTOR.intercept(chain)
        } catch (_: ExpectedStopException) {}
        assertEquals("en-US,en;q=0.9", captured!!.header("Accept-Language"))
        assertEquals("test-ua", captured!!.header("User-Agent"))
    }

    @Test
    fun interceptor_does_not_override_existing_accept_language() {
        val request = Request.Builder()
            .url("https://finance.yahoo.com/quote/AAPL")
            .header("User-Agent", "test-ua")
            .header("Accept", "text/html")
            .header("Accept-Language", "fr-FR")
            .build()
        var captured: Request? = null
        val chain = TestChain(request) { captured = it }
        try {
            BROWSER_DEFAULT_HEADERS_INTERCEPTOR.intercept(chain)
        } catch (_: ExpectedStopException) {}
        assertEquals("fr-FR", captured!!.header("Accept-Language"))
    }

    private fun chartMetaJson(
        symbol: String,
        longName: String,
        shortName: String,
        price: Double,
    ): kotlinx.serialization.json.JsonObject {
        val body = """
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"$symbol","longName":"$longName","shortName":"$shortName","regularMarketPrice":$price},"timestamp":[1],"indicators":{"quote":[{"close":[$price],"open":[$price],"high":[$price],"low":[$price],"volume":[1]}]}}],"error":null}}
        """.trimIndent()
        return kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
    }

    private fun loadQuoteSummaryFixture(symbol: String): kotlinx.serialization.json.JsonObject {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("yahoo/quoteSummary/$symbol.json"),
        ) { "missing fixture yahoo/quoteSummary/$symbol.json" }
        val body = stream.bufferedReader().use { it.readText() }
        return kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
    }

    private class ExpectedStopException : RuntimeException()

    private class TestChain(
        private val originalRequest: Request,
        private val onProceed: (Request) -> Unit,
    ) : okhttp3.Interceptor.Chain {
        override fun request() = originalRequest
        override fun proceed(request: Request): okhttp3.Response {
            onProceed(request)
            throw ExpectedStopException()
        }
        override fun connection() = throw UnsupportedOperationException()
        override fun readTimeoutMillis() = 0
        override fun writeTimeoutMillis() = 0
        override fun connectTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun call() = throw UnsupportedOperationException()
    }
}
