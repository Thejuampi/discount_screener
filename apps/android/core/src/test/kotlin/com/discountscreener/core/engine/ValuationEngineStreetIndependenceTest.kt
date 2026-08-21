package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The structural half of a design rule: the quant engine is absolutely independent from the street.
 *
 * The engine's compute paths read the types scanned here and nothing else, so if none of those
 * types can carry a street-derived field, no street input can reach an intrinsic value by any
 * route — not as a term, not as a clamp, not as a quiet default. Adding such a field to one of
 * these types turns this red at compile-adjacent time instead of at review time.
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

    @Test
    fun the_output_the_engine_publishes_carries_no_street_fields() {
        assertEquals(
            emptyList(),
            streetViolations(DcfAnalysis::class.java),
        )
    }

    private fun streetViolations(vararg types: Class<*>): List<String> =
        types.flatMap { type ->
            type.declaredFields
                .map { it.name.lowercase() }
                .filter { field -> STREET_MARKERS.any { marker -> field.contains(marker) } }
                .map { "${type.simpleName}.${it}" }
        }

    private companion object {
        /** Anything matching these substrings on an engine type is street-derived until proven otherwise. */
        val STREET_MARKERS = listOf(
            "external", "analyst", "recommendation", "strongbuy", "strongsell",
            "street", "tipranks", "targetprice",
        )
    }
}
