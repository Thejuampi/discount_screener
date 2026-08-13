package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The share count enters the fundamentals bucket, which it never did before.
 *
 * Both providers download the annual series and, until V4, only its last point was ever read — the
 * count, never the change. A company that buys its own shares and one that pays for growth by
 * issuing them scored identically.
 *
 * Every case below holds the share series as the *only* fact about the company, so the bucket is
 * the term and nothing else can mask it.
 */
class AggressiveV4ShareCountTest {

    /**
     * The claim, in one comparison. Both rows are the same company by every other measure.
     *
     *  1000 → 970   −300 bps, the whole shrink edge  → +1 × 10 / 110 →  +9
     *  1000 → 1030  +300 bps, the whole dilute edge  → −1 × 10 / 110 →  −9
     */
    @Test
    fun a_shrinking_share_count_outscores_a_growing_one() {
        var buyback = score(1_000.0, 970.0)
        var dilution = score(1_000.0, 1_030.0)

        assertEquals(listOf(9, -9), listOf(buyback, dilution))
    }

    /**
     * One annual point cannot say which way the count moved, so it contributes nothing — and
     * nothing is not zero.
     *
     * With the series as the only fact, "nothing" is a bucket that refuses and "zero" is a bucket
     * that reports a neutral company. The two are a whole grade apart in the list and this is the
     * assertion that tells them apart.
     */
    @Test
    fun a_single_annual_point_is_not_a_change_and_scores_no_bucket_at_all() {
        assertNull(score(970.0))
    }

    /**
     * A pair that did not move is a real observation and does score — at the middle of the ramp.
     * This is the case that separates "no series" from "a flat series": one refuses, one reports.
     */
    @Test
    fun a_flat_pair_is_an_observation_and_reports_the_middle_of_the_ramp() {
        assertEquals(0, score(1_000.0, 1_000.0))
    }

    /**
     * A reported count of zero is bad data, not a company with no shares, and it is dropped before
     * the pair is formed.
     *
     * Left in, it would be the denominator of the change: 970 against a previous of zero is an
     * infinite increase, `smoothRamp` pins that at the dilute edge, and the bucket reports a
     * confident −1 about a company it knows nothing about. Dropping it leaves one usable point,
     * which is no pair, which is no term.
     */
    @Test
    fun a_reported_count_of_zero_is_dropped_rather_than_used_as_a_denominator() {
        assertNull(score(0.0, 970.0))
    }

    /**
     * The series reaches the bucket through the list, and this is the only test that says so.
     *
     * Everything above calls the scorer directly and would stay green if the context carried the
     * map and the row never looked its symbol up.
     */
    @Test
    fun the_list_reads_a_symbols_share_series_from_the_context() {
        var engine = ReportingEngine()
        engine.ingestSnapshot(
            MarketSnapshot(symbol = "SUT", profitable = true, marketPriceCents = 8_000, intrinsicValueCents = 10_000),
        )
        engine.ingestFundamentals(FundamentalSnapshot(symbol = "SUT", sectorName = "Technology"))

        var context = OpportunityContext(
            scoringModel = OpportunityScoringModel.AggressiveV4,
            timeseriesBySymbol = mapOf("SUT" to timeseries(1_000.0, 970.0)),
        )

        assertEquals(9, OpportunityEngine.buildRows(engine, context).single().fundamentalsScore)
    }

    private fun score(vararg shares: Double) = OpportunityEngine
        .aggressiveV4FundamentalsScore(detail(), sectorBenchmarks = null, timeseries = timeseries(*shares))
        .first

    /** Oldest first, as both providers sort it. */
    private fun timeseries(vararg shares: Double) = FundamentalTimeseries(
        dilutedAverageShares = shares.mapIndexed { index, value ->
            AnnualReportedValue(asOfDate = "202${index}-12-31", value = value)
        },
    )

    private fun detail() = SymbolDetail(
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
        fundamentals = FundamentalSnapshot(symbol = "SUT", sectorName = "Technology"),
    )
}
