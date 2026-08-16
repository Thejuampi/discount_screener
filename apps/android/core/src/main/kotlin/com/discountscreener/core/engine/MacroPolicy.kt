package com.discountscreener.core.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class MacroResolution(
    val nominalGrowthCeilingBps: Int,
    val asOfDate: String,
    val source: String,
    val policyVersion: String = MacroPolicy.VERSION,
)

private data class MacroRow(
    val asOfDate: String,
    val nominalGrowthCeilingBps: Int,
    val source: String,
)

object MacroPolicy {
    const val VERSION = "macro-policy/1"

    fun resolve(asOfEpochMillis: Long): MacroResolution {
        var asOf = Instant.ofEpochMilli(asOfEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()
        var row = MACRO_ROWS.lastOrNull { LocalDate.parse(it.asOfDate) <= asOf }
            ?: BOOTSTRAP_ROW
        return MacroResolution(
            nominalGrowthCeilingBps = row.nominalGrowthCeilingBps,
            asOfDate = row.asOfDate,
            source = row.source,
        )
    }
}

private val BOOTSTRAP_ROW = MacroRow(
    asOfDate = "bootstrap",
    nominalGrowthCeilingBps = BOOTSTRAP_MACRO_STABLE_GROWTH_BPS,
    source = "bootstrap",
)

/**
 * Long-run US nominal growth ceiling for g_stable.
 * 2026: CBO-style real ~1.8% + PCE 2.0% → 380 bps. Not a perpetual 300.
 */
private val MACRO_ROWS = listOf(
    MacroRow("2026-01-01", 380, "cbo_long_run_nominal_2026"),
)
