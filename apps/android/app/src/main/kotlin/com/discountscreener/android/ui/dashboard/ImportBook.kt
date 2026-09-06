package com.discountscreener.android.ui.dashboard

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.importPlanWarning
import com.discountscreener.core.portfolio.ImportPlan

const val SYSTEM_GATE_IMPORT = "systemGateImport"

@Composable
fun ImportBookButton(
    onAction: (DashboardAction) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String,
) {
    var context = LocalContext.current
    var open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        var text = runCatching { readCsv(context, source) }.getOrElse { "" }
        onAction(DashboardAction.ImportBookCsv(text))
    }
    OutlinedButton(
        onClick = { open.launch(arrayOf("text/*", "*/*")) },
        modifier = modifier.testTag(testTag),
    ) {
        Text("Import book")
    }
}

@Composable
fun ImportBookDialog(
    plan: ImportPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmable = plan is ImportPlan.ConfirmHoldingsReplace || plan is ImportPlan.ConfirmTradesMerge
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmable) "Import book" else "Import refused") },
        text = { Text(importPlanWarning(plan)) },
        confirmButton = {
            if (confirmable) {
                TextButton(onClick = onConfirm) { Text("Confirm") }
            } else {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        },
        dismissButton = {
            if (confirmable) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun readCsv(context: Context, source: Uri): String =
    context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
        ?: error("The chosen file refused to open for reading.")
