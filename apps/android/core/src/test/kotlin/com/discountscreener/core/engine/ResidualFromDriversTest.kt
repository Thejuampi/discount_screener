package com.discountscreener.core.engine

import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResidualFromDriversTest {
    @Test
    fun financial_services_with_filled_drivers_persists_residual_income() {
        var yahoo = FundamentalSnapshot(
            symbol = "JPM",
            sectorName = "Financial Services",
            industryName = "Banks - Diversified",
        )
        var outcome = ResidualFromDrivers.compute(
            yahoo = yahoo,
            secFactsJson = fixture("sec-companyfacts/JPM.json"),
            secFetchAttempted = true,
            marketPriceCents = 30_000L,
            marketParams = MarketParams(),
            instrumentId = "JPM",
            shareBasis = ValuationJudgmentAssembler.SHARE_BASIS,
        )
        assertEquals(ValuationModel.ResidualIncomeEquity, outcome.analysis.model)
    }

    @Test
    fun trough_year_does_not_pin_sec_roe() {
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
        var yahoo = FundamentalSnapshot(
            symbol = "UNH",
            sectorName = "Healthcare",
            industryName = "Healthcare Plans",
            returnOnEquityBps = 727,
        )
        var outcome = ResidualFromDrivers.compute(
            yahoo = yahoo,
            secFactsJson = json,
            secFetchAttempted = true,
            marketPriceCents = 40_200L,
            marketParams = MarketParams(),
            instrumentId = "UNH",
            shareBasis = ValuationJudgmentAssembler.SHARE_BASIS,
        )
        assertEquals(2_000, outcome.fundamentals.returnOnEquityBps)
    }

    @Test
    fun empty_sec_and_empty_yahoo_names_the_sources_tried() {
        var yahoo = FundamentalSnapshot(
            symbol = "JPM",
            sectorName = "Financial Services",
            industryName = "Banks - Diversified",
        )
        var outcome = ResidualFromDrivers.compute(
            yahoo = yahoo,
            secFactsJson = null,
            secFetchAttempted = true,
            marketPriceCents = 30_000L,
            marketParams = MarketParams(),
            instrumentId = "JPM",
            shareBasis = ValuationJudgmentAssembler.SHARE_BASIS,
        )
        var reason = outcome.analysis.valuationUnavailableReason.orEmpty()
        assertTrue(
            outcome.analysis.model == ValuationModel.None &&
                reason.contains(ResidualFromDrivers.SOURCE_SEC) &&
                reason.contains(ResidualFromDrivers.SOURCE_YAHOO),
        )
    }

    private fun fixture(path: String): String {
        var stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path))
        return stream.bufferedReader().use { it.readText() }
    }
}
