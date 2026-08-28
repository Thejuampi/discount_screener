package com.discountscreener.core.earnings

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val RESULTS_OF_OPERATIONS_ITEM = "2.02"
const val EARNINGS_FORM = "8-K"

private val EDGAR_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private val MARKET_OPENS: LocalTime = LocalTime.of(9, 30)
private val MARKET_CLOSES: LocalTime = LocalTime.of(16, 0)

data class EarningsAnnouncement(val date: LocalDate, val timing: ReportTiming)

fun parseEarningsAnnouncements(body: String): List<EarningsAnnouncement> {
    var recent = readObject(body)
        ?.get("filings")?.jsonObject
        ?.get("recent")?.jsonObject
        ?: return emptyList()
    var forms = column(recent, "form")
    var dates = column(recent, "filingDate")
    var items = column(recent, "items")
    var stamps = column(recent, "acceptanceDateTime")
    var size = minOf(forms.size, dates.size, items.size)
    if (size == 0) return emptyList()
    var found = ArrayList<EarningsAnnouncement>(size)
    for (index in 0 until size) {
        if (forms[index] != EARNINGS_FORM) continue
        if (!carriesResults(items[index])) continue
        var accepted = acceptedAt(stamps.getOrNull(index))
        var date = accepted?.toLocalDate()
            ?: runCatching { LocalDate.parse(dates[index]) }.getOrNull()
            ?: continue
        found.add(EarningsAnnouncement(date, timingOf(accepted)))
    }
    return found.distinctBy { it.date }.sortedBy { it.date }
}

fun pastAbnormalReturnsOf(
    announcements: List<EarningsAnnouncement>,
    symbolCloses: List<DailyClose>,
    marketCloses: List<DailyClose>,
): List<Int> {
    if (symbolCloses.isEmpty() || marketCloses.isEmpty()) return emptyList()
    var stock = symbolCloses.sortedBy { it.date }
    var market = marketCloses.sortedBy { it.date }
    return announcements.mapNotNull { event ->
        var own = reactionOf(stock, event.date, event.timing) ?: return@mapNotNull null
        var index = reactionOf(market, event.date, event.timing) ?: return@mapNotNull null
        own - index
    }
}

private fun carriesResults(items: String): Boolean =
    items.split(',').any { it.trim() == RESULTS_OF_OPERATIONS_ITEM }

private fun acceptedAt(stamp: String?): ZonedDateTime? =
    stamp?.let { runCatching { Instant.parse(it) }.getOrNull() }?.atZone(EXCHANGE_ZONE)

private fun timingOf(accepted: ZonedDateTime?): ReportTiming {
    var time = accepted?.toLocalTime() ?: return ReportTiming.Unknown
    return when {
        time < MARKET_OPENS -> ReportTiming.BeforeOpen
        time >= MARKET_CLOSES -> ReportTiming.AfterClose
        else -> ReportTiming.Unknown
    }
}

private fun column(recent: JsonObject, name: String): List<String> =
    recent[name]?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
        ?: emptyList()

private fun readObject(body: String): JsonObject? =
    runCatching { EDGAR_JSON.parseToJsonElement(body).jsonObject }.getOrNull()
