package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.FinanceSubsidiaryMatch
import com.discountscreener.core.engine.IssuerComponentAssembler
import com.discountscreener.core.engine.IssuerComponentSet
import com.discountscreener.core.engine.NamedFiler
import com.discountscreener.core.engine.XbrlDimensionalFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private const val SEC_USER_AGENT = "DiscountScreener research@discountscreener.com"
private const val SUBMISSIONS_URL = "https://data.sec.gov/submissions/"
private const val ARCHIVES_URL = "https://www.sec.gov/Archives/edgar/data/"
private const val EFTS_URL = "https://efts.sec.gov/LATEST/search-index"
private const val COMPANY_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json"
private const val COMPANY_FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/"
private const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1000L

class SecIssuerComponentClient(
    private val cacheDir: File? = null,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) : IssuerComponentLookup {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var tickerToCik: Map<String, String>? = null

    override suspend fun lookup(symbol: String, companyName: String?): IssuerComponentSet? =
        withContext(Dispatchers.IO) {
            try {
                var xml = loadTenKXml(symbol) ?: return@withContext null
                var facts = XbrlDimensionalFacts.parse(xml)
                if (facts.isEmpty()) return@withContext null
                var finance = companyName?.let { name -> loadFinanceDrivers(name) }
                IssuerComponentAssembler.fromParentFacts(facts, finance)
            } catch (_: Exception) {
                null
            }
        }

    private fun loadFinanceDrivers(parentName: String): com.discountscreener.core.engine.FinancialComponentDrivers? {
        var candidates = searchFinanceFilers(parentName)
        var pick = FinanceSubsidiaryMatch.pick(parentName, candidates) ?: return null
        var slim = loadSievedFacts(pick.cik) ?: return null
        return IssuerComponentAssembler.financeFromResidualFacts(slim, "subsidiary_companyfacts:${pick.cik}")
    }

    private fun loadTenKXml(symbol: String): String? {
        var cik = resolveCik(symbol) ?: return null
        var cacheName = "CIK$cik.10k-instance.xml"
        return cachedText(cacheName) {
            var accession = latestTenKAccession(cik) ?: return@cachedText null
            var accDir = accession.replace("-", "")
            var cikNum = cik.trimStart('0').ifBlank { "0" }
            var indexUrl = "$ARCHIVES_URL$cikNum/$accDir/index.json"
            var indexBody = getText(indexUrl) ?: return@cachedText null
            var xmlName = instanceXmlName(indexBody) ?: return@cachedText null
            getText("$ARCHIVES_URL$cikNum/$accDir/$xmlName")
        }
    }

    private fun latestTenKAccession(cik: String): String? {
        var body = getText("${SUBMISSIONS_URL}CIK$cik.json") ?: return null
        var recent = json.parseToJsonElement(body).jsonObject["filings"]
            ?.jsonObject?.get("recent")?.jsonObject ?: return null
        var forms = recent["form"]?.jsonArray ?: return null
        var acc = recent["accessionNumber"]?.jsonArray ?: return null
        for (i in forms.indices) {
            var form = forms[i].jsonPrimitive.content
            if (form == "10-K" || form == "10-K/A") return acc[i].jsonPrimitive.content
        }
        return null
    }

    private fun instanceXmlName(indexJson: String): String? {
        var items = json.parseToJsonElement(indexJson).jsonObject["directory"]
            ?.jsonObject?.get("item")?.jsonArray ?: return null
        var names = items.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        return names.firstOrNull { it.endsWith("_htm.xml") } ?: names.firstOrNull { it.endsWith(".xml") && !it.endsWith(".xsd") }
    }

    private fun searchFinanceFilers(parentName: String): List<NamedFiler> {
        var stem = FinanceSubsidiaryMatch.normalize(parentName)
        if (stem.isBlank()) return emptyList()
        var found = linkedMapOf<String, NamedFiler>()
        for (arm in listOf("Financial", "Credit", "Capital")) {
            var query = "\"$stem $arm\""
            var url = "$EFTS_URL?q=${java.net.URLEncoder.encode(query, Charsets.UTF_8)}&forms=10-K"
            var body = getText(url) ?: continue
            var hits = json.parseToJsonElement(body).jsonObject["hits"]
                ?.jsonObject?.get("hits")?.jsonArray ?: continue
            for (hit in hits) {
                var source = hit.jsonObject["_source"]?.jsonObject ?: continue
                var cik = source["ciks"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: continue
                var name = source["display_names"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                    ?: continue
                var clean = name.replace(Regex("""\s+\(CIK.*"""), "").trim()
                found.putIfAbsent(cik.padStart(10, '0'), NamedFiler(cik.padStart(10, '0'), clean))
            }
        }
        return found.values.toList()
    }

    private fun loadSievedFacts(cik: String): String? {
        var slimFile = cacheDir?.let { File(it, companyFactsSlimFileName(cik)) }
        if (slimFile != null && slimFile.isFile) {
            var age = System.currentTimeMillis() - slimFile.lastModified()
            if (age < ttlMillis) return slimFile.readText()
        }
        var body = getText("${COMPANY_FACTS_URL}CIK$cik.json") ?: return null
        var slim = com.discountscreener.core.engine.SecCompanyFactsSieve.sieve(body.reader())
        slimFile?.let {
            it.parentFile?.mkdirs()
            it.writeText(slim)
        }
        return slim
    }

    private fun resolveCik(symbol: String): String? {
        var map = tickerToCik ?: loadTickerMap()
        return map[symbol.uppercase()]
    }

    private fun loadTickerMap(): Map<String, String> {
        return try {
            var body = cachedText("company_tickers.json") {
                getText(COMPANY_TICKERS_URL)
            } ?: return emptyMap()
            var root = json.parseToJsonElement(body).jsonObject
            var map = mutableMapOf<String, String>()
            for ((_, entry) in root) {
                var obj = entry.jsonObject
                var ticker = obj["ticker"]?.jsonPrimitive?.content?.uppercase() ?: continue
                var cikVal = obj["cik_str"]?.jsonPrimitive?.int ?: continue
                map[ticker] = cikVal.toString().padStart(10, '0')
            }
            tickerToCik = map
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun getText(url: String): String? {
        return try {
            var request = Request.Builder()
                .url(url)
                .header("User-Agent", SEC_USER_AGENT)
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cachedText(name: String, fetch: () -> String?): String? {
        var file = cacheDir?.let { File(it, name) }
        if (file != null && file.isFile) {
            var age = System.currentTimeMillis() - file.lastModified()
            if (age < ttlMillis) return file.readText()
        }
        var body = fetch() ?: return null
        if (file != null) {
            file.parentFile?.mkdirs()
            file.writeText(body)
        }
        return body
    }
}
