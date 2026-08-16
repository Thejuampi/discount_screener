package com.discountscreener.core.engine

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ErpPolicyTest {
    @Test
    fun implied_index_is_the_default_school() {
        assertEquals(ErpSchool.ImpliedIndex, ErpPolicy.DEFAULT_SCHOOL)
    }

    @Test
    fun implied_index_in_july_2026_is_damodaran_442() {
        var resolved = ErpPolicy.resolve(ErpSchool.ImpliedIndex, epochDay("2026-07-15"))
        assertEquals(442, resolved.erpBps)
    }

    @Test
    fun kroll_in_2026_is_the_500_recommendation() {
        var resolved = ErpPolicy.resolve(ErpSchool.KrollRecommended, epochDay("2026-08-15"))
        assertEquals(500, resolved.erpBps)
    }

    @Test
    fun current_implied_row_is_not_stale() {
        var resolved = ErpPolicy.resolve(ErpSchool.ImpliedIndex, epochDay("2026-08-15"))
        assertEquals(false, resolved.stale)
    }

    @Test
    fun implied_row_older_than_freshness_window_is_stale() {
        var resolved = ErpPolicy.resolve(ErpSchool.ImpliedIndex, epochDay("2027-02-01"))
        assertEquals(true, resolved.stale)
    }

    @Test
    fun school_table_has_no_firm_implied_cost_of_capital() {
        assertEquals(
            false,
            ErpSchool.entries.any { school ->
                school.name.contains("Firm", ignoreCase = true) ||
                    school.name.contains("Icc", ignoreCase = true)
            },
        )
    }
}

private fun epochDay(isoDate: String): Long =
    LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
