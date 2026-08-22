package com.discountscreener.android.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class YahooTnxClientTest {
    @Test
    fun chart_request_sends_the_yahoo_crumb() = runBlocking {
        var seen = mutableListOf<String>()
        var http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                var req = chain.request()
                seen += req.url.toString()
                var payload = when {
                    req.url.encodedPath.contains("getcrumb") -> "testcrumb"
                    req.url.encodedPath.contains("chart") -> """{"chart":{"result":[]}}"""
                    else -> "<html></html>"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        YahooTnxClient(http).chart()
        var chartUrl = seen.first { it.contains("chart") }
        assertEquals(true, chartUrl.contains("crumb=testcrumb"))
    }
}
