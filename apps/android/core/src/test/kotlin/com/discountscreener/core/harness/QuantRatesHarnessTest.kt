package com.discountscreener.core.harness

import com.discountscreener.core.engine.ErpSchool
import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.engine.RF_SOURCE_FRED_DGS10
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuantRatesHarnessTest {
    @Test
    fun hardcoded_returns_the_stored_rf() {
        var params = MarketParams(rfBps = 412, erpBps = 442, provisional = false)
        var data = QuantHarness.hardcodedRates(params).load()
        assertEquals(412, data.marketParams.rfBps)
    }

    @Test
    fun hardcoded_names_its_mode() {
        var data = QuantHarness.hardcodedRates(MarketParams()).load()
        assertEquals(QuantRatesMode.Hardcoded, data.mode)
    }

    @Test
    fun cached_empty_disk_writes_a_pack() {
        var cacheDir = Files.createTempDirectory("quant-rates-miss")
        QuantHarness.cachedRates(RecordingRatesClient(), cacheDir).load()
        assertEquals(1L, Files.list(cacheDir).use { it.count() })
    }

    @Test
    fun cached_second_load_is_a_cache_hit() {
        var cacheDir = Files.createTempDirectory("quant-rates-hit")
        var source = QuantHarness.cachedRates(RecordingRatesClient(), cacheDir)
        source.load()
        var data = source.load()
        assertEquals(true, data.cacheHit)
    }

    @Test
    fun cached_hit_does_not_call_live_again() {
        var cacheDir = Files.createTempDirectory("quant-rates-no-live")
        var live = RecordingRatesClient()
        var source = QuantHarness.cachedRates(live, cacheDir)
        source.load()
        source.load()
        assertEquals(1, live.calls)
    }

    @Test
    fun cached_after_ttl_calls_live_again() {
        var cacheDir = Files.createTempDirectory("quant-rates-stale")
        var live = RecordingRatesClient()
        var now = 1_000_000L
        var source = QuantHarness.cachedRates(
            client = live,
            cacheDir = cacheDir,
            ttl = Duration.ofDays(1),
            clock = { now },
        )
        source.load()
        now += Duration.ofDays(1).toMillis() + 1
        source.load()
        assertEquals(2, live.calls)
    }

    @Test
    fun live_failure_does_not_write_a_pack() {
        var cacheDir = Files.createTempDirectory("quant-rates-fail")
        var source = QuantHarness.cachedRates(FailingRatesClient(), cacheDir)
        runCatching { source.load() }
        assertEquals(0, Files.list(cacheDir).use { it.count() })
    }

    @Test
    fun live_failure_does_not_fall_back_to_bootstrap() {
        var source = QuantHarness.cachedRates(
            FailingRatesClient(),
            Files.createTempDirectory("quant-rates-no-fallback"),
        )
        assertFailsWith<IllegalStateException> { source.load() }
    }

    @Test
    fun cached_pack_keeps_the_fred_source() {
        var cacheDir = Files.createTempDirectory("quant-rates-source")
        var source = QuantHarness.cachedRates(RecordingRatesClient(), cacheDir)
        source.load()
        var data = source.load()
        assertEquals(RF_SOURCE_FRED_DGS10, data.marketParams.rfSource)
    }
}

private class RecordingRatesClient : QuantRatesLiveClient {
    var calls: Int = 0
    override fun fetch(): QuantRatesBundle {
        calls += 1
        return QuantRatesBundle(
            marketParams = MarketParams.observed(
                rfBps = 425,
                asOfEpochMillis = 1_700_000_000_000L,
                rfSource = RF_SOURCE_FRED_DGS10,
                school = ErpSchool.ImpliedIndex,
            ),
            mode = QuantRatesMode.Live,
            asOfEpochMillis = 1_700_000_000_000L,
        )
    }
}

private class FailingRatesClient : QuantRatesLiveClient {
    override fun fetch(): QuantRatesBundle {
        error("live rates fetch failed")
    }
}
