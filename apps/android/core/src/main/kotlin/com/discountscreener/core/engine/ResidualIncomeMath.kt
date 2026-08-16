package com.discountscreener.core.engine

import kotlin.math.pow
import kotlin.math.roundToLong

val FRANCHISE_PERSIST_SPREAD_BPS: Int
    get() = ValuationPolicy.current.residualIncome.franchisePersistSpreadBps
val RESIDUAL_GORDON_EPSILON_BPS: Int
    get() = ValuationPolicy.current.residualIncome.gordonEpsilonBps

/**
 * Residual income path.
 * Extra profit fades toward a capped franchise ROE, not raw ROE0 forever.
 * Remaining extra profit after [fadeYears] is capitalized at stable growth.
 */
object ResidualIncomeMath {
    fun longRunRoeBps(
        roe0Bps: Int,
        costOfEquityBps: Int,
        spreadBps: Int = FRANCHISE_PERSIST_SPREAD_BPS,
    ): Int {
        if (roe0Bps <= costOfEquityBps) return costOfEquityBps
        return minOf(roe0Bps, costOfEquityBps + spreadBps)
    }

    fun valuePerShareCents(
        book0: Double,
        shares: Double,
        roe0Bps: Int,
        costOfEquityBps: Int,
        retention: Double,
        fadeYears: Int,
        longRunRoeBps: Int = costOfEquityBps,
        stableGrowthBps: Int = 0,
    ): Long? {
        if (book0 <= 0.0 || shares <= 0.0 || costOfEquityBps <= 0 || fadeYears <= 0) return null
        var re = costOfEquityBps / 10_000.0
        var roe0 = roe0Bps / 10_000.0
        var roeStable = longRunRoeBps / 10_000.0
        var book = book0
        var pvRi = 0.0
        for (t in 1..fadeYears) {
            var w = t.toDouble() / fadeYears
            var roeT = roe0 * (1.0 - w) + roeStable * w
            var excess = (roeT - re) * book
            pvRi += excess / (1.0 + re).pow(t)
            book *= 1.0 + roeT * retention
            if (!book.isFinite() || book <= 0.0) return null
        }
        if (longRunRoeBps > costOfEquityBps) {
            var gCap = (costOfEquityBps - RESIDUAL_GORDON_EPSILON_BPS).coerceAtLeast(0)
            var gBps = stableGrowthBps.coerceAtMost(gCap)
            var g = gBps / 10_000.0
            if (g >= re) return null
            var nextExcess = (roeStable - re) * book
            var terminal = nextExcess / (re - g)
            pvRi += terminal / (1.0 + re).pow(fadeYears)
        }
        var equity = book0 + pvRi
        if (!equity.isFinite() || equity <= 0.0) return null
        return ((equity / shares) * 100.0).roundToLong()
    }

    /** Whole-firm equity cents. Same path as per-share with one claim on the book. */
    fun valueEquityCents(
        book0: Double,
        roe0Bps: Int,
        costOfEquityBps: Int,
        retention: Double,
        fadeYears: Int,
        longRunRoeBps: Int = costOfEquityBps,
        stableGrowthBps: Int = 0,
    ): Long? = valuePerShareCents(
        book0 = book0,
        shares = 1.0,
        roe0Bps = roe0Bps,
        costOfEquityBps = costOfEquityBps,
        retention = retention,
        fadeYears = fadeYears,
        longRunRoeBps = longRunRoeBps,
        stableGrowthBps = stableGrowthBps,
    )
}
