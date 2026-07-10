package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacdPlainReadingTest {
    @Test
    fun null_inputs_need_more_candles() {
        assertEquals(
            "MACD needs more candles.",
            plainMacdReading(null, null, null, null, null),
        )
    }

    @Test
    fun hot_upside_building_warns_against_chase() {
        val reading = plainMacdReading(
            macd = 800.0,
            histogram = 300.0,
            histogramSlope = 80.0,
            histogramAccel = 40.0,
            scale = 1000.0,
        )
        assertTrue(
            reading.contains("Don't chase", ignoreCase = true) ||
                reading.contains("late and hot", ignoreCase = true),
            "expected chase warning or late-hot language; got: $reading",
        )
    }

    @Test
    fun positive_hist_exhausting_uses_fade_or_stall() {
        val reading = plainMacdReading(
            macd = 500.0,
            histogram = 200.0,
            histogramSlope = -60.0,
            histogramAccel = -30.0,
            scale = 1000.0,
        )
        assertTrue(
            reading.contains("fad", ignoreCase = true) ||
                reading.contains("stall", ignoreCase = true) ||
                reading.contains("Watch", ignoreCase = true),
            "expected fade/stall/watch language; got: $reading",
        )
    }

    @Test
    fun deep_selloff_building_stays_out() {
        val reading = plainMacdReading(
            macd = -900.0,
            histogram = -350.0,
            histogramSlope = -90.0,
            histogramAccel = -40.0,
            scale = 1000.0,
        )
        assertTrue(
            reading.contains("Stay out", ignoreCase = true) ||
                reading.contains("knife", ignoreCase = true) ||
                reading.contains("worse", ignoreCase = true),
            "expected stay-out / knife / worse language; got: $reading",
        )
    }

    @Test
    fun recovery_from_weak_hist_watches_bounce() {
        val reading = plainMacdReading(
            macd = -400.0,
            histogram = -180.0,
            histogramSlope = 70.0,
            histogramAccel = 25.0,
            scale = 1000.0,
        )
        assertTrue(
            reading.contains("Watch", ignoreCase = true) ||
                reading.contains("bounce", ignoreCase = true) ||
                reading.contains("take the push", ignoreCase = true) ||
                reading.contains("hold", ignoreCase = true),
            "expected recovery / watch language; got: $reading",
        )
    }

    @Test
    fun quiet_near_zero_has_no_reason_to_act() {
        val reading = plainMacdReading(
            macd = 10.0,
            histogram = 5.0,
            histogramSlope = 1.0,
            histogramAccel = 0.5,
            scale = 1000.0,
        )
        assertTrue(
            reading.contains("Quiet", ignoreCase = true) ||
                reading.contains("No clear edge", ignoreCase = true) ||
                reading.contains("No push reason", ignoreCase = true),
            "expected quiet / no-edge language; got: $reading",
        )
    }

    @Test
    fun non_null_reading_includes_action_cue() {
        val reading = plainMacdReading(
            macd = 200.0,
            histogram = 80.0,
            histogramSlope = 20.0,
            histogramAccel = 8.0,
            scale = 1000.0,
        )
        val hasAction = listOf(
            "chase",
            "watch",
            "stay out",
            "ride",
            "act",
            "avoid",
            "wait",
            "size",
            "add",
        ).any { token -> reading.contains(token, ignoreCase = true) }
        assertTrue(hasAction, "expected an action cue; got: $reading")
    }

    @Test
    fun reading_avoids_trader_jargon() {
        val reading = plainMacdReading(
            macd = 300.0,
            histogram = 100.0,
            histogramSlope = 15.0,
            histogramAccel = -10.0,
            scale = 1000.0,
        )
        val banned = listOf(
            "histogram",
            "signal line",
            "bullish",
            "bearish",
            "momentum",
            "macd",
        )
        val hit = banned.firstOrNull { token -> reading.contains(token, ignoreCase = true) }
        assertEquals(null, hit, "banned jargon '$hit' in: $reading")
    }

    @Test
    fun weak_hist_slope_flip_shifts_pressure_to_recovery() {
        val washout = plainMacdReading(
            macd = -500.0,
            histogram = -200.0,
            histogramSlope = -70.0,
            histogramAccel = -20.0,
            scale = 1000.0,
        )
        val recovery = plainMacdReading(
            macd = -500.0,
            histogram = -200.0,
            histogramSlope = 70.0,
            histogramAccel = 20.0,
            scale = 1000.0,
        )
        assertFalse(
            washout.equals(recovery, ignoreCase = true),
            "washout and recovery readings should differ; both: $washout",
        )
    }

    @Test
    fun flat_histogram_series_has_near_zero_derivatives() {
        val (slope, accel) = macdHistogramDerivatives(listOf(10.0, 10.0, 10.0, 10.0, 10.0))
        assertTrue(
            absOrZero(slope) < 0.01 && absOrZero(accel) < 0.01,
            "expected near-zero derivatives; slope=$slope accel=$accel",
        )
    }

    @Test
    fun rising_histogram_series_reports_positive_slope() {
        val (slope, _) = macdHistogramDerivatives(listOf(0.0, 10.0, 20.0, 30.0, 40.0))
        assertTrue((slope ?: 0.0) > 0.0, "expected positive slope; got $slope")
    }
}

private fun absOrZero(value: Double?): Double = kotlin.math.abs(value ?: 0.0)
