package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.Locale
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.engine.scoreReading
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ScoreFactor
import com.discountscreener.core.model.ScoreFactorComparison
import com.discountscreener.core.model.ScoreFactorValueKind
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
    /** The engine's own name for the term, before [scoreFactorLabel] turns it into English. */
    val key: String,
    val label: String,
    val pointsText: String?,
    val token: String,
    val points: Int,
)

internal const val SCORE_FACTOR_CAPTION =
    "Points add inside Fundamentals, Technicals and Forecast. Market shows fit weight."

/** The engine keys the notes below are attached to. */
internal const val PULSE_KEY = "Pulse"
internal const val PULSE_TREND_DIVERGENCE_KEY = "Pulse≠Trend"

/**
 * What `Pulse` is, in the source's own terms.
 *
 * The number is Yahoo's `financialData.earningsGrowth`. Yahoo does not say whether it is reported
 * or adjusted earnings, and the difference is large: a single impairment year prints here as a
 * collapse in growth and costs the symbol real points. Naming the hole is the honest half of this;
 * closing it needs the XBRL series that does not exist in this repo yet.
 */
internal const val PULSE_BASIS_NOTE =
    "Quarter EPS YoY is Yahoo's own figure: one quarter against the same quarter a year before. " +
        "Yahoo does not declare the accounting basis, so a one-off charge reads here as lost growth."

/**
 * The engine has flagged this disagreement since V4 shipped and nothing showed it.
 *
 * A quarter that falls while a five-year revenue line climbs is the signature of a charge, a
 * currency move, or a cycle — three different things, none of them visible in one number. The row
 * cannot say which, so it says the two halves disagree and leaves the reading to the person.
 */
internal const val PULSE_TREND_DIVERGENCE_NOTE =
    "Pulse and Trend point opposite ways. Each holds half the growth weight, so the two cancel " +
        "instead of one deciding the bucket."

internal const val CYCLE_PEAK_KEY = "CyclePeak"

/**
 * The size of the claim, said on screen and not only in the source.
 *
 * A mid-cycle earnings figure needs seven to ten years of an operating line. This app holds five
 * annual points and no operating income at all, so the mark says where the latest year sits inside
 * the only history there is. A reader who takes it for a cycle-adjusted valuation would be reading
 * more into it than it measures.
 */
internal const val CYCLE_PEAK_NOTE =
    "Earnings sit at the top of the five years on file, in an industry that moves with a " +
        "commodity cycle. Five annual points cannot place a cycle; this marks the peak inside " +
        "the window, not a mid-cycle level."

internal const val EARNINGS_CHARGE_KEY = "Charges"
internal const val FCF_FINANCIAL_KEY = "FCFy∅ financial"
internal const val FCF_UNKNOWN_KEY = "FCFy∅ unknown"
internal const val FCF_INELIGIBLE_KEY = "FCFy∅ ineligible"
internal const val OCF_BAND_UNMEASURED_KEY = "OCFy∅ unmeasured"
internal const val FUND_COVERAGE_GAP_KEY = "Fund∅ coverage"

/**
 * What the mark is, and what it is not.
 *
 * The charge is read from the SEC filing, so a name with no filing on file is never marked. Saying
 * "one-off" would be a claim this app cannot make: whether a write-down repeats is a judgment about
 * the business. The wording states the measurement, which is the size against the year it landed in.
 */
internal const val EARNINGS_CHARGE_NOTE =
    "The last filed year carries impairment or restructuring worth 15% or more of its operating " +
        "income, so growth read across it is partly the charge. Whether such a charge repeats is " +
        "not measured here."

internal const val FCF_FINANCIAL_NOTE =
    "Industrial free cash flow is the wrong cash for financial services. Deposits, float, and " +
        "premium cash are not owner earnings, so this row does not take an FCF yield."

internal const val FCF_UNKNOWN_NOTE =
    "The sector or industry is unknown, so this row does not take an industrial FCF yield."

internal const val FCF_INELIGIBLE_NOTE =
    "This asset class does not take an industrial FCF yield (ETF, fund, REIT, or similar)."

internal const val OCF_BAND_UNMEASURED_NOTE =
    "The OCF yield band (0% to 10%) is a stated estimate. It is not a measured OCF/FCF ratio " +
        "for this universe."

internal const val FUND_COVERAGE_GAP_NOTE =
    "Some fundamentals inputs are missing. Empty slots stay in the divisor, so this score sits " +
        "closer to zero than a complete file. New inputs can raise or lower the number."

internal const val SCORE_READING_LEGEND =
    "−100…+100 · Strong ≥50 · Good ≥15 · Neutral · Weak ≤−15 · Poor ≤−50"

internal fun scoreFactorGroupTitle(group: ScoreFactorGroupUi, scoringModel: OpportunityScoringModel): String {
    var heading = group.title
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

/**
 * The sentences a term needs before its number means anything, in reading order.
 *
 * Matched on [ScoreFactorLineUi.key] and not on the token, because `Pulse≠Trend` starts with
 * `Pulse` and a prefix match would print the basis note for a row that never scored a quarter.
 */
internal fun scoreFactorNotes(groups: List<ScoreFactorGroupUi>): List<String> {
    var keys = groups.flatMap { group -> group.lines.map { line -> line.key } }.toSet()
    return listOfNotNull(
        PULSE_BASIS_NOTE.takeIf { PULSE_KEY in keys },
        PULSE_TREND_DIVERGENCE_NOTE.takeIf { PULSE_TREND_DIVERGENCE_KEY in keys },
        CYCLE_PEAK_NOTE.takeIf { CYCLE_PEAK_KEY in keys },
        EARNINGS_CHARGE_NOTE.takeIf { EARNINGS_CHARGE_KEY in keys },
        FCF_FINANCIAL_NOTE.takeIf { FCF_FINANCIAL_KEY in keys },
        FCF_UNKNOWN_NOTE.takeIf { FCF_UNKNOWN_KEY in keys },
        FCF_INELIGIBLE_NOTE.takeIf { FCF_INELIGIBLE_KEY in keys },
        OCF_BAND_UNMEASURED_NOTE.takeIf { OCF_BAND_UNMEASURED_KEY in keys },
        FUND_COVERAGE_GAP_NOTE.takeIf { FUND_COVERAGE_GAP_KEY in keys },
    )
}

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
                key = cause.factor.name,
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
    key = factor.key,
    label = scoreFactorLineLabel(factor),
    pointsText = factor.bucketPoints.takeIf { it != 0 }?.let(::formatBucketPoints),
    token = factor.token,
    points = factor.bucketPoints,
)

internal fun scoreFactorLineLabel(factor: ScoreFactor): String {
    var name = scoreFactorLabel(factor.key)
    var comparison = formatScoreFactorComparisons(factor.comparisons)
    if (comparison != null) return "$name · $comparison"
    var rate = factor.inputBps?.let(::formatBpsPercent) ?: return name
    return "$name · $rate"
}

internal fun formatScoreFactorComparisons(comparisons: List<ScoreFactorComparison>): String? {
    if (comparisons.isEmpty()) return null
    var rendered = comparisons.mapNotNull { comparison ->
        var pair = formatComparisonPair(comparison)
        var metric = comparison.metric
        var body = when {
            pair != null && !metric.isNullOrEmpty() -> "$metric $pair"
            pair != null -> pair
            !metric.isNullOrEmpty() -> metric
            else -> ""
        }
        var why = comparison.why
        when {
            body.isNotEmpty() && !why.isNullOrEmpty() -> "$body · $why"
            body.isNotEmpty() -> body
            !why.isNullOrEmpty() -> why
            else -> null
        }
    }
    if (rendered.isEmpty()) return null
    return rendered.joinToString(" · ")
}

private fun formatComparisonPair(comparison: ScoreFactorComparison): String? {
    if (comparison.kind == ScoreFactorValueKind.Dollars) {
        var observed = comparison.observedDollars
        var reference = comparison.referenceDollars
        return when {
            observed != null && reference != null -> "${formatDollars(observed)} vs ${formatDollars(reference)}"
            observed != null -> formatDollars(observed)
            reference != null -> formatDollars(reference)
            else -> null
        }
    }
    var observed = formatFactorValue(comparison.observed, comparison.kind)
    var reference = comparison.reference?.let { formatFactorValue(it, comparison.kind) }
    if (reference != null) return "$observed vs $reference"
    var low = comparison.referenceLow
    var high = comparison.referenceHigh
    if (low != null && high != null) {
        return "$observed vs ${formatFactorValue(low, comparison.kind)}–${formatFactorValue(high, comparison.kind)}"
    }
    return observed
}

private fun formatFactorValue(value: Int, kind: ScoreFactorValueKind): String = when (kind) {
    ScoreFactorValueKind.Percent -> String.format(Locale.US, "%.1f%%", value / 100.0)
    ScoreFactorValueKind.Multiple -> String.format(Locale.US, "%.1f", value / 100.0)
    ScoreFactorValueKind.Dollars -> formatDollars(value.toLong())
}

private fun formatDollars(value: Long): String {
    var sign = if (value < 0) "-" else ""
    var amount = if (value == Long.MIN_VALUE) Long.MAX_VALUE else abs(value)
    return when {
        amount >= 1_000_000_000L ->
            sign + "$" + String.format(Locale.US, "%.1fB", amount / 1_000_000_000.0)
        amount >= 1_000_000L ->
            sign + "$" + String.format(Locale.US, "%.0fM", amount / 1_000_000.0)
        else -> sign + "$" + String.format(Locale.US, "%,d", amount)
    }
}

// A signal token carries its strength in trailing `+`/`-` characters; the key is what is left.
private fun lineFromToken(token: String) = ScoreFactorLineUi(
    key = token.trimEnd('+', '-'),
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
    "FCFy∅ financial" to "FCF yield skipped (financials)",
    "FCFy∅ unknown" to "FCF yield skipped (unknown class)",
    "FCFy∅ ineligible" to "FCF yield skipped (not eligible)",
    "OCFy∅ unmeasured" to "OCF yield band unmeasured",
    "Fund∅ coverage" to "Score uses incomplete fundamentals",
    "FCF" to "Free cash flow",
    "OCF" to "Operating cash flow",
    "ROE" to "Return on equity",
    "Growth" to "Quarter EPS YoY",
    "Pulse" to "Quarter EPS YoY",
    "Trend" to "Revenue 3–5y",
    "Pulse≠Trend" to "Pulse and Trend disagree",
    "ND/EBITDA" to "Net debt / EBITDA",
    "D/E" to "Debt / equity",
    "Bal" to "Cash vs debt",
    "FwdPE" to "Forward P/E",
    "Mult" to "Multiples",
    "Conv" to "Cash conversion",
    "Shares" to "Share count",
    "CyclePeak" to "Earnings at a cycle peak",
    "Charges" to "Impairment or restructuring charge",
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
