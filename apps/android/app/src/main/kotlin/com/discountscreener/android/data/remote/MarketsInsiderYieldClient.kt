package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.IssuerYieldLookup
import com.discountscreener.core.engine.IssuerYieldPoint
import com.discountscreener.core.engine.MarketsInsiderBorrower
import com.discountscreener.core.engine.parseMarketsInsiderBondTable
import com.discountscreener.core.engine.parseMarketsInsiderBorrowerOptions
import com.discountscreener.core.engine.selectIssuerMarketYield
import com.discountscreener.core.engine.selectMarketsInsiderBorrowerId
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private const val FINDER_URL = "https://markets.businessinsider.com/bonds/finder"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
private const val BORROWERS_TTL_MILLIS = 60L * 60L * 1000L

class MarketsInsiderYieldClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(25))
        .build(),
    private val clock: () -> LocalDate = { LocalDate.now(ZoneId.of("America/New_York")) },
) : IssuerYieldLookup {
    private val borrowersLock = Any()
    private var borrowers: List<MarketsInsiderBorrower>? = null
    private var borrowersAtMillis = 0L

    override fun lookup(symbol: String, companyName: String?): IssuerYieldPoint? {
        var name = companyName?.trim().orEmpty()
        if (name.isBlank()) return null
        return runCatching {
            var borrower = selectMarketsInsiderBorrowerId(loadBorrowers(), name)
                ?: return@runCatching null
            var table = fetch("$FINDER_URL?borrower=$borrower")
            var quotes = parseMarketsInsiderBondTable(table)
            selectIssuerMarketYield(quotes, clock().toString())
        }.getOrElse { error ->
            Log.w("MarketsInsiderYield", "$symbol: ${error.message}")
            null
        }
    }

    // The finder page is the same for every symbol, and a refresh asks for hundreds of symbols
    // at once. One fetch and one parse serve them all; the lock holds the fan-out to a single
    // download on a cold cache.
    private fun loadBorrowers(): List<MarketsInsiderBorrower> = synchronized(borrowersLock) {
        var cached = borrowers
        var now = System.currentTimeMillis()
        if (cached != null && now - borrowersAtMillis < BORROWERS_TTL_MILLIS) return cached
        var parsed = parseMarketsInsiderBorrowerOptions(fetch(FINDER_URL))
        borrowers = parsed
        borrowersAtMillis = now
        parsed
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
