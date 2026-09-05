package com.discountscreener.core.earnings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

data class ConsensusEstimate(
    val period: String,
    val periodEndDate: LocalDate?,
    val avgEps: Double?,
    val lowEps: Double?,
    val highEps: Double?,
    val analystCount: Int?,
    val avgRevenue: Double?,
)

const val CURRENT_QUARTER = "0q"

fun consensusOf(root: JsonObject, period: String = CURRENT_QUARTER): ConsensusEstimate? {
    var trend = root["quoteSummary"]?.jsonObject
        ?.get("result")?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("earningsTrend")?.jsonObject
        ?.get("trend")?.jsonArray
        ?: return null
    var block = trend
        .map { it.jsonObject }
        .firstOrNull { it["period"]?.jsonPrimitive?.content == period }
        ?: return null
    var earnings = block["earningsEstimate"]?.jsonObject
    var revenue = block["revenueEstimate"]?.jsonObject
    return ConsensusEstimate(
        period = period,
        periodEndDate = block["endDate"]?.jsonPrimitive?.content?.let(::parseDate),
        avgEps = earnings.raw("avg"),
        lowEps = earnings.raw("low"),
        highEps = earnings.raw("high"),
        analystCount = earnings?.get("numberOfAnalysts")?.rawInt(),
        avgRevenue = revenue.raw("avg"),
    )
}

private fun JsonObject?.raw(name: String): Double? = this?.get(name)?.rawDouble()

private fun JsonElement.rawDouble(): Double? {
    var wrapped = runCatching { jsonObject["raw"] }.getOrNull()
    var value = runCatching { (wrapped ?: this).jsonPrimitive.doubleOrNull }.getOrNull()
    return value?.takeIf { it.isFinite() }
}

private fun JsonElement.rawInt(): Int? {
    var wrapped = runCatching { jsonObject["raw"] }.getOrNull()
    return runCatching { (wrapped ?: this).jsonPrimitive.intOrNull }.getOrNull()
}

private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
