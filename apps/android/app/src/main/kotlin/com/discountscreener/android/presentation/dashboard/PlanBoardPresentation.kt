package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.plan.CrossCopy
import com.discountscreener.core.plan.DipCopy
import com.discountscreener.core.plan.DipLane
import com.discountscreener.core.plan.DipSetup
import com.discountscreener.core.plan.LeftoverCopy
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
    val huntLabel: String,
    val countsLine: String,
    val offRadarLine: String?,
    val universeLine: String?,
    val nowTitle: String,
    val laterTitle: String,
    val now: List<PlanCardUi>,
    val later: List<PlanCardUi>,
    val emptyNow: Boolean,
    val emptyNowTitle: String,
    val emptyNowDetail: String,
)

/**
 * A board the refresh has not built yet.
 *
 * A blocked board is not a scanned universe with no hit. Saying "no name meets the rule" for
 * a board that scanned nothing states a result the app never measured.
 */
private fun pendingBoard(huntLabel: String, nowTitle: String, laterTitle: String) = PlanBoardUi(
    huntLabel = huntLabel,
    countsLine = "Not scored yet",
    offRadarLine = null,
    universeLine = null,
    nowTitle = nowTitle,
    laterTitle = laterTitle,
    now = emptyList(),
    later = emptyList(),
    emptyNow = true,
    emptyNowTitle = "Waiting for the refresh",
    emptyNowDetail = "This board reads the data the refresh is loading. It is built when the " +
        "refresh ends.",
)

fun presentSelectedDipBoard(
    opportunities: PlanBoard?,
    profile: PlanBoard?,
    universe: PlanDipUniverse,
): PlanBoardUi {
    var board = if (universe == PlanDipUniverse.Opportunities) opportunities else profile
    return presentPlanBoard(board)
}

fun presentPlanBoard(board: PlanBoard?): PlanBoardUi {
    if (board == null) return pendingBoard("DIP HUNTER", "NOW · DIP", "ALMOST · REVIEW")
    var offRadar = if (board.offRadarAlmost > 0) {
        "${board.offRadarAlmost} more almost off radar"
    } else {
        null
    }
    return PlanBoardUi(
        huntLabel = "DIP HUNTER",
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

fun presentSelectedCrossBoard(
    opportunities: PlanBoard?,
    profile: PlanBoard?,
    universe: PlanDipUniverse,
): PlanBoardUi {
    var board = if (universe == PlanDipUniverse.Opportunities) opportunities else profile
    return presentCrossBoard(board)
}

fun presentCrossBoard(board: PlanBoard?): PlanBoardUi {
    if (board == null) return pendingBoard("CROSS HUNTER", "NOW · CROSS", "ALMOST · REVIEW")
    var offRadar = if (board.offRadarAlmost > 0) {
        "${board.offRadarAlmost} more almost off radar"
    } else {
        null
    }
    return PlanBoardUi(
        huntLabel = "CROSS HUNTER",
        countsLine = "${board.nowCount} now  ·  ${board.later.size} almost  ·  ${board.refuseCount} out",
        offRadarLine = offRadar,
        universeLine = "Universe ${board.universeName}  ·  ${board.scanned} scanned",
        nowTitle = "NOW · CROSS",
        laterTitle = "ALMOST · REVIEW",
        now = board.now.map { presentCard(it, cross = true) },
        later = board.later.map { presentCard(it, cross = true) },
        emptyNow = board.now.isEmpty(),
        emptyNowTitle = "No golden cross now",
        emptyNowDetail = "No name meets F, a fresh MACD golden cross, and Street 20% together.",
    )
}

fun presentLeftoverBoard(board: PlanBoard?): PlanBoardUi {
    if (board == null) return pendingBoard("LEFTOVER REVIEW", "PRIMARY · FADE", "REVIEW · AT TARGET")
    var offRadar = if (board.offRadarAlmost > 0) {
        "${board.offRadarAlmost} more at target off radar"
    } else {
        null
    }
    return PlanBoardUi(
        huntLabel = "LEFTOVER REVIEW",
        countsLine = "${board.nowCount} fade  ·  ${board.later.size} at target  ·  ${board.refuseCount} out",
        offRadarLine = offRadar,
        universeLine = "Universe ${board.universeName}  ·  ${board.scanned} scanned",
        nowTitle = "PRIMARY · FADE",
        laterTitle = "REVIEW · AT TARGET",
        now = board.now.map { presentCard(it, leftover = true) },
        later = board.later.map { presentCard(it, leftover = true) },
        emptyNow = board.now.isEmpty(),
        emptyNowTitle = "No leftover fade",
        emptyNowDetail = "No name meets leftover of 5% or less and a fading tape together.",
    )
}

private fun presentCard(setup: DipSetup, leftover: Boolean = false, cross: Boolean = false): PlanCardUi {
    var streetLabel = when {
        leftover -> LeftoverCopy.streetLine(setup.streetUpsideBps)
        cross -> CrossCopy.streetLine(setup.streetUpsideBps)
        else -> DipCopy.streetLine(setup.streetUpsideBps)
    }
    var fLabel = when {
        leftover -> LeftoverCopy.fLine(setup.fundamentalsScore)
        cross -> CrossCopy.fLine(setup.fundamentalsScore)
        else -> DipCopy.fLine(setup.fundamentalsScore)
    }
    var laneLabel = when {
        leftover && setup.lane == DipLane.Now -> "Fade"
        leftover -> "At target"
        cross && setup.lane == DipLane.Now -> "Cross"
        setup.lane == DipLane.Now -> "Now"
        else -> "Almost"
    }
    var evidence = setup.evidence.filter { line -> line != streetLabel && line != fLabel }
    return PlanCardUi(
        symbol = setup.symbol,
        lane = setup.lane,
        laneLabel = laneLabel,
        deathCross = setup.tags.contains("death_cross"),
        headline = setup.headline,
        evidence = evidence,
        priceLabel = formatDollars(setup.marketPriceCents),
        streetLabel = streetLabel,
        fLabel = fLabel,
        spark = setup.spark,
    )
}
