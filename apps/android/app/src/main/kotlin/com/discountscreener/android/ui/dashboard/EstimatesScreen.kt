package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discountscreener.android.domain.model.DashboardNotice
import com.discountscreener.android.domain.model.DashboardNoticeSeverity
import com.discountscreener.core.model.DcfCoverageStatus
import com.discountscreener.core.model.DcfCoverageSummary
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.ScenarioEstimate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun EstimatesScreen(
    indexEstimates: IndexEstimatesReport?,
    loading: Boolean,
    estimatesHistory: List<IndexEstimatesReport> = emptyList(),
    notice: DashboardNotice? = null,
) {
    when {
        loading && indexEstimates == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        notice != null && indexEstimates == null -> {
            EstimatesNoticeState(notice)
        }
        indexEstimates == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No estimates available")
            }
        }
        indexEstimates.currentWeightedPriceCents == 0L -> {
            EstimatesNoticeState(notice ?: DashboardNotice("Estimates unavailable", "No price data available yet", DashboardNoticeSeverity.Info))
        }
        else -> {
            EstimatesContent(indexEstimates, estimatesHistory, notice)
        }
    }
}

@Composable
private fun EstimatesContent(
    report: IndexEstimatesReport,
    estimatesHistory: List<IndexEstimatesReport>,
    notice: DashboardNotice?,
) {
    val coverage = report.dcfCoverage

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notice?.let { activeNotice ->
            item { NoticeBanner(activeNotice) }
        }
        item {
            HeaderCard(report)
        }
        when (dcfCoverageBannerKind(coverage)) {
            DcfCoverageBannerKind.Unavailable -> item {
                DcfErrorBanner(coverage.coveredSymbols, coverage.totalEligibleSymbols)
            }
            DcfCoverageBannerKind.LowConfidence -> item {
                DcfLowConfidenceBanner(coverage.coveredSymbols, coverage.totalEligibleSymbols)
            }
            DcfCoverageBannerKind.Partial -> item {
                DcfLowConfidenceBanner(coverage.coveredSymbols, coverage.totalEligibleSymbols)
            }
            DcfCoverageBannerKind.Provisional -> item {
                DcfProvisionalBanner(coverage.coveredSymbols, coverage.totalEligibleSymbols)
            }
            DcfCoverageBannerKind.None -> Unit
        }
        items(report.scenarios) { scenario -> ScenarioCard(scenario) }
        item {
            EstimatesTrendChart(estimatesHistory)
        }
    }
}

@Composable
private fun EstimatesNoticeState(notice: DashboardNotice) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NoticeBanner(notice)
    }
}

@Composable
private fun NoticeBanner(notice: DashboardNotice) {
    val containerColor = when (notice.severity) {
        DashboardNoticeSeverity.Info -> MaterialTheme.colorScheme.surfaceVariant
        DashboardNoticeSeverity.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        DashboardNoticeSeverity.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (notice.severity) {
        DashboardNoticeSeverity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        DashboardNoticeSeverity.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        DashboardNoticeSeverity.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = notice.title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
        }
    }
}

internal enum class DcfCoverageBannerKind {
    None,
    Unavailable,
    LowConfidence,
    Partial,
    Provisional,
}

internal fun dcfCoverageBannerKind(summary: DcfCoverageSummary): DcfCoverageBannerKind = when (summary.status) {
    DcfCoverageStatus.Unavailable -> DcfCoverageBannerKind.Unavailable
    DcfCoverageStatus.LowConfidence -> DcfCoverageBannerKind.LowConfidence
    DcfCoverageStatus.Partial -> DcfCoverageBannerKind.Partial
    DcfCoverageStatus.Provisional -> DcfCoverageBannerKind.Provisional
    DcfCoverageStatus.Ready -> DcfCoverageBannerKind.None
}

@Composable
private fun DcfProvisionalBanner(coverageCount: Int, totalSymbols: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u26A0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Provisional — DCF based on $coverageCount / $totalSymbols companies",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DcfLowConfidenceBanner(coverageCount: Int, totalSymbols: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u26A0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Low confidence — only $coverageCount / $totalSymbols companies have DCF data",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "Results may be skewed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun DcfErrorBanner(coverageCount: Int, totalSymbols: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u26A0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Not enough DCF data ($coverageCount / $totalSymbols companies)",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Estimates require at least 25% coverage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(report: IndexEstimatesReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${report.profileName.uppercase()} · ${report.totalSymbols} symbols",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Cap-weighted implied upside vs current prices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Updated ${formatRelativeTime(report.computedAtEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScenarioCard(estimate: ScenarioEstimate) {
    var upsideColor = if (estimate.impliedUpsideBps >= 0) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = scenarioLabel(estimate.scenario),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${estimate.coverageCount} companies",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatUpside(estimate.impliedUpsideBps),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = upsideColor,
            )
        }
    }
}

private fun scenarioLabel(scenario: EstimateScenario): String = when (scenario) {
    EstimateScenario.BearDcf -> "Bear DCF"
    EstimateScenario.BaseDcf -> "Base DCF"
    EstimateScenario.BullDcf -> "Bull DCF"
    EstimateScenario.AnalystLow -> "Analyst Low"
    EstimateScenario.AnalystHigh -> "Analyst High"
}

private fun formatUpside(bps: Int): String {
    var pct = bps / 100.0
    return if (pct >= 0) "+%.1f%%".format(pct) else "%.1f%%".format(pct)
}

private fun formatRelativeTime(epochSeconds: Long): String {
    var nowSeconds = System.currentTimeMillis() / 1_000
    var diffSeconds = nowSeconds - epochSeconds
    return when {
        diffSeconds < 10 -> "just now"
        diffSeconds < 60 -> "${diffSeconds}s ago"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
        else -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalTime())
    }
}

@Composable
private fun EstimatesTrendChart(history: List<IndexEstimatesReport>) {
    val model = remember(history) { EstimatesTrendChartModel.from(history) }
        ?: return

    val scenarioColors = mapOf(
        EstimateScenario.BearDcf to Color(0xFFEF5350),
        EstimateScenario.BaseDcf to Color(0xFFFFCA28),
        EstimateScenario.BullDcf to Color(0xFF66BB6A),
        EstimateScenario.AnalystLow to Color(0xFF26C6DA),
        EstimateScenario.AnalystHigh to Color(0xFF42A5F5),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Forecast trend",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(bottom = 8.dp),
        ) {
            val w = size.width
            val h = size.height

            // Y=0 axis line — only when zero is within the visible range
            val zeroFraction = (0f - model.drawMinUpside) / (model.drawMaxUpside - model.drawMinUpside).coerceAtLeast(1f)
            if (zeroFraction in 0f..1f) {
                val zeroY = h - zeroFraction * h
                drawLine(
                    color = Color.Gray.copy(alpha = 0.4f),
                    start = Offset(0f, zeroY),
                    end = Offset(w, zeroY),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            model.series.forEach { series ->
                val color = scenarioColors[series.scenario] ?: Color.White
                val path = Path()
                series.points.forEachIndexed { i, (epoch, upside) ->
                    val x = ((epoch - model.minEpoch).toFloat() / model.epochSpan) * w
                    val y = h - ((upside - model.drawMinUpside) / (model.drawMaxUpside - model.drawMinUpside).coerceAtLeast(1f)) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            scenarioColors.forEach { (scenario, color) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(modifier = Modifier.size(8.dp).background(color))
                    Text(
                        text = scenarioLabel(scenario),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
