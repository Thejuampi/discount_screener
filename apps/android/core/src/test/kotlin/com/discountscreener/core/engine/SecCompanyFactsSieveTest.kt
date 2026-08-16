package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecCompanyFactsSieveTest {
    @Test
    fun unused_us_gaap_concepts_are_dropped() {
        var raw = """
            {"cik":320193,"facts":{"dei":{"EntityName":{"units":{"USD":[]}}},
            "us-gaap":{
              "InterestExpense":{"units":{"USD":[{"fp":"FY","form":"10-K","end":"2024-09-30","val":1}]}},
              "HugeUnusedConcept":{"label":"junk { not a structure }","units":{"USD":[{"fp":"FY","val":999}]}}
            }}}
        """.trimIndent()
        var slim = SecCompanyFactsSieve.sieve(raw.reader(), setOf("InterestExpense"))
        assertFalse(slim.contains("HugeUnusedConcept"))
    }

    @Test
    fun allowed_interest_expense_is_kept() {
        var raw = """
            {"facts":{"us-gaap":{
              "InterestExpense":{"units":{"USD":[{"fp":"FY","form":"10-K","end":"2024-09-30","val":1}]}}
            }}}
        """.trimIndent()
        var slim = SecCompanyFactsSieve.sieve(raw.reader(), setOf("InterestExpense"))
        assertTrue(slim.contains("InterestExpense"))
    }

    @Test
    fun a_brace_inside_an_unused_string_does_not_break_the_sieve() {
        var raw = """
            {"facts":{"us-gaap":{
              "InterestExpense":{"units":{"USD":[{"val":1}]}},
              "Unused":{"label":"looks like {\"end\":1}"}
            }}}
        """.trimIndent()
        var slim = SecCompanyFactsSieve.sieve(raw.reader(), setOf("InterestExpense"))
        assertFalse(slim.contains("Unused"))
    }

    @Test
    fun default_sieve_keeps_acgl_dividend_and_unh_minority_tags() {
        var allowed = SecCompanyFactsSieve.defaultAllowedQnames
        assertTrue(
            allowed.contains("DividendsCommonStockCash") &&
                allowed.contains("MinorityInterest"),
        )
    }

    @Test
    fun default_sieve_keeps_residual_qnames_from_jpm_companyfacts() {
        var raw = fixture("sec-companyfacts/JPM.json")
        var slim = SecCompanyFactsSieve.sieve(raw.reader())
        assertTrue(SecResidualFacts.extract(slim) != null)
    }

    private fun fixture(path: String): String {
        var stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path))
        return stream.bufferedReader().use { it.readText() }
    }
}
