package com.discountscreener.android.data.remote

import com.discountscreener.core.earnings.AlphaVantageBudget
import com.discountscreener.core.earnings.EarningsGatePolicy
import com.discountscreener.core.earnings.SueQuarter
import com.discountscreener.core.earnings.admitAlphaVantageCall
import com.discountscreener.core.earnings.alphaVantageRefusal
import com.discountscreener.core.earnings.sueQuartersOf
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

class AlphaVantageEarningsClient(
    private val cacheDir: File,
    private val keyFile: File,
    private val budgetFile: File,
    private val now: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val get: (String) -> String = Companion::httpGet,
) {
    fun saveKey(key: String) {
        keyFile.parentFile?.mkdirs()
        var trimmed = key.trim()
        if (trimmed.isEmpty()) {
            keyFile.delete()
        } else {
            keyFile.writeText(trimmed)
        }
    }

    fun quarters(symbol: String): List<SueQuarter> {
        var key = keyFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (key.isEmpty()) return emptyList()
        var earnings = fresh("EARNINGS", symbol)
        var estimates = fresh("EARNINGS_ESTIMATES", symbol)
        if (earnings != null && estimates != null) return sueQuartersOf(earnings, estimates)
        var needed = buildList {
            if (earnings == null) add("EARNINGS")
            if (estimates == null) add("EARNINGS_ESTIMATES")
        }
        var fetched = fetch(needed, symbol, key)
        var earningsBody = earnings ?: fetched["EARNINGS"] ?: stale("EARNINGS", symbol)
        var estimatesBody = estimates ?: fetched["EARNINGS_ESTIMATES"] ?: stale("EARNINGS_ESTIMATES", symbol)
        if (earningsBody == null || estimatesBody == null) return emptyList()
        return sueQuartersOf(earningsBody, estimatesBody)
    }

    private fun fresh(function: String, symbol: String): String? {
        var cached = cacheFile(function, symbol)
        if (!cached.isFile) return null
        var ageDays = TimeUnit.MILLISECONDS.toDays(now() * 1_000L - cached.lastModified())
        if (ageDays > EarningsGatePolicy.current.avCacheFreshDays.toLong()) return null
        return cached.readText()
    }

    private fun stale(function: String, symbol: String): String? =
        cacheFile(function, symbol).takeIf { it.isFile }?.readText()

    private fun fetch(functions: List<String>, symbol: String, key: String): Map<String, String> {
        if (functions.isEmpty()) return emptyMap()
        var admitted = admitAlphaVantageCall(readBudget(), now(), count = functions.size) ?: return emptyMap()
        writeBudget(admitted)
        return functions.mapNotNull { function ->
            var body = runCatching {
                get("https://www.alphavantage.co/query?function=$function&symbol=$symbol&apikey=$key")
            }.getOrNull() ?: return@mapNotNull null
            if (alphaVantageRefusal(body) != null) return@mapNotNull null
            writeCache(function, symbol, body)
            function to body
        }.toMap()
    }

    private fun writeCache(function: String, symbol: String, body: String) {
        var cached = cacheFile(function, symbol)
        cacheDir.mkdirs()
        var part = File(cached.parentFile, "${cached.name}.part")
        part.writeText(body)
        if (!part.renameTo(cached)) {
            cached.writeText(body)
            part.delete()
        }
    }

    private fun cacheFile(function: String, symbol: String) =
        File(cacheDir, "${symbol.uppercase()}-$function.json")

    private fun readBudget(): AlphaVantageBudget {
        if (!budgetFile.isFile) return AlphaVantageBudget()
        return runCatching { Json.decodeFromString(AlphaVantageBudget.serializer(), budgetFile.readText()) }
            .getOrDefault(AlphaVantageBudget())
    }

    private fun writeBudget(budget: AlphaVantageBudget) {
        budgetFile.parentFile?.mkdirs()
        budgetFile.writeText(Json.encodeToString(AlphaVantageBudget.serializer(), budget))
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(20))
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()

        private fun httpGet(url: String): String {
            var request = Request.Builder()
                .url(url)
                .header("User-Agent", "DiscountScreener-Android/1")
                .header("Accept", "application/json")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                var body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Alpha Vantage HTTP ${response.code}")
                return body
            }
        }
    }
}
