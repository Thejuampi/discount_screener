package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AlphaVantageEarning(
    val fiscalEnd: LocalDate,
    val reportedOn: LocalDate?,
    val actualEps: Double?,
    val meanEps: Double?,
)

data class AlphaVantageEstimate(
    val fiscalEnd: LocalDate,
    val meanEps: Double,
    val lowEps: Double,
    val highEps: Double,
    val analystCount: Int?,
    val meanRevenue: Double?,
)

data class SueQuarter(
    val fiscalEnd: LocalDate,
    val reportedOn: LocalDate?,
    val actualEps: Double,
    val meanEps: Double,
    val lowEps: Double,
    val highEps: Double,
    val sueBps: Int,
    val revenueSurpriseBps: Int?,
)

private val AV_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

fun alphaVantageRefusal(body: String): String? {
    var root = readObject(body) ?: return "invalid_payload"
    var information = root.string("Information")
    if (information != null) {
        return if (information.contains("demo", ignoreCase = true)) "demo_key" else "refused"
    }
    var error = root.string("Error Message") ?: return null
    return if (error.contains("apikey", ignoreCase = true)) "missing_key" else "refused"
}

fun parseAlphaVantageEarnings(body: String): List<AlphaVantageEarning> {
    if (alphaVantageRefusal(body) != null) return emptyList()
    var root = readObject(body) ?: return emptyList()
    var rows = root["quarterlyEarnings"]?.jsonArray ?: return emptyList()
    return rows.mapNotNull { entry ->
        var block = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
        var fiscalEnd = block.date("fiscalDateEnding") ?: return@mapNotNull null
        AlphaVantageEarning(
            fiscalEnd = fiscalEnd,
            reportedOn = block.date("reportedDate"),
            actualEps = block.number("reportedEPS"),
            meanEps = block.number("estimatedEPS"),
        )
    }
}

fun parseAlphaVantageEstimates(body: String): List<AlphaVantageEstimate> {
    if (alphaVantageRefusal(body) != null) return emptyList()
    var root = readObject(body) ?: return emptyList()
    var rows = root["estimates"]?.jsonArray ?: return emptyList()
    return rows.mapNotNull { entry ->
        var block = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
        if (block.string("horizon") != "fiscal quarter") return@mapNotNull null
        var fiscalEnd = block.date("date") ?: return@mapNotNull null
        var mean = block.number("eps_estimate_average") ?: return@mapNotNull null
        var low = block.number("eps_estimate_low") ?: return@mapNotNull null
        var high = block.number("eps_estimate_high") ?: return@mapNotNull null
        AlphaVantageEstimate(
            fiscalEnd = fiscalEnd,
            meanEps = mean,
            lowEps = low,
            highEps = high,
            analystCount = block.number("eps_estimate_analyst_count")?.roundToInt(),
            meanRevenue = block.number("revenue_estimate_average"),
        )
    }
}

fun sueQuartersOf(earningsBody: String, estimatesBody: String): List<SueQuarter> =
    sueQuartersOf(parseAlphaVantageEarnings(earningsBody), parseAlphaVantageEstimates(estimatesBody))

fun sueQuartersOf(
    earnings: List<AlphaVantageEarning>,
    estimates: List<AlphaVantageEstimate>,
): List<SueQuarter> {
    var byFiscal = estimates.associateBy { it.fiscalEnd }
    return earnings.mapNotNull { row ->
        var estimate = byFiscal[row.fiscalEnd] ?: return@mapNotNull null
        var actual = row.actualEps ?: return@mapNotNull null
        var dispersion = abs(estimate.highEps - estimate.lowEps) / 2.0
        if (dispersion <= 0.0) return@mapNotNull null
        var sue = ((actual - estimate.meanEps) / dispersion * 10_000.0).roundToInt()
        SueQuarter(
            fiscalEnd = row.fiscalEnd,
            reportedOn = row.reportedOn,
            actualEps = actual,
            meanEps = estimate.meanEps,
            lowEps = estimate.lowEps,
            highEps = estimate.highEps,
            sueBps = sue,
            revenueSurpriseBps = null,
        )
    }
}

private fun readObject(body: String): JsonObject? =
    runCatching { AV_JSON.parseToJsonElement(body).jsonObject }.getOrNull()

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.date(name: String): LocalDate? =
    string(name)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun JsonObject.number(name: String): Double? {
    var text = string(name) ?: return null
    if (text.equals("None", ignoreCase = true) || text.equals("null", ignoreCase = true)) return null
    return text.toDoubleOrNull()?.takeIf { it.isFinite() }
}
