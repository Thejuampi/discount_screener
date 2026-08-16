package com.discountscreener.core.engine

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketParamsTest {
    @Test
    fun bootstrap_is_provisional() {
        assertEquals(true, MarketParams().provisional)
    }

    @Test
    fun bootstrap_keeps_frozen_rf_430() {
        assertEquals(430, MarketParams().rfBps)
    }

    @Test
    fun observed_uses_implied_erp_not_bootstrap_450() {
        var params = MarketParams.observed(
            rfBps = 425,
            asOfEpochMillis = epochDay("2026-08-15"),
        )
        assertEquals(442, params.erpBps)
    }

    @Test
    fun observed_with_current_table_is_not_provisional() {
        var params = MarketParams.observed(
            rfBps = 425,
            asOfEpochMillis = epochDay("2026-08-15"),
        )
        assertEquals(false, params.provisional)
    }

    @Test
    fun observed_with_stale_erp_stays_provisional() {
        var params = MarketParams.observed(
            rfBps = 425,
            asOfEpochMillis = epochDay("2027-02-01"),
        )
        assertEquals(true, params.provisional)
    }

    @Test
    fun observed_g_stable_uses_dated_macro_ceiling() {
        var params = MarketParams.observed(
            rfBps = 600,
            asOfEpochMillis = epochDay("2026-08-15"),
        )
        assertEquals(380, params.stableGrowthBps())
    }

    @Test
    fun bootstrap_g_stable_stays_300_for_parity() {
        assertEquals(300, MarketParams(rfBps = 600).stableGrowthBps())
    }

    @Test
    fun reason_codes_rebuild_the_same_display_label() {
        var params = MarketParams.observed(rfBps = 425, asOfEpochMillis = epochDay("2026-08-15"))
        assertEquals(
            params.displayLabel(),
            MarketParams.displayLabelFromReasonCodes(listOf(params.fingerprint())),
        )
    }
}

private fun epochDay(isoDate: String): Long =
    LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
