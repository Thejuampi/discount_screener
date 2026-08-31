package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.FinanceSubsidiaryMatch
import com.discountscreener.core.engine.IssuerComponentAssembler
import com.discountscreener.core.engine.IssuerComponentSet
import com.discountscreener.core.engine.NamedFiler
import com.discountscreener.core.engine.SecCompanyFactsSieve
import com.discountscreener.core.engine.XbrlDimensionalFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val SEC_USER_AGENT = "DiscountScreener research@discountscreener.com"
private const val SUBMISSIONS_URL = "https://data.sec.gov/submissions/"
private const val ARCHIVES_URL = "https://www.sec.gov/Archives/edgar/data/"
private const val EFTS_URL = "https://efts.sec.gov/LATEST/search-index"
private const val COMPANY_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json"
private const val COMPANY_FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/"
private const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1000L
private const val TEN_K_FETCH_CONCURRENCY = 2

private fun defaultSecClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

class SecIssuerComponentClient(
    private val cacheDir: File? = null,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val client: OkHttpClient = defaultSecClient(),
) : IssuerComponentLookup {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var tickerToCik: Map<String, String>? = null

    // A 10-K fetch and its two-pass streaming parse are heavy on network and CPU. An enrichment
    // round can ask for this many symbols at once at the caller's own fan-out limit; that limit is
    // sized for a Yahoo quote, not this. Capped here so a cold cache does not run that many of
    // these at the same time.
    private val tenKFetchPermits = Semaphore(TEN_K_FETCH_CONCURRENCY)

    override suspend fun lookup(symbol: String, companyName: String?): IssuerComponentSet? =
        withContext(Dispatchers.IO) {
            try {
                tenKFetchPermits.withPermit {
                    var file = loadTenKFile(symbol) ?: return@withContext null
                    var facts = XbrlDimensionalFacts.parse { file.reader() }
                    if (facts.isEmpty()) return@withContext null
                    var finance = companyName?.let { name -> loadFinanceDrivers(name) }
                    IssuerComponentAssembler.fromParentFacts(facts, finance)
                }
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

    private fun loadTenKFile(symbol: String): File? {
        var cik = resolveCik(symbol) ?: return null
        var dir = cacheDir ?: File(System.getProperty("java.io.tmpdir"))
        var file = File(dir, "CIK$cik.10k-instance.xml")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < ttlMillis) return file
        var accession = latestTenKAccession(cik) ?: return null
        var accDir = accession.replace("-", "")
        var cikNum = cik.trimStart('0').ifBlank { "0" }
        var indexBody = getText("$ARCHIVES_URL$cikNum/$accDir/index.json") ?: return null
        var xmlName = instanceXmlName(indexBody) ?: return null
        return downloadToFile("$ARCHIVES_URL$cikNum/$accDir/$xmlName", file)
    }

    private fun downloadToFile(url: String, target: File): File? {
        return try {
            var request = Request.Builder()
                .url(url)
                .header("User-Agent", SEC_USER_AGENT)
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(request).execute().use { response ->
                var body = response.body
                if (!response.isSuccessful || body == null) return@use null
                target.parentFile?.mkdirs()
                var tmp = File(target.parentFile, target.name + ".part")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) return@use null
                }
                target
            }
        } catch (_: Exception) {
            null
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
            var phrase = "$stem $arm"
            collectFilers(
                found,
                "$EFTS_URL?entityName=${URLEncoder.encode(phrase, "UTF-8")}&forms=10-K",
            )
            collectFilers(
                found,
                "$EFTS_URL?q=${URLEncoder.encode("\"$phrase\"", "UTF-8")}&forms=10-K",
            )
        }
        return found.values.toList()
    }

    private fun collectFilers(found: MutableMap<String, NamedFiler>, url: String) {
        var body = getText(url) ?: return
        var hits = json.parseToJsonElement(body).jsonObject["hits"]
            ?.jsonObject?.get("hits")?.jsonArray ?: return
        for (hit in hits) {
            var source = hit.jsonObject["_source"]?.jsonObject ?: continue
            var cik = source["ciks"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: continue
            var name = source["display_names"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                ?: continue
            var clean = stripCikSuffix(name)
            found.putIfAbsent(cik.padStart(10, '0'), NamedFiler(cik.padStart(10, '0'), clean))
        }
    }

    // Cut a "  (CIK 0000320193)" tail: the first "(CIK" that a whitespace char precedes.
    private fun stripCikSuffix(name: String): String {
        var at = name.indexOf("(CIK")
        while (at > 0) {
            if (name[at - 1].isWhitespace()) return name.substring(0, at).trim()
            at = name.indexOf("(CIK", at + 1)
        }
        return name.trim()
    }

    /**
     * The sieved companyfacts of one CIK, from the day's cache or from the network.
     *
     * This call blocks on the network. Every caller runs it on [Dispatchers.IO]. The provider's
     * function of the same name is `suspend`; this one is not, so the dispatcher is the caller's
     * to hold.
     */
    internal fun loadSievedFacts(cik: String): String? {
        var slimFile = cacheDir?.let { File(it, companyFactsSlimFileName(cik)) }
        if (slimFile != null && slimFile.isFile) {
            var age = System.currentTimeMillis() - slimFile.lastModified()
            if (age < ttlMillis) return slimFile.readText()
        }
        var slim = sievedStream("${COMPANY_FACTS_URL}CIK$cik.json") ?: return null
        slimFile?.let { target ->
            target.parentFile?.mkdirs()
            writeAtomically(target, slim)
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

    /**
     * A companyfacts answer, sieved as it arrives.
     *
     * The file is about 4 MB and the sieve keeps a small part of it. Reading the body to a string
     * first would hold all 4 MB, plus the copy the decoder makes, for nothing.
     */
    private fun sievedStream(url: String): String? {
        return try {
            var request = Request.Builder()
                .url(url)
                .header("User-Agent", SEC_USER_AGENT)
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                var body = response.body ?: return null
                body.charStream().use { reader -> SecCompanyFactsSieve.sieve(reader) }
            }
        } catch (_: Exception) {
            null
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
