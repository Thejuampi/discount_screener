package com.discountscreener.core.plan

data class PlanBoard(
    val universeName: String,
    val scanned: Int,
    val nowCount: Int,
    val almostCount: Int,
    val refuseCount: Int,
    val now: List<DipSetup>,
    val later: List<DipSetup>,
    val offRadarAlmost: Int,
) {
    companion object {
        val EMPTY = PlanBoard(
            universeName = DipSignalEngine.UNIVERSE_OPPORTUNITIES,
            scanned = 0,
            nowCount = 0,
            almostCount = 0,
            refuseCount = 0,
            now = emptyList(),
            later = emptyList(),
            offRadarAlmost = 0,
        )
    }
}
