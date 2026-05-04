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

    val opCfRecords = annualFyRecords(usGaap, "NetCashProvidedByUsedInOperatingActivities")
    val capexRecords = annualFyRecords(usGaap, "PaymentsToAcquirePropertyPlantAndEquipment")
    val sharesRecords = annualFyRecords(usGaap, "WeightedAverageNumberOfDilutedSharesOutstanding")

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

    return FundamentalTimeseries(
        freeCashFlow = freeCashFlow,
        operatingCashFlow = acceptedOperatingCashFlow,
        capitalExpenditure = capitalExpenditure,
        dilutedAverageShares = sharesRecords,
    )
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
            byDate[endDate] = AnnualReportedValue(asOfDate = endDate, value = value)
        }
    }
    return byDate.values.sortedBy { it.asOfDate }
}
