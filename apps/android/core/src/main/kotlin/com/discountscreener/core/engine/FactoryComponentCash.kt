package com.discountscreener.core.engine

/**
 * Factory cash for a mixed issuer.
 * NOPAT is after depreciation. Add depreciation back, then subtract plant renewal.
 */
object FactoryComponentCash {
    fun annualFcff(nopat: Double, depreciation: Double, sustainingCapex: Double): Double =
        nopat + depreciation - sustainingCapex
}
