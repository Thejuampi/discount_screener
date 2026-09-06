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
    fun a_quarter_never_reaches_the_output() {
        var slim = SecCompanyFactsSieve.sieve(
            factsOf(
                """{"fp":"Q3","form":"10-Q","end":"2024-06-30","val":3}""",
                """{"fp":"FY","form":"10-K","end":"2024-09-30","val":1}""",
            ).reader(),
            setOf("InterestExpense"),
        )
        assertFalse(slim.contains("2024-06-30"))
    }

    @Test
    fun a_form_that_is_not_a_ten_k_never_reaches_the_output() {
        var slim = SecCompanyFactsSieve.sieve(
            factsOf("""{"fp":"FY","form":"8-K","end":"2024-09-30","val":1}""").reader(),
            setOf("InterestExpense"),
        )
        assertFalse(slim.contains("InterestExpense"))
    }

    @Test
    fun a_dimensional_fact_never_reaches_the_output() {
        var slim = SecCompanyFactsSieve.sieve(
            factsOf(
                """{"fp":"FY","form":"10-K","end":"2024-09-30","val":1,"segment":{"dim":"Americas"}}""",
            ).reader(),
            setOf("InterestExpense"),
        )
        assertFalse(slim.contains("Americas"))
    }

    @Test
    fun a_null_segment_stays_in_the_output() {
        var slim = SecCompanyFactsSieve.sieve(
            factsOf("""{"fp":"FY","form":"10-K","end":"2024-09-30","val":1,"segment":null}""").reader(),
            setOf("InterestExpense"),
        )
        assertTrue(slim.contains("\"segment\":null"))
    }

    @Test
    fun the_fields_no_reader_asks_for_never_reach_the_output() {
        var slim = SecCompanyFactsSieve.sieve(
            factsOf(
                """{"fp":"FY","form":"10-K","end":"2024-09-30","val":1,""" +
                    """"accn":"0000320193-24-000123","fy":2024,"frame":"CY2024"}""",
            ).reader(),
            setOf("InterestExpense"),
        )
        assertEquals(
            """{"facts":{"us-gaap":{"InterestExpense":{"units":{"USD":""" +
                """[{"fp":"FY","form":"10-K","end":"2024-09-30","val":1}]}}}}}""",
            slim,
        )
    }

    @Test
    fun a_concept_with_no_annual_fact_leaves_no_empty_shell() {
        var slim = SecCompanyFactsSieve.sieve(
            factsOf("""{"fp":"Q1","form":"10-Q","end":"2024-12-31","val":1}""").reader(),
            setOf("InterestExpense"),
        )
        assertEquals("""{"facts":{}}""", slim)
    }

    /**
     * SEC sends the shape it wants to send. A concept that arrives as null, or a `units` that is
     * not an object, must cost that concept and nothing else. Before, the sieve stopped there and
     * the company lost every fact it had.
     */
    @Test
    fun a_concept_that_is_not_an_object_costs_only_that_concept() {
        var raw = """
            {"facts":{"us-gaap":{
              "InterestExpense":null,
              "OperatingIncomeLoss":{"units":{"USD":[{"fp":"FY","form":"10-K","end":"2024-09-30","val":7}]}}
            }}}
        """.trimIndent()
        var slim = SecCompanyFactsSieve.sieve(raw.reader(), setOf("InterestExpense", "OperatingIncomeLoss"))
        assertTrue(slim.contains("OperatingIncomeLoss"))
    }

    @Test
    fun a_units_that_is_not_an_object_costs_only_that_concept() {
        var raw = """
            {"facts":{"us-gaap":{
              "InterestExpense":{"units":[]},
              "OperatingIncomeLoss":{"units":{"USD":[{"fp":"FY","form":"10-K","end":"2024-09-30","val":7}]}}
            }}}
        """.trimIndent()
        var slim = SecCompanyFactsSieve.sieve(raw.reader(), setOf("InterestExpense", "OperatingIncomeLoss"))
        assertTrue(slim.contains("OperatingIncomeLoss"))
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

    /**
     * The cost the sieve exists to hold down. A companyfacts file is about 4 MB, and everything
     * downstream - the string, the cache file, the parsed tree - is a multiple of what the sieve
     * lets through. The fixtures are the same shape, so a regression here is a regression on the
     * phone.
     */
    @Test
    fun the_sieve_keeps_under_a_fifth_of_the_source() {
        var raw = fixture("sec-companyfacts/JPM.json")
        var slim = SecCompanyFactsSieve.sieve(raw.reader())
        assertTrue(slim.length * 5 < raw.length, "kept ${slim.length} of ${raw.length} chars")
    }

    private fun factsOf(vararg facts: String): String =
        """{"facts":{"us-gaap":{"InterestExpense":{"label":"Interest","units":{"USD":[""" +
            facts.joinToString(",") +
            """]}}}}}"""

    private fun fixture(path: String): String {
        var stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path))
        return stream.bufferedReader().use { it.readText() }
    }
}
