package com.discountscreener.core.harness

import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.engine.MarketParams
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class YahooQuantLiveHttpTest {
    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "DS_QUANT_LIVE", matches = "true")
    fun cached_empty_disk_preloads_aapl_from_yahoo() {
        var cacheDir = Files.createTempDirectory("quant-harness-live-aapl")
        var data = QuantHarness.cached(cacheDir).load("AAPL")
        assertTrue((data.fundamentals.sharesOutstanding ?: 0L) > 0L)
    }

    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "DS_QUANT_LIVE", matches = "true")
    fun cached_live_aapl_has_annual_free_cash_flow() {
        var cacheDir = Files.createTempDirectory("quant-harness-live-aapl-fcf")
        var data = QuantHarness.cached(cacheDir).load("AAPL")
        assertTrue(data.timeseries.freeCashFlow.size >= 3)
    }

    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "DS_QUANT_LIVE", matches = "true")
    fun cached_live_aapl_has_marginal_tax() {
        var cacheDir = Files.createTempDirectory("quant-harness-live-aapl-tax")
        var data = QuantHarness.cached(cacheDir).load("AAPL")
        assertTrue(data.timeseries.taxRateForCalcs.isNotEmpty() || data.timeseries.marginalTaxRate.isNotEmpty())
    }

    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "DS_QUANT_LIVE", matches = "true")
    fun cached_live_aapl_yahoo_only_refuses_without_filing_tax() {
        var cacheDir = Files.createTempDirectory("quant-harness-live-aapl-dcf")
        var rates = QuantHarness.liveRates().load()
        var data = QuantHarness.cached(cacheDir).load("AAPL")
        var result = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals,
            timeseries = data.timeseries,
            marketParams = rates.marketParams,
        )
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("marginal tax is unavailable"),
        )
    }
}
