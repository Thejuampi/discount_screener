package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecDriverNormalizationPolicyTest {
    @Test
    fun retained_qnames_include_interest_expense() {
        assertTrue(SecDriverNormalizationPolicy.retainedQnames.contains("InterestExpense"))
    }

    @Test
    fun finance_lease_interest_is_not_an_expense_equivalent() {
        var qnames = SecDriverNormalizationPolicy.operator(
            SecDriverNormalizationPolicy.Driver.InterestExpense,
        ).qnames
        assertTrue("FinanceLeaseInterestExpense" !in qnames)
    }

    @Test
    fun oil_and_gas_property_and_equipment_is_recurring_development() {
        var category = SecDriverNormalizationPolicy.investmentCategory(
            "PaymentsToAcquireOilAndGasPropertyAndEquipment",
        )
        assertEquals(SecDriverNormalizationPolicy.InvestmentCategory.Development, category)
    }

    @Test
    fun oil_and_gas_property_without_equipment_stays_acquisition() {
        var category = SecDriverNormalizationPolicy.investmentCategory(
            "PaymentsToAcquireOilAndGasProperty",
        )
        assertEquals(SecDriverNormalizationPolicy.InvestmentCategory.PropertyAcquisition, category)
    }

    @Test
    fun oil_well_program_adds_to_other_plant() {
        var total = SecDriverNormalizationPolicy.recurringDevelopmentTotal(
            tangibleDollars = 479_000_000.0,
            wellsDollars = 6_115_000_000.0,
            tangibleConcept = "PaymentsToAcquireOtherPropertyPlantAndEquipment",
        )
        assertEquals(6_594_000_000.0, total)
    }

    @Test
    fun software_development_adds_to_plant() {
        var total = SecDriverNormalizationPolicy.recurringDevelopmentTotal(
            tangibleDollars = 154_000_000.0,
            wellsDollars = null,
            tangibleConcept = "PaymentsToAcquirePropertyPlantAndEquipment",
            softwareDollars = 835_000_000.0,
        )
        assertEquals(989_000_000.0, total)
    }

    @Test
    fun software_inside_productive_assets_is_not_added_twice() {
        var total = SecDriverNormalizationPolicy.recurringDevelopmentTotal(
            tangibleDollars = 833_000_000.0,
            wellsDollars = null,
            tangibleConcept = "PaymentsToAcquireProductiveAssets",
            softwareDollars = 665_000_000.0,
        )
        assertEquals(833_000_000.0, total)
    }

    @Test
    fun software_development_alone_still_reports_capex() {
        var total = SecDriverNormalizationPolicy.recurringDevelopmentTotal(
            tangibleDollars = null,
            wellsDollars = null,
            tangibleConcept = null,
            softwareDollars = 40_000_000.0,
        )
        assertEquals(40_000_000.0, total)
    }

    @Test
    fun retained_qnames_include_software_development() {
        assertTrue(SecDriverNormalizationPolicy.retainedQnames.contains("PaymentsToDevelopSoftware"))
    }

    @Test
    fun intangible_purchases_add_to_plant() {
        var total = SecDriverNormalizationPolicy.recurringDevelopmentTotal(
            tangibleDollars = 11_750_000_000.0,
            wellsDollars = null,
            tangibleConcept = "PaymentsToAcquirePropertyPlantAndEquipment",
            softwareDollars = null,
            intangiblesDollars = 2_658_000_000.0,
        )
        assertEquals(14_408_000_000.0, total)
    }

    @Test
    fun intangibles_inside_productive_assets_are_not_added_twice() {
        var total = SecDriverNormalizationPolicy.recurringDevelopmentTotal(
            tangibleDollars = 20_263_000_000.0,
            wellsDollars = null,
            tangibleConcept = "PaymentsToAcquireProductiveAssets",
            softwareDollars = null,
            intangiblesDollars = 2_658_000_000.0,
        )
        assertEquals(20_263_000_000.0, total)
    }

    @Test
    fun retained_qnames_include_intangible_purchases() {
        assertTrue(
            SecDriverNormalizationPolicy.retainedQnames.contains("PaymentsToAcquireIntangibleAssets"),
        )
    }
}
