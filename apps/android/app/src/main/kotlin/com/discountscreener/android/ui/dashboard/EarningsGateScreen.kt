package com.discountscreener.android.ui.dashboard

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discountscreener.android.presentation.dashboard.EarningsEventRowUi
import com.discountscreener.android.presentation.dashboard.EarningsGateUi
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.matching
import com.discountscreener.core.earnings.EventRisk

@Composable
fun EarningsGateScreen(
    state: EarningsGateUi,
    loading: Boolean,
    pendingBackup: String? = null,
    notice: String? = null,
    onAction: (DashboardAction) -> Unit = {},
) {
    EarningsLogHandOff(pendingBackup = pendingBackup, onAction = onAction)
    if (loading && state.isEmpty) {
        EmptyState(title = "Reading the earnings log", detail = "One line per report, kept on this device.")
        return
    }
    if (state.isEmpty && state.damagedLines == 0) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // EmptyState fills whatever it is given, so it needs a share of the height and not
            // all of it. Without the weight the buttons below it land off the bottom of the
            // screen, which is exactly the screen a reader with a fresh install arrives at.
            Box(modifier = Modifier.weight(1f)) {
                EmptyState(
                    title = "No earnings events logged yet",
                    detail = "A report is captured when it comes within ten days of a refresh. " +
                        "Option chains are never republished, so the log only grows forward." +
                        state.lastCapture?.let { " $it." }.orEmpty(),
                )
            }
            notice?.let { GateNotice(it) }
            EarningsLogButtons(onAction)
        }
        return
    }
    var query by remember { mutableStateOf("") }
    var shown = state.matching(query)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag(EARNINGS_GATE_LIST),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter by ticker") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(EARNINGS_GATE_SEARCH),
            )
        }
        state.lastCapture?.let { checked ->
            item {
                Text(
                    text = checked,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(EARNINGS_GATE_LAST_CAPTURE),
                )
            }
        }
        if (shown.isEmpty && query.isNotBlank()) {
            item {
                Text(
                    text = "No logged report matches \"${query.trim()}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(EARNINGS_GATE_NO_MATCH),
                )
            }
        }
        if (shown.upcoming.isNotEmpty()) {
            item { GateSectionLabel("Reporting soon") }
            items(shown.upcoming, key = { it.symbol + it.reportDate }) { row -> EarningsEventCard(row) }
        }
        if (shown.settled.isNotEmpty()) {
            item { GateSectionLabel("Already reported") }
            items(shown.settled, key = { it.symbol + it.reportDate }) { row -> EarningsEventCard(row) }
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
        item {
            notice?.let { GateNotice(it) }
            EarningsLogButtons(onAction)
        }
    }
}

/**
 * The log out to a file the phone does not own, and back in from one.
 *
 * Whatever the reader picks outlives an uninstall, and an uninstall is what a lost signing key
 * forces. The release build is not debuggable, so no cable reaches this file either. Every other
 * thing on the phone can be downloaded again; the option chains in here are never republished.
 */
@Composable
private fun EarningsLogHandOff(pendingBackup: String?, onAction: (DashboardAction) -> Unit) {
    var context = LocalContext.current
    var save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { target ->
        var text = pendingBackup
        var written = if (target == null || text == null) {
            null
        } else {
            runCatching { writeTo(context, target, text) }.getOrNull()
        }
        if (written == null) {
            onAction(DashboardAction.EarningsLogBackupDropped)
        } else {
            onAction(DashboardAction.EarningsLogBackupWritten(written))
        }
    }
    LaunchedEffect(pendingBackup) {
        if (pendingBackup != null) save.launch(BACKUP_NAME)
    }
}

@Composable
private fun EarningsLogButtons(onAction: (DashboardAction) -> Unit) {
    var context = LocalContext.current
    var open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        var text = source?.let { runCatching { readFrom(context, it) }.getOrNull() }
        if (text != null) onAction(DashboardAction.RestoreEarningsLog(text))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { onAction(DashboardAction.BackUpEarningsLog) },
            modifier = Modifier.weight(1f).testTag(EARNINGS_GATE_BACK_UP),
        ) {
            Text("Back up log")
        }
        OutlinedButton(
            onClick = { open.launch(arrayOf("*/*")) },
            modifier = Modifier.weight(1f).testTag(EARNINGS_GATE_RESTORE),
        ) {
            Text("Restore")
        }
    }
}

@Composable
private fun GateNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(EARNINGS_GATE_NOTICE),
    )
}

private fun writeTo(context: Context, target: Uri, text: String): Int {
    var stream = context.contentResolver.openOutputStream(target, "wt")
        ?: error("The chosen file refused to open for writing.")
    stream.use { it.write(text.toByteArray()) }
    return text.trim().lines().count { it.isNotBlank() }
}

private fun readFrom(context: Context, source: Uri): String =
    context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
        ?: error("The chosen file refused to open for reading.")

private const val BACKUP_MIME = "application/x-ndjson"
private const val BACKUP_NAME = "earnings-log.jsonl"

const val EARNINGS_GATE_BACK_UP = "earningsGateBackUp"
const val EARNINGS_GATE_RESTORE = "earningsGateRestore"
const val EARNINGS_GATE_NOTICE = "earningsGateNotice"
const val EARNINGS_GATE_LIST = "earningsGateList"
const val EARNINGS_GATE_LAST_CAPTURE = "earningsGateLastCapture"
const val EARNINGS_GATE_SEARCH = "earningsGateSearch"
const val EARNINGS_GATE_NO_MATCH = "earningsGateNoMatch"

@Composable
internal fun EarningsEventCard(row: EarningsEventRowUi) {
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
            row.reportedOn?.let { GateLine("Reported", it) }
            row.reaction?.let { GateLine("Reaction", it) }
            row.surprise?.let { GateLine("Surprise", it) }
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
