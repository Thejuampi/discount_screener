package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A structural tripwire for a design rule: the quant engine is independent from the street.
 *
 * The engine computes from the types scanned here and publishes `DcfAnalysis`. If one of those
 * types — or a direct component type such as `WaccInputProvenance` inside `DcfAnalysis` — grows a
 * street-named field, this goes red at test time instead of surfacing at review time. A substring
 * scan over field names cannot prove full independence by itself; it catches the obvious naming
 * before it ships, and the second test proves the scan still fires.
 *
 * Scope note: the forecast bucket is a street family **by design** (`OpportunityEngine` reads
 * `SymbolDetail`'s analyst anchors there), so `SymbolDetail` is deliberately outside this scan.
 * What must stay clean is everything valuation consumes.
 */
class ValuationEngineStreetIndependenceTest {

    @Test
    fun the_types_the_valuation_engine_reads_carry_no_street_fields() {
        assertEquals(
            emptyList(),
            streetViolations(
                FundamentalSnapshot::class.java,
                FundamentalTimeseries::class.java,
                DcfAnalysis::class.java,
                MarketParams::class.java,
            ),
        )
    }

    /** An empty result above means clean only if the scanner itself fires on a known offender. */
    @Test
    fun the_tripwire_catches_a_smuggled_street_field() {
        assertEquals(
            listOf("Smuggled.analysttargetcents"),
            streetViolations(Smuggled::class.java),
        )
    }

    private class Smuggled(val analystTargetCents: Long? = null)

    private fun streetViolations(vararg types: Class<*>): List<String> =
        types.flatMap { type ->
            scanSet(type).flatMap { scanned ->
                scanned.declaredFields
                    .map { it.name.lowercase() }
                    .filter { field -> STREET_MARKERS.any { marker -> field.contains(marker) } }
                    .map { "${scanned.simpleName}.${it}" }
            }
        }.distinct()

    /** The root, its ancestors, and its one-level component types from our own packages. */
    private fun scanSet(root: Class<*>): List<Class<*>> =
        generateSequence(root) { it.superclass }
            .takeWhile { it != Any::class.java }
            .toList() +
            root.declaredFields
                .map { it.type }
                .filter { it.packageName.startsWith("com.discountscreener.core") }
                .distinct()

    private companion object {
        /**
         * Anything matching these substrings on an engine type is street-derived until proven
         * otherwise. Bare `rating` is excluded on purpose: `operatingCashFlow` contains it.
         */
        val STREET_MARKERS = listOf(
            "external", "analyst", "recommendation", "strongbuy", "strongsell",
            "street", "tipranks", "targetprice", "pricetarget", "consensus",
            "ratingbuy", "ratingsell",
        )
    }
}
