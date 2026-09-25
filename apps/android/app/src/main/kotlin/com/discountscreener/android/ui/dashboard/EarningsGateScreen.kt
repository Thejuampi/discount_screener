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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
            ImportBookButton(
                onAction = onAction,
                modifier = Modifier.fillMaxWidth(),
                testTag = EARNINGS_GATE_IMPORT,
            )
            EarningsLogButtons(onAction, keyPresent = state.alphaVantageKeyPresent)
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
            ImportBookButton(
                onAction = onAction,
                modifier = Modifier.fillMaxWidth(),
                testTag = EARNINGS_GATE_IMPORT,
            )
            EarningsLogButtons(onAction, keyPresent = state.alphaVantageKeyPresent)
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
private fun EarningsLogButtons(
    onAction: (DashboardAction) -> Unit,
    keyPresent: Boolean,
) {
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
    Text(
        text = if (keyPresent) {
            "Alpha Vantage key is on this device"
        } else {
            "No Alpha Vantage key on this device"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(EARNINGS_GATE_KEY_STATUS),
    )
    var key by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Alpha Vantage key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f).testTag(EARNINGS_GATE_AV_KEY),
        )
        OutlinedButton(
            onClick = {
                if (key.isNotBlank()) {
                    onAction(DashboardAction.SaveAlphaVantageKey(key))
                    key = ""
                }
            },
            modifier = Modifier.testTag(EARNINGS_GATE_SAVE_KEY),
        ) {
            Text("Save key")
        }
        OutlinedButton(
            onClick = { onAction(DashboardAction.ClearAlphaVantageKey) },
            modifier = Modifier.testTag(EARNINGS_GATE_CLEAR_KEY),
        ) {
            Text("Clear key")
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
const val EARNINGS_GATE_AV_KEY = "earningsGateAvKey"
const val EARNINGS_GATE_SAVE_KEY = "earningsGateSaveKey"
const val EARNINGS_GATE_CLEAR_KEY = "earningsGateClearKey"
const val EARNINGS_GATE_KEY_STATUS = "earningsGateKeyStatus"
const val EARNINGS_GATE_HELD = "earningsGateHeld"
const val EARNINGS_GATE_IMPORT = "earningsGateImport"

@Composable
internal fun EarningsEventCard(row: EarningsEventRowUi) {
    var showOptions by rememberSaveable(row.symbol, row.reportDate) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = row.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (row.held) {
                        Text(
                            text = "Held",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag(EARNINGS_GATE_HELD),
                        )
                    }
                }
                Text(
                    text = "${row.reportDate} · ${row.timing}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (showOptions) {
                    OutlinedButton(onClick = { showOptions = false }, modifier = Modifier.weight(1f)) { Text("Simple") }
                    Button(onClick = { showOptions = true }, modifier = Modifier.weight(1f)) { Text("Options") }
                } else {
                    Button(onClick = { showOptions = false }, modifier = Modifier.weight(1f)) { Text("Simple") }
                    OutlinedButton(onClick = { showOptions = true }, modifier = Modifier.weight(1f)) { Text("Options") }
                }
            }
            if (showOptions) {
                Text(text = "This is a saved example, not a live order.", style = MaterialTheme.typography.bodySmall)
                Text(text = row.optionExplanation, style = MaterialTheme.typography.bodySmall)
                row.optionWarning?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                GateLine("Model stance", "${row.action} · ${row.positionSize}")
                GateLine("Hedge", row.hedge)
                GateLine("Hedge cost", row.hedgeCost)
                GateLine("Expiry", row.optionExpiry)
                Text(
                    text = "Contract count, live price, and fees are not saved.",
                    style = MaterialTheme.typography.bodySmall,
                )
                row.reportedOn?.let { GateLine("Reported", it) }
                row.reaction?.let { GateLine("Reaction", it) }
                row.surprise?.let { GateLine("Surprise", it) }
                row.sueFit?.let { GateLine("SUE fit", it) }
                row.revenueTrail?.let { GateLine("Revenue trail", it) }
                if (row.justification.isNotBlank()) {
                    Text(text = row.justification, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    text = row.simpleRisk.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = riskColor(row.risk),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = row.simpleRisk.reportMove, style = MaterialTheme.typography.bodyMedium)
                Text(text = row.simpleRisk.pastMove, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "These figures describe possible movement. They do not predict direction.",
                    style = MaterialTheme.typography.bodySmall,
                )
                row.simpleRisk.outcome?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                if (row.simpleRisk.paths.isNotEmpty()) {
                    Text("Ways without options", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "These are choices to compare. The app does not select one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    row.simpleRisk.paths.forEach { path ->
                        Text(path.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(path.tradeoff, style = MaterialTheme.typography.bodySmall)
                    }
                }
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
