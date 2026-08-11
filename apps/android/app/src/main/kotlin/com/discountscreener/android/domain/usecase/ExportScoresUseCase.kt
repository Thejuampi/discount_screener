package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.OpportunityScoringModel
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the export landed, and how many rows it really holds. */
data class ScoreExportResult(val path: String, val rowCount: Int)

/**
 * Writes the score export to the app's private files directory and returns the path.
 *
 * Private storage on purpose: a debug build is readable with
 * `adb exec-out run-as <applicationId> cat files/<name>.csv`, so the export needs no storage
 * permission and no `getExternalFilesDir` to reach a workstation.
 */
class ExportScoresUseCase(
    private val repository: DashboardRepository,
    private val exportDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(
        profile: String,
        model: OpportunityScoringModel,
    ): ScoreExportResult = withContext(ioDispatcher) {
        var csv = repository.scoreExportCsv(model)
        // Profile and model are both in the name: two models over the same universe are two
        // different populations, and one overwriting the other would lose half the comparison.
        var target = File(exportDirectory, "score-export-$profile-${model.name.lowercase()}.csv")
        target.writeText(csv)
        // Counted from the text that was written, minus its header. The export covers the whole
        // scored cohort, which is roughly eight times the Opportunities list, so a count taken
        // from the list would understate the file by a factor and hide a truncated export.
        ScoreExportResult(target.absolutePath, csv.trim().lines().size - 1)
    }
}
