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
        var policy = ValuationPolicy.current.coverageCredit
        if (!coverage.isFinite()) return policy.defaultSpreadBps
        for (bucket in policy.buckets) {
            if (coverage >= bucket.minCoverage) return bucket.spreadBps
        }
        return policy.defaultSpreadBps
    }
}
