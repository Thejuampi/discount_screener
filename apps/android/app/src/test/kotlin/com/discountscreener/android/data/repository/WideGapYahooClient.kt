package com.discountscreener.android.data.repository

import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.MarketSnapshot

/**
 * An offline Yahoo whose rows can earn a tag and a gap.
 *
 * The shared [OfflineYahooClient] prices every name 2.5 % under its fair value with no analyst
 * behind it, which the engine reads as GapTooSmall on Low confidence and no named primary, so every
 * row comes out untagged and with no upside whatever a test does around it. A wide gap and a full
 * analyst range give the judgment a primary to name, so the rows carry the numbers a test can
 * compare. It does not give every row one: the judgment still refuses the names whose DCF and
 * analyst range disagree, which is why a test that depends on tagged rows has to measure how many
 * it got rather than assume.
 */
internal open class WideGapYahooClient : OfflineYahooClient(candlesPerChart = CANDLES) {
    override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
        var base = super.fetchSymbol(symbol)
        var price = base.snapshot?.marketPriceCents ?: return base
        var fair = price * FAIR_VALUE_MULTIPLE
        return base.copy(
            snapshot = MarketSnapshot(
                symbol = symbol,
                companyName = "$symbol Holdings",
                profitable = true,
                marketPriceCents = price,
                intrinsicValueCents = fair,
            ),
            externalSignal = ExternalValuationSignal(
                symbol = symbol,
                fairValueCents = fair,
                ageSeconds = 0,
                lowFairValueCents = fair - price / 10,
                highFairValueCents = fair + price / 10,
                analystOpinionCount = ANALYSTS,
                weightedFairValueCents = fair,
                weightedAnalystCount = ANALYSTS,
            ),
            coverage = ProviderCoverage(
                core = ProviderComponentState.Fresh,
                external = ProviderComponentState.Fresh,
                fundamentals = ProviderComponentState.Fresh,
            ),
        )
    }

    private companion object {
        const val CANDLES = 5

        /** Priced at half of fair value, so the gap gate passes and the row is worth a call. */
        const val FAIR_VALUE_MULTIPLE = 2L
        const val ANALYSTS = 16
    }
}
