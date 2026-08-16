package com.discountscreener.core.engine

/**
 * Versioned interest-coverage → credit-spread map.
 *
 * The FCFF waterfall already ranks rated/synthetic spread above the accounting
 * coupon. Production never filled that rung, so every name used the coupon on
 * debt issued years ago. That coupon does not rise with leverage, so WACC fell
 * as the firm borrowed.
 *
 * Coverage is EBIT / interest with EBIT = pretax + interest. The buckets follow
 * the Damodaran large-firm default-spread shape and stay a named policy table.
 */
object CoverageCreditPolicy {
    const val VERSION = "coverage-credit-policy/1"

    fun spreadBps(coverage: Double): Int {
        if (!coverage.isFinite()) return 1_157
        return when {
            coverage >= 12.50 -> 59
            coverage >= 9.50 -> 70
            coverage >= 7.50 -> 92
            coverage >= 6.00 -> 107
            coverage >= 4.50 -> 121
            coverage >= 3.50 -> 147
            coverage >= 3.00 -> 178
            coverage >= 2.50 -> 221
            coverage >= 2.00 -> 304
            coverage >= 1.75 -> 359
            coverage >= 1.50 -> 418
            coverage >= 1.25 -> 519
            coverage >= 0.80 -> 798
            coverage >= 0.50 -> 895
            else -> 1_157
        }
    }
}
