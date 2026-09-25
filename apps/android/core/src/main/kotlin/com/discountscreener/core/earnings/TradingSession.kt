package com.discountscreener.core.earnings

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

val MARKET_OPENS: LocalTime = LocalTime.of(9, 30)
val MARKET_CLOSES: LocalTime = LocalTime.of(16, 0)

/**
 * Whether an option chain asked for right now would come back with prices on it.
 *
 * Outside regular trading hours every strike quotes a bid of zero, and a straddle built from zero
 * bids is not a priced move. An option chain is never republished, so a report that passes its
 * window unpriced is unpriced forever: the capture has to run while the market is open, and this
 * is the question it asks before it spends a request.
 *
 * A market holiday still reads as live here. The chain comes back unquoted, the straddle refuses,
 * and the cost is one wasted request rather than a wrong number.
 */
fun quotesAreLive(now: Instant): Boolean {
    var moment = now.atZone(EXCHANGE_ZONE)
    if (moment.dayOfWeek == DayOfWeek.SATURDAY || moment.dayOfWeek == DayOfWeek.SUNDAY) return false
    var time = moment.toLocalTime()
    return time >= MARKET_OPENS && time < MARKET_CLOSES
}
