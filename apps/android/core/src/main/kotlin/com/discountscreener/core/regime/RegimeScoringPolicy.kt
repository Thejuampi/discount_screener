package com.discountscreener.core.regime

/**
 * The scoring policy the market reading implies — pure, no I/O. Ported from
 * `regime/scoring_policy.rs`.
 *
 * **Long only.** Rust carries a `ScoreSide` and a `mirror_for_short` that inverts the weights for
 * a short book. Android has no short model — `OpportunityScoringModel` is Legacy / Aggressive /
 * AggressiveV2 / AggressiveV3, with no `ShortV3` — so the mirror would be unreachable code whose
 * correctness nothing could check. It is left out rather than ported blind; if Android ever gains
 * a short model, port it then, against a test that can fail.
 */
data class RegimeScoringPolicy(
    val stance: String,
    val environmentBand: String,
    val primaryRegime: String,
    /** Relative weights. The scorer L1-normalizes them. */
    val wQuality: Double,
    val wLowBeta: Double,
    val wValue: Double,
    val wOversoldQuality: Double,
    val wAntiExtension: Double,
    val wTrend: Double,
    val wDefensive: Double,
    val wGrowth: Double,
    val wLiquidity: Double,
    /** Multiplier on the V3 beta haircut. 1.0 leaves it unchanged. */
    val betaHaircutMult: Double,
    /** 0..1, scales every feature contribution. Derived from regime confidence. */
    val strength: Double,
    val preferQuality: Boolean,
    val label: String,
) {
    companion object {
        /**
         * Below this the reading is not worth steering on, and [fromRegime] refuses rather than
         * returning a weak policy. A refusal surfaces as `Unavailable`; a weak policy would
         * silently move every score.
         */
        const val MIN_CONFIDENCE_BPS: Int = 3500

        /** Null when confidence is too low, or when the reading is Unknown on both axes. */
        fun fromRegime(regime: MarketRegime): RegimeScoringPolicy? {
            if (regime.globalConfidenceBps < MIN_CONFIDENCE_BPS) return null
            if (regime.environmentBand == "Unknown" && regime.primaryRegime == "Unknown") return null

            val base = baseForStance(regime.actionStance)
            var quality = base.wQuality
            var lowBeta = base.wLowBeta
            var oversoldQuality = base.wOversoldQuality
            var antiExtension = base.wAntiExtension
            var growth = base.wGrowth
            var betaHaircutMult = base.betaHaircutMult

            if (regime.preferQuality) {
                quality = minOf(quality + 0.25, 1.25)
                lowBeta = minOf(lowBeta + 0.1, 1.1)
            }

            // A rising tape on thin breadth is a narrow rally: chase growth less, demand more quality.
            regime.breadthAboveMa200Pct?.let { breadth ->
                if (breadth < 40.0 && regime.environmentBand in setOf("RiskOn", "StrongRiskOn")) {
                    antiExtension = minOf(antiExtension + 0.25, 1.2)
                    quality = minOf(quality + 0.15, 1.2)
                    growth *= 0.5
                }
            }

            regime.creditScore?.let { credit ->
                if (credit < -25) {
                    quality = minOf(quality + 0.2, 1.3)
                    lowBeta = minOf(lowBeta + 0.2, 1.2)
                    betaHaircutMult = minOf(betaHaircutMult * 1.15, 2.2)
                }
            }

            regime.cnnFearGreed?.let { fng ->
                if (fng <= 24) {
                    oversoldQuality = minOf(oversoldQuality + 0.2, 1.2)
                } else if (fng >= 76) {
                    antiExtension = minOf(antiExtension + 0.25, 1.25)
                    oversoldQuality *= 0.3
                }
            }

            val strength = clamp(regime.globalConfidenceBps.toDouble() / 10_000.0, 0.35, 1.0)
            val policy = base.copy(
                stance = regime.actionStance,
                environmentBand = regime.environmentBand,
                primaryRegime = regime.primaryRegime,
                wQuality = quality,
                wLowBeta = lowBeta,
                wOversoldQuality = oversoldQuality,
                wAntiExtension = antiExtension,
                wGrowth = growth,
                betaHaircutMult = betaHaircutMult,
                strength = strength,
                preferQuality = regime.preferQuality,
            )
            return policy.copy(
                label = "${policy.stance} · ${timingLabel(policy)} · strength ${
                    roundHalfAwayFromZero(strength * 100.0)
                }%",
            )
        }

        private fun timingLabel(policy: RegimeScoringPolicy): String = when {
            policy.wOversoldQuality >= 0.7 -> "mean-revert"
            policy.wAntiExtension >= 0.8 -> "anti-chase"
            policy.wTrend >= 0.7 -> "trend"
            else -> "balanced"
        }

        private fun baseForStance(stance: String): RegimeScoringPolicy {
            // quality, lowβ, value, oversold×q, anti-ext, trend, defensive, growth, liquidity, betaMult
            val w = when (stance) {
                "BloodInStreets" -> listOf(1.0, 0.9, 0.6, 1.0, 0.3, 0.1, 0.7, 0.0, 0.5, 1.8)
                "Washout" -> listOf(0.9, 0.7, 0.6, 0.9, 0.4, 0.2, 0.5, 0.1, 0.4, 1.5)
                "Accumulate", "SelectiveBuy" -> listOf(0.7, 0.5, 0.5, 0.7, 0.5, 0.3, 0.4, 0.2, 0.3, 1.2)
                "HealthyPullback" -> listOf(0.4, 0.2, 0.3, 0.6, 0.4, 0.7, 0.2, 0.4, 0.2, 0.9)
                "Deploy", "TrendDeploy" -> listOf(0.2, 0.0, 0.2, 0.2, 0.2, 1.0, 0.0, 0.6, 0.1, 0.7)
                "HoldTrim", "Reduce" -> listOf(0.5, 0.4, 0.3, 0.1, 0.8, 0.3, 0.3, 0.2, 0.3, 1.1)
                "Euphoria", "Distribute" -> listOf(0.4, 0.3, 0.2, 0.0, 1.0, 0.2, 0.2, 0.1, 0.2, 1.0)
                "Defend", "Denial", "UnstableBlowoff" -> listOf(1.0, 1.0, 0.4, 0.3, 0.6, 0.1, 0.8, 0.0, 0.6, 2.0)
                else -> listOf(0.3, 0.2, 0.3, 0.3, 0.4, 0.4, 0.2, 0.3, 0.2, 1.0)
            }
            return RegimeScoringPolicy(
                stance = stance,
                environmentBand = "",
                primaryRegime = "",
                wQuality = w[0],
                wLowBeta = w[1],
                wValue = w[2],
                wOversoldQuality = w[3],
                wAntiExtension = w[4],
                wTrend = w[5],
                wDefensive = w[6],
                wGrowth = w[7],
                wLiquidity = w[8],
                betaHaircutMult = w[9],
                strength = 1.0,
                preferQuality = false,
                label = "",
            )
        }
    }
}
