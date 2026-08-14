package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ScoreFactor
import com.discountscreener.core.regime.RegimeCause
import com.discountscreener.core.regime.RegimeCauseEffect
import com.discountscreener.core.regime.RegimeCauseFactor
import com.discountscreener.core.regime.RegimeScoreStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreFactorUiTest {

    @Test
    fun groups_run_fundamentals_then_technicals_then_forecast_then_market() {
        var groups = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(ScoreFactor("FCFy", "FCFy+", 8)),
                technicalFactors = listOf(ScoreFactor("RSI", "RSI+", 3)),
                forecastFactors = listOf(ScoreFactor("Val", "Val++", 20)),
                regimeStatus = RegimeScoreStatus.Included,
                regimeScore = 33,
                regimeSignals = listOf("Liquidity+"),
            ),
        )

        assertEquals(listOf("Fundamentals", "Technicals", "Forecast", "Market"), groups.map { it.title })
    }

    @Test
    fun factors_inside_a_group_run_from_largest_absolute_contribution_to_smallest() {
        var group = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor("FCFy", "FCFy+", 8),
                    ScoreFactor("ROE§", "ROE§++", 16),
                    ScoreFactor("Hist", "Hist-", -5),
                ),
            ),
        ).first { it.title == "Fundamentals" }

        assertEquals(listOf("Return on equity vs sector", "FCF yield", "MACD histogram"), group.lines.map { it.label })
    }

    @Test
    fun a_sector_multiple_says_it_was_scored_against_its_sector() {
        assertEquals("Multiples vs sector", scoreFactorLabel("Mult§"))
    }

    @Test
    fun pulse_names_the_yahoo_quarter_field() {
        assertEquals("Quarter EPS YoY", scoreFactorLabel("Pulse"))
    }

    @Test
    fun val_names_the_street_target() {
        assertEquals("Street target", scoreFactorLabel("Val"))
    }

    @Test
    fun trend_names_the_revenue_window() {
        assertEquals("Revenue 3–5y", scoreFactorLabel("Trend"))
    }

    @Test
    fun a_growth_line_prints_the_rate_it_scored() {
        var line = scoreFactorGroups(
            row(fundamentalsFactors = listOf(ScoreFactor("Pulse", "Pulse--", -5, -1_100))),
        ).first().lines.single()

        assertEquals("Quarter EPS YoY · -11.0%", line.label)
    }

    @Test
    fun a_positive_contribution_carries_its_sign() {
        assertEquals("+16", formatBucketPoints(16))
    }

    @Test
    fun group_headers_use_the_snapshot_tokens() {
        assertEquals(
            listOf("F", "T", "Fc", "Market"),
            listOf("Fundamentals", "Technicals", "Forecast", "Market").map(::scoreFactorGroupToken),
        )
    }

    @Test
    fun a_bucket_header_names_its_reading() {
        var group = ScoreFactorGroupUi("Fundamentals", -22, emptyList())

        assertEquals(
            "F -22 · Weak",
            scoreFactorGroupTitle(group, OpportunityScoringModel.AggressiveV3),
        )
    }

    @Test
    fun a_legacy_bucket_header_keeps_the_five_point_scale() {
        var group = ScoreFactorGroupUi("Fundamentals", 3, emptyList())

        assertEquals(
            "F 3/5",
            scoreFactorGroupTitle(group, OpportunityScoringModel.Legacy),
        )
    }

    @Test
    fun a_market_signal_is_kept_even_when_the_bucket_was_not_included() {
        var titles = scoreFactorGroups(row(regimeSignals = listOf("Liquidity+"))).map { it.title }

        assertEquals(listOf("Market"), titles)
    }

    @Test
    fun the_market_group_is_absent_when_the_dimension_is_not_included() {
        var titles = scoreFactorGroups(row(regimeStatus = RegimeScoreStatus.Disabled)).map { it.title }

        assertEquals(emptyList<String>(), titles)
    }

    @Test
    fun a_market_support_cause_shows_its_fit_weight() {
        var market = scoreFactorGroups(
            row(
                regimeStatus = RegimeScoreStatus.Included,
                regimeScore = 18,
                regimeCauses = listOf(
                    RegimeCause(RegimeCauseFactor.Quality, RegimeCauseEffect.Support, 900),
                ),
            ),
        ).first { it.title == "Market" }

        assertEquals("+9", market.lines.single().pointsText)
    }

    @Test
    fun a_market_risk_cause_shows_a_negative_fit_weight() {
        var market = scoreFactorGroups(
            row(
                regimeStatus = RegimeScoreStatus.Included,
                regimeScore = 18,
                regimeCauses = listOf(
                    RegimeCause(RegimeCauseFactor.Extension, RegimeCauseEffect.Risk, 700),
                ),
            ),
        ).first { it.title == "Market" }

        assertEquals("-7", market.lines.single().pointsText)
    }

    @Test
    fun market_keeps_only_the_three_largest_causes() {
        var market = scoreFactorGroups(
            row(
                regimeStatus = RegimeScoreStatus.Included,
                regimeScore = 18,
                regimeCauses = listOf(
                    RegimeCause(RegimeCauseFactor.Growth, RegimeCauseEffect.Risk, 100),
                    RegimeCause(RegimeCauseFactor.Quality, RegimeCauseEffect.Support, 900),
                    RegimeCause(RegimeCauseFactor.Extension, RegimeCauseEffect.Risk, 700),
                    RegimeCause(RegimeCauseFactor.LowBeta, RegimeCauseEffect.Support, 400),
                ),
            ),
        ).first { it.title == "Market" }

        assertEquals(listOf("quality", "extension", "low beta"), market.lines.map { it.label })
    }

    private fun row(
        fundamentalsFactors: List<ScoreFactor> = emptyList(),
        technicalFactors: List<ScoreFactor> = emptyList(),
        forecastFactors: List<ScoreFactor> = emptyList(),
        regimeStatus: RegimeScoreStatus = RegimeScoreStatus.NotApplicable,
        regimeScore: Int? = null,
        regimeCauses: List<RegimeCause> = emptyList(),
        regimeSignals: List<String> = emptyList(),
    ) = OpportunityListRow(
        symbol = "SNDK",
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 5_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        fundamentalsScore = 58,
        technicalScore = 6,
        forecastScore = 72,
        regimeScore = regimeScore,
        compositeScore = 52,
        coverageCount = 4,
        fundamentalsFactors = fundamentalsFactors,
        technicalFactors = technicalFactors,
        forecastFactors = forecastFactors,
        regimeStatus = regimeStatus,
        regimeCauses = regimeCauses,
        regimeSignals = regimeSignals,
    )
}
