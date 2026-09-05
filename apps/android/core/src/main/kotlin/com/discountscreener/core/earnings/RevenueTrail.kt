package com.discountscreener.core.earnings

import com.discountscreener.core.math.medianOf
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

data class RevenueTrail(
    val latestCents: Long,
    val medianCents: Long,
    val shortfallZBps: Int,
)

fun revenueTrailOf(
    revenuesOldestFirst: List<Long>,
    minN: Int = EarningsGatePolicy.current.minRevenueTrailQuarters,
): RevenueTrail? {
    if (revenuesOldestFirst.size < minN) return null
    var window = revenuesOldestFirst.takeLast(minN)
    if (window.any { it <= 0L }) return null
    var values = window.map { it.toDouble() }
    var location = medianOf(values) ?: return null
    var mean = values.average()
    var variance = values.sumOf { value ->
        var delta = value - mean
        delta * delta
    } / (values.size - 1)
    if (variance <= 0.0) return null
    var latest = values.last()
    return RevenueTrail(
        latestCents = window.last(),
        medianCents = location.roundToLong(),
        shortfallZBps = ((location - latest) / sqrt(variance) * 10_000.0).roundToInt(),
    )
}
