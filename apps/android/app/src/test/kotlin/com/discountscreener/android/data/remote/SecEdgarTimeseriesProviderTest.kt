package com.discountscreener.android.data.remote

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
                        { "fp": "FY", "form": "10-K", "end": "2021-12-31", "val": 100.0 },
                        { "fp": "FY", "form": "10-K", "end": "2022-12-31", "val": 110.0 },
                        { "fp": "FY", "form": "10-K", "end": "2023-12-31", "val": 120.0 }
                      ]
                    }
                  },
                  "PaymentsToAcquirePropertyPlantAndEquipment": {
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "end": "2021-12-31", "val": 10.0 },
                        { "fp": "FY", "form": "10-K", "end": "2022-12-31", "val": 20.0 }
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
}