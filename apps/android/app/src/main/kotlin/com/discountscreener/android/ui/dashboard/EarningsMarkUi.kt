package com.discountscreener.android.ui.dashboard

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The earnings date, turned into the one sentence a reader needs before acting on a score.
 *
 * This marks and never blocks. No bucket, no composite and no decision reads the date; the row
 * keeps whatever it scored and the person decides what to do about the report.
 */
internal data class EarningsMarkUi(
    val label: String,
    /** True inside [EARNINGS_SOON_DAYS], which is when [EARNINGS_SOON_NOTE] earns its space. */
    val soon: Boolean,
)

/**
 * Two weeks. Same window the price-path motive uses, kept equal so two parts of the app cannot
 * disagree about what "soon" means.
 */
internal const val EARNINGS_SOON_DAYS = 14L

internal const val EARNINGS_SOON_NOTE =
    "A report inside this window can move the price further than anything scored here. Yahoo's date " +
        "is an estimate until the company confirms it."

/**
 * Null when there is no date, and null again once the date has passed.
 *
 * A past date is kept by the ingestion so the field is never empty for a company that reports, and
 * it is dropped here because "the last report was three weeks ago" changes nothing a reader does
 * today. Days are counted between calendar dates rather than by dividing seconds, so a report
 * tomorrow morning reads as one day and not as zero.
 */
internal fun earningsMark(
    nextEarningsEpoch: Long?,
    nowEpochSeconds: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): EarningsMarkUi? {
    var epoch = nextEarningsEpoch ?: return null
    var today = Instant.ofEpochSecond(nowEpochSeconds).atZone(zone).toLocalDate()
    var date = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()
    var days = ChronoUnit.DAYS.between(today, date)
    if (days < 0L) return null
    var printed = date.format(EARNINGS_DATE_FORMAT)
    return EarningsMarkUi(
        label = when (days) {
            0L -> "Earnings today · $printed"
            1L -> "Earnings tomorrow · $printed"
            else -> "Earnings in $days days · $printed"
        },
        soon = days <= EARNINGS_SOON_DAYS,
    )
}

/** Pinned to [Locale.US] because the rest of the score tab is written in English. */
private val EARNINGS_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
