package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ProjectedRowDecision.Act
import com.discountscreener.core.model.ProjectedRowDecision.Avoid
import com.discountscreener.core.model.ProjectedRowDecision.Watch
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V31ActAdjustmentTest {
    private val v = V31ActAdjustment
    private fun summary(hist: Long?, close: Long?) =
        ChartRangeSummary(ChartRange.Year, 0L, 54, latestCloseCents = close, histogramCents = hist)

    @Test
    fun config_list_is_the_named_6_grid_with_stable_ids() {
        assertEquals(
            listOf("b0050_lm015", "b0050_lm030", "b0200_lm015", "b0200_lm030", "b0500_lm015", "b0500_lm030"),
            v.SHADOW_CONFIGS.map { it.id },
        )
        assertEquals(setOf(0.005, 0.02, 0.05), v.SHADOW_CONFIGS.map { it.bound }.toSet())
        assertEquals(setOf(0.15, 0.30), v.SHADOW_CONFIGS.map { it.lambdaM }.toSet())
        assertEquals(1.5, v.LAMBDA_T, 0.0)
    }

    @Test
    fun ssrm_t_term_is_about_27_4() {
        val s = v.adjustedScore(36, -18, null, 1.5, 0.3)
        assertEquals(36.0 * exp(-0.27), s, 1e-12)
        assertTrue(s < 30.0 && kotlin.math.abs(s - 27.4) < 0.1)
    }

    @Test
    fun non_negative_inputs_leave_s_unchanged() {
        for ((t, m) in listOf(0 to 0.0, 5 to 0.3, 100 to 1.0)) assertEquals(36.0, v.adjustedScore(36, t, m, 1.5, 0.3), 0.0)
        assertEquals(36.0, v.adjustedScore(36, null, null, 1.5, 0.3), 0.0)
    }

    @Test
    fun monotonic_in_t_and_m() {
        var prev = Double.POSITIVE_INFINITY
        for (t in 0 downTo -100) { val s = v.adjustedScore(50, t, 0.0, 1.5, 0.3); assertTrue(s <= prev); prev = s }
        prev = Double.POSITIVE_INFINITY
        for (i in 0..20) { val s = v.adjustedScore(50, 0, -i / 20.0, 1.5, 0.3); assertTrue(s <= prev); prev = s }
    }

    @Test
    fun momentum_is_price_normalised_and_clipped_per_bound() {
        assertEquals(-0.0087, v.histogramRatio(summary(-87, 10_000))!!, 1e-12)
        assertEquals(-1.0, v.momentum(-0.0087, 0.005)!!, 0.0)
        assertEquals(-0.435, v.momentum(-0.0087, 0.02)!!, 1e-12)
        assertEquals(1.0, v.momentum(0.5, 0.05)!!, 0.0)
        assertNull(v.histogramRatio(summary(5, 0)))
        assertNull(v.histogramRatio(summary(null, 10_000)))
        assertNull(v.momentum(null, 0.02))
    }

    @Test
    fun shadow_only_demotes_act_and_never_touches_avoid_or_watch() {
        assertEquals(Watch, v.shadowDecision(Act, 29.99))
        assertEquals(Act, v.shadowDecision(Act, 30.0))
        assertEquals(Avoid, v.shadowDecision(Avoid, 1.0))
        assertEquals(Watch, v.shadowDecision(Watch, 99.0))
        assertNull(v.shadowDecision(null, 1.0))
        val g = v.grid(Act, 36, -20, -87.0 / 2833.0)
        assertEquals(6, g.size)
        assertTrue(g.all { it.shadowDecision == Watch && it.sAdj < 30 && it.lambdaT == 1.5 })
    }
}
