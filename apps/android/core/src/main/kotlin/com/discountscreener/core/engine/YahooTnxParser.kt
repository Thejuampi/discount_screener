package com.discountscreener.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

data class YahooTnxObservation(
    val yieldBps: Int,
    val asOfEpochSeconds: Long?,
)

object YahooTnxParser {
    fun parse(body: String): YahooTnxObservation {
        var root = JSON.parseToJsonElement(body).jsonObject
        var result = root["chart"]?.jsonObject
            ?.get("result")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: throw IllegalArgumentException("Yahoo ^TNX chart result is empty")
        var meta = result["meta"] as? JsonObject
        var yieldPct = meta?.get("regularMarketPrice")?.jsonPrimitive?.doubleOrNull
            ?: lastClose(result)
            ?: throw IllegalArgumentException("Yahoo ^TNX yield is missing")
        var bps = (yieldPct * 100.0).roundToInt()
        if (bps < FredDgs10Parser.MIN_YIELD_BPS || bps > FredDgs10Parser.MAX_YIELD_BPS) {
            throw IllegalArgumentException("Yahoo ^TNX yield out of range: $bps bps")
        }
        var asOf = meta?.get("regularMarketTime")?.jsonPrimitive?.content?.toLongOrNull()
        return YahooTnxObservation(yieldBps = bps, asOfEpochSeconds = asOf)
    }

    private fun lastClose(result: JsonObject): Double? {
        var closes = result["indicators"]?.jsonObject
            ?.get("quote")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("close")
            ?.jsonArray
            ?: return null
        return closes.asReversed().firstNotNullOfOrNull { el ->
            el.jsonPrimitive.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }
        }
    }
}

private val JSON = Json { ignoreUnknownKeys = true }
