package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.core.portfolio.ImportPlan
import com.discountscreener.core.portfolio.RefuseReason
import com.discountscreener.core.portfolio.isHeld
import com.discountscreener.core.portfolio.pinHeldFirst

fun pinOpportunityRows(rows: List<OpportunityListRow>, held: Set<String>): List<OpportunityListRow> {
    var ranked = rows.mapIndexed { index, row ->
        row.copy(held = isHeld(row.symbol, held), scoreRank = index + 1)
    }
    return pinHeldFirst(ranked, held) { it.symbol }
}

fun pinTrackedRows(rows: List<TrackedSymbolRow>, held: Set<String>): List<TrackedSymbolRow> {
    var marked = rows.map { row -> row.copy(held = isHeld(row.symbol, held)) }
    return pinHeldFirst(marked, held) { it.symbol }
}

fun pinWatchedRows(rows: List<TrackedSymbolRow>): List<TrackedSymbolRow> {
    var watched = rows.filter { it.isWatched }
    var held = watched.filter { it.held }.map { it.symbol }.toSet()
    return pinHeldFirst(watched, held) { it.symbol }
}

fun refuseReasonCode(reason: RefuseReason): String = when (reason) {
    RefuseReason.TradesWithoutBook -> "trades_without_book"
    RefuseReason.MissingBookAsOf -> "missing_book_as_of"
    RefuseReason.EmptyKeep -> "empty_keep"
    RefuseReason.LedgerApplyUnsupported -> "ledger_apply_unsupported"
    RefuseReason.Unreadable -> "unreadable"
    RefuseReason.AsOfUnparseable -> "as_of_unparseable"
}

fun importPlanWarning(plan: ImportPlan): String = when (plan) {
    is ImportPlan.ConfirmHoldingsReplace -> {
        var removedLots = if (plan.remove.size == 1) "lot" else "lots"
        var omit = if (plan.remove.isEmpty()) {
            ""
        } else {
            " Removing: ${plan.remove.joinToString(", ")}."
        }
        "This file is the full book image (${plan.format}). It loads ${plan.positions.size} lots " +
            "and removes ${plan.remove.size} $removedLots the file omits. As-of ${plan.asOf}. " +
            "It ignores ${plan.ignored} rows (${plan.expectedExclusions} expected exclusions, " +
            "${plan.parseFailures} parse failures).$omit"
    }
    is ImportPlan.ConfirmTradesMerge -> {
        var removedLots = if (plan.remove.size == 1) "closed lot" else "closed lots"
        var closed = if (plan.remove.isEmpty()) {
            ""
        } else {
            " Closed lots: ${plan.remove.joinToString(", ")}."
        }
        "This file is a trade blotter (${plan.format}). The app applies trades after ${plan.asOf} " +
            "onto current lots. Trades on or before as-of do not change quantity. " +
            "It applies ${plan.applied} trades, skips ${plan.skipped} trades, and ignores " +
            "${plan.ignored} rows (${plan.expectedExclusions} expected exclusions, " +
            "${plan.parseFailures} parse failures). It removes ${plan.remove.size} $removedLots." +
            "$closed Next as-of ${plan.nextBookAsOf}."
    }
    is ImportPlan.Refuse -> refuseReasonCode(plan.reason)
}
