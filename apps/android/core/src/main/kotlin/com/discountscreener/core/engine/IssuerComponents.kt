package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue

data class OperatingComponentDrivers(
    val revenue: List<AnnualReportedValue>,
    val ebit: List<AnnualReportedValue>,
    val capex: List<AnnualReportedValue>,
    val interest: List<AnnualReportedValue>,
    val debt: List<AnnualReportedValue>,
    val cash: List<AnnualReportedValue> = emptyList(),
    val da: List<AnnualReportedValue> = emptyList(),
)

data class FinancialComponentDrivers(
    val bookEquity: List<AnnualReportedValue>,
    val netIncome: List<AnnualReportedValue>,
    val dividends: List<AnnualReportedValue> = emptyList(),
    val source: String,
    val cash: List<AnnualReportedValue> = emptyList(),
    val retentionBps: Int? = null,
)

data class IssuerComponentSet(
    val operating: OperatingComponentDrivers?,
    val financial: FinancialComponentDrivers?,
    val provenance: List<String>,
    val financeArmMaterial: Boolean = financial != null,
) {
    fun isMixed(): Boolean = operating != null && financial != null
    fun missingLenderBook(): Boolean = financeArmMaterial && financial == null
}

object IssuerComponentAssembler {
    private val EBIT_CONCEPTS = listOf(
        "EarningsLossBeforeAutomotiveInterestAndTaxesAdjusted",
        "OperatingIncomeLoss",
        "SegmentReportingInformationOperatingIncomeLoss",
    )
    private val REVENUE_CONCEPTS = listOf(
        "Revenues",
        "RevenueFromContractWithCustomerExcludingAssessedTax",
        "RevenueNotFromContractWithCustomer",
    )
    private val CAPEX_CONCEPTS = listOf("SegmentExpenditureAdditionToLongLivedAssets")
    private val DA_CONCEPTS = listOf(
        "DepreciationDepletionAndAmortization",
        "Depreciation",
    )
    private val INTEREST_CONCEPTS = listOf("InterestExpenseNonoperating", "InterestExpense")
    private val DEBT_CONCEPTS = listOf(
        "DebtandFinanceLeaseLiabilities",
        "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities",
        "LongTermDebtAndCapitalLeaseObligations",
    )

    fun fromParentFacts(
        facts: List<TaggedFact>,
        finance: FinancialComponentDrivers?,
    ): IssuerComponentSet {
        var ebit = subtractOrDirect(facts, EBIT_CONCEPTS)
        var revenue = subtractOrDirect(facts, REVENUE_CONCEPTS)
        var capex = subtractOrDirect(facts, CAPEX_CONCEPTS)
        var da = subtractOrDirect(facts, DA_CONCEPTS)
        var interest = roleSeries(facts, INTEREST_CONCEPTS, ComponentFactRole.Operating)
        var debt = roleSeries(facts, DEBT_CONCEPTS, ComponentFactRole.Operating)
        var operating = if (ebit.size >= 1 && revenue.size >= 1 && capex.size >= 1) {
            OperatingComponentDrivers(revenue, ebit, capex, interest, debt, da = da)
        } else {
            null
        }
        var financeRevenue = roleSeries(facts, REVENUE_CONCEPTS, ComponentFactRole.Financial)
        var financeEbit = roleSeries(facts, EBIT_CONCEPTS, ComponentFactRole.Financial)
        var totalRevenue = latest(roleSeries(facts, REVENUE_CONCEPTS, ComponentFactRole.TotalOperatingSegments))
            ?: latest(revenue)
        var totalEbit = latest(roleSeries(facts, EBIT_CONCEPTS, ComponentFactRole.TotalOperatingSegments))
            ?: latest(ebit)
        var financeLatest = latest(financeRevenue)
        var financeEbitLatest = latest(financeEbit)
        var materialRevenue = totalRevenue != null && financeLatest != null && totalRevenue > 0.0 &&
            ((kotlin.math.abs(financeLatest) / totalRevenue) * 10_000.0) >= ComponentFamilyPolicy.MATERIAL_REVENUE_BPS
        var materialEbit = totalEbit != null && financeEbitLatest != null && kotlin.math.abs(totalEbit) > 0.0 &&
            ((kotlin.math.abs(financeEbitLatest) / kotlin.math.abs(totalEbit)) * 10_000.0) >=
            ComponentFamilyPolicy.MATERIAL_REVENUE_BPS
        var material = materialRevenue || materialEbit
        var provenance = buildList {
            add("component_sotp=$COMPONENT_SOTP_VERSION")
            add("source=parent_xbrl_dimensions")
            if (finance != null) add("finance_source=${finance.source}")
            if (material) add("finance_arm=material")
        }
        var financial = if (material) finance else null
        return IssuerComponentSet(
            operating = operating,
            financial = financial,
            provenance = provenance,
            financeArmMaterial = material,
        )
    }

    fun financeFromResidualFacts(companyFactsJson: String, source: String): FinancialComponentDrivers? {
        var drivers = SecResidualFacts.extract(companyFactsJson) ?: return null
        var dividends = drivers.dividendsDollars?.let { listOf(AnnualReportedValue(drivers.fiscalEnd, it)) }
            ?: emptyList()
        var cash = drivers.cashDollars?.let { listOf(AnnualReportedValue(drivers.fiscalEnd, it)) }
            ?: emptyList()
        return FinancialComponentDrivers(
            bookEquity = listOf(AnnualReportedValue(drivers.fiscalEnd, drivers.bookEquityDollars)),
            netIncome = listOf(AnnualReportedValue(drivers.fiscalEnd, drivers.netIncomeDollars)),
            dividends = dividends,
            source = source,
            cash = cash,
            retentionBps = drivers.retentionBps,
        )
    }

    private fun subtractOrDirect(facts: List<TaggedFact>, concepts: List<String>): List<AnnualReportedValue> {
        var total = byEnd(facts, concepts, ComponentFactRole.TotalOperatingSegments)
        var finance = byEnd(facts, concepts, ComponentFactRole.Financial)
        if (total.isNotEmpty() && finance.isNotEmpty()) {
            return total.keys.intersect(finance.keys).map { end ->
                AnnualReportedValue(end, total.getValue(end) - finance.getValue(end))
            }.sortedBy { it.asOfDate }
        }
        return roleSeries(facts, concepts, ComponentFactRole.Operating)
    }

    private fun roleSeries(
        facts: List<TaggedFact>,
        concepts: List<String>,
        role: ComponentFactRole,
    ): List<AnnualReportedValue> {
        return byEnd(facts, concepts, role)
            .map { (end, value) -> AnnualReportedValue(end, value) }
            .sortedBy { it.asOfDate }
    }

    private fun byEnd(
        facts: List<TaggedFact>,
        concepts: List<String>,
        role: ComponentFactRole,
    ): Map<String, Double> {
        var out = linkedMapOf<String, Double>()
        for (concept in concepts) {
            var hits = facts.filter {
                it.concept.substringAfterLast(':') == concept &&
                    ComponentFamilyPolicy.role(it.members) == role
            }
            if (hits.isEmpty()) continue
            var chosen = linkedMapOf<String, TaggedFact>()
            for (hit in hits) {
                var current = chosen[hit.periodEnd]
                if (current == null ||
                    hit.members.size < current.members.size ||
                    (hit.members.size == current.members.size &&
                        kotlin.math.abs(hit.value) > kotlin.math.abs(current.value))
                ) {
                    chosen[hit.periodEnd] = hit
                }
            }
            for ((end, hit) in chosen) {
                out.putIfAbsent(end, hit.value)
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    private fun latest(rows: List<AnnualReportedValue>): Double? = rows.maxByOrNull { it.asOfDate }?.value
}
