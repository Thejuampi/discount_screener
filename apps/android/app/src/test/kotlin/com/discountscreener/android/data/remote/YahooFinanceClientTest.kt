package com.discountscreener.android.data.remote

import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.MarketSnapshot
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
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
    fun cancelled_request_does_not_retry_sleep() = runTest {
        val quoteSummaryStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        val interceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            when {
                url.contains("getcrumb") ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("testcrumb".toResponseBody("text/plain".toMediaType()))
                        .build()
                url.contains("finance.yahoo.com/") && !url.contains("quoteSummary") ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                else -> {
                    quoteSummaryStarted.set(true)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("rate limited".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
        }
        val client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )
        val job = launch {
            try {
                client.fetchSymbol("AAPL")
            } catch (_: CancellationException) {
            }
        }
        while (!quoteSummaryStarted.get()) {
            kotlinx.coroutines.delay(10)
        }
        kotlinx.coroutines.delay(20)
        val startedAt = System.nanoTime()
        job.cancel()
        job.join()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(
            "cancel waited ${elapsedMs}ms on a Yahoo retry sleep",
            true,
            elapsedMs < 250,
        )
    }

    @Test
    fun socket_timeout_is_not_treated_as_cancel() = runTest {
        val interceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            when {
                url.contains("getcrumb") ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("testcrumb".toResponseBody("text/plain".toMediaType()))
                        .build()
                url.contains("finance.yahoo.com/") && !url.contains("quoteSummary") ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                else -> throw java.net.SocketTimeoutException("read timed out")
            }
        }
        val client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )
        val outcome = runCatching { client.fetchSymbol("AAPL") }
        assertEquals(false, outcome.exceptionOrNull() is CancellationException)
    }

    @Test
    fun cancelled_request_aborts_body_read() = runTest {
        var quoteSummaryStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        var interceptor = Interceptor { chain ->
            var url = chain.request().url.toString()
            when {
                url.contains("getcrumb") ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("testcrumb".toResponseBody("text/plain".toMediaType()))
                        .build()
                url.contains("finance.yahoo.com/") && !url.contains("quoteSummary") ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                else -> {
                    quoteSummaryStarted.set(true)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(SlowResponseBody())
                        .build()
                }
            }
        }
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )
        var job = launch {
            try {
                client.fetchSymbol("AAPL")
            } catch (_: CancellationException) {
            }
        }
        while (!quoteSummaryStarted.get()) {
            kotlinx.coroutines.delay(10)
        }
        kotlinx.coroutines.delay(20)
        var startedAt = System.nanoTime()
        job.cancel()
        job.join()
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(
            "cancel waited ${elapsedMs}ms on a Yahoo body read",
            true,
            elapsedMs < 250,
        )
    }

    @Test
    fun cancelled_crumb_aborts_body_read() = runTest {
        var crumbStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        var interceptor = Interceptor { chain ->
            var url = chain.request().url.toString()
            when {
                url.contains("getcrumb") -> {
                    crumbStarted.set(true)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(SlowResponseBody())
                        .build()
                }
                else ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html></html>".toResponseBody("text/html".toMediaType()))
                        .build()
            }
        }
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )
        var job = launch {
            try {
                client.fetchSymbol("AAPL")
            } catch (_: CancellationException) {
            }
        }
        while (!crumbStarted.get()) {
            kotlinx.coroutines.delay(10)
        }
        kotlinx.coroutines.delay(20)
        var startedAt = System.nanoTime()
        job.cancel()
        job.join()
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(
            "cancel waited ${elapsedMs}ms on a Yahoo crumb body read",
            true,
            elapsedMs < 250,
        )
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

    /**
     * The module has to be asked for. Everything below parses a fixture, which would keep passing
     * with the module dropped from the live request and the field empty on every real symbol.
     */
    @Test
    fun the_quote_summary_request_asks_for_the_earnings_calendar() {
        assertEquals(true, QUOTE_SUMMARY_MODULES.split(',').contains("calendarEvents"))
    }

    /**
     * Yahoo sends two dates for an unconfirmed window. The near edge is what a reader can act on,
     * so with both dates ahead the answer is the earlier one and not the later.
     */
    @Test
    fun with_two_dates_ahead_the_earnings_date_is_the_earlier_one() {
        val root = loadQuoteSummaryFixture("SUT-earnings")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "SUT", null, diagnostics, nowEpochSeconds = 1_750_000_000L)

        assertEquals(1_760_000_000L, parsed.snapshot?.nextEarningsEpoch)
    }

    /** With the first date behind us, taking the minimum of the whole list would report a report
     * that already happened. The filter is what this pins. */
    @Test
    fun a_date_that_already_passed_is_skipped_for_the_one_still_ahead() {
        val root = loadQuoteSummaryFixture("SUT-earnings")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "SUT", null, diagnostics, nowEpochSeconds = 1_770_000_000L)

        assertEquals(1_790_000_000L, parsed.snapshot?.nextEarningsEpoch)
    }

    /**
     * Parity with Windows `quote_summary.rs:264-284`, where the fallback to a past date is
     * deliberate. A stale date still says when the last report was; a null says nothing.
     */
    @Test
    fun every_date_in_the_past_falls_back_to_the_latest_one() {
        val root = loadQuoteSummaryFixture("SUT-earnings")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "SUT", null, diagnostics, nowEpochSeconds = 1_800_000_000L)

        assertEquals(1_790_000_000L, parsed.snapshot?.nextEarningsEpoch)
    }

    /** A fixture captured before the module was requested must not invent a date. */
    @Test
    fun a_payload_without_the_calendar_module_carries_no_earnings_date() {
        val root = loadQuoteSummaryFixture("AAPL")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "AAPL", null, diagnostics)

        assertNull(parsed.snapshot?.nextEarningsEpoch)
    }

    @Test
    fun cof_reads_reported_payout_from_summary_detail_for_residual_income() {
        assertEquals(true, QUOTE_SUMMARY_MODULES.split(',').contains("summaryDetail"))
        val root = loadQuoteSummaryFixture("COF-retention")
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val parsed = parseQuoteSummary(root, "COF", null, diagnostics)
        assertEquals(8_347, parsed.fundamentals?.retentionBps)
        assertEquals(16_786L, parsed.fundamentals?.bookValuePerShareCents)
        assertEquals(903, parsed.fundamentals?.returnOnEquityBps)
    }

    @Test
    fun financial_fixtures_resolve_summary_detail_payout_book_and_roe() {
        data class Case(
            val fixture: String,
            val symbol: String,
            val retentionBps: Int,
            val bookCents: Long,
            val roeBps: Int,
        )
        val cases = listOf(
            Case("JPM-retention", "JPM", 7_200, 11_540L, 1_620),
            Case("ACGL-retention", "ACGL", 7_000, 6_511L, 2_000),
            Case("COF-retention", "COF", 8_347, 16_786L, 903),
        )
        for (case in cases) {
            val root = loadQuoteSummaryFixture(case.fixture)
            val diagnostics = mutableListOf<ProviderDiagnostic>()
            val fund = parseQuoteSummary(root, case.symbol, null, diagnostics).fundamentals
            assertEquals("${case.symbol} retention", case.retentionBps, fund?.retentionBps)
            assertEquals("${case.symbol} book", case.bookCents, fund?.bookValuePerShareCents)
            assertEquals("${case.symbol} roe", case.roeBps, fund?.returnOnEquityBps)
        }
    }

    @Test
    fun payout_prefers_financial_data_over_summary_detail() {
        val financialData = buildJsonObject {
            put("payoutRatio", buildJsonObject {
                put("raw", JsonPrimitive(0.40))
            })
        }
        val summaryDetail = buildJsonObject {
            put("payoutRatio", buildJsonObject {
                put("raw", JsonPrimitive(0.1653))
            })
        }
        assertEquals(6_000, resolveRetentionBps(financialData, summaryDetail))
    }

    @Test
    fun payout_at_one_is_zero_retention() {
        var financialData = buildJsonObject {
            put("payoutRatio", buildJsonObject {
                put("raw", JsonPrimitive(1.0))
            })
        }
        assertEquals(0, resolveRetentionBps(financialData, JsonObject(emptyMap())))
    }

    @Test
    fun payout_above_one_is_zero_retention() {
        var financialData = buildJsonObject {
            put("payoutRatio", buildJsonObject {
                put("raw", JsonPrimitive(1.47))
            })
        }
        assertEquals(0, resolveRetentionBps(financialData, JsonObject(emptyMap())))
    }

    @Test
    fun quote_summary_404_recovers_from_the_quote_page() = runTest {
        var html = """
            <!doctype html><html><head><meta property="og:title" content="Bank of New York Mellon (BK) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":91.11},\"targetMeanPrice\":{\"raw\":100.00},\"targetMedianPrice\":{\"raw\":99.00},\"targetLowPrice\":{\"raw\":80.00},\"targetHighPrice\":{\"raw\":120.00},\"numberOfAnalystOpinions\":{\"raw\":12},\"recommendationMean\":{\"raw\":2.10}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":4.20}},\"recommendationTrend\":{\"trend\":[{\"period\":\"0m\",\"strongBuy\":4,\"buy\":5,\"hold\":2,\"sell\":1,\"strongSell\":0}]}}";
            </script></body></html>
        """.trimIndent()
        var interceptor = Interceptor { chain ->
            var url = chain.request().url.toString()
            var (code, body, type) = when {
                url.contains("getcrumb") -> Triple(200, "testcrumb", "text/plain")
                url.contains("quoteSummary") -> Triple(
                    404,
                    """{"quoteSummary":{"result":null,"error":{"code":"Not Found","description":"Quote not found for symbol: BK"}}}""",
                    "application/json",
                )
                url.contains("/quote/") -> Triple(200, html, "text/html")
                else -> Triple(200, "<html></html>", "text/html")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Not Found")
                .body(body.toResponseBody(type.toMediaType()))
                .build()
        }
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )
        assertEquals(9_111L, client.fetchSymbol("BK").snapshot?.marketPriceCents)
    }

    @Test
    fun quote_summary_server_error_does_not_scrape_the_quote_page() = runTest {
        var html = """
            <!doctype html><html><head><meta property="og:title" content="Bank of New York Mellon (BK) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
            window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":91.11},\"targetMeanPrice\":{\"raw\":100.00},\"targetMedianPrice\":{\"raw\":99.00},\"targetLowPrice\":{\"raw\":80.00},\"targetHighPrice\":{\"raw\":120.00},\"numberOfAnalystOpinions\":{\"raw\":12},\"recommendationMean\":{\"raw\":2.10}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":4.20}},\"recommendationTrend\":{\"trend\":[{\"period\":\"0m\",\"strongBuy\":4,\"buy\":5,\"hold\":2,\"sell\":1,\"strongSell\":0}]}}";
            </script></body></html>
        """.trimIndent()
        var interceptor = Interceptor { chain ->
            var url = chain.request().url.toString()
            var (code, body, type) = when {
                url.contains("getcrumb") -> Triple(200, "testcrumb", "text/plain")
                url.contains("quoteSummary") -> Triple(500, "upstream", "text/plain")
                url.contains("/quote/") -> Triple(200, html, "text/html")
                else -> Triple(404, "missing", "text/plain")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(body.toResponseBody(type.toMediaType()))
                .build()
        }
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )
        assertNull(client.fetchSymbol("BK").snapshot)
    }

    @Test
    fun missing_payout_in_both_modules_yields_null_retention() {
        val empty = JsonObject(emptyMap())
        assertNull(resolveRetentionBps(empty, empty))
        // Empty Yahoo payout object (no raw) is missing.
        val emptyPayout = buildJsonObject {
            put("payoutRatio", buildJsonObject {})
        }
        assertNull(resolveRetentionBps(emptyPayout, empty))
        val summaryOnly = buildJsonObject {
            put("payoutRatio", buildJsonObject {
                put("raw", JsonPrimitive(0.28))
            })
        }
        assertEquals(7_200, resolveRetentionBps(empty, summaryOnly))
    }

    /**
     * Yahoo writes `null` into a candle array for a bar with no trade, so this branch is common.
     *
     * The parse used to go through `doubleOrNull`, which screens the text with a Regex first. That
     * screen made a native ICU Matcher per call and a universe refresh made them faster than the GC
     * released them, so the process died of native memory. The parse is direct now, and a direct
     * parse reports a bad value by throwing. These two tests pin the two ways a bar can be bad.
     */
    @Test
    fun a_null_last_candle_falls_back_to_the_last_real_close() {
        assertEquals(19_950L, parseChartLatestCloseCents(chartClosesJson("199.50", "null")))
    }

    @Test
    fun a_non_numeric_candle_falls_back_to_the_last_real_close() {
        assertEquals(19_950L, parseChartLatestCloseCents(chartClosesJson("199.50", "\"n/a\"")))
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
    fun parse_search_fixture_returns_meli_for_mercado_query() {
        val root = loadSearchFixture("mercado")
        val quotes = parseSearchQuotes(root)

        assertEquals("MELI", quotes.first().symbol)
        assertEquals("MercadoLibre, Inc.", quotes.first().companyName)
        assertEquals("NASDAQ", quotes.first().exchange)
    }

    @Test
    fun timeseries_request_sends_the_yahoo_crumb() = runTest {
        var seen = mutableListOf<String>()
        var http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                var req = chain.request()
                seen += req.url.toString()
                var payload = when {
                    req.url.encodedPath.contains("getcrumb") -> "testcrumb"
                    req.url.encodedPath.contains("timeseries") ->
                        """{"timeseries":{"result":[]}}"""
                    else -> "<html></html>"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        YahooFinanceClient(httpClient = http).fetchFundamentalTimeseries("AAPL")
        var timeseriesUrl = seen.first { it.contains("timeseries") }
        assertEquals(true, timeseriesUrl.contains("crumb=testcrumb"))
    }

    @Test
    fun timeseries_request_asks_for_later_interest_types() = runTest {
        var seen = mutableListOf<String>()
        var http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                var req = chain.request()
                seen += req.url.toString()
                var payload = when {
                    req.url.encodedPath.contains("getcrumb") -> "testcrumb"
                    req.url.encodedPath.contains("timeseries") ->
                        """{"timeseries":{"result":[]}}"""
                    else -> "<html></html>"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        YahooFinanceClient(httpClient = http).fetchFundamentalTimeseries("AAPL")
        var timeseriesUrl = seen.first { it.contains("timeseries") }
        assertEquals(true, timeseriesUrl.contains("annualInterestExpenseNonOperating"))
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
    ): JsonObject {
        val body = """
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"$symbol","longName":"$longName","shortName":"$shortName","regularMarketPrice":$price},"timestamp":[1],"indicators":{"quote":[{"close":[$price],"open":[$price],"high":[$price],"low":[$price],"volume":[1]}]}}],"error":null}}
        """.trimIndent()
        return Json.parseToJsonElement(body).jsonObject
    }

    /** A chart whose close array holds [closes] verbatim, so a test can put a raw JSON token in it. */
    private fun chartClosesJson(vararg closes: String): JsonObject {
        val body = """
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"X"},"indicators":{"quote":[{"close":[${closes.joinToString(",")}]}]}}],"error":null}}
        """.trimIndent()
        return Json.parseToJsonElement(body).jsonObject
    }

    private fun loadQuoteSummaryFixture(symbol: String): JsonObject {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("yahoo/quoteSummary/$symbol.json"),
        ) { "missing fixture yahoo/quoteSummary/$symbol.json" }
        val body = stream.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(body).jsonObject
    }

    private fun loadSearchFixture(query: String): JsonObject {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("yahoo/search/$query.json"),
        ) { "missing fixture yahoo/search/$query.json" }
        val body = stream.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(body).jsonObject
    }

    private class ExpectedStopException : RuntimeException()

    private class TestChain(
        private val originalRequest: Request,
        private val onProceed: (Request) -> Unit,
    ) : Interceptor.Chain {
        override fun request() = originalRequest
        override fun proceed(request: Request): Response {
            onProceed(request)
            throw ExpectedStopException()
        }
        override fun connection() = throw UnsupportedOperationException()
        override fun readTimeoutMillis() = 0
        override fun writeTimeoutMillis() = 0
        override fun connectTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun call() = throw UnsupportedOperationException()
    }

    private class SlowResponseBody : okhttp3.ResponseBody() {
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

    @Test
    fun fetch_symbol_reads_country_from_quote_summary() = runTest {
        var body = javaClass.classLoader!!.getResource("yahoo/quoteSummary/AAPL.json")!!.readText()
        var interceptor = Interceptor { chain ->
            var url = chain.request().url.toString()
            var (code, payload, type) = when {
                url.contains("getcrumb") -> Triple(200, "testcrumb", "text/plain")
                url.contains("quoteSummary") -> Triple(200, body, "application/json")
                else -> Triple(200, "<html></html>", "text/html")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(payload.toResponseBody(type.toMediaType()))
                .build()
        }
        var client = YahooFinanceClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )

        assertEquals("United States", client.fetchSymbol("AAPL").fundamentals?.country)
    }
}
