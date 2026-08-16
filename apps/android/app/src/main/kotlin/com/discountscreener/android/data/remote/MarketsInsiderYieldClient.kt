package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.IssuerYieldLookup
import com.discountscreener.core.engine.IssuerYieldPoint
import com.discountscreener.core.engine.parseMarketsInsiderBondTable
import com.discountscreener.core.engine.parseMarketsInsiderBorrowerId
import com.discountscreener.core.engine.selectIssuerMarketYield
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private const val FINDER_URL = "https://markets.businessinsider.com/bonds/finder"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

class MarketsInsiderYieldClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(25))
        .build(),
    private val clock: () -> LocalDate = { LocalDate.now(ZoneId.of("America/New_York")) },
) : IssuerYieldLookup {
    override fun lookup(symbol: String, companyName: String?): IssuerYieldPoint? {
        var name = companyName?.trim().orEmpty()
        if (name.isBlank()) return null
        return runCatching {
            var finder = fetch(FINDER_URL)
            var borrower = parseMarketsInsiderBorrowerId(finder, name) ?: return@runCatching null
            var table = fetch("$FINDER_URL?borrower=$borrower")
            var quotes = parseMarketsInsiderBondTable(table)
            selectIssuerMarketYield(quotes, clock().toString())
        }.getOrElse { error ->
            Log.w("MarketsInsiderYield", "$symbol: ${error.message}")
            null
        }
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
