package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XbrlDimensionalFactsTest {
    @Test
    fun parse_keeps_member_tagged_facts() {
        var facts = XbrlDimensionalFacts.parse(fixture())
        assertTrue(facts.any { it.concept.endsWith("Revenues") && it.members.any { member -> member.contains("Financial") } })
    }

    @Test
    fun geo_slice_of_the_lender_is_ignored_for_totals() {
        var facts = XbrlDimensionalFacts.parse(fixture())
        var financeRevenue = facts.filter {
            it.concept.endsWith("Revenues") &&
                ComponentFamilyPolicy.role(it.members) == ComponentFactRole.Financial
        }
        assertEquals(1, financeRevenue.size)
    }

    private fun fixture(): String {
        var url = javaClass.classLoader!!.getResource("xbrl/mixed-factory-lender.xml")!!
        return url.readText()
    }
}
