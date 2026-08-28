package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discountscreener.android.presentation.dashboard.EarningsEventRowUi
import com.discountscreener.android.presentation.dashboard.EarningsGateUi
import com.discountscreener.core.earnings.EventRisk

@Composable
fun EarningsGateScreen(state: EarningsGateUi, loading: Boolean) {
    if (loading && state.isEmpty) {
        EmptyState(title = "Reading the earnings log", detail = "One line per report, kept on this device.")
        return
    }
    if (state.isEmpty) {
        EmptyState(
            title = "No earnings events logged yet",
            detail = "A report is captured when it comes within ten days of a refresh. " +
                "Option chains are never republished, so the log only grows forward.",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag(EARNINGS_GATE_LIST),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        if (state.upcoming.isNotEmpty()) {
            item { GateSectionLabel("Reporting soon") }
            items(state.upcoming, key = { it.symbol + it.reportDate }) { row -> EarningsEventCard(row) }
        }
        if (state.settled.isNotEmpty()) {
            item { GateSectionLabel("Already reported") }
            items(state.settled, key = { it.symbol + it.reportDate }) { row -> EarningsEventCard(row) }
        }
        if (state.damagedLines > 0) {
            item {
                Text(
                    text = "${state.damagedLines} unreadable line(s) in the log, skipped.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

const val EARNINGS_GATE_LIST = "earningsGateList"

@Composable
private fun EarningsEventCard(row: EarningsEventRowUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = row.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${row.reportDate} · ${row.timing}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = row.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = riskColor(row.risk),
                fontWeight = FontWeight.SemiBold,
            )
            GateLine("Priced move", row.impliedMove)
            GateLine("Event move", row.eventMove)
            GateLine("Own history", row.ownHistory)
            GateLine("Risk ratio", row.riskRatio)
            GateLine("Price vs fair value", row.priceToFair)
            GateLine("Action", "${row.action} · ${row.positionSize}")
            GateLine("Hedge", row.hedge)
            GateLine("Hedge cost", row.hedgeCost)
            row.reaction?.let { GateLine("Reaction", it) }
            if (row.justification.isNotBlank()) {
                Text(text = row.justification, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GateLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GateSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun riskColor(risk: EventRisk): Color = when (risk) {
    EventRisk.High -> MaterialTheme.colorScheme.error
    EventRisk.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}
