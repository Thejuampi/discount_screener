package com.discountscreener.core.engine

import kotlin.math.pow
import kotlin.math.roundToLong

object FcffFadePricer {
    fun equityCentsPerShare(
        latestRevenueDollars: Double,
        fcffMarginBps: Int,
        stableFcffMarginBps: Int,
        revenueGrowthBps: Int,
        currentShares: Double,
        netDebtDollars: Long,
        gStableBps: Int,
        discountRateBps: Int,
        growthFadeExponent: Double,
        holdYears: Int,
        fadeYears: Int,
    ): Long? {
        var projectionYears = holdYears + fadeYears
        if (latestRevenueDollars <= 0.0 || currentShares <= 0.0 || stableFcffMarginBps <= 0 ||
            revenueGrowthBps <= -10_000 || gStableBps >= discountRateBps ||
            fadeYears < 3 || holdYears < 0 || projectionYears < 3
        ) {
            return null
        }
        var rate = discountRateBps / 10_000.0
        var nearGrowth = revenueGrowthBps / 10_000.0
        var stableGrowth = gStableBps / 10_000.0
        var margin = fcffMarginBps / 10_000.0
        var stableMargin = stableFcffMarginBps / 10_000.0
        var revenue = latestRevenueDollars
        var presentValue = 0.0
        var year = 0
        for (step in 1..holdYears) {
            year += 1
            revenue *= 1.0 + nearGrowth
            var fcff = revenue * margin
            if (!fcff.isFinite()) return null
            presentValue += fcff / (1.0 + rate).pow(year)
        }
        for (fadeStep in 1..fadeYears) {
            year += 1
            var fade = (fadeStep.toDouble() / fadeYears).pow(growthFadeExponent)
            var growth = nearGrowth * (1.0 - fade) + stableGrowth * fade
            revenue *= 1.0 + growth
            var marginT = margin * (1.0 - fade) + stableMargin * fade
            var fcff = revenue * marginT
            if (!fcff.isFinite()) return null
            presentValue += fcff / (1.0 + rate).pow(year)
        }
        var terminalFcff = revenue * (1.0 + stableGrowth) * stableMargin
        var terminalValue = terminalFcff / (rate - stableGrowth)
        var enterpriseValue = presentValue + terminalValue / (1.0 + rate).pow(projectionYears)
        var equityValue = enterpriseValue - netDebtDollars
        if (!equityValue.isFinite()) return null
        return ((equityValue.coerceAtLeast(0.0) / currentShares) * 100.0).roundToLong()
    }
}
