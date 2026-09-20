package com.discountscreener.core.portfolio

import com.discountscreener.core.earnings.EXCHANGE_ZONE
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.IsoFields

enum class Closeness {
    Today,
    Tomorrow,
    ThisWeek,
    Later,
    None,
}

fun closeness(today: LocalDate, report: LocalDate?): Closeness {
    if (report == null || report.isBefore(today)) return Closeness.None
    if (report == today) return Closeness.Today
    if (report == today.plusDays(1)) return Closeness.Tomorrow
    if (sameIsoWeek(today, report)) return Closeness.ThisWeek
    return Closeness.Later
}

fun closenessLabel(tag: Closeness): String? = when (tag) {
    Closeness.Today -> "Today"
    Closeness.Tomorrow -> "Tomorrow"
    Closeness.ThisWeek -> "This week"
    Closeness.Later -> "Later"
    Closeness.None -> null
}

fun nySessionDay(epochSeconds: Long): LocalDate =
    Instant.ofEpochSecond(epochSeconds).atZone(EXCHANGE_ZONE).toLocalDate()

fun formatLotShares(quantityTenThousandths: Long): String {
    var whole = quantityTenThousandths / QTY_SCALE
    var frac = quantityTenThousandths % QTY_SCALE
    if (frac == 0L) return whole.toString()
    var decimals = frac.toString().padStart(4, '0').trimEnd('0')
    return "$whole.$decimals"
}

private fun sameIsoWeek(a: LocalDate, b: LocalDate): Boolean =
    a.get(IsoFields.WEEK_BASED_YEAR) == b.get(IsoFields.WEEK_BASED_YEAR) &&
        a.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == b.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
