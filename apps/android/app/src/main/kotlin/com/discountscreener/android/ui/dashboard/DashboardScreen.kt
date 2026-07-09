package com.discountscreener.android.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ProjectedProviderState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onAction: (DashboardAction) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    val tickerSearchActive = state.tickerSearchExpanded ||
        state.tickerSearchQuery.isNotBlank() ||
        state.tickerSearchSuggestions.isNotEmpty() ||
        state.tickerSearchNotice != null

    BackHandler(enabled = tickerSearchActive) {
        onAction(DashboardAction.ClearTickerSearch)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "Discount Screener",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            actions = {
                TextButton(
                    onClick = { onAction(DashboardAction.Refresh) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("Refresh")
                }
                TextButton(
                    onClick = { showAddDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("Add")
                }
                TextButton(
                    onClick = { showProfiles = true },
                    modifier = Modifier.widthIn(min = 72.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = state.currentProfile.uppercase(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
        if (state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (!state.statusMessage.isNullOrBlank() && state.startupPhase != com.discountscreener.android.domain.model.DashboardStartupPhase.Ready) {
            Surface(
                tonalElevation = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = state.statusMessage,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TickerSearchBar(
            query = state.tickerSearchQuery,
            suggestions = state.tickerSearchSuggestions,
            expanded = state.tickerSearchExpanded,
            notice = state.tickerSearchNotice,
            label = "Ticker",
            onQueryChange = { onAction(DashboardAction.UpdateTickerSearchQuery(it.uppercase())) },
            onExpandedChange = { onAction(DashboardAction.SetTickerSearchExpanded(it)) },
            onSubmit = { onAction(DashboardAction.SubmitTickerSearch) },
            onSelect = { onAction(DashboardAction.SelectTickerSuggestion(it)) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        ScrollableTabRow(
            selectedTabIndex = state.currentTab.ordinal,
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DashboardTab.entries.forEach { tab ->
                Tab(
                    selected = state.currentTab == tab,
                    onClick = { onAction(DashboardAction.SelectTab(tab)) },
                    text = {
                        Text(
                            text = tabLabel(tab, state),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val visibleTrackedRows = if (state.currentTab == DashboardTab.Watch) {
                state.trackedRows.filter { it.isWatched }
            } else {
                state.trackedRows
            }
            when (state.currentTab) {
                DashboardTab.Tracked,
                DashboardTab.Watch,
                -> {
                    if (visibleTrackedRows.isEmpty()) {
                        EmptyState(
                            title = when {
                                state.currentTab != DashboardTab.Watch -> "No tracked symbols"
                                state.watchlistSymbols.isEmpty() -> "Watchlist is empty"
                                else -> "No watched symbols match the filter"
                            },
                            detail = when {
                                state.currentTab != DashboardTab.Watch ->
                                    "Load a profile or add symbols to begin streaming live Yahoo data."
                                state.watchlistSymbols.isEmpty() ->
                                    "Add symbols to the watchlist from Upside or Opps."
                                else ->
                                    "Clear or change the current filter to see your watched symbols."
                            },
                        )
                    } else {
                        TrackedList(visibleTrackedRows, state.rowQuantLensChipsBySymbol, onAction)
                    }
                }

                DashboardTab.Opportunities -> {
                    if (state.opportunityRows.isEmpty()) {
                        EmptyState(
                            title = "No ranked opportunities",
                            detail = "Opportunity ranks appear once restored data or live coverage is strong enough to score the current universe.",
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpportunityScoringModelToggle(
                                selected = state.opportunityScoringModel,
                                onAction = onAction,
                            )
                            OpportunityList(
                                state.opportunityRows,
                                state.opportunityScoringModel,
                                state.rowQuantLensChipsBySymbol,
                                onAction,
                            )
                        }
                    }
                }

                DashboardTab.System -> SystemContent(state, onAction)
                DashboardTab.Estimates -> EstimatesScreen(
                    indexEstimates = state.indexEstimates,
                    loading = state.indexEstimatesLoading,
                    estimatesHistory = state.estimatesHistory,
                    notice = state.estimatesNotice,
                )
            }
        }
    }

    if (showAddDialog) {
        AddSymbolsDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = {
                onAction(DashboardAction.AddSymbols(it))
                showAddDialog = false
            },
        )
    }

    if (showProfiles) {
        ProfileDialog(
            profiles = state.availableProfiles,
            current = state.currentProfile,
            onDismiss = { showProfiles = false },
            onSelect = {
                onAction(DashboardAction.SelectProfile(it))
                showProfiles = false
            },
        )
    }
}

internal val opportunityScoringModelChipOrder = listOf(
    OpportunityScoringModel.AggressiveV3,
    OpportunityScoringModel.AggressiveV2,
    OpportunityScoringModel.Aggressive,
    OpportunityScoringModel.Legacy,
)

internal fun OpportunityScoringModel.chipLabel(): String = when (this) {
    OpportunityScoringModel.AggressiveV3 -> "Aggressive V3"
    OpportunityScoringModel.AggressiveV2 -> "Aggressive V2"
    OpportunityScoringModel.Aggressive -> "Aggressive"
    OpportunityScoringModel.Legacy -> "Legacy"
}

@Composable
private fun OpportunityScoringModelToggle(
    selected: OpportunityScoringModel,
    onAction: (DashboardAction) -> Unit,
) {
    // Four chips overflow a typical phone width; LazyRow keeps every model reachable and
    // scrolls the selected chip into view so selection never looks like "no chip filled".
    val listState = rememberLazyListState()
    val selectedIndex = opportunityScoringModelChipOrder.indexOf(selected).coerceAtLeast(0)

    LaunchedEffect(selected) {
        listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = opportunityScoringModelChipOrder,
            key = { _, model -> model.name },
        ) { _, model ->
            val isSelected = selected == model
            FilterChip(
                selected = isSelected,
                onClick = {
                    if (!isSelected) {
                        onAction(DashboardAction.SetOpportunityScoringModel(model))
                    }
                },
                label = { Text(model.chipLabel()) },
                modifier = Modifier.semantics { this.selected = isSelected },
            )
        }
    }
}

@Composable
private fun SystemContent(state: DashboardUiState, onAction: (DashboardAction) -> Unit) {
    var showPruneDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Refresh Status", fontWeight = FontWeight.Bold)
                    Text(
                        "Phase: ${state.startupPhase.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Progress: ${state.refreshCompletedSymbols}/${state.refreshTargetSymbols.coerceAtLeast(state.trackedSymbols.size)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.lastUpdatedAtEpochSeconds?.let {
                        Text(
                            "Last updated: ${formatUpdatedTime(it)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = state.statusMessage ?: "Ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { DatabaseStatsCard(state) }

        item { ProviderStateCard(state.providerState) }

        item { LogStatsCard(state) }

        item {
            MaintenanceCard(
                state = state,
                onRefreshStats = { onAction(DashboardAction.RefreshSystemStats) },
                onPrune = { showPruneDialog = true },
                onClearAll = { showClearDialog = true },
            )
        }

        if (state.systemStatusMessage != null) {
            item {
                Surface(
                    tonalElevation = 1.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.systemStatusMessage,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (state.issues.isEmpty()) {
            item {
                EmptyState(
                    title = "No active errors",
                    detail = "Provider and chart errors will surface here when the live feed is degraded.",
                )
            }
        } else {
            items(state.issues.size, key = { state.issues[it].key }) { index ->
                val issue = state.issues[index]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(issue.title, fontWeight = FontWeight.Bold)
                        Text(issue.detail, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${issue.severity.uppercase()}  Count ${issue.count}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    if (showPruneDialog) {
        ConfirmPruneDialog(
            onDismiss = { showPruneDialog = false },
            onConfirm = { retentionDays ->
                onAction(DashboardAction.PruneOldRevisions(retentionDays))
                showPruneDialog = false
            },
        )
    }
    if (showClearDialog) {
        ConfirmClearAllDialog(
            onDismiss = { showClearDialog = false },
            onConfirm = {
                onAction(DashboardAction.ClearAllData)
                showClearDialog = false
            },
        )
    }
}

internal data class ProviderStatusSummary(
    val title: String,
    val status: String,
    val affectedSymbols: String?,
    val retryState: String,
)

internal fun providerStatusSummary(providerState: ProjectedProviderState): ProviderStatusSummary = ProviderStatusSummary(
    title = "Provider State: ${providerState.category.name}",
    status = providerState.statusCopy,
    affectedSymbols = providerState.affectedSymbols.takeIf(List<String>::isNotEmpty)?.joinToString(", "),
    retryState = if (providerState.retryable) "Retryable" else "No retry needed",
)

@Composable
private fun ProviderStateCard(providerState: ProjectedProviderState) {
    val summary = providerStatusSummary(providerState)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(summary.title, fontWeight = FontWeight.Bold)
            Text(summary.status, style = MaterialTheme.typography.bodySmall)
            summary.affectedSymbols?.let {
                Text("Affected: $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                summary.retryState,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DatabaseStatsCard(state: DashboardUiState) {
    val stats = state.systemStats
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Database Stats", fontWeight = FontWeight.Bold)
            if (state.systemStatsLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (stats != null) {
                Text(
                    "Size: ${formatFileSize(stats.databaseFileSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                stats.tables.forEach { table ->
                    Text(
                        "${table.tableName}: ${table.rowCount} rows",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogStatsCard(state: DashboardUiState) {
    val stats = state.systemStats ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Log Stats", fontWeight = FontWeight.Bold)
            stats.logTables.forEach { log ->
                Text(
                    "${log.tableName}: ${log.rowCount} rows",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (log.oldestEpoch != null) {
                    Text(
                        "  Oldest: ${formatLogTimestamp(log.oldestEpoch)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "  Newest: ${formatLogTimestamp(log.newestEpoch!!)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MaintenanceCard(
    state: DashboardUiState,
    onRefreshStats: () -> Unit,
    onPrune: () -> Unit,
    onClearAll: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Maintenance", fontWeight = FontWeight.Bold)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                when (maintenanceLayoutMode(maxWidth)) {
                    MaintenanceLayoutMode.Stacked -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onRefreshStats,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Refresh Stats", maxLines = 1, textAlign = TextAlign.Center)
                            }
                            OutlinedButton(
                                onClick = onPrune,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Prune Old Data", maxLines = 1, textAlign = TextAlign.Center)
                            }
                            Button(
                                onClick = onClearAll,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("Clear All Data", maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                    }

                    MaintenanceLayoutMode.Split -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onRefreshStats,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Refresh Stats", maxLines = 1, textAlign = TextAlign.Center)
                                }
                                OutlinedButton(
                                    onClick = onPrune,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Prune Old Data", maxLines = 1, textAlign = TextAlign.Center)
                                }
                            }
                            Button(
                                onClick = onClearAll,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("Clear All Data", maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal enum class MaintenanceLayoutMode {
    Stacked,
    Split,
}

internal fun maintenanceLayoutMode(maxWidth: Dp): MaintenanceLayoutMode =
    if (maxWidth < 320.dp) MaintenanceLayoutMode.Stacked else MaintenanceLayoutMode.Split

@Composable
private fun EmptyState(title: String, detail: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun tabLabel(tab: DashboardTab, state: DashboardUiState): String = when (tab) {
    DashboardTab.Tracked -> "Upside ${state.trackedRows.size}"
    DashboardTab.Opportunities -> "Opps ${state.opportunityRows.size}"
    DashboardTab.Watch -> "Watch ${state.watchlistSymbols.size}"
    DashboardTab.System -> "System"
    DashboardTab.Estimates -> "Estimates"
}

private fun formatUpdatedTime(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(
            Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalTime(),
        )
