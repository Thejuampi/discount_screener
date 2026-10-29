package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V31ActAdjustmentTest {
    private fun summary(hist: Long?, close: Long?) =
        ChartRangeSummary(ChartRange.Year, 0L, 54, latestCloseCents = close, histogramCents = hist)

    @Test
    fun ssrm_t_term_is_about_27_4() {
        val s = V31ActAdjustment.adjustedScore(36, -18, null)
        assertEquals(36.0 * exp(-0.27), s, 1e-12)
        assertTrue(s < 30.0 && kotlin.math.abs(s - 27.4) < 0.1)
    }

    @Test
    fun non_negative_inputs_leave_s_unchanged() {
        for ((t, m) in listOf(0 to 0.0, 5 to 0.3, 100 to 1.0)) assertEquals(36.0, V31ActAdjustment.adjustedScore(36, t, m), 0.0)
        assertEquals(36.0, V31ActAdjustment.adjustedScore(36, null, null), 0.0)
    }

    @Test
    fun monotonic_in_t_and_m() {
        var prev = Double.POSITIVE_INFINITY
        for (t in 0 downTo -100) { val s = V31ActAdjustment.adjustedScore(50, t, 0.0); assertTrue(s <= prev); prev = s }
        prev = Double.POSITIVE_INFINITY
        for (i in 0..20) { val s = V31ActAdjustment.adjustedScore(50, 0, -i / 20.0); assertTrue(s <= prev); prev = s }
    }

    @Test
    fun m_is_clipped_and_price_normalised() {
        assertEquals(-1.0, V31ActAdjustment.macdMomentum(summary(-1000, 10_000))!!, 0.0)
        assertEquals(1.0, V31ActAdjustment.macdMomentum(summary(1000, 10_000))!!, 0.0)
        assertEquals(-0.5, V31ActAdjustment.macdMomentum(summary(-25, 10_000))!!, 1e-12)
        assertNull(V31ActAdjustment.macdMomentum(summary(5, 0)))
        assertNull(V31ActAdjustment.macdMomentum(summary(null, 10_000)))
        assertEquals(V31ActAdjustment.adjustedScore(30, 0, -1.0), V31ActAdjustment.adjustedScore(30, 0, -5.0), 0.0)
    }
}
