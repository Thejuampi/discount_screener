package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.discountscreener.android.domain.model.ChangeDirection
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RankMovement
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowExplanationKind
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.model.ValuationChange
import com.discountscreener.android.domain.model.ValuationChangeTier
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensQualifier
import com.discountscreener.core.engine.DiscoveryScoreRow
import com.discountscreener.core.engine.DiscoveryTriage
import com.discountscreener.core.engine.DiscoveryUniverseEngine
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.regime.RegimeScoreStatus
import com.discountscreener.core.model.QualificationStatus
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrackedList(
    rows: List<TrackedSymbolRow>,
    quantLensChipsBySymbol: Map<String, List<QuantLensChipUi>> = emptyMap(),
    onAction: (DashboardAction) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(rows, key = { it.symbol }) { row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = row.marketPriceCents != null) {
                        onAction(DashboardAction.OpenDetail(row.symbol))
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SymbolCompanyTitle(
                            symbol = row.symbol,
                            companyName = row.companyName,
                        )
                        TrackedRowSignals(row, quantLensChipsBySymbol[row.symbol].orEmpty())
                        TrackedRowMetrics(row)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CandidateList(rows: List<CandidateRow>, onAction: (DashboardAction) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(rows, key = { it.symbol }) { row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(DashboardAction.OpenDetail(row.symbol)) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SymbolCompanyTitle(symbol = row.symbol, companyName = row.companyName)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricToken("Disc ${formatPct(row.gapBps)}", discountColor())
                            MetricToken("Upside ${formatPct(row.upsideBps)}", upsideColor(row.upsideBps))
                            MetricToken("Conf ${row.confidence.name.lowercase()}", confidenceColor(row.confidence))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpportunityList(
    rows: List<OpportunityListRow>,
    scoringModel: OpportunityScoringModel,
    quantLensChipsBySymbol: Map<String, List<QuantLensChipUi>> = emptyMap(),
    onAction: (DashboardAction) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(rows, key = { _, row -> row.symbol }) { index, row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(DashboardAction.OpenDetail(row.symbol)) },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RankOrdinal(index = index)
                        SymbolCompanyTitle(
                            symbol = row.symbol,
                            companyName = row.companyName,
                            modifier = Modifier.weight(1f),
                        )
                        ScoreBadge(score = row.compositeScore, scoringModel = scoringModel)
                    }
                    OpportunityRowSignals(row, quantLensChipsBySymbol[row.symbol].orEmpty())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricToken("F ${formatOpportunityBucket(row.fundamentalsScore, scoringModel)}", fundamentalsMetricColor())
                        MetricToken("T ${formatOpportunityBucket(row.technicalScore, scoringModel)}", technicalMetricColor())
                        MetricToken("Fc ${formatOpportunityBucket(row.forecastScore, scoringModel)}", forecastMetricColor())
                        // Only when it is actually in the composite. The dense row has no space to
                        // say why a dimension is absent, and a token reading "--" would look like a
                        // measurement that came back empty rather than one that was never taken.
                        if (row.regimeStatus == RegimeScoreStatus.Included) {
                            MetricToken(
                                "$MARKET_DIMENSION_LABEL ${formatOpportunityBucket(row.regimeScore, scoringModel)}",
                                marketMetricColor(),
                            )
                        }
                        MetricToken("Disc ${formatPct(row.gapBps)}", discountColor())
                        MetricToken("Upside ${formatPct(row.upsideBps)}", upsideColor(row.upsideBps))
                        MetricToken("Conf ${row.confidence.name.lowercase()}", confidenceColor(row.confidence))
                    }
                    row.providerIssue?.let { issue ->
                        Text(
                            text = issue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Discovery ranked list — same visual density and triage vocabulary as Opportunities.
 * Controls live outside this list so rows own the viewport.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryList(
    rows: List<DiscoveryScoreRow>,
    scoringModel: OpportunityScoringModel,
    onAction: (DashboardAction) -> Unit,
    rankOffset: Int = 0,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.symbol }) { index, row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(DashboardAction.OpenDetail(row.symbol)) },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RankOrdinal(index = index, rankOffset = rankOffset)
                        SymbolCompanyTitle(
                            symbol = row.symbol,
                            companyName = row.companyName,
                            modifier = Modifier.weight(1f),
                        )
                        ScoreBadge(score = row.compositeScore, scoringModel = scoringModel)
                    }
                    DiscoveryRowSignals(row = row, scoringModel = scoringModel)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricToken(
                            "F ${formatOpportunityBucket(row.fundamentalsScore, scoringModel)}",
                            fundamentalsMetricColor(),
                        )
                        MetricToken(
                            "T ${formatOpportunityBucket(row.technicalScore, scoringModel)}",
                            technicalMetricColor(),
                        )
                        MetricToken(
                            "Fc ${formatOpportunityBucket(row.forecastScore, scoringModel)}",
                            forecastMetricColor(),
                        )
                        row.gapBps?.let { MetricToken("Disc ${formatPct(it)}", discountColor()) }
                        row.upsideBps?.let { MetricToken("Upside ${formatPct(it)}", upsideColor(it)) }
                        row.marketPriceCents?.let {
                            MetricToken("Price ${money(it)}", MaterialTheme.colorScheme.onSurface)
                        }
                        parseDiscoveryConfidence(row.confidence)?.let { conf ->
                            MetricToken("Conf ${conf.name.lowercase()}", confidenceColor(conf))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryRowSignals(
    row: DiscoveryScoreRow,
    scoringModel: OpportunityScoringModel,
) {
    val triage = DiscoveryUniverseEngine.triage(row.compositeScore, scoringModel)
    val decisionState = when (triage) {
        DiscoveryTriage.Act -> RowDecisionState.Act
        DiscoveryTriage.Watch -> RowDecisionState.Watch
        DiscoveryTriage.Avoid -> RowDecisionState.Avoid
    }
    val decisionLabel = decisionStateLabel(decisionState)
    val decisionColors = decisionStateColors(decisionState)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (decisionLabel != null) {
            ChangeBadge(
                label = decisionLabel,
                contentColor = decisionColors.first,
                backgroundColor = decisionColors.second,
            )
        }
        ChangeBadge(
            label = "Discovery",
            contentColor = MaterialTheme.colorScheme.tertiary,
            backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
        )
        MetricToken(
            text = freshnessTimeLabel(
                freshness = RowFreshness.Updated,
                freshnessAsOfEpochSeconds = row.scoredAtEpochSeconds,
            ) ?: "scored",
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

internal fun parseDiscoveryConfidence(raw: String?): ConfidenceBand? =
    raw?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
        ConfidenceBand.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

/**
 * Everything that qualifies a row, on one flowing strip: what to do about it, what changed, how
 * fresh it is, and what the lens read.
 *
 * The lens chips used to own a line of their own, which on a phone was a line carrying one chip and
 * two thirds empty space — about 60px of a 270px card, spent on nothing. Flowed together they land
 * on one line for a typical row and wrap to two only when there is genuinely that much to say,
 * which is what the second line was always meant to be for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpportunityRowSignals(row: OpportunityListRow, lensChips: List<QuantLensChipUi>) {
    val freshness = freshnessColors(row.freshness)
    val rankLabel = rankMovementLabel(row.rankMovement)
    val valuationLabel = valuationChangeLabel(row.valuationChange)
    val decisionLabel = decisionStateLabel(row.decisionState)
    val explanationLabel = explanationLabel(row.explanation)
    val freshnessTime = freshnessTimeLabel(row.freshness, row.freshnessAsOfEpochSeconds)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        decisionLabel?.let {
            val colors = decisionStateColors(row.decisionState)
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        valuationLabel?.let {
            val colors = valuationChangeColors(row.valuationChange)
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        rankLabel?.let {
            val colors = rankMovementColors(row.rankMovement)
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        explanationLabel?.let {
            val colors = explanationColors(row.explanation)
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        // "Updated" and "now" were two tokens saying one thing, with a gap and a pill border between
        // them. One badge reads the same and leaves room for the lens chips on the same line.
        ChangeBadge(
            label = listOfNotNull(freshnessLabel(row.freshness), freshnessTime).joinToString(" "),
            contentColor = freshness.first,
            backgroundColor = freshness.second,
        )
        row.trustNote?.let {
            val colors = trustNoteColors()
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        if (row.isWatched) {
            ChangeBadge(
                label = "Watchlist",
                contentColor = MaterialTheme.colorScheme.primary,
                backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            )
        }
        LensChips(lensChips)
    }
}

/**
 * The lens chips, emitted into whatever strip is already flowing rather than a strip of their own.
 *
 * Call this from inside a `FlowRow`: the chips become that row's children, so they wrap with the
 * badges around them instead of reserving a line whether or not they need one.
 */
@Composable
private fun LensChips(chips: List<QuantLensChipUi>) {
    val visible = if (chips.isEmpty()) {
        listOf(QuantLensChipUi(null, "Lens loading", QuantLensQualifier.Unknown))
    } else {
        chips.take(3)
    }
    visible.forEach { chip -> SignalChip(chip) }
}

@Composable
private fun SymbolCompanyTitle(
    symbol: String,
    companyName: String?,
    modifier: Modifier = Modifier,
) {
    val normalizedCompanyName = companyName
        ?.trim()
        .orEmpty()
        .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .orEmpty()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = symbol,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (normalizedCompanyName.isNotBlank() && !normalizedCompanyName.equals(symbol, ignoreCase = true)) {
            Text(
                text = normalizedCompanyName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackedRowSignals(row: TrackedSymbolRow, lensChips: List<QuantLensChipUi>) {
    val rankLabel = rankMovementLabel(row.rankMovement)
    val valuationLabel = valuationChangeLabel(row.valuationChange)
    val freshness = freshnessColors(row.freshness)
    val decisionLabel = decisionStateLabel(row.decisionState)
    val explanationLabel = explanationLabel(row.explanation)
    val freshnessTime = freshnessTimeLabel(row.freshness, row.freshnessAsOfEpochSeconds)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        decisionLabel?.let {
            val colors = decisionStateColors(row.decisionState)
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        valuationLabel?.let {
            val colors = valuationChangeColors(row.valuationChange)
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        rankLabel?.let {
            val colors = rankMovementColors(row.rankMovement)
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        explanationLabel?.let {
            val colors = explanationColors(row.explanation)
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        ChangeBadge(
            label = listOfNotNull(freshnessLabel(row.freshness), freshnessTime).joinToString(" "),
            contentColor = freshness.first,
            backgroundColor = freshness.second,
        )
        row.trustNote?.let {
            val colors = trustNoteColors()
            ChangeBadge(
                label = it,
                contentColor = colors.first,
                backgroundColor = colors.second,
            )
        }
        LensChips(lensChips)
    }
}

private fun freshnessLabel(freshness: RowFreshness): String = when (freshness) {
    RowFreshness.Loading -> "Loading"
    RowFreshness.Restored -> "Restored"
    RowFreshness.Updating -> "Updating"
    RowFreshness.Updated -> "Updated"
    RowFreshness.Stale -> "Stale"
    RowFreshness.Issue -> "Issue"
}

/**
 * Why this row's numbers moved, or null when there is nothing to say.
 *
 * [RowExplanationKind.NoMeaningfulChange] is the `else` of the derivation — a baseline exists and
 * neither price, target nor rank moved — and it was the widest badge on the strip, present on nearly
 * every row of a settled screen, spending about a third of the line to report that nothing happened.
 * It reads the same as its own absence: every other kind renders a label, so no explanation badge
 * means exactly this one. [RowExplanationKind.NoBaseline] stays visible, because "nothing moved" and
 * "there is nothing to compare against" are different claims and only one of them is reassuring.
 */
private fun explanationLabel(explanation: RowExplanationKind?): String? = when (explanation) {
    RowExplanationKind.PriceMoved -> "Price moved"
    RowExplanationKind.TargetChanged -> "Target changed"
    RowExplanationKind.RelativeReRank -> "Relative re-rank"
    RowExplanationKind.CombinedMove -> "Combined move"
    RowExplanationKind.NoBaseline -> "No baseline"
    RowExplanationKind.NoMeaningfulChange, null -> null
}

internal fun decisionStateLabel(decisionState: RowDecisionState?): String? = when (decisionState) {
    RowDecisionState.Act -> "Act"
    RowDecisionState.Watch -> "Watch"
    RowDecisionState.Avoid -> "Avoid"
    null -> null
}

@Composable
private fun ChangeBadge(
    label: String,
    contentColor: Color,
    backgroundColor: Color,
) {
    Text(
        text = label,
        color = contentColor,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun freshnessColors(freshness: RowFreshness): Pair<Color, Color> = when (freshness) {
    RowFreshness.Loading -> Color(0xFF8A6E00) to Color(0xFF8A6E00).copy(alpha = 0.14f)
    RowFreshness.Restored -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    RowFreshness.Updating -> Color(0xFF0F766E) to Color(0xFF0F766E).copy(alpha = 0.14f)
    RowFreshness.Updated -> Color(0xFF156F3D) to Color(0xFF156F3D).copy(alpha = 0.14f)
    RowFreshness.Stale -> Color(0xFF8A6E00) to Color(0xFF8A6E00).copy(alpha = 0.14f)
    RowFreshness.Issue -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
}

@Composable
private fun explanationColors(explanation: RowExplanationKind?): Pair<Color, Color> = when (explanation) {
    RowExplanationKind.PriceMoved ->
        MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
    RowExplanationKind.TargetChanged ->
        Color(0xFF6D4C41) to Color(0xFF6D4C41).copy(alpha = 0.12f)
    RowExplanationKind.RelativeReRank ->
        MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
    RowExplanationKind.CombinedMove ->
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    RowExplanationKind.NoBaseline,
    RowExplanationKind.NoMeaningfulChange,
    null -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
}

@Composable
private fun decisionStateColors(decisionState: RowDecisionState?): Pair<Color, Color> = when (decisionState) {
    RowDecisionState.Act -> BullishChartColor to BullishChartColor.copy(alpha = 0.16f)
    RowDecisionState.Watch -> Color(0xFF8A6E00) to Color(0xFF8A6E00).copy(alpha = 0.14f)
    RowDecisionState.Avoid -> BearishChartColor to BearishChartColor.copy(alpha = 0.14f)
    null -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
}

@Composable
private fun trustNoteColors(): Pair<Color, Color> =
    MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)

@Composable
private fun rankMovementColors(movement: RankMovement?): Pair<Color, Color> {
    movement ?: return MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (movement.direction == ChangeDirection.Up) BullishChartColor else BearishChartColor
    return contentColor to contentColor.copy(alpha = 0.14f)
}

@Composable
private fun valuationChangeColors(change: ValuationChange?): Pair<Color, Color> {
    change ?: return MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surfaceVariant
    val baseColor = when (change.direction) {
        ChangeDirection.Up -> if (change.tier == ValuationChangeTier.Major) BullishChartColor else Color(0xFF2E7D32)
        ChangeDirection.Down -> if (change.tier == ValuationChangeTier.Major) BearishChartColor else Color(0xFFC62828)
    }
    val alpha = if (change.tier == ValuationChangeTier.Major) 0.22f else 0.12f
    return baseColor to baseColor.copy(alpha = alpha)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackedRowMetrics(row: TrackedSymbolRow) {
    row.providerIssue?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    if (row.marketPriceCents == null || row.intrinsicValueCents == null || row.gapBps == null || row.upsideBps == null) {
        Text(
            text = "Waiting for real Yahoo data",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricToken("Price ${money(row.marketPriceCents)}", MaterialTheme.colorScheme.onSurface)
        MetricToken("Fair ${money(row.intrinsicValueCents)}", fairMetricColor())
        MetricToken("Disc ${formatPct(row.gapBps)}", discountColor())
        MetricToken("Upside ${formatPct(row.upsideBps)}", upsideColor(row.upsideBps))
        MetricToken(row.qualification?.name?.lowercase() ?: "unknown", qualificationColor(row.qualification))
        MetricToken(row.confidence?.name?.lowercase() ?: "unknown", confidenceColor(row.confidence))
        if (row.stale) {
            MetricToken("stale", MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * The `#N` placing on a ranked row. Opportunities and Discovery share this so the two lists cannot
 * drift into rendering their ordinals differently.
 */
@Composable
internal fun RankOrdinal(index: Int, rankOffset: Int = 0) {
    Text(
        text = "#${rankOffset + index + 1}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(end = 6.dp),
    )
}

@Composable
internal fun ScoreBadge(score: Int, scoringModel: OpportunityScoringModel) {
    val (textColor, backgroundColor) = scoreBadgeColors(score, scoringModel)
    Text(
        text = "Score $score",
        color = textColor,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
internal fun MetricToken(
    text: String,
    color: Color,
    horizontalPadding: Dp = 0.dp,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = horizontalPadding),
    )
}

internal fun freshnessTimeLabel(
    freshness: RowFreshness,
    freshnessAsOfEpochSeconds: Long?,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
): String? {
    val asOf = freshnessAsOfEpochSeconds ?: return null
    val secondsAgo = max(0L, nowEpochSeconds - asOf)
    val relative = when {
        secondsAgo < 60L -> "now"
        secondsAgo < 3600L -> "${secondsAgo / 60L}m ago"
        secondsAgo < 86_400L -> "${secondsAgo / 3600L}h ago"
        else -> "${secondsAgo / 86_400L}d ago"
    }
    return when (freshness) {
        RowFreshness.Restored -> "saved $relative"
        else -> relative
    }
}

@Composable
internal fun fundamentalsMetricColor(): Color = Color(0xFF26C6DA)

@Composable
internal fun technicalMetricColor(): Color = Color(0xFFAB47BC)

@Composable
internal fun forecastMetricColor(): Color = Color(0xFFFFB300)

@Composable
private fun upsideColor(upsideBps: Int): Color = if (upsideBps >= 0) BullishChartColor else BearishChartColor

@Composable
private fun discountColor(): Color = MaterialTheme.colorScheme.tertiary

@Composable
private fun fairMetricColor(): Color = MaterialTheme.colorScheme.onPrimaryContainer

@Composable
private fun confidenceColor(confidence: ConfidenceBand?): Color = when (confidence) {
    ConfidenceBand.High -> Color(0xFF42A5F5)
    ConfidenceBand.Provisional -> Color(0xFFFFB300)
    ConfidenceBand.Low -> MaterialTheme.colorScheme.outline
    null -> MaterialTheme.colorScheme.outline
}

@Composable
private fun qualificationColor(qualification: QualificationStatus?): Color = when (qualification) {
    QualificationStatus.Qualified -> BullishChartColor
    QualificationStatus.GapTooSmall -> Color(0xFFFFB300)
    QualificationStatus.Unprofitable -> BearishChartColor
    null -> MaterialTheme.colorScheme.outline
}

private fun scoreBadgeColors(score: Int, scoringModel: OpportunityScoringModel): Pair<Color, Color> {
    val strong = OpportunityEngine.actAtOrAboveScore(scoringModel)
    val mid = when (scoringModel) {
        OpportunityScoringModel.Legacy,
        OpportunityScoringModel.Aggressive,
        -> 10
        OpportunityScoringModel.AggressiveV2,
        OpportunityScoringModel.AggressiveV3,
        -> 15
    }
    val weak = OpportunityEngine.avoidBelowScore(scoringModel)
    return when {
        score >= strong -> Color(0xFF66BB6A) to Color(0x1F66BB6A)
        score >= mid -> Color(0xFF29B6F6) to Color(0x1F29B6F6)
        score >= weak -> Color(0xFFFFCA28) to Color(0x1FFFCA28)
        else -> Color(0xFFB0BEC5) to Color(0x1FB0BEC5)
    }
}

internal fun formatOpportunityBucket(score: Int?, scoringModel: OpportunityScoringModel): String = when {
    score == null -> "--"
    scoringModel == OpportunityScoringModel.Legacy -> "$score/5"
    else -> score.toString()
}
