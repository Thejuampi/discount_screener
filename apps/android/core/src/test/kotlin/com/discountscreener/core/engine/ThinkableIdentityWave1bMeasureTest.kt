package com.discountscreener.core.engine

import com.discountscreener.core.harness.HttpYahooTransport
import com.discountscreener.core.harness.MarketsInsiderYieldLiveClient
import com.discountscreener.core.harness.parseQuoteSummary
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ValuationModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertTrue

private val WAVE1B_ROOT: Path = Path.of(
    "G:/dev/repos/discount_screener/.agents/workspace/tmp/e2e/thinkable-identity-qa/build/wave-1b",
)

private val QA_SYMBOLS = listOf(
    "T", "AMZN", "AAPL", "CI", "JPM", "ACGL", "MSFT", "NVDA", "UNH", "JNJ",
    "XOM", "BAC", "V", "WMT", "GOOGL", "META", "TSLA", "HD", "PG", "MRK",
)

private val SYMBOL_CIK = mapOf(
    "T" to "0000732717",
    "AMZN" to "0001018724",
    "AAPL" to "0000320193",
    "CI" to "0001739940",
    "JPM" to "0000019617",
    "ACGL" to "0000947484",
    "MSFT" to "0000789019",
    "NVDA" to "0001045810",
    "UNH" to "0000731766",
    "JNJ" to "0000200406",
    "XOM" to "0000034088",
    "BAC" to "0000070858",
    "V" to "0001403161",
    "WMT" to "0000104169",
    "GOOGL" to "0001652044",
    "META" to "0001326801",
    "TSLA" to "0001318605",
    "HD" to "0000354950",
    "PG" to "0000080424",
    "MRK" to "0000310158",
)

private val HOLDOUT_ROOT: Path = Path.of(
    "G:/dev/repos/discount_screener/.agents/workspace/tmp/e2e/thinkable-identity-qa/build/holdout",
)

private val HOLDOUT_SYMBOLS = listOf(
    "CMCSA", "DASH", "DELL", "WDAY", "AMAT", "PFE", "BIIB", "EOG", "CPAY", "TGT",
    "EA", "TTWO", "GM", "LOW", "CHD", "PNC", "C", "CB", "HUM", "ELV",
)

private val HOLDOUT_CIK = mapOf(
    "CMCSA" to "0001166691",
    "DASH" to "0001792789",
    "DELL" to "0001571996",
    "WDAY" to "0001327811",
    "AMAT" to "0000006951",
    "PFE" to "0000078003",
    "BIIB" to "0000875045",
    "EOG" to "0000821189",
    "CPAY" to "0001175454",
    "TGT" to "0000027419",
    "EA" to "0000712515",
    "TTWO" to "0000946581",
    "GM" to "0001467858",
    "LOW" to "0000060667",
    "CHD" to "0000313927",
    "PNC" to "0000713676",
    "C" to "0000831001",
    "CB" to "0000896159",
    "HUM" to "0000049071",
    "ELV" to "0001156039",
)

class ThinkableIdentityWave1bPrefetchTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DS_WAVE1B", matches = "true")
    fun prefetch_yahoo_quotes() {
        var dest = WAVE1B_ROOT.resolve("yahoo")
        Files.createDirectories(dest)
        var transport = HttpYahooTransport()
        for (symbol in QA_SYMBOLS) {
            Files.writeString(dest.resolve("$symbol-quote.json"), transport.quoteSummary(symbol))
            var ts = transport.timeseries(symbol)
            if (ts != null) Files.writeString(dest.resolve("$symbol-ts.json"), ts)
        }
        assertTrue(Files.exists(dest.resolve("JPM-quote.json")))
    }
}

class ThinkableIdentityHoldoutPrefetchTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DS_HOLDOUT", matches = "true")
    fun prefetch_holdout_yahoo_quotes() {
        var dest = HOLDOUT_ROOT.resolve("yahoo")
        Files.createDirectories(dest)
        var transport = HttpYahooTransport()
        for (symbol in HOLDOUT_SYMBOLS) {
            Files.writeString(dest.resolve("$symbol-quote.json"), transport.quoteSummary(symbol))
            var ts = transport.timeseries(symbol)
            if (ts != null) Files.writeString(dest.resolve("$symbol-ts.json"), ts)
        }
        assertTrue(Files.exists(dest.resolve("PNC-quote.json")))
    }
}

class ThinkableIdentityWave1bMeasureTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DS_WAVE1B", matches = "true")
    fun measure_official_widths() {
        var marketParams = MarketParams(
            rfBps = 470,
            erpBps = 442,
            provisional = false,
            erpSchool = ErpSchool.ImpliedIndex,
            rfSource = RF_SOURCE_YAHOO_TNX,
            macroStableGrowthBps = 380,
        )
        var rows = QA_SYMBOLS.map { measure(it, marketParams) }
        var out = WAVE1B_ROOT.resolve("measure-results.json")
        var body = rows.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { row ->
            row.toJson().entries.joinToString(prefix = "  {", postfix = "}") { (k, v) ->
                var encoded = if (v == null) "null" else "\"${v.replace("\"", "'")}\""
                "\"$k\":$encoded"
            }
        }
        Files.writeString(out, body)
        var csv = scoreboardCsv(rows, extraHoldout = false)
        Files.writeString(WAVE1B_ROOT.resolve("driver-dump.csv"), csv)
        println(csv)
        var jpm = rows.first { it.symbol == "JPM" }
        assertTrue(
            (jpm.identityBaseCents ?: 0L) > 0L || jpm.computeError != null,
            "JPM measure must publish residual cents or a documented miss",
        )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DS_WAVE1B", matches = "true")
    fun book_mean_ape_vs_street_is_at_most_two_thirds_of_5_percent() {
        var marketParams = MarketParams(
            rfBps = 470,
            erpBps = 442,
            provisional = false,
            erpSchool = ErpSchool.ImpliedIndex,
            rfSource = RF_SOURCE_YAHOO_TNX,
            macroStableGrowthBps = 380,
        )
        var rows = QA_SYMBOLS.map { measure(it, marketParams) }
        var apes = rows.map { row ->
            var street = requireNotNull(row.streetBaseCents).toDouble()
            var ident = requireNotNull(row.identityBaseCents).toDouble()
            kotlin.math.abs(ident - street) / street
        }
        assertTrue(
            apes.average() <= 0.05 * 2.0 / 3.0,
            "mean APE=${apes.average()} n=${apes.size} gate=${0.05 * 2.0 / 3.0}",
        )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DS_HOLDOUT", matches = "true")
    fun measure_holdout_book() {
        var marketParams = MarketParams(
            rfBps = 470,
            erpBps = 442,
            provisional = false,
            erpSchool = ErpSchool.ImpliedIndex,
            rfSource = RF_SOURCE_YAHOO_TNX,
            macroStableGrowthBps = 380,
        )
        var rows = HOLDOUT_SYMBOLS.map {
            measure(it, marketParams, root = HOLDOUT_ROOT, ciks = HOLDOUT_CIK)
        }
        var csv = scoreboardCsv(rows, extraHoldout = true)
        Files.createDirectories(HOLDOUT_ROOT)
        Files.writeString(HOLDOUT_ROOT.resolve("driver-dump.csv"), csv)
        println(csv)
        var priced = rows.count { (it.identityBaseCents ?: 0L) > 0L }
        assertTrue(priced >= 1, "holdout must price at least one name")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DS_DEBT_MEASURE", matches = "true")
    fun ab_fit_debt_engine() {
        writeDebtAb(
            symbols = QA_SYMBOLS,
            root = WAVE1B_ROOT,
            ciks = SYMBOL_CIK,
            outName = "debt-engine-c.csv",
        )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DS_DEBT_MEASURE", matches = "true")
    fun ab_holdout_debt_engine() {
        writeDebtAb(
            symbols = HOLDOUT_SYMBOLS,
            root = HOLDOUT_ROOT,
            ciks = HOLDOUT_CIK,
            outName = "debt-engine-c.csv",
        )
    }

    private fun writeDebtAb(
        symbols: List<String>,
        root: Path,
        ciks: Map<String, String>,
        outName: String,
    ) {
        var marketParams = MarketParams(
            rfBps = 470,
            erpBps = 442,
            provisional = false,
            erpSchool = ErpSchool.ImpliedIndex,
            rfSource = RF_SOURCE_YAHOO_TNX,
            macroStableGrowthBps = 380,
        )
        var yields = MarketsInsiderYieldLiveClient(root.resolve("issuer-yield"))
        var rows = symbols.map { symbol ->
            debtAbRow(symbol, marketParams, root, ciks, yields)
        }
        var csv = debtAbCsv(rows)
        Files.createDirectories(root)
        Files.writeString(root.resolve(outName), csv)
        println(csv)
        assertTrue(rows.isNotEmpty(), "debt A/B must write at least one row")
    }

    private fun debtAbRow(
        symbol: String,
        marketParams: MarketParams,
        root: Path,
        ciks: Map<String, String>,
        yields: MarketsInsiderYieldLiveClient,
    ): DebtAbRow {
        var off = measure(symbol, marketParams, root, ciks)
        var quotePath = root.resolve("yahoo").resolve("$symbol-quote.json")
        var companyName = if (Files.exists(quotePath)) {
            companyNameFromQuote(Files.readString(quotePath))
        } else {
            null
        }
        var operating = off.businessClass == BusinessClass.OperatingNonFinancial.name
        var point = if (operating) yields.lookup(symbol, companyName) else null
        var on = if (point != null) {
            measure(symbol, marketParams, root, ciks, issuerYield = point)
        } else {
            off
        }
        return DebtAbRow(
            symbol = symbol,
            businessClass = off.businessClass,
            model = off.model,
            companyName = companyName,
            yieldBps = point?.yieldBps,
            yieldConcept = point?.concept,
            kdOff = kdSource(off.engineReasons),
            kdOn = kdSource(on.engineReasons),
            kdBpsOff = kdBps(off.engineReasons),
            kdBpsOn = kdBps(on.engineReasons),
            qualityOff = reasonValue(off.engineReasons, "rate_quality="),
            qualityOn = reasonValue(on.engineReasons, "rate_quality="),
            taxYearsOff = reasonValue(off.engineReasons, "period_intersection=common_fiscal_years:"),
            taxYearsOn = reasonValue(on.engineReasons, "period_intersection=common_fiscal_years:"),
            waccOff = off.waccOrCoeBps,
            waccOn = on.waccOrCoeBps,
            identOffCents = off.identityBaseCents,
            identOnCents = on.identityBaseCents,
            error = on.computeError ?: off.computeError,
        )
    }

    private fun debtAbCsv(rows: List<DebtAbRow>): String = buildString {
        appendLine(
            "sym,class,model,name,yield_bps,yield_concept,kd_off,kd_on,kd_bps_off,kd_bps_on," +
                "q_off,q_on,tax_n_off,tax_n_on,wacc_off,wacc_on,ident_off,ident_on,ident_delta,error",
        )
        for (row in rows) {
            var identOff = row.identOffCents?.toDouble()?.div(100.0)
            var identOn = row.identOnCents?.toDouble()?.div(100.0)
            var delta = if (identOff != null && identOn != null) identOn - identOff else null
            appendLine(
                listOf(
                    row.symbol,
                    row.businessClass,
                    row.model,
                    row.companyName?.replace(",", " "),
                    row.yieldBps,
                    row.yieldConcept,
                    row.kdOff,
                    row.kdOn,
                    row.kdBpsOff,
                    row.kdBpsOn,
                    row.qualityOff,
                    row.qualityOn,
                    row.taxYearsOff,
                    row.taxYearsOn,
                    row.waccOff,
                    row.waccOn,
                    identOff,
                    identOn,
                    delta,
                    row.error?.replace(",", ";"),
                ).joinToString(","),
            )
        }
        var operating = rows.filter { it.businessClass == BusinessClass.OperatingNonFinancial.name }
        var withYield = operating.count { it.yieldBps != null }
        var switched = operating.count { it.kdOff != it.kdOn }
        var solidOnHits = operating.count { it.yieldBps != null && it.qualityOn == "solid" }
        appendLine("OPERATING,${operating.size}")
        appendLine("YIELD_HITS,$withYield")
        appendLine("KD_SOURCE_SWITCHES,$switched")
        appendLine("QUALITY_SOLID_ON_HITS,$solidOnHits")
    }

    private fun kdSource(codes: List<String>): String? =
        reasonValue(codes, "cost_of_debt_source=")

    private fun kdBps(codes: List<String>): Int? =
        reasonValue(codes, "cost_of_debt_bps=")?.toIntOrNull()

    private fun reasonValue(codes: List<String>, prefix: String): String? =
        codes.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private fun companyNameFromQuote(body: String): String? {
        var root = Json.parseToJsonElement(body).jsonObject
        var result = root["quoteSummary"]?.jsonObject
            ?.get("result")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
        var price = result?.get("price")?.jsonObject
        var longName = price?.get("longName")
        var shortName = price?.get("shortName")
        return longName?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: shortName?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private data class DebtAbRow(
        val symbol: String,
        val businessClass: String?,
        val model: String?,
        val companyName: String?,
        val yieldBps: Int?,
        val yieldConcept: String?,
        val kdOff: String?,
        val kdOn: String?,
        val kdBpsOff: Int?,
        val kdBpsOn: Int?,
        val qualityOff: String?,
        val qualityOn: String?,
        val taxYearsOff: String?,
        val taxYearsOn: String?,
        val waccOff: Int?,
        val waccOn: Int?,
        val identOffCents: Long?,
        val identOnCents: Long?,
        val error: String?,
    )

    private fun measure(
        symbol: String,
        marketParams: MarketParams,
        root: Path = WAVE1B_ROOT,
        ciks: Map<String, String> = SYMBOL_CIK,
        issuerYield: IssuerYieldPoint? = null,
    ): MeasureRow {
        var sources = mutableListOf("sec:companyfacts", "yahoo:quoteSummary")
        var cik = ciks[symbol]
        var factsPath = cik?.let { root.resolve("sec").resolve("CIK$it.json") }
        var quotePath = root.resolve("yahoo").resolve("$symbol-quote.json")
        if (factsPath == null || !Files.exists(factsPath)) {
            return MeasureRow(symbol, sourcesTried = sources + "sec:missing_file")
        }
        if (!Files.exists(quotePath)) {
            return MeasureRow(symbol, sourcesTried = sources + "yahoo:missing_file")
        }
        var slimAllowed = SecDriverNormalizationPolicy.retainedQnames + SecResidualFacts.retainedQnames
        var slim = SecCompanyFactsSieve.sieve(Files.newBufferedReader(factsPath), slimAllowed)
        var residualJson = slim
        var yahoo = parseQuoteSummary(Files.readString(quotePath), symbol)
        var street = parseStreet(Files.readString(quotePath))
        var businessClass = DcfAnalysisEngine.classifyBusiness(
            yahoo.sectorName,
            yahoo.industryName,
            yahoo.sectorKey,
            yahoo.industryKey,
            symbol = symbol,
        )
        var residual = SecResidualFacts.extract(residualJson)
        var fund = if (businessClass == BusinessClass.FinancialServices && residual != null) {
            sources += residual.provenance
            var secShares = residual.shares?.roundToLong()
            var usedShares = secShares ?: yahoo.sharesOutstanding
            if (secShares == null && usedShares != null) sources += "yahoo:shares_fallback"
            var bvps = residual.bookValuePerShareCents
                ?: usedShares?.takeIf { it > 0L }?.let { count ->
                    ((residual.bookEquityDollars / count.toDouble()) * 100.0).roundToLong()
                }
                ?: yahoo.bookValuePerShareCents
            yahoo.copy(
                sharesOutstanding = usedShares,
                bookValuePerShareCents = bvps,
                returnOnEquityBps = residual.returnOnEquityBps,
                retentionBps = residual.retentionBps ?: yahoo.retentionBps,
            )
        } else {
            if (businessClass == BusinessClass.FinancialServices) {
                sources += "sec:residual_extract_empty"
                if (yahoo.retentionBps != null) sources += "yahoo:retention_fallback"
            }
            yahoo
        }
        var timeseries = if (businessClass == BusinessClass.OperatingNonFinancial) {
            sources += "sec:fcff_timeseries"
            Wave1bSecTimeseries.build(slim) ?: FundamentalTimeseries()
        } else {
            FundamentalTimeseries()
        }
        var computed = DcfAnalysisEngine.compute(
            fundamentals = fund,
            timeseries = timeseries,
            marketPriceCents = street.priceCents,
            marketParams = marketParams,
            issuerYield = issuerYield,
        )
        var analysis = computed.getOrNull()
        var error = computed.exceptionOrNull()?.message
        var shareCount = fund.sharesOutstanding?.toDouble()
            ?: timeseries.dilutedAverageShares.lastOrNull()?.value
        var implied = if (analysis != null && street.baseCents != null) {
            StreetImpliedHonesty.reconcile(analysis, street.baseCents, shareCount)
        } else {
            null
        }
        var judgment = ValuationJudgmentAssembler.assemble(
            detail(symbol, fund, street),
            analysis,
        )
        var bear = analysis?.bearIntrinsicValueCents
        var base = analysis?.baseIntrinsicValueCents
        var bull = analysis?.bullIntrinsicValueCents
        var width = if (bear != null && base != null && bull != null) {
            ValuationDecisionPolicy.scenarioWidthBps(bear, base, bull)
        } else {
            null
        }
        var streetBase = street.baseCents
        var gap = if (base != null && streetBase != null) {
            ValuationDecisionPolicy.differenceBps(base, streetBase)
        } else {
            null
        }
        var predicted = predictedWave2(judgment, width, street.complete)
        return MeasureRow(
            symbol = symbol,
            businessClass = businessClass.name,
            model = analysis?.model?.name,
            identityBearCents = bear,
            identityBaseCents = base,
            identityBullCents = bull,
            streetLowCents = street.lowCents,
            streetBaseCents = streetBase,
            streetHighCents = street.highCents,
            streetComplete = street.complete,
            differenceBps = gap,
            widthBps = width,
            status = judgment.status.name,
            relation = judgment.relation.name,
            reasons = judgment.reasonCodes.map { it.name },
            waccOrCoeBps = analysis?.waccBps,
            discountRateKind = analysis?.discountRateKind?.name,
            bookValuePerShareCents = fund.bookValuePerShareCents,
            roe0Bps = fund.returnOnEquityBps,
            retentionBps = fund.retentionBps,
            shares = fund.sharesOutstanding,
            residualProvenance = residual?.provenance.orEmpty(),
            growthBps = analysis?.baseGrowthBps,
            stableGrowthBps = analysis?.stableGrowthBps,
            regime = analysis?.driverRegime,
            revenueDollars = analysis?.latestRevenueDollars,
            fcffDollars = analysis?.normalizedFcffDollars,
            ocfMarginBps = analysis?.normalizedOcfMarginBps,
            capexIntensityBps = analysis?.normalizedCapexIntensityBps,
            netDebtDollars = analysis?.netDebtDollars,
            computeError = error,
            marketParams = marketParams.fingerprint(),
            predictedWave2 = predicted,
            sourcesTried = sources,
            engineReasons = analysis?.reasonCodes.orEmpty(),
            nonHonestCents = implied?.impliedBaseCents,
            nonHonestKnob = implied?.winningKnob?.name,
            nonHonestHonestBps = implied?.winningHonestBps,
            nonHonestImpliedBps = implied?.winningImpliedBps,
            nonHonestDeltaBps = implied?.winningDeltaBps,
            nonHonestStretch = implied?.winningStretch?.name,
        )
    }

    private fun scoreboardCsv(rows: List<MeasureRow>, extraHoldout: Boolean): String = buildString {
        var head = "sym,street,ident,ape_h,nonhonest,ape_nh,nh_knob,nh_honest,nh_implied,nh_delta,nh_stretch"
        if (extraHoldout) head += ",class,model"
        head += ",wacc,g,gstab,regime,revB,fcffB,ocfM,capexI,book,roe,ret,netDebtB,sharesB"
        if (extraHoldout) head += ",error"
        head += ",engineReasons"
        appendLine(head)
        for (row in rows) {
            var street = row.streetBaseCents?.toDouble() ?: 0.0
            var ident = row.identityBaseCents?.toDouble() ?: 0.0
            var apeH = StreetScoreboard.ape(row.identityBaseCents, row.streetBaseCents)
            var apeNh = StreetScoreboard.ape(row.nonHonestCents, row.streetBaseCents)
            var cells = mutableListOf<Any?>(
                row.symbol,
                street / 100.0,
                ident / 100.0,
                StreetScoreboard.formatApe(apeH),
                row.nonHonestCents?.let { it / 100.0 },
                StreetScoreboard.formatApe(apeNh),
                row.nonHonestKnob,
                row.nonHonestHonestBps,
                row.nonHonestImpliedBps,
                row.nonHonestDeltaBps,
                row.nonHonestStretch,
            )
            if (extraHoldout) {
                cells += row.businessClass
                cells += row.model
            }
            cells.addAll(
                listOf(
                    row.waccOrCoeBps,
                    row.growthBps,
                    row.stableGrowthBps,
                    row.regime,
                    row.revenueDollars?.let { it / 1e9 },
                    row.fcffDollars?.let { it / 1e9 },
                    row.ocfMarginBps,
                    row.capexIntensityBps,
                    row.bookValuePerShareCents?.let { it / 100.0 },
                    row.roe0Bps,
                    row.retentionBps,
                    row.netDebtDollars?.let { it / 1e9 },
                    row.shares?.let { it / 1e9 },
                ),
            )
            if (extraHoldout) cells += row.computeError?.replace(",", ";")
            cells += row.engineReasons.joinToString("|")
            appendLine(cells.joinToString(","))
        }
        var honest = rows.mapNotNull { StreetScoreboard.ape(it.identityBaseCents, it.streetBaseCents) }
        var nonHonest = rows.mapNotNull { StreetScoreboard.ape(it.nonHonestCents, it.streetBaseCents) }
        appendLine(
            "MEAN_APE_HONEST,${if (honest.isEmpty()) "" else honest.average().toString()},n=${honest.size}",
        )
        appendLine(
            "MEAN_APE_NONHONEST,${if (nonHonest.isEmpty()) "" else nonHonest.average().toString()},n=${nonHonest.size}",
        )
        var stretchCounts = rows.mapNotNull { it.nonHonestStretch }.groupingBy { it }.eachCount()
        appendLine(
            "STRETCH," +
                listOf("Modest", "Stretched", "Absurd", "Unreachable").joinToString(",") { token ->
                    "$token=${stretchCounts[token] ?: 0}"
                },
        )
    }

    private fun predictedWave2(
        judgment: ValuationJudgment,
        width: Int?,
        streetComplete: Boolean,
    ): String {
        if (width != null && width > ValuationDecisionPolicy.WIDE_SCENARIO_BPS && streetComplete) {
            return "Street + UnusableIdentityFan"
        }
        if (width != null && width > ValuationDecisionPolicy.WIDE_SCENARIO_BPS) {
            return "IncompleteIdentity + UnusableIdentityFan"
        }
        return "${judgment.status.name} (${judgment.relation.name})"
    }

    private fun detail(
        symbol: String,
        fund: FundamentalSnapshot,
        street: StreetParse,
    ): SymbolDetail = SymbolDetail(
        symbol = symbol,
        profitable = true,
        marketPriceCents = street.priceCents ?: 0L,
        intrinsicValueCents = 0L,
        gapBps = 0,
        minimumGapBps = 2_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalFairValueCents = street.baseCents,
        externalSignalLowFairValueCents = street.lowCents,
        externalSignalHighFairValueCents = street.highCents,
        weightedExternalSignalFairValueCents = street.baseCents,
        weightedAnalystCount = street.analystCount,
        externalSignalAgeSeconds = 0L,
        externalSignalMaxAgeSeconds = 86_400L,
        analystOpinionCount = street.analystCount,
        fundamentals = fund,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )

    private data class StreetParse(
        val priceCents: Long?,
        val lowCents: Long?,
        val baseCents: Long?,
        val highCents: Long?,
        val analystCount: Int?,
        val complete: Boolean,
    )

    private fun parseStreet(body: String): StreetParse {
        var root = Json.parseToJsonElement(body).jsonObject
        var result = root["quoteSummary"]?.jsonObject
            ?.get("result")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
        var financial = result?.get("financialData")?.jsonObject
        var price = rawMoney(financial, "currentPrice")
            ?: rawMoney(result?.get("price")?.jsonObject, "regularMarketPrice")
        var low = rawMoney(financial, "targetLowPrice")
        var base = rawMoney(financial, "targetMedianPrice") ?: rawMoney(financial, "targetMeanPrice")
        var high = rawMoney(financial, "targetHighPrice")
        var count = financial?.get("numberOfAnalystOpinions")?.jsonObject
            ?.get("raw")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull()
        var complete = low != null && base != null && high != null &&
            low > 0L && base > 0L && high > 0L &&
            low <= base && base <= high &&
            (count ?: 0) > 0
        return StreetParse(price, low, base, high, count, complete)
    }

    private fun rawMoney(obj: JsonObject?, field: String): Long? {
        var raw = obj?.get(field)?.jsonObject?.get("raw")?.jsonPrimitive?.doubleOrNull ?: return null
        if (!raw.isFinite() || raw <= 0.0) return null
        return (raw * 100.0).roundToLong()
    }

    private data class MeasureRow(
        val symbol: String,
        val businessClass: String? = null,
        val model: String? = null,
        val identityBearCents: Long? = null,
        val identityBaseCents: Long? = null,
        val identityBullCents: Long? = null,
        val streetLowCents: Long? = null,
        val streetBaseCents: Long? = null,
        val streetHighCents: Long? = null,
        val streetComplete: Boolean = false,
        val differenceBps: Int? = null,
        val widthBps: Int? = null,
        val status: String? = null,
        val relation: String? = null,
        val reasons: List<String> = emptyList(),
        val waccOrCoeBps: Int? = null,
        val discountRateKind: String? = null,
        val bookValuePerShareCents: Long? = null,
        val roe0Bps: Int? = null,
        val retentionBps: Int? = null,
        val shares: Long? = null,
        val residualProvenance: List<String> = emptyList(),
        val growthBps: Int? = null,
        val stableGrowthBps: Int? = null,
        val regime: String? = null,
        val revenueDollars: Long? = null,
        val fcffDollars: Long? = null,
        val ocfMarginBps: Int? = null,
        val capexIntensityBps: Int? = null,
        val netDebtDollars: Long? = null,
        val computeError: String? = null,
        val marketParams: String? = null,
        val predictedWave2: String? = null,
        val sourcesTried: List<String> = emptyList(),
        val engineReasons: List<String> = emptyList(),
        val nonHonestCents: Long? = null,
        val nonHonestKnob: String? = null,
        val nonHonestHonestBps: Int? = null,
        val nonHonestImpliedBps: Int? = null,
        val nonHonestDeltaBps: Int? = null,
        val nonHonestStretch: String? = null,
    ) {
        fun toJson(): Map<String, String?> = mapOf(
            "symbol" to symbol,
            "businessClass" to businessClass,
            "model" to model,
            "identityBearCents" to identityBearCents?.toString(),
            "identityBaseCents" to identityBaseCents?.toString(),
            "identityBullCents" to identityBullCents?.toString(),
            "streetLowCents" to streetLowCents?.toString(),
            "streetBaseCents" to streetBaseCents?.toString(),
            "streetHighCents" to streetHighCents?.toString(),
            "streetComplete" to streetComplete.toString(),
            "differenceBps" to differenceBps?.toString(),
            "widthBps" to widthBps?.toString(),
            "status" to status,
            "relation" to relation,
            "reasons" to reasons.joinToString(","),
            "waccOrCoeBps" to waccOrCoeBps?.toString(),
            "discountRateKind" to discountRateKind,
            "bookValuePerShareCents" to bookValuePerShareCents?.toString(),
            "roe0Bps" to roe0Bps?.toString(),
            "retentionBps" to retentionBps?.toString(),
            "shares" to shares?.toString(),
            "residualProvenance" to residualProvenance.joinToString(","),
            "computeError" to computeError,
            "marketParams" to marketParams,
            "predictedWave2" to predictedWave2,
            "sourcesTried" to sourcesTried.joinToString(","),
            "nonHonestCents" to nonHonestCents?.toString(),
            "nonHonestKnob" to nonHonestKnob,
            "nonHonestHonestBps" to nonHonestHonestBps?.toString(),
            "nonHonestImpliedBps" to nonHonestImpliedBps?.toString(),
            "nonHonestDeltaBps" to nonHonestDeltaBps?.toString(),
            "nonHonestStretch" to nonHonestStretch,
        )
    }
}

private object Wave1bSecTimeseries {
    fun build(slimJson: String): FundamentalTimeseries? {
        var facts = Json.parseToJsonElement(slimJson).jsonObject
        var usGaap = facts["facts"]?.jsonObject?.get("us-gaap")?.jsonObject ?: return null
        var opCf = annualAny(usGaap, SecDriverNormalizationPolicy.Driver.OperatingCashFlow)
        var tangible = annualAny(
            usGaap,
            SecDriverNormalizationPolicy.recurringDevelopmentConcepts,
            "USD",
            SecDriverNormalizationPolicy.PeriodShape.Duration,
        )
        var wells = annualAny(
            usGaap,
            SecDriverNormalizationPolicy.recurringWellsConcepts,
            "USD",
            SecDriverNormalizationPolicy.PeriodShape.Duration,
        )
        var software = annualAny(
            usGaap,
            SecDriverNormalizationPolicy.recurringSoftwareConcepts,
            "USD",
            SecDriverNormalizationPolicy.PeriodShape.Duration,
        )
        var intangibles = annualAny(
            usGaap,
            SecDriverNormalizationPolicy.recurringIntangibleConcepts,
            "USD",
            SecDriverNormalizationPolicy.PeriodShape.Duration,
        )
        var tangibleByDate = tangible.associateBy { it.asOfDate }
        var wellsByDate = wells.associateBy { it.asOfDate }
        var softwareByDate = software.associateBy { it.asOfDate }
        var intangiblesByDate = intangibles.associateBy { it.asOfDate }
        var capexByDate = (
            tangibleByDate.keys + wellsByDate.keys + softwareByDate.keys + intangiblesByDate.keys
        ).mapNotNull { date ->
            var total = SecDriverNormalizationPolicy.recurringDevelopmentTotal(
                tangibleDollars = tangibleByDate[date]?.value,
                wellsDollars = wellsByDate[date]?.value,
                tangibleConcept = tangibleByDate[date]?.concept,
                softwareDollars = softwareByDate[date]?.value,
                intangiblesDollars = intangiblesByDate[date]?.value,
            ) ?: return@mapNotNull null
            date to total
        }.toMap()
        var capex = capexByDate.map { (date, value) ->
            AnnualReportedValue(date, value, source = DcfSource.SecEdgar)
        }
        if (opCf.isEmpty() || capex.isEmpty()) return null
        var acceptedOp = opCf.filter { capexByDate.containsKey(it.asOfDate) }
        if (acceptedOp.isEmpty()) return null
        var dates = acceptedOp.map { it.asOfDate }.toSet()
        var freeCashFlow = acceptedOp.map { row ->
            AnnualReportedValue(row.asOfDate, row.value - abs(requireNotNull(capexByDate[row.asOfDate])))
        }
        return FundamentalTimeseries(
            freeCashFlow = freeCashFlow,
            operatingCashFlow = acceptedOp,
            capitalExpenditure = capex.filter { it.asOfDate in dates }.map { it.copy(value = -abs(it.value)) },
            revenue = annualAny(usGaap, SecDriverNormalizationPolicy.Driver.Revenue).filter { it.asOfDate in dates },
            dilutedAverageShares = annualAny(usGaap, SecDriverNormalizationPolicy.Driver.DilutedAverageShares),
            interestExpense = annualAny(usGaap, SecDriverNormalizationPolicy.Driver.InterestExpense).filter { it.asOfDate in dates },
            pretaxIncome = annualAny(usGaap, SecDriverNormalizationPolicy.Driver.PretaxIncome).filter { it.asOfDate in dates },
            taxRateForCalcs = taxRates(usGaap, dates),
            totalDebt = annualAny(usGaap, SecDriverNormalizationPolicy.Driver.TotalDebt).filter { it.asOfDate in dates },
            marginalTaxRate = annualAny(usGaap, SecDriverNormalizationPolicy.Driver.MarginalTaxReference).filter { it.asOfDate in dates },
        )
    }

    private fun taxRates(usGaap: JsonObject, dates: Set<String>): List<AnnualReportedValue> {
        var pretax = annualAny(usGaap, SecDriverNormalizationPolicy.Driver.PretaxIncome).associateBy { it.asOfDate }
        return annualAny(usGaap, SecDriverNormalizationPolicy.Driver.TaxExpense)
            .filter { it.asOfDate in dates }
            .mapNotNull { tax ->
                var pre = pretax[tax.asOfDate]?.value ?: return@mapNotNull null
                if (abs(pre) <= 0.0) return@mapNotNull null
                AnnualReportedValue(tax.asOfDate, abs(tax.value) / abs(pre))
            }
    }

    private fun annualAny(
        usGaap: JsonObject,
        driver: SecDriverNormalizationPolicy.Driver,
    ): List<AnnualReportedValue> {
        var op = SecDriverNormalizationPolicy.operator(driver)
        return annualAny(usGaap, op.qnames, op.unit, op.periodShape)
    }

    private fun annualAny(
        usGaap: JsonObject,
        qnames: List<String>,
        unit: String,
        shape: SecDriverNormalizationPolicy.PeriodShape,
    ): List<AnnualReportedValue> {
        var byDate = linkedMapOf<String, AnnualReportedValue>()
        for (concept in qnames) {
            var entries = usGaap[concept]
                ?.jsonObject?.get("units")
                ?.jsonObject?.get(unit)
                ?.jsonArray
                ?: continue
            for (entry in entries) {
                var obj = entry.jsonObject
                if (obj["fp"]?.jsonPrimitive?.contentOrNull != "FY") continue
                var form = obj["form"]?.jsonPrimitive?.contentOrNull ?: continue
                if (form !in SecDriverNormalizationPolicy.acceptedForms) continue
                if (obj["segment"] != null && obj["segment"] !is JsonNull) continue
                var end = obj["end"]?.jsonPrimitive?.contentOrNull ?: continue
                var value = obj["val"]?.jsonPrimitive?.doubleOrNull ?: continue
                var start = obj["start"]?.jsonPrimitive?.contentOrNull
                var days = start?.let {
                    runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.parse(end)).toInt() }.getOrNull()
                }
                if (shape == SecDriverNormalizationPolicy.PeriodShape.Duration &&
                    (days == null || days !in SecDriverNormalizationPolicy.minimumDurationDays..
                        SecDriverNormalizationPolicy.maximumDurationDays)
                ) {
                    continue
                }
                if (shape == SecDriverNormalizationPolicy.PeriodShape.Instant && start != null) continue
                var record = AnnualReportedValue(
                    asOfDate = end,
                    value = value,
                    periodStart = start,
                    periodEnd = end,
                    durationDays = days,
                    source = DcfSource.SecEdgar,
                    concept = concept,
                    unit = unit,
                    filedAt = obj["filed"]?.jsonPrimitive?.contentOrNull,
                )
                var existing = byDate[end]
                if (existing == null || record.filedAt.orEmpty() > existing.filedAt.orEmpty()) {
                    byDate[end] = record
                }
            }
        }
        return byDate.values.sortedBy { it.asOfDate }
    }
}
