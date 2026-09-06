package com.discountscreener.core.earnings

import com.discountscreener.core.math.medianOf
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val MIN_EVENT_SHARE_BPS = 3_000
const val MIN_DAILY_SAMPLES = 20

fun tradingDaysBetween(from: LocalDate, to: LocalDate): Int {
    if (!to.isAfter(from)) return 0
    var day = from
    var count = 0
    while (day.isBefore(to)) {
        day = day.plusDays(1)
        if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) count++
    }
    return count
}

fun normalDailyMoveBps(closes: List<DailyClose>): Int? {
    var moves = closes.sortedBy { it.date }
        .zipWithNext()
        .filter { (before, after) -> before.closeCents > 0L && after.closeCents > 0L }
        .map { (before, after) -> abs(after.closeCents.toDouble() / before.closeCents - 1.0) * 10_000.0 }
    if (moves.size < MIN_DAILY_SAMPLES) return null
    return medianOf(moves)?.roundToInt()
}

fun eventMoveBps(totalMoveBps: Int?, normalDailyBps: Int?, tradingDaysToExpiry: Int): Int? {
    if (totalMoveBps == null) return null
    if (normalDailyBps == null || tradingDaysToExpiry <= 1) return totalMoveBps
    var total = totalMoveBps.toDouble()
    var quiet = normalDailyBps.toDouble() * sqrt((tradingDaysToExpiry - 1).toDouble())
    var floor = total * MIN_EVENT_SHARE_BPS / 10_000.0
    var event = total * total - quiet * quiet
    if (event <= 0.0) return floor.roundToInt()
    return maxOf(sqrt(event), floor).roundToInt()
}
