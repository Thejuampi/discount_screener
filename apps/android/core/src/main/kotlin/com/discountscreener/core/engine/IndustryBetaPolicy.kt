package com.discountscreener.core.engine

/**
 * Versioned industry-beta prior table (parity with
 * `shared/contracts/industry-beta-policy-v1.json` and Windows `dcf_model`).
 *
 * Match order: industryKey → industryNameContains → sectorKey → sectorNameContains → default.
 * Unmapped names use the default prior with provisional provenance.
 */
data class IndustryBetaPrior(
    val betaMillis: Int,
    val entryId: String,
    val throughCycle: Boolean,
    val provisional: Boolean,
    val policyVersion: String = INDUSTRY_BETA_POLICY_VERSION,
)

private data class IndustryBetaEntry(
    val id: String,
    val industryKeys: List<String> = emptyList(),
    val industryNameContains: List<String> = emptyList(),
    val sectorKeys: List<String> = emptyList(),
    val sectorNameContains: List<String> = emptyList(),
    val betaMillis: Int,
    val throughCycle: Boolean,
)

/** Embedded table — keep cent-for-cent with `industry-beta-policy-v1.json` (contract tests assert). */
private val INDUSTRY_BETA_ENTRIES: List<IndustryBetaEntry> = listOf(
    IndustryBetaEntry(
        id = "oil_gas_ep",
        industryKeys = listOf("oil-gas-e-p"),
        industryNameContains = listOf(
            "oil & gas e&p",
            "oil & gas exploration",
            "oil and gas e&p",
            "oil and gas exploration",
        ),
        betaMillis = 1_500,
        throughCycle = true,
    ),
    IndustryBetaEntry(
        id = "oil_gas_integrated",
        industryKeys = listOf("oil-gas-integrated"),
        industryNameContains = listOf("oil & gas integrated", "oil and gas integrated"),
        betaMillis = 1_400,
        throughCycle = true,
    ),
    IndustryBetaEntry(
        id = "specialty_chemicals",
        industryKeys = listOf("specialty-chemicals"),
        industryNameContains = listOf("specialty chemicals"),
        betaMillis = 1_300,
        throughCycle = true,
    ),
    IndustryBetaEntry(
        id = "utilities",
        sectorKeys = listOf("utilities"),
        sectorNameContains = listOf("utilit"),
        betaMillis = 600,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "consumer_staples",
        sectorKeys = listOf("consumer-defensive", "consumer-staples"),
        sectorNameContains = listOf("consumer staples", "consumer defensive"),
        betaMillis = 700,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "healthcare",
        industryNameContains = listOf("pharma"),
        sectorKeys = listOf("healthcare"),
        sectorNameContains = listOf("health"),
        betaMillis = 900,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "semiconductors",
        industryKeys = listOf("semiconductors", "semiconductor-equipment-materials"),
        industryNameContains = listOf("semiconductor"),
        betaMillis = 1_300,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "software_technology",
        industryKeys = listOf(
            "software-infrastructure",
            "software-application",
            "software-systems",
        ),
        industryNameContains = listOf("software", "information technology"),
        sectorKeys = listOf("technology"),
        sectorNameContains = listOf("technolog"),
        betaMillis = 1_200,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "energy_sector",
        sectorKeys = listOf("energy"),
        sectorNameContains = listOf("energy"),
        betaMillis = 1_100,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "financials",
        industryNameContains = listOf("bank", "insurance"),
        sectorKeys = listOf("financial-services", "financials"),
        sectorNameContains = listOf("financial"),
        betaMillis = 900,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "real_estate",
        industryNameContains = listOf("reit"),
        sectorKeys = listOf("real-estate"),
        sectorNameContains = listOf("real estate"),
        betaMillis = 850,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "consumer_cyclical",
        sectorKeys = listOf("consumer-cyclical", "consumer-discretionary"),
        sectorNameContains = listOf("consumer cyclical", "consumer discretionary"),
        betaMillis = 1_100,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "communication_services",
        industryKeys = listOf("telecom-services"),
        industryNameContains = listOf("telecom"),
        sectorKeys = listOf("communication-services"),
        sectorNameContains = listOf("communication services"),
        betaMillis = 900,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "industrials",
        sectorKeys = listOf("industrials"),
        sectorNameContains = listOf("industrial"),
        betaMillis = 1_000,
        throughCycle = false,
    ),
    IndustryBetaEntry(
        id = "basic_materials",
        sectorKeys = listOf("basic-materials"),
        sectorNameContains = listOf("basic materials"),
        betaMillis = 1_100,
        throughCycle = false,
    ),
)

fun resolveIndustryBetaPrior(
    sectorName: String?,
    industryName: String?,
    sectorKey: String? = null,
    industryKey: String? = null,
): IndustryBetaPrior {
    val sk = sectorKey?.trim()?.lowercase().orEmpty()
    val ik = industryKey?.trim()?.lowercase().orEmpty()
    val sn = sectorName?.trim()?.lowercase().orEmpty()
    val inn = industryName?.trim()?.lowercase().orEmpty()

    val matched = matchIndustryBetaEntry(sk, ik, sn, inn)
    return if (matched != null) {
        IndustryBetaPrior(
            betaMillis = matched.betaMillis,
            entryId = matched.id,
            throughCycle = matched.throughCycle,
            provisional = false,
        )
    } else {
        IndustryBetaPrior(
            betaMillis = ValuationPolicy.current.dcf.defaultIndustryBetaMillis,
            entryId = "default",
            throughCycle = false,
            provisional = true,
        )
    }
}

private fun matchIndustryBetaEntry(
    sectorKey: String,
    industryKey: String,
    sectorName: String,
    industryName: String,
): IndustryBetaEntry? {
    if (industryKey.isNotEmpty()) {
        for (entry in INDUSTRY_BETA_ENTRIES) {
            if (entry.industryKeys.any { it.equals(industryKey, ignoreCase = true) }) {
                return entry
            }
        }
    }
    if (industryName.isNotEmpty()) {
        for (entry in INDUSTRY_BETA_ENTRIES) {
            if (entry.industryNameContains.any { industryName.contains(it.lowercase()) }) {
                return entry
            }
        }
    }
    if (sectorKey.isNotEmpty()) {
        for (entry in INDUSTRY_BETA_ENTRIES) {
            if (entry.sectorKeys.any { it.equals(sectorKey, ignoreCase = true) }) {
                return entry
            }
        }
    }
    if (sectorName.isNotEmpty()) {
        for (entry in INDUSTRY_BETA_ENTRIES) {
            if (entry.sectorNameContains.any { sectorName.contains(it.lowercase()) }) {
                return entry
            }
        }
    }
    return null
}

/** Contract-test surface: entry id → beta millis for golden parity. */
fun industryBetaPolicyEntrySnapshots(): List<Triple<String, Int, Boolean>> =
    INDUSTRY_BETA_ENTRIES.map { Triple(it.id, it.betaMillis, it.throughCycle) }
