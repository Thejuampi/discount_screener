package com.discountscreener.core.math

fun clamp(value: Double, lower: Double, upper: Double): Double? {
    if (!value.isFinite() || !lower.isFinite() || !upper.isFinite()) return null
    if (upper < lower) return null
    return value.coerceIn(lower, upper)
}

fun minimum(values: List<Double>): Double? {
    var finite = values.filter { it.isFinite() }
    return finite.minOrNull()
}

fun maximum(values: List<Double>): Double? {
    var finite = values.filter { it.isFinite() }
    return finite.maxOrNull()
}
