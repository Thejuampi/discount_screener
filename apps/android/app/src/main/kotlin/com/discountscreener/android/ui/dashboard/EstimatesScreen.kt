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
) {
    when {
        loading && indexEstimates == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        indexEstimates == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No estimates available")
            }
        }
        indexEstimates.currentWeightedPriceCents == 0L -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No price data available yet")
            }
        }
        else -> {
            EstimatesContent(indexEstimates, estimatesHistory)
        }
    }
}

@Composable
private fun EstimatesContent(report: IndexEstimatesReport, estimatesHistory: List<IndexEstimatesReport>) {
    val dcfScenarios = report.scenarios.filter {
        it.scenario in setOf(EstimateScenario.BearDcf, EstimateScenario.BaseDcf, EstimateScenario.BullDcf)
    }
    val dcfCoverageCount = dcfScenarios.maxOfOrNull { it.coverageCount } ?: 0
    val showDcfWarning = report.totalSymbols > 0 && dcfCoverageCount * 2 < report.totalSymbols

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            HeaderCard(report)
        }
        if (showDcfWarning) {
            item {
                DcfCoverageBanner(dcfCoverageCount, report.totalSymbols)
            }
        }
        items(report.scenarios) { scenario ->
            ScenarioCard(scenario)
        }
        item {
            EstimatesTrendChart(estimatesHistory)
        }
    }
}

@Composable
private fun DcfCoverageBanner(coverageCount: Int, totalSymbols: Int) {
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
                    text = "DCF coverage is low ($coverageCount / $totalSymbols companies)",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "DCF estimates may not be representative until more data is fetched",
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
                text = "Updated ${formatComputedTime(report.computedAtEpochSeconds)}",
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

private fun formatComputedTime(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(
            Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalTime(),
        )

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
            val zeroFraction = (0f - model.minUpside) / model.upsideSpan
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
                    val y = h - ((upside - model.minUpside) / model.upsideSpan) * h
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