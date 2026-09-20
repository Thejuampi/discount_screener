package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.earnings.EXCHANGE_ZONE
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.QuantLensLensRowState
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.QuantLensRowSummary
import com.discountscreener.core.portfolio.Closeness
import com.discountscreener.core.portfolio.Coverage
import com.discountscreener.core.portfolio.PortfolioQuote
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.nySessionDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class PositionsPresentationTest {

    @Test
    fun phyl_is_an_off_feed_row() {
        var rows = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        )
        assertEquals(
            listOf("PHYL" to true),
            rows.map { it.symbol to (it.opportunity == null) },
        )
    }

    @Test
    fun phyl_qty_is_shares() {
        var row = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals("1273", row.quantityLabel)
    }

    @Test
    fun tracked_price_without_research_projects_as_current_exposure() {
        val tracked = TrackedSymbolRow(
            symbol = "AAA",
            marketPriceCents = 10_000L,
            state = TrackedRowState.Live,
            freshness = RowFreshness.Updated,
        )
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = portfolioQuotesFromTrackedRows(listOf(tracked)),
        ).single()

        assertEquals(10_000L, row.quoteCents)
        assertEquals(true, row.quoteIsCurrent)
        assertEquals(10_000L, row.marketValueCents)
        assertEquals("Missing analysis", row.researchReason)
    }

    @Test
    fun opps_row_wins_over_universe() {
        var universe = opp("AMZN", confidence = ConfidenceBand.High)
        var opps = opp("AMZN", confidence = ConfidenceBand.Low)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = scoredLots(listOf(universe), listOf(opps)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(opps, row.opportunity)
    }

    @Test
    fun amzn_keeps_the_scored_row_pointer() {
        var scored = opp("AMZN")
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(scored),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(scored, row.opportunity)
    }

    @Test
    fun amzn_qty_is_shares() {
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals("36.2954", row.quantityLabel)
    }

    @Test
    fun tagged_sorts_ahead_of_blank() {
        var rows = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L), lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = mapOf("AMZN" to LocalDate.of(2026, 9, 7)),
            today = LocalDate.of(2026, 9, 7),
        )
        assertEquals(listOf("AMZN", "PHYL"), rows.map { it.symbol })
    }

    @Test
    fun prefix_lot_does_not_take_the_amzn_strip() {
        var row = projectPositions(
            lots = listOf(lot("A", 100_000L, 10_000L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(null, row.opportunity)
    }

    @Test
    fun scored_row_with_no_date_is_none() {
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.None, row.closeness)
    }

    @Test
    fun sort_is_closeness_then_ticker() {
        var monday = LocalDate.of(2026, 9, 7)
        var rows = projectPositions(
            lots = listOf(
                lot("PHYL", 12_730_000L, 3_528L),
                lot("AMZN", 362_954L, 21_403L),
                lot("MSFT", 100_000L, 10_000L),
            ),
            scored = listOf(opp("AMZN"), opp("MSFT")),
            upcomingReport = mapOf(
                "MSFT" to monday,
                "AMZN" to LocalDate.of(2026, 9, 14),
            ),
            today = monday,
        )
        assertEquals(listOf("MSFT", "AMZN", "PHYL"), rows.map { it.symbol })
    }

    @Test
    fun blank_closeness_sorts_by_ticker() {
        var rows = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L), lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        )
        assertEquals(listOf("AMZN", "PHYL"), rows.map { it.symbol })
    }

    @Test
    fun empty_lots_paint_no_rows() {
        assertEquals(
            emptyList<String>(),
            projectPositions(emptyList(), emptyList(), emptyMap(), LocalDate.of(2026, 9, 7)).map { it.symbol },
        )
    }

    @Test
    fun presented_gate_log_date_is_today() {
        var monday = LocalDate.of(2026, 9, 7)
        var yahooLater = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var gate = presentEarningsGate(
            events = listOf(event("AMZN", monday)),
            damagedLines = 0,
            today = monday,
        )
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = yahooLater)),
            upcomingReport = upcomingReportDates(gate, monday),
            today = monday,
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun settled_ny_today_print_is_still_today() {
        var monday = LocalDate.of(2026, 9, 7)
        var yahooLater = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var gate = presentEarningsGate(
            events = listOf(event("AMZN", monday)),
            damagedLines = 0,
            today = monday.plusDays(1),
        )
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = emptyList(),
            upcomingReport = reportDatesForLots(gate, mapOf("AMZN" to yahooLater), monday),
            today = monday,
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun log_date_beats_yahoo_epoch() {
        var monday = LocalDate.of(2026, 9, 7)
        var yahooLater = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = yahooLater)),
            upcomingReport = mapOf("AMZN" to monday),
            today = monday,
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun yahoo_epoch_is_the_fallback() {
        var tomorrow = LocalDateTime.of(2026, 9, 8, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = tomorrow)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.Tomorrow, row.closeness)
    }

    @Test
    fun yahoo_utc_midnight_uses_ny_session_day() {
        var midnight = LocalDateTime.of(2026, 9, 8, 0, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = midnight)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun past_yahoo_epoch_is_none() {
        var past = LocalDateTime.of(2026, 9, 6, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = past)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.None, row.closeness)
    }

    @Test
    fun off_feed_ignores_yahoo() {
        var tomorrow = LocalDateTime.of(2026, 9, 8, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("PHYL", 12_730_000L, 3_528L)),
            scored = listOf(opp("AMZN", nextEarningsEpoch = tomorrow)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.None, row.closeness)
    }

    @Test
    fun a_book_lot_uses_the_shared_calendar() {
        var later = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var row = projectPositions(
            lots = listOf(lot("BSX", 93_357L, 4_576L)),
            scored = emptyList(),
            upcomingReport = reportDatesForLots(
                EarningsGateUi(),
                mapOf("BSX" to later),
                LocalDate.of(2026, 9, 7),
            ),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals(Closeness.Later, row.closeness)
    }

    @Test
    fun the_log_beats_the_calendar() {
        var monday = LocalDate.of(2026, 9, 7)
        var later = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var gate = presentEarningsGate(
            events = listOf(event("ORCL", monday)),
            damagedLines = 0,
            today = monday,
        )
        var row = projectPositions(
            lots = listOf(lot("ORCL", 54_345L, 13_939L)),
            scored = emptyList(),
            upcomingReport = reportDatesForLots(gate, mapOf("ORCL" to later), monday),
            today = monday,
        ).single()
        assertEquals(Closeness.Today, row.closeness)
    }

    @Test
    fun a_settled_log_date_does_not_hide_a_future_calendar_date() {
        var monday = LocalDate.of(2026, 9, 7)
        var settled = monday.minusDays(1)
        var later = LocalDateTime.of(2026, 9, 14, 12, 0).toEpochSecond(ZoneOffset.UTC)
        var gate = presentEarningsGate(
            events = listOf(event("AMZN", settled)),
            damagedLines = 0,
            today = monday,
        )

        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = emptyList(),
            upcomingReport = reportDatesForLots(gate, mapOf("AMZN" to later), monday),
            today = monday,
        ).single()

        assertEquals(Closeness.Later, row.closeness)
    }

    @Test
    fun a_settled_log_date_does_not_hide_a_future_log_date() {
        var monday = LocalDate.of(2026, 9, 7)
        var settled = monday.minusDays(1)
        var gate = presentEarningsGate(
            events = listOf(
                event("AMZN", settled),
                event("AMZN", monday.plusDays(1)),
            ),
            damagedLines = 0,
            today = monday,
        )

        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = emptyList(),
            upcomingReport = reportDatesForLots(gate, emptyMap(), monday),
            today = monday,
        ).single()

        assertEquals(Closeness.Tomorrow, row.closeness)
    }

    @Test
    fun the_nearest_upcoming_log_date_wins_when_a_ticker_has_multiple_future_records() {
        var monday = LocalDate.of(2026, 9, 7)
        var gate = presentEarningsGate(
            events = listOf(
                event("AMZN", monday.plusDays(7)),
                event("AMZN", monday.plusDays(1)),
            ),
            damagedLines = 0,
            today = monday,
        )

        var row = projectPositions(
            lots = listOf(lot("AMZN", 362_954L, 21_403L)),
            scored = emptyList(),
            upcomingReport = upcomingReportDates(gate, monday),
            today = monday,
        ).single()

        assertEquals(Closeness.Tomorrow, row.closeness)
    }

    @Test
    fun madrid_clock_does_not_move_ny_today() {
        var nyNoon = LocalDateTime.of(2026, 9, 7, 12, 0)
            .atZone(EXCHANGE_ZONE)
            .toInstant()
            .epochSecond
        assertEquals(LocalDate.of(2026, 9, 7), nySessionDay(nyNoon))
    }

    @Test
    fun complete_book_summary_uses_enriched_rows() {
        val rows = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L), lot("BBB", 10_000L, 20_000L)),
            scored = listOf(opp("AAA"), opp("BBB").copy(marketPriceCents = 30_000L)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L), "BBB" to PortfolioQuote(30_000L)),
        )
        val summary = presentPositionsSummary(rows)

        assertEquals(40_000L, summary.totalValueCents)
        assertEquals(Coverage.Complete, summary.valueCoverage)
        assertEquals(listOf(2_500, 7_500), rows.sortedBy { it.symbol }.map { it.weightBps })
        assertEquals(15_000L, summary.profitLossCents)
    }

    @Test
    fun partial_book_summary_keeps_coverage_and_refuses_weights() {
        val rows = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L), lot("BBB", 10_000L, 20_000L)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L)),
        )
        val summary = presentPositionsSummary(rows)

        assertEquals(Coverage.Partial, summary.valueCoverage)
        assertEquals(listOf(null, null), rows.map { it.weightBps })
    }

    @Test
    fun summary_missing_default_exposure_row_stays_partial() {
        val projected = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L), lot("BBB", 10_000L, 20_000L)),
            scored = listOf(opp("AAA"), opp("BBB")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        )
        val rows = listOf(projected[0], projected[1].copy(exposure = null))

        assertEquals(Coverage.Partial, presentPositionsSummary(rows).valueCoverage)
    }

    @Test
    fun explicit_stored_quote_blocks_current_research() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L, isCurrent = false)),
        ).single()

        assertEquals("Check data", row.reviewLabel)
        assertEquals("Stored evidence", row.researchReason)
    }

    @Test
    fun non_current_quote_without_analysis_keeps_value_without_stored_claim() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L, isCurrent = false)),
        ).single()

        assertEquals(10_000L, row.quoteCents)
        assertEquals(10_000L, row.marketValueCents)
        assertEquals("Missing analysis", row.researchReason)
        assertEquals(null, row.storedScoreMarker)
    }

    @Test
    fun current_identity_research_shows_model_relation() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    intrinsicValueCents = 12_000L,
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Model: Undervalued", row.valuationLabel)
    }

    @Test
    fun current_act_research_shows_review_opportunity() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Review opportunity", row.reviewLabel)
    }

    @Test
    fun current_watch_research_shows_monitor() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Watch,
                    valuationStanceLabel = "Identity",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Monitor", row.reviewLabel)
        assertEquals("Monitor", row.researchReason)
    }

    @Test
    fun current_avoid_research_shows_review_position() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Avoid,
                    valuationStanceLabel = "Identity",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Review position", row.reviewLabel)
        assertEquals("Review position", row.researchReason)
    }

    @Test
    fun current_act_provider_error_shows_exact_reason() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    providerIssue = "Provider timeout",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Check data", row.reviewLabel)
        assertEquals("Provider timeout", row.researchReason)
    }

    @Test
    fun blank_provider_error_does_not_block_current_act() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    providerIssue = "",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Review opportunity", row.reviewLabel)
        assertEquals("Review opportunity", row.researchReason)
    }

    @Test
    fun whitespace_provider_error_does_not_block_current_act() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    providerIssue = " \t",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Review opportunity", row.reviewLabel)
        assertEquals("Review opportunity", row.researchReason)
    }

    @Test
    fun model_value_note_is_neutral_when_confidence_is_high() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    trustNote = "Model value",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Review opportunity", row.reviewLabel)
    }

    @Test
    fun model_value_note_does_not_bypass_low_confidence() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA", confidence = ConfidenceBand.Low).copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    trustNote = "Model value",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Check data", row.reviewLabel)
        assertEquals("Low confidence", row.researchReason)
    }

    @Test
    fun blank_trust_note_is_absent() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    trustNote = "",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Review opportunity", row.reviewLabel)
    }

    @Test
    fun whitespace_trust_note_is_absent() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    trustNote = " \t",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Review opportunity", row.reviewLabel)
    }

    @Test
    fun current_analyst_research_shows_analyst_relation() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Watch,
                    valuationStanceLabel = "Analyst range",
                    intrinsicValueCents = 8_000L,
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Monitor", row.reviewLabel)
        assertEquals("Analyst: Below price", row.valuationLabel)
    }

    @Test
    fun disputed_rows_show_check_data_and_reason() {
        val disputed = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Disputed",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()
        assertEquals("Check data", disputed.reviewLabel)
        assertEquals("Disputed", disputed.researchReason)
    }

    @Test
    fun provisional_rows_show_check_data_and_reason() {
        val provisional = projectPositions(
            lots = listOf(lot("BBB", 10_000L, 5_000L)),
            scored = listOf(
                opp("BBB").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    trustNote = "Provisional inputs",
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Check data", provisional.reviewLabel)
        assertEquals("Provisional inputs", provisional.researchReason)
    }

    @Test
    fun matching_lens_provisional_status_requires_check_data() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    trustNote = "Model value",
                    quantLensSummary = lensSummary("AAA", QuantLensPrimaryStatus.Provisional),
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Check data", row.reviewLabel)
        assertEquals("Provisional evidence", row.researchReason)
    }

    @Test
    fun matching_lens_disputed_status_requires_check_data() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    trustNote = "Model value",
                    quantLensSummary = lensSummary("AAA", QuantLensPrimaryStatus.Disputed),
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Check data", row.reviewLabel)
        assertEquals("Disputed evidence", row.researchReason)
    }

    @Test
    fun mismatched_lens_summary_does_not_block_the_position() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(
                opp("AAA").copy(
                    freshness = com.discountscreener.android.domain.model.RowFreshness.Updated,
                    decisionState = com.discountscreener.android.domain.model.RowDecisionState.Act,
                    valuationStanceLabel = "Identity",
                    trustNote = "Model value",
                    quantLensSummary = lensSummary("BBB", QuantLensPrimaryStatus.Disputed),
                ),
            ),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Review opportunity", row.reviewLabel)
    }

    @Test
    fun stored_research_shows_check_data_and_marker() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(opp("AAA").copy(valuationStanceLabel = "Identity")),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Check data", row.reviewLabel)
        assertEquals("Stored", row.storedScoreMarker)
    }

    @Test
    fun exact_stance_labels_do_not_infer_legacy_primary() {
        val row = projectPositions(
            lots = listOf(lot("AAA", 10_000L, 5_000L)),
            scored = listOf(opp("AAA").copy(valuationStanceLabel = null)),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Check data", row.reviewLabel)
        assertEquals(null, row.valuationLabel)
    }

    @Test
    fun absent_research_shows_check_data_and_missing_quote_reason() {
        val row = projectPositions(
            lots = listOf(lot("PHYL", 10_000L, 5_000L)),
            scored = emptyList(),
            upcomingReport = emptyMap(),
            today = LocalDate.of(2026, 9, 7),
        ).single()

        assertEquals("Check data", row.reviewLabel)
        assertEquals("Missing quote", row.researchReason)
    }

    @Test
    fun largest_sort_puts_unavailable_values_last() {
        val rows = listOf(
            PositionsRow("BBB", "1", 1L, Closeness.None, null, inputIndex = 1, marketValueCents = 2_000L),
            PositionsRow("AAA", "1", 1L, Closeness.None, null, inputIndex = 0),
        )

        assertEquals(listOf("BBB", "AAA"), sortPositionRows(rows, PositionsSort.LargestPosition).map { it.symbol })
    }

    private fun event(symbol: String, report: LocalDate) = EarningsEventRecord(
        pre = PreReport(
            symbol = symbol,
            reportEpochDay = report.toEpochDay(),
            timing = ReportTiming.AfterClose,
            priceCents = 10_000L,
        ),
    )

    private fun lot(symbol: String, qty: Long, cost: Long) = PortfolioLot(symbol, qty, cost, null)

    private fun opp(
        symbol: String,
        nextEarningsEpoch: Long? = null,
        confidence: ConfidenceBand = ConfidenceBand.High,
    ) = OpportunityListRow(
        symbol = symbol,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        nextEarningsEpoch = nextEarningsEpoch,
        gapBps = 5_000,
        confidence = confidence,
        isWatched = false,
        compositeScore = 40,
        coverageCount = 3,
    )

    private fun lensSummary(symbol: String, status: QuantLensPrimaryStatus) = QuantLensRowSummary(
        symbol = symbol,
        fingerprint = "fixture",
        lensStates = listOf(
            QuantLensLensRowState(
                lensId = QuantLensLensId.EvidenceStrength,
                primaryStatus = status,
                reasonCodes = if (status == QuantLensPrimaryStatus.Available) {
                    listOf(QuantLensReasonCode.CompleteScenarioAnchors)
                } else {
                    emptyList()
                },
            ),
        ),
    )
}
