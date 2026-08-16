package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinanceSubsidiaryMatchTest {
    @Test
    fun parent_stem_plus_financial_matches() {
        var pick = FinanceSubsidiaryMatch.pick(
            parentRegistrant = "General Motors Co",
            candidates = listOf(
                NamedFiler("0001467858", "General Motors Co"),
                NamedFiler("0000804269", "General Motors Financial Company, Inc."),
            ),
        )
        assertEquals("0000804269", pick?.cik)
    }

    @Test
    fun parent_stem_plus_credit_matches() {
        var pick = FinanceSubsidiaryMatch.pick(
            parentRegistrant = "Ford Motor Co",
            candidates = listOf(
                NamedFiler("0000037996", "Ford Motor Co"),
                NamedFiler("0000038009", "Ford Motor Credit Company LLC"),
            ),
        )
        assertEquals("0000038009", pick?.cik)
    }

    @Test
    fun missing_finance_name_stays_empty() {
        assertNull(
            FinanceSubsidiaryMatch.pick(
                parentRegistrant = "Tesla, Inc.",
                candidates = listOf(NamedFiler("0001318605", "Tesla, Inc.")),
            ),
        )
    }

    @Test
    fun parent_stem_plus_capital_matches() {
        var pick = FinanceSubsidiaryMatch.pick(
            parentRegistrant = "Deere & Company",
            candidates = listOf(
                NamedFiler("0000315189", "Deere & Company"),
                NamedFiler("0000027673", "John Deere Capital Corporation"),
            ),
        )
        assertEquals("0000027673", pick?.cik)
    }
}
