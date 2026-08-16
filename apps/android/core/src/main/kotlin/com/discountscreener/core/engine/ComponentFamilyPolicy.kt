package com.discountscreener.core.engine

const val COMPONENT_SOTP_VERSION = "component-sotp/2"

enum class ComponentEconomy {
    Operating,
    Financial,
}

enum class ComponentFactRole {
    Operating,
    Financial,
    TotalOperatingSegments,
    Ignore,
}

/**
 * Member names decide the economy. Ticker symbols do not.
 * Tokens stay in the local name after `Member` is dropped.
 */
object ComponentFamilyPolicy {
    val MATERIAL_REVENUE_BPS: Int
        get() = ValuationPolicy.current.component.materialRevenueBps

    private val FINANCIAL_TOKENS: Set<String>
        get() = ValuationPolicy.current.component.financialTokens
    private val INSTRUMENT_TOKENS: Set<String>
        get() = ValuationPolicy.current.component.instrumentTokens
    private val OPERATING_TOKENS: Set<String>
        get() = ValuationPolicy.current.component.operatingTokens
    private val SLICE_TOKENS: Set<String>
        get() = ValuationPolicy.current.component.sliceTokens
    private val STRUCTURAL_LOCAL: Set<String>
        get() = ValuationPolicy.current.component.structuralLocal

    fun tokens(memberQname: String): List<String> {
        var local = memberQname.substringAfterLast(':')
        if (local.endsWith("Member")) local = local.removeSuffix("Member")
        var spaced = local
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return spaced.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
    }

    fun economy(memberQname: String): ComponentEconomy? {
        var local = memberQname.substringAfterLast(':').lowercase().replace("_", "")
        if (local.removeSuffix("member") in STRUCTURAL_LOCAL) return null
        var words = tokens(memberQname)
        if (words.any { it in INSTRUMENT_TOKENS } && words.any { it == "credit" }) return null
        if (words.any { it in FINANCIAL_TOKENS }) return ComponentEconomy.Financial
        if (words.any { it in OPERATING_TOKENS } || words.joinToString("") in OPERATING_TOKENS) {
            return ComponentEconomy.Operating
        }
        return null
    }

    fun role(members: List<String>): ComponentFactRole {
        if (members.isEmpty()) return ComponentFactRole.Ignore
        var economies = members.mapNotNull { economy(it) }
        var hasCountry = members.any { it.startsWith("country:", ignoreCase = true) }
        var slice = members.any { member ->
            tokens(member).any { it in SLICE_TOKENS } && economy(member) == null
        }
        var onlyStructural = members.all { economy(it) == null } &&
            members.any { it.substringAfterLast(':').equals("OperatingSegmentsMember", ignoreCase = true) }
        if (onlyStructural && members.size == 1) return ComponentFactRole.TotalOperatingSegments
        if (hasCountry || (slice && economies.isEmpty())) return ComponentFactRole.Ignore
        if (economies.any { it == ComponentEconomy.Financial } && !hasCountry && !extraSlice(members)) {
            return ComponentFactRole.Financial
        }
        if (economies.any { it == ComponentEconomy.Operating } &&
            economies.none { it == ComponentEconomy.Financial } &&
            !hasCountry &&
            !extraSlice(members)
        ) {
            return ComponentFactRole.Operating
        }
        return ComponentFactRole.Ignore
    }

    private fun extraSlice(members: List<String>): Boolean {
        var extras = members.filter { economy(it) == null }
        return extras.any { member ->
            var local = member.substringAfterLast(':')
            !local.equals("OperatingSegmentsMember", ignoreCase = true) &&
                tokens(member).any { it in SLICE_TOKENS }
        }
    }
}
