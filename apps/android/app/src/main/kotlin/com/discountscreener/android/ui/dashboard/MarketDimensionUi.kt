package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.regime.MarketContextUnavailableReason
import com.discountscreener.core.regime.RegimeCause
import com.discountscreener.core.regime.RegimeCauseEffect
import com.discountscreener.core.regime.RegimeCauseFactor
import com.discountscreener.core.regime.RegimeScoreStatus
import kotlin.math.abs

/**
 * The one place the market dimension's vocabulary lives.
 *
 * The Opportunities header, the ticker header, the dense list row and the detail section all read
 * the same state; keeping the copy and the switch here is what stops one surface saying a name is
 * scored on four dimensions while another shows three.
 */

/** How the fourth bucket is labelled everywhere it appears. */
internal const val MARKET_DIMENSION_LABEL = "Market"

@Composable
internal fun marketMetricColor(): Color = MaterialTheme.colorScheme.primary

/**
 * The runtime on/off switch, beside the model chips in both headers.
 *
 * Rendered only for the model that has a fourth bucket. A switch that visibly does nothing on three
 * of the four models would be a worse answer than no switch, and hiding it keeps "why did nothing
 * change" from being a question the UI invites.
 */
@Composable
internal fun MarketDimensionSwitch(
    model: OpportunityScoringModel,
    enabled: Boolean,
    onAction: (DashboardAction) -> Unit,
) {
    if (model != OpportunityScoringModel.AggressiveV3) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = enabled,
            onClick = { onAction(DashboardAction.SetRegimeScoringEnabled(!enabled)) },
            label = { Text(if (enabled) "Market context on" else "Market context off") },
            modifier = Modifier.semantics { this.selected = enabled },
        )
    }
}

/**
 * Why this name carries no fourth bucket, in one line, or null when it does.
 *
 * [RegimeScoreStatus.NotApplicable] gets two lines rather than one because it has two genuinely
 * different causes — the wrong model, or an asset that is not a stock — and telling a user their ETF
 * needs a different scoring model would send them somewhere that cannot help.
 */
internal fun marketDimensionStatusLine(
    status: RegimeScoreStatus,
    reason: MarketContextUnavailableReason?,
    model: OpportunityScoringModel,
): String? = when (status) {
    RegimeScoreStatus.Included -> null
    RegimeScoreStatus.Disabled -> "Market context is switched off."
    RegimeScoreStatus.NotApplicable ->
        if (model == OpportunityScoringModel.AggressiveV3) {
            "Market context is fitted to stocks; this is not one."
        } else {
            "Market context applies to Aggressive V3 only."
        }
    RegimeScoreStatus.Unavailable -> when (reason) {
        MarketContextUnavailableReason.InsufficientAssetData ->
            "Not enough data on this name to fit it to the market."
        MarketContextUnavailableReason.MarketReadingUnavailable,
        MarketContextUnavailableReason.Unknown,
        null,
        -> "No market reading yet — waiting on one."
    }
}

/** `Base 35 · Market +4 · Final 39` — the impact is a subtraction, never a second stored number. */
internal fun marketDimensionImpactLine(row: OpportunityListRow): String {
    val impact = row.compositeScore - row.compositeScoreBase
    val signed = if (impact >= 0) "+$impact" else "$impact"
    return "Base ${row.compositeScoreBase} · $MARKET_DIMENSION_LABEL $signed · Final ${row.compositeScore}"
}

/** `+ quality`, `− extension`: what the fit rewarded or marked down, in plain words. */
internal fun regimeCauseLabel(cause: RegimeCause): String {
    val sign = when (cause.effect) {
        RegimeCauseEffect.Support -> "+"
        RegimeCauseEffect.Risk -> "−"
        RegimeCauseEffect.Neutral -> "·"
    }
    return "$sign ${regimeFactorLabel(cause.factor)}"
}

@Composable
internal fun regimeCauseColor(effect: RegimeCauseEffect): Color = when (effect) {
    RegimeCauseEffect.Support -> BullishChartColor
    RegimeCauseEffect.Risk -> BearishChartColor
    RegimeCauseEffect.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun regimeFactorLabel(factor: RegimeCauseFactor): String = when (factor) {
    RegimeCauseFactor.Quality -> "quality"
    RegimeCauseFactor.LowBeta -> "low beta"
    RegimeCauseFactor.Value -> "valuation"
    RegimeCauseFactor.OversoldQual -> "oversold quality"
    RegimeCauseFactor.Extension -> "extension"
    RegimeCauseFactor.Trend -> "trend"
    RegimeCauseFactor.Defensive -> "defensive posture"
    RegimeCauseFactor.Growth -> "growth"
    RegimeCauseFactor.Liquidity -> "liquidity"
    RegimeCauseFactor.GeneralFit -> "overall fit"
    RegimeCauseFactor.Neutral -> "no clear tilt"
}

/**
 * The causes worth showing, ranked by how much they moved the fit.
 *
 * Three, because the fit weighs eight or nine factors and a list of all of them is a table, not an
 * explanation — and because the ones below the top three are, by construction, the ones that
 * changed the answer least.
 */
internal fun topRegimeCauses(causes: List<RegimeCause>): List<RegimeCause> =
    causes.sortedByDescending { abs(it.contributionBps) }.take(TOP_CAUSE_COUNT)

private const val TOP_CAUSE_COUNT = 3
