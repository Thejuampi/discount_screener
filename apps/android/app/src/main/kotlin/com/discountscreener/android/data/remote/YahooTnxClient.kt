package com.discountscreener.android.data.remote

import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.time.Duration

private const val YAHOO_TNX_CHART_URL =
    "https://query1.finance.yahoo.com/v8/finance/chart/%5ETNX?range=5d&interval=1d&includePrePost=false"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

class YahooTnxClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(20))
        .cookieJar(JavaNetCookieJar(CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }))
        .build(),
) {
    private val session = YahooSession(httpClient = http, userAgent = USER_AGENT)

    fun chart(): String {
        var crumb = runBlocking { session.ensureCrumb() }
        var request = Request.Builder()
            .url("$YAHOO_TNX_CHART_URL&crumb=$crumb")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", "https://finance.yahoo.com")
            .header("Referer", "https://finance.yahoo.com/")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                var body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Yahoo ^TNX HTTP ${response.code}: ${body.take(120)}")
                }
                return body
            }
        } catch (error: IOException) {
            Log.w("MarketParams", "TNX fetch failed: ${error.message}")
            throw error
        }
    }
}
