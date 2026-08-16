package com.discountscreener.core.harness

import com.discountscreener.core.engine.IssuerYieldLookup
import com.discountscreener.core.engine.IssuerYieldPoint
import com.discountscreener.core.engine.parseMarketsInsiderBondTable
import com.discountscreener.core.engine.parseMarketsInsiderBorrowerId
import com.discountscreener.core.engine.selectIssuerMarketYield
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private const val FINDER_URL = "https://markets.businessinsider.com/bonds/finder"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

class MarketsInsiderYieldLiveClient(
    private val cacheDir: Path,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(25))
        .build(),
    private val clock: () -> LocalDate = { LocalDate.now(ZoneId.of("America/New_York")) },
) : IssuerYieldLookup {
    override fun lookup(symbol: String, companyName: String?): IssuerYieldPoint? {
        var name = companyName?.trim().orEmpty()
        if (name.isBlank()) return null
        Files.createDirectories(cacheDir)
        var packPath = cacheDir.resolve("${symbol.uppercase()}.json")
        if (Files.exists(packPath)) {
            var pack = JSON.decodeFromString<YieldCachePack>(Files.readString(packPath))
            if (pack.status == "empty") return null
            var bps = pack.yieldBps ?: return null
            return IssuerYieldPoint(yieldBps = bps, concept = pack.concept ?: "IssuerInstrumentYield")
        }
        var point = runCatching { fetchPoint(name) }.getOrElse { error ->
            Files.writeString(
                cacheDir.resolve("${symbol.uppercase()}.error.txt"),
                error.message ?: "yield fetch failed",
            )
            return null
        }
        var pack = if (point == null) {
            YieldCachePack(status = "empty")
        } else {
            YieldCachePack(status = "hit", yieldBps = point.yieldBps, concept = point.concept)
        }
        Files.writeString(packPath, JSON.encodeToString(pack))
        return point
    }

    private fun fetchPoint(companyName: String): IssuerYieldPoint? {
        var finder = finderHtml()
        var borrower = parseMarketsInsiderBorrowerId(finder, companyName) ?: return null
        var table = fetch("$FINDER_URL?borrower=$borrower")
        var quotes = parseMarketsInsiderBondTable(table)
        return selectIssuerMarketYield(quotes, clock().toString())
    }

    private fun finderHtml(): String {
        var path = cacheDir.resolve("finder.html")
        if (Files.exists(path)) return Files.readString(path)
        var body = fetch(FINDER_URL)
        Files.writeString(path, body)
        return body
    }

    private fun fetch(url: String): String {
        var request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            var body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Markets Insider HTTP ${response.code} for $url" }
            return body
        }
    }
}

@Serializable
private data class YieldCachePack(
    val status: String,
    val yieldBps: Int? = null,
    val concept: String? = null,
)

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
