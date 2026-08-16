package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.engine.MarketParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class SecEdgarTimeseriesLiveTest {
    @Test
    fun live_aapl_sec_interest_covers_three_years() = runBlocking {
        assumeTrue(System.getenv("DS_QUANT_LIVE") == "true")
        var cacheDir = Files.createTempDirectory("sec-live-aapl").toFile()
        var timeseries = SecEdgarTimeseriesProvider(cacheDir).fetch("AAPL")
        assertTrue((timeseries?.interestExpense?.size ?: 0) >= 3)
    }

    @Test
    fun live_aapl_sec_computes_fcff() = runBlocking {
        assumeTrue(System.getenv("DS_QUANT_LIVE") == "true")
        var cacheDir = Files.createTempDirectory("sec-live-aapl-dcf").toFile()
        var timeseries = SecEdgarTimeseriesProvider(cacheDir).fetch("AAPL")
            ?: error("SEC timeseries missing for AAPL")
        var yahoo = YahooFinanceClient().fetchSymbol("AAPL")
        var fundamentals = yahoo.fundamentals ?: error("yahoo fundamentals missing for AAPL")
        var result = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = timeseries,
            marketParams = MarketParams(),
        )
        assertTrue(
            result.exceptionOrNull()?.message,
            (result.getOrNull()?.baseIntrinsicValueCents ?: 0L) > 0L,
        )
    }
}
