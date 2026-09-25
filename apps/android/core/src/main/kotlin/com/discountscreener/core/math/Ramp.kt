package com.discountscreener.core.math

/**
 * Piecewise-linear map onto [-1, +1]. This is the scoring atom V2–V5 call `smoothRamp`.
 *
 * Returns -1 at or below [lower], +1 at or above [upper], and interpolates between.
 * A band with [upper] ≤ [lower] is missing.
 */
fun ramp(observed: Double, lower: Double, upper: Double): Double? {
    if (!observed.isFinite() || !lower.isFinite() || !upper.isFinite()) return null
    if (upper <= lower) return null
    if (observed <= lower) return -1.0
    if (observed >= upper) return 1.0
    return 2.0 * (observed - lower) / (upper - lower) - 1.0
}
