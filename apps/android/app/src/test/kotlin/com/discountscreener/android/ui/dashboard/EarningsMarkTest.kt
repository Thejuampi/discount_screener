package com.discountscreener.android.ui.dashboard

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The date is the only part of the score tab that goes stale on its own, so every case here pins
 * both clocks. A test that read the machine clock would pass today and fail in two weeks.
 */
class EarningsMarkTest {

    @Test
    fun a_symbol_with_no_earnings_date_gets_no_mark() {
        assertNull(mark(date = null))
    }

    /**
     * The ingestion keeps a past date on purpose, because a company that reports always has one.
     * The reader does not need it: a report that already happened moves no decision today.
     */
    @Test
    fun a_date_that_already_passed_gets_no_mark() {
        assertNull(mark(date = LocalDate.of(2026, 8, 17)))
    }

    @Test
    fun the_day_of_the_report_reads_as_today() {
        assertEquals("Earnings today · 18 Aug 2026", mark(LocalDate.of(2026, 8, 18))?.label)
    }

    @Test
    fun the_next_day_reads_as_tomorrow() {
        assertEquals("Earnings tomorrow · 19 Aug 2026", mark(LocalDate.of(2026, 8, 19))?.label)
    }

    @Test
    fun a_date_further_out_reads_as_a_day_count() {
        assertEquals("Earnings in 9 days · 27 Aug 2026", mark(LocalDate.of(2026, 8, 27))?.label)
    }

    /**
     * Two hours apart across midnight. Dividing seconds would call this "today"; the reader has one
     * evening to act on it, so it has to say tomorrow.
     */
    @Test
    fun a_report_just_after_midnight_reads_as_tomorrow_and_not_as_today() {
        var now = LocalDateTime.of(2026, 8, 18, 23, 0).toEpochSecond(ZoneOffset.UTC)
        var report = LocalDateTime.of(2026, 8, 19, 1, 0).toEpochSecond(ZoneOffset.UTC)

        assertEquals(
            "Earnings tomorrow · 19 Aug 2026",
            earningsMark(report, now, ZoneOffset.UTC)?.label,
        )
    }

    /** Day 14 is inside the window. Probed against day 15 below so the constant is pinned. */
    @Test
    fun the_last_day_of_the_window_is_still_soon() {
        assertEquals(true, mark(LocalDate.of(2026, 9, 1))?.soon)
    }

    @Test
    fun the_first_day_past_the_window_is_no_longer_soon() {
        assertEquals(false, mark(LocalDate.of(2026, 9, 2))?.soon)
    }

    /** A date outside the window still prints. Only the warning below it is conditional. */
    @Test
    fun a_date_outside_the_window_still_gets_a_mark() {
        assertEquals("Earnings in 60 days · 17 Oct 2026", mark(LocalDate.of(2026, 10, 17))?.label)
    }

    private fun mark(date: LocalDate?) = earningsMark(
        nextEarningsEpoch = date?.atStartOfDay(ZoneOffset.UTC)?.toEpochSecond(),
        nowEpochSeconds = NOW,
        zone = ZoneOffset.UTC,
    )

    private companion object {
        /** 2026-08-18, midday, so a date at start-of-day is still the same calendar day. */
        val NOW: Long = LocalDateTime.of(2026, 8, 18, 12, 0).toEpochSecond(ZoneOffset.UTC)
    }
}
