package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reading is about the last filed year only. Earlier charges are still added back in the
 * normalized series, because a growth rate read across them is wrong in the same way.
 */
class EarningsNormalizationTest {

    @Test
    fun a_charge_worth_a_third_of_the_year_is_measured_as_a_third() {
        assertEquals(3_333, earningsContamination(timeseries(charge = 30.0)).chargeShareBps)
    }

    @Test
    fun a_charge_worth_a_third_of_the_year_marks_it() {
        assertTrue(earningsContamination(timeseries(charge = 30.0)).latestYearContaminated)
    }

    /** Fourteen percent is under the contract's threshold and leaves the year standing. */
    @Test
    fun a_charge_under_the_threshold_leaves_the_year_alone() {
        assertFalse(earningsContamination(timeseries(charge = 12.0)).latestYearContaminated)
    }

    /** Exactly fifteen percent is inside the mark, not outside it. */
    @Test
    fun a_charge_at_the_threshold_marks_the_year() {
        assertTrue(earningsContamination(timeseries(charge = 13.5)).latestYearContaminated)
    }

    @Test
    fun a_year_with_no_charge_measures_zero() {
        assertEquals(0, earningsContamination(timeseries(charge = null)).chargeShareBps)
    }

    /** A filer that books the write-down as a negative number reports the same size. */
    @Test
    fun a_charge_filed_as_a_negative_number_is_read_by_its_size() {
        assertEquals(3_333, earningsContamination(timeseries(charge = -30.0)).chargeShareBps)
    }

    @Test
    fun the_normalized_series_adds_the_charge_back() {
        assertEquals(
            listOf(80.0, 120.0),
            earningsContamination(timeseries(charge = 30.0)).normalizedOperatingIncome.map { it.value },
        )
    }

    /** A year that had no charge keeps its filed number, so the two series stay comparable. */
    @Test
    fun a_clean_year_keeps_its_filed_operating_income() {
        assertEquals(80.0, earningsContamination(timeseries(charge = 30.0)).normalizedOperatingIncome.first().value)
    }

    /** A source with no operating line reads as unmeasured, never as clean. */
    @Test
    fun a_name_with_no_operating_income_is_not_measured() {
        assertNull(earningsContamination(FundamentalTimeseries()).chargeShareBps)
    }

    @Test
    fun a_name_with_no_operating_income_is_not_marked() {
        assertFalse(earningsContamination(FundamentalTimeseries()).latestYearContaminated)
    }

    /** A charge against a zero operating income has no scale, and the year is still not the business. */
    @Test
    fun a_charge_against_a_zero_operating_year_marks_it_without_a_size() {
        var reading = earningsContamination(timeseries(latestOperating = 0.0, charge = 30.0))

        assertTrue(reading.latestYearContaminated)
    }

    @Test
    fun a_charge_against_a_zero_operating_year_states_no_size() {
        var reading = earningsContamination(timeseries(latestOperating = 0.0, charge = 30.0))

        assertNull(reading.chargeShareBps)
    }

    /** The mark is about the last year. An old charge does not keep marking the name. */
    @Test
    fun a_charge_in_an_earlier_year_does_not_mark_the_latest_one() {
        var reading = earningsContamination(
            FundamentalTimeseries(
                operatingIncome = listOf(
                    AnnualReportedValue("2024-12-31", 80.0),
                    AnnualReportedValue("2025-12-31", 90.0),
                ),
                nonRecurringCharges = listOf(AnnualReportedValue("2024-12-31", 40.0)),
            ),
        )

        assertFalse(reading.latestYearContaminated)
    }

    private fun timeseries(latestOperating: Double = 90.0, charge: Double?) = FundamentalTimeseries(
        operatingIncome = listOf(
            AnnualReportedValue("2024-12-31", 80.0),
            AnnualReportedValue("2025-12-31", latestOperating),
        ),
        nonRecurringCharges = listOfNotNull(
            charge?.let { AnnualReportedValue("2025-12-31", it) },
        ),
    )
}
