package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.carriesMarketDimension
import com.discountscreener.core.model.readsSectorBenchmarks
import com.discountscreener.core.regime.MarketFeatureSet
import com.discountscreener.core.regime.marketFeatureSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AggressiveV5 is V4 minus its two documented defects, selectable beside V4 at runtime.
 *
 * Delta 1 — loss-maker pulse: a quarter EPS YoY rate whose only annual corroboration is stale
 * profit years while the latest filed year is a loss is refused instead of scored. Delta 2 — an
 * unclassified row no longer takes the industrial leverage vote; an unknown class skips the term
 * and says so. Everything else scores byte-for-byte like V4, which the parity test pins.
 */
class AggressiveV5Test {

    // ---------------------------------------------------------------- wiring

    @Test
    fun v5_uses_the_continuous_act_and_avoid_gates() {
        assertEquals(
            Gates(OpportunityEngine.avoidBelowScore(OPPORTUNITY_V4_MODEL), OpportunityEngine.actAtOrAboveScore(OPPORTUNITY_V4_MODEL)),
            Gates(OpportunityEngine.avoidBelowScore(MODEL), OpportunityEngine.actAtOrAboveScore(MODEL)),
        )
    }

    @Test
    fun v5_carries_the_same_regime_wiring_as_v4() {
        assertEquals(
            Wiring(
                OPPORTUNITY_V4_MODEL.carriesMarketDimension(),
                OPPORTUNITY_V4_MODEL.readsSectorBenchmarks(),
                OPPORTUNITY_V4_MODEL.marketFeatureSet(),
            ),
            Wiring(
                MODEL.carriesMarketDimension(),
                MODEL.readsSectorBenchmarks(),
                MODEL.marketFeatureSet(),
            ),
        )
    }

    // ---------------------------------------------------------------- parity

    /** A healthy operating name must score identically under both models: V5 changes defects, not taste. */
    @Test
    fun a_healthy_operating_name_scores_the_same_under_v4_and_v5() {
        var detail = healthyOperating()
        var series = FundamentalTimeseries(
            revenue = annual(100.0, 112.0, 125.0),
            netIncome = annual(9.0, 11.0, 13.0),
            dilutedAverageShares = annualShares(100.0, 99.0),
        )

        var v4 = OpportunityEngine.aggressiveV4FundamentalsScore(detail, null, series)
        var v5 = OpportunityEngine.aggressiveV5FundamentalsScore(detail, null, series)

        assertEquals(v4.score, v5.score)
        assertTrue(v5.factors.any { it.key == "Pulse" })
        assertEquals(v4.factors.map { it.key }, v5.factors.map { it.key })
    }

    @Test
    fun the_composite_treats_v5_like_v4() {
        assertEquals(
            OpportunityEngine.compositeScoreFor(
                OPPORTUNITY_V4_MODEL,
                fundamentals = 40,
                technical = 7,
                forecast = 29,
                regime = 12,
                coverageCount = 4,
                betaMillis = 250_000,
                betaHaircutMult = 1.0,
            ),
            OpportunityEngine.compositeScoreFor(
                MODEL,
                fundamentals = 40,
                technical = 7,
                forecast = 29,
                regime = 12,
                coverageCount = 4,
                betaMillis = 250_000,
                betaHaircutMult = 1.0,
            ),
        )
    }

    // ---------------------------------------------------------------- delta 1: loss-maker pulse

    /**
     * A perpetual loss-maker with stale profit pairs: the only annual net-income transitions are
     * old profit years, so `isForeignTo` cannot object and V4 scores the quarter rate. V5 refuses
     * it because the latest filed year is a loss.
     */
    @Test
    fun v4_scores_a_loss_maker_s_quarter_pulse_and_v5_refuses_it() {
        var detail = detail(earningsGrowthBps = 3_800, trailingEpsCents = 500)
        var series = FundamentalTimeseries(
            revenue = annual(80.0, 95.0, 110.0),
            netIncome = annual(12.0, -8.0, -21.0),
        )

        var v4 = OpportunityEngine.aggressiveV4FundamentalsScore(detail, null, series)
        var v5 = OpportunityEngine.aggressiveV5FundamentalsScore(detail, null, series)

        assertTrue(v4.factors.any { it.key == "Pulse" })
        assertFalse(v5.factors.any { it.key == "Pulse" })
    }

    @Test
    fun the_refused_pulse_names_itself_so_the_journal_can_attribute_it() {
        var evidence = OpportunityEngine.aggressiveV5FundamentalsScore(
            detail(earningsGrowthBps = 3_800, trailingEpsCents = 500),
            null,
            FundamentalTimeseries(
                revenue = annual(80.0, 95.0, 110.0),
                netIncome = annual(12.0, -8.0, -21.0),
            ),
        )

        assertTrue(evidence.signals.any { it.startsWith("Pulse") && it.contains("loss") })
    }

    /** A real turnaround files a positive latest year, and its pulse counts again. */
    @Test
    fun a_turnaround_with_a_profitable_latest_year_keeps_its_pulse_under_v5() {
        var detail = detail(earningsGrowthBps = 3_800, trailingEpsCents = 500)
        var series = FundamentalTimeseries(
            revenue = annual(80.0, 95.0, 110.0),
            netIncome = annual(-8.0, -2.0, 14.0),
        )

        var v4 = OpportunityEngine.aggressiveV4FundamentalsScore(detail, null, series)
        var v5 = OpportunityEngine.aggressiveV5FundamentalsScore(detail, null, series)

        assertTrue(v4.factors.any { it.key == "Pulse" })
        assertTrue(v5.factors.any { it.key == "Pulse" })
        assertEquals(v4.score, v5.score)
    }

    /** Zero is not profit. A break-even year corroborates nothing. */
    @Test
    fun a_break_even_latest_year_does_not_corroborate_the_pulse() {
        var evidence = OpportunityEngine.aggressiveV5FundamentalsScore(
            detail(earningsGrowthBps = 3_800, trailingEpsCents = 500),
            null,
            FundamentalTimeseries(
                revenue = annual(80.0, 95.0, 110.0),
                netIncome = annual(-8.0, -2.0, 0.0),
            ),
        )

        assertFalse(evidence.factors.any { it.key == "Pulse" })
    }

    // ---------------------------------------------------------------- delta 2: unknown class

    /**
     * A bank-like row whose profile keys the policy tables do not know. V4 reads it as "not
     * financial services" and votes on its deposits with industrial bands. V5 fails closed: no
     * class, no leverage vote, and a flag that names why.
     */
    @Test
    fun v4_leverage_votes_on_an_unclassified_bank_and_v5_declines() {
        var fundamentals = unclassifiedBank()
        var v4 = OpportunityEngine.aggressiveV4FundamentalsScore(detailOf(fundamentals), null, null)
        var v5 = OpportunityEngine.aggressiveV5FundamentalsScore(detailOf(fundamentals), null, null)

        assertTrue(v4.factors.any { it.key == "D/E" || it.key == "ND/EBITDA" || it.key == "Bal" })
        assertFalse(v5.factors.any { it.key == "D/E" || it.key == "ND/EBITDA" || it.key == "Bal" })
        assertTrue(v5.signals.any { it.contains("Class") })
    }

    /** The strict refusal must be about the *unknown* class, never about known operating names. */
    @Test
    fun an_operating_row_still_takes_its_leverage_vote_under_v5() {
        var fundamentals = healthyOperating().fundamentals!!
        var v5 = OpportunityEngine.aggressiveV5FundamentalsScore(detailOf(fundamentals), null, null)

        assertTrue(v5.factors.any { it.key == "D/E" || it.key == "ND/EBITDA" || it.key == "Bal" })
    }

    // ---------------------------------------------------------------- policy unit

    @Test
    fun the_policy_classifies_an_unknown_profile_as_unclassified_not_operating() {
        assertEquals(BusinessClass.Unclassified, FinancialClassPolicy.classify(unclassifiedBank()))
    }

    // ---------------------------------------------------------------- fixtures

    private val MODEL = OpportunityScoringModel.AggressiveV5
    private val OPPORTUNITY_V4_MODEL = OpportunityScoringModel.AggressiveV4

    private data class Gates(val avoidBelow: Int, val actAtOrAbove: Int)

    private data class Wiring(
        val marketDimension: Boolean,
        val sectorBenchmarks: Boolean,
        val featureSet: MarketFeatureSet,
    )

    private fun healthyOperating() = detail(
        earningsGrowthBps = 900,
        trailingEpsCents = 500,
        forwardPeHundredths = 1_400,
        enterpriseToEbitdaHundredths = 900,
        priceToBookHundredths = 300,
        returnOnEquityBps = 1_800,
        freeCashFlowDollars = 900_000_000L,
        operatingCashFlowDollars = 1_200_000_000L,
        totalCashDollars = 700_000_000L,
        totalDebtDollars = 300_000_000L,
        sectorName = "Technology",
    )

    /** Deposit-funded balance sheet, profile keys no policy table knows. */
    private fun unclassifiedBank() = FundamentalSnapshot(
        symbol = "BNKX",
        sectorKey = "mystery-sector",
        industryKey = "mystery-industry",
        returnOnEquityBps = 1_100,
        debtToEquityHundredths = 650,
        totalCashDollars = 40_000_000_000L,
        totalDebtDollars = 120_000_000_000L,
        forwardPeHundredths = 900,
        priceToBookHundredths = 120,
    )

    private fun detail(
        earningsGrowthBps: Int? = null,
        trailingEpsCents: Long? = null,
        forwardPeHundredths: Int? = null,
        enterpriseToEbitdaHundredths: Int? = null,
        priceToBookHundredths: Int? = null,
        returnOnEquityBps: Int? = null,
        freeCashFlowDollars: Long? = null,
        operatingCashFlowDollars: Long? = null,
        totalCashDollars: Long? = null,
        totalDebtDollars: Long? = null,
        sectorName: String? = null,
    ) = SymbolDetail(
        symbol = "SUT",
        profitable = true,
        marketPriceCents = 19_950,
        intrinsicValueCents = 25_000,
        gapBps = 2_000,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        fundamentals = FundamentalSnapshot(
            symbol = "SUT",
            sectorName = sectorName,
            earningsGrowthBps = earningsGrowthBps,
            trailingEpsCents = trailingEpsCents,
            forwardPeHundredths = forwardPeHundredths,
            enterpriseToEbitdaHundredths = enterpriseToEbitdaHundredths,
            priceToBookHundredths = priceToBookHundredths,
            returnOnEquityBps = returnOnEquityBps,
            freeCashFlowDollars = freeCashFlowDollars,
            operatingCashFlowDollars = operatingCashFlowDollars,
            totalCashDollars = totalCashDollars,
            totalDebtDollars = totalDebtDollars,
        ),
    )

    private fun detailOf(fundamentals: FundamentalSnapshot) = SymbolDetail(
        symbol = fundamentals.symbol,
        profitable = true,
        marketPriceCents = 19_950,
        intrinsicValueCents = 25_000,
        gapBps = 2_000,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        fundamentals = fundamentals,
    )

    private fun annual(vararg values: Double) = values.mapIndexed { index, value ->
        AnnualReportedValue(asOfDate = "202${index}-12-31", value = value)
    }

    private fun annualShares(vararg values: Double) = annual(*values)
}
