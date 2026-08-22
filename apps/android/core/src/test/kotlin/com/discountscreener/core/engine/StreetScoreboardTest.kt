package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class StreetScoreboardTest {
    @Test
    fun honest_ape_is_absolute_gap_over_street() {
        assertEquals(0.5, StreetScoreboard.ape(15_000L, 10_000L))
    }

    @Test
    fun missing_identity_has_no_ape() {
        assertEquals(null, StreetScoreboard.ape(null, 10_000L))
    }

    @Test
    fun blank_format_when_ape_is_missing() {
        assertEquals("", StreetScoreboard.formatApe(null))
    }
}
