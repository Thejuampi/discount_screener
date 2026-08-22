package com.discountscreener.core.engine

/**
 * Versioned industry FCFF-margin targets for the fade path.
 * Unmapped industry keeps the issuer's own margin (no invented cut).
 */
object IndustryOperatingPathPolicy {
    val VERSION: String
        get() = ValuationPolicy.current.industryOperatingPath.version

    data class Prior(
        val id: String,
        val targetFcffMarginBps: Int,
        val matched: Boolean,
    )

    fun resolve(industry: String?, sector: String?): Prior {
        var industryText = industry.orEmpty().lowercase()
        var sectorText = sector.orEmpty().lowercase()
        var entries = ValuationPolicy.current.industryOperatingPath.entries
        for (entry in entries) {
            if (entry.industryContains.any { industryText.contains(it) }) {
                return Prior(entry.id, entry.targetFcffMarginBps, matched = true)
            }
        }
        for (entry in entries) {
            if (entry.sectorContains.any { sectorText.contains(it) }) {
                return Prior(entry.id, entry.targetFcffMarginBps, matched = true)
            }
        }
        return Prior("issuer_margin", 0, matched = false)
    }
}
