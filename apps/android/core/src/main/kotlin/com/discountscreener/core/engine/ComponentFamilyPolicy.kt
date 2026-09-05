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

    fun parentHostsCaptive(industryName: String?, sectorName: String?): Boolean {
        var blob = "${industryName.orEmpty()} ${sectorName.orEmpty()}".lowercase()
        if (blob.isBlank()) return false
        return ValuationPolicy.current.component.captiveParentIndustryContains.any { needle ->
            needle.isNotBlank() && blob.contains(needle.lowercase())
        }
    }

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

    // Split camel case: break before an uppercase char that follows a lowercase char, and before
    // the last uppercase char of an acronym run when a lowercase char follows it.
    fun tokens(memberQname: String): List<String> {
        var local = memberQname.substringAfterLast(':')
        if (local.endsWith("Member")) local = local.removeSuffix("Member")
        var words = mutableListOf<String>()
        var word = StringBuilder()
        for (i in local.indices) {
            var c = local[i]
            var lower = c.lowercaseChar()
            if (lower !in 'a'..'z' && lower !in '0'..'9') {
                if (word.isNotEmpty()) {
                    words += word.toString()
                    word = StringBuilder()
                }
                continue
            }
            var prev = if (i > 0) local[i - 1] else null
            var next = if (i + 1 < local.length) local[i + 1] else null
            var breaks = c in 'A'..'Z' && prev != null &&
                (prev in 'a'..'z' || (prev in 'A'..'Z' && next != null && next in 'a'..'z'))
            if (breaks && word.isNotEmpty()) {
                words += word.toString()
                word = StringBuilder()
            }
            word.append(lower)
        }
        if (word.isNotEmpty()) words += word.toString()
        return words
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
