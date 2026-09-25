package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelValue
import kotlin.test.Test
import kotlin.test.assertEquals

class StandardPumlHostTest {

    @Test
    fun ramp_midpoint_is_zero() {
        var value = StandardPumlHost().call(
            "ramp",
            listOf(ModelValue.Num(5.0), ModelValue.Num(0.0), ModelValue.Num(10.0)),
            mutableMapOf(),
            emptyDocument(),
        )
        assertEquals(0.0, value.asNum())
    }

    @Test
    fun classify_banks_as_financial_services() {
        var value = StandardPumlHost().call(
            "classify",
            listOf(ModelValue.Text("Financial Services"), ModelValue.Text("Banks")),
            mutableMapOf(),
            emptyDocument(),
        )
        assertEquals("FinancialServices", value.asText())
    }

    private fun emptyDocument(): PumlDocument = ActivityPumlModelFactory.load(
        PumlEngineTest.tinySource(),
        object : PumlHost {
            override fun evaluate(
                phrase: String,
                env: MutableMap<String, ModelValue>,
            ): ModelValue = ModelValue.Missing
        },
    ).document
}
