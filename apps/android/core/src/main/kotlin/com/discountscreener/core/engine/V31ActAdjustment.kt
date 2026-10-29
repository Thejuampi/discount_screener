package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRangeSummary
import kotlin.math.exp

/**
 * Decision rule V3.1 (Juan, 2026-10-29). The Act threshold is checked against a
 * technically-adjusted composite instead of the raw V3 composite S:
 *
 *     S_adj = S * exp(-LAMBDA_T * max(0, -T) / 100) * exp(-LAMBDA_M * max(0, -m))
 *
 * T = V3 technical bucket (-100..100). m = MACD momentum in [-1, 1]:
 *
 *     m = clip((histogram / close) / MOMENTUM_HISTOGRAM_BOUND, -1, 1)
 *
 * from the same `ChartRange.Year` summary the technical bucket reads (histogram = MACD(12,26)
 * minus signal(9), in cents). Price-normalising makes it comparable across tickers.
 * MOMENTUM_HISTOGRAM_BOUND is V3.1's own constant, deliberately independent of the V3 technical
 * histogram bound; callers may pass another bound (sensitivity runs), the default is mirrored in
 * headless and checked by tools/android-jvm-parity/diff_headless.py. On weekly bars 0.005 saturates
 * |m| = 1 for >80% of rows; |hist/close| p90-p95 is ~4-5.5% (see docs/architecture/decision-rule-v3-1.md).
 * Only negative T / m penalise; ranking stays on raw S; Avoid logic is unchanged.
 * Mirrored in headless: `apps/windows/src-tauri/src/opportunity_v31.rs`.
 */
object V31ActAdjustment {
    const val RULE_VERSION = "V3.1"
    const val LAMBDA_T = 1.5
    const val LAMBDA_M = 0.3
    const val MOMENTUM_HISTOGRAM_BOUND = 0.005

    /** MACD momentum m, or null when histogram or close is missing. */
    fun macdMomentum(summary: ChartRangeSummary?, bound: Double = MOMENTUM_HISTOGRAM_BOUND): Double? {
        require(bound > 0.0) { "momentum bound must be > 0" }
        val hist = summary?.histogramCents ?: return null
        val close = summary.latestCloseCents?.takeIf { it > 0 } ?: return null
        return ((hist.toDouble() / close.toDouble()) / bound).coerceIn(-1.0, 1.0)
    }

    /** S_adj; a missing T or m contributes no penalty. */
    fun adjustedScore(composite: Int, technical: Int?, momentum: Double?): Double {
        val t = (technical ?: 0).toDouble()
        val m = (momentum ?: 0.0).coerceIn(-1.0, 1.0)
        return composite.toDouble() * exp(-LAMBDA_T * maxOf(0.0, -t) / 100.0) * exp(-LAMBDA_M * maxOf(0.0, -m))
    }
}
