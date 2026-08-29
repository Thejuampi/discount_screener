package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.SecCompanyFactsSieve
import com.discountscreener.core.engine.SecDriverNormalizationPolicy
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfSource
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
import com.discountscreener.core.earnings.EarningsAnnouncement
import com.discountscreener.core.earnings.parseEarningsAnnouncements
import okhttp3.Response
import kotlin.math.abs
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private const val COMPANY_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json"
private const val COMPANY_FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/"
private const val SUBMISSIONS_URL = "https://data.sec.gov/submissions/"
private const val SEC_USER_AGENT = "DiscountScreener research@discountscreener.com"
private const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1000L
/**
 * Bumped whenever the sieve keeps a different set of facts: a new concept, a wider period or form,
 * or another field per fact. A slim cache written by an older version has the old shape and no way
 * to say so, so it would answer "this company reports no impairment" for a file that simply never
 * looked. The name changes, and the old file is left to expire.
 */
internal const val COMPANY_FACTS_SIEVE_VERSION = "fcff-annual-consolidated-1"
private const val VALIDATOR_SUFFIX = ".etag"
private const val NOT_MODIFIED = 304

/** SEC answers an exceeded limit with 403 and a `Retry-After` header, not with 429. */
private const val SEC_RATE_LIMITED_CODE = 403
private const val TOO_MANY_REQUESTS = 429

/** A companyfacts file is about 4 MB. Eight at once is 32 MB over a phone's connection. */
private const val SEC_MAX_IN_FLIGHT = 4

internal fun companyFactsSlimFileName(cikPadded: String): String =
    "CIK$cikPadded.sieve-$COMPANY_FACTS_SIEVE_VERSION.json"

/**
 * Write the whole text, or leave the file as it was.
 *
 * Two clients write this name. A process that stops in the middle of a plain write leaves a
 * truncated file that both of them then read as the company's facts for the rest of the day.
 */
internal fun writeAtomically(target: File, text: String) {
    var partial = File(target.parentFile, target.name + ".part")
    partial.writeText(text)
    if (!partial.renameTo(target)) {
        target.delete()
        partial.renameTo(target)
    }
}

class SecEdgarTimeseriesProvider(
    private val cacheDir: File? = null,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) : FundamentalTimeseriesProvider, ResidualCompanyFactsProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * SEC's own governor, separate from Yahoo's.
     *
     * SEC publishes a limit of ten requests a second and answers over it with a 403 that carries
     * `Retry-After`. Sharing Yahoo's governor would make one host's refusal close the other host's
     * window, which is a limit invented by the client. The window is small because a companyfacts
     * file is about four megabytes: eight of them at once is thirty-two megabytes over a phone's
     * connection, and nothing on screen wants more than one.
     */
    private val governor = RequestGovernor(window = AdaptiveRequestWindow(maxWindow = SEC_MAX_IN_FLIGHT))

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

    /**
     * Every past earnings announcement of one ticker, dated by the company itself.
     *
     * An 8-K carrying item 2.02 is the results release. Its acceptance timestamp says whether the
     * report landed before the open or after the close, which is what decides the reaction window.
     */
    suspend fun earningsAnnouncements(symbol: String): List<EarningsAnnouncement> =
        withContext(Dispatchers.IO) {
            var cik = resolveCik(symbol) ?: return@withContext emptyList()
            var body = cachedText("CIK$cik.submissions.json") {
                var request = Request.Builder()
                    .url("${SUBMISSIONS_URL}CIK$cik.json")
                    .header("User-Agent", SEC_USER_AGENT)
                    .build()
                governedText(request)
            } ?: return@withContext emptyList()
            parseEarningsAnnouncements(body)
        }

    private suspend fun resolveCik(symbol: String): String? {
        val map = tickerToCik ?: loadTickerMap()
        return map[symbol.uppercase()]
    }

    private suspend fun loadTickerMap(): Map<String, String> {
        return try {
            val body = cachedText("company_tickers.json") {
                val request = Request.Builder()
                    .url(COMPANY_TICKERS_URL)
                    .header("User-Agent", SEC_USER_AGENT)
                    .build()
                governedText(request)
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

    /**
     * The sieved facts for one symbol, from the cache when it is fresh and from SEC when it is not.
     *
     * Two costs decide the shape of this. A companyfacts file is about 4 MB and the sieve keeps
     * about 3% of it, so the answer is never written whole: it is sieved as it arrives. And an
     * expired cache does not mean a changed filing, so the refresh asks conditionally. A company
     * that filed nothing new answers 304 with no body, and the file already on disk is kept.
     */
    private suspend fun loadSievedFacts(symbol: String): String? {
        return try {
            val cik = resolveCik(symbol) ?: return null
            val slimFile = cacheDir?.let { File(it, companyFactsSlimFileName(cik)) }
            if (slimFile != null && slimFile.isFile) {
                val age = System.currentTimeMillis() - slimFile.lastModified()
                if (age < ttlMillis) {
                    return slimFile.readText()
                }
            }
            val validatorFile = slimFile?.let { File(it.parentFile, it.name + VALIDATOR_SUFFIX) }
            val request = Request.Builder()
                .url("${COMPANY_FACTS_URL}CIK$cik.json")
                .header("User-Agent", SEC_USER_AGENT)
                .apply {
                    val validator = validatorFile?.takeIf { it.isFile }?.readText()?.trim()
                    if (!validator.isNullOrBlank() && slimFile?.isFile == true) {
                        header("If-None-Match", validator)
                    }
                }
                .build()
            governor.request {
                client.newCall(request).execute().use { response ->
                    val refusal = refusalOf(response)
                    if (refusal != null) return@use refusal
                    if (response.code == NOT_MODIFIED && slimFile != null && slimFile.isFile) {
                        slimFile.setLastModified(System.currentTimeMillis())
                        return@use RequestGovernor.Attempt.Ok(slimFile.readText())
                    }
                    if (!response.isSuccessful) {
                        return@use RequestGovernor.Attempt.Failed(
                            retryable = false,
                            error = IOException("SEC HTTP ${response.code} for ${request.url}"),
                        )
                    }
                    val body = response.body
                        ?: return@use RequestGovernor.Attempt.Failed(false, IOException("empty SEC body"))
                    // Sieved as it arrives: the whole file is about 4 MB and 3% of it is kept, so
                    // it is never held in memory whole and never written whole.
                    val slim = body.charStream().use { reader -> SecCompanyFactsSieve.sieve(reader) }
                    if (slimFile != null) {
                        slimFile.parentFile?.mkdirs()
                        writeAtomically(slimFile, slim)
                        response.header("ETag")?.let { tag -> validatorFile?.writeText(tag) }
                    }
                    RequestGovernor.Attempt.Ok(slim)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One governed request that only has to come back as text. Used for the ticker map, which is a
     * small file asked for once a day.
     */
    private suspend fun governedText(request: Request): String? = governor.request {
        client.newCall(request).execute().use { response ->
            refusalOf(response)
                ?: RequestGovernor.Attempt.Ok(response.takeIf { it.isSuccessful }?.body?.string())
        }
    }

    /**
     * SEC answers an exceeded limit with 403 and a `Retry-After`, not with 429. Reading only the
     * code would make its clearest instruction look like a permanent refusal.
     */
    private fun refusalOf(response: Response): RequestGovernor.Attempt<Nothing>? {
        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1_000L)
        val overLimit = response.code == SEC_RATE_LIMITED_CODE || response.code == TOO_MANY_REQUESTS
        if (!overLimit && retryAfter == null) return null
        response.body?.close()
        return RequestGovernor.Attempt.PushBack(
            retryAfterMillis = retryAfter,
            error = IOException("SEC asked for quiet: HTTP ${response.code}"),
        )
    }

    private suspend fun cachedText(name: String, fetch: suspend () -> String?): String? {
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
    val operatingIncomeRecords = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.operator(SecDriverNormalizationPolicy.Driver.OperatingIncome),
    )
    val nonRecurringRecords = annualNonRecurringChargeRecords(usGaap)
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
        operatingIncome = operatingIncomeRecords.filter { it.asOfDate in acceptedDates },
        nonRecurringCharges = nonRecurringRecords.filter { it.asOfDate in acceptedDates },
    )
}

/**
 * Impairment and restructuring for the year, as one positive charge.
 *
 * Impairment reaches the filing two ways, and one company can use both: a single aggregate line,
 * or the goodwill, intangible and tangible lines on their own. Adding every concept found would
 * count the same write-down twice for a filer that reports the aggregate and its parts. The larger
 * of the two readings wins instead. An aggregate smaller than the parts it is supposed to hold is
 * not an aggregate, and a filer that reports only parts is still counted.
 *
 * Restructuring stays a separate driver, and its qnames are the two pure ones. The combined
 * concepts (`RestructuringCostsAndAssetImpairmentCharges`) already carry impairment, so reading
 * them here would double the same dollars a second way.
 */
private fun annualNonRecurringChargeRecords(usGaap: JsonObject): List<AnnualReportedValue> {
    val aggregate = annualChargesByDate(usGaap, SecDriverNormalizationPolicy.Driver.ImpairmentAggregate, ::maxOf)
    val components = annualChargesByDate(usGaap, SecDriverNormalizationPolicy.Driver.ImpairmentComponents, Double::plus)
    val restructuring = annualChargesByDate(usGaap, SecDriverNormalizationPolicy.Driver.RestructuringCharges, ::maxOf)
    val dates = aggregate.keys + components.keys + restructuring.keys
    return dates.sorted().map { date ->
        val impairment = maxOf(aggregate[date] ?: 0.0, components[date] ?: 0.0)
        AnnualReportedValue(
            asOfDate = date,
            value = impairment + (restructuring[date] ?: 0.0),
            source = DcfSource.SecEdgar,
            concept = "non_recurring_charges",
            unit = "USD",
        )
    }
}

/**
 * A charge per fiscal year end, as an absolute value.
 *
 * [combine] decides what two concepts reporting the same year mean: [maxOf] for concepts that
 * describe the same dollars, [Double.plus] for concepts that describe different ones. Sign is
 * dropped because filers book a write-down both ways and the size is what is being read.
 */
private fun annualChargesByDate(
    usGaap: JsonObject,
    driver: SecDriverNormalizationPolicy.Driver,
    combine: (Double, Double) -> Double,
): Map<String, Double> {
    val operator = SecDriverNormalizationPolicy.operator(driver)
    val byDate = mutableMapOf<String, Double>()
    operator.qnames.forEach { concept ->
        annualFyRecords(usGaap, concept, operator).forEach { record ->
            val charge = abs(record.value)
            byDate[record.asOfDate] = byDate[record.asOfDate]?.let { combine(it, charge) } ?: charge
        }
    }
    return byDate
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

private fun annualRecurringDevelopmentRecords(usGaap: JsonObject): List<AnnualReportedValue> {
    var tangible = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.DriverOperator(
            qnames = SecDriverNormalizationPolicy.recurringDevelopmentConcepts,
            unit = "USD",
            periodShape = SecDriverNormalizationPolicy.PeriodShape.Duration,
            operation = "select_one_equivalent",
        ),
    )
    var wells = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.DriverOperator(
            qnames = SecDriverNormalizationPolicy.recurringWellsConcepts,
            unit = "USD",
            periodShape = SecDriverNormalizationPolicy.PeriodShape.Duration,
            operation = "select_one_equivalent",
        ),
    )
    var software = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.DriverOperator(
            qnames = SecDriverNormalizationPolicy.recurringSoftwareConcepts,
            unit = "USD",
            periodShape = SecDriverNormalizationPolicy.PeriodShape.Duration,
            operation = "select_one_equivalent",
        ),
    )
    var intangibles = annualFyRecordsAny(
        usGaap,
        SecDriverNormalizationPolicy.DriverOperator(
            qnames = SecDriverNormalizationPolicy.recurringIntangibleConcepts,
            unit = "USD",
            periodShape = SecDriverNormalizationPolicy.PeriodShape.Duration,
            operation = "select_one_equivalent",
        ),
    )
    var tangibleByDate = tangible.associateBy { it.asOfDate }
    var wellsByDate = wells.associateBy { it.asOfDate }
    var softwareByDate = software.associateBy { it.asOfDate }
    var intangiblesByDate = intangibles.associateBy { it.asOfDate }
    return (
        tangibleByDate.keys + wellsByDate.keys + softwareByDate.keys + intangiblesByDate.keys
    ).mapNotNull { date ->
        var plant = tangibleByDate[date]
        var wellProgram = wellsByDate[date]
        var softwareProgram = softwareByDate[date]
        var intangibleProgram = intangiblesByDate[date]
        var total = SecDriverNormalizationPolicy.recurringDevelopmentTotal(
            tangibleDollars = plant?.value,
            wellsDollars = wellProgram?.value,
            tangibleConcept = plant?.concept,
            softwareDollars = softwareProgram?.value,
            intangiblesDollars = intangibleProgram?.value,
        ) ?: return@mapNotNull null
        (plant ?: wellProgram ?: softwareProgram ?: intangibleProgram)?.copy(value = total)
    }.sortedBy { it.asOfDate }
}

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
            // Period-end year. SEC `fy` is the filing year, so a 2025 10-K
            // stamps fy=2025 on the 2024 comparative and collapses two years.
            fiscalYear = endDate.take(4).toIntOrNull(),
            source = DcfSource.SecEdgar,
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
