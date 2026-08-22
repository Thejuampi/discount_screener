package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.SecCompanyFactsSieve
import com.discountscreener.core.model.AnnualReportedValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SecEdgarTimeseriesProviderTest {
    @Test
    fun build_sec_timeseries_uses_only_capex_from_the_same_annual_period() {
        var facts = Json.parseToJsonElement(
            """
            {
              "facts": {
                "us-gaap": {
                  "NetCashProvidedByUsedInOperatingActivities": {
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "start": "2021-01-01", "end": "2021-12-31", "val": 100.0 },
                        { "fp": "FY", "form": "10-K", "start": "2022-01-01", "end": "2022-12-31", "val": 110.0 },
                        { "fp": "FY", "form": "10-K", "start": "2023-01-01", "end": "2023-12-31", "val": 120.0 }
                      ]
                    }
                  },
                  "PaymentsToAcquirePropertyPlantAndEquipment": {
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "start": "2021-01-01", "end": "2021-12-31", "val": 10.0 },
                        { "fp": "FY", "form": "10-K", "start": "2022-01-01", "end": "2022-12-31", "val": 20.0 }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        var timeseries = buildSecEdgarTimeseries(facts)

        assertEquals(
            listOf(
                AnnualReportedValue("2021-12-31", 90.0),
                AnnualReportedValue("2022-12-31", 90.0),
            ),
            timeseries?.freeCashFlow,
        )
    }

    @Test
    fun sieved_facts_still_build_the_same_free_cash_flow() {
        var raw = """
            {
              "cik": 1,
              "facts": {
                "dei": { "EntityName": { "units": { "USD": [] } } },
                "us-gaap": {
                  "NetCashProvidedByUsedInOperatingActivities": {
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "start": "2021-01-01", "end": "2021-12-31", "val": 100.0 },
                        { "fp": "FY", "form": "10-K", "start": "2022-01-01", "end": "2022-12-31", "val": 110.0 }
                      ]
                    }
                  },
                  "PaymentsToAcquirePropertyPlantAndEquipment": {
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "start": "2021-01-01", "end": "2021-12-31", "val": 10.0 },
                        { "fp": "FY", "form": "10-K", "start": "2022-01-01", "end": "2022-12-31", "val": 20.0 }
                      ]
                    }
                  },
                  "HugeUnusedConcept": { "label": "drop me", "units": { "USD": [{ "val": 1 }] } }
                }
              }
            }
        """.trimIndent()
        var slim = SecCompanyFactsSieve.sieve(raw.reader())
        var timeseries = buildSecEdgarTimeseries(Json.parseToJsonElement(slim).jsonObject)
        assertEquals(
            listOf(
                AnnualReportedValue("2021-12-31", 90.0),
                AnnualReportedValue("2022-12-31", 90.0),
            ),
            timeseries?.freeCashFlow,
        )
    }

    @Test
    fun slim_cache_name_includes_residual_sieve_version() {
        var name = companyFactsSlimFileName("0000019617")
        assertEquals("CIK0000019617.sieve-$COMPANY_FACTS_SIEVE_VERSION.json", name)
    }

    @Test
    fun operating_income_is_read_for_the_accepted_years() {
        var timeseries = buildSecEdgarTimeseries(
            factsWith(annual("OperatingIncomeLoss", 2021 to 40.0, 2022 to 50.0)),
        )

        assertEquals(listOf(40.0, 50.0), timeseries?.operatingIncome?.map { it.value })
    }

    /** The aggregate line holds the parts. Adding both would charge the same write-down twice. */
    @Test
    fun an_aggregate_impairment_is_not_added_to_the_parts_it_holds() {
        var timeseries = buildSecEdgarTimeseries(
            factsWith(
                annual("AssetImpairmentCharges", 2022 to 30.0),
                annual("GoodwillImpairmentLoss", 2022 to 10.0),
                annual("TangibleAssetImpairmentCharges", 2022 to 5.0),
            ),
        )

        assertEquals(listOf(30.0), timeseries?.nonRecurringCharges?.map { it.value })
    }

    /** A filer that reports no aggregate still has a total: its parts are disjoint by name. */
    @Test
    fun parts_reported_without_an_aggregate_are_summed() {
        var timeseries = buildSecEdgarTimeseries(
            factsWith(
                annual("GoodwillImpairmentLoss", 2022 to 10.0),
                annual("ImpairmentOfIntangibleAssetsExcludingGoodwill", 2022 to 5.0),
            ),
        )

        assertEquals(listOf(15.0), timeseries?.nonRecurringCharges?.map { it.value })
    }

    /** An aggregate smaller than the parts it is supposed to hold is not an aggregate. */
    @Test
    fun parts_larger_than_the_reported_aggregate_win() {
        var timeseries = buildSecEdgarTimeseries(
            factsWith(
                annual("AssetImpairmentCharges", 2022 to 8.0),
                annual("GoodwillImpairmentLoss", 2022 to 10.0),
                annual("TangibleAssetImpairmentCharges", 2022 to 5.0),
            ),
        )

        assertEquals(listOf(15.0), timeseries?.nonRecurringCharges?.map { it.value })
    }

    @Test
    fun restructuring_is_added_to_impairment() {
        var timeseries = buildSecEdgarTimeseries(
            factsWith(
                annual("AssetImpairmentCharges", 2022 to 30.0),
                annual("RestructuringCharges", 2022 to 7.0),
            ),
        )

        assertEquals(listOf(37.0), timeseries?.nonRecurringCharges?.map { it.value })
    }

    /** Filers book a write-down with either sign. What is read is its size. */
    @Test
    fun a_charge_filed_as_a_negative_number_is_read_by_its_size() {
        var timeseries = buildSecEdgarTimeseries(
            factsWith(annual("AssetImpairmentCharges", 2022 to -30.0)),
        )

        assertEquals(listOf(30.0), timeseries?.nonRecurringCharges?.map { it.value })
    }

    /** 2023 has no cash-flow pair, so nothing from that year reaches a row. */
    @Test
    fun a_charge_outside_the_accepted_cash_flow_years_is_dropped() {
        var timeseries = buildSecEdgarTimeseries(
            factsWith(annual("AssetImpairmentCharges", 2022 to 30.0, 2023 to 90.0)),
        )

        assertEquals(listOf(30.0), timeseries?.nonRecurringCharges?.map { it.value })
    }

    /**
     * Every case above needs the same two years of cash flow, because a driver row is dropped
     * unless the year has both operating cash flow and CapEx.
     */
    private fun factsWith(vararg concepts: String) = Json.parseToJsonElement(
        """
        {
          "facts": {
            "us-gaap": {
              ${annual("NetCashProvidedByUsedInOperatingActivities", 2021 to 100.0, 2022 to 110.0)},
              ${annual("PaymentsToAcquirePropertyPlantAndEquipment", 2021 to 10.0, 2022 to 20.0)},
              ${concepts.joinToString(", ")}
            }
          }
        }
        """.trimIndent(),
    ).jsonObject

    private fun annual(concept: String, vararg years: Pair<Int, Double>): String {
        var facts = years.joinToString(", ") { (year, value) ->
            """{ "fp": "FY", "form": "10-K", "start": "$year-01-01", "end": "$year-12-31", "val": $value }"""
        }
        return """ "$concept": { "units": { "USD": [ $facts ] } } """
    }
}
