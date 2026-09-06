package com.discountscreener.core.engine

import com.discountscreener.core.model.FundamentalSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class SecResidualFactsTest {
    @Test
    fun jpm_like_annual_facts_yield_beginning_book_roe() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2023-12-31","val":100000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":110000000000,"filed":"2025-02-20"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":15000000000,"filed":"2025-02-20"}
              ]}},
              "PaymentsOfDividendsCommonStock":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":5000000000,"filed":"2025-02-20"}
              ]}},
              "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":3000000000,"filed":"2025-02-20"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals(1_500, drivers?.returnOnEquityBps)
    }

    @Test
    fun same_year_cash_stays_on_the_lender() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2023-12-31","val":100000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":110000000000,"filed":"2025-02-20"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":15000000000,"filed":"2025-02-20"}
              ]}},
              "PaymentsOfDividendsCommonStock":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":5000000000,"filed":"2025-02-20"}
              ]}},
              "CashAndCashEquivalentsAtCarryingValue":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":6200000000,"filed":"2025-02-20"}
              ]}},
              "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":3000000000,"filed":"2025-02-20"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals(6_200_000_000.0, drivers?.cashDollars)
    }

    @Test
    fun roe_uses_median_of_recent_years_not_the_trough_year() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2021-12-31","val":80000000000,"filed":"2022-02-20"},
                {"fp":"FY","form":"10-K","end":"2022-12-31","val":90000000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","end":"2023-12-31","val":100000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":110000000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":120000000000,"filed":"2026-02-20"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2022-01-01","end":"2022-12-31","val":16000000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","start":"2023-01-01","end":"2023-12-31","val":18000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":20000000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":8000000000,"filed":"2026-02-20"}
              ]}},
              "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":900000000,"filed":"2026-02-20"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals(2_000, drivers?.returnOnEquityBps)
    }

    @Test
    fun trough_loss_year_keeps_a_positive_median_roe() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2021-12-31","val":26795000000,"filed":"2022-02-20"},
                {"fp":"FY","form":"10-K","end":"2022-12-31","val":24057000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","end":"2023-12-31","val":25840000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":26410000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":19953000000,"filed":"2026-02-20"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2022-01-01","end":"2022-12-31","val":1202000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","start":"2023-01-01","end":"2023-12-31","val":2702000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":3305000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":-6674000000,"filed":"2026-02-20"}
              ]}},
              "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":493116000,"filed":"2026-02-20"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals(1_123, drivers?.returnOnEquityBps)
    }

    @Test
    fun median_roe_writes_count_in_provenance() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2021-12-31","val":80000000000,"filed":"2022-02-20"},
                {"fp":"FY","form":"10-K","end":"2022-12-31","val":90000000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","end":"2023-12-31","val":100000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":110000000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":120000000000,"filed":"2026-02-20"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2022-01-01","end":"2022-12-31","val":16000000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","start":"2023-01-01","end":"2023-12-31","val":18000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":20000000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":8000000000,"filed":"2026-02-20"}
              ]}},
              "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":900000000,"filed":"2026-02-20"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = requireNotNull(SecResidualFacts.extract(json))
        assertEquals(true, drivers.provenance.contains("roe=median_ni_over_beginning_book:n=4"))
    }

    @Test
    fun stale_dei_shares_do_not_stand_in_for_the_current_year() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2024-09-30","val":37909000000,"filed":"2025-11-14"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2023-10-01","end":"2024-09-30","val":20058000000,"filed":"2025-11-14"}
              ]}}
            },"dei":{"EntityCommonStockSharesOutstanding":{"units":{"shares":[
              {"fp":"FY","form":"10-K","end":"2009-11-13","val":470000000,"filed":"2009-11-20"}
            ]}}}}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals(null, drivers?.shares)
    }

    @Test
    fun later_net_income_year_wins_over_an_older_common_tag() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2023-12-31","val":46223000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":41033000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":41713000000,"filed":"2026-02-20"}
              ]}},
              "NetIncomeLossAvailableToCommonStockholdersBasic":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":3434000000,"filed":"2025-02-20"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":5957000000,"filed":"2026-02-20"}
              ]}},
              "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":268563000,"filed":"2026-02-20"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals("2025-12-31", drivers?.fiscalEnd)
    }

    @Test
    fun dividends_common_stock_cash_is_an_approved_payout_tag() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":20820000000,"filed":"2025-02-27"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":24206000000,"filed":"2026-02-26"}
              ]}},
              "NetIncomeLossAvailableToCommonStockholdersBasic":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":4359000000,"filed":"2026-02-26"}
              ]}},
              "DividendsCommonStockCash":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":1900000000,"filed":"2026-02-26"}
              ]}},
              "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":375900000,"filed":"2026-02-26"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals(5_641, drivers?.retentionBps)
    }

    @Test
    fun retention_uses_median_of_recent_years_not_the_trough_payout() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2021-12-31","val":80000000000,"filed":"2022-02-20"},
                {"fp":"FY","form":"10-K","end":"2022-12-31","val":90000000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","end":"2023-12-31","val":100000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":110000000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":120000000000,"filed":"2026-02-20"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2022-01-01","end":"2022-12-31","val":10000000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","start":"2023-01-01","end":"2023-12-31","val":10000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":10000000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":10000000000,"filed":"2026-02-20"}
              ]}},
              "PaymentsOfDividendsCommonStock":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2022-01-01","end":"2022-12-31","val":3000000000,"filed":"2023-02-20"},
                {"fp":"FY","form":"10-K","start":"2023-01-01","end":"2023-12-31","val":3000000000,"filed":"2024-02-20"},
                {"fp":"FY","form":"10-K","start":"2024-01-01","end":"2024-12-31","val":5000000000,"filed":"2025-02-20"},
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":8000000000,"filed":"2026-02-20"}
              ]}},
              "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":900000000,"filed":"2026-02-20"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals(6_000, drivers?.retentionBps)
    }

    @Test
    fun stale_parent_book_uses_nci_less_minority() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquity":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2014-12-31","val":32454000000,"filed":"2015-02-10"}
              ]}},
              "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":98268000000,"filed":"2026-03-02"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":100090000000,"filed":"2026-03-02"}
              ]}},
              "MinorityInterest":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":5610000000,"filed":"2026-03-02"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":5980000000,"filed":"2026-03-02"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":12056000000,"filed":"2026-03-02"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = SecResidualFacts.extract(json)
        assertEquals(94_110_000_000.0, drivers?.bookEquityDollars)
    }

    @Test
    fun nci_less_minority_writes_book_provenance() {
        var json = """
            {"facts":{"us-gaap":{
              "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":98268000000,"filed":"2026-03-02"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":100090000000,"filed":"2026-03-02"}
              ]}},
              "MinorityInterest":{"units":{"USD":[
                {"fp":"FY","form":"10-K","end":"2024-12-31","val":5610000000,"filed":"2026-03-02"},
                {"fp":"FY","form":"10-K","end":"2025-12-31","val":5980000000,"filed":"2026-03-02"}
              ]}},
              "NetIncomeLoss":{"units":{"USD":[
                {"fp":"FY","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":12056000000,"filed":"2026-03-02"}
              ]}}
            }}}
        """.trimIndent()
        var drivers = requireNotNull(SecResidualFacts.extract(json))
        assertEquals(true, drivers.provenance.contains("book=nci_less_minority:2025-12-31"))
    }

    @Test
    fun overlay_prefers_sec_book_when_present() {
        var sec = requireNotNull(SecResidualFacts.extract(fixture("sec-companyfacts/JPM.json")))
        var yahoo = FundamentalSnapshot(
            symbol = "JPM",
            sectorName = "Financial Services",
            industryName = "Banks - Diversified",
            bookValuePerShareCents = 1L,
        )
        var overlaid = SecResidualFacts.overlay(yahoo, sec)
        assertEquals(sec.bookValuePerShareCents, overlaid.fundamentals.bookValuePerShareCents)
    }

    @Test
    fun overlay_uses_yahoo_retention_when_sec_dividends_are_missing() {
        var sec = requireNotNull(SecResidualFacts.extract(fixture("sec-companyfacts/ACGL.json")))
        var yahoo = FundamentalSnapshot(
            symbol = "ACGL",
            sectorName = "Financial Services",
            industryName = "Insurance - Diversified",
            retentionBps = 10_000,
        )
        var overlaid = SecResidualFacts.overlay(yahoo, sec)
        assertEquals(10_000, overlaid.fundamentals.retentionBps)
    }

    private fun fixture(path: String): String {
        var stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path))
        return stream.bufferedReader().use { it.readText() }
    }
}
