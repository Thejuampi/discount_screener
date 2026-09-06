package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.presentation.dashboard.ValuationJudgmentUi
import com.discountscreener.core.model.SymbolDetail

/**
 * What each line of the valuation card is for. The role picks the style, so the card content stays
 * plain data a test can read.
 */
internal enum class ValuationLineRole {
    Title,
    Price,
    Caveat,
    Reason,
    Gap,
    HonestAnchor,
    BentAnchor,
    BentReason,
    KnobNote,
    Series,
    OwnModel,
    OwnModelNote,
}

internal data class ValuationCardLine(
    val text: String,
    val role: ValuationLineRole,
)

/**
 * Every line the valuation card prints, in order.
 *
 * The card used to build its text inside the composable, where nesting one block in another loop
 * repeated the price and both series once per bent input and no test could see it. The lines are a
 * list now, so [valuationCardLines] carries the ordering and the composable carries only style.
 */
internal fun valuationCardLines(
    detail: SymbolDetail,
    ui: ValuationJudgmentUi,
): List<ValuationCardLine> = buildList {
    add(ValuationCardLine("Valuation", ValuationLineRole.Title))
    var price = ui.lastPriceCents ?: detail.marketPriceCents.takeIf { it > 0L }
    if (price != null) {
        add(ValuationCardLine("${ui.lastPriceLabel} ${money(price)}", ValuationLineRole.Price))
    }
    ui.caveatLines.forEach { line -> add(ValuationCardLine(line, ValuationLineRole.Caveat)) }
    modelReasonLines(ui).forEach { line -> add(ValuationCardLine(line, ValuationLineRole.Reason)) }
    ui.officialGapBps?.let { bps ->
        add(ValuationCardLine("Identity vs analyst $bps bps", ValuationLineRole.Gap))
    }
    honestyPairLines(ui).forEach { line ->
        var role = when (line) {
            ui.honestValueLine -> ValuationLineRole.HonestAnchor
            ui.nonHonestReason -> ValuationLineRole.BentReason
            else -> ValuationLineRole.BentAnchor
        }
        add(ValuationCardLine(line, role))
    }
    ui.nonHonestLines.forEach { line -> add(ValuationCardLine(line, ValuationLineRole.KnobNote)) }
    judgmentReferenceLines(ui).forEach { line ->
        add(ValuationCardLine(line, ValuationLineRole.Series))
    }
    var ownModel = ownModelLines(ui)
    if (ownModel.isNotEmpty()) {
        ownModel.forEach { line -> add(ValuationCardLine(line, ValuationLineRole.OwnModel)) }
        add(ValuationCardLine(ui.horizonPriceNote, ValuationLineRole.OwnModelNote))
    }
}
