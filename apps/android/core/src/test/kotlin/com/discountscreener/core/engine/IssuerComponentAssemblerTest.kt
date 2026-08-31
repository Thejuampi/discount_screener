package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssuerComponentAssemblerTest {
    @Test
    fun factory_ebit_is_total_minus_lender() {
        var xml = javaClass.classLoader!!.getResource("xbrl/mixed-factory-lender.xml")!!.readText()
        var set = IssuerComponentAssembler.fromParentFacts(
            facts = XbrlDimensionalFacts.parse(xml),
            finance = FinancialComponentDrivers(
                bookEquity = listOf(com.discountscreener.core.model.AnnualReportedValue("2025-12-31", 15_813_000_000.0)),
                netIncome = listOf(com.discountscreener.core.model.AnnualReportedValue("2025-12-31", 2_058_000_000.0)),
                source = "subsidiary_companyfacts",
            ),
        )
        assertEquals(10_916_000_000.0, set.operating!!.ebit.single().value)
    }

    @Test
    fun material_lender_marks_the_set_mixed() {
        var xml = javaClass.classLoader!!.getResource("xbrl/mixed-factory-lender.xml")!!.readText()
        var set = IssuerComponentAssembler.fromParentFacts(
            facts = XbrlDimensionalFacts.parse(xml),
            finance = FinancialComponentDrivers(
                bookEquity = listOf(com.discountscreener.core.model.AnnualReportedValue("2025-12-31", 15_813_000_000.0)),
                netIncome = listOf(com.discountscreener.core.model.AnnualReportedValue("2025-12-31", 2_058_000_000.0)),
                source = "subsidiary_companyfacts",
            ),
        )
        assertTrue(set.isMixed())
    }

    @Test
    fun factory_depreciation_is_total_minus_lender() {
        var xml = javaClass.classLoader!!.getResource("xbrl/mixed-factory-lender.xml")!!.readText()
        var set = IssuerComponentAssembler.fromParentFacts(
            facts = XbrlDimensionalFacts.parse(xml),
            finance = FinancialComponentDrivers(
                bookEquity = listOf(com.discountscreener.core.model.AnnualReportedValue("2025-12-31", 15_813_000_000.0)),
                netIncome = listOf(com.discountscreener.core.model.AnnualReportedValue("2025-12-31", 2_058_000_000.0)),
                source = "subsidiary_companyfacts",
            ),
        )
        assertEquals(6_960_000_000.0, set.operating!!.da.single().value)
    }

    @Test
    fun factory_revenue_uses_total_minus_lender() {
        var xml = javaClass.classLoader!!.getResource("xbrl/mixed-factory-lender.xml")!!.readText()
        var set = IssuerComponentAssembler.fromParentFacts(
            facts = XbrlDimensionalFacts.parse(xml),
            finance = FinancialComponentDrivers(
                bookEquity = listOf(com.discountscreener.core.model.AnnualReportedValue("2025-12-31", 15_813_000_000.0)),
                netIncome = listOf(com.discountscreener.core.model.AnnualReportedValue("2025-12-31", 2_058_000_000.0)),
                source = "subsidiary_companyfacts",
            ),
        )
        assertEquals(167_745_000_000.0, set.operating!!.revenue.single().value)
    }

    @Test
    fun paccar_financial_services_member_marks_the_lender_arm() {
        var xml = javaClass.classLoader!!.getResource("xbrl/pcar-financial-services.xml")!!.readText()
        var set = IssuerComponentAssembler.fromParentFacts(
            facts = XbrlDimensionalFacts.parse(xml),
            finance = null,
        )
        assertEquals(true, set.missingLenderBook())
    }
}
