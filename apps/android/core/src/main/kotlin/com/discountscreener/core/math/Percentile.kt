package com.discountscreener.core.math

import kotlin.math.abs

/**
 * Percentile of [value] in [sample], 0..100, ties at half rank.
 *
 * Sorts a copy. Empty sample is missing.
 */
fun percentile(sample: List<Double>, value: Double): Double? {
    if (sample.isEmpty() || !value.isFinite()) return null
    if (sample.any { !it.isFinite() }) return null
    var sorted = sample.sorted()
    var below = 0
    for (member in sorted) {
        if (member < value) below += 1 else break
    }
    var equal = 0
    for (member in sorted.drop(below)) {
        if (abs(member - value) < 1e-9) equal += 1 else break
    }
    var rank = below.toDouble() + equal.toDouble() * 0.5
    return (rank / sorted.size.toDouble() * 100.0).coerceIn(0.0, 100.0)
}
