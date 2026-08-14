package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.engine.scoreReading
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ScoreFactor
import com.discountscreener.core.regime.RegimeCause
import com.discountscreener.core.regime.RegimeCauseEffect
import com.discountscreener.core.regime.RegimeScoreStatus
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Score-tab breakdown: one group per bucket, strongest term first.
 *
 * The engine already owns the points. This file only names the terms and puts them in reading
 * order. It does not invent a second score.
 */

internal data class ScoreFactorPalette(
    val fundamentals: Color,
    val technicals: Color,
    val forecast: Color,
    val market: Color,
    val positive: Color,
    val negative: Color,
)

/**
 * Bucket and sign inks that stay readable on both themes.
 *
 * Light inks sit on the pale purple surfaces. Dark inks sit on the teal surfaces. Each pair is
 * checked at 4.5:1 against the theme background, surface, and surfaceVariant.
 */
internal fun scoreFactorPalette(dark: Boolean): ScoreFactorPalette = if (dark) {
    ScoreFactorPalette(
        fundamentals = Color(0xFF6FE8F2),
        technicals = Color(0xFFF0C8FF),
        forecast = Color(0xFFFFD28A),
        market = Color(0xFFC5E8E3),
        positive = Color(0xFF7FE0A0),
        negative = Color(0xFFFFB8B0),
    )
} else {
    ScoreFactorPalette(
        fundamentals = Color(0xFF006B73),
        technicals = Color(0xFF5C2D91),
        forecast = Color(0xFF8A5500),
        market = Color(0xFF3D348B),
        positive = Color(0xFF146C2E),
        negative = Color(0xFFB3261E),
    )
}

@Composable
internal fun scoreFactorPalette(): ScoreFactorPalette = scoreFactorPalette(isSystemInDarkTheme())

internal fun ScoreFactorPalette.colorFor(title: String): Color = when (title) {
    "Fundamentals" -> fundamentals
    "Technicals" -> technicals
    "Forecast" -> forecast
    else -> market
}

internal data class ScoreFactorGroupUi(
    val title: String,
    val bucketScore: Int?,
    val lines: List<ScoreFactorLineUi>,
)

internal data class ScoreFactorLineUi(
    val label: String,
    val pointsText: String?,
    val token: String,
    val points: Int,
)

internal const val SCORE_FACTOR_CAPTION = "Points add inside F, T and Fc. Market shows fit weight."

internal const val SCORE_READING_LEGEND =
    "−100…+100 · Strong ≥50 · Good ≥15 · Neutral · Weak ≤−15 · Poor ≤−50"

internal fun scoreFactorGroupToken(title: String): String = when (title) {
    "Fundamentals" -> "F"
    "Technicals" -> "T"
    "Forecast" -> "Fc"
    else -> title
}

internal fun scoreFactorGroupTitle(group: ScoreFactorGroupUi, scoringModel: OpportunityScoringModel): String {
    var heading = scoreFactorGroupToken(group.title)
    var score = formatOpportunityBucket(group.bucketScore, scoringModel)
    var reading = group.bucketScore
        ?.takeIf { scoringModel != OpportunityScoringModel.Legacy }
        ?.let { scoreReading(it).name }
    return if (reading != null) "$heading $score · $reading" else "$heading $score"
}

internal fun scoreFactorGroups(row: OpportunityListRow): List<ScoreFactorGroupUi> {
    var groups = listOf(
        factorGroup("Fundamentals", row.fundamentalsScore, row.fundamentalsFactors, row.fundamentalsSignals),
        factorGroup("Technicals", row.technicalScore, row.technicalFactors, row.technicalSignals),
        factorGroup("Forecast", row.forecastScore, row.forecastFactors, row.forecastSignals),
    ).filter { group -> group.lines.isNotEmpty() }
    var showMarket = row.regimeStatus == RegimeScoreStatus.Included ||
        row.regimeCauses.isNotEmpty() ||
        row.regimeSignals.isNotEmpty()
    if (showMarket) {
        groups = groups + marketGroup(row)
    }
    return groups
}

internal fun scoreFactorCaption(groups: List<ScoreFactorGroupUi>): String? =
    SCORE_FACTOR_CAPTION.takeIf { groups.any { group -> group.lines.any { line -> line.pointsText != null } } }

internal fun scoreFactorLabel(key: String): String {
    var sectorAdjusted = key.endsWith("§")
    var stem = key.removeSuffix("§")
    var name = FACTOR_NAMES[stem] ?: stem
    return if (sectorAdjusted) "$name vs sector" else name
}

internal fun formatBucketPoints(points: Int): String = if (points > 0) "+$points" else "$points"

/**
 * Market causes are ranked by [RegimeCause.contributionBps], which is not a bucket point.
 *
 * Divide by 100 so the number sits on a readable scale. Take the sign from the effect, because
 * some fixtures store the magnitude as a positive value and put the direction on the effect.
 */
internal fun marketCausePoints(cause: RegimeCause): Int {
    var magnitude = abs((cause.contributionBps / 100.0).roundToInt())
    return when (cause.effect) {
        RegimeCauseEffect.Support -> magnitude
        RegimeCauseEffect.Risk -> -magnitude
        RegimeCauseEffect.Neutral -> 0
    }
}

private fun factorGroup(
    title: String,
    bucketScore: Int?,
    factors: List<ScoreFactor>,
    signals: List<String>,
): ScoreFactorGroupUi {
    var lines = if (factors.isNotEmpty()) {
        factors
            .sortedWith(compareByDescending<ScoreFactor> { abs(it.bucketPoints) }.thenBy { it.key })
            .map(::lineFromFactor)
    } else {
        signals
            .sortedWith(compareByDescending<String> { tokenStrength(it) }.thenBy { it })
            .map(::lineFromToken)
    }
    return ScoreFactorGroupUi(title = title, bucketScore = bucketScore, lines = lines)
}

private fun marketGroup(row: OpportunityListRow): ScoreFactorGroupUi {
    var causes = topRegimeCauses(row.regimeCauses)
    var lines = if (causes.isNotEmpty()) {
        causes.map { cause ->
            var points = marketCausePoints(cause)
            ScoreFactorLineUi(
                label = regimeFactorLabel(cause.factor),
                pointsText = points.takeIf { it != 0 }?.let(::formatBucketPoints),
                token = regimeCauseLabel(cause),
                points = points,
            )
        }
    } else {
        row.regimeSignals.map(::lineFromToken)
    }
    return ScoreFactorGroupUi(
        title = MARKET_DIMENSION_LABEL,
        bucketScore = row.regimeScore,
        lines = lines,
    )
}

private fun lineFromFactor(factor: ScoreFactor) = ScoreFactorLineUi(
    label = scoreFactorLineLabel(factor),
    pointsText = factor.bucketPoints.takeIf { it != 0 }?.let(::formatBucketPoints),
    token = factor.token,
    points = factor.bucketPoints,
)

internal fun scoreFactorLineLabel(factor: ScoreFactor): String {
    var name = scoreFactorLabel(factor.key)
    var rate = factor.inputBps?.let(::formatBpsPercent) ?: return name
    return "$name · $rate"
}

private fun lineFromToken(token: String) = ScoreFactorLineUi(
    label = token,
    pointsText = null,
    token = token,
    points = tokenStrength(token),
)

private fun tokenStrength(token: String): Int = when {
    token.endsWith("++") || token.endsWith("--") -> 2
    token.endsWith("+") || token.endsWith("-") -> 1
    else -> 0
}

private val FACTOR_NAMES = mapOf(
    "FCFy" to "FCF yield",
    "FCF" to "Free cash flow",
    "OCF" to "Operating cash flow",
    "ROE" to "Return on equity",
    "Growth" to "Quarter EPS YoY",
    "Pulse" to "Quarter EPS YoY",
    "Trend" to "Revenue 3–5y",
    "Pulse≠Trend" to "Pulse vs Trend",
    "D/E" to "Debt / equity",
    "Bal" to "Cash vs debt",
    "FwdPE" to "Forward P/E",
    "Mult" to "Multiples",
    "Conv" to "Cash conversion",
    "Shares" to "Share count",
    "Px/20" to "Price vs EMA 20",
    "20/50" to "EMA 20 / 50",
    "50/200" to "EMA 50 / 200",
    "Hist" to "MACD histogram",
    "MACD" to "MACD",
    "RSI" to "RSI",
    "Vol" to "Volume",
    "Val" to "Street target",
    "Rec" to "Recommendation",
    "Skew" to "Rating mix",
    "Cov" to "Analyst coverage",
    "Unc" to "Target range",
    "DcfUnc" to "Model range",
    "Fresh" to "Freshness",
    "Target" to "Target",
    "Liquidity" to "Liquidity",
)
