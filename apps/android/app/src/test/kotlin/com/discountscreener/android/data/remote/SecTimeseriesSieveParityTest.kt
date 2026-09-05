package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.SecCompanyFactsSieve
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sieve is allowed to drop bytes. It is not allowed to change an answer.
 *
 * The document below carries everything the sieve now cuts on the stream: a quarter, a 10-Q, an
 * 8-K, a dimensional row, and the `accn`, `fy` and `frame` fields. The reader refused all of them
 * for itself before the sieve did, so both reads must reach the same timeseries.
 *
 * It also carries a restatement: 2023 operating cash flow is filed twice, and the later filing
 * wins. That case is what keeps `filed` in the sieve's field set. Drop `filed` and the two reads
 * disagree, because the reader then keeps whichever row it saw first.
 */
class SecTimeseriesSieveParityTest {
    @Test
    fun the_timeseries_reader_reaches_the_same_series_through_the_sieve() {
        assertEquals(
            buildSecEdgarTimeseries(Json.parseToJsonElement(RAW).jsonObject),
            buildSecEdgarTimeseries(Json.parseToJsonElement(SecCompanyFactsSieve.sieve(RAW.reader())).jsonObject),
        )
    }

    private companion object {
        val RAW = """
            {
              "cik": 320193,
              "entityName": "NOISY FILER INC",
              "facts": {
                "us-gaap": {
                  "NetCashProvidedByUsedInOperatingActivities": {
                    "label": "Operating cash flow",
                    "description": "A long label nobody reads.",
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "start": "2022-01-01", "end": "2022-12-31", "val": 100.0,
                          "accn": "0000320193-23-000006", "fy": 2023, "frame": "CY2022", "filed": "2023-02-01" },
                        { "fp": "FY", "form": "10-K", "start": "2023-01-01", "end": "2023-12-31", "val": 120.0,
                          "accn": "0000320193-24-000007", "fy": 2024, "frame": "CY2023", "filed": "2024-02-01" },
                        { "fp": "FY", "form": "10-K", "start": "2023-01-01", "end": "2023-12-31", "val": 111.0,
                          "accn": "0000320193-25-000008", "fy": 2025, "frame": "CY2023", "filed": "2025-02-01" },
                        { "fp": "Q3", "form": "10-Q", "start": "2023-07-01", "end": "2023-09-30", "val": 30.0,
                          "filed": "2023-10-25" },
                        { "fp": "FY", "form": "8-K", "start": "2023-01-01", "end": "2023-12-31", "val": 999.0,
                          "filed": "2024-01-05" },
                        { "fp": "FY", "form": "10-K", "start": "2023-01-01", "end": "2023-12-31", "val": 40.0,
                          "segment": { "dim": "Americas" }, "filed": "2024-02-01" }
                      ]
                    }
                  },
                  "PaymentsToAcquirePropertyPlantAndEquipment": {
                    "label": "Capital expenditure",
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "start": "2022-01-01", "end": "2022-12-31", "val": 10.0,
                          "accn": "0000320193-23-000006", "fy": 2023, "frame": "CY2022", "filed": "2023-02-01" },
                        { "fp": "FY", "form": "10-K", "start": "2023-01-01", "end": "2023-12-31", "val": 20.0,
                          "accn": "0000320193-24-000007", "fy": 2024, "frame": "CY2023", "filed": "2024-02-01" },
                        { "fp": "Q1", "form": "10-Q", "start": "2023-01-01", "end": "2023-03-31", "val": 5.0,
                          "filed": "2023-04-25" }
                      ]
                    }
                  },
                  "RevenueFromContractWithCustomerExcludingAssessedTax": {
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "start": "2023-01-01", "end": "2023-12-31", "val": 500.0,
                          "frame": "CY2023", "filed": "2024-02-01" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
