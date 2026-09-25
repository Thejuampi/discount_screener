package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.presentation.dashboard.PositionsBookSummary
import com.discountscreener.android.presentation.dashboard.PositionsRow
import com.discountscreener.android.presentation.dashboard.PositionsSort
import com.discountscreener.android.presentation.dashboard.presentPositionsSummary
import com.discountscreener.android.presentation.dashboard.sortPositionRows
import com.discountscreener.core.portfolio.Coverage
import com.discountscreener.core.portfolio.closenessLabel
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal const val POSITION_FACTS_PREFIX = "positionFacts"
internal const val POSITIONS_MENU = "positionsMenu"

@Composable
internal fun PositionsContent(
    state: DashboardUiState,
    onAction: (DashboardAction) -> Unit,
) {
    var selectedSort by rememberSaveable { mutableStateOf(PositionsSort.LargestPosition.name) }
    var expandedSort by rememberSaveable { mutableStateOf(false) }
    val sort = PositionsSort.valueOf(selectedSort)
    PositionsList(
        rows = sortPositionRows(state.positionsRows, sort),
        scoringModel = state.opportunityScoringModel,
        onAction = onAction,
        modifier = Modifier.fillMaxWidth(),
        summary = state.positionsRows
            .takeIf { it.isNotEmpty() }
            ?.let(::presentPositionsSummary),
        selectedSort = sort,
        menuExpanded = expandedSort,
        onMenuExpandedChange = { expandedSort = it },
        onSortSelected = {
            selectedSort = it.name
            expandedSort = false
        },
        importBookNotice = state.importBookNotice,
    )
}

@Composable
internal fun PositionFactsBlock(
    row: PositionsRow,
    modifier: Modifier = Modifier,
    heading: String? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heading?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text("Shares ${row.quantityLabel}", style = MaterialTheme.typography.bodySmall)
        Text(
            if (row.avgCostCents >= 0L) {
                "Average cost ${positionMoney(row.avgCostCents)}"
            } else {
                "Average cost unavailable: Missing cost"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            row.quoteCents?.let { "Price ${positionMoney(it)}" }
                ?: "Price unavailable: ${valueReason(row)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            row.profitLossCents?.let { "Unrealized P/L ${positionMoney(it)}" }
                ?: "Unrealized P/L unavailable: ${profitLossReason(row)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            row.profitLossBps?.let { "P/L % ${formatWeight(it)}" }
                ?: "P/L % unavailable: ${profitLossPercentReason(row)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            row.weightBps?.let { "Weight ${formatWeight(it)}" }
                ?: "Weight unavailable: ${weightReason(row)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Quote status ${quoteStatus(row.quoteIsCurrent)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun PositionsSummary(summary: PositionsBookSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f
            val valueBlock: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Stock value", fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                    Text(
                        summary.totalValueCents?.let(::positionMoney) ?: valueUnavailableReason(summary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Value coverage: ${coverageLabel(summary.valueCoverage, summary.valueEligibleLots, summary.totalLots)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val profitBlock: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Unrealized P/L", fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                    Text(
                        summary.profitLossCents?.let(::positionMoney) ?: profitLossUnavailableReason(summary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        summary.profitLossBps?.let { "P/L ${formatWeight(it)}" }
                            ?: profitLossPercentUnavailableReason(summary),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "P/L coverage: ${coverageLabel(summary.profitLossCoverage, summary.profitLossEligibleLots, summary.totalLots)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    valueBlock()
                    profitBlock()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) { valueBlock() }
                    Column(modifier = Modifier.weight(1f)) { profitBlock() }
                }
            }
        }
        if (summary.hasNonCurrentQuotes) {
            Text("Some quote ages are unconfirmed", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PositionsMenu(
    selectedSort: PositionsSort,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortSelected: (PositionsSort) -> Unit,
    onAction: (DashboardAction) -> Unit,
    importBookNotice: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = selectedSort.label(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 12.dp, end = 4.dp),
        )
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier
                .testTag(POSITIONS_MENU)
                .semantics { contentDescription = "Positions menu" },
        ) {
            Text("⋮", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            PositionsSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label()) },
                    onClick = { onSortSelected(sort) },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ImportBookButton(
                    onAction = onAction,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = POSITIONS_GATE_IMPORT,
                )
                importBookNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

internal fun PositionsSort.label(): String = when (this) {
    PositionsSort.LargestPosition -> "Largest position"
    PositionsSort.NeedsReview -> "Needs review"
    PositionsSort.EarningsSoon -> "Earnings soon"
}

internal fun formatWeight(weightBps: Int): String {
    val sign = if (weightBps < 0) "-" else ""
    val absolute = kotlin.math.abs(weightBps.toLong())
    return "$sign${absolute / 100}.${absolute % 100 / 10}${absolute % 10}%"
}

internal fun positionMoney(cents: Long): String = DecimalFormat(
    "$#,##0.00",
    DecimalFormatSymbols(Locale.US),
).format(BigDecimal.valueOf(cents, 2))

internal fun quoteStatus(current: Boolean?): String = when (current) {
    true -> "Current"
    false -> "Unconfirmed"
    null -> "Unavailable"
}

private fun coverageLabel(coverage: Coverage, eligible: Int, total: Int): String = when (coverage) {
    Coverage.Complete -> "Complete ($eligible/$total)"
    Coverage.Partial -> "Partial ($eligible/$total)"
    Coverage.Unavailable -> "Unavailable (0/$total)"
}

private fun valueUnavailableReason(summary: PositionsBookSummary): String = if (
    summary.valueEligibleLots > 0
) {
    "Unavailable: total exceeds supported range"
} else {
    "Unavailable"
}

private fun profitLossUnavailableReason(summary: PositionsBookSummary): String = if (
    summary.profitLossEligibleLots > 0
) {
    "Unrealized P/L unavailable: total exceeds supported range"
} else {
    "Unavailable"
}

private fun profitLossPercentUnavailableReason(summary: PositionsBookSummary): String = if (
    summary.profitLossCents == null && summary.profitLossEligibleLots > 0
) {
    "P/L % unavailable: total exceeds supported range"
} else {
    "P/L % unavailable"
}

private fun valueReason(row: PositionsRow): String = when {
    row.quoteCents == null -> "Missing quote"
    row.marketValueCents == null -> "Unsupported value"
    else -> "Unavailable"
}

private fun profitLossReason(row: PositionsRow): String = when {
    row.avgCostCents < 0L -> "Missing cost"
    row.quoteCents == null -> "Missing quote"
    row.marketValueCents == null -> "Unsupported value"
    else -> "Unavailable"
}

private fun profitLossPercentReason(row: PositionsRow): String = when {
    row.avgCostCents == 0L -> "Zero cost"
    row.avgCostCents < 0L -> "Missing cost"
    row.quoteCents == null -> "Missing quote"
    row.marketValueCents == null -> "Unsupported value"
    else -> "Unavailable"
}

private fun weightReason(row: PositionsRow): String = when {
    row.weightBps == null && row.valueCoverage == Coverage.Complete && row.marketValueCents != null ->
        "total exceeds supported range"
    row.valueCoverage != Coverage.Complete -> "Incomplete book"
    row.marketValueCents == null -> "Unsupported value"
    else -> "Unavailable"
}
