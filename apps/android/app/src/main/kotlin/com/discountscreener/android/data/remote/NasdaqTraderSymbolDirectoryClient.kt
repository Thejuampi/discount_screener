package com.discountscreener.android.data.remote

import com.discountscreener.android.data.profile.NasdaqSymbolDirectoryParser
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the official NASDAQ Trader US symbol directory over HTTPS and returns
 * Yahoo-compatible equity symbols (non-ETF, non-test).
 */
open class NasdaqTraderSymbolDirectoryClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(45))
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofSeconds(45))
        .build(),
) {
    open suspend fun fetchUsEquitySymbols(): List<String> = withContext(Dispatchers.IO) {
        val nasdaqBody = downloadText(NASDAQ_LISTED_URL)
        val otherBody = downloadText(OTHER_LISTED_URL)
        NasdaqSymbolDirectoryParser.mergeYahooSymbols(
            NasdaqSymbolDirectoryParser.parseNasdaqListed(nasdaqBody),
            NasdaqSymbolDirectoryParser.parseOtherListed(otherBody),
        ).also { symbols ->
            if (symbols.size < MIN_ACCEPTABLE_SYMBOLS) {
                throw IOException(
                    "NASDAQ symbol directory too small (${symbols.size}); expected at least $MIN_ACCEPTABLE_SYMBOLS",
                )
            }
        }
    }

    private fun downloadText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/plain,*/*")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching $url")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw IOException("empty body from $url")
            }
            return body
        }
    }

    companion object {
        const val NASDAQ_LISTED_URL =
            "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt"
        const val OTHER_LISTED_URL =
            "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt"
        private const val USER_AGENT =
            "DiscountScreener/1.0 (Android Discovery universe; +https://github.com/local/discount_screener)"
        private const val MIN_ACCEPTABLE_SYMBOLS = 1_000
    }
}
