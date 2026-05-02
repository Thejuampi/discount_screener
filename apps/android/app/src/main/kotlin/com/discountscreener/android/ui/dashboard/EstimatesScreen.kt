package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            EstimatesContent(indexEstimates)
        }
    }
}

@Composable
private fun EstimatesContent(report: IndexEstimatesReport) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            HeaderCard(report)
        }
        items(report.scenarios) { scenario ->
            ScenarioCard(scenario, report.totalSymbols)
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
private fun ScenarioCard(estimate: ScenarioEstimate, totalSymbols: Int) {
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
                    text = "${estimate.coverageCount} / $totalSymbols companies",
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

private fun formatComputedTime(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(
            Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalTime(),
        )