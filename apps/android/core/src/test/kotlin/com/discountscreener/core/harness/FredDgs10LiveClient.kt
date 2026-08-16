package com.discountscreener.core.harness

import com.discountscreener.core.engine.FredDgs10Parser
import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.engine.RF_SOURCE_FRED_DGS10
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

fun interface FredCsvTransport {
    fun csv(): String
}

class FixtureFredCsvTransport(
    private val body: String,
) : FredCsvTransport {
    override fun csv(): String = body
}

class HttpFredCsvTransport(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(5))
        .protocols(listOf(Protocol.HTTP_1_1))
        .build(),
    private val url: String = FRED_DGS10_CSV_URL,
) : FredCsvTransport {
    override fun csv(): String {
        var request = Request.Builder()
            .url(url)
            .header("User-Agent", "DiscountScreener-QuantHarness/1")
            .header("Accept", "text/csv,text/plain,*/*")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            var body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url: ${body.take(120)}")
            }
            return body
        }
    }
}

class FredDgs10LiveClient(
    private val transport: FredCsvTransport = HttpFredCsvTransport(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : QuantRatesLiveClient {
    override fun fetch(): QuantRatesBundle {
        var csv = transport.csv()
        var obs = FredDgs10Parser.latest(csv)
        var asOf = LocalDate.parse(obs.asOfDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        var params = MarketParams.observed(
            rfBps = obs.yieldBps,
            asOfEpochMillis = asOf,
            rfSource = RF_SOURCE_FRED_DGS10,
        )
        return QuantRatesBundle(
            marketParams = params,
            mode = QuantRatesMode.Live,
            asOfEpochMillis = clock(),
        )
    }
}

const val FRED_DGS10_CSV_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=DGS10"
