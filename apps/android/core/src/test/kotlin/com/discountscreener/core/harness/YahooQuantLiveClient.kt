package com.discountscreener.core.harness

import com.discountscreener.core.engine.YahooInterestSeries
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.time.Duration
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
private const val QUOTE_SUMMARY_URL = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/"
private const val TIMESERIES_URL =
    "https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/"
private const val QUOTE_SUMMARY_MODULES =
    "price,financialData,summaryDetail,defaultKeyStatistics,assetProfile,recommendationTrend"
private val CRUMB_URLS = listOf(
    "https://query2.finance.yahoo.com/v1/test/getcrumb",
    "https://query1.finance.yahoo.com/v1/test/getcrumb",
)
private val TIMESERIES_TYPES = listOf(
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
)

interface YahooTransport {
    fun quoteSummary(symbol: String): String
    fun timeseries(symbol: String): String?
}

class FixtureYahooTransport(
    private val classLoader: ClassLoader = FixtureYahooTransport::class.java.classLoader,
) : YahooTransport {
    override fun quoteSummary(symbol: String): String {
        var path = "yahoo/quoteSummary/${yahooRequestSymbol(symbol)}.json"
        var stream = classLoader.getResourceAsStream(path)
            ?: error("quote summary fixture missing for $symbol")
        return stream.bufferedReader().use { it.readText() }
    }

    override fun timeseries(symbol: String): String? {
        var path = "yahoo/timeseries/${yahooRequestSymbol(symbol)}.json"
        return classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
    }
}

class HttpYahooTransport(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(20))
        .cookieJar(JavaNetCookieJar(CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }))
        .addInterceptor(BROWSER_HEADERS)
        .build(),
) : YahooTransport {
    override fun quoteSummary(symbol: String): String {
        var crumb = ensureCrumb()
        var url = "$QUOTE_SUMMARY_URL${yahooRequestSymbol(symbol)}" +
            "?modules=$QUOTE_SUMMARY_MODULES&crumb=$crumb"
        return get(url)
    }

    override fun timeseries(symbol: String): String {
        var crumb = ensureCrumb()
        var types = TIMESERIES_TYPES.joinToString(",")
        var url = "$TIMESERIES_URL${yahooRequestSymbol(symbol)}" +
            "?type=$types&period1=1262304000&period2=2524608000&crumb=$crumb"
        return get(url)
    }

    private fun ensureCrumb(): String {
        get(
            url = "https://finance.yahoo.com/",
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            requireSuccess = false,
        )
        var seen = mutableListOf<String>()
        for (crumbUrl in CRUMB_URLS) {
            var body = try {
                get(crumbUrl, accept = "*/*").trim()
            } catch (error: IOException) {
                seen += "${error.message}"
                continue
            }
            if (body.isNotBlank() && !body.startsWith("{") && body.length <= 80) {
                return body
            }
            seen += "$crumbUrl -> ${body.take(80)}"
        }
        error("failed to obtain Yahoo crumb: ${seen.joinToString("; ")}")
    }

    private fun get(url: String, accept: String = "application/json,text/plain,*/*", requireSuccess: Boolean = true): String {
        var request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            var body = response.body?.string().orEmpty()
            if (requireSuccess && !response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url: ${body.take(120)}")
            }
            return body
        }
    }
}

private val BROWSER_HEADERS = Interceptor { chain ->
    var builder = chain.request().newBuilder()
    if (chain.request().header("User-Agent") == null) {
        builder.header("User-Agent", USER_AGENT)
    }
    if (chain.request().header("Accept-Language") == null) {
        builder.header("Accept-Language", "en-US,en;q=0.9")
    }
    chain.proceed(builder.build())
}

class YahooQuantLiveClient(
    private val transport: YahooTransport = HttpYahooTransport(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : QuantLiveClient {
    override fun fetch(symbol: String): QuantBundle {
        var quoteBody = transport.quoteSummary(symbol)
        var fundamentals = parseQuoteSummary(quoteBody, symbol)
        var timeseriesBody = transport.timeseries(symbol)
        var timeseries = timeseriesBody?.let(::parseTimeseries) ?: FundamentalTimeseries()
        return QuantBundle(
            symbol = symbol,
            fundamentals = fundamentals,
            timeseries = timeseries,
            mode = QuantDataMode.Live,
            asOfEpochMillis = clock(),
        )
    }
}

internal fun yahooRequestSymbol(symbol: String): String =
    symbol.trim().uppercase().replace('.', '-')

internal fun parseQuoteSummary(body: String, symbol: String): FundamentalSnapshot {
    var root = JSON.parseToJsonElement(body).jsonObject
    var result = root.child("quoteSummary").childArray("result").firstOrNull()?.jsonObject
        ?: error("quoteSummary result is empty for $symbol")
    var financialData = result.child("financialData")
    var summaryDetail = result.child("summaryDetail")
    var statistics = result.child("defaultKeyStatistics")
    var price = result.child("price")
    var assetProfile = result["assetProfile"]?.jsonObject
    var snapshot = FundamentalSnapshot(
        symbol = symbol,
        sectorKey = assetProfile.string("sectorKey"),
        sectorName = assetProfile.string("sectorDisp") ?: assetProfile.string("sector"),
        industryKey = assetProfile.string("industryKey"),
        industryName = assetProfile.string("industryDisp") ?: assetProfile.string("industry"),
        country = assetProfile.string("country"),
        marketCapDollars = price.rawDouble("marketCap")?.takeIf { it > 0.0 }?.toLong(),
        sharesOutstanding = statistics.rawDouble("sharesOutstanding")?.toLong(),
        trailingPeHundredths = statistics.rawDouble("trailingPE")?.times(100.0)?.roundToLong()?.toInt(),
        forwardPeHundredths = statistics.rawDouble("forwardPE")?.times(100.0)?.roundToLong()?.toInt(),
        priceToBookHundredths = statistics.rawDouble("priceToBook")?.times(100.0)?.roundToLong()?.toInt(),
        returnOnEquityBps = financialData.rawDouble("returnOnEquity")
            ?.takeIf { it.isFinite() }
            ?.times(10_000.0)
            ?.roundToLong()
            ?.toInt(),
        ebitdaDollars = financialData.rawDouble("ebitda")?.toLong(),
        enterpriseValueDollars = statistics.rawDouble("enterpriseValue")?.toLong(),
        enterpriseToEbitdaHundredths = statistics.rawDouble("enterpriseToEbitda")
            ?.times(100.0)?.roundToLong()?.toInt(),
        totalDebtDollars = financialData.rawDouble("totalDebt")?.toLong(),
        totalCashDollars = financialData.rawDouble("totalCash")?.toLong(),
        debtToEquityHundredths = financialData.rawDouble("debtToEquity")
            ?.times(100.0)?.roundToLong()?.toInt(),
        freeCashFlowDollars = financialData.rawDouble("freeCashflow")?.toLong(),
        operatingCashFlowDollars = financialData.rawDouble("operatingCashflow")?.toLong(),
        betaMillis = statistics.rawDouble("beta")?.times(1_000.0)?.roundToLong()?.toInt(),
        trailingEpsCents = statistics.rawDouble("trailingEps")?.times(100.0)?.roundToLong(),
        earningsGrowthBps = financialData.rawDouble("earningsGrowth")
            ?.times(10_000.0)?.roundToLong()?.toInt(),
        bookValuePerShareCents = statistics.rawDouble("bookValue")
            ?.takeIf { it > 0.0 }
            ?.times(100.0)
            ?.roundToLong(),
        retentionBps = resolveRetentionBps(financialData, summaryDetail),
    )
    if (!snapshot.hasAnyValues()) {
        error("fundamentals snapshot is empty for $symbol")
    }
    return snapshot
}

internal fun parseTimeseries(body: String): FundamentalTimeseries {
    var root = JSON.parseToJsonElement(body).jsonObject
    return FundamentalTimeseries(
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

private fun parseTimeseriesMetric(root: JsonObject, name: String): List<AnnualReportedValue> {
    var result = root.child("timeseries").childArray("result")
    var series = result.firstOrNull { it.jsonObject[name] != null }
        ?.jsonObject?.get(name)?.jsonArray
        .orEmpty()
    return series.mapNotNull { element ->
        var obj = element.jsonObject
        var date = obj["asOfDate"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        var value = obj["reportedValue"]?.jsonObject?.get("raw")?.jsonPrimitive?.doubleOrNull
            ?: return@mapNotNull null
        AnnualReportedValue(
            asOfDate = date,
            value = value,
            source = DcfSource.YahooFinance,
            concept = name,
        )
    }.sortedBy { it.asOfDate }
}

private fun resolveRetentionBps(financialData: JsonObject?, summaryDetail: JsonObject?): Int? {
    var payout = financialData.rawDouble("payoutRatio") ?: summaryDetail.rawDouble("payoutRatio")
    if (payout == null || !payout.isFinite() || payout < 0.0) return null
    if (payout >= 1.0) return 0
    return ((1.0 - payout) * 10_000.0).roundToInt()
}

private fun JsonObject?.child(name: String): JsonObject =
    this?.get(name)?.jsonObject ?: JsonObject(emptyMap())

private fun JsonObject?.childArray(name: String): JsonArray =
    this?.get(name)?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject?.string(name: String): String? =
    this?.get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject?.rawDouble(name: String): Double? {
    var node = this?.get(name) ?: return null
    var asObject = runCatching { node.jsonObject }.getOrNull()
    if (asObject != null) {
        return asObject["raw"]?.jsonPrimitive?.doubleOrNull
    }
    return runCatching { node.jsonPrimitive.doubleOrNull }.getOrNull()
}

private val JSON = Json { ignoreUnknownKeys = true }
