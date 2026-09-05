package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository

/** The log to write out, and how many reports it carries. */
data class EarningsLogBackup(val text: String, val eventCount: Int)

class EarningsLogBackupUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(): EarningsLogBackup {
        var text = repository.earningsLogBackup()
        return EarningsLogBackup(text = text, eventCount = text.trim().lines().count { it.isNotBlank() })
    }
}

class RestoreEarningsLogUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(text: String): Int = repository.restoreEarningsLog(text)
}
