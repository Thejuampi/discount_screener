package com.discountscreener.android.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.discountscreener.android.data.portfolio.BookCsvInputReader
import com.discountscreener.android.data.portfolio.BookCsvReadGeneration
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.importPlanWarning
import com.discountscreener.core.portfolio.ImportPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val SYSTEM_GATE_IMPORT = "systemGateImport"
const val BOOK_CSV_READ_STATE = "bookCsvReadState"
const val BOOK_CSV_READ_ERROR = "bookCsvReadError"

@Composable
fun ImportBookButton(
    onAction: (DashboardAction) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember(context) { BookCsvInputReader(context.contentResolver) }
    val generations = remember { BookCsvReadGeneration() }
    var readState by remember { mutableStateOf(BookCsvReadState.Idle) }
    var readError by remember { mutableStateOf<String?>(null) }
    var readJob by remember { mutableStateOf<Job?>(null) }
    var open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        val generation = generations.begin()
        readJob?.cancel()
        readError = null
        readState = BookCsvReadState.Reading
        val job = scope.launch {
            try {
                val text = reader.read(source)
                if (!generations.isCurrent(generation)) return@launch
                readState = BookCsvReadState.Idle
                onAction(DashboardAction.ImportBookCsv(text))
            } catch (_: CancellationException) {
                if (generations.isCurrent(generation)) readState = BookCsvReadState.Idle
            } catch (error: Exception) {
                if (generations.isCurrent(generation)) {
                    readState = BookCsvReadState.Idle
                    readError = error.message ?: "Unable to read the selected book file."
                }
            } finally {
                if (generations.isCurrent(generation)) readJob = null
            }
        }
        readJob = job
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(
            onClick = {
                if (readState == BookCsvReadState.Reading) {
                    generations.cancel()
                    readState = BookCsvReadState.Idle
                    readJob?.cancel()
                    readJob = null
                } else {
                    open.launch(arrayOf("text/*", "*/*"))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
        ) {
            Text(if (readState == BookCsvReadState.Reading) "Cancel read" else "Import book")
        }
        if (readState == BookCsvReadState.Reading) {
            Text("Reading book…", modifier = Modifier.testTag(BOOK_CSV_READ_STATE))
        }
        readError?.let { error ->
            Text("Import failed: $error", modifier = Modifier.testTag(BOOK_CSV_READ_ERROR))
        }
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

private enum class BookCsvReadState {
    Idle,
    Reading,
}
