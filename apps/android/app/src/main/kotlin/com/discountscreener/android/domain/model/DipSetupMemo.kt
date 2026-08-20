package com.discountscreener.android.domain.model

import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.DipSetup

/**
 * The last setup evaluated for each symbol, returned again while its input is unchanged.
 *
 * A snapshot evaluates every tracked symbol for two boards, and a load takes a snapshot every few
 * rows. On the largest profile that was two boards times five hundred evaluations, over a hundred
 * times per load, for rows that had not moved. The input is a data class whose lists are the very
 * references the caches hold, so the compare is by identity for an unchanged row and the whole
 * cost falls on the few rows that did change.
 *
 * Not thread-safe: it lives under the repository's state mutex like the caches it reads.
 */
class DipSetupMemo(private val evaluate: (DipRowInput) -> DipSetup) {
    private val setups = HashMap<String, Pair<DipRowInput, DipSetup>>()

    fun setup(input: DipRowInput): DipSetup {
        var cached = setups[input.symbol]
        if (cached != null && cached.first == input) {
            return cached.second
        }
        var setup = evaluate(input)
        setups[input.symbol] = input to setup
        return setup
    }

    fun clear() = setups.clear()
}
