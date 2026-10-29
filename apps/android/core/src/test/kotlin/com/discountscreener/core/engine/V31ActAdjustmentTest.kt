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
    private fun summary(hist: Long?, close: Long?, bars: Int = 54) =
        ChartRangeSummary(ChartRange.Year, 0L, bars, latestCloseCents = close, histogramCents = hist, histogramRawCents = hist?.toDouble())

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

    /** Pins the exact config list AND the defaults (kills "bound changed" / "λ changed"). */
    @Test
    fun pinned_constants_and_config_values() {
        assertEquals("V3.1-shadow", v.SHADOW_RULE_VERSION)
        assertEquals(1.5, v.LAMBDA_T, 0.0)
        assertEquals(30.0, v.ACT_THRESHOLD, 0.0)
        assertEquals(35, v.MIN_WEEKLY_BARS)
        assertEquals(
            listOf(
                Triple("b0050_lm015", 0.005, 0.15), Triple("b0050_lm030", 0.005, 0.30),
                Triple("b0200_lm015", 0.02, 0.15), Triple("b0200_lm030", 0.02, 0.30),
                Triple("b0500_lm015", 0.05, 0.15), Triple("b0500_lm030", 0.05, 0.30),
            ),
            v.SHADOW_CONFIGS.map { Triple(it.id, it.bound, it.lambdaM) },
        )
    }

    /** λ_M pin: S=40, T=0, m=-1 -> 40·e^(-λ_M) for every config, with its provenance fields. */
    @Test
    fun lambda_m_pinned_per_config() {
        val g = v.grid(Act, 40, 0, -1.0)
        g.zip(v.SHADOW_CONFIGS).forEach { (r, c) ->
            assertEquals(-1.0, r.m!!, 0.0)
            assertEquals(40.0 * exp(-c.lambdaM), r.sAdj, 1e-12)
            assertEquals(listOf<Any>("V3.1-shadow", c.id, c.bound, 1.5, c.lambdaM), listOf<Any>(r.ruleVersion, r.configId, r.bound, r.lambdaT, r.lambdaM))
        }
        assertEquals(40.0 * exp(-0.3), g[1].sAdj, 1e-12)
        assertEquals(40.0 * exp(-0.15), g[0].sAdj, 1e-12)
    }

    /** λ_T pin: S=40, m=0, T=-20 -> 40·e^(-0.3); T=-100 -> 40·e^(-1.5); same for every config. */
    @Test
    fun lambda_t_pinned() {
        for ((t, want) in listOf(-20 to 40.0 * exp(-0.3), -100 to 40.0 * exp(-1.5))) {
            v.grid(Act, 40, t, 0.0).forEach { assertEquals(want, it.sAdj, 1e-12) }
        }
    }

    /** Kills "max(0,·) dropped on T/m": positive inputs never boost. */
    @Test
    fun positive_inputs_never_boost() {
        assertEquals(40.0, v.adjustedScore(40, 50, 0.0, 1.5, 0.3), 0.0)
        assertEquals(40.0, v.adjustedScore(40, 0, 1.0, 1.5, 0.3), 0.0)
    }

    /** Kills "clip ±0.5", "no clip", "no clamp in S_adj" and a changed bound. */
    @Test
    fun clip_is_exactly_plus_minus_one_and_per_bound() {
        assertEquals(-0.75, v.momentum(-0.75 * 0.02, 0.02)!!, 1e-12)
        assertEquals(-1.0, v.momentum(-3.0 * 0.02, 0.02)!!, 0.0)
        assertEquals(1.0, v.momentum(3.0 * 0.02, 0.02)!!, 0.0)
        assertEquals(40.0 * exp(-0.3), v.adjustedScore(40, 0, -5.0, 1.5, 0.3), 1e-12)
        val ms = v.grid(Act, 40, 0, -0.01).map { it.m!! }
        ms.zip(listOf(-1.0, -1.0, -0.5, -0.5, -0.2, -0.2)).forEach { (got, want) -> assertEquals(want, got, 1e-12) }
    }

    @Test
    fun s_adj_exactly_30_stays_act() {
        assertTrue(v.grid(Act, 30, 0, 0.0).all { it.sAdj == 30.0 && it.shadowDecision == Act })
    }

    @Test
    fun warmup_nulls_m_without_penalty_and_raw_histogram_is_used() {
        val young = summary(-500, 10_000, bars = 34)
        assertTrue(v.isWarmup(young))
        assertNull(v.histogramRatio(young))
        assertTrue(v.grid(Act, 40, 0, v.histogramRatio(young), v.isWarmup(young)).all { it.m == null && it.warmup && it.sAdj == 40.0 })
        assertTrue(v.grid(Act, 40, 0, -1.0, warmup = true).all { it.m == null && it.sAdj == 40.0 })
        val raw = ChartRangeSummary(ChartRange.Year, 0L, 35, latestCloseCents = 10_000, histogramCents = -1, histogramRawCents = -0.5)
        assertEquals(-0.5 / 10_000.0, v.histogramRatio(raw)!!, 0.0)
        assertEquals(-5.0 / 10_000.0, v.histogramRatio(ChartRangeSummary(ChartRange.Year, 0L, 60, latestCloseCents = 10_000, histogramCents = -5))!!, 0.0)
        assertNull(v.histogramRatio(ChartRangeSummary(ChartRange.Year, 0L, 60, latestCloseCents = 10_000)))
        assertTrue(!v.isWarmup(null))
    }
}
