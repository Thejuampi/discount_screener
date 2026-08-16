package com.discountscreener.core.harness

import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.engine.RF_SOURCE_YAHOO_TNX
import com.discountscreener.core.engine.YahooTnxParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration

fun interface YahooTnxTransport {
    fun chart(): String
}

class HttpYahooTnxTransport(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(20))
        .build(),
) : YahooTnxTransport {
    override fun chart(): String {
        var request = Request.Builder()
            .url(YAHOO_TNX_CHART_URL)
            .header("User-Agent", "DiscountScreener-QuantHarness/1")
            .header("Accept", "application/json")
            .header("Origin", "https://finance.yahoo.com")
            .header("Referer", "https://finance.yahoo.com/")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            var body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $YAHOO_TNX_CHART_URL: ${body.take(120)}")
            }
            return body
        }
    }
}

class YahooTnxLiveClient(
    private val transport: YahooTnxTransport = HttpYahooTnxTransport(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : QuantRatesLiveClient {
    override fun fetch(): QuantRatesBundle {
        var json = transport.chart()
        var obs = YahooTnxParser.parse(json)
        var asOf = obs.asOfEpochSeconds?.let { it * 1_000L } ?: clock()
        var params = MarketParams.observed(
            rfBps = obs.yieldBps,
            asOfEpochMillis = asOf,
            rfSource = RF_SOURCE_YAHOO_TNX,
        )
        return QuantRatesBundle(
            marketParams = params,
            mode = QuantRatesMode.Live,
            asOfEpochMillis = clock(),
        )
    }
}

class FredThenTnxRatesClient(
    private val fred: QuantRatesLiveClient = FredDgs10LiveClient(),
    private val tnx: QuantRatesLiveClient = YahooTnxLiveClient(),
) : QuantRatesLiveClient {
    override fun fetch(): QuantRatesBundle =
        runCatching { fred.fetch() }.getOrElse { tnx.fetch() }
}

const val YAHOO_TNX_CHART_URL =
    "https://query1.finance.yahoo.com/v8/finance/chart/%5ETNX?range=5d&interval=1d&includePrePost=false"
