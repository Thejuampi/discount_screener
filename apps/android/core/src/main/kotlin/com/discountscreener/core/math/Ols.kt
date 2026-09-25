package com.discountscreener.core.math

/**
 * Ordinary least-squares slope of [values] against index time 0..n-1.
 *
 * Two points are the floor. A non-finite member refuses. A zero-width time axis
 * (one distinct t) returns 0.
 */
fun ols(values: List<Double>): Double? {
    if (values.size < 2) return null
    var n = values.size.toDouble()
    var sumT = 0.0
    var sumY = 0.0
    var sumTy = 0.0
    var sumTt = 0.0
    values.forEachIndexed { i, y ->
        if (!y.isFinite()) return null
        var t = i.toDouble()
        sumT += t
        sumY += y
        sumTy += t * y
        sumTt += t * t
    }
    var denom = n * sumTt - sumT * sumT
    if (denom == 0.0) return 0.0
    return (n * sumTy - sumT * sumY) / denom
}
