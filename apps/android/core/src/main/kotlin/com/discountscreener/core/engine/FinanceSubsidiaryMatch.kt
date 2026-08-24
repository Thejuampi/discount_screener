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
        var words = mutableListOf<String>()
        var word = StringBuilder()
        for (c in raw.lowercase()) {
            if (c in 'a'..'z' || c in '0'..'9') {
                word.append(c)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word = StringBuilder()
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words.filter { it !in LEGAL }.joinToString(" ")
    }
}
