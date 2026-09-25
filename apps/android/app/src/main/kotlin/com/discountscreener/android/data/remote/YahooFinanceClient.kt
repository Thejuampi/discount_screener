package com.discountscreener.android.data.remote

import com.discountscreener.android.domain.logging.AppLogger
import com.discountscreener.android.domain.logging.NoOpAppLogger
import com.discountscreener.core.earnings.CURRENT_QUARTER
import com.discountscreener.core.earnings.ConsensusEstimate
import com.discountscreener.core.earnings.OptionChainSnapshot
import com.discountscreener.core.earnings.consensusOf
import com.discountscreener.core.earnings.isOptionChainAnswer
import com.discountscreener.core.earnings.ReportedQuarter
import com.discountscreener.core.earnings.reportedQuartersOf
import com.discountscreener.core.earnings.parseOptionChain
import com.discountscreener.core.engine.YahooInterestSeries
import com.discountscreener.core.engine.sanitizeExternalSignal
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.roundToLong

enum class ProviderComponentState {
    Fresh,
    Missing,
    Error,
}

data class ProviderCoverage(
    val core: ProviderComponentState,
    val external: ProviderComponentState,
    val fundamentals: ProviderComponentState,
)

data class ProviderDiagnostic(
    val component: String,
    val kind: String,
    val detail: String,
    val retryable: Boolean,
)

data class ProviderFetchResult(
    val symbol: String,
    val snapshot: MarketSnapshot?,
    val externalSignal: ExternalValuationSignal?,
    val fundamentals: FundamentalSnapshot?,
    val companyName: String? = null,
    val coverage: ProviderCoverage,
    val diagnostics: List<ProviderDiagnostic>,
)

data class YahooSearchQuote(
    val symbol: String,
    val companyName: String,
    val exchange: String?,
    val quoteType: String,
)

/**
 * One row of Yahoo's batch quote endpoint: a symbol's price and the little that travels with it.
 *
 * The endpoint carries no analyst target, no recommendation counts and no sector, so an entry can
 * refresh the price of a row the app already knows and cannot create a row on its own; that is
 * still `quoteSummary`'s job.
 */
data class QuoteBatchEntry(
    val symbol: String,
    val companyName: String?,
    val marketPriceCents: Long,
    /** Trailing twelve-month EPS above zero, the same test `quoteSummary` applies; null when absent. */
    val profitable: Boolean?,
    val nextEarningsEpoch: Long?,
)

internal data class QuoteContext(
    val snapshot: MarketSnapshot? = null,
    val externalSignal: ExternalValuationSignal? = null,
    val fundamentals: FundamentalSnapshot? = null,
    val companyName: String? = null,
)

internal data class ChartMetaProbe(
    val marketPriceCents: Long?,
    val companyName: String?,
)

private data class RecommendationPeriod(
    val period: String,
    val strongBuy: Int,
    val buy: Int,
    val hold: Int,
    val sell: Int,
    val strongSell: Int,
) {
    fun totalCount(): Int = strongBuy + buy + hold + sell + strongSell
}

private const val QUOTE_PAGE_URL = "https://finance.yahoo.com/quote/"
private const val CHART_API_URL = "https://query1.finance.yahoo.com/v8/finance/chart/"
private const val QUOTE_SUMMARY_URL = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/"
private const val QUOTE_BATCH_URL = "https://query1.finance.yahoo.com/v7/finance/quote"
private const val FUNDAMENTALS_TIMESERIES_URL =
    "https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/"
private const val SEARCH_API_URL = "https://query2.finance.yahoo.com/v1/finance/search"
private const val OPTIONS_API_URL = "https://query1.finance.yahoo.com/v7/finance/options/"
private const val LOG_TAG = "DiscountScreener"

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
private const val QUOTE_PAGE_ACCEPT =
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
private const val QUOTE_PAGE_ACCEPT_LANGUAGE = "en-US,en;q=0.9"
private const val QUOTE_PAGE_UPGRADE_INSECURE_REQUESTS = "1"
/**
 * Yahoo quoteSummary modules for list + financial RI drivers.
 *
 * Financial residual-income field precedence (parity with Windows `quote_summary.rs`):
 * - payout → retention: `financialData.payoutRatio` then `summaryDetail.payoutRatio`
 *   (finite `[0,1]` only; empty `{}` is missing)
 * - book / BVPS: `defaultKeyStatistics.bookValue` | `bookValuePerShare`, else price / P/B
 * - ROE: `financialData.returnOnEquity` only (reported; no synthesis)
 */
internal const val QUOTE_SUMMARY_MODULES =
    "price,financialData,summaryDetail,defaultKeyStatistics,assetProfile,recommendationTrend," +
        "calendarEvents,earningsTrend"

internal const val REPORTED_QUARTER_MODULES = "earningsHistory,incomeStatementHistoryQuarterly"

internal const val CALENDAR_MODULES = "calendarEvents"

/**
 * Symbols a batch quote call asks for at once, and the size below which a failing batch is not
 * split further.
 *
 * Measured with the live endpoint on 2026-08-18: it answered the whole Russell list, 1 937 symbols
 * in one 9 KB URL, 4.6 MB, in 0.7 s; a hundred symbols is 240 KB. Two hundred and fifty keeps a
 * call at ~600 KB, so the first rows land in the first second and a phone parses one answer while
 * the next is on the wire. Yahoo has no published ceiling; if it rejects a batch outright the
 * client halves it and asks again, down to [QUOTE_BATCH_MIN_SIZE], below which the symbols are left
 * for the per-symbol path.
 */
internal const val QUOTE_BATCH_SIZE = 250
internal const val QUOTE_BATCH_MIN_SIZE = 8

private const val QUOTE_HTML_COMPONENT = "quoteHtml"
internal const val QUOTE_SUMMARY_COMPONENT = "quoteSummary"
private const val CHART_COMPONENT = "chart"
private const val CORE_COMPONENT = "core"
private const val EXTERNAL_COMPONENT = "external"
private const val FUNDAMENTALS_COMPONENT = "fundamentals"
/** The one code that means "the provider, not this call, wants you to stop". */
private const val RATE_LIMITED_CODE = 429
private const val FIRST_SERVER_ERROR_CODE = 500

private const val MISSING_KIND = "missing"
private const val ERROR_KIND = "error"

internal const val FINANCIAL_DATA_MARKER = "\\\"financialData\\\":"
internal const val SUMMARY_DETAIL_MARKER = "\\\"summaryDetail\\\":"
internal const val DEFAULT_KEY_STATISTICS_MARKER = "\\\"defaultKeyStatistics\\\":"
internal const val PRICE_MARKER = "\\\"price\\\":"
internal const val ASSET_PROFILE_MARKER = "\\\"assetProfile\\\":"
internal const val RECOMMENDATION_TREND_MARKER = "\\\"recommendationTrend\\\":"
private const val META_TITLE_MARKER = "<meta property=\"og:title\" content=\""
private const val TITLE_MARKER = "<title>"
private const val LONG_NAME_MARKER = "\\\"longName\\\":"

internal val BROWSER_DEFAULT_HEADERS_INTERCEPTOR = Interceptor { chain ->
    var request = chain.request()
    val builder = request.newBuilder()
    if (request.header("Accept-Language") == null) {
        builder.header("Accept-Language", "en-US,en;q=0.9")
    }
    if (request.header("User-Agent") == null) {
        builder.header("User-Agent", USER_AGENT)
    }
    request = builder.build()
    chain.proceed(request)
}

open class YahooFinanceClient(
    private val httpClient: OkHttpClient = providerHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** When true, fall back to multi-MB HTML scrape if quoteSummary fails. Off by default. */
    private val htmlFallback: Boolean = false,
    private val logger: AppLogger = NoOpAppLogger,
) {
    private val session = YahooSession(httpClient = httpClient, userAgent = USER_AGENT)

    /**
     * Everything this client asks of Yahoo passes here, one permit per request.
     *
     * It is built rather than injected because [RequestGovernor] is internal and this class is
     * public. One governor per client instance is also the right unit: the limit belongs to Yahoo,
     * and every call in the app goes through one client.
     */
    private val governor = RequestGovernor(
        onPushBack = { retryAfterMillis, windowSize, cooldownMillis ->
            logger.info(
                LOG_TAG,
                "yahoo push-back retryAfterMillis=$retryAfterMillis window=$windowSize cooldownMillis=$cooldownMillis",
            )
        },
    )

    /** How many symbols a caller may fan out before the governor is the only thing holding back. */
    val requestCeiling: Int get() = governor.ceiling

    open suspend fun fetchSymbol(symbol: String): ProviderFetchResult = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val requestSymbol = yahooRequestSymbol(symbol)

        var chartProbe: ChartMetaProbe? = null
        suspend fun loadChartProbe(): ChartMetaProbe {
            chartProbe?.let { return it }
            val probe = try {
                fetchChartMetaProbe(requestSymbol, displaySymbol = symbol)
            } catch (error: IOException) {
                diagnostics += ProviderDiagnostic(
                    component = CHART_COMPONENT,
                    kind = ERROR_KIND,
                    detail = error.message ?: "chart probe failed",
                    retryable = isRetryable(error),
                )
                ChartMetaProbe(marketPriceCents = null, companyName = null)
            }
            chartProbe = probe
            return probe
        }

        currentCoroutineContext().ensureActive()
        var quoteSummaryNotFound = false
        var quoteContext = try {
            val root = fetchQuoteSummaryJson(requestSymbol)
            parseQuoteSummary(
                root = root,
                symbol = symbol,
                chartMarketPriceCents = null,
                diagnostics = diagnostics,
            )
        } catch (error: IOException) {
            quoteSummaryNotFound = isNotFound(error)
            diagnostics += ProviderDiagnostic(
                component = QUOTE_SUMMARY_COMPONENT,
                kind = ERROR_KIND,
                detail = error.message ?: "quoteSummary request failed",
                retryable = isRetryable(error) || isAuthError(error),
            )
            null
        }

        if (quoteContext == null && (htmlFallback || quoteSummaryNotFound)) {
            quoteContext = try {
                val body = fetchQuotePage(requestSymbol)
                val quotePrice = parseEmbeddedJsonObject(body, FINANCIAL_DATA_MARKER, diagnostics)
                    ?.rawMoney("currentPrice")
                parseQuotePage(
                    symbol = symbol,
                    body = body,
                    chartMarketPriceCents = quotePrice,
                    diagnostics = diagnostics,
                )
            } catch (error: IOException) {
                diagnostics += ProviderDiagnostic(
                    component = QUOTE_HTML_COMPONENT,
                    kind = ERROR_KIND,
                    detail = error.message ?: "quote page request failed",
                    retryable = isRetryable(error),
                )
                null
            }
        }

        var context = quoteContext ?: QuoteContext()

        // Fill name (and price on existing snapshots) from chart meta when needed.
        if (context.companyName.isNullOrBlank() || context.snapshot == null) {
            val probe = loadChartProbe()
            val companyName = mergeCompanyName(context.companyName, probe.companyName)
            val snapshot = context.snapshot?.let { snap ->
                var updated = snap
                if (updated.companyName.isNullOrBlank() && !companyName.isNullOrBlank()) {
                    updated = updated.copy(companyName = companyName)
                }
                if (probe.marketPriceCents != null && updated.marketPriceCents <= 0L) {
                    updated = updated.copy(marketPriceCents = probe.marketPriceCents)
                }
                updated
            }
            context = context.copy(snapshot = snapshot, companyName = companyName)
        }

        // Recovery: suppress transient rate-limit noise when we still recovered usable name data.
        val filteredDiagnostics = if (!context.companyName.isNullOrBlank() || context.snapshot != null) {
            diagnostics.filterNot { diagnostic ->
                diagnostic.retryable && isRateLimitDetail(diagnostic.detail)
            }
        } else {
            diagnostics
        }

        ProviderFetchResult(
            symbol = symbol,
            snapshot = context.snapshot,
            externalSignal = context.externalSignal,
            fundamentals = context.fundamentals,
            companyName = context.companyName,
            coverage = ProviderCoverage(
                core = componentState(context.snapshot != null, filteredDiagnostics, CORE_COMPONENT),
                external = componentState(context.externalSignal != null, filteredDiagnostics, EXTERNAL_COMPONENT),
                fundamentals = componentState(context.fundamentals != null, filteredDiagnostics, FUNDAMENTALS_COMPONENT),
            ),
            diagnostics = filteredDiagnostics,
        )
    }

    /**
     * The current price of many symbols in a few calls, from Yahoo's batch quote endpoint.
     *
     * Where the per-symbol `quoteSummary` costs one call a symbol, this costs one call per
     * [QUOTE_BATCH_SIZE] symbols. What comes back is only what the endpoint carries (see
     * [QuoteBatchEntry]); a symbol the endpoint does not answer, or answers without a price, is
     * simply absent from the result and is the per-symbol path's to fetch. Measured on 2026-08-18,
     * about three in a hundred live equities are absent that way (SATS, GTLS among them), and a
     * symbol asked for under its app spelling (BF.B) comes back as an empty shell, which is why
     * every symbol goes out under [yahooRequestSymbol] and is mapped back on return.
     *
     * A batch Yahoo refuses outright, a client error or a body it cannot parse, is split in two
     * and each half asked for again, so one symbol the endpoint chokes on costs its own neighbours
     * and no one else. Push-back and server errors are the governor's, as for every other call:
     * a batch that is still refused after the governor's attempts is dropped and logged, never
     * thrown, so a closed Yahoo costs the caller a slower load rather than a failed one.
     */
    open suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> = withContext(Dispatchers.IO) {
        if (symbols.isEmpty()) return@withContext emptyMap()
        val batches = symbols.distinct().chunked(QUOTE_BATCH_SIZE)
        coroutineScope {
            batches
                .map { batch -> async { fetchQuoteBatchSplitting(batch) } }
                .awaitAll()
                .fold(HashMap<String, QuoteBatchEntry>()) { all, part -> all.apply { putAll(part) } }
        }
    }

    private suspend fun fetchQuoteBatchSplitting(symbols: List<String>): Map<String, QuoteBatchEntry> {
        val refusal = try {
            return fetchQuoteBatch(symbols)
        } catch (error: IOException) {
            if (isRetryable(error)) {
                logger.info(LOG_TAG, "yahoo batch quote dropped ${symbols.size} symbols: ${error.message}")
                return emptyMap()
            }
            error
        } catch (error: IllegalArgumentException) {
            error
        }
        if (symbols.size < QUOTE_BATCH_MIN_SIZE * 2) {
            logger.info(LOG_TAG, "yahoo batch quote dropped ${symbols.size} symbols: ${refusal.message}")
            return emptyMap()
        }
        val half = symbols.size / 2
        return fetchQuoteBatchSplitting(symbols.subList(0, half)) +
            fetchQuoteBatchSplitting(symbols.subList(half, symbols.size))
    }

    private suspend fun fetchQuoteBatch(symbols: List<String>): Map<String, QuoteBatchEntry> {
        val appSymbolByRequestSymbol = symbols.associateBy(::yahooRequestSymbol)
        suspend fun once(): JsonObject {
            val crumb = session.ensureCrumb()
            val url = QUOTE_BATCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("symbols", appSymbolByRequestSymbol.keys.joinToString(","))
                .addQueryParameter("crumb", crumb)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", QUOTE_PAGE_ACCEPT_LANGUAGE)
                .build()
            val body = executeText(request)
            return json.parseToJsonElement(body).jsonObject
        }
        val root = try {
            once()
        } catch (error: IOException) {
            if (!isAuthError(error)) throw error
            session.clear()
            once()
        }
        return parseQuoteBatch(root, appSymbolByRequestSymbol)
    }

    private suspend fun fetchQuoteSummaryJson(
        requestSymbol: String,
        modules: String = QUOTE_SUMMARY_MODULES,
    ): JsonObject {
        suspend fun once(): JsonObject {
            val crumb = session.ensureCrumb()
            val url = QUOTE_SUMMARY_URL.toHttpUrl().newBuilder()
                .addPathSegment(requestSymbol)
                .addQueryParameter("modules", modules)
                .addQueryParameter("crumb", crumb)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", QUOTE_PAGE_ACCEPT_LANGUAGE)
                .build()
            val body = executeText(request)
            return json.parseToJsonElement(body).jsonObject
        }

        return try {
            once()
        } catch (error: IOException) {
            if (!isAuthError(error)) throw error
            session.clear()
            once()
        }
    }

    open suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
        val (rangeToken, interval) = chartRangeSpec(range)
        return fetchCandles(symbol, rangeToken, interval)
    }

    open suspend fun fetchReplayBackingCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
        val (rangeToken, interval) = replayBackingRangeSpec(range)
        return fetchCandles(symbol, rangeToken, interval)
    }

    /**
     * Candles over a raw Yahoo range and interval, for callers whose pairing has no [ChartRange].
     *
     * The market read needs `1y`/`1d` and `3mo`/`1d`; [ChartRange] offers neither — its `Year` is
     * `1y`/`1wk`, fifty-two *weekly* bars. Adding members for the missing pairings is not an option:
     * `ChartRange` is a published contract (`shared/contracts/chart-ranges.json`), it is serialized
     * into persisted state, and it drives the chip row the user taps. So the enum keeps describing
     * the chart UI's ranges and this takes the tokens directly.
     *
     * Index symbols work unchanged. `addPathSegment` percent-encodes the caret in `^VIX` to `%5E`,
     * which the endpoint accepts and echoes back as `^VIX` — checked against the live endpoint
     * rather than assumed, since a silent 404 here would read as "VIX unavailable" forever.
     */
    open suspend fun fetchCandles(
        symbol: String,
        rangeToken: String,
        interval: String,
    ): List<HistoricalCandle> = withContext(Dispatchers.IO) {
        val requestSymbol = yahooRequestSymbol(symbol)
        val url = CHART_API_URL.toHttpUrl().newBuilder()
            .addPathSegment(requestSymbol)
            .addQueryParameter("range", rangeToken)
            .addQueryParameter("interval", interval)
            .addQueryParameter("includePrePost", "false")
            .build()
        val root = getJson(url.toString())
        val result = root.child("chart").childArray("result").firstOrNull()?.jsonObject ?: return@withContext emptyList()
        val timestamps = result["timestamp"]?.jsonArray ?: return@withContext emptyList()
        val quote = result.child("indicators").childArray("quote").firstOrNull()?.jsonObject ?: return@withContext emptyList()
        val opens = quote["open"]?.jsonArray ?: JsonArray(emptyList())
        val highs = quote["high"]?.jsonArray ?: JsonArray(emptyList())
        val lows = quote["low"]?.jsonArray ?: JsonArray(emptyList())
        val closes = quote["close"]?.jsonArray ?: JsonArray(emptyList())
        val volumes = quote["volume"]?.jsonArray ?: JsonArray(emptyList())

        timestamps.indices.mapNotNull { index ->
            val close = closes.getOrNull(index)?.jsonPrimitive?.plainDoubleOrNull() ?: return@mapNotNull null
            val open = opens.getOrNull(index)?.jsonPrimitive?.plainDoubleOrNull() ?: close
            val high = highs.getOrNull(index)?.jsonPrimitive?.plainDoubleOrNull() ?: close
            val low = lows.getOrNull(index)?.jsonPrimitive?.plainDoubleOrNull() ?: close
            val volume = volumes.getOrNull(index)?.jsonPrimitive?.longOrNull ?: 0L
            val timestamp = timestamps[index].jsonPrimitive.longOrNull ?: return@mapNotNull null
            HistoricalCandle(
                epochSeconds = timestamp,
                openCents = dollarsToCents(open) ?: return@mapNotNull null,
                highCents = dollarsToCents(high) ?: return@mapNotNull null,
                lowCents = dollarsToCents(low) ?: return@mapNotNull null,
                closeCents = dollarsToCents(close) ?: return@mapNotNull null,
                volume = volume,
            )
        }
    }

    open suspend fun searchSymbols(query: String, limit: Int = 8): List<YahooSearchQuote> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@withContext emptyList()
        val url = SEARCH_API_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", trimmedQuery)
            .addQueryParameter("quotesCount", limit.coerceAtLeast(1).toString())
            .addQueryParameter("newsCount", "0")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", QUOTE_PAGE_ACCEPT_LANGUAGE)
            .build()
        val body = executeText(request)
        parseSearchQuotes(json.parseToJsonElement(body).jsonObject)
    }

    /**
     * The option chain of one expiry, or null when Yahoo has none for the symbol.
     *
     * Two calls are needed for one event and that is the provider's shape, not a choice here: the
     * first answer lists every expiry the ticker has, the second returns the ladder of the one that
     * covers the report. Callers that already know the expiry pass [expiryEpochSeconds] and pay for
     * one call.
     *
     * Nothing is interpreted here. The body goes straight to `parseOptionChain`, which is the same
     * function the fixture tests run, so a saved answer and a live one cannot drift apart.
     */
    open suspend fun fetchOptionChain(
        symbol: String,
        expiryEpochSeconds: Long? = null,
    ): OptionChainSnapshot? = withContext(Dispatchers.IO) {
        suspend fun once(): OptionChainSnapshot? {
            val crumb = session.ensureCrumb()
            val url = (OPTIONS_API_URL + yahooRequestSymbol(symbol)).toHttpUrl().newBuilder()
                .apply { expiryEpochSeconds?.let { addQueryParameter("date", it.toString()) } }
                .addQueryParameter("crumb", crumb)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", QUOTE_PAGE_ACCEPT_LANGUAGE)
                .build()
            var body = executeText(request)
            return parseOptionChain(body)
                ?: if (isOptionChainAnswer(body)) null
                else throw OptionChainRefused("Yahoo options answered no chain: ${body.take(160)}")
        }

        try {
            once()
        } catch (error: IOException) {
            if (!isAuthError(error) && error !is OptionChainRefused) throw error
            session.clear()
            once()
        }
    }

    private class OptionChainRefused(message: String) : IOException(message)

    open suspend fun fetchConsensus(
        symbol: String,
        period: String = CURRENT_QUARTER,
    ): ConsensusEstimate? = withContext(Dispatchers.IO) {
        consensusOf(fetchQuoteSummaryJson(yahooRequestSymbol(symbol)), period)
    }

    /**
     * The quarters the company has already reported: actual EPS, the estimate it was measured
     * against, and the revenue of the same quarter.
     *
     * Its own two modules rather than the shared summary, because only a settling event needs
     * them. Adding them to [QUOTE_SUMMARY_MODULES] would make every symbol of every refresh carry
     * a quarterly income statement it never reads.
     */
    open suspend fun fetchReportedQuarters(symbol: String): List<ReportedQuarter> =
        withContext(Dispatchers.IO) {
            reportedQuartersOf(fetchQuoteSummaryJson(yahooRequestSymbol(symbol), REPORTED_QUARTER_MODULES))
        }

    /**
     * When this symbol reports next, asked on its own.
     *
     * The date on file comes from the last refresh, and a refresh needs the app open. The
     * background capture has to learn on its own that a report moved, or that the one it knew has
     * already happened, or it prices nothing.
     */
    open suspend fun fetchNextEarningsEpoch(symbol: String, nowEpochSeconds: Long): Long? =
        withContext(Dispatchers.IO) {
            var root = fetchQuoteSummaryJson(yahooRequestSymbol(symbol), CALENDAR_MODULES)
            var calendar = root["quoteSummary"]?.jsonObject
                ?.get("result")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("calendarEvents")?.jsonObject
            nextEarningsEpochOf(calendar, nowEpochSeconds)
        }

    open suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries = withContext(Dispatchers.IO) {
        val requestSymbol = yahooRequestSymbol(symbol)
        val types = listOf(
            "annualFreeCashFlow",
            "annualOperatingCashFlow",
            "annualCapitalExpenditure",
            "annualTotalRevenue",
            "annualDilutedAverageShares",
            "annualInterestExpense",
            "annualInterestExpenseNonOperating",
            "annualInterestPaidSupplementalData",
            "annualInterestPaid",
            "annualPretaxIncome",
            "annualTaxRateForCalcs",
            "annualTotalDebt",
            "annualMarginalTaxRate",
            "annualNetIncome",
        ).joinToString(",")

        val root = fetchTimeseriesJson(requestSymbol, types)
        FundamentalTimeseries(
            freeCashFlow = parseTimeseriesMetric(root, "annualFreeCashFlow"),
            operatingCashFlow = parseTimeseriesMetric(root, "annualOperatingCashFlow"),
            capitalExpenditure = parseTimeseriesMetric(root, "annualCapitalExpenditure"),
            revenue = parseTimeseriesMetric(root, "annualTotalRevenue"),
            dilutedAverageShares = parseTimeseriesMetric(root, "annualDilutedAverageShares"),
            interestExpense = YahooInterestSeries.mergeByYear(
                parseTimeseriesMetric(root, "annualInterestExpense"),
                parseTimeseriesMetric(root, "annualInterestExpenseNonOperating"),
                parseTimeseriesMetric(root, "annualInterestPaidSupplementalData"),
                parseTimeseriesMetric(root, "annualInterestPaid"),
            ),
            pretaxIncome = parseTimeseriesMetric(root, "annualPretaxIncome"),
            taxRateForCalcs = parseTimeseriesMetric(root, "annualTaxRateForCalcs"),
            totalDebt = parseTimeseriesMetric(root, "annualTotalDebt"),
            marginalTaxRate = parseTimeseriesMetric(root, "annualMarginalTaxRate"),
            netIncome = parseTimeseriesMetric(root, "annualNetIncome"),
        )
    }

    private suspend fun fetchQuotePage(requestSymbol: String): String {
        val url = QUOTE_PAGE_URL.toHttpUrl().newBuilder()
            .addPathSegment(requestSymbol)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", QUOTE_PAGE_ACCEPT)
            .header("Accept-Language", QUOTE_PAGE_ACCEPT_LANGUAGE)
            .header("Upgrade-Insecure-Requests", QUOTE_PAGE_UPGRADE_INSECURE_REQUESTS)
            .build()
        return executeText(request)
    }

    private suspend fun fetchChartMetaProbe(requestSymbol: String, displaySymbol: String): ChartMetaProbe {
        val (rangeToken, interval) = chartRangeSpec(ChartRange.Day)
        val url = CHART_API_URL.toHttpUrl().newBuilder()
            .addPathSegment(requestSymbol)
            .addQueryParameter("range", rangeToken)
            .addQueryParameter("interval", interval)
            .addQueryParameter("includePrePost", "false")
            .build()
        val root = getJson(url.toString())
        val marketPriceCents = parseChartLatestCloseCents(root)
            ?: parseChartRegularMarketPriceCents(root)
        return ChartMetaProbe(
            marketPriceCents = marketPriceCents,
            companyName = parseChartCompanyName(root, displaySymbol),
        )
    }

    /**
     * One request, governed. The retry, the wait and the `Retry-After` all live in
     * [RequestGovernor] now; this only says what the answer was.
     *
     * A 429 is push-back and holds every other call as well, because the limit is Yahoo's and not
     * this call's. A 5xx is this call's bad luck, so it retries alone. Anything else is an answer,
     * even when it is a bad one, and retrying it only wastes the window.
     */
    private suspend fun executeText(request: Request): String = governor.request {
        try {
            val call = httpClient.newCall(request)
            call.executeCancellable().use { response ->
                val code = response.code
                when {
                    code == RATE_LIMITED_CODE -> {
                        response.body?.close()
                        RequestGovernor.Attempt.PushBack(
                            retryAfterMillis = retryAfterMillis(response),
                            error = IOException("HTTP $code for ${request.url}"),
                        )
                    }

                    code >= FIRST_SERVER_ERROR_CODE -> {
                        response.body?.close()
                        RequestGovernor.Attempt.Failed(
                            retryable = true,
                            error = IOException("HTTP $code for ${request.url}"),
                        )
                    }

                    !response.isSuccessful -> {
                        val body = call.readTextCancellable(response)
                        RequestGovernor.Attempt.Failed(
                            retryable = false,
                            error = IOException("HTTP $code for ${request.url}: $body"),
                        )
                    }

                    response.body == null -> {
                        RequestGovernor.Attempt.Failed(false, IOException("empty response body"))
                    }

                    else -> {
                        RequestGovernor.Attempt.Ok(call.readTextCancellable(response))
                    }
                }
            }
        } catch (error: IOException) {
            RequestGovernor.Attempt.Failed(retryable = isRetryable(error), error = error)
        }
    }

    /**
     * What Yahoo asked for, in milliseconds. `Retry-After` carries seconds, and the app used to
     * parse it and throw it away.
     */
    private fun retryAfterMillis(response: Response): Long? =
        response.header("Retry-After")?.toLongOrNull()?.times(1_000L)

    private suspend fun fetchTimeseriesJson(requestSymbol: String, types: String): JsonObject {
        suspend fun once(): JsonObject {
            val crumb = session.ensureCrumb()
            val url = FUNDAMENTALS_TIMESERIES_URL.toHttpUrl().newBuilder()
                .addPathSegment(requestSymbol)
                .addQueryParameter("type", types)
                .addQueryParameter("period1", "1262304000")
                .addQueryParameter("period2", "2524608000")
                .addQueryParameter("crumb", crumb)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", QUOTE_PAGE_ACCEPT_LANGUAGE)
                .build()
            val body = executeText(request)
            return json.parseToJsonElement(body).jsonObject
        }
        return try {
            once()
        } catch (error: IOException) {
            if (!isAuthError(error)) throw error
            session.clear()
            once()
        }
    }

    private suspend fun getJson(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        val body = executeText(request)
        return json.parseToJsonElement(body).jsonObject
    }

    private fun isRetryable(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("HTTP 429") ||
            message.contains("Too Many Requests", ignoreCase = true) ||
            message.contains("HTTP 5")
    }

    private fun isAuthError(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Invalid Crumb", ignoreCase = true) ||
            message.contains("Invalid Cookie", ignoreCase = true) ||
            message.contains("HTTP 401")
    }

    private fun isNotFound(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("HTTP 404")
    }
}

/**
 * Map app/profile symbols onto Yahoo request symbols.
 * Share classes use hyphens on Yahoo (BF.B → BF-B). Multi-letter suffixes are exchanges (YPFD.BA).
 */
internal fun yahooRequestSymbol(symbol: String): String {
    val normalized = symbol.trim().uppercase()
    val dot = normalized.lastIndexOf('.')
    if (dot <= 0 || dot == normalized.lastIndex) {
        return normalized
    }
    val suffix = normalized.substring(dot + 1)
    return if (suffix.length == 1 && suffix[0].isLetter()) {
        normalized.substring(0, dot) + "-" + suffix
    } else {
        normalized
    }
}

internal fun isRateLimitDetail(detail: String): Boolean {
    val normalized = detail.lowercase()
    return normalized.contains("http 429") ||
        normalized.contains("too many requests") ||
        normalized.contains("rate limit")
}

internal fun isUsableCompanyName(name: String?): Boolean {
    val normalized = name?.trim().orEmpty()
    return normalized.isNotBlank() && !normalized.equals("null", ignoreCase = true)
}

internal fun parseSearchQuotes(root: JsonObject): List<YahooSearchQuote> =
    root.childArray("quotes").mapNotNull { element ->
        val quote = element.jsonObject
        val quoteType = quote.stringValue("quoteType") ?: return@mapNotNull null
        if (quoteType !in TRADABLE_SEARCH_QUOTE_TYPES) return@mapNotNull null
        val symbol = quote.stringValue("symbol")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val companyName = listOf(
            quote.stringValue("longname"),
            quote.stringValue("longName"),
            quote.stringValue("shortname"),
            quote.stringValue("shortName"),
        ).firstOrNull(::isUsableCompanyName) ?: symbol
        YahooSearchQuote(
            symbol = symbol,
            companyName = companyName,
            exchange = quote.stringValue("exchDisp") ?: quote.stringValue("exchange"),
            quoteType = quoteType,
        )
    }

private val TRADABLE_SEARCH_QUOTE_TYPES = setOf("EQUITY", "ETF")

internal fun parseQuotePage(
    symbol: String,
    body: String,
    chartMarketPriceCents: Long?,
    diagnostics: MutableList<ProviderDiagnostic>,
): QuoteContext {
    val financialData = parseEmbeddedJsonObject(body, FINANCIAL_DATA_MARKER, diagnostics) ?: JsonObject(emptyMap())
    val summaryDetail = parseEmbeddedJsonObject(body, SUMMARY_DETAIL_MARKER, diagnostics) ?: JsonObject(emptyMap())
    val statistics = parseEmbeddedJsonObject(body, DEFAULT_KEY_STATISTICS_MARKER, diagnostics) ?: JsonObject(emptyMap())
    val recommendationTrend = parseEmbeddedJsonObject(body, RECOMMENDATION_TREND_MARKER, diagnostics)
    val price = parseQuoteSummaryPrice(body, diagnostics)
    val assetProfile = parseEmbeddedJsonObject(body, ASSET_PROFILE_MARKER, diagnostics)
    val companyName = resolveCompanyName(
        body = body,
        symbol = symbol,
        assetProfile = assetProfile,
    )
    return buildQuoteContext(
        symbol = symbol,
        financialData = financialData,
        summaryDetail = summaryDetail,
        statistics = statistics,
        recommendationTrend = recommendationTrend,
        price = price,
        assetProfile = assetProfile,
        companyName = companyName,
        chartMarketPriceCents = chartMarketPriceCents,
        // The HTML scrape carries no calendar block. A row from this path shows no earnings mark
        // rather than a guessed one.
        nextEarningsEpoch = null,
        diagnostics = diagnostics,
    )
}

/**
 * Parse a live Yahoo `v10/finance/quoteSummary` JSON payload into domain quote context.
 * Fixtures under `test/resources/yahoo/quoteSummary/` were captured from production.
 */
internal fun parseQuoteSummary(
    root: JsonObject,
    symbol: String,
    chartMarketPriceCents: Long?,
    diagnostics: MutableList<ProviderDiagnostic>,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
): QuoteContext {
    val result = root.child("quoteSummary").childArray("result").firstOrNull()?.jsonObject
    if (result == null) {
        val errorDescription = root.child("quoteSummary").child("error").string("description")
            ?: root.child("finance").child("error").string("description")
        diagnostics += ProviderDiagnostic(
            component = QUOTE_SUMMARY_COMPONENT,
            kind = ERROR_KIND,
            detail = errorDescription ?: "quoteSummary result is empty",
            retryable = false,
        )
        return QuoteContext()
    }

    val financialData = result.child("financialData")
    val summaryDetail = result.child("summaryDetail")
    val statistics = result.child("defaultKeyStatistics")
    val recommendationTrend = result["recommendationTrend"]?.jsonObject
    val price = result.child("price")
    val assetProfile = result["assetProfile"]?.jsonObject
    val calendarEvents = result["calendarEvents"]?.jsonObject
    val companyName = listOfNotNull(
        price.string("longName"),
        price.string("shortName"),
        assetProfile?.string("longName"),
        assetProfile?.string("shortName"),
        assetProfile?.string("name"),
    )
        .mapNotNull { candidate -> normalizeCompanyNameCandidate(candidate, symbol) }
        .firstOrNull()

    return buildQuoteContext(
        symbol = symbol,
        financialData = financialData,
        summaryDetail = summaryDetail,
        statistics = statistics,
        recommendationTrend = recommendationTrend,
        price = price,
        assetProfile = assetProfile,
        companyName = companyName,
        chartMarketPriceCents = chartMarketPriceCents
            ?: price.rawMoney("regularMarketPrice"),
        nextEarningsEpoch = nextEarningsEpochOf(calendarEvents, nowEpochSeconds),
        diagnostics = diagnostics,
    )
}

/**
 * The rows of a batch quote answer, keyed by app symbol. [appSymbolByRequestSymbol] maps what
 * Yahoo was asked (BF-B) back to what the app calls it (BF.B). A row without a price is left out:
 * that is how the endpoint answers a symbol it does not serve.
 *
 * Throws [IllegalArgumentException] when the body is not a quote answer at all (an error object,
 * an empty document), so the caller can tell a refused batch from an answered one.
 */
internal fun parseQuoteBatch(
    root: JsonObject,
    appSymbolByRequestSymbol: Map<String, String>,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
): Map<String, QuoteBatchEntry> {
    val response = root["quoteResponse"]?.jsonObject
        ?: throw IllegalArgumentException(
            root.child("finance").child("error").string("description") ?: "batch quote answer has no quoteResponse",
        )
    response["error"]?.takeUnless { it is JsonNull }?.jsonObject?.let { error ->
        throw IllegalArgumentException(error.string("description") ?: "batch quote error")
    }
    val entries = HashMap<String, QuoteBatchEntry>()
    response.childArray("result").forEach { element ->
        val row = element.jsonObject
        val requestSymbol = row.string("symbol") ?: return@forEach
        val symbol = appSymbolByRequestSymbol[requestSymbol] ?: return@forEach
        val marketPriceCents = row.plainMoney("regularMarketPrice") ?: return@forEach
        if (marketPriceCents <= 0L) return@forEach
        val companyName = listOfNotNull(row.string("longName"), row.string("shortName"))
            .mapNotNull { candidate -> normalizeCompanyNameCandidate(candidate, symbol) }
            .firstOrNull()
        entries[symbol] = QuoteBatchEntry(
            symbol = symbol,
            companyName = companyName,
            marketPriceCents = marketPriceCents,
            profitable = row.plainDouble("epsTrailingTwelveMonths")?.let { eps -> eps > 0.0 },
            nextEarningsEpoch = nextEarningsEpochOf(
                epochs = listOfNotNull(
                    row.plainLong("earningsTimestamp"),
                    row.plainLong("earningsTimestampStart"),
                    row.plainLong("earningsTimestampEnd"),
                ),
                nowEpochSeconds = nowEpochSeconds,
            ),
        )
    }
    return entries
}

/**
 * The soonest earnings date Yahoo still has ahead of [nowEpochSeconds], or the latest one it knows
 * about when every date has passed.
 *
 * Parity with Windows `quote_summary.rs:264-284`. The fallback to a past date is deliberate there
 * and kept here: a stale date says "the last report was then", which the caller can still judge,
 * while a null says nothing at all. Yahoo sends two entries when the date is an unconfirmed window,
 * so the earliest future one is the near edge of that window.
 */
private fun nextEarningsEpochOf(calendarEvents: JsonObject?, nowEpochSeconds: Long): Long? {
    var epochs = calendarEvents
        ?.get("earnings")?.jsonObject
        ?.get("earningsDate")?.jsonArray
        .orEmpty()
        .mapNotNull { entry -> entry.jsonObject["raw"]?.jsonPrimitive?.longOrNull }
    return nextEarningsEpochOf(epochs, nowEpochSeconds)
}

/** The same choice over dates that arrive already unwrapped, as the batch quote endpoint sends them. */
private fun nextEarningsEpochOf(epochs: List<Long>, nowEpochSeconds: Long): Long? {
    if (epochs.isEmpty()) return null
    return epochs.filter { it >= nowEpochSeconds }.minOrNull() ?: epochs.max()
}

private fun buildQuoteContext(
    symbol: String,
    financialData: JsonObject,
    summaryDetail: JsonObject,
    statistics: JsonObject,
    recommendationTrend: JsonObject?,
    price: JsonObject,
    assetProfile: JsonObject?,
    companyName: String?,
    chartMarketPriceCents: Long?,
    nextEarningsEpoch: Long?,
    diagnostics: MutableList<ProviderDiagnostic>,
): QuoteContext {
    val currentRecommendation = recommendationTrend
        ?.childArray("trend")
        ?.mapNotNull(::toRecommendationPeriod)
        ?.let { periods -> periods.firstOrNull { it.period == "0m" } ?: periods.firstOrNull() }

    val marketPriceCents = financialData.rawMoney("currentPrice")
        ?: price.rawMoney("regularMarketPrice")
        ?: chartMarketPriceCents
    val intrinsicValueCents = financialData.rawMoney("targetMeanPrice")
    val fairValueCents = financialData.rawMoney("targetMedianPrice")
    val lowFairValueCents = financialData.rawMoney("targetLowPrice")
    val highFairValueCents = financialData.rawMoney("targetHighPrice")
    val profitable = statistics.rawDouble("trailingEps")?.let { it > 0.0 }
    val analystOpinionCount = financialData.rawInt("numberOfAnalystOpinions")
        ?: currentRecommendation?.totalCount()
    val recommendationMeanHundredths = financialData.rawDouble("recommendationMean")
        ?.takeIf(Double::isFinite)
        ?.times(100.0)
        ?.roundToLong()
        ?.toInt()

    val fundamentals = FundamentalSnapshot(
        symbol = symbol,
        sectorKey = assetProfile.string("sectorKey"),
        sectorName = assetProfile.string("sectorDisp") ?: assetProfile.string("sector"),
        industryKey = assetProfile.string("industryKey"),
        industryName = assetProfile.string("industryDisp") ?: assetProfile.string("industry"),
        country = assetProfile.string("country"),
        marketCapDollars = resolveMarketCapDollars(
            reportedMarketCap = price.rawDouble("marketCap"),
            sharesOutstanding = statistics.rawDouble("sharesOutstanding"),
            marketPriceDollars = financialData.rawDouble("currentPrice")
                ?: price.rawDouble("regularMarketPrice")
                ?: chartMarketPriceCents?.let { it / 100.0 },
        ),
        sharesOutstanding = statistics.rawDouble("sharesOutstanding")?.toLong(),
        trailingPeHundredths = statistics.rawDouble("trailingPE")?.times(100.0)?.roundToLong()?.toInt(),
        forwardPeHundredths = statistics.rawDouble("forwardPE")?.times(100.0)?.roundToLong()?.toInt(),
        priceToBookHundredths = statistics.rawDouble("priceToBook")?.times(100.0)?.roundToLong()?.toInt(),
        // Reported ROE only (financialData); no EPS/book synthesis.
        returnOnEquityBps = financialData.rawDouble("returnOnEquity")
            ?.takeIf { it.isFinite() }
            ?.times(10_000.0)
            ?.roundToLong()
            ?.toInt(),
        ebitdaDollars = financialData.rawDouble("ebitda")?.toLong(),
        enterpriseValueDollars = statistics.rawDouble("enterpriseValue")?.toLong(),
        enterpriseToEbitdaHundredths = statistics.rawDouble("enterpriseToEbitda")?.times(100.0)?.roundToLong()?.toInt(),
        totalDebtDollars = financialData.rawDouble("totalDebt")?.toLong(),
        totalCashDollars = financialData.rawDouble("totalCash")?.toLong(),
        debtToEquityHundredths = financialData.rawDouble("debtToEquity")?.times(100.0)?.roundToLong()?.toInt(),
        freeCashFlowDollars = financialData.rawDouble("freeCashflow")?.toLong(),
        operatingCashFlowDollars = financialData.rawDouble("operatingCashflow")?.toLong(),
        betaMillis = statistics.rawDouble("beta")?.times(1_000.0)?.roundToLong()?.toInt(),
        trailingEpsCents = statistics.rawDouble("trailingEps")?.times(100.0)?.roundToLong()?.toLong(),
        earningsGrowthBps = financialData.rawDouble("earningsGrowth")?.times(10_000.0)?.roundToLong()?.toInt(),
        // BVPS: statistics bookValue/bookValuePerShare, else price / P/B.
        bookValuePerShareCents = resolveBookValuePerShareCents(
            statistics = statistics,
            financialData = financialData,
            price = price,
            chartMarketPriceCents = chartMarketPriceCents,
        ),
        // Retention: financialData.payoutRatio then summaryDetail.payoutRatio (COF-class).
        retentionBps = resolveRetentionBps(financialData, summaryDetail),
    ).takeIf(FundamentalSnapshot::hasAnyValues)

    val snapshot = if (marketPriceCents != null && intrinsicValueCents != null && profitable != null) {
        MarketSnapshot(
            symbol = symbol,
            companyName = companyName,
            profitable = profitable,
            marketPriceCents = marketPriceCents,
            intrinsicValueCents = intrinsicValueCents,
            nextEarningsEpoch = nextEarningsEpoch,
        )
    } else {
        val missingFields = buildList {
            if (marketPriceCents == null) add("market price")
            if (intrinsicValueCents == null) add("target mean price")
            if (profitable == null) add("profitability")
        }
        diagnostics += ProviderDiagnostic(
            component = "core",
            kind = "missing",
            detail = "core snapshot is missing ${missingFields.joinToString(", ")}",
            retryable = false,
        )
        null
    }

    val externalSignal = fairValueCents?.let {
        sanitizeExternalSignal(
            ExternalValuationSignal(
                symbol = symbol,
                fairValueCents = it,
                ageSeconds = 0,
                lowFairValueCents = lowFairValueCents,
                highFairValueCents = highFairValueCents,
                analystOpinionCount = analystOpinionCount,
                recommendationMeanHundredths = recommendationMeanHundredths,
                strongBuyCount = currentRecommendation?.strongBuy,
                buyCount = currentRecommendation?.buy,
                holdCount = currentRecommendation?.hold,
                sellCount = currentRecommendation?.sell,
                strongSellCount = currentRecommendation?.strongSell,
            ),
        )
    } ?: run {
        diagnostics += ProviderDiagnostic(
            component = "external",
            kind = "missing",
            detail = "external signal is missing target median price",
            retryable = false,
        )
        null
    }

    if (fundamentals == null) {
        diagnostics += ProviderDiagnostic(
            component = "fundamentals",
            kind = "missing",
            detail = "fundamentals snapshot is missing all supported quote fields",
            retryable = false,
        )
    }

    return QuoteContext(
        snapshot = snapshot,
        externalSignal = externalSignal,
        fundamentals = fundamentals,
        companyName = companyName,
    )
}

internal fun parseEmbeddedJsonObject(
    body: String,
    marker: String,
    diagnostics: MutableList<ProviderDiagnostic>,
): JsonObject? {
    return try {
        extractEmbeddedJsonObject(body, marker)
            ?.replace("\\\"", "\"")
            ?.let { Json.parseToJsonElement(it).jsonObject }
    } catch (error: Exception) {
        diagnostics += ProviderDiagnostic(
            component = "quoteHtml",
            kind = "error",
            detail = error.message ?: "failed to parse quote page JSON",
            retryable = false,
        )
        null
    }
}

private fun parseQuoteSummaryPrice(
    body: String,
    diagnostics: MutableList<ProviderDiagnostic>,
): JsonObject {
    var searchStart = 0
    while (true) {
        val markerIndex = body.indexOf(PRICE_MARKER, searchStart)
        if (markerIndex < 0) {
            return JsonObject(emptyMap())
        }
        val fragment = try {
            extractEmbeddedJsonObjectAt(body, markerIndex, PRICE_MARKER)
        } catch (error: Exception) {
            diagnostics += ProviderDiagnostic(
                component = "quoteHtml",
                kind = "error",
                detail = error.message ?: "failed to parse quote price block",
                retryable = false,
            )
            null
        }
        if (fragment != null) {
            val decoded = fragment.replace("\\\"", "\"")
            val parsed = runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull()
            if (parsed?.rawDouble("marketCap") != null) {
                return parsed
            }
        }
        searchStart = markerIndex + PRICE_MARKER.length
    }
}

internal fun extractEmbeddedJsonObject(body: String, marker: String): String? {
    val markerIndex = body.indexOf(marker)
    if (markerIndex < 0) return null
    return extractEmbeddedJsonObjectAt(body, markerIndex, marker)
}

internal fun extractEmbeddedJsonObjectAt(body: String, markerIndex: Int, marker: String): String? {
    val searchStart = markerIndex + marker.length
    val braceOffset = body.substring(searchStart).indexOf('{')
    if (braceOffset < 0) return null
    val objectStart = searchStart + braceOffset
    for (index in objectStart until body.length) {
        if (body[index] != '}') continue
        val fragment = body.substring(objectStart, index + 1)
        val decoded = fragment.replace("\\\"", "\"")
        if (runCatching { Json.parseToJsonElement(decoded).jsonObject }.isSuccess) {
            return fragment
        }
    }
    return null
}

internal fun resolveCompanyName(
    body: String,
    symbol: String,
    assetProfile: JsonObject? = null,
): String? {
    val candidates = listOfNotNull(
        parseMetaTitle(body)?.let { parseCompanyNameFromTitle(it, symbol) },
        parseHtmlTitle(body)?.let { parseCompanyNameFromTitle(it, symbol) },
        assetProfile?.string("longName")?.let { normalizeCompanyNameCandidate(it, symbol) },
        assetProfile?.string("shortName")?.let { normalizeCompanyNameCandidate(it, symbol) },
        parseEmbeddedStringField(body, LONG_NAME_MARKER)?.let { normalizeCompanyNameCandidate(it, symbol) },
    )
    return candidates.firstOrNull()
}

internal fun mergeCompanyName(
    quoteCompanyName: String?,
    chartCompanyName: String?,
): String? =
    quoteCompanyName?.takeIf(::isUsableCompanyName)
        ?: chartCompanyName?.takeIf(::isUsableCompanyName)

/**
 * Extract a usable company name from Yahoo chart API meta.
 * Live samples: L/C/F/V/T/AAPL expose longName; junk numeric shortName (e.g. N) is rejected.
 */
internal fun parseChartCompanyName(root: JsonObject, symbol: String): String? {
    val meta = root.child("chart").childArray("result").firstOrNull()?.jsonObject?.get("meta")?.jsonObject
        ?: return null
    return listOfNotNull(meta.string("longName"), meta.string("shortName"))
        .mapNotNull { candidate -> normalizeCompanyNameCandidate(candidate, symbol) }
        .firstOrNull()
}

internal fun parseChartLatestCloseCents(root: JsonObject): Long? {
    val result = root.child("chart").childArray("result").firstOrNull()?.jsonObject ?: return null
    val closes = result.child("indicators").childArray("quote").firstOrNull()?.jsonObject
        ?.get("close")
        ?.jsonArray
        ?: return null
    for (index in closes.indices.reversed()) {
        val close = closes[index].jsonPrimitive.plainDoubleOrNull() ?: continue
        dollarsToCents(close)?.let { return it }
    }
    return null
}

internal fun parseChartRegularMarketPriceCents(root: JsonObject): Long? {
    val meta = root.child("chart").childArray("result").firstOrNull()?.jsonObject?.get("meta")?.jsonObject
        ?: return null
    val price = meta.get("regularMarketPrice")?.jsonPrimitive?.plainDoubleOrNull() ?: return null
    return dollarsToCents(price)
}

private fun parseMetaTitle(body: String): String? {
    val start = body.indexOf(META_TITLE_MARKER)
    if (start < 0) return null
    val contentStart = start + META_TITLE_MARKER.length
    val contentEnd = body.indexOf('"', contentStart)
    if (contentEnd < 0) return null
    return body.substring(contentStart, contentEnd)
}

private fun parseHtmlTitle(body: String): String? {
    val start = body.indexOf(TITLE_MARKER, ignoreCase = true)
    if (start < 0) return null
    val contentStart = start + TITLE_MARKER.length
    val contentEnd = body.indexOf("</title>", contentStart, ignoreCase = true)
    if (contentEnd < 0) return null
    return body.substring(contentStart, contentEnd).trim().takeIf(String::isNotBlank)
}

internal fun parseCompanyNameFromTitle(title: String, symbol: String): String? {
    var normalizedTitle = normalizeCompanyName(title) ?: return null
    var marker = "($symbol)"
    var from = 0
    while (true) {
        var at = normalizedTitle.indexOf(marker, from, ignoreCase = true)
        if (at < 0) return null
        from = at + 1
        var after = at + marker.length
        if (at == 0 || !normalizedTitle[at - 1].isWhitespace()) continue
        if (after >= normalizedTitle.length || !normalizedTitle[after].isWhitespace()) continue
        return normalizedTitle.substring(0, at).trim().takeIf(String::isNotBlank)
    }
}

private fun parseEmbeddedStringField(body: String, marker: String): String? {
    val markerIndex = body.indexOf(marker)
    if (markerIndex < 0) return null
    val valueStart = markerIndex + marker.length
    if (body.getOrNull(valueStart) != '"') return null
    val contentStart = valueStart + 1
    val contentEnd = body.indexOf('"', contentStart)
    if (contentEnd < 0) return null
    return body.substring(contentStart, contentEnd)
}

private fun normalizeCompanyName(raw: String): String? = raw
    .trim()
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&#x27;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .takeIf(String::isNotBlank)

internal fun normalizeCompanyNameCandidate(raw: String, symbol: String): String? {
    val normalized = normalizeCompanyName(raw) ?: return null
    if (!isUsableCompanyName(normalized)) return null
    if (normalized.equals(symbol, ignoreCase = true)) return null
    if (normalized.equals("Symbol Lookup from Yahoo Finance", ignoreCase = true)) return null
    // Reject pure numeric junk sometimes returned as shortName for dead/invalid symbols.
    if (normalized.all { character -> character.isDigit() || character.isWhitespace() || character == '.' || character == ',' }) {
        return null
    }
    return normalized
}

private fun toRecommendationPeriod(element: JsonElement): RecommendationPeriod? {
    val obj = element.jsonObject
    return RecommendationPeriod(
        period = obj.stringValue("period") ?: return null,
        strongBuy = obj.intValue("strongBuy") ?: 0,
        buy = obj.intValue("buy") ?: 0,
        hold = obj.intValue("hold") ?: 0,
        sell = obj.intValue("sell") ?: 0,
        strongSell = obj.intValue("strongSell") ?: 0,
    )
}

private fun parseTimeseriesMetric(root: JsonObject, name: String): List<AnnualReportedValue> {
    val result = root.child("timeseries").childArray("result")
    val series = result.firstOrNull { it.jsonObject[name] != null }?.jsonObject?.get(name)?.jsonArray.orEmpty()
    return series.mapNotNull { element ->
        val obj = element.jsonObject
        val date = obj["asOfDate"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val value = obj["reportedValue"]?.jsonObject?.get("raw")?.jsonPrimitive?.plainDoubleOrNull() ?: return@mapNotNull null
        AnnualReportedValue(
            asOfDate = date,
            value = value,
            source = DcfSource.YahooFinance,
            concept = name,
        )
    }.sortedBy { it.asOfDate }
}

private fun componentState(
    present: Boolean,
    diagnostics: List<ProviderDiagnostic>,
    component: String,
): ProviderComponentState = when {
    present -> ProviderComponentState.Fresh
    diagnostics.any { it.component == component && it.kind == "error" } -> ProviderComponentState.Error
    else -> ProviderComponentState.Missing
}

private fun chartRangeSpec(range: ChartRange): Pair<String, String> = when (range) {
    ChartRange.Day -> "1d" to "5m"
    ChartRange.Week -> "5d" to "30m"
    ChartRange.Month -> "1mo" to "1d"
    ChartRange.Year -> "1y" to "1wk"
    ChartRange.FiveYears -> "5y" to "1mo"
    ChartRange.TenYears -> "10y" to "1mo"
}

internal fun replayBackingRangeSpec(range: ChartRange): Pair<String, String> = when (range) {
    ChartRange.Day -> "5d" to "5m"
    ChartRange.Week -> "1mo" to "30m"
    ChartRange.Month -> "3mo" to "1d"
    ChartRange.Year -> "2y" to "1wk"
    ChartRange.FiveYears -> "10y" to "1mo"
    ChartRange.TenYears -> "10y" to "1mo"
}

internal fun resolveMarketCapDollars(
    reportedMarketCap: Double?,
    sharesOutstanding: Double?,
    marketPriceDollars: Double?,
): Long? {
    reportedMarketCap?.takeIf { it.isFinite() && it > 0.0 }?.toLong()?.let { return it }
    val shares = sharesOutstanding?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val price = marketPriceDollars?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val derived = shares * price
    return derived.takeIf { it.isFinite() && it > 0.0 }?.toLong()
}

/** BVPS: statistics bookValue/bookValuePerShare, else price / P/B. */
internal fun resolveBookValuePerShareCents(
    statistics: JsonObject,
    financialData: JsonObject,
    price: JsonObject,
    chartMarketPriceCents: Long?,
): Long? {
    statistics.rawDouble("bookValue")
        ?.takeIf { it > 0.0 }
        ?.times(100.0)
        ?.roundToLong()
        ?.let { return it }
    statistics.rawDouble("bookValuePerShare")
        ?.takeIf { it > 0.0 }
        ?.times(100.0)
        ?.roundToLong()
        ?.let { return it }
    val priceDollars = financialData.rawDouble("currentPrice")
        ?: price.rawDouble("regularMarketPrice")
        ?: chartMarketPriceCents?.takeIf { it > 0 }?.div(100.0)
        ?: return null
    val pb = statistics.rawDouble("priceToBook") ?: return null
    if (priceDollars <= 0.0 || pb <= 0.0) return null
    val bvps = priceDollars / pb
    return bvps.takeIf { it.isFinite() && it > 0.0 }?.times(100.0)?.roundToLong()
}

/**
 * Retention from payout: financialData first, then summaryDetail (COF-class).
 * Empty Yahoo objects omit `raw` and resolve as missing.
 */
internal fun resolveRetentionBps(
    financialData: JsonObject,
    summaryDetail: JsonObject,
): Int? {
    val payout = financialData.rawDouble("payoutRatio")
        ?: summaryDetail.rawDouble("payoutRatio")
        ?: return null
    if (!payout.isFinite() || payout < 0.0) return null
    if (payout >= 1.0) return 0
    return ((1.0 - payout) * 10_000.0).roundToLong().toInt()
}

private fun dollarsToCents(value: Double): Long? =
    value.takeIf { it.isFinite() && it > 0.0 }?.times(100.0)?.roundToLong()

/**
 * `doubleOrNull` with no regex.
 *
 * `kotlinx.serialization`'s `doubleOrNull` calls `String.toDoubleOrNull`, which screens the text
 * against `Regex` before it parses. Each screen builds a `java.util.regex.Matcher`, and on Android
 * a Matcher owns a native ICU object that only a GC releases. A universe refresh parses four of
 * these per candle for every tracked symbol, which is roughly 500,000 matchers in a burst on four
 * dispatcher threads. Measured: the process reached 1.7 GB of native memory in 40 seconds and the
 * Scudo allocator aborted with SIGABRT. The Java heap cap is 192 MB and was never near it, which
 * is how the growth showed itself as native.
 *
 * `String.toDouble` goes straight to `Double.parseDouble` and allocates no matcher. The catch is
 * the price of that: `parseDouble` reports a bad value by throwing. Yahoo writes `null` into a
 * candle array for a bar with no trade, so that branch is common and is checked first.
 */
private fun JsonPrimitive.plainDoubleOrNull(): Double? {
    if (this is JsonNull) {
        return null
    }
    return try {
        content.toDouble()
    } catch (_: NumberFormatException) {
        null
    }
}

private fun JsonObject?.child(name: String): JsonObject =
    this?.get(name)?.jsonObject ?: JsonObject(emptyMap())

private fun JsonObject?.childArray(name: String): JsonArray =
    this?.get(name)?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject?.string(name: String): String? =
    this?.get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject?.stringValue(name: String): String? =
    this?.get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject?.rawDouble(name: String): Double? =
    this?.get(name)?.jsonObject?.get("raw")?.jsonPrimitive?.plainDoubleOrNull()

private fun JsonObject?.rawInt(name: String): Int? =
    rawDouble(name)?.takeIf(Double::isFinite)?.roundToLong()?.toInt()

private fun JsonObject?.intValue(name: String): Int? =
    this?.get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject?.rawMoney(name: String): Long? =
    rawDouble(name)?.let(::dollarsToCents)

/** The batch quote endpoint sends bare numbers where quoteSummary sends `{raw, fmt}` pairs. */
private fun JsonObject?.plainDouble(name: String): Double? =
    (this?.get(name) as? JsonPrimitive)?.plainDoubleOrNull()

private fun JsonObject?.plainLong(name: String): Long? =
    (this?.get(name) as? JsonPrimitive)?.longOrNull

private fun JsonObject?.plainMoney(name: String): Long? =
    plainDouble(name)?.let(::dollarsToCents)

private val JsonPrimitive.intOrNull: Int?
    get() = contentOrNull?.toIntOrNull()

private val JsonPrimitive.longOrNull: Long?
    get() = contentOrNull?.toLongOrNull()

private val JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()

internal fun canceledIo(error: IOException): Boolean {
    if (error is SocketTimeoutException) {
        return false
    }
    val message = error.message.orEmpty()
    return error is java.io.InterruptedIOException ||
        message.contains("Canceled", ignoreCase = true) ||
        message.contains("Cancelled", ignoreCase = true)
}
