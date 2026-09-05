package com.discountscreener.core.earnings

import com.discountscreener.core.math.medianOf
import com.discountscreener.core.math.robustCentre
import kotlin.math.abs
import kotlin.math.roundToLong

data class RevenueTrail(
    val latestCents: Long,
    val centreCents: Long,
    val scaleCents: Long,
)

fun revenueTrailOf(
    revenuesOldestFirst: List<Long>,
    minN: Int = EarningsGatePolicy.current.minRevenueTrailQuarters,
): RevenueTrail? {
    if (revenuesOldestFirst.size < minN) return null
    var window = revenuesOldestFirst.takeLast(minN)
    if (window.any { it <= 0L }) return null
    var prior = window.dropLast(1).map { it.toDouble() }
    var priorLocation = medianOf(prior) ?: return null
    var mad = medianOf(prior.map { abs(it - priorLocation) }) ?: return null
    if (!mad.isFinite()) return null
    var centre = if (mad <= 0.0) priorLocation else robustCentre(prior) ?: return null
    return RevenueTrail(
        latestCents = window.last(),
        centreCents = centre.roundToLong(),
        scaleCents = mad.roundToLong(),
    )
}
