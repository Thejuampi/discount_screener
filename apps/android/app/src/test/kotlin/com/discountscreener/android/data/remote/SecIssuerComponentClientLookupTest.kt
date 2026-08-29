package com.discountscreener.android.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole lookup, over the six SEC endpoints it crosses.
 *
 * The ticker map, the submissions list, the filing index, the instance download, the subsidiary
 * search and the sieved companyfacts each answer once, from [cannedHttpClient]. A step that asks
 * for a URL no route names fails the test with that URL in the message.
 */
class SecIssuerComponentClientLookupTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun the_parent_facts_reach_the_operating_component() {
        var set = runBlocking { client().lookup("GM", null) }
        assertEquals(10_916_000_000.0, set!!.operating!!.ebit.single().value, 0.0)
    }

    @Test
    fun the_subsidiary_search_reaches_the_finance_component() {
        var set = runBlocking { client().lookup("GM", "General Motors") }
        assertEquals(2_058_000_000.0, set!!.financial!!.netIncome.last().value, 0.0)
    }

    private fun client() = SecIssuerComponentClient(
        cacheDir = temp.newFolder(),
        client = cannedHttpClient(
            listOf(
                "https://www.sec.gov/files/company_tickers.json" to TICKERS,
                "https://data.sec.gov/submissions/CIK0000000001.json" to SUBMISSIONS,
                "$FILING_DIR/index.json" to FILING_INDEX,
                "$FILING_DIR/gm-20251231_htm.xml" to instanceXml(),
                "https://efts.sec.gov/LATEST/search-index?q=" to EFTS_HITS,
                "https://data.sec.gov/api/xbrl/companyfacts/CIK0000804269.json" to COMPANY_FACTS,
            ),
        ),
    )

    private fun instanceXml(): String =
        javaClass.classLoader!!.getResource("sec/mixed-factory-lender.xml")!!.readText()

    private companion object {
        const val FILING_DIR = "https://www.sec.gov/Archives/edgar/data/1/000000000125000001"

        val TICKERS = """{"0":{"cik_str":1,"ticker":"GM","title":"General Motors Company"}}"""

        val SUBMISSIONS = """
            {"filings":{"recent":{
              "form":["8-K","10-K"],
              "accessionNumber":["0000000001-25-000002","0000000001-25-000001"]
            }}}
        """.trimIndent()

        val FILING_INDEX = """
            {"directory":{"item":[
              {"name":"gm-20251231.xsd"},
              {"name":"gm-20251231_htm.xml"}
            ]}}
        """.trimIndent()

        val EFTS_HITS = """
            {"hits":{"hits":[
              {"_source":{"ciks":["0000804269"],
               "display_names":["General Motors Financial Company, Inc.  (CIK 0000804269)"]}}
            ]}}
        """.trimIndent()

        val COMPANY_FACTS = """
            {
              "cik": 804269,
              "facts": {
                "us-gaap": {
                  "StockholdersEquity": {
                    "units": { "USD": [
                      { "fp": "FY", "form": "10-K", "end": "2025-12-31", "val": 15813000000,
                        "filed": "2026-02-01" }
                    ] }
                  },
                  "NetIncomeLoss": {
                    "units": { "USD": [
                      { "fp": "FY", "form": "10-K", "start": "2025-01-01", "end": "2025-12-31",
                        "val": 2058000000, "filed": "2026-02-01" }
                    ] }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
