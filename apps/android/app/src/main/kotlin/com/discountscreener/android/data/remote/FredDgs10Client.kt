package com.discountscreener.android.data.remote

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.time.Duration

private const val FRED_DGS10_CSV_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=DGS10"
private const val USER_AGENT = "DiscountScreener-Android/1"

class FredDgs10Client(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(15))
        .protocols(listOf(Protocol.HTTP_1_1))
        .build(),
) {
    fun csv(): String {
        var request = Request.Builder()
            .url(FRED_DGS10_CSV_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/csv,text/plain,*/*")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                var body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("FRED DGS10 HTTP ${response.code}: ${body.take(120)}")
                }
                return body
            }
        } catch (error: IOException) {
            Log.w("MarketParams", "FRED DGS10 fetch failed: ${error.message}")
            throw error
        }
    }
}
