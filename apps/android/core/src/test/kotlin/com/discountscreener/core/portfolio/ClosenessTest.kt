package com.discountscreener.core.portfolio

import com.discountscreener.core.earnings.EXCHANGE_ZONE
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ClosenessTest {

    @Test
    fun close_today() {
        assertEquals(Closeness.Today, closeness(MONDAY, LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun close_tomorrow() {
        assertEquals(Closeness.Tomorrow, closeness(MONDAY, LocalDate.of(2026, 9, 8)))
    }

    @Test
    fun close_week() {
        assertEquals(Closeness.ThisWeek, closeness(MONDAY, LocalDate.of(2026, 9, 10)))
    }

    @Test
    fun close_later() {
        assertEquals(Closeness.Later, closeness(MONDAY, LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun close_sun_mon() {
        assertEquals(Closeness.Tomorrow, closeness(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun close_fri_mon() {
        assertEquals(Closeness.Later, closeness(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun close_sat_mon() {
        assertEquals(Closeness.Later, closeness(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun close_settled() {
        assertEquals(Closeness.None, closeness(MONDAY, LocalDate.of(2026, 9, 6)))
    }

    @Test
    fun close_missing() {
        assertEquals(Closeness.None, closeness(MONDAY, null))
    }

    @Test
    fun close_tz() {
        var prior = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        try {
            var nyEvening = LocalDateTime.of(2026, 9, 7, 23, 30)
                .atZone(EXCHANGE_ZONE)
                .toInstant()
                .epochSecond
            assertEquals(Closeness.Today, closeness(nySessionDay(nyEvening), MONDAY))
        } finally {
            TimeZone.setDefault(prior)
        }
    }

    @Test
    fun this_week_label_is_two_words() {
        assertEquals("This week", closenessLabel(Closeness.ThisWeek))
    }

    @Test
    fun none_has_no_label() {
        assertEquals(null, closenessLabel(Closeness.None))
    }

    @Test
    fun lot_shares_drop_trailing_zeros() {
        assertEquals(
            listOf("1273", "36.2954", "10"),
            listOf(12_730_000L, 362_954L, 100_000L).map { formatLotShares(it) },
        )
    }

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 9, 7)
    }
}
