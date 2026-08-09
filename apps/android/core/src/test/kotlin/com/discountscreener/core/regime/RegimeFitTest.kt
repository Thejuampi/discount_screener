package com.discountscreener.core.regime

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.FundamentalSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegimeFitTest {
    /**
     * One feature is not a fit, it is a coincidence — the L1-normalized mean would read as a
     * confident ±100 on the strength of a single input. The whole result is compared rather than
     * just the reason, so a refusal that still leaked a score would fail here.
     */
    @Test
    fun a_symbol_with_one_readable_feature_is_refused() {
        assertEquals(
            RegimeFitResult.insufficient(),
            scoreRegimeFit(FundamentalSnapshot(symbol = "X", betaMillis = 900), daily = null, policy = accumulate()),
        )
    }

    /**
     * The other side of the same boundary. Without this, a `scoreRegimeFit` that refused
     * unconditionally would pass the refusal test and nothing would notice.
     */
    @Test
    fun two_readable_features_are_enough_to_score() {
        assertNotNull(
            scoreRegimeFit(
                FundamentalSnapshot(symbol = "X", betaMillis = 900, marketCapDollars = 10_000_000_000L),
                daily = null,
                policy = accumulate(),
            ).score,
        )
    }

    /** Coverage can be met and the policy still weight none of it — also a refusal, not a zero. */
    @Test
    fun a_policy_that_weights_nothing_the_symbol_has_is_refused() {
        assertEquals(
            RegimeFitResult.insufficient(),
            scoreRegimeFit(qualityFundamentals(), oversoldChart(), policy = weights(oversold = 0.0, liquidity = 0.0)),
        )
    }

    @Test
    fun a_quality_low_beta_name_fits_a_flight_to_quality() {
        assertTrue(scoreRegimeFit(qualityFundamentals(), oversoldChart(), defend()).score!! > 0)
    }

    @Test
    fun a_junk_high_beta_name_does_not_fit_a_flight_to_quality() {
        assertTrue(scoreRegimeFit(junkFundamentals(), oversoldChart(), defend()).score!! < 0)
    }

    /**
     * The dip-buying gate, measured on its own.
     *
     * Both names are deeply oversold and identical everywhere the liquidity feature reads; they
     * differ only in cash flow, leverage and return on equity — the inputs to `quality`. The policy
     * gives quality *zero* weight, so the quality feature itself cannot move the score. Any
     * difference that survives is the gate, which is the thing this test claims to measure.
     */
    @Test
    fun an_oversold_junk_name_gets_no_credit_for_being_cheap() {
        assertTrue(
            scoreRegimeFit(qualityFundamentals(), oversoldChart(), meanRevertIgnoringQuality()).score!! >
                scoreRegimeFit(junkFundamentals(), oversoldChart(), meanRevertIgnoringQuality()).score!!,
        )
    }

    /**
     * Same isolation as the gate test: the policy pays for defensiveness and nothing for growth, so
     * the only channel between these two names is the sector flag. Reading the flags off a *live*
     * stance would instead measure whether the defensive constant happens to sit above the rest of
     * that stance's mean, which is arithmetic about the fixture, not about the sector.
     */
    @Test
    fun a_defensive_sector_scores_above_a_growth_sector_when_the_policy_defends() {
        assertTrue(
            scoreRegimeFit(qualityFundamentals("Utilities"), oversoldChart(), defensiveOnly()).score!! >
                scoreRegimeFit(qualityFundamentals("Technology"), oversoldChart(), defensiveOnly()).score!!,
        )
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun accumulate() = requireNotNull(
        RegimeScoringPolicy.fromRegime(
            MarketRegime(
                primaryRegime = "Bull",
                environmentBand = "RiskOn",
                actionStance = "Accumulate",
                globalConfidenceBps = 8000,
            ),
        ),
    )

    private fun defend() = requireNotNull(
        RegimeScoringPolicy.fromRegime(
            MarketRegime(
                primaryRegime = "Correction",
                environmentBand = "RiskOff",
                actionStance = "Defend",
                globalConfidenceBps = 8000,
            ),
        ),
    )

    private fun meanRevertIgnoringQuality() = weights(oversold = 1.0, liquidity = 0.5)

    private fun defensiveOnly() = weights(defensive = 1.0, liquidity = 0.5)

    /** A hand-built policy: every weight zero except the ones named, so the test reads one channel. */
    private fun weights(
        oversold: Double = 0.0,
        defensive: Double = 0.0,
        liquidity: Double = 0.0,
    ) = RegimeScoringPolicy(
        stance = "Synthetic",
        environmentBand = "RiskOff",
        primaryRegime = "Correction",
        wQuality = 0.0,
        wLowBeta = 0.0,
        wValue = 0.0,
        wOversoldQuality = oversold,
        wAntiExtension = 0.0,
        wTrend = 0.0,
        wDefensive = defensive,
        wGrowth = 0.0,
        wLiquidity = liquidity,
        betaHaircutMult = 1.0,
        strength = 1.0,
        preferQuality = false,
        label = "synthetic",
    )

    private fun qualityFundamentals(sector: String? = null) = FundamentalSnapshot(
        symbol = "GOOD",
        sectorName = sector,
        marketCapDollars = 10_000_000_000L,
        returnOnEquityBps = 2_000,
        debtToEquityHundredths = 30,
        freeCashFlowDollars = 800_000_000L,
        operatingCashFlowDollars = 1_000_000_000L,
        betaMillis = 700,
    )

    private fun junkFundamentals() = FundamentalSnapshot(
        symbol = "JUNK",
        marketCapDollars = 10_000_000_000L,
        returnOnEquityBps = -500,
        debtToEquityHundredths = 400,
        freeCashFlowDollars = -500_000_000L,
        betaMillis = 1_800,
    )

    private fun oversoldChart() = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0L,
        candleCount = 260,
        latestCloseCents = 8_000L,
        ema20Cents = 9_000L,
        ema50Cents = 9_500L,
        ema200Cents = 11_000L,
        latestWilderRsi = 20.0,
        volumeRatioHundredths = 100,
        pos52wPct = 5.0,
        bbPercentB = 0.0,
    )
}
