package com.discountscreener.core.harness

import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuantHarnessTest {
    @Test
    fun hardcoded_returns_the_stored_constants() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.PEPITO).load("PEPITO")
        assertEquals(1_200_000_000L, data.fundamentals.marketCapDollars)
    }

    @Test
    fun hardcoded_names_its_mode() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.PEPITO).load("PEPITO")
        assertEquals(QuantDataMode.Hardcoded, data.mode)
    }

    @Test
    fun hardcoded_unknown_symbol_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            QuantHarness.hardcoded(QuantHarnessCases.PEPITO).load("MISSING")
        }
    }

    @Test
    fun hardcoded_does_not_write_the_cache_dir() {
        var cacheDir = Files.createTempDirectory("quant-harness-hardcoded")
        QuantHarness.hardcoded(QuantHarnessCases.PEPITO).load("PEPITO")
        assertEquals(0, Files.list(cacheDir).use { it.count() })
    }

    @Test
    fun cached_empty_disk_writes_a_pack() {
        var cacheDir = Files.createTempDirectory("quant-harness-miss")
        QuantHarness.cached(RecordingLiveClient(), cacheDir).load("PEPITO")
        assertEquals(1L, Files.list(cacheDir).use { it.count() })
    }

    @Test
    fun cached_miss_is_not_a_hit() {
        var data = QuantHarness.cached(
            RecordingLiveClient(),
            Files.createTempDirectory("quant-harness-miss-flag"),
        ).load("PEPITO")
        assertEquals(false, data.cacheHit)
    }

    @Test
    fun cached_second_load_is_a_cache_hit() {
        var cacheDir = Files.createTempDirectory("quant-harness-hit")
        var live = RecordingLiveClient()
        var source = QuantHarness.cached(live, cacheDir)
        source.load("PEPITO")
        var data = source.load("PEPITO")
        assertEquals(true, data.cacheHit)
    }

    @Test
    fun cached_hit_does_not_call_live_again() {
        var cacheDir = Files.createTempDirectory("quant-harness-no-live")
        var live = RecordingLiveClient()
        var source = QuantHarness.cached(live, cacheDir)
        source.load("PEPITO")
        source.load("PEPITO")
        assertEquals(1, live.calls)
    }

    @Test
    fun cached_after_ttl_calls_live_again() {
        var cacheDir = Files.createTempDirectory("quant-harness-stale")
        var live = RecordingLiveClient()
        var now = 1_000_000L
        var source = QuantHarness.cached(
            client = live,
            cacheDir = cacheDir,
            ttl = Duration.ofDays(1),
            clock = { now },
        )
        source.load("PEPITO")
        now += Duration.ofDays(1).toMillis() + 1
        source.load("PEPITO")
        assertEquals(2, live.calls)
    }

    @Test
    fun live_failure_does_not_write_a_pack() {
        var cacheDir = Files.createTempDirectory("quant-harness-fail")
        var source = QuantHarness.cached(FailingLiveClient(), cacheDir)
        runCatching { source.load("PEPITO") }
        assertEquals(0, Files.list(cacheDir).use { it.count() })
    }

    @Test
    fun live_attaches_rates_to_the_bundle() {
        var rates = QuantHarness.hardcodedRates(
            MarketParams(rfBps = 419, provisional = false),
        )
        var data = QuantHarness.live(RecordingLiveClient(), rates).load("PEPITO")
        assertEquals(419, data.marketParams?.rfBps)
    }

    @Test
    fun live_failure_does_not_fall_back_to_hardcoded() {
        var source = QuantHarness.cached(
            FailingLiveClient(),
            Files.createTempDirectory("quant-harness-no-fallback"),
        )
        assertFailsWith<IllegalStateException> { source.load("PEPITO") }
    }
}

private class RecordingLiveClient : QuantLiveClient {
    var calls: Int = 0
    override fun fetch(symbol: String): QuantBundle {
        calls += 1
        return QuantBundle(
            symbol = symbol,
            fundamentals = FundamentalSnapshot(symbol = symbol, marketCapDollars = 12_000_000L),
            timeseries = FundamentalTimeseries(),
            mode = QuantDataMode.Live,
            asOfEpochMillis = 1L,
        )
    }
}

private class FailingLiveClient : QuantLiveClient {
    override fun fetch(symbol: String): QuantBundle {
        error("live fetch failed for $symbol")
    }
}
