package com.discountscreener.core.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValuationFcffQaContractTest {
    @Test
    fun `nine-name QA evidence corpus is policy-versioned and keeps anchors out of inputs`() {
        val text = Files.readString(findContract())
        assertTrue(text.contains("\"modelPolicyVersion\": \"$MODEL_POLICY_VERSION\""))
        val orderedSymbols = Regex("\"symbol\"\\s*:\\s*\"([A-Z]+)\"")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            listOf("DVN", "MU", "GDDY", "BR", "BSX", "ADSK", "AVGO", "JBL", "HPE"),
            orderedSymbols,
        )
        assertTrue(text.contains("\"forbidden\": [\"market_price\", \"analyst_target\""))
        assertTrue(text.contains("\"baseMargin\": \"median_aligned_annual_fcff_margin\""))
        assertTrue(text.contains("\"provisionalWaccUpliftBps\": 175"))
    }

    private fun findContract(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve("shared/contracts/valuation-fcff-qa-2026-07-31.json")
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("shared FCFF QA contract not found")
    }
}
