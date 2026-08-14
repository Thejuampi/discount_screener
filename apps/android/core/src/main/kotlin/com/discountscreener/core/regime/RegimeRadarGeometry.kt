package com.discountscreener.core.regime

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Polar layout for the six-axis regime radar. Matches Windows `regimeRadar.ts`. */
object RegimeRadarGeometry {
    val AXIS_ORDER: List<String> = listOf(
        "trend",
        "breadth",
        "volatility",
        "sentiment",
        "cross_asset",
        "quality",
    )

    fun polarPoint(
        index: Int,
        count: Int,
        radius01: Double,
        centerX: Double,
        centerY: Double,
        maxRadius: Double,
    ): RadarPoint {
        var angle = -PI / 2.0 + (index * 2.0 * PI) / count
        return RadarPoint(
            x = centerX + maxRadius * radius01 * cos(angle),
            y = centerY + maxRadius * radius01 * sin(angle),
        )
    }
}

data class RadarPoint(val x: Double, val y: Double)
