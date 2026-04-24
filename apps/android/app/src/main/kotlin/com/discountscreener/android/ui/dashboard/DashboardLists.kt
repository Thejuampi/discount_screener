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
import com.discountscreener.android.domain.model.RowExplanationKind
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.model.ValuationChange
import com.discountscreener.android.domain.model.ValuationChangeTier
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrackedList(rows: List<TrackedSymbolRow>, onAction: (DashboardAction) -> Unit) {
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
                        TrackedRowSignals(row)
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
    onAction: (DashboardAction) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(rows, key = { it.symbol }) { row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(DashboardAction.OpenDetail(row.symbol)) },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SymbolCompanyTitle(
                            symbol = row.symbol,
                            companyName = row.companyName,
                            modifier = Modifier.weight(1f),
                        )
                        ScoreBadge(row.compositeScore)
                    }
                    OpportunityRowSignals(row)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricToken("F ${formatOpportunityBucket(row.fundamentalsScore, scoringModel)}", fundamentalsMetricColor())
                        MetricToken("T ${formatOpportunityBucket(row.technicalScore, scoringModel)}", technicalMetricColor())
                        MetricToken("Fc ${formatOpportunityBucket(row.forecastScore, scoringModel)}", forecastMetricColor())
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpportunityRowSignals(row: OpportunityListRow) {
    val freshness = freshnessColors(row.freshness)
    val rankLabel = rankMovementLabel(row.rankMovement)
    val valuationLabel = valuationChangeLabel(row.valuationChange)
    val explanationLabel = explanationLabel(row.explanation)
    val freshnessTime = freshnessTimeLabel(row.freshness, row.freshnessAsOfEpochSeconds)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            label = freshnessLabel(row.freshness),
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
        freshnessTime?.let {
            MetricToken(it, MaterialTheme.colorScheme.outline)
        }
        if (row.isWatched) {
            ChangeBadge(
                label = "Watch",
                contentColor = MaterialTheme.colorScheme.primary,
                backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            )
        }
    }
}

@Composable
private fun SymbolCompanyTitle(
    symbol: String,
    companyName: String?,
    modifier: Modifier = Modifier,
) {
    val normalizedCompanyName = companyName?.trim().orEmpty()
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
private fun TrackedRowSignals(row: TrackedSymbolRow) {
    val rankLabel = rankMovementLabel(row.rankMovement)
    val valuationLabel = valuationChangeLabel(row.valuationChange)
    val freshness = freshnessColors(row.freshness)
    val explanationLabel = explanationLabel(row.explanation)
    val freshnessTime = freshnessTimeLabel(row.freshness, row.freshnessAsOfEpochSeconds)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            label = freshnessLabel(row.freshness),
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
        freshnessTime?.let {
            MetricToken(it, MaterialTheme.colorScheme.outline)
        }
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

private fun explanationLabel(explanation: RowExplanationKind?): String? = when (explanation) {
    RowExplanationKind.PriceMoved -> "Price moved"
    RowExplanationKind.TargetChanged -> "Target changed"
    RowExplanationKind.RelativeReRank -> "Relative re-rank"
    RowExplanationKind.CombinedMove -> "Combined move"
    RowExplanationKind.NoBaseline -> "No baseline"
    RowExplanationKind.NoMeaningfulChange -> "No meaningful change"
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
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
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

@Composable
private fun ScoreBadge(score: Int) {
    val (textColor, backgroundColor) = scoreBadgeColors(score)
    Text(
        text = "Score $score",
        color = textColor,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun MetricToken(
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
private fun fundamentalsMetricColor(): Color = Color(0xFF26C6DA)

@Composable
private fun technicalMetricColor(): Color = Color(0xFFAB47BC)

@Composable
private fun forecastMetricColor(): Color = Color(0xFFFFB300)

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

private fun scoreBadgeColors(score: Int): Pair<Color, Color> = when {
    score >= 12 -> Color(0xFF66BB6A) to Color(0x1F66BB6A)
    score >= 10 -> Color(0xFF29B6F6) to Color(0x1F29B6F6)
    score >= 8 -> Color(0xFFFFCA28) to Color(0x1FFFCA28)
    else -> Color(0xFFB0BEC5) to Color(0x1FB0BEC5)
}

private fun formatOpportunityBucket(score: Int?, scoringModel: OpportunityScoringModel): String = when {
    score == null -> "--"
    scoringModel == OpportunityScoringModel.Legacy -> "$score/5"
    else -> score.toString()
}
