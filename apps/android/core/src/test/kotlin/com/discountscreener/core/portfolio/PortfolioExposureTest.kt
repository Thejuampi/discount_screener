package com.discountscreener.core.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortfolioExposureTest {
    @Test
    fun complete_book_uses_raw_values_for_weights() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 1_000L), lot("BBB", 10_000L, 3_000L)),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L), "BBB" to PortfolioQuote(30_000L)),
        )

        assertEquals(listOf(10_000L, 30_000L), result.rows.map { it.marketValueCents })
        assertEquals(listOf(2_500, 7_500), result.rows.map { it.weightBps })
        assertEquals(40_000L, result.summary.totalValueCents)
        assertEquals(Coverage.Complete, result.summary.valueCoverage)
    }

    @Test
    fun one_missing_quote_suppresses_all_weights_but_keeps_partial_total() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 1_000L), lot("BBB", 10_000L, 3_000L)),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L)),
        )

        assertEquals(10_000L, result.summary.totalValueCents)
        assertEquals(Coverage.Partial, result.summary.valueCoverage)
        assertEquals(listOf(null, null), result.rows.map { it.weightBps })
    }

    @Test
    fun no_quotes_returns_unavailable_total() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 1_000L)),
            quotes = emptyMap(),
        )

        assertEquals(Coverage.Unavailable, result.summary.valueCoverage)
        assertNull(result.summary.totalValueCents)
        assertNull(result.rows.single().marketValueCents)
    }

    @Test
    fun zero_and_negative_quotes_are_unavailable() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 1_000L), lot("BBB", 10_000L, 1_000L)),
            quotes = mapOf("AAA" to PortfolioQuote(0L), "BBB" to PortfolioQuote(-1L)),
        )

        assertEquals(listOf(null, null), result.rows.map { it.marketValueCents })
        assertEquals(listOf(null, null), result.rows.map { it.quoteIsCurrent })
        assertEquals(Coverage.Unavailable, result.summary.valueCoverage)
    }

    @Test
    fun overflowed_lot_does_not_poison_valid_subtotal() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", Long.MAX_VALUE, 0L), lot("BBB", 10_000L, 0L)),
            quotes = mapOf("AAA" to PortfolioQuote(Long.MAX_VALUE), "BBB" to PortfolioQuote(20_000L)),
        )

        assertEquals(20_000L, result.summary.totalValueCents)
        assertEquals(Coverage.Partial, result.summary.valueCoverage)
        assertEquals(listOf(null, null), result.rows.map { it.weightBps })
    }

    @Test
    fun individually_valid_values_can_overflow_the_aggregate() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 0L), lot("BBB", 10_000L, 0L)),
            quotes = mapOf("AAA" to PortfolioQuote(Long.MAX_VALUE), "BBB" to PortfolioQuote(Long.MAX_VALUE)),
        )

        assertEquals(listOf(Long.MAX_VALUE, Long.MAX_VALUE), result.rows.map { it.marketValueCents })
        assertEquals(Coverage.Complete, result.summary.valueCoverage)
        assertNull(result.summary.totalValueCents)
        assertEquals(listOf(null, null), result.rows.map { it.weightBps })
    }

    @Test
    fun zero_cost_keeps_pl_dollars_and_refuses_percentage() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 0L)),
            quotes = mapOf("AAA" to PortfolioQuote(1_000L)),
        )

        assertEquals(1_000L, result.rows.single().profitLossCents)
        assertNull(result.rows.single().profitLossBps)
    }

    @Test
    fun fractional_positive_value_remains_eligible() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 1L, 1L)),
            quotes = mapOf("AAA" to PortfolioQuote(1L)),
        )

        assertEquals(0L, result.rows.single().marketValueCents)
        assertEquals(0L, result.summary.totalValueCents)
    }

    @Test
    fun duplicate_symbols_keep_input_order_and_index() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 1_000L), lot("AAA", 20_000L, 1_000L)),
            quotes = mapOf("AAA" to PortfolioQuote(2_000L)),
        )

        assertEquals(listOf(0, 1), result.rows.map { it.inputIndex })
        assertEquals(listOf(2_000L, 4_000L), result.rows.map { it.marketValueCents })
    }

    @Test
    fun fractional_raw_products_aggregate_before_rounding() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 5_000L, 0L), lot("BBB", 5_000L, 0L)),
            quotes = mapOf("AAA" to PortfolioQuote(1L), "BBB" to PortfolioQuote(1L)),
        )

        assertEquals(1L, result.summary.totalValueCents)
    }

    @Test
    fun weight_rounding_handles_below_at_and_above_half_boundary() {
        val result = projectPortfolioExposure(
            lots = listOf(
                lot("A", 1L, 0L),
                lot("B", 1L, 0L),
                lot("C", 1L, 0L),
                lot("D", 1L, 0L),
            ),
            quotes = mapOf(
                "A" to PortfolioQuote(1L),
                "B" to PortfolioQuote(2L),
                "C" to PortfolioQuote(3L),
                "D" to PortfolioQuote(58L),
            ),
        )

        assertEquals(listOf(156, 313, 469, 9_063), result.rows.map { it.weightBps })
    }

    @Test
    fun overflow_suppresses_total_and_weights() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", Long.MAX_VALUE, Long.MAX_VALUE)),
            quotes = mapOf("AAA" to PortfolioQuote(Long.MAX_VALUE)),
        )

        assertNull(result.summary.totalValueCents)
        assertNull(result.rows.single().marketValueCents)
        assertNull(result.rows.single().weightBps)
    }

    @Test
    fun overflowed_public_market_value_cannot_support_paired_pl() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", Long.MAX_VALUE, 10_000L)),
            quotes = mapOf("AAA" to PortfolioQuote(10_001L)),
        )

        assertNull(result.rows.single().marketValueCents)
        assertNull(result.rows.single().profitLossCents)
        assertEquals(Coverage.Unavailable, result.summary.profitLossCoverage)
    }

    @Test
    fun percentage_overflow_is_unavailable() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 1L, 1L)),
            quotes = mapOf("AAA" to PortfolioQuote(Long.MAX_VALUE)),
        )

        assertNull(result.rows.single().profitLossBps)
    }

    @Test
    fun negative_cost_excludes_only_that_lot_from_paired_pl() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 5_000L), lot("BBB", 10_000L, -1L)),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L), "BBB" to PortfolioQuote(30_000L)),
        )

        assertEquals(5_000L, result.summary.profitLossCents)
        assertEquals(1, result.summary.profitLossEligibleLots)
        assertEquals(Coverage.Partial, result.summary.profitLossCoverage)
    }

    @Test
    fun paired_pl_reports_cents_and_basis_points() {
        val result = projectPortfolioExposure(
            lots = listOf(lot("AAA", 10_000L, 5_000L), lot("BBB", 10_000L, -1L)),
            quotes = mapOf("AAA" to PortfolioQuote(10_000L), "BBB" to PortfolioQuote(30_000L)),
        )

        assertEquals(5_000L, result.summary.profitLossCents)
        assertEquals(10_000, result.summary.profitLossBps)
    }

    @Test
    fun signed_half_cent_pl_rounds_away_from_zero() {
        val positive = listOf(4_999L, 5_000L, 5_001L).map { quantity ->
            projectPortfolioExposure(
                lots = listOf(lot("AAA", quantity, 1L)),
                quotes = mapOf("AAA" to PortfolioQuote(2L)),
            ).rows.single().profitLossCents
        }
        val negative = listOf(4_999L, 5_000L, 5_001L).map { quantity ->
            projectPortfolioExposure(
                lots = listOf(lot("AAA", quantity, 2L)),
                quotes = mapOf("AAA" to PortfolioQuote(1L)),
            ).rows.single().profitLossCents
        }

        assertEquals(listOf(0L, 1L, 1L), positive)
        assertEquals(listOf(0L, -1L, -1L), negative)
    }

    private fun lot(symbol: String, quantity: Long, cost: Long) =
        PortfolioLot(symbol, quantity, cost, null)
}
