package com.discountscreener.core.engine

data class NamedFiler(
    val cik: String,
    val name: String,
)

/**
 * Bind a parent registrant to a finance-arm filer by name shape.
 * The parent CIK never maps through a ticker table.
 */
object FinanceSubsidiaryMatch {
    private val LEGAL: Set<String>
        get() = ValuationPolicy.current.component.legalTokens
    private val ARM: Set<String>
        get() = ValuationPolicy.current.component.financeArmTokens

    fun pick(parentRegistrant: String, candidates: List<NamedFiler>): NamedFiler? {
        var stemWords = normalize(parentRegistrant).split(" ").filter { it.isNotBlank() }
        if (stemWords.isEmpty()) return null
        return candidates.firstOrNull { filer ->
            var nameWords = normalize(filer.name).split(" ").filter { it.isNotBlank() }
            nameWords != stemWords &&
                nameWords.containsAll(stemWords) &&
                nameWords.any { it in ARM }
        }
    }

    fun normalize(raw: String): String {
        var cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        var words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() && it !in LEGAL }
        return words.joinToString(" ")
    }
}
