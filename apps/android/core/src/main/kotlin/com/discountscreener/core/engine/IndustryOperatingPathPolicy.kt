package com.discountscreener.core.engine

/**
 * Versioned industry FCFF-margin targets for the fade path.
 * Unmapped industry keeps the issuer's own margin (no invented cut).
 */
object IndustryOperatingPathPolicy {
    const val VERSION = "industry-operating-path/3"

    data class Prior(
        val id: String,
        val targetFcffMarginBps: Int,
        val matched: Boolean,
    )

    fun resolve(industry: String?, sector: String?): Prior {
        var industryText = industry.orEmpty().lowercase()
        var sectorText = sector.orEmpty().lowercase()
        for (entry in ENTRIES) {
            if (entry.industryContains.any { industryText.contains(it) }) {
                return Prior(entry.id, entry.targetFcffMarginBps, matched = true)
            }
        }
        for (entry in ENTRIES) {
            if (entry.sectorContains.any { sectorText.contains(it) }) {
                return Prior(entry.id, entry.targetFcffMarginBps, matched = true)
            }
        }
        return Prior("issuer_margin", 0, matched = false)
    }

    private data class Entry(
        val id: String,
        val industryContains: List<String>,
        val sectorContains: List<String> = emptyList(),
        val targetFcffMarginBps: Int,
    )

    private val ENTRIES = listOf(
        Entry("semiconductors", listOf("semiconductor"), targetFcffMarginBps = 4_500),
        Entry("software", listOf("software"), targetFcffMarginBps = 3_200),
        Entry("internet_content", listOf("internet content", "interactive media"), targetFcffMarginBps = 3_750),
        Entry("internet_retail", listOf("internet retail"), targetFcffMarginBps = 2_000),
        Entry("consumer_electronics", listOf("consumer electronics"), targetFcffMarginBps = 2_400),
        Entry("payments", listOf("credit services", "payment"), targetFcffMarginBps = 4_500),
        Entry("telecom", listOf("telecom"), targetFcffMarginBps = 1_500),
        Entry("pharma", listOf("drug manufacturer", "pharma"), targetFcffMarginBps = 1_600),
        Entry("oil_integrated", listOf("oil & gas integrated", "oil and gas integrated"), targetFcffMarginBps = 900),
        Entry(
            "oil_ep",
            listOf("oil & gas e&p", "oil and gas e&p", "oil & gas exploration", "oil and gas exploration"),
            targetFcffMarginBps = 900,
        ),
        Entry("discount_store", listOf("discount store"), targetFcffMarginBps = 400),
        Entry("home_improvement", listOf("home improvement"), targetFcffMarginBps = 1_000),
        Entry("household", listOf("household", "personal products"), targetFcffMarginBps = 1_800),
        Entry("auto", listOf("auto manufacturer"), targetFcffMarginBps = 1_000),
    )
}
