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

object MacroPolicy {
    const val VERSION = "macro-policy/1"

    fun resolve(asOfEpochMillis: Long): MacroResolution {
        var asOf = Instant.ofEpochMilli(asOfEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()
        var rows = ValuationPolicy.current.macro.rows
        var row = rows.lastOrNull { LocalDate.parse(it.asOfDate) <= asOf }
        return if (row != null) {
            MacroResolution(
                nominalGrowthCeilingBps = row.valueBps,
                asOfDate = row.asOfDate,
                source = row.source,
            )
        } else {
            MacroResolution(
                nominalGrowthCeilingBps = BOOTSTRAP_MACRO_STABLE_GROWTH_BPS,
                asOfDate = "bootstrap",
                source = "bootstrap",
            )
        }
    }
}
