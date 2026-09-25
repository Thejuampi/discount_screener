package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.earnings.CAPTURE_WINDOW_DAYS
import com.discountscreener.core.portfolio.closeness
import com.discountscreener.core.portfolio.closenessLabel
import com.discountscreener.core.portfolio.nySessionDay
import java.time.LocalDate

sealed class DetailEarningsUi {
    data object Loading : DetailEarningsUi()
    data class Priced(val events: List<EarningsEventRowUi>) : DetailEarningsUi()
    data class Scheduled(
        val symbol: String,
        val reportDate: String,
        val closenessLabel: String,
        val body: String,
    ) : DetailEarningsUi()
    data class Quiet(val title: String, val body: String) : DetailEarningsUi()
}

const val QUIET_EARNINGS_TITLE = "No report on the calendar"
const val QUIET_EARNINGS_BODY =
    "This name has no upcoming earnings date. The gate prices a move only after a report is dated."
const val SCHEDULED_LATER_BODY =
    "The next report is on the calendar. The gate has not priced this name yet. A capture pass has to land with the market open."
const val SCHEDULED_FAR_BODY =
    "The next report is on the calendar. The gate prices the option chain inside $CAPTURE_WINDOW_DAYS days of that print."

fun presentDetailEarnings(
    symbol: String,
    events: List<EarningsEventRowUi>,
    calendarEpoch: Long?,
    scoreEpoch: Long?,
    today: LocalDate,
    loading: Boolean,
): DetailEarningsUi {
    if (events.isNotEmpty()) return DetailEarningsUi.Priced(events)
    var epoch = calendarEpoch ?: scoreEpoch
    var day = epoch?.let(::nySessionDay)
    var tag = closeness(today, day)
    var label = closenessLabel(tag)
    if (day != null && label != null) {
        var days = day.toEpochDay() - today.toEpochDay()
        return DetailEarningsUi.Scheduled(
            symbol = symbol.trim().uppercase(),
            reportDate = day.toString(),
            closenessLabel = label,
            body = if (days > CAPTURE_WINDOW_DAYS) SCHEDULED_FAR_BODY else SCHEDULED_LATER_BODY,
        )
    }
    if (loading) return DetailEarningsUi.Loading
    return DetailEarningsUi.Quiet(title = QUIET_EARNINGS_TITLE, body = QUIET_EARNINGS_BODY)
}
