package com.discountscreener.core.puml

/**
 * Parsed PlantUML activity document. Hunt-agnostic.
 *
 * This dialect is the activity subset this factory accepts: start/stop, partition,
 * assignment, flag, emit, if/elseif/else, split, notes, legend. Anything else fails closed.
 */
data class PumlDocument(
    val title: String,
    val partitions: List<PumlPartition>,
    val legend: List<String>,
    val sourceText: String,
    /** Named lists drawn from notes with a `key, first match:` header. */
    val tables: Map<String, List<String>> = emptyMap(),
    /** English phrase in the diagram mapped to an expression. */
    val aliases: Map<String, PumlExpr> = emptyMap(),
)

data class PumlPartition(
    val name: String,
    val steps: List<PumlStep>,
)

sealed class PumlStep {
    data class Assign(val name: String, val expression: PumlExpr) : PumlStep()

    data class Clear(val name: String) : PumlStep()

    data class BareCall(val phrase: String) : PumlStep()

    data class Flag(val name: String) : PumlStep()

    data class Emit(val label: String, val fields: Map<String, String> = emptyMap()) : PumlStep()

    data object Stop : PumlStep()

    data class Branch(
        val condition: PumlExpr,
        val yes: List<PumlStep>,
        val no: List<PumlStep>,
    ) : PumlStep()

    data class Split(val arms: List<List<PumlStep>>) : PumlStep()
}

sealed class PumlExpr {
    data class Ident(val name: String) : PumlExpr()

    data class Number(val value: Double) : PumlExpr()

    data class Bool(val value: Boolean) : PumlExpr()

    data class Phrase(val text: String) : PumlExpr()

    data class Call(val name: String, val args: List<PumlExpr>) : PumlExpr()

    data class Unary(val op: String, val inner: PumlExpr) : PumlExpr()

    data class Binary(val op: String, val left: PumlExpr, val right: PumlExpr) : PumlExpr()
}
