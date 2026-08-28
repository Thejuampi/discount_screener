package com.discountscreener.core.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RampTest {

    @Test
    fun at_the_lower_edge_is_minus_one() {
        assertEquals(-1.0, ramp(0.0, 0.0, 10.0))
    }

    @Test
    fun below_the_lower_edge_stays_minus_one() {
        assertEquals(-1.0, ramp(-100.0, 0.0, 10.0))
    }

    @Test
    fun at_the_upper_edge_is_plus_one() {
        assertEquals(1.0, ramp(10.0, 0.0, 10.0))
    }

    @Test
    fun above_the_upper_edge_stays_plus_one() {
        assertEquals(1.0, ramp(100.0, 0.0, 10.0))
    }

    @Test
    fun midpoint_is_zero() {
        assertEquals(0.0, ramp(5.0, 0.0, 10.0))
    }

    @Test
    fun inverted_band_is_missing() {
        assertNull(ramp(5.0, 10.0, 0.0))
    }
}
