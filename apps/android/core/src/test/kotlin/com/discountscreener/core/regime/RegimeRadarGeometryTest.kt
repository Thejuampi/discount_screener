package com.discountscreener.core.regime

import kotlin.test.Test
import kotlin.test.assertEquals

class RegimeRadarGeometryTest {

    @Test
    fun axis_order_matches_windows() {
        assertEquals(
            listOf("trend", "breadth", "volatility", "sentiment", "cross_asset", "quality"),
            RegimeRadarGeometry.AXIS_ORDER,
        )
    }

    @Test
    fun first_axis_points_straight_up() {
        var point = RegimeRadarGeometry.polarPoint(
            index = 0,
            count = 6,
            radius01 = 1.0,
            centerX = 120.0,
            centerY = 120.0,
            maxRadius = 88.0,
        )
        assertEquals("120.00,32.00", String.format(java.util.Locale.US, "%.2f,%.2f", point.x, point.y))
    }
}
