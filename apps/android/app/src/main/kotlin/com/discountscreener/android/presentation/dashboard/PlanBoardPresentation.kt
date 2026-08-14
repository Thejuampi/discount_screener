package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.plan.DipCopy
import com.discountscreener.core.plan.DipLane
import com.discountscreener.core.plan.DipSetup
import com.discountscreener.core.plan.PlanBoard
import com.discountscreener.core.plan.formatDollars

data class PlanCardUi(
    val symbol: String,
    val lane: DipLane,
    val laneLabel: String,
    val deathCross: Boolean,
    val headline: String,
    val evidence: List<String>,
    val priceLabel: String,
    val streetLabel: String,
    val fLabel: String,
    val spark: List<Long>,
)

data class PlanBoardUi(
    val countsLine: String,
    val offRadarLine: String?,
    val universeLine: String,
    val nowTitle: String,
    val laterTitle: String,
    val now: List<PlanCardUi>,
    val later: List<PlanCardUi>,
    val emptyNow: Boolean,
    val emptyNowTitle: String,
    val emptyNowDetail: String,
)

fun presentPlanBoard(board: PlanBoard): PlanBoardUi {
    var offRadar = if (board.offRadarAlmost > 0) {
        "${board.offRadarAlmost} more almost off radar"
    } else {
        null
    }
    return PlanBoardUi(
        countsLine = "${board.nowCount} now  ·  ${board.later.size} almost  ·  ${board.refuseCount} out",
        offRadarLine = offRadar,
        universeLine = "Universe ${board.universeName}  ·  ${board.scanned} scanned",
        nowTitle = "NOW · DIP",
        laterTitle = "ALMOST · REVIEW",
        now = board.now.map(::presentCard),
        later = board.later.map(::presentCard),
        emptyNow = board.now.isEmpty(),
        emptyNowTitle = "No dip now",
        emptyNowDetail = "No name meets F, dip, RSI, MACD, and Street 20% together.",
    )
}

private fun presentCard(setup: DipSetup): PlanCardUi {
    var laneLabel = if (setup.lane == DipLane.Now) "Now" else "Almost"
    return PlanCardUi(
        symbol = setup.symbol,
        lane = setup.lane,
        laneLabel = laneLabel,
        deathCross = setup.tags.contains("death_cross"),
        headline = setup.headline,
        evidence = setup.evidence.take(5),
        priceLabel = formatDollars(setup.marketPriceCents),
        streetLabel = DipCopy.streetLine(setup.streetUpsideBps),
        fLabel = DipCopy.fLine(setup.fundamentalsScore),
        spark = setup.spark,
    )
}
