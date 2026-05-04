package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DcfSourceCandidate
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ProviderDecisionReasonCode
import com.discountscreener.core.model.RefreshDisposition
import com.discountscreener.core.model.ResolverState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DcfSourceSelectionPolicyTest {
    @Test
    fun sec_is_selected_when_yahoo_and_sec_are_both_dcf_usable() {
        var selection = DcfSourceSelectionPolicy.select(
            yahoo = candidate(DcfSource.YahooFinance, usableTimeseries()),
            sec = candidate(DcfSource.SecEdgar, usableTimeseries()),
        )

        assertEquals(DcfSource.SecEdgar, selection.selectedSource)
    }

    @Test
    fun yahoo_is_selected_when_sec_is_not_dcf_usable() {
        var selection = DcfSourceSelectionPolicy.select(
            yahoo = candidate(DcfSource.YahooFinance, usableTimeseries()),
            sec = candidate(DcfSource.SecEdgar, unusableTimeseries()),
        )

        assertEquals(DcfSource.YahooFinance, selection.selectedSource)
    }

    @Test
    fun no_input_is_selected_when_both_sources_are_unusable() {
        var selection = DcfSourceSelectionPolicy.select(
            yahoo = candidate(DcfSource.YahooFinance, unusableTimeseries()),
            sec = candidate(DcfSource.SecEdgar, unusableTimeseries()),
        )

        assertNull(selection.timeseries)
    }

    @Test
    fun no_configured_providers_returns_blocked_unavailable_state() {
        var selection = DcfSourceSelectionPolicy.select()

        assertEquals(ResolverState.Unavailable, selection.resolverState)
        assertEquals(RefreshDisposition.BlockedUntilProviderEnabled, selection.refreshDisposition)
        assertEquals(
            listOf(ProviderDecisionReasonCode.ProviderConfigurationAbsent, ProviderDecisionReasonCode.NoEnabledProviders),
            selection.reasons.map { it.code },
        )
    }

    @Test
    fun materially_divergent_usable_sources_return_provider_uncertain() {
        var selection = DcfSourceSelectionPolicy.select(
            yahoo = candidate(DcfSource.YahooFinance, usableTimeseries()),
            sec = candidate(DcfSource.SecEdgar, divergentTimeseries()),
        )

        assertEquals(ResolverState.ProviderUncertain, selection.resolverState)
        assertEquals(listOf(ProviderDecisionReasonCode.ProviderDisagreement), selection.reasons.map { it.code })
    }

    @Test
    fun latest_non_positive_free_cash_flow_is_not_dcf_usable() {
        var candidate = candidate(DcfSource.YahooFinance, unusableTimeseries())

        assertFalse(DcfSourceSelectionPolicy.isDcfUsable(candidate))
    }

    private fun candidate(
        source: DcfSource,
        timeseries: FundamentalTimeseries,
    ) = DcfSourceCandidate(
        source = source,
        timeseries = timeseries,
        analysis = analysis(),
    )

    private fun usableTimeseries() = FundamentalTimeseries(
        freeCashFlow = listOf(
            AnnualReportedValue("2021-12-31", 100.0),
            AnnualReportedValue("2022-12-31", 120.0),
            AnnualReportedValue("2023-12-31", 140.0),
        ),
    )

    private fun unusableTimeseries() = FundamentalTimeseries(
        freeCashFlow = listOf(
            AnnualReportedValue("2021-12-31", 100.0),
            AnnualReportedValue("2022-12-31", 120.0),
            AnnualReportedValue("2023-12-31", -1.0),
        ),
    )

    private fun divergentTimeseries() = FundamentalTimeseries(
        freeCashFlow = listOf(
            AnnualReportedValue("2021-12-31", 100.0),
            AnnualReportedValue("2022-12-31", 120.0),
            AnnualReportedValue("2023-12-31", 180.0),
        ),
    )

    private fun analysis() = DcfAnalysis(
        bearIntrinsicValueCents = 8_000L,
        baseIntrinsicValueCents = 10_000L,
        bullIntrinsicValueCents = 12_000L,
        waccBps = 800,
        baseGrowthBps = 500,
        netDebtDollars = 0L,
    )
}