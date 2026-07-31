package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecDriverNormalizationPolicyTest {
    @Test
    fun `CRGY development and property acquisition have separate economics`() {
        assertEquals(
            SecDriverNormalizationPolicy.InvestmentCategory.Development,
            SecDriverNormalizationPolicy.investmentCategory("PaymentsToExploreAndDevelopOilAndGasProperties"),
        )
        assertEquals(
            SecDriverNormalizationPolicy.InvestmentCategory.PropertyAcquisition,
            SecDriverNormalizationPolicy.investmentCategory("PaymentsToAcquireOilAndGasProperty"),
        )
        assertTrue(
            SecDriverNormalizationPolicy.isAcceptedRecurringDevelopment(
                "PaymentsToExploreAndDevelopOilAndGasProperties", "USD", 365, "10-K", true,
            ),
        )
    }

    @Test
    fun `generated contract admits the five frozen issuer development concepts`() {
        listOf(
            "PaymentsToExploreAndDevelopOilAndGasProperties", // CRGY
            "PaymentsToAcquireProductiveAssets", // T, F
            "PaymentsToAcquirePropertyPlantAndEquipment", // XOM, TSLA, KO
        ).forEach { concept ->
            assertTrue(
                SecDriverNormalizationPolicy.isAcceptedRecurringDevelopment(
                    concept, "USD", 365, "10-K", true,
                ),
                concept,
            )
        }
    }

    @Test
    fun `generated operators carry every SEC driver QName list and normalization metadata`() {
        val expected = mapOf(
            SecDriverNormalizationPolicy.Driver.OperatingCashFlow to Triple("USD", SecDriverNormalizationPolicy.PeriodShape.Duration, "select_one_equivalent"),
            SecDriverNormalizationPolicy.Driver.Revenue to Triple("USD", SecDriverNormalizationPolicy.PeriodShape.Duration, "select_one_equivalent"),
            SecDriverNormalizationPolicy.Driver.InterestExpense to Triple("USD", SecDriverNormalizationPolicy.PeriodShape.Duration, "select_one_equivalent"),
            SecDriverNormalizationPolicy.Driver.TotalDebt to Triple("USD", SecDriverNormalizationPolicy.PeriodShape.Instant, "select_one_equivalent"),
            SecDriverNormalizationPolicy.Driver.CurrentDebt to Triple("USD", SecDriverNormalizationPolicy.PeriodShape.Instant, "sum_disjoint_components"),
            SecDriverNormalizationPolicy.Driver.NonCurrentDebt to Triple("USD", SecDriverNormalizationPolicy.PeriodShape.Instant, "sum_disjoint_components"),
            SecDriverNormalizationPolicy.Driver.TaxExpense to Triple("USD", SecDriverNormalizationPolicy.PeriodShape.Duration, "derive_effective_tax"),
            SecDriverNormalizationPolicy.Driver.PretaxIncome to Triple("USD", SecDriverNormalizationPolicy.PeriodShape.Duration, "derive_effective_tax"),
            SecDriverNormalizationPolicy.Driver.MarginalTaxReference to Triple("pure", SecDriverNormalizationPolicy.PeriodShape.Duration, "reference_policy"),
        )

        expected.forEach { (driver, metadata) ->
            val operator = SecDriverNormalizationPolicy.operator(driver)
            assertTrue(operator.qnames.isNotEmpty(), driver.name)
            assertEquals(metadata.first, operator.unit, driver.name)
            assertEquals(metadata.second, operator.periodShape, driver.name)
            assertEquals(metadata.third, operator.operation, driver.name)
        }
        assertEquals(
            "NetCashProvidedByUsedInOperatingActivities",
            SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.OperatingCashFlow).qnames.first(),
        )
        assertEquals(
            "DebtAndCapitalLeaseObligations",
            SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.TotalDebt).qnames.first(),
        )
    }

    @Test
    fun `period unit form and consolidation are acceptance boundaries`() {
        assertFalse(
            SecDriverNormalizationPolicy.isAcceptedRecurringDevelopment(
                "PaymentsToAcquirePropertyPlantAndEquipment", "shares", 365, "10-K", true,
            ),
        )
        assertFalse(
            SecDriverNormalizationPolicy.isAcceptedRecurringDevelopment(
                "PaymentsToAcquirePropertyPlantAndEquipment", "USD", 91, "10-K", true,
            ),
        )
        assertFalse(
            SecDriverNormalizationPolicy.isAcceptedRecurringDevelopment(
                "PaymentsToAcquirePropertyPlantAndEquipment", "USD", 365, "10-Q", true,
            ),
        )
        assertFalse(
            SecDriverNormalizationPolicy.isAcceptedRecurringDevelopment(
                "PaymentsToAcquirePropertyPlantAndEquipment", "USD", 365, "10-K", false,
            ),
        )
    }
}
