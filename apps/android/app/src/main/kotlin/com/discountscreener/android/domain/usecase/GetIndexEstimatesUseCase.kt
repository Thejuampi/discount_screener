package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.engine.IndexEstimatesEngine
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter

class GetIndexEstimatesUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(opportunityScoringModel: OpportunityScoringModel): IndexEstimatesReport {
        var symbols = repository.trackedSymbolDetails()
        var dcfBySymbol = repository.dcfSnapshot()
        var profileName = repository.currentSnapshot(
            filter = ViewFilter(),
            selectedSymbol = null,
            selectedRange = ChartRange.Year,
            opportunityScoringModel = opportunityScoringModel,
        ).currentProfile
        return IndexEstimatesEngine.compute(
            symbols = symbols,
            dcfBySymbol = dcfBySymbol,
            profileName = profileName,
            nowEpochSeconds = System.currentTimeMillis() / 1_000,
        )
    }
}
