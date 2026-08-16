package com.discountscreener.core.engine

import com.discountscreener.core.math.medianOf
import com.discountscreener.core.model.FundamentalSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Approved US-GAAP/DEI annual facts for residual-income drivers.
 * SEC first. Missing facts stay missing. Does not invent payout, book, or ROE.
 */
object SecResidualFacts {
    private const val USD = "USD"
    private const val DEI_SHARES_OUT = "EntityCommonStockSharesOutstanding"
    private const val MIN_DURATION_DAYS = 325
    private const val MAX_DURATION_DAYS = 380
    private val ACCEPTED_FORMS = setOf("10-K", "10-K/A")
    private val BOOK_QNAMES = listOf(
        "StockholdersEquity",
        "CommonStockholdersEquity",
        "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
        "PartnersCapital",
        "MembersEquity",
    )
    private val NI_QNAMES = listOf(
        "NetIncomeLossAvailableToCommonStockholdersBasic",
        "NetIncomeLoss",
        "ProfitLoss",
    )
    private val DIVIDEND_QNAMES = listOf(
        "PaymentsOfDividendsCommonStock",
        "DividendsCommonStockCash",
        "PaymentsOfOrdinaryDividends",
        "PaymentsOfDividends",
        "CommonStockDividendsCash",
    )
    private const val NCI_INCLUSIVE_EQUITY =
        "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"
    private const val MINORITY_INTEREST = "MinorityInterest"
    private val SHARE_QNAMES = listOf(
        "WeightedAverageNumberOfDilutedSharesOutstanding",
        "WeightedAverageNumberOfSharesOutstandingBasic",
        "CommonStockSharesOutstanding",
    )

    val retainedQnames: Set<String> = BOOK_QNAMES.toSet() +
        NI_QNAMES +
        DIVIDEND_QNAMES +
        SHARE_QNAMES +
        setOf(DEI_SHARES_OUT, MINORITY_INTEREST)

    data class Drivers(
        val fiscalEnd: String,
        val bookEquityDollars: Double,
        val beginningBookEquityDollars: Double?,
        val netIncomeDollars: Double,
        val dividendsDollars: Double?,
        val shares: Double?,
        val bookValuePerShareCents: Long?,
        val returnOnEquityBps: Int,
        val retentionBps: Int?,
        val provenance: List<String>,
    )

    fun extract(companyFactsJson: String): Drivers? {
        var root = JSON.parseToJsonElement(companyFactsJson).jsonObject
        var facts = root["facts"]?.jsonObject ?: return null
        var gaap = facts["us-gaap"]?.jsonObject ?: JsonObject(emptyMap())
        var dei = facts["dei"]?.jsonObject ?: JsonObject(emptyMap())

        var ni = latestSeries(gaap, NI_QNAMES, USD, PeriodKind.Duration) ?: return null
        var bookChoice = selectBookSeries(gaap, ni.latest.end) ?: return null
        var book = bookChoice.series
        var latestBook = book.byEnd[ni.latest.end] ?: book.latest
        if (latestBook.end != ni.latest.end) return null
        var priorBook = priorObservation(book.sorted, ni.latest.end)
        var shares = selectShares(gaap, dei, ni.latest.end)
        var dividendSeries = latestSeries(gaap, DIVIDEND_QNAMES, USD, PeriodKind.Duration)
        var latestDividend = dividendSeries?.byEnd?.get(ni.latest.end)
        var recentRoes = recentRoeBps(ni.sorted, book.sorted)
        var roeBps = medianOf(recentRoes.map { it.toDouble() })?.roundToInt() ?: return null
        var shareCount = shares?.obs?.value?.takeIf { it > 0.0 }
        var bvpsCents = shareCount?.let { count ->
            var cents = ((latestBook.value / count) * 100.0).roundToLong()
            cents.takeIf { it > 0L }
        }
        var recentRetention = recentRetentionBps(ni.sorted, dividendSeries)
        var retention = if (recentRetention.size >= 2) {
            medianOf(recentRetention.map { it.toDouble() })?.roundToInt()
        } else {
            recentRetention.singleOrNull()
                ?: latestDividend?.let { retentionBps(ni.latest.value, it.value) }
        }
        var roeProvenance = roeProvenance(recentRoes, priorBook, latestBook)

        var provenance = buildList {
            add("source=sec_companyfacts")
            add(bookChoice.provenance)
            add("ni=${ni.qname}:${ni.latest.end}")
            if (shares != null) add("shares=${shares.qname}:${shares.obs.end}")
            else add("shares=missing_same_fy")
            add(roeProvenance)
            if (dividendSeries != null && latestDividend != null) add("dividends=${dividendSeries.qname}")
            else add("dividends=missing")
            add(
                when {
                    recentRetention.size >= 2 ->
                        "retention=median_derived:n=${recentRetention.size}"
                    retention != null -> "retention=derived:${retention}bps"
                    else -> "retention=missing"
                },
            )
        }
        return Drivers(
            fiscalEnd = ni.latest.end,
            bookEquityDollars = latestBook.value,
            beginningBookEquityDollars = priorBook?.value,
            netIncomeDollars = ni.latest.value,
            dividendsDollars = latestDividend?.value,
            shares = shareCount,
            bookValuePerShareCents = bvpsCents,
            returnOnEquityBps = roeBps,
            retentionBps = retention,
            provenance = provenance,
        )
    }

    data class Overlay(
        val fundamentals: FundamentalSnapshot,
        val sourcesTried: List<String>,
        val bookSource: String,
        val sharesSource: String,
        val roeSource: String,
        val retentionSource: String,
    )

    fun overlay(yahoo: FundamentalSnapshot, sec: Drivers?): Overlay {
        var secShares = sec?.shares?.takeIf { it > 0.0 }?.roundToLong()
        var shares = secShares ?: yahoo.sharesOutstanding
        var sharesSource = when {
            secShares != null -> ResidualFromDrivers.SOURCE_SEC
            yahoo.sharesOutstanding != null -> ResidualFromDrivers.SOURCE_YAHOO
            else -> "missing"
        }
        var secBvps = sec?.bookValuePerShareCents
            ?: shares?.takeIf { it > 0L }?.let { count ->
                sec?.let { drivers ->
                    ((drivers.bookEquityDollars / count.toDouble()) * 100.0).roundToLong().takeIf { it > 0L }
                }
            }
        var bookCents = secBvps ?: yahoo.bookValuePerShareCents
        var bookSource = when {
            secBvps != null -> ResidualFromDrivers.SOURCE_SEC
            yahoo.bookValuePerShareCents != null -> ResidualFromDrivers.SOURCE_YAHOO
            else -> "missing"
        }
        var roe = sec?.returnOnEquityBps ?: yahoo.returnOnEquityBps
        var roeSource = when {
            sec != null -> ResidualFromDrivers.SOURCE_SEC
            yahoo.returnOnEquityBps != null -> ResidualFromDrivers.SOURCE_YAHOO
            else -> "missing"
        }
        var retention = sec?.retentionBps ?: yahoo.retentionBps
        var retentionSource = when {
            sec?.retentionBps != null -> ResidualFromDrivers.SOURCE_SEC
            yahoo.retentionBps != null -> ResidualFromDrivers.SOURCE_YAHOO
            else -> "missing"
        }
        return Overlay(
            fundamentals = yahoo.copy(
                sharesOutstanding = shares,
                bookValuePerShareCents = bookCents,
                returnOnEquityBps = roe,
                retentionBps = retention,
            ),
            sourcesTried = listOf(ResidualFromDrivers.SOURCE_SEC, ResidualFromDrivers.SOURCE_YAHOO),
            bookSource = bookSource,
            sharesSource = sharesSource,
            roeSource = roeSource,
            retentionSource = retentionSource,
        )
    }

    private enum class PeriodKind { Duration, Instant }

    private data class Observation(
        val end: String,
        val value: Double,
        val filed: String,
    )

    private data class Series(
        val qname: String,
        val sorted: List<Observation>,
    ) {
        val latest: Observation get() = sorted.last()
        val byEnd: Map<String, Observation> get() = sorted.associateBy { it.end }
    }

    private data class BookChoice(val series: Series, val provenance: String)

    private fun selectBookSeries(gaap: JsonObject, niEnd: String): BookChoice? {
        var parent = series(gaap, "StockholdersEquity", USD, PeriodKind.Instant)
        if (parent != null && parent.byEnd.containsKey(niEnd)) {
            return BookChoice(parent, "book=${parent.qname}:$niEnd")
        }
        var common = series(gaap, "CommonStockholdersEquity", USD, PeriodKind.Instant)
        if (common != null && common.byEnd.containsKey(niEnd)) {
            return BookChoice(common, "book=${common.qname}:$niEnd")
        }
        var nci = series(gaap, NCI_INCLUSIVE_EQUITY, USD, PeriodKind.Instant) ?: return null
        if (!nci.byEnd.containsKey(niEnd)) return null
        var minority = series(gaap, MINORITY_INTEREST, USD, PeriodKind.Instant)
        if (minority == null || !minority.byEnd.containsKey(niEnd)) {
            return BookChoice(nci, "book=${nci.qname}:$niEnd")
        }
        var derived = nci.sorted.mapNotNull { observation ->
            var nciAmount = observation.value
            var minorityAmount = minority.byEnd[observation.end]?.value
            var parentAmount = if (minorityAmount != null) nciAmount - minorityAmount else nciAmount
            if (parentAmount > 0.0) Observation(observation.end, parentAmount, observation.filed) else null
        }
        if (derived.none { it.end == niEnd }) return null
        return BookChoice(
            Series("nci_less_minority", derived),
            "book=nci_less_minority:$niEnd",
        )
    }

    private data class NamedObservation(val qname: String, val obs: Observation)

    private fun selectShares(gaap: JsonObject, dei: JsonObject, end: String): NamedObservation? {
        for (name in SHARE_QNAMES) {
            var found = series(gaap, name, null, PeriodKind.Duration)?.byEnd?.get(end)
                ?: series(gaap, name, null, PeriodKind.Instant)?.byEnd?.get(end)
            if (found != null && found.value > 0.0) return NamedObservation(name, found)
        }
        var deiShares = series(dei, DEI_SHARES_OUT, null, PeriodKind.Instant)?.byEnd?.get(end)
        if (deiShares != null && deiShares.value > 0.0) {
            return NamedObservation(DEI_SHARES_OUT, deiShares)
        }
        return null
    }

    private fun latestSeries(
        gaap: JsonObject,
        names: List<String>,
        unit: String?,
        kind: PeriodKind,
    ): Series? {
        var best: Series? = null
        for (name in names) {
            var found = series(gaap, name, unit, kind) ?: continue
            var current = best
            if (current == null || found.latest.end > current.latest.end) {
                best = found
            }
        }
        return best
    }

    private fun series(
        taxonomy: JsonObject,
        qname: String,
        requiredUnit: String?,
        kind: PeriodKind,
    ): Series? {
        var units = taxonomy[qname]?.jsonObject?.get("units")?.jsonObject ?: return null
        var byEnd = linkedMapOf<String, Observation>()
        for ((unitName, values) in units) {
            if (requiredUnit != null && unitName != requiredUnit) continue
            for (element in values.jsonArray) {
                var obj = element.jsonObject
                var fp = obj["fp"]?.jsonPrimitive?.contentOrNull ?: continue
                var form = obj["form"]?.jsonPrimitive?.contentOrNull ?: continue
                if (fp != "FY" || form !in ACCEPTED_FORMS) continue
                if (obj["segment"] != null) continue
                var end = obj["end"]?.jsonPrimitive?.contentOrNull ?: continue
                var value = obj["val"]?.jsonPrimitive?.doubleOrNull ?: continue
                var start = obj["start"]?.jsonPrimitive?.contentOrNull
                if (kind == PeriodKind.Instant && start != null) continue
                if (kind == PeriodKind.Duration) {
                    var days = durationDays(start, end) ?: continue
                    if (days !in MIN_DURATION_DAYS..MAX_DURATION_DAYS) continue
                }
                var filed = obj["filed"]?.jsonPrimitive?.contentOrNull.orEmpty()
                var existing = byEnd[end]
                if (existing == null || filed >= existing.filed) {
                    byEnd[end] = Observation(end, value, filed)
                }
            }
        }
        if (byEnd.isEmpty()) return null
        return Series(qname, byEnd.values.sortedBy { it.end })
    }

    private const val RECENT_ROE_YEARS = 4

    private fun recentRoeBps(niSorted: List<Observation>, bookSorted: List<Observation>): List<Int> {
        return niSorted.takeLast(RECENT_ROE_YEARS).mapNotNull { observation ->
            var ending = bookSorted.firstOrNull { it.end == observation.end } ?: return@mapNotNull null
            var beginning = priorObservation(bookSorted, observation.end)
            roeBps(observation.value, beginning?.value, ending.value)
        }
    }

    private fun recentRetentionBps(niSorted: List<Observation>, dividends: Series?): List<Int> {
        if (dividends == null) return emptyList()
        var byEnd = dividends.byEnd
        return niSorted.takeLast(RECENT_ROE_YEARS).mapNotNull { observation ->
            var paid = byEnd[observation.end] ?: return@mapNotNull null
            retentionBps(observation.value, paid.value)
        }
    }

    private fun roeProvenance(
        recentRoes: List<Int>,
        priorBook: Observation?,
        latestBook: Observation,
    ): String {
        if (recentRoes.size >= 2) {
            return "roe=median_ni_over_beginning_book:n=${recentRoes.size}"
        }
        return if (priorBook != null) {
            "roe=ni_over_beginning_book:${priorBook.end}"
        } else {
            "roe=ni_over_ending_book:${latestBook.end}"
        }
    }

    private fun priorObservation(sorted: List<Observation>, end: String): Observation? {
        var idx = sorted.indexOfFirst { it.end == end }
        if (idx <= 0) return null
        return sorted[idx - 1]
    }

    private fun roeBps(ni: Double, beginningBook: Double?, endingBook: Double): Int? {
        var book = when {
            beginningBook != null && beginningBook > 0.0 -> beginningBook
            endingBook > 0.0 -> endingBook
            else -> return null
        }
        var roe = (ni / book) * 10_000.0
        if (!roe.isFinite()) return null
        var bps = roe.roundToInt()
        if (bps <= 0 || bps >= 10_000) return null
        return bps
    }

    private fun retentionBps(ni: Double, dividends: Double): Int? {
        if (ni <= 0.0 || !dividends.isFinite() || dividends < 0.0) return null
        var paid = minOf(dividends, ni)
        return (((ni - paid) / ni) * 10_000.0).roundToInt().coerceIn(0, 10_000)
    }

    private fun durationDays(start: String?, end: String): Int? {
        if (start == null) return null
        return runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)).toInt()
        }.getOrNull()
    }

    private val JSON = Json { ignoreUnknownKeys = true }
}
