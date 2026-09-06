package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ReportedQuarter(
    val quarterEndDate: LocalDate,
    val epsActual: Double?,
    val epsEstimate: Double?,
    val revenueActual: Double?,
)

fun reportedQuartersOf(root: JsonObject): List<ReportedQuarter> {
    var result = root["quoteSummary"]?.jsonObject
        ?.get("result")?.jsonArray?.firstOrNull()?.jsonObject
        ?: return emptyList()
    var revenue = revenueByQuarter(result)
    var history = result["earningsHistory"]?.jsonObject
        ?.get("history")?.jsonArray
        ?: return revenue.map { (date, amount) -> ReportedQuarter(date, null, null, amount) }
            .sortedBy { it.quarterEndDate }
    return history
        .mapNotNull { entry ->
            var block = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            var date = block.date("quarter") ?: return@mapNotNull null
            ReportedQuarter(
                quarterEndDate = date,
                epsActual = block.raw("epsActual"),
                epsEstimate = block.raw("epsEstimate"),
                revenueActual = revenue[date],
            )
        }
        .sortedBy { it.quarterEndDate }
}

fun quarterReportedOn(quarters: List<ReportedQuarter>, reportDate: LocalDate): ReportedQuarter? =
    quarters
        .filter { it.quarterEndDate <= reportDate }
        .maxByOrNull { it.quarterEndDate }
        ?.takeIf { reportDate.toEpochDay() - it.quarterEndDate.toEpochDay() <= MAX_REPORT_LAG_DAYS }

private const val MAX_REPORT_LAG_DAYS = 120L

private fun revenueByQuarter(result: JsonObject): Map<LocalDate, Double> =
    result["incomeStatementHistoryQuarterly"]?.jsonObject
        ?.get("incomeStatementHistory")?.jsonArray
        ?.mapNotNull { entry ->
            var block = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            var date = block.date("endDate") ?: return@mapNotNull null
            var amount = block.raw("totalRevenue")?.takeIf { it > 0.0 } ?: return@mapNotNull null
            date to amount
        }
        ?.toMap()
        .orEmpty()

private fun JsonObject.date(name: String): LocalDate? =
    this[name]?.let { runCatching { it.jsonObject["fmt"]?.jsonPrimitive?.content }.getOrNull() }
        ?.let { text -> runCatching { LocalDate.parse(text) }.getOrNull() }

private fun JsonObject.raw(name: String): Double? = this[name]?.rawDouble()

private fun JsonElement.rawDouble(): Double? {
    var wrapped = runCatching { jsonObject["raw"] }.getOrNull()
    return runCatching { (wrapped ?: this).jsonPrimitive.doubleOrNull }.getOrNull()
        ?.takeIf { it.isFinite() }
}
