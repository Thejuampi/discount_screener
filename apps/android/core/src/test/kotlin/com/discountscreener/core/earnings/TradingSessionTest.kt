package com.discountscreener.core.earnings

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingSessionTest {

    @Test
    fun the_middle_of_a_weekday_session_quotes() {
        assertTrue(live(WEDNESDAY, LocalTime.of(12, 0)))
    }

    @Test
    fun the_opening_minute_quotes() {
        assertTrue(live(WEDNESDAY, MARKET_OPENS))
    }

    @Test
    fun the_minute_before_the_open_never_quotes() {
        assertFalse(live(WEDNESDAY, MARKET_OPENS.minusMinutes(1)))
    }

    @Test
    fun the_closing_minute_never_quotes() {
        assertFalse(live(WEDNESDAY, MARKET_CLOSES))
    }

    @Test
    fun the_last_minute_of_the_session_quotes() {
        assertTrue(live(WEDNESDAY, MARKET_CLOSES.minusMinutes(1)))
    }

    @Test
    fun a_saturday_never_quotes() {
        assertFalse(live(WEDNESDAY.plusDays(3), LocalTime.of(12, 0)))
    }

    @Test
    fun a_sunday_never_quotes() {
        assertFalse(live(WEDNESDAY.plusDays(4), LocalTime.of(12, 0)))
    }

    @Test
    fun the_session_is_read_in_exchange_time_never_in_the_zone_the_phone_sits_in() {
        assertTrue(quotesAreLive(WEDNESDAY.atTime(23, 0).atZone(TOKYO).toInstant()))
    }

    private fun live(date: LocalDate, time: LocalTime): Boolean =
        quotesAreLive(date.atTime(time).atZone(EXCHANGE_ZONE).toInstant())

    private companion object {
        val WEDNESDAY: LocalDate = LocalDate.of(2026, 8, 26)
        val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
