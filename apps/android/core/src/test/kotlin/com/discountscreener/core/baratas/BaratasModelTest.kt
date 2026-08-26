package com.discountscreener.core.baratas

import com.discountscreener.core.puml.PumlModelFactoryTest
import com.discountscreener.core.runtime.ModelInput
import com.discountscreener.core.runtime.ModelOutput
import com.discountscreener.core.runtime.ModelValue
import kotlin.test.Test
import kotlin.test.assertEquals

class BaratasModelTest {

    @Test
    fun unclassified_is_unavailable_class() {
        var out = evaluateClass("Unclassified")
        assertEquals("class", out.huntReason())
    }

    @Test
    fun not_eligible_is_unavailable_class() {
        var out = evaluateClass("NotEligible")
        assertEquals(listOf("Unavailable", "class"), listOf(out.huntTriage(), out.huntReason()))
    }

    @Test
    fun linear_clocks_follow_the_puml_identities() {
        var out = evaluate(
            eps = listOf(8.0, 9.0, 10.0, 11.0, 12.0),
            price = 100.0,
        )
        assertEquals(listOf(12.0, 13.0), listOf(out.num("eps_now"), out.num("eps_next")))
    }

    @Test
    fun falling_cross_is_unavailable_eps_now() {
        var out = evaluate(eps = listOf(10.0, 6.0, 2.0, -2.0, -6.0), price = 100.0)
        assertEquals("eps_now", out.huntReason())
    }

    @Test
    fun thin_sector_is_single_source() {
        var out = evaluate(
            eps = listOf(8.0, 9.0, 10.0, 11.0, 12.0),
            price = 100.0,
            peerNext = List(4) { 10.0 },
        )
        assertEquals(true, out.flag("SingleSource"))
    }

    @Test
    fun watch_disputed_now_beats_later_tokens() {
        var out = evaluate(
            eps = listOf(16.0, 13.0, 10.0, 7.0, 4.0),
            price = 20.0,
        )
        assertEquals("Disputed(now)", out.huntReason())
    }

    @Test
    fun watch_disputed_next_when_slope_crosses_up() {
        var out = evaluate(
            eps = listOf(8.0, 9.0, 10.0, 11.0, 12.0),
            price = 135.0,
        )
        assertEquals("Disputed(next)", out.huntReason())
    }

    @Test
    fun watch_quality_when_q_is_negative() {
        var out = evaluate(
            eps = listOf(8.0, 9.0, 10.0, 11.0, 12.0),
            price = 80.0,
            q = -0.2,
        )
        assertEquals("quality", out.huntReason())
    }

    @Test
    fun watch_window_short_on_through_cycle_thin_history() {
        var out = evaluate(
            eps = listOf(8.0, 9.0, 10.0, 11.0, 12.0),
            price = 80.0,
            throughCycle = true,
        )
        assertEquals("eps_window_short", out.huntReason())
    }

    private fun evaluateClass(className: String): ModelOutput {
        var model = BaratasModels.load(PumlModelFactoryTest.frozenPumlText())
        return model.evaluate(ModelInput.of("class" to ModelValue.Text(className)))
    }

    private fun evaluate(
        eps: List<Double>,
        price: Double,
        peerNow: List<Double> = List(5) { 10.0 },
        peerNext: List<Double> = List(5) { 10.0 },
        q: Double = 0.2,
        throughCycle: Boolean = false,
    ): ModelOutput {
        var model = BaratasModels.load(PumlModelFactoryTest.frozenPumlText())
        return model.evaluate(
            ModelInput.of(
                "class" to ModelValue.Text("OperatingNonFinancial"),
                "annual_eps" to ModelValue.Series(eps),
                "annual_revenue" to ModelValue.Series(eps.map { it * 10.0 }),
                "price" to ModelValue.Num(price),
                "hunt_on" to ModelValue.Flag(false),
                "through_cycle" to ModelValue.Flag(throughCycle),
                "q" to ModelValue.Num(q),
                "peer_pe_now" to ModelValue.Series(peerNow),
                "peer_pe_next" to ModelValue.Series(peerNext),
            ),
        )
    }
}
