package com.discountscreener.android.domain.model

import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.DipSignalEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The memo returns a stale setup only if it misreads an input as unchanged, so the two readings
 * that matter are how many times it evaluates: once for a repeated input, twice for a changed one.
 */
class DipSetupMemoTest {
    private var evaluations = 0
    private val memo = DipSetupMemo { input ->
        evaluations += 1
        DipSignalEngine.evaluate(input)
    }

    @Test
    fun a_repeated_input_is_evaluated_once() {
        var input = input(marketPriceCents = 10_000)

        memo.setup(input)
        memo.setup(input.copy())

        assertEquals(1, evaluations)
    }

    @Test
    fun a_changed_input_is_evaluated_again() {
        memo.setup(input(marketPriceCents = 10_000))
        memo.setup(input(marketPriceCents = 9_000))

        assertEquals(2, evaluations)
    }

    @Test
    fun a_changed_input_returns_the_new_setup() {
        memo.setup(input(marketPriceCents = 10_000))
        var second = memo.setup(input(marketPriceCents = 9_000))

        assertEquals(DipSignalEngine.evaluate(input(marketPriceCents = 9_000)), second)
    }

    @Test
    fun clear_forgets_every_setup() {
        var input = input(marketPriceCents = 10_000)
        memo.setup(input)

        memo.clear()
        memo.setup(input)

        assertEquals(2, evaluations)
    }

    private fun input(marketPriceCents: Long) = DipRowInput(
        symbol = "ACME",
        fundamentalsScore = 70,
        marketPriceCents = marketPriceCents,
        streetFairValueCents = 12_000,
    )
}
