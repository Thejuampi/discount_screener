package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.sanitizeExternalSignal
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.time.Duration
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

internal data class QuoteContext(
    val snapshot: MarketSnapshot? = null,
    val externalSignal: ExternalValuationSignal? = null,
    val fundamentals: FundamentalSnapshot? = null,
    val companyName: String? = null,
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
private const val FUNDAMENTALS_TIMESERIES_URL =
    "https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
private const val QUOTE_PAGE_ACCEPT =
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
private const val QUOTE_PAGE_ACCEPT_LANGUAGE = "en-US,en;q=0.9"
private const val QUOTE_PAGE_UPGRADE_INSECURE_REQUESTS = "1"

private const val QUOTE_HTML_COMPONENT = "quoteHtml"
private const val CHART_COMPONENT = "chart"
private const val CORE_COMPONENT = "core"
private const val EXTERNAL_COMPONENT = "external"
private const val FUNDAMENTALS_COMPONENT = "fundamentals"
private const val MISSING_KIND = "missing"
private const val ERROR_KIND = "error"

internal const val FINANCIAL_DATA_MARKER = "\\\"financialData\\\":"
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
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(20))
        .cookieJar(
            JavaNetCookieJar(
                CookieManager().apply {
                    setCookiePolicy(CookiePolicy.ACCEPT_ALL)
                },
            ),
        )
        .addInterceptor(BROWSER_DEFAULT_HEADERS_INTERCEPTOR)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    open suspend fun fetchSymbol(symbol: String): ProviderFetchResult = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val quoteBody = try {
            fetchQuotePage(symbol)
        } catch (error: IOException) {
            diagnostics += ProviderDiagnostic(
                component = QUOTE_HTML_COMPONENT,
                kind = ERROR_KIND,
                detail = error.message ?: "quote page request failed",
                retryable = isRetryable(error),
            )
            null
        }

        val quoteMarketPriceCents = quoteBody?.let { body ->
            parseEmbeddedJsonObject(body, FINANCIAL_DATA_MARKER, diagnostics)?.rawMoney("currentPrice")
        }

        val chartMarketPriceCents = if (quoteMarketPriceCents != null) {
            null
        } else {
            try {
                fetchLiveChartProbe(symbol)
            } catch (error: IOException) {
                diagnostics += ProviderDiagnostic(
                    component = CHART_COMPONENT,
                    kind = ERROR_KIND,
                    detail = error.message ?: "chart probe failed",
                    retryable = isRetryable(error),
                )
                null
            } ?: run {
                diagnostics += ProviderDiagnostic(
                    component = CHART_COMPONENT,
                    kind = MISSING_KIND,
                    detail = "chart API returned no recent price candles",
                    retryable = false,
                )
                null
            }
        }

        val quoteContext = quoteBody?.let { body ->
            parseQuotePage(
                symbol = symbol,
                body = body,
                chartMarketPriceCents = quoteMarketPriceCents ?: chartMarketPriceCents,
                diagnostics = diagnostics,
            )
        } ?: QuoteContext()

        ProviderFetchResult(
            symbol = symbol,
            snapshot = quoteContext.snapshot,
            externalSignal = quoteContext.externalSignal,
            fundamentals = quoteContext.fundamentals,
            companyName = quoteContext.companyName,
            coverage = ProviderCoverage(
                core = componentState(quoteContext.snapshot != null, diagnostics, CORE_COMPONENT),
                external = componentState(quoteContext.externalSignal != null, diagnostics, EXTERNAL_COMPONENT),
                fundamentals = componentState(quoteContext.fundamentals != null, diagnostics, FUNDAMENTALS_COMPONENT),
            ),
            diagnostics = diagnostics,
        )
    }

    open suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> = withContext(Dispatchers.IO) {
        val (rangeToken, interval) = chartRangeSpec(range)
        val url = CHART_API_URL.toHttpUrl().newBuilder()
            .addPathSegment(symbol)
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
            val close = closes.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val open = opens.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: close
            val high = highs.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: close
            val low = lows.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: close
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

    open suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries = withContext(Dispatchers.IO) {
        val types = listOf(
            "annualFreeCashFlow",
            "annualOperatingCashFlow",
            "annualCapitalExpenditure",
            "annualDilutedAverageShares",
            "annualInterestExpense",
            "annualPretaxIncome",
            "annualTaxRateForCalcs",
            "annualNetIncome",
        ).joinToString(",")

        val url = FUNDAMENTALS_TIMESERIES_URL.toHttpUrl().newBuilder()
            .addPathSegment(symbol)
            .addQueryParameter("type", types)
            .addQueryParameter("period1", "1262304000")
            .addQueryParameter("period2", "2524608000")
            .build()

        val root = getJson(url.toString())
        FundamentalTimeseries(
            freeCashFlow = parseTimeseriesMetric(root, "annualFreeCashFlow"),
            operatingCashFlow = parseTimeseriesMetric(root, "annualOperatingCashFlow"),
            capitalExpenditure = parseTimeseriesMetric(root, "annualCapitalExpenditure"),
            dilutedAverageShares = parseTimeseriesMetric(root, "annualDilutedAverageShares"),
            interestExpense = parseTimeseriesMetric(root, "annualInterestExpense"),
            pretaxIncome = parseTimeseriesMetric(root, "annualPretaxIncome"),
            taxRateForCalcs = parseTimeseriesMetric(root, "annualTaxRateForCalcs"),
            netIncome = parseTimeseriesMetric(root, "annualNetIncome"),
        )
    }

    private fun fetchQuotePage(symbol: String): String {
        val url = QUOTE_PAGE_URL.toHttpUrl().newBuilder()
            .addPathSegment(symbol)
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

    private fun fetchLiveChartProbe(symbol: String): Long? {
        val (rangeToken, interval) = chartRangeSpec(ChartRange.Day)
        return fetchHistoricalCandlesBlocking(symbol, rangeToken, interval)
            .lastOrNull()
            ?.closeCents
    }

    private fun fetchHistoricalCandlesBlocking(symbol: String, range: String, interval: String): List<HistoricalCandle> {
        val url = CHART_API_URL.toHttpUrl().newBuilder()
            .addPathSegment(symbol)
            .addQueryParameter("range", range)
            .addQueryParameter("interval", interval)
            .addQueryParameter("includePrePost", "false")
            .build()
        val root = getJson(url.toString())
        val result = root.child("chart").childArray("result").firstOrNull()?.jsonObject ?: return emptyList()
        val timestamps = result["timestamp"]?.jsonArray ?: return emptyList()
        val quote = result.child("indicators").childArray("quote").firstOrNull()?.jsonObject ?: return emptyList()
        val opens = quote["open"]?.jsonArray ?: JsonArray(emptyList())
        val highs = quote["high"]?.jsonArray ?: JsonArray(emptyList())
        val lows = quote["low"]?.jsonArray ?: JsonArray(emptyList())
        val closes = quote["close"]?.jsonArray ?: JsonArray(emptyList())
        val volumes = quote["volume"]?.jsonArray ?: JsonArray(emptyList())

        return timestamps.indices.mapNotNull { index ->
            val close = closes.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val open = opens.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: close
            val high = highs.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: close
            val low = lows.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: close
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

    private fun executeText(request: Request): String =
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}")
            }
            response.body?.string() ?: throw IOException("empty response body")
        }

    private fun getJson(url: String): JsonObject {
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
        return message.contains("HTTP 429") || message.contains("HTTP 5")
    }

}

internal fun parseQuotePage(
    symbol: String,
    body: String,
    chartMarketPriceCents: Long?,
    diagnostics: MutableList<ProviderDiagnostic>,
): QuoteContext {
    val financialData = parseEmbeddedJsonObject(body, FINANCIAL_DATA_MARKER, diagnostics) ?: JsonObject(emptyMap())
    val statistics = parseEmbeddedJsonObject(body, DEFAULT_KEY_STATISTICS_MARKER, diagnostics) ?: JsonObject(emptyMap())
    val recommendationTrend = parseEmbeddedJsonObject(body, RECOMMENDATION_TREND_MARKER, diagnostics)
    val currentRecommendation = recommendationTrend
        ?.childArray("trend")
        ?.mapNotNull(::toRecommendationPeriod)
        ?.let { periods -> periods.firstOrNull { it.period == "0m" } ?: periods.firstOrNull() }
    val price = parseQuoteSummaryPrice(body, diagnostics)
    val assetProfile = parseEmbeddedJsonObject(body, ASSET_PROFILE_MARKER, diagnostics)

    val marketPriceCents = financialData.rawMoney("currentPrice") ?: chartMarketPriceCents
    val intrinsicValueCents = financialData.rawMoney("targetMeanPrice")
    val fairValueCents = financialData.rawMoney("targetMedianPrice")
    val lowFairValueCents = financialData.rawMoney("targetLowPrice")
    val highFairValueCents = financialData.rawMoney("targetHighPrice")
    val profitable = statistics.rawDouble("trailingEps")?.let { it > 0.0 }
    val companyName = resolveCompanyName(
        body = body,
        symbol = symbol,
        assetProfile = assetProfile,
    )
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
        marketCapDollars = price.rawDouble("marketCap")?.toLong(),
        sharesOutstanding = statistics.rawDouble("sharesOutstanding")?.toLong(),
        trailingPeHundredths = statistics.rawDouble("trailingPE")?.times(100.0)?.roundToLong()?.toInt(),
        forwardPeHundredths = statistics.rawDouble("forwardPE")?.times(100.0)?.roundToLong()?.toInt(),
        priceToBookHundredths = statistics.rawDouble("priceToBook")?.times(100.0)?.roundToLong()?.toInt(),
        returnOnEquityBps = financialData.rawDouble("returnOnEquity")?.times(10_000.0)?.roundToLong()?.toInt(),
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
    ).takeIf(FundamentalSnapshot::hasAnyValues)

    val snapshot = if (marketPriceCents != null && intrinsicValueCents != null && profitable != null) {
        MarketSnapshot(
            symbol = symbol,
            companyName = companyName,
            profitable = profitable,
            marketPriceCents = marketPriceCents,
            intrinsicValueCents = intrinsicValueCents,
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
        assetProfile?.string("longName")?.let(::normalizeCompanyName),
        assetProfile?.string("shortName")?.let(::normalizeCompanyName),
        parseEmbeddedStringField(body, LONG_NAME_MARKER)?.let(::normalizeCompanyName),
    )
    return candidates.firstOrNull { candidate ->
        candidate.isNotBlank() && !candidate.equals(symbol, ignoreCase = true)
    }
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
    val normalizedTitle = normalizeCompanyName(title) ?: return null
    val pattern = Regex("(?i)\\s+\\(${Regex.escape(symbol)}\\)\\s+")
    val match = pattern.find(normalizedTitle) ?: return null
    return normalizedTitle.substring(0, match.range.first).trim().takeIf(String::isNotBlank)
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
        val value = obj["reportedValue"]?.jsonObject?.get("raw")?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        AnnualReportedValue(date, value)
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

private fun dollarsToCents(value: Double): Long? =
    value.takeIf { it.isFinite() && it > 0.0 }?.times(100.0)?.roundToLong()

private fun JsonObject?.child(name: String): JsonObject =
    this?.get(name)?.jsonObject ?: JsonObject(emptyMap())

private fun JsonObject?.childArray(name: String): JsonArray =
    this?.get(name)?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject?.string(name: String): String? =
    this?.get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject?.stringValue(name: String): String? =
    this?.get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject?.rawDouble(name: String): Double? =
    this?.get(name)?.jsonObject?.get("raw")?.jsonPrimitive?.doubleOrNull

private fun JsonObject?.rawInt(name: String): Int? =
    rawDouble(name)?.takeIf(Double::isFinite)?.roundToLong()?.toInt()

private fun JsonObject?.intValue(name: String): Int? =
    this?.get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject?.rawMoney(name: String): Long? =
    rawDouble(name)?.let(::dollarsToCents)

private val JsonPrimitive.intOrNull: Int?
    get() = contentOrNull?.toIntOrNull()

private val JsonPrimitive.longOrNull: Long?
    get() = contentOrNull?.toLongOrNull()

private val JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()
