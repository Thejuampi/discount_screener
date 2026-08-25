package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Financial services keep their equity terms and drop the EBITDA leverage vote.
 *
 * A bank's balance-sheet debt is dominated by customer deposits and an insurer's by policyholder
 * float. Neither is borrowed money in the lending sense, EBITDA is not a capacity measure for
 * either, so net debt over EBITDA reads a deposit franchise as a levered industrial. Citigroup's
 * own filing carries hundreds of billions of deposits; scored as an operating company it pins the
 * bottom of the band on them.
 *
 * The leverage gate reads the canonical class and only [BusinessClass.FinancialServices] loses the
 * EBITDA vote, so an unclassified row keeps it.
 *
 * The cash gate is stricter and fails closed. An unclassified row cannot answer an industrial FCF
 * yield either, so `industrialFcfRefusalLabel` refuses that vote and the cash conversion vote for
 * it, and `applyClassExemptions` takes both weights — 32 points — off its budget. Sizing the blast
 * radius of a sector-key change means counting that row as losing two terms.
 */
class AggressiveV4FinancialClassTest {

    /** Deposits are not leverage: a bank with 10 turns of "net debt" takes no EBITDA-leverage vote. */
    @Test
    fun a_bank_takes_no_ebitda_leverage_vote() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(
                sectorKey = "financial-services",
                industryKey = "banks-diversified",
                totalDebtDollars = 500_000_000_000,
                totalCashDollars = 200_000_000_000,
                ebitdaDollars = 30_000_000_000,
            )),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertTrue(
            keys.none { it.startsWith("ND/EBITDA") || it == "D/E" || it == "Bal" },
            "a bank must not carry a leverage factor: $keys",
        )
    }

    /** Float behaves like deposits: the insurer skips the vote too. */
    @Test
    fun an_insurer_takes_no_ebitda_leverage_vote() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(
                sectorKey = "financial-services",
                industryKey = "insurance-property-casualty",
                totalDebtDollars = 50_000_000_000,
                totalCashDollars = 40_000_000_000,
                ebitdaDollars = 12_000_000_000,
            )),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertTrue(
            keys.none { it.startsWith("ND/EBITDA") || it == "D/E" || it == "Bal" },
            "an insurer must not carry a leverage factor: $keys",
        )
    }

    /**
     * The gate runs on the canonical classifier, and the classifier classes healthcare plans as
     * financial services — UNH and CI carry `sectorKey: healthcare`, which a sector-key table
     * would miss. Their debt is real borrowed money but their underwriting-like economics are
     * what the equity terms read; the EBITDA vote stays off for them as for any other insurer.
     */
    @Test
    fun managed_care_reads_as_financial_services_through_the_canonical_classifier() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(
                sectorName = "Healthcare",
                industryName = "Healthcare Plans",
                sectorKey = "healthcare",
                industryKey = "healthcare-plans",
                totalDebtDollars = 30_000_000_000,
                totalCashDollars = 25_000_000_000,
                ebitdaDollars = 15_000_000_000,
            )),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertTrue(
            keys.none { it.startsWith("ND/EBITDA") || it == "D/E" || it == "Bal" },
            "managed care must not carry a leverage factor: $keys",
        )
    }

    /**
     * The gate removes the terms a bank cannot answer and leaves the rest. Return on equity — the
     * ratio that actually ranks banks — still scores, here pinned at the top of its absolute band.
     *
     * A bank has the FCF (22), cash quality (10) and leverage (16) weights taken off its budget, so
     * V4's 110 becomes 62 and the ROE weight of 16 is that much larger a share of what is left:
     * 16 / 62 × 100, pinned at the top of the band, is 26.
     */
    @Test
    fun a_bank_still_scores_its_return_on_equity() {
        var score = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(
                sectorKey = "financial-services",
                returnOnEquityBps = 2_500,
            )),
            sectorBenchmarks = null,
        ).score

        assertEquals(26, score)
    }

    /** An operating company with the same balance sheet keeps the vote — the gate is not a haircut on everyone. */
    @Test
    fun an_operating_company_keeps_the_leverage_vote() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(
                sectorKey = "industrials",
                totalDebtDollars = 5_000_000_000,
                totalCashDollars = 5_000_000_000,
                ebitdaDollars = 10_000_000_000,
            )),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertTrue("ND/EBITDA" in keys)
    }
}
