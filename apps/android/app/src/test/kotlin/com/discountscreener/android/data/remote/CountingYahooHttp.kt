package com.discountscreener.android.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A Yahoo that answers from fixtures and writes down what it was asked for.
 *
 * The fake clients in this package replace [YahooFinanceClient] itself, so they cannot see what the
 * real one does: the crumb handshake, the retry loop, and the second call the client makes when the
 * first answer is thin. This one sits under the real client instead, so every round trip the
 * shipped code makes is counted here.
 *
 * Latency is a `Thread.sleep`, because a socket blocks the thread it runs on. A `delay` would hide
 * exactly the starvation this probe is looking for.
 */
internal class CountingYahooHttp(
    private val latencyMillis: Long = DEFAULT_LATENCY_MILLIS,
    private val refuseAfter: Int = Int.MAX_VALUE,
    /**
     * A server with a real limit: it serves this many calls at once and answers the rest with 429,
     * with a `Retry-After` only when [retryAfterSeconds] says so. Yahoo sends none, measured on a
     * device on 2026-08-18. [refuseAfter] refuses by count, which is a quota; this refuses by
     * pressure, which is what a governor has to steer against.
     */
    private val concurrencyLimit: Int = Int.MAX_VALUE,
    private val retryAfterSeconds: Long? = null,
    /**
     * Symbols whose quote and chart are answered 503 every time, so the load carries them from round
     * to round as it does a real straggler.
     */
    private val brokenSymbols: Set<String> = emptySet(),
) {
    val calls = ConcurrentHashMap<String, AtomicInteger>()

    /** Every call as it arrived: what kind, for which symbol, at what wall-clock millisecond. */
    val requests = CopyOnWriteArrayList<LoggedRequest>()
    val refusals = AtomicInteger(0)
    val networkMillis = AtomicLong(0)
    val peakInFlight = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    private val served = AtomicInteger(0)

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> answer(chain) })
        .build()

    fun count(kind: String): Int = calls[kind]?.get() ?: 0

    fun total(): Int = calls.values.sumOf { it.get() }

    private fun answer(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var url = request.url.toString()
        var kind = kindOf(url)
        calls.getOrPut(kind) { AtomicInteger(0) }.incrementAndGet()
        requests += LoggedRequest(kind, symbolOf(url), System.currentTimeMillis())

        var now = inFlight.incrementAndGet()
        peakInFlight.getAndUpdate { seen -> maxOf(seen, now) }
        var started = System.currentTimeMillis()
        try {
            if (now > concurrencyLimit) {
                refusals.incrementAndGet()
                return response(request, code = 429, body = "rate limited")
            }
            Thread.sleep(latencyMillis)
            if (symbolOf(url) in brokenSymbols) {
                return response(request, code = 503, body = "service unavailable")
            }
            var refused = served.incrementAndGet() > refuseAfter
            if (refused) {
                refusals.incrementAndGet()
                return response(request, code = 429, body = "rate limited")
            }
            return response(request, code = 200, body = body(kind, url))
        } finally {
            networkMillis.addAndGet(System.currentTimeMillis() - started)
            inFlight.decrementAndGet()
        }
    }

    private fun kindOf(url: String): String = when {
        url.contains("/v7/finance/quote?") -> QUOTE_BATCH
        url.contains("/v10/finance/quoteSummary/") -> QUOTE_SUMMARY
        url.contains("/v8/finance/chart/") -> CHART
        url.contains("fundamentals-timeseries") -> TIMESERIES
        url.contains("getcrumb") -> CRUMB
        url.contains("finance.yahoo.com/quote/") -> QUOTE_HTML
        else -> BOOTSTRAP
    }

    private fun body(kind: String, url: String): String = when (kind) {
        CRUMB -> "fakeCrumb01"
        QUOTE_SUMMARY -> quoteSummaryFixture()
        QUOTE_BATCH -> quoteBatchBody(url)
        CHART -> chartBody(symbolOf(url))
        TIMESERIES -> EMPTY_TIMESERIES
        else -> "<html><body>ok</body></html>"
    }

    /** The symbol a chart, quote or timeseries call is about; blank for the crumb handshake. */
    private fun symbolOf(url: String): String = when (kindOf(url)) {
        CHART -> url.substringAfter("/chart/").substringBefore("?")
        QUOTE_SUMMARY -> url.substringAfter("/quoteSummary/").substringBefore("?")
        TIMESERIES -> url.substringAfter("/timeseries/").substringBefore("?")
        else -> ""
    }

    /** One call the server saw. [atMillis] is `System.currentTimeMillis()` when it arrived. */
    data class LoggedRequest(val kind: String, val symbol: String, val atMillis: Long)

    private fun response(request: okhttp3.Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(
            when (code) {
                200 -> "OK"
                429 -> "Too Many Requests"
                else -> "Service Unavailable"
            },
        )
        .apply {
            if (code == 429 && retryAfterSeconds != null) {
                header("Retry-After", retryAfterSeconds.toString())
            }
        }
        .body(body.toByteArray().toResponseBody(JSON_MEDIA_TYPE))
        .build()

    /** Fifty-two weekly closes, the shape [YahooFinanceClient.fetchHistoricalCandles] asks for. */
    private fun chartBody(symbol: String): String {
        var stamps = (0 until CANDLES).joinToString(",") { index -> (1_600_000_000L + index * 604_800L).toString() }
        var closes = (0 until CANDLES).joinToString(",") { index -> (100 + index % 20).toString() + ".0" }
        return """
            {"chart":{"result":[{"meta":{"symbol":"$symbol","longName":"$symbol Holdings"},
            "timestamp":[$stamps],
            "indicators":{"quote":[{"open":[$closes],"high":[$closes],"low":[$closes],
            "close":[$closes],"volume":[${(0 until CANDLES).joinToString(",") { "1000" }}]}]}}],"error":null}}
        """.trimIndent()
    }

    /**
     * The batch quote endpoint prices every symbol it is asked for, at [BATCH_PRICE_DOLLARS], the
     * shape the live endpoint sends: bare numbers, no `{raw, fmt}` pairs.
     */
    private fun quoteBatchBody(url: String): String {
        var symbols = url.substringAfter("symbols=").substringBefore("&").split("%2C", ",")
        var rows = symbols.joinToString(",") { symbol ->
            """{"symbol":"$symbol","regularMarketPrice":$BATCH_PRICE_DOLLARS,"longName":"$symbol Holdings"}"""
        }
        return """{"quoteResponse":{"result":[$rows],"error":null}}"""
    }

    private fun quoteSummaryFixture(): String = CountingYahooHttp::class.java
        .getResourceAsStream("/yahoo/quoteSummary/AAPL.json")
        ?.bufferedReader()?.use { reader -> reader.readText() }
        ?: error("quoteSummary fixture missing")

    companion object {
        const val QUOTE_SUMMARY = "quoteSummary"
        const val QUOTE_BATCH = "batch quote"
        /** The price every batch row carries; unlike the quoteSummary fixture's, so a test can tell which one a row shows. */
        const val BATCH_PRICE_DOLLARS = "77.25"
        const val CHART = "chart"
        const val TIMESERIES = "timeseries"
        const val CRUMB = "crumb + cookie bootstrap"
        const val QUOTE_HTML = "quote page (html)"
        const val BOOTSTRAP = "bootstrap"
        const val DEFAULT_LATENCY_MILLIS = 40L
        private const val CANDLES = 52
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val EMPTY_TIMESERIES = """{"timeseries":{"result":[],"error":null}}"""
    }
}
