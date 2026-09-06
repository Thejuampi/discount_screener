package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.portfolio.ImportPlan

class ImportPortfolioBookUseCase(private val repository: DashboardRepository) {
    suspend fun plan(text: String): ImportPlan = repository.planPortfolioCsv(text)

    suspend fun confirm(plan: ImportPlan) = repository.confirmPortfolioPlan(plan)
}
