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

    @Test
    fun negative_latest_reported_fcf_keeps_driver_path_usable() {
        var timeseries = usableTimeseries().copy(
            freeCashFlow = listOf(
                AnnualReportedValue("2021-12-31", 100.0),
                AnnualReportedValue("2022-12-31", 120.0),
                AnnualReportedValue("2023-12-31", -1.0),
            ),
        )
        var candidate = candidate(DcfSource.YahooFinance, timeseries)

        assertEquals(true, DcfSourceSelectionPolicy.isDcfUsable(candidate))
    }

    @Test
    fun source_with_aligned_driver_rows_wins_over_priority_only_source() {
        val yahoo = usableTimeseries().copy(
            operatingCashFlow = annual(100.0, 120.0, 140.0),
            capitalExpenditure = annual(-20.0, -24.0, -28.0),
            revenue = annual(500.0, 550.0, 600.0),
        )
        val selection = DcfSourceSelectionPolicy.select(
            yahoo = candidate(DcfSource.YahooFinance, yahoo),
            sec = candidate(DcfSource.SecEdgar, usableTimeseries().copy(
                operatingCashFlow = emptyList(),
                capitalExpenditure = emptyList(),
                revenue = emptyList(),
            )),
        )

        assertEquals(DcfSource.YahooFinance, selection.selectedSource)
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
        operatingCashFlow = annual(150.0, 175.0, 200.0),
        capitalExpenditure = annual(-50.0, -55.0, -60.0),
        revenue = annual(500.0, 550.0, 600.0),
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
        operatingCashFlow = annual(150.0, 175.0, 200.0),
        capitalExpenditure = annual(-50.0, -55.0, -60.0),
        revenue = annual(500.0, 550.0, 600.0),
    )

    private fun annual(vararg values: Double) = values.mapIndexed { index, value ->
        AnnualReportedValue("${2021 + index}-12-31", value)
    }

    private fun analysis() = DcfAnalysis(
        bearIntrinsicValueCents = 8_000L,
        baseIntrinsicValueCents = 10_000L,
        bullIntrinsicValueCents = 12_000L,
        waccBps = 800,
        baseGrowthBps = 500,
        netDebtDollars = 0L,
    )
}
