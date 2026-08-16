package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class FactoryComponentCashTest {
    @Test
    fun gm_2025_factory_adds_depreciation_back() {
        var nopat = 10_916_000_000.0 * 0.79
        var depreciation = 6_960_000_000.0
        var sustain = 9_155_000_000.0
        assertEquals(6_428_640_000.0, FactoryComponentCash.annualFcff(nopat, depreciation, sustain))
    }
}
