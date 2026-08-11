package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.model.OpportunityScoringModel
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    ): String = withContext(ioDispatcher) {
        var csv = repository.scoreExportCsv(model)
        // Profile and model are both in the name: two models over the same universe are two
        // different populations, and one overwriting the other would lose half the comparison.
        var target = File(exportDirectory, "score-export-$profile-${model.name.lowercase()}.csv")
        target.writeText(csv)
        target.absolutePath
    }
}
