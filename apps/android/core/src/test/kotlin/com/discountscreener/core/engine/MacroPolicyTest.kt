package com.discountscreener.core.engine

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class MacroPolicyTest {
    @Test
    fun dated_2026_ceiling_is_cbo_nominal_380() {
        var resolved = MacroPolicy.resolve(epochDay("2026-08-15"))
        assertEquals(380, resolved.nominalGrowthCeilingBps)
    }

    @Test
    fun date_before_any_row_uses_bootstrap_300() {
        var resolved = MacroPolicy.resolve(epochDay("2000-01-01"))
        assertEquals(300, resolved.nominalGrowthCeilingBps)
    }
}

private fun epochDay(isoDate: String): Long =
    LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
