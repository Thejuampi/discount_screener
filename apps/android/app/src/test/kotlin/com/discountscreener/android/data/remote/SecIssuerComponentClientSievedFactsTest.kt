package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.SecCompanyFactsSieve
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The path that carries companyfacts into the finance subsidiary read.
 *
 * The client sieves the response while it arrives, so the 4 MB body never becomes a string. That
 * makes the http client the only seam a test can hold, and [cannedHttpClient] is that seam.
 */
class SecIssuerComponentClientSievedFactsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun the_response_arrives_sieved() {
        assertEquals(SecCompanyFactsSieve.sieve(RAW.reader()), clientOn(temp.newFolder()).loadSievedFacts(CIK))
    }

    @Test
    fun the_cache_holds_what_the_stream_returned() {
        var dir = temp.newFolder()
        var slim = clientOn(dir).loadSievedFacts(CIK)
        assertEquals(slim, File(dir, companyFactsSlimFileName(CIK)).readText())
    }

    @Test
    fun a_second_read_costs_no_request() {
        var dir = temp.newFolder()
        clientOn(dir).loadSievedFacts(CIK)
        assertEquals(
            SecCompanyFactsSieve.sieve(RAW.reader()),
            SecIssuerComponentClient(cacheDir = dir, client = offlineHttpClient()).loadSievedFacts(CIK),
        )
    }

    private fun clientOn(dir: File) =
        SecIssuerComponentClient(cacheDir = dir, client = cannedHttpClient("companyfacts", RAW))

    private companion object {
        const val CIK = "0000320193"
        val RAW = """
            {
              "cik": 320193,
              "entityName": "SUBSIDIARY FINANCE INC",
              "facts": {
                "us-gaap": {
                  "InterestExpense": {
                    "label": "Interest expense",
                    "description": "A label the phone never reads.",
                    "units": {
                      "USD": [
                        { "fp": "FY", "form": "10-K", "start": "2023-01-01", "end": "2023-12-31", "val": 40.0,
                          "accn": "0000320193-24-000007", "fy": 2024, "frame": "CY2023", "filed": "2024-02-01" },
                        { "fp": "Q3", "form": "10-Q", "start": "2023-07-01", "end": "2023-09-30", "val": 9.0,
                          "filed": "2023-10-25" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
