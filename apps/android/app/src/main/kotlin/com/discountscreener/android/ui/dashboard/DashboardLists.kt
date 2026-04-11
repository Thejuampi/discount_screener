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
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityRow
import com.discountscreener.core.model.QualificationStatus

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SymbolCompanyTitle(
                                symbol = row.symbol,
                                companyName = row.companyName,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                trackedStateLabel(row),
                                color = trackedStateColor(row),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
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

private fun trackedStateLabel(row: TrackedSymbolRow): String = when (row.state) {
    TrackedRowState.Loading -> "Loading"
    TrackedRowState.Cached -> if (row.stale) "Cached" else "Ready"
    TrackedRowState.Live -> "Live"
    TrackedRowState.Failed -> "Failed"
}

private fun trackedStateColor(row: TrackedSymbolRow): Color = when (row.state) {
    TrackedRowState.Loading -> Color(0xFF8A6E00)
    TrackedRowState.Cached -> Color(0xFF6B7280)
    TrackedRowState.Live -> Color(0xFF156F3D)
    TrackedRowState.Failed -> Color(0xFFB3261E)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpportunityList(rows: List<OpportunityRow>, onAction: (DashboardAction) -> Unit) {
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricToken("F ${row.fundamentalsScore ?: "--"}", fundamentalsMetricColor())
                        MetricToken("T ${row.technicalScore ?: "--"}", technicalMetricColor())
                        MetricToken("Fc ${row.forecastScore ?: "--"}", forecastMetricColor())
                        MetricToken("Disc ${formatPct(row.gapBps)}", discountColor())
                        MetricToken("Upside ${formatPct(row.upsideBps)}", upsideColor(row.upsideBps))
                    }
                }
            }
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
