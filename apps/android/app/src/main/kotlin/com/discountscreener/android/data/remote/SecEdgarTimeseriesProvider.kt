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
            val url = "$COMPANY_FACTS_URL/CIK$cikPadded.json"
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

    private fun buildTimeseries(facts: JsonObject): FundamentalTimeseries? {
        val usGaap = facts["facts"]?.jsonObject?.get("us-gaap")?.jsonObject ?: return null

        val opCfRecords = annualFyRecords(usGaap, "NetCashProvidedByUsedInOperatingActivities")
        val capexRecords = annualFyRecords(usGaap, "PaymentsToAcquirePropertyPlantAndEquipment")
        val sharesRecords = annualFyRecords(usGaap, "WeightedAverageNumberOfDilutedSharesOutstanding")

        if (opCfRecords.isEmpty()) return null

        // Compute FCF = opCF - |capex| per fiscal year end date
        val capexByDate = capexRecords.associate { it.asOfDate to it.value }
        val freeCashFlow = opCfRecords.mapNotNull { opCf ->
            val capex = capexByDate[opCf.asOfDate]
                ?: capexRecords.minByOrNull { Math.abs(parseYear(it.asOfDate) - parseYear(opCf.asOfDate)) }
                    ?.value
                ?: 0.0
            // SEC EDGAR capex is typically positive (payments) or negative; normalise to outflow
            val capexOutflow = Math.abs(capex)
            val fcf = opCf.value - capexOutflow
            AnnualReportedValue(asOfDate = opCf.asOfDate, value = fcf)
        }

        val operatingCashFlow = opCfRecords.toList()
        val capitalExpenditure = capexRecords.map { it.copy(value = -Math.abs(it.value)) }
        val dilutedShares = sharesRecords.toList()

        return FundamentalTimeseries(
            freeCashFlow = freeCashFlow,
            operatingCashFlow = operatingCashFlow,
            capitalExpenditure = capitalExpenditure,
            dilutedAverageShares = dilutedShares,
        )
    }

    private fun annualFyRecords(usGaap: JsonObject, concept: String): List<AnnualReportedValue> {
        val entries = usGaap[concept]
            ?.jsonObject?.get("units")
            ?.jsonObject?.entries
            ?.firstOrNull()
            ?.value?.jsonArray
            ?: return emptyList()

        // Keep only FY / 10-K annual records; deduplicate by fiscal-year end date keeping the
        // earliest-filed value (most canonical).
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

    private fun parseYear(date: String): Int = date.substringBefore("-").toIntOrNull() ?: 0
}
