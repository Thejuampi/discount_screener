package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.SecCompanyFactsSieve
import com.discountscreener.core.engine.SecDriverNormalizationPolicy
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalTimeseries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private const val COMPANY_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json"
private const val COMPANY_FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/"
private const val SEC_USER_AGENT = "DiscountScreener research@discountscreener.com"
private const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1000L
internal const val COMPANY_FACTS_SIEVE_VERSION = "fcff-residual-1"

internal fun companyFactsSlimFileName(cikPadded: String): String =
    "CIK$cikPadded.sieve-$COMPANY_FACTS_SIEVE_VERSION.json"

class SecEdgarTimeseriesProvider(
    private val cacheDir: File? = null,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) : FundamentalTimeseriesProvider, ResidualCompanyFactsProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var tickerToCik: Map<String, String>? = null

    override suspend fun fetch(symbol: String): FundamentalTimeseries? = withContext(Dispatchers.IO) {
        val slim = loadSievedFacts(symbol) ?: return@withContext null
        var facts = json.parseToJsonElement(slim).jsonObject
        buildTimeseries(facts)
    }

    override suspend fun fetchSievedCompanyFacts(symbol: String): String? = withContext(Dispatchers.IO) {
        loadSievedFacts(symbol)
    }

    private fun resolveCik(symbol: String): String? {
        val map = tickerToCik ?: loadTickerMap()
        return map[symbol.uppercase()]
    }

    private fun loadTickerMap(): Map<String, String> {
        return try {
            val body = cachedText("company_tickers.json") {
                val request = Request.Builder()
                    .url(COMPANY_TICKERS_URL)
                    .header("User-Agent", SEC_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { it.body?.string() }
            } ?: return emptyMap()
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

    private fun loadSievedFacts(symbol: String): String? {
        return try {
            val cik = resolveCik(symbol) ?: return null
            val slimFile = cacheDir?.let { File(it, companyFactsSlimFileName(cik)) }
            if (slimFile != null && slimFile.isFile) {
                val age = System.currentTimeMillis() - slimFile.lastModified()
                if (age < ttlMillis) {
                    return slimFile.readText()
                }
            }
            val url = "${COMPANY_FACTS_URL}CIK$cik.json"
            val full = workingFile("CIK$cik.full.json") { sink ->
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", SEC_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@workingFile false
                    response.body?.byteStream()?.use { input -> input.copyTo(sink) }
                    true
                }
            } ?: return null
            try {
                var slim = full.bufferedReader().use { reader -> SecCompanyFactsSieve.sieve(reader) }
                if (slimFile != null) {
                    slimFile.parentFile?.mkdirs()
                    slimFile.writeText(slim)
                }
                slim
            } finally {
                full.delete()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cachedText(name: String, fetch: () -> String?): String? {
        val file = cacheDir?.let { File(it, name) }
        if (file != null && file.isFile) {
            val age = System.currentTimeMillis() - file.lastModified()
            if (age < ttlMillis) {
                return file.readText()
            }
        }
        val body = fetch() ?: return null
        if (file != null) {
            file.parentFile?.mkdirs()
            file.writeText(body)
        }
        return body
    }

    private fun workingFile(name: String, fetchTo: (OutputStream) -> Boolean): File? {
        val dir = cacheDir
        val file = if (dir != null) {
            dir.mkdirs()
            File(dir, name)
        } else {
            File.createTempFile("sec-", ".json").also { it.deleteOnExit() }
        }
        var tmp = File(file.parentFile, "${file.name}.part")
        val ok = tmp.outputStream().use { fetchTo(it) }
        if (!ok) {
            tmp.delete()
            return null
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return file
    }

    private fun buildTimeseries(facts: JsonObject): FundamentalTimeseries? = buildSecEdgarTimeseries(facts)
}

internal fun buildSecEdgarTimeseries(facts: JsonObject): FundamentalTimeseries? {
    val usGaap = facts["facts"]?.jsonObject?.get("us-gaap")?.jsonObject ?: return null

    val opCfRecords = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.OperatingCashFlow),
    )
    val capexRecords = annualRecurringDevelopmentRecords(usGaap)
    val acquisitionRecords = annualAcquisitionInvestmentRecords(usGaap)
    val revenueRecords = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.Revenue),
    )
    val interestRecords = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.InterestExpense),
    )
    val pretaxRecords = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.PretaxIncome),
    )
    val taxExpenseRecords = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.TaxExpense),
    )
    val sharesRecords = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.DilutedAverageShares),
    )
    val debtRecords = annualDebtRecords(usGaap)
    val marginalTaxRecords = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.MarginalTaxReference),
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
        acquisitionInvestment = acquisitionRecords.filter { it.asOfDate in acceptedDates },
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
    operator: SecDriverNormalizationPolicy.DriverOperator,
): List<AnnualReportedValue> {
    val byDate = linkedMapOf<String, AnnualReportedValue>()
    operator.qnames.forEach { concept ->
        annualFyRecords(usGaap, concept, operator).forEach { record ->
            byDate.putIfAbsent(record.asOfDate, record)
        }
    }
    return byDate.values.sortedBy { it.asOfDate }
}

private fun annualRecurringDevelopmentRecords(usGaap: JsonObject): List<AnnualReportedValue> =
    annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.DriverOperator(
            qnames = SecDriverNormalizationPolicy.recurringDevelopmentConcepts,
            unit = "USD",
            periodShape = SecDriverNormalizationPolicy.PeriodShape.Duration,
            operation = "select_one_equivalent",
        ),
    )

private fun annualAcquisitionInvestmentRecords(usGaap: JsonObject): List<AnnualReportedValue> =
    annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.DriverOperator(
            qnames = SecDriverNormalizationPolicy.acquisitionInvestmentConcepts,
            unit = "USD",
            periodShape = SecDriverNormalizationPolicy.PeriodShape.Duration,
            operation = "select_one_equivalent",
        ),
    )

private fun annualDebtRecords(usGaap: JsonObject): List<AnnualReportedValue> {
    val current = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.CurrentDebt),
    )
    val nonCurrent = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.NonCurrentDebt),
    )
    val reportedTotal = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.TotalDebt),
    )
    val currentByDate = current.associateBy { it.asOfDate }
    val nonCurrentByDate = nonCurrent.associateBy { it.asOfDate }
    val components = currentByDate.keys.intersect(nonCurrentByDate.keys)
        .map { date ->
            val currentRecord = requireNotNull(currentByDate[date])
            val nonCurrentRecord = requireNotNull(nonCurrentByDate[date])
            currentRecord.copy(
                value = abs(currentRecord.value) + abs(nonCurrentRecord.value),
                concept = "total_debt",
            )
        }
    return (reportedTotal + components)
        .groupBy { it.asOfDate }
        .map { (_, records) -> records.first() }
        .sortedBy { it.asOfDate }
}

private fun annualFyRecords(
    usGaap: JsonObject,
    concept: String,
    operator: SecDriverNormalizationPolicy.DriverOperator,
): List<AnnualReportedValue> {
    val entries = usGaap[concept]
        ?.jsonObject?.get("units")
        ?.jsonObject?.entries
        ?.firstOrNull { it.key == operator.unit }
        ?.value?.jsonArray
        ?: return emptyList()

    val byDate = mutableMapOf<String, AnnualReportedValue>()
    for (entry in entries) {
        val obj = entry.jsonObject
        val fp = obj["fp"]?.jsonPrimitive?.content ?: continue
        val form = obj["form"]?.jsonPrimitive?.content ?: continue
        if (fp != "FY" || form !in SecDriverNormalizationPolicy.acceptedForms) continue
        val endDate = obj["end"]?.jsonPrimitive?.content ?: continue
        val value = obj["val"]?.jsonPrimitive?.double ?: continue
        val startDate = obj["start"]?.jsonPrimitive?.content
        val durationDays = startDate?.let {
            runCatching {
                ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.parse(endDate)).toInt()
            }.getOrNull()
        }
        val unit = operator.unit
        val consolidated = obj["segment"] == null || obj["segment"] == JsonNull
        if (operator.periodShape == SecDriverNormalizationPolicy.PeriodShape.Duration &&
            durationDays !in SecDriverNormalizationPolicy.minimumDurationDays..
                SecDriverNormalizationPolicy.maximumDurationDays
        ) continue
        if (operator.periodShape == SecDriverNormalizationPolicy.PeriodShape.Instant && startDate != null) continue
        val record = AnnualReportedValue(
            asOfDate = endDate,
            value = value,
            periodStart = startDate,
            periodEnd = endDate,
            durationDays = durationDays,
            fiscalYear = obj["fy"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: endDate.take(4).toIntOrNull(),
            source = com.discountscreener.core.model.DcfSource.SecEdgar,
            concept = concept,
            unit = unit,
            filedAt = obj["filed"]?.jsonPrimitive?.content,
        )
        // Investment concepts cross the stronger, generated policy boundary.
        // Property/business-acquisition facts never become CapEx merely because
        // their cash-flow period looks annual.
        val investmentCategory = SecDriverNormalizationPolicy.investmentCategory(concept)
        if (investmentCategory == SecDriverNormalizationPolicy.InvestmentCategory.Development &&
            !SecDriverNormalizationPolicy.isAcceptedRecurringDevelopment(
                concept, record.unit, record.durationDays, form, consolidated,
            )
        ) continue
        if (!consolidated) continue
        val existing = byDate[endDate]
        // Restated/comparative observations are equivalent, never additive. SEC
        // filing date is primary precedence; accession is the stable tie-break.
        if (existing == null || record.filedAt.orEmpty() > existing.filedAt.orEmpty()) {
            byDate[endDate] = record
        }
    }
    return byDate.values.sortedBy { it.asOfDate }
}
