package com.discountscreener.android.domain.usecase

import com.discountscreener.android.domain.repository.DashboardRepository

class LoadSymbolNotesUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(): Map<String, String> = repository.loadSymbolNotes()
}

class SaveSymbolNoteUseCase(private val repository: DashboardRepository) {
    suspend operator fun invoke(symbol: String, note: String) = repository.saveSymbolNote(symbol, note)
}
