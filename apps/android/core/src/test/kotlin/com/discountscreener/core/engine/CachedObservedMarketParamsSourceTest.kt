package com.discountscreener.core.engine

import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CachedObservedMarketParamsSourceTest {
    @Test
    fun fetch_fail_returns_bootstrap() {
        var cacheFile = Files.createTempDirectory("mp-fail").resolve("dgs10.csv")
        var source = CachedObservedMarketParamsSource(
            fetchCsv = { error("network down") },
            cacheFile = cacheFile,
        )
        assertEquals(true, source.current().provisional)
    }

    @Test
    fun live_csv_is_not_provisional() {
        var cacheFile = Files.createTempDirectory("mp-live").resolve("dgs10.csv")
        var source = CachedObservedMarketParamsSource(
            fetchCsv = { FRED_CSV },
            cacheFile = cacheFile,
            clock = { epochMillis("2026-08-15") },
        )
        assertEquals(false, source.current().provisional)
    }

    @Test
    fun cascade_uses_tnx_when_fred_is_bootstrap() {
        var source = FredThenTnxMarketParamsSource(
            fred = MarketParamsSource { MarketParams() },
            tnx = MarketParamsSource {
                MarketParams.observed(
                    rfBps = 419,
                    asOfEpochMillis = epochMillis("2026-08-15"),
                    rfSource = RF_SOURCE_YAHOO_TNX,
                )
            },
        )
        assertEquals(RF_SOURCE_YAHOO_TNX, source.current().rfSource)
    }

    @Test
    fun live_csv_is_written_as_a_java_file() {
        var cacheFile = Files.createTempDirectory("mp-write").resolve("dgs10.csv")
        var source = CachedObservedMarketParamsSource(
            fetchCsv = { FRED_CSV },
            cacheFile = cacheFile,
            clock = { epochMillis("2026-08-15") },
        )
        source.current()
        assertEquals(true, cacheFile.toFile().isFile)
    }

    @Test
    fun second_read_does_not_call_fetch() {
        var cacheFile = Files.createTempDirectory("mp-hit").resolve("dgs10.csv")
        var calls = 0
        var source = CachedObservedMarketParamsSource(
            fetchCsv = {
                calls += 1
                FRED_CSV
            },
            cacheFile = cacheFile,
            ttl = Duration.ofDays(1),
            clock = { 1_000_000L },
        )
        source.current()
        source.current()
        assertEquals(1, calls)
    }
}

private const val FRED_CSV = "observation_date,DGS10\n2026-08-13,4.25\n"

private fun epochMillis(isoDate: String): Long =
    LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
