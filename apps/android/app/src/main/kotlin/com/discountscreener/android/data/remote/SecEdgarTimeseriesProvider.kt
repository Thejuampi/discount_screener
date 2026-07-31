package com.discountscreener.android.data.remote

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalTimeseries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private const val COMPANY_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json"
private const val COMPANY_FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/"
private const val SEC_USER_AGENT = "DiscountScreener research@discountscreener.com"

class SecEdgarTimeseriesProvider : FundamentalTimeseriesProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var tickerToCik: Map<String, String>? = null

    override suspend fun fetch(symbol: String): FundamentalTimeseries? = withContext(Dispatchers.IO) {
        val cik = resolveCik(symbol) ?: return@withContext null
        val facts = fetchCompanyFacts(cik) ?: return@withContext null
        buildTimeseries(facts)
    }

    private fun resolveCik(symbol: String): String? {
        val map = tickerToCik ?: loadTickerMap()
        return map[symbol.uppercase()]
    }

    private fun loadTickerMap(): Map<String, String> {
        return try {
            val request = Request.Builder()
                .url(COMPANY_TICKERS_URL)
                .header("User-Agent", SEC_USER_AGENT)
                .build()
            val body = client.newCall(request).execute().use { it.body?.string() } ?: return emptyMap()
            val root = json.parseToJsonElement(body).jsonObject
            val map = mutableMapOf<String, String>()
            for ((_, entry) in root) {
                val obj = entry.jsonObject
                val ticker = obj["ticker"]?.jsonPrimitive?.content?.uppercase() ?: continue
                val cikVal = obj["cik_str"]?.jsonPrimitive?.int ?: continue
                map[ticker] = cikVal.toString().padStart(10, '0')
            }
            tickerToCik = map
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun fetchCompanyFacts(cikPadded: String): JsonObject? {
        return try {
            val url = "${COMPANY_FACTS_URL}CIK$cikPadded.json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SEC_USER_AGENT)
                .build()
            val body = client.newCall(request).execute().use { it.body?.string() } ?: return null
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun buildTimeseries(facts: JsonObject): FundamentalTimeseries? = buildSecEdgarTimeseries(facts)
}

internal fun buildSecEdgarTimeseries(facts: JsonObject): FundamentalTimeseries? {
    val usGaap = facts["facts"]?.jsonObject?.get("us-gaap")?.jsonObject ?: return null

    val opCfRecords = annualFyRecordsAny(
        usGaap,
        listOf(
            "NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperationsIncludingDiscontinuedOperation",
        ),
    )
    val capexRecords = annualFyRecordsAny(
        usGaap,
        listOf(
            "PaymentsToAcquirePropertyPlantAndEquipment",
            "PaymentsToAcquireProductiveAssets",
            "PaymentsToAcquireOtherPropertyPlantAndEquipment",
            "PaymentsForCapitalImprovements",
        ),
    )
    val revenueRecords = annualFyRecordsAny(
        usGaap,
        listOf(
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "Revenues",
            "SalesRevenueNet",
            "SalesRevenueGoodsNet",
            "RevenueFromContractWithCustomerIncludingAssessedTax",
            "RevenuesFromExternalCustomers",
        ),
    )
    val interestRecords = annualFyRecordsAny(
        usGaap,
        listOf(
            "InterestExpenseNonOperating",
            "InterestExpenseNonoperating",
            "InterestExpenseDebt",
            "InterestAndDebtExpense",
            "InterestExpense",
            "InterestExpenseOtherLongTermDebt",
            "InterestIncomeExpenseNet",
            "InterestIncomeExpenseNonoperatingNet",
            "FinanceLeaseInterestExpense",
            "InterestPaidNet",
        ),
    )
    val pretaxRecords = annualFyRecordsAny(
        usGaap,
        listOf(
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxes",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesDomestic",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesForeign",
        ),
    )
    val taxExpenseRecords = annualFyRecordsAny(
        usGaap,
        listOf("IncomeTaxExpenseBenefit", "IncomeTaxExpenseBenefitContinuingOperations"),
    )
    val sharesRecords = annualFyRecords(usGaap, "WeightedAverageNumberOfDilutedSharesOutstanding")
    val debtRecords = annualDebtRecords(usGaap)
    val marginalTaxRecords = annualFyRecordsAny(
        usGaap,
        listOf(
            "IncomeTaxReconciliationAtFederalStatutoryIncomeTaxRate",
            "IncomeTaxReconciliationFederalStatutoryIncomeTaxRate",
            "EffectiveIncomeTaxRateReconciliationAtFederalStatutoryIncomeTaxRate",
            "StatutoryFederalIncomeTaxRate",
            "StatutoryIncomeTaxRate",
        ),
    )

    if (opCfRecords.isEmpty() || capexRecords.isEmpty()) return null

    val capexByDate = capexRecords.associate { it.asOfDate to it.value }
    val acceptedOperatingCashFlow = opCfRecords.filter { opCf -> capexByDate.containsKey(opCf.asOfDate) }
    if (acceptedOperatingCashFlow.isEmpty()) return null

    val acceptedDates = acceptedOperatingCashFlow.map { it.asOfDate }.toSet()
    val freeCashFlow = acceptedOperatingCashFlow.map { opCf ->
        val capexOutflow = Math.abs(requireNotNull(capexByDate[opCf.asOfDate]))
        AnnualReportedValue(asOfDate = opCf.asOfDate, value = opCf.value - capexOutflow)
    }
    val capitalExpenditure = capexRecords
        .filter { capex -> capex.asOfDate in acceptedDates }
        .map { capex -> capex.copy(value = -Math.abs(capex.value)) }
    val revenue = revenueRecords.filter { it.asOfDate in acceptedDates }
    val interestExpense = interestRecords.filter { it.asOfDate in acceptedDates }
    val pretaxIncome = pretaxRecords.filter { it.asOfDate in acceptedDates }
    val pretaxByDate = pretaxIncome.associateBy { it.asOfDate }
    val taxRateForCalcs = taxExpenseRecords
        .filter { it.asOfDate in acceptedDates }
        .mapNotNull { tax ->
            val pretax = pretaxByDate[tax.asOfDate]?.value ?: return@mapNotNull null
            if (abs(pretax) <= 0.0) return@mapNotNull null
            AnnualReportedValue(tax.asOfDate, abs(tax.value) / abs(pretax))
        }
    val totalDebt = debtRecords.filter { it.asOfDate in acceptedDates }
    val marginalTaxRate = marginalTaxRecords.filter { it.asOfDate in acceptedDates }

    return FundamentalTimeseries(
        freeCashFlow = freeCashFlow,
        operatingCashFlow = acceptedOperatingCashFlow,
        capitalExpenditure = capitalExpenditure,
        revenue = revenue,
        dilutedAverageShares = sharesRecords,
        interestExpense = interestExpense,
        pretaxIncome = pretaxIncome,
        taxRateForCalcs = taxRateForCalcs,
        totalDebt = totalDebt,
        marginalTaxRate = marginalTaxRate,
    )
}

private fun annualFyRecordsAny(
    usGaap: JsonObject,
    concepts: List<String>,
): List<AnnualReportedValue> {
    val byDate = linkedMapOf<String, AnnualReportedValue>()
    concepts.forEach { concept ->
        annualFyRecords(usGaap, concept).forEach { record ->
            byDate.putIfAbsent(record.asOfDate, record)
        }
    }
    return byDate.values.sortedBy { it.asOfDate }
}

private fun annualDebtRecords(usGaap: JsonObject): List<AnnualReportedValue> {
    val current = annualFyRecordsAny(
        usGaap,
        listOf(
            "LongTermDebtAndFinanceLeaseObligationsCurrent",
            "LongTermDebtCurrent",
            "DebtCurrent",
            "ShortTermBorrowings",
        ),
    )
    val nonCurrent = annualFyRecordsAny(
        usGaap,
        listOf(
            "LongTermDebtAndFinanceLeaseObligationsNoncurrent",
            "LongTermDebtNoncurrent",
        ),
    )
    val reportedTotal = annualFyRecordsAny(
        usGaap,
        listOf(
            "DebtAndCapitalLeaseObligations",
            "LongTermDebtAndCapitalLeaseObligations",
            "LongTermDebt",
            "DebtInstrumentCarryingAmount",
        ),
    )
    val components = (current + nonCurrent)
        .groupBy { it.asOfDate }
        .map { (_, records) ->
            records.first().copy(
                value = records.sumOf { abs(it.value) },
                concept = "total_debt",
            )
        }
    return (components + reportedTotal)
        .groupBy { it.asOfDate }
        .map { (_, records) -> records.maxByOrNull { abs(it.value) }!! }
        .sortedBy { it.asOfDate }
}

private fun annualFyRecords(usGaap: JsonObject, concept: String): List<AnnualReportedValue> {
    val entries = usGaap[concept]
        ?.jsonObject?.get("units")
        ?.jsonObject?.entries
        ?.firstOrNull()
        ?.value?.jsonArray
        ?: return emptyList()

    val byDate = mutableMapOf<String, AnnualReportedValue>()
    for (entry in entries) {
        val obj = entry.jsonObject
        val fp = obj["fp"]?.jsonPrimitive?.content ?: continue
        val form = obj["form"]?.jsonPrimitive?.content ?: continue
        if (fp != "FY" || !form.startsWith("10-K")) continue
        val endDate = obj["end"]?.jsonPrimitive?.content ?: continue
        val value = obj["val"]?.jsonPrimitive?.double ?: continue
        if (!byDate.containsKey(endDate)) {
            val startDate = obj["start"]?.jsonPrimitive?.content
            val durationDays = startDate?.let {
                runCatching {
                    ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.parse(endDate)).toInt()
                }.getOrNull()
            }
            byDate[endDate] = AnnualReportedValue(
                asOfDate = endDate,
                value = value,
                periodStart = startDate,
                periodEnd = endDate,
                durationDays = durationDays,
                fiscalYear = obj["fy"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: endDate.take(4).toIntOrNull(),
                source = com.discountscreener.core.model.DcfSource.SecEdgar,
                concept = concept,
                unit = usGaap[concept]?.jsonObject?.get("units")?.jsonObject?.keys?.firstOrNull(),
                filedAt = obj["filed"]?.jsonPrimitive?.content,
            )
        }
    }
    return byDate.values.sortedBy { it.asOfDate }
}
