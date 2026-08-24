package com.discountscreener.core.engine

import com.discountscreener.core.model.ScoreFactorComparison
import com.discountscreener.core.model.ScoreFactorValueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V4 scores leverage from net debt against EBITDA, in dollars.
 *
 * The defect being fixed is not a mis-tuned band. It is that the leverage component of every model
 * scored the same constant for the whole universe. Yahoo reports `financialData.debtToEquity` as a
 * percent — AMZN comes back as 40.46 — and the ingestion multiplies it by a hundred again, so the
 * stored number is the ratio times ten thousand. V2 and V3 ramp that against 30 to 200, and every
 * real company lands past the far edge and pins at the floor. Sixteen of V4's hundred and ten
 * fundamental points paid for a number that never moved.
 *
 * The existing tests missed it because their fixtures used the unit the code intended — 10, 40, 220
 * — and the ingestion cannot produce those. Every test below states its inputs in the magnitudes
 * that arrive in production.
 *
 * Weight 16 over V4's budget of 110 puts the whole component inside ±15 points, so
 * `16 / 110 × 100 = 14.5455` is the multiplier behind every expected number here.
 */
class AggressiveV4LeverageTest {

    /**
     * Three balance sheets, three scores. This is the claim the old component could not make.
     *
     * All three earn ten billion of EBITDA and differ only in what they owe. The absolute band runs
     * from net cash to three turns of net debt.
     *
     *  net cash   debt 2B, cash 12B  → −10B / 10B = −1.00x → −100, under the low edge → +1 → +15
     *  moderate   debt 20B, cash 5B  →  15B / 10B = +1.50x →  150, the middle of the band → 0
     *  heavy      debt 40B, cash 5B  →  35B / 10B = +3.50x →  350, over the high edge → −1 → −15
     */
    @Test
    fun three_balance_sheets_score_three_different_ways() {
        var netCash = leverageScore(totalDebtDollars = 2_000_000_000, totalCashDollars = 12_000_000_000)
        var moderate = leverageScore(totalDebtDollars = 20_000_000_000, totalCashDollars = 5_000_000_000)
        var heavy = leverageScore(totalDebtDollars = 40_000_000_000, totalCashDollars = 5_000_000_000)

        assertEquals(listOf(15, 0, -15), listOf(netCash, moderate, heavy))
    }

    /**
     * A real company's real numbers, at the magnitude the ingestion delivers, and it does not sit
     * at the floor. **This is the test whose absence let the defect ship.**
     *
     * Amazon: 161B of debt, 88B of cash, 111B of EBITDA. Net debt is 73B, which is 0.66 turns — an
     * ordinary balance sheet that should score near the good end and now does.
     *
     *  73B / 111B = 0.6577x → 66 hundredths
     *  2 × 66 / 300 − 1 = −0.56 → negated +0.56 × 14.5455 → 8.15 → 8
     *
     * The same row through the old component reads `debtToEquityHundredths = 4046` against a band
     * that ends at 200, saturates, and returns the floor.
     */
    @Test
    fun a_real_balance_sheet_at_the_ingested_magnitude_does_not_pin_at_the_floor() {
        var amazon = leverageScore(
            totalDebtDollars = 161_000_000_000,
            totalCashDollars = 88_000_000_000,
            ebitdaDollars = 111_000_000_000,
        )

        assertEquals(8, amazon)
    }

    /**
     * The sector band is **additive**, and this is the test that would catch it being made
     * multiplicative like the three price multiples.
     *
     * A software sector whose members hold a turn of net cash has a centre of −100, and that is an
     * ordinary thing to be. The additive band is [−250, +50], so a company at exactly zero net debt
     * is the weaker half of that sector even though the absolute band calls it perfect.
     *
     *  absolute  0 at or under the low edge of [0, 300] → +1 × 14.5455 → +15
     *  sector    2 × 250 / 300 − 1 = +0.6667 → negated −0.6667 × 14.5455 → −9.70 → −10
     *
     * A `× 0.7 / × 1.5` band on the same centre would be [−70, −150] — upper below lower — which is
     * not a band at all, and `smoothRamp` refuses it rather than scoring it.
     */
    @Test
    fun the_leverage_band_is_additive_so_a_sector_holding_net_cash_still_orders() {
        var absolute = leverageScore()
        var sectorRelative = leverageScore(sectorNetDebtToEbitdaHundredths = -100)

        assertEquals(listOf(15, -10), listOf(absolute, sectorRelative))
    }

    /**
     * A row scored against its sector says so, and a row scored against the absolute band says that
     * too. Two rows in one list scored by two rules is fine; two rows that stay quiet about which
     * rule scored them is the defect this marker exists to prevent.
     */
    @Test
    fun the_label_says_which_band_scored_the_row() {
        var absolute = leverageFactorKey()
        var sectorRelative = leverageFactorKey(sectorNetDebtToEbitdaHundredths = 150)

        assertEquals(listOf("ND/EBITDA", "ND/EBITDA§"), listOf(absolute, sectorRelative))
    }

    /**
     * Without EBITDA the component falls back to debt/equity, read against the band that field is
     * actually in. The fallback keeps its own label so a weaker input stays visible in the list.
     *
     *  AMZN 4046 in [3000, 20000] → 2 × 1046 / 17000 − 1 = −0.8769 → negated +0.8769 → 12.76 → 13
     *  levered 15000              → 2 × 12000 / 17000 − 1 = +0.4118 → negated −0.4118 → −5.99 → −6
     *
     * Both readings sit inside the band and both move. Through V2 and V3's [30, 200] the same two
     * numbers return the identical floor, which is the defect in one line.
     */
    @Test
    fun without_ebitda_the_fallback_reads_debt_to_equity_in_the_unit_the_ingestion_produces() {
        var amazonLike = leverageScore(ebitdaDollars = null, debtToEquityHundredths = 4_046)
        var leveredLike = leverageScore(ebitdaDollars = null, debtToEquityHundredths = 15_000)

        assertEquals(listOf(13, -6), listOf(amazonLike, leveredLike))
    }

    /**
     * When both inputs are present the dollar one wins. Debt/equity is the second choice on its
     * merits — buybacks shrink book equity, and lease liabilities inflate the numerator — so a row
     * that could have been scored on EBITDA turns and was not would be a silent downgrade.
     */
    @Test
    fun net_debt_over_ebitda_outranks_debt_to_equity_when_both_are_present() {
        assertEquals("ND/EBITDA", leverageFactorKey(debtToEquityHundredths = 4_046))
    }

    /**
     * A company with unknown cash is not a company with no cash. Treating a missing side of net
     * debt as zero would invent leverage the balance sheet may not carry, so the row drops to the
     * fallback instead.
     */
    @Test
    fun a_missing_side_of_net_debt_falls_back_rather_than_assuming_zero() {
        assertEquals("D/E", leverageFactorKey(totalCashDollars = null, debtToEquityHundredths = 4_046))
    }

    /**
     * The sector centre survives net cash, and this is the test that catches it being routed
     * through the price-multiple helper.
     *
     * That helper drops every value at or below zero, which is right for a P/E and wrong here. A
     * sector whose members hold more cash than debt has a negative centre, and dropping those
     * members would leave the sector with nothing and send all five rows to the absolute band.
     */
    @Test
    fun sector_leverage_keeps_the_symbol_turns_against_the_sector_centre() {
        var factor = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    totalDebtDollars = 20_000_000_000,
                    totalCashDollars = 5_000_000_000,
                    ebitdaDollars = 10_000_000_000,
                ),
            ),
            sectorBenchmarks(100),
        ).factors.single { it.key == "ND/EBITDA§" }

        assertEquals(
            listOf(ScoreFactorComparison(150, ScoreFactorValueKind.Multiple, reference = 100)),
            factor.comparisons,
        )
    }

    @Test
    fun a_sector_that_holds_net_cash_still_produces_a_centre() {
        var members = listOf(-2_000_000_000L, -1_500_000_000L, -1_000_000_000L, -500_000_000L, 0L)
            .map { netDebt ->
                baseDetail(
                    fundamentals = fundamentals(
                        sectorName = "Technology",
                        totalDebtDollars = 1_000_000_000,
                        totalCashDollars = 1_000_000_000 - netDebt,
                        ebitdaDollars = 1_000_000_000,
                    ),
                )
            }

        var centre = computeSectorBenchmarks(members)["Technology"]?.netDebtToEbitdaHundredths

        assertTrue(centre != null && centre < 0, "expected a negative sector centre, got $centre")
    }

    /**
     * No V4 fundamental factor may return one number for a whole cohort. A component that cannot
     * vary cannot rank, and it spends its share of the budget saying nothing.
     *
     * This is the guard the original defect needed and did not have. It fails against the old
     * leverage component: the six debt/equity readings below are the magnitudes the ingestion
     * produces, and every one of them saturates the [30, 200] band to the same floor.
     *
     * **Scope, stated rather than implied:** the cohort carries no fundamental timeseries, so the
     * trend and share-count factors are outside it. Those need their own cohort with real annual
     * series behind it.
     */
    @Test
    fun no_v4_fundamental_factor_is_constant_across_a_cohort() {
        var constantKeys = COHORT
            .flatMap { OpportunityEngine.aggressiveV4FundamentalsScore(it, null).factors }
            .groupBy { it.key }
            .filterKeys { it != FUND_COVERAGE_GAP_LABEL }
            .filterValues { readings ->
                readings.size == COHORT.size && readings.distinctBy { it.bucketPoints }.size == 1
            }
            .keys

        assertEquals(emptySet(), constantKeys)
    }

    /**
     * Six companies that differ in every fundamental V4 reads. The leverage figures are stated as
     * balance sheets rather than ratios, and the debt/equity figures in the magnitudes Yahoo
     * returns, so the cohort exercises the path production takes.
     */
    private val COHORT = listOf(
        cohortMember(2_000_000_000, 12_000_000_000, 10_000_000_000, 4_046, 3_055, 1_800, 900, 250, 8_000_000_000, 11_000_000_000),
        cohortMember(20_000_000_000, 5_000_000_000, 10_000_000_000, 12_500, 1_200, 2_400, 1_400, 420, 3_000_000_000, 5_000_000_000),
        cohortMember(40_000_000_000, 5_000_000_000, 10_000_000_000, 31_000, 700, 3_100, 1_900, 610, 1_500_000_000, 3_500_000_000),
        cohortMember(161_000_000_000, 88_000_000_000, 111_000_000_000, 8_200, 2_100, 2_050, 1_150, 330, 30_000_000_000, 60_000_000_000),
        cohortMember(6_000_000_000, 1_000_000_000, 4_000_000_000, 19_400, -400, 1_300, 700, 140, 900_000_000, 2_000_000_000),
        cohortMember(500_000_000, 30_000_000_000, 22_000_000_000, 1_100, 4_800, 2_700, 1_600, 800, 18_000_000_000, 24_000_000_000),
    )

    private fun cohortMember(
        totalDebtDollars: Long,
        totalCashDollars: Long,
        ebitdaDollars: Long,
        debtToEquityHundredths: Int,
        returnOnEquityBps: Int,
        forwardPeHundredths: Int,
        enterpriseToEbitdaHundredths: Int,
        priceToBookHundredths: Int,
        freeCashFlowDollars: Long,
        operatingCashFlowDollars: Long,
    ) = baseDetail(
        fundamentals = fundamentals(
            sectorName = "Technology",
            marketCapDollars = 200_000_000_000,
            totalDebtDollars = totalDebtDollars,
            totalCashDollars = totalCashDollars,
            ebitdaDollars = ebitdaDollars,
            debtToEquityHundredths = debtToEquityHundredths,
            returnOnEquityBps = returnOnEquityBps,
            forwardPeHundredths = forwardPeHundredths,
            enterpriseToEbitdaHundredths = enterpriseToEbitdaHundredths,
            priceToBookHundredths = priceToBookHundredths,
            freeCashFlowDollars = freeCashFlowDollars,
            operatingCashFlowDollars = operatingCashFlowDollars,
        ),
    )

    /**
     * Score leverage alone, so the component under test is the only term the bucket holds. Every
     * other V4 fundamental input stays null, which keeps it out of the accumulator entirely.
     */
    private fun leverageScore(
        totalDebtDollars: Long? = 5_000_000_000,
        totalCashDollars: Long? = 5_000_000_000,
        ebitdaDollars: Long? = 10_000_000_000,
        debtToEquityHundredths: Int? = null,
        sectorNetDebtToEbitdaHundredths: Int? = null,
    ) = OpportunityEngine.aggressiveV4FundamentalsScore(
        baseDetail(
            fundamentals = fundamentals(
                totalDebtDollars = totalDebtDollars,
                totalCashDollars = totalCashDollars,
                ebitdaDollars = ebitdaDollars,
                debtToEquityHundredths = debtToEquityHundredths,
            ),
        ),
        sectorBenchmarks(sectorNetDebtToEbitdaHundredths),
    ).score

    /** The same call, read for the label instead of the number. */
    private fun leverageFactorKey(
        totalCashDollars: Long? = 5_000_000_000,
        debtToEquityHundredths: Int? = null,
        sectorNetDebtToEbitdaHundredths: Int? = null,
    ) = OpportunityEngine.aggressiveV4FundamentalsScore(
        baseDetail(
            fundamentals = fundamentals(
                totalDebtDollars = 5_000_000_000,
                totalCashDollars = totalCashDollars,
                ebitdaDollars = 10_000_000_000,
                debtToEquityHundredths = debtToEquityHundredths,
            ),
        ),
        sectorBenchmarks(sectorNetDebtToEbitdaHundredths),
    ).factors.single { it.key != FUND_COVERAGE_GAP_LABEL }.key

    /** A benchmark set that carries a leverage centre and nothing else, so no other factor fires. */
    private fun sectorBenchmarks(netDebtToEbitdaHundredths: Int?) = netDebtToEbitdaHundredths?.let {
        SectorBenchmarks(
            forwardPeHundredths = null,
            enterpriseToEbitdaHundredths = null,
            priceToBookHundredths = null,
            returnOnEquityBps = null,
            netDebtToEbitdaHundredths = it,
        )
    }
}
