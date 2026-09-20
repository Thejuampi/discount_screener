package com.discountscreener.core.portfolio

fun heldTickers(lots: List<PortfolioLot>): Set<String> =
    lots.map { it.symbol.trim().uppercase() }.toSet()

fun isHeld(symbol: String, held: Set<String>): Boolean =
    symbol.trim().uppercase() in held

fun <T> pinHeldFirst(rows: List<T>, held: Set<String>, symbolOf: (T) -> String): List<T> {
    var yes = ArrayList<T>()
    var no = ArrayList<T>()
    for (row in rows) {
        if (isHeld(symbolOf(row), held)) yes.add(row) else no.add(row)
    }
    return yes + no
}
