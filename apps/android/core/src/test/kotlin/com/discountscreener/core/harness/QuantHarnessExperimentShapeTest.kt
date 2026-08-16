package com.discountscreener.core.harness

import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals

class QuantHarnessExperimentShapeTest {
    @Test
    fun pepito_hardcoded_routes_fcff() {
        var expected = ValuationModel.FcffWacc
        var data = QuantHarness.hardcoded(QuantHarnessCases.PEPITO).load("PEPITO")
        var result = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals,
            timeseries = data.timeseries,
            marketParams = data.marketParams ?: MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals(expected, result.model)
    }
}
