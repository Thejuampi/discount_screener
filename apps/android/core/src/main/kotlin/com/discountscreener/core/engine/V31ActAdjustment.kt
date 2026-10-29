package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ProjectedRowDecision
import com.discountscreener.core.model.ProjectedShadowDecision
import kotlin.math.exp

/**
 * V3.1 SHADOW MODE (Juan, 2026-10-29). Official decisions and ranking stay V3; this only computes,
 * for every config in [SHADOW_CONFIGS], what V3.1 *would* decide:
 *
 *     m        = clip((histogram / close) / bound, -1, 1)
 *     S_adj    = S * exp(-LAMBDA_T * max(0, -T) / 100) * exp(-lambda_m * max(0, -m))
 *     shadow   = Watch if official == Act and S_adj < ACT_THRESHOLD, else official
 *
 * T = V3 technical bucket; histogram = MACD(12,26) - signal(9) of the `ChartRange.Year` (1y/1wk)
 * summary, preferably UNROUNDED ([ChartRangeSummary.histogramRawCents], so Kotlin's round-half-up
 * vs Rust's round-half-even cannot reach the shadow); summaries without the raw value fall back to
 * the rounded histogramCents (headless records which one in `score_shadow.hist_source`). The last weekly bar is
 * the current, possibly partial, week, as for the official technicals. Warm-up: with fewer than
 * [MIN_WEEKLY_BARS] bars m is null, `warmup` is true and no momentum penalty applies. Avoid and ranking are never touched (S_adj <= S, so shadow can only demote Act).
 * Mirrored in headless `apps/windows/src-tauri/src/opportunity_v31.rs`; the constants and the
 * config list are checked by `tools/android-jvm-parity/diff_headless.py`.
 */
object V31ActAdjustment {
    const val SHADOW_RULE_VERSION = "V3.1-shadow"
    const val LAMBDA_T = 1.5
    const val ACT_THRESHOLD = 30.0
    const val MIN_WEEKLY_BARS = 35

    data class ShadowConfig(val id: String, val bound: Double, val lambdaM: Double)

    /** Stable ids: b<bound*10^4 4 digits>_lm<lambda_m*100 3 digits>. Never renumber; append only. */
    val SHADOW_CONFIGS: List<ShadowConfig> = listOf(
        ShadowConfig("b0050_lm015", 0.005, 0.15),
        ShadowConfig("b0050_lm030", 0.005, 0.30),
        ShadowConfig("b0200_lm015", 0.02, 0.15),
        ShadowConfig("b0200_lm030", 0.02, 0.30),
        ShadowConfig("b0500_lm015", 0.05, 0.15),
        ShadowConfig("b0500_lm030", 0.05, 0.30),
    )

    /** True when the Year summary exists but has fewer than [MIN_WEEKLY_BARS] bars. */
    fun isWarmup(summary: ChartRangeSummary?): Boolean = summary != null && summary.candleCount < MIN_WEEKLY_BARS

    /** Histogram (raw, else rounded fallback) / close of the Year summary; null when missing, close <= 0, or warming up. */
    fun histogramRatio(summary: ChartRangeSummary?): Double? {
        if (summary == null || isWarmup(summary)) return null
        // Raw when present; summaries persisted before the raw field fall back to rounded cents.
        val hist = (summary.histogramRawCents ?: summary.histogramCents?.toDouble())?.takeIf { it.isFinite() } ?: return null
        val close = summary.latestCloseCents?.takeIf { it > 0 } ?: return null
        return hist / close.toDouble()
    }

    fun momentum(ratio: Double?, bound: Double): Double? {
        require(bound > 0.0) { "momentum bound must be > 0" }
        return ratio?.let { (it / bound).coerceIn(-1.0, 1.0) }
    }

    /** S_adj; a missing T or m contributes no penalty. */
    fun adjustedScore(composite: Int, technical: Int?, momentum: Double?, lambdaT: Double, lambdaM: Double): Double {
        val t = (technical ?: 0).toDouble()
        val m = (momentum ?: 0.0).coerceIn(-1.0, 1.0)
        return composite.toDouble() * exp(-lambdaT * maxOf(0.0, -t) / 100.0) * exp(-lambdaM * maxOf(0.0, -m))
    }

    fun shadowDecision(official: ProjectedRowDecision?, sAdj: Double): ProjectedRowDecision? =
        if (official == ProjectedRowDecision.Act && sAdj < ACT_THRESHOLD) ProjectedRowDecision.Watch else official

    fun grid(
        official: ProjectedRowDecision?,
        composite: Int,
        technical: Int?,
        ratio: Double?,
        warmup: Boolean = false,
    ): List<ProjectedShadowDecision> =
        SHADOW_CONFIGS.map { c ->
            val m = if (warmup) null else momentum(ratio, c.bound)
            val sAdj = adjustedScore(composite, technical, m, LAMBDA_T, c.lambdaM)
            ProjectedShadowDecision(SHADOW_RULE_VERSION, c.id, c.bound, LAMBDA_T, c.lambdaM, m, warmup, sAdj, shadowDecision(official, sAdj))
        }
}
