package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RsiPlainReadingTest {
    @Test
    fun null_inputs_need_more_candles() {
        assertEquals("RSI needs more candles.", plainRsiReading(null, null, null))
    }

    @Test
    fun hot_upside_building_warns_against_chase() {
        val reading = plainRsiReading(level = 82.0, slope = 1.4, acceleration = 0.4)
        assertTrue(
            reading.contains("Don't chase", ignoreCase = true) ||
                reading.contains("late and hot", ignoreCase = true),
            "expected chase warning or late-hot language; got: $reading",
        )
    }

    @Test
    fun high_level_stall_uses_fade_or_reverse_watch() {
        val reading = plainRsiReading(level = 78.0, slope = 0.6, acceleration = -0.35)
        assertTrue(
            reading.contains("stall", ignoreCase = true) ||
                reading.contains("fad", ignoreCase = true) ||
                reading.contains("reverse", ignoreCase = true),
            "expected stall/fade/reverse language; got: $reading",
        )
    }

    @Test
    fun deep_selloff_building_stays_out() {
        val reading = plainRsiReading(level = 22.0, slope = -1.2, acceleration = -0.35)
        assertTrue(
            reading.contains("Stay out", ignoreCase = true) ||
                reading.contains("knife", ignoreCase = true) ||
                reading.contains("worse", ignoreCase = true),
            "expected stay-out / knife / worse language; got: $reading",
        )
    }

    @Test
    fun recovery_from_weak_level_watches_bounce() {
        val reading = plainRsiReading(level = 28.0, slope = 0.9, acceleration = 0.2)
        assertTrue(
            reading.contains("Watch", ignoreCase = true) ||
                reading.contains("bounce", ignoreCase = true) ||
                reading.contains("take back", ignoreCase = true) ||
                reading.contains("hold", ignoreCase = true),
            "expected recovery / watch language; got: $reading",
        )
    }

    @Test
    fun quiet_mid_has_no_reason_to_act() {
        val reading = plainRsiReading(level = 50.0, slope = 0.02, acceleration = 0.01)
        assertTrue(
            reading.contains("Quiet", ignoreCase = true) ||
                reading.contains("No clear edge", ignoreCase = true) ||
                reading.contains("No RSI reason", ignoreCase = true),
            "expected quiet / no-edge language; got: $reading",
        )
    }

    @Test
    fun non_null_reading_includes_action_cue() {
        val reading = plainRsiReading(level = 60.0, slope = 0.5, acceleration = 0.1)
        val hasAction = listOf(
            "chase",
            "watch",
            "stay out",
            "ride",
            "act",
            "avoid",
            "wait",
            "size",
        ).any { token -> reading.contains(token, ignoreCase = true) }
        assertTrue(hasAction, "expected an action cue; got: $reading")
    }

    @Test
    fun reading_avoids_trader_jargon() {
        val reading = plainRsiReading(level = 65.0, slope = 0.4, acceleration = -0.15)
        val banned = listOf("overbought", "oversold", "momentum", "acceleration", "bullish", "bearish", "slope")
        val hit = banned.firstOrNull { token -> reading.contains(token, ignoreCase = true) }
        assertEquals(null, hit, "banned jargon '$hit' in: $reading")
    }

    @Test
    fun weak_level_slope_flip_shifts_from_pressure_to_recovery() {
        val washout = plainRsiReading(level = 26.0, slope = -0.9, acceleration = -0.2)
        val recovery = plainRsiReading(level = 26.0, slope = 0.9, acceleration = 0.2)
        assertFalse(
            washout.equals(recovery, ignoreCase = true),
            "washout and recovery readings should differ; both: $washout",
        )
    }
}
