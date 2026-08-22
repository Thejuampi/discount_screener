package com.discountscreener.core.engine

/** Steady-state split of reported CapEx into plant renewal. */
object SustainingCapex {
    val ASSET_RENEWAL_RATE_BPS: Int
        get() = ValuationPolicy.current.sustainingCapex.assetRenewalRateBps
    val MIN_REVENUE_BPS: Int
        get() = ValuationPolicy.current.sustainingCapex.minRevenueBps

    fun intensityBps(capexIntensityBps: Int, revenueGrowthBps: Int): Int {
        var capex = capexIntensityBps.coerceAtLeast(0)
        var renewal = ASSET_RENEWAL_RATE_BPS.toLong()
        var growth = revenueGrowthBps.coerceAtLeast(0).toLong()
        var sustaining = (capex.toLong() * renewal / (renewal + growth)).toInt()
        return sustaining.coerceIn(MIN_REVENUE_BPS.coerceAtMost(capex), capex)
    }
}
