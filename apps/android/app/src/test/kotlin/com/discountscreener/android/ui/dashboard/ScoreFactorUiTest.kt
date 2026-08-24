package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ScoreFactor
import com.discountscreener.core.model.ScoreFactorComparison
import com.discountscreener.core.model.ScoreFactorValueKind
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
    fun a_sector_multiple_prints_the_symbol_against_the_sector() {
        var line = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor(
                        "Mult§",
                        "Mult§--",
                        -22,
                        comparisons = listOf(
                            ScoreFactorComparison(250, ScoreFactorValueKind.Multiple, "P/E", 850),
                        ),
                    ),
                ),
            ),
        ).first().lines.single()

        assertEquals("Multiples vs sector · P/E 2.5 vs 8.5", line.label)
    }

    @Test
    fun three_multiples_name_each_pair() {
        var line = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor(
                        "Mult",
                        "Mult+",
                        2,
                        comparisons = listOf(
                            ScoreFactorComparison(2_000, ScoreFactorValueKind.Multiple, "P/E", referenceLow = 800, referenceHigh = 3_500),
                            ScoreFactorComparison(1_200, ScoreFactorValueKind.Multiple, "EV/EBITDA", referenceLow = 600, referenceHigh = 2_000),
                            ScoreFactorComparison(300, ScoreFactorValueKind.Multiple, "P/B", referenceLow = 100, referenceHigh = 500),
                        ),
                    ),
                ),
            ),
        ).first().lines.single()

        assertEquals(
            "Multiples · P/E 20.0 vs 8.0–35.0 · EV/EBITDA 12.0 vs 6.0–20.0 · P/B 3.0 vs 1.0–5.0",
            line.label,
        )
    }

    @Test
    fun a_sector_roe_prints_both_rates() {
        var line = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor(
                        "ROE§",
                        "ROE§++",
                        16,
                        comparisons = listOf(
                            ScoreFactorComparison(1_500, ScoreFactorValueKind.Percent, reference = 1_200),
                        ),
                    ),
                ),
            ),
        ).first().lines.single()

        assertEquals("Return on equity vs sector · 15.0% vs 12.0%", line.label)
    }

    @Test
    fun free_cash_flow_prints_dollars_against_zero_and_why_it_is_a_sign_vote() {
        var line = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor(
                        "FCF",
                        "FCF++",
                        20,
                        comparisons = listOf(
                            ScoreFactorComparison(
                                0,
                                ScoreFactorValueKind.Dollars,
                                why = "sign only, no market cap",
                                observedDollars = 1_200_000_000,
                                referenceDollars = 0,
                            ),
                        ),
                    ),
                ),
            ),
        ).first().lines.single()

        assertEquals("Free cash flow · $1.2B vs $0 · sign only, no market cap", line.label)
    }

    @Test
    fun missing_dollar_fields_do_not_print_zero() {
        var line = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor(
                        "FCF",
                        "FCF+",
                        4,
                        comparisons = listOf(
                            ScoreFactorComparison(
                                0,
                                ScoreFactorValueKind.Dollars,
                                why = "sign only, no market cap",
                            ),
                        ),
                    ),
                ),
            ),
        ).first().lines.single()

        assertEquals("Free cash flow · sign only, no market cap", line.label)
    }

    @Test
    fun fcf_yield_prints_the_rate_against_the_policy_band() {
        var line = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor(
                        "FCFy",
                        "FCFy+",
                        8,
                        comparisons = listOf(
                            ScoreFactorComparison(
                                610,
                                ScoreFactorValueKind.Percent,
                                referenceLow = -200,
                                referenceHigh = 800,
                                why = "FCF / market cap",
                            ),
                        ),
                    ),
                ),
            ),
        ).first().lines.single()

        assertEquals("FCF yield · 6.1% vs -2.0%–8.0% · FCF / market cap", line.label)
    }

    @Test
    fun cash_conversion_prints_the_ratio_and_what_it_divides() {
        var line = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor(
                        "Conv",
                        "Conv-",
                        -6,
                        comparisons = listOf(
                            ScoreFactorComparison(
                                40,
                                ScoreFactorValueKind.Multiple,
                                referenceLow = 0,
                                referenceHigh = 100,
                                why = "FCF / OCF",
                            ),
                        ),
                    ),
                ),
            ),
        ).first().lines.single()

        assertEquals("Cash conversion · 0.4 vs 0.0–1.0 · FCF / OCF", line.label)
    }

    @Test
    fun a_growth_line_prints_the_rate_it_scored() {
        var line = scoreFactorGroups(
            row(fundamentalsFactors = listOf(ScoreFactor("Pulse", "Pulse--", -5, -1_100))),
        ).first().lines.single()

        assertEquals("Quarter EPS YoY · -11.0%", line.label)
    }

    /**
     * The engine has raised this flag since V4 shipped and nothing between it and the screen was
     * ever tested. A flag carries zero points, so it survives only if the group keeps a line for a
     * factor that contributes nothing — which is the exact thing a points-ordered list invites
     * someone to drop.
     */
    @Test
    fun the_growth_divergence_flag_reaches_the_breakdown() {
        var labels = scoreFactorGroups(
            row(
                fundamentalsFactors = listOf(
                    ScoreFactor("Trend", "Trend++", 5),
                    ScoreFactor("Pulse", "Pulse--", -5),
                    ScoreFactor("Pulse≠Trend", "Pulse≠Trend", 0),
                ),
            ),
        ).first().lines.map { it.label }

        assertEquals(true, "Pulse and Trend disagree" in labels)
    }

    @Test
    fun a_row_that_scored_a_quarter_says_where_the_quarter_comes_from() {
        var notes = scoreFactorNotes(
            scoreFactorGroups(row(fundamentalsFactors = listOf(ScoreFactor("Pulse", "Pulse--", -5)))),
        )

        assertEquals(listOf(PULSE_BASIS_NOTE), notes)
    }

    /**
     * `Pulse≠Trend` begins with `Pulse`, so a note matched on the token instead of the key would
     * print the basis of a term this row never scored.
     */
    @Test
    fun the_divergence_flag_alone_does_not_claim_a_quarter_was_scored() {
        var notes = scoreFactorNotes(
            scoreFactorGroups(row(fundamentalsSignals = listOf("Pulse≠Trend"))),
        )

        assertEquals(listOf(PULSE_TREND_DIVERGENCE_NOTE), notes)
    }

    /** The real V4 case: the basis first, because the divergence note reads as nonsense without it. */
    @Test
    fun a_diverging_row_states_the_basis_before_the_disagreement() {
        var notes = scoreFactorNotes(
            scoreFactorGroups(
                row(
                    fundamentalsFactors = listOf(
                        ScoreFactor("Trend", "Trend++", 5),
                        ScoreFactor("Pulse", "Pulse--", -5),
                        ScoreFactor("Pulse≠Trend", "Pulse≠Trend", 0),
                    ),
                ),
            ),
        )

        assertEquals(listOf(PULSE_BASIS_NOTE, PULSE_TREND_DIVERGENCE_NOTE), notes)
    }

    /**
     * The mark carries the size of its own claim. Without the sentence a reader could take
     * "Earnings at a cycle peak" for a cycle-adjusted valuation, which is not what five annual
     * points can say.
     */
    @Test
    fun a_row_marked_at_a_cycle_peak_states_what_the_mark_measures() {
        var notes = scoreFactorNotes(
            scoreFactorGroups(row(fundamentalsFactors = listOf(ScoreFactor("CyclePeak", "CyclePeak-", -4)))),
        )

        assertEquals(listOf(CYCLE_PEAK_NOTE), notes)
    }

    @Test
    fun the_cycle_peak_factor_is_named_in_words() {
        assertEquals("Earnings at a cycle peak", scoreFactorLabel("CyclePeak"))
    }

    /**
     * The row shows a signal with no points. Without the sentence, "Impairment or restructuring
     * charge" gives a size the reader has no way to judge against the year it landed in.
     */
    @Test
    fun a_row_marked_for_charges_states_what_the_mark_measures() {
        var notes = scoreFactorNotes(
            scoreFactorGroups(row(fundamentalsFactors = listOf(ScoreFactor("Charges", "Charges", 0)))),
        )

        assertEquals(listOf(EARNINGS_CHARGE_NOTE), notes)
    }

    @Test
    fun the_charges_factor_is_named_in_words() {
        assertEquals("Impairment or restructuring charge", scoreFactorLabel("Charges"))
    }

    @Test
    fun a_financial_fcf_skip_states_why_the_vote_is_absent() {
        var notes = scoreFactorNotes(
            scoreFactorGroups(
                row(fundamentalsFactors = listOf(ScoreFactor("FCFy∅ financial", "FCFy∅ financial", 0))),
            ),
        )

        assertEquals(listOf(FCF_FINANCIAL_NOTE), notes)
    }

    @Test
    fun incomplete_fundamentals_state_that_the_score_can_move() {
        var notes = scoreFactorNotes(
            scoreFactorGroups(
                row(fundamentalsFactors = listOf(ScoreFactor("Fund∅ coverage", "Fund∅ coverage", 0))),
            ),
        )

        assertEquals(listOf(FUND_COVERAGE_GAP_NOTE), notes)
    }

    @Test
    fun the_financial_fcf_skip_is_named_in_words() {
        assertEquals("FCF yield skipped (financials)", scoreFactorLabel("FCFy∅ financial"))
    }

    @Test
    fun a_row_with_neither_term_carries_no_notes() {
        assertEquals(
            emptyList<String>(),
            scoreFactorNotes(scoreFactorGroups(row(fundamentalsFactors = listOf(ScoreFactor("FCFy", "FCFy+", 8))))),
        )
    }

    @Test
    fun a_positive_contribution_carries_its_sign() {
        assertEquals("+16", formatBucketPoints(16))
    }

    @Test
    fun a_bucket_header_names_its_reading() {
        var group = ScoreFactorGroupUi("Fundamentals", -22, emptyList())

        assertEquals(
            "Fundamentals -22 · Weak",
            scoreFactorGroupTitle(group, OpportunityScoringModel.AggressiveV3),
        )
    }

    @Test
    fun a_legacy_bucket_header_keeps_the_five_point_scale() {
        var group = ScoreFactorGroupUi("Fundamentals", 3, emptyList())

        assertEquals(
            "Fundamentals 3/5",
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
        fundamentalsSignals: List<String> = emptyList(),
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
        fundamentalsSignals = fundamentalsSignals,
        fundamentalsFactors = fundamentalsFactors,
        technicalFactors = technicalFactors,
        forecastFactors = forecastFactors,
        regimeStatus = regimeStatus,
        regimeCauses = regimeCauses,
        regimeSignals = regimeSignals,
    )
}
