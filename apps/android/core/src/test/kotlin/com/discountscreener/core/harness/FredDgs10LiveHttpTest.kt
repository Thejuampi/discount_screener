package com.discountscreener.core.harness

import com.discountscreener.core.engine.RF_SOURCE_FRED_DGS10
import com.discountscreener.core.engine.RF_SOURCE_YAHOO_TNX
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class FredDgs10LiveHttpTest {
    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "DS_QUANT_LIVE", matches = "true")
    fun cached_empty_disk_preloads_a_live_ten_year() {
        var cacheDir = Files.createTempDirectory("quant-rates-live-dgs10")
        var data = QuantHarness.cachedRates(cacheDir).load()
        assertTrue(data.marketParams.rfSource == RF_SOURCE_FRED_DGS10 || data.marketParams.rfSource == RF_SOURCE_YAHOO_TNX)
    }

    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "DS_QUANT_LIVE", matches = "true")
    fun live_dgs10_yield_is_in_range() {
        var data = QuantHarness.liveRates().load()
        assertTrue(data.marketParams.rfBps in 50..2_000)
    }
}
