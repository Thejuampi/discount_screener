package com.discountscreener.core.backtest

import com.discountscreener.core.model.OpportunityScoringModel
import kotlin.test.Test
import kotlin.test.assertEquals

class V5OutcomeAblationsTest {

    @Test
    fun only_exact_symbol_and_timestamp_pairs_enter_the_comparison() {
        val result = V5OutcomeAblations.build(
            listOf(
                row("AAPL", V2),
                row("AAPL", V5),
                row("MSFT", V2),
                row("MSFT", V5, scoredAt = NOW + 1),
                row("NVDA", V5),
            ),
        )

        assertEquals(1, result.pairedRowCount)
        result.series.forEach { series ->
            assertEquals(listOf("AAPL" to NOW), series.scores.map { it.symbol to it.scoredAtEpochSeconds })
        }
    }

    @Test
    fun each_ablation_recombines_only_its_declared_inputs() {
        val result = V5OutcomeAblations.build(
            listOf(
                row(V2_SYMBOL, V2, technical = 30, forecast = 60, composite = 51),
                row(
                    V2_SYMBOL,
                    V5,
                    fundamentals = 90,
                    technical = -20,
                    forecast = 10,
                    regime = 40,
                    composite = 66,
                    compositeBase = 55,
                ),
            ),
        )

        assertEquals(
            mapOf(
                "paired-v2-composite" to 51,
                "paired-v5-composite" to 66,
                "v5-no-market" to 55,
                "v5-mean-no-bonus-no-beta" to 30,
                "v5-fundamentals-v2-shell" to 70,
            ),
            result.series.associate { series -> series.name to series.scores.single().score },
        )
    }

    @Test
    fun absent_buckets_remain_absent_instead_of_becoming_zero() {
        val result = V5OutcomeAblations.build(
            listOf(
                row(V2_SYMBOL, V2, technical = null, forecast = 60),
                row(
                    V2_SYMBOL,
                    V5,
                    fundamentals = 90,
                    technical = null,
                    forecast = 10,
                    regime = null,
                ),
            ),
        )

        assertEquals(
            50,
            result.series.single { it.name == "v5-mean-no-bonus-no-beta" }.scores.single().score,
        )
        assertEquals(
            80,
            result.series.single { it.name == "v5-fundamentals-v2-shell" }.scores.single().score,
        )
    }

    @Test
    fun mean_ablation_pins_half_point_rounding_on_both_sides_of_zero() {
        val result = V5OutcomeAblations.build(
            listOf(
                row("POS", V2),
                row("POS", V5, fundamentals = 0, technical = 1, forecast = null, regime = null),
                row("NEG", V2),
                row("NEG", V5, fundamentals = 0, technical = -1, forecast = null, regime = null),
            ),
        )

        assertEquals(
            mapOf("NEG" to 0, "POS" to 1),
            result.series.single { it.name == "v5-mean-no-bonus-no-beta" }
                .scores
                .associate { it.symbol to it.score },
        )
    }

    private fun row(
        symbol: String,
        model: OpportunityScoringModel,
        scoredAt: Long = NOW,
        fundamentals: Int? = 20,
        technical: Int? = 22,
        forecast: Int? = 19,
        regime: Int? = 18,
        composite: Int = 45,
        compositeBase: Int = 40,
    ) = ScoreAblationObservation(
        symbol = symbol,
        model = model,
        scoredAtEpochSeconds = scoredAt,
        fundamentalsScore = fundamentals,
        technicalScore = technical,
        forecastScore = forecast,
        regimeScore = regime,
        compositeScore = composite,
        compositeScoreBase = compositeBase,
    )

    private companion object {
        const val NOW = 1_700_000_000L
        const val V2_SYMBOL = "AAPL"
        val V2 = OpportunityScoringModel.AggressiveV2
        val V5 = OpportunityScoringModel.AggressiveV5
    }
}
