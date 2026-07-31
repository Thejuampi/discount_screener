package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exports Android DCF results for the Windows QA baseline cohort (+ checklist)
 * so a side-by-side cent-for-cent compare can run against Windows export.
 */
class CrossPlatformParityExportTest {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun export_qa_cohort_parity_snapshot_for_windows_compare() {
        val cohort = loadCohort()
        val driverData = loadDriverData()
        val rows = mutableListOf<ParityRow>()

        for (member in cohort.members) {
            if (member.quarantine) continue
            val inputs = member.inputs
            val fundamentals = FundamentalSnapshot(
                symbol = member.symbol,
                sectorName = inputs.sectorName,
                industryName = inputs.industryName,
                marketCapDollars = inputs.marketCapDollars,
                sharesOutstanding = inputs.sharesOutstanding,
                betaMillis = inputs.betaMillis,
                totalDebtDollars = inputs.totalDebtDollars,
                totalCashDollars = inputs.totalCashDollars,
            )
            val driverRows = driverData.rows[member.symbol].orEmpty()
            val timeseries = FundamentalTimeseries(
                freeCashFlow = inputs.fcfAnnual.map { point ->
                    AnnualReportedValue("${point.year}-12-31", point.valueDollars)
                },
                operatingCashFlow = driverRows.map { row ->
                    AnnualReportedValue("${row.year}-12-31", row.ocf)
                },
                capitalExpenditure = driverRows.map { row ->
                    AnnualReportedValue("${row.year}-12-31", -row.capex)
                },
                revenue = driverRows.map { row ->
                    AnnualReportedValue("${row.year}-12-31", row.revenue)
                },
                interestExpense = driverRows.map { row ->
                    AnnualReportedValue("${row.year}-12-31", row.interest)
                },
                taxRateForCalcs = driverRows.map { row ->
                    AnnualReportedValue("${row.year}-12-31", row.effectiveTaxBps / 10_000.0)
                },
                totalDebt = driverRows.mapNotNull { row ->
                    row.debt?.let { debt -> AnnualReportedValue("${row.year}-12-31", debt) }
                },
                marginalTaxRate = driverRows.map { row ->
                    AnnualReportedValue(
                        asOfDate = "${row.year}-12-31",
                        value = row.marginalTaxBps / 10_000.0,
                        concept = "USStatutoryTaxRate",
                    )
                },
            )
            rows += runCase(
                symbol = member.symbol,
                case = "qa_baseline_cohort",
                fundamentals = fundamentals,
                timeseries = timeseries,
                marketPriceCents = inputs.marketPriceCents,
            )
        }

        rows += runCase(
            symbol = "T",
            case = "checklist_t_contract",
            fundamentals = FundamentalSnapshot(
                symbol = "T",
                sectorName = "Communication Services",
                industryName = "Telecom Services",
                marketCapDollars = 146_748_915_712L,
                sharesOutstanding = 6_948_338_835L,
                betaMillis = 422,
                totalDebtDollars = 159_750_995_968L,
                totalCashDollars = 11_964_000_256L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    AnnualReportedValue("2021-12-31", 26_420_000_000.0),
                    AnnualReportedValue("2022-12-31", 12_397_000_000.0),
                    AnnualReportedValue("2023-12-31", 20_460_000_000.0),
                    AnnualReportedValue("2024-12-31", 18_510_000_000.0),
                    AnnualReportedValue("2025-12-31", 19_440_000_000.0),
                ),
                operatingCashFlow = listOf(
                    AnnualReportedValue("2022-12-31", 32_023_000_000.0),
                    AnnualReportedValue("2023-12-31", 38_314_000_000.0),
                    AnnualReportedValue("2024-12-31", 38_771_000_000.0),
                    AnnualReportedValue("2025-12-31", 40_284_000_000.0),
                ),
                capitalExpenditure = listOf(
                    AnnualReportedValue("2022-12-31", -19_626_000_000.0),
                    AnnualReportedValue("2023-12-31", -17_853_000_000.0),
                    AnnualReportedValue("2024-12-31", -20_263_000_000.0),
                    AnnualReportedValue("2025-12-31", -20_842_000_000.0),
                ),
                revenue = listOf(
                    AnnualReportedValue("2022-12-31", 120_741_000_000.0),
                    AnnualReportedValue("2023-12-31", 122_428_000_000.0),
                    AnnualReportedValue("2024-12-31", 122_336_000_000.0),
                    AnnualReportedValue("2025-12-31", 125_648_000_000.0),
                ),
                interestExpense = listOf(
                    AnnualReportedValue("2022-12-31", 6_108_000_000.0),
                    AnnualReportedValue("2023-12-31", 6_704_000_000.0),
                    AnnualReportedValue("2024-12-31", 6_759_000_000.0),
                    AnnualReportedValue("2025-12-31", 6_804_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2022-12-31", 0.21),
                    AnnualReportedValue("2023-12-31", 0.213),
                    AnnualReportedValue("2024-12-31", 0.266),
                    AnnualReportedValue("2025-12-31", 0.134),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2022-12-31", 154_679_000_000.0),
                    AnnualReportedValue("2023-12-31", 154_899_000_000.0),
                    AnnualReportedValue("2024-12-31", 140_923_000_000.0),
                    AnnualReportedValue("2025-12-31", 155_043_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2022-12-31", 0.21, concept = "ReportedMarginalTax"),
                    AnnualReportedValue("2023-12-31", 0.21, concept = "ReportedMarginalTax"),
                    AnnualReportedValue("2024-12-31", 0.21, concept = "ReportedMarginalTax"),
                    AnnualReportedValue("2025-12-31", 0.21, concept = "ReportedMarginalTax"),
                ),
            ),
            marketPriceCents = 2_112L,
        )

        rows += runCase(
            symbol = "AMZN",
            case = "checklist_amzn_contract",
            fundamentals = FundamentalSnapshot(
                symbol = "AMZN",
                sectorName = "Consumer Cyclical",
                industryName = "Internet Retail",
                marketCapDollars = 2_574_493_679_616L,
                sharesOutstanding = 10_757_109_436L,
                betaMillis = 1_461,
                totalDebtDollars = 235_540_004_864L,
                totalCashDollars = 143_088_992_256L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    AnnualReportedValue("2020-12-31", 25_924_000_000.0),
                    AnnualReportedValue("2021-12-31", -14_726_000_000.0),
                    AnnualReportedValue("2022-12-31", -16_893_000_000.0),
                    AnnualReportedValue("2023-12-31", 32_217_000_000.0),
                    AnnualReportedValue("2024-12-31", 32_878_000_000.0),
                    AnnualReportedValue("2025-12-31", 7_695_000_000.0),
                ),
                operatingCashFlow = listOf(
                    AnnualReportedValue("2022-12-31", 46_752_000_000.0),
                    AnnualReportedValue("2023-12-31", 84_946_000_000.0),
                    AnnualReportedValue("2024-12-31", 115_877_000_000.0),
                    AnnualReportedValue("2025-12-31", 139_514_000_000.0),
                ),
                capitalExpenditure = listOf(
                    AnnualReportedValue("2022-12-31", -63_645_000_000.0),
                    AnnualReportedValue("2023-12-31", -52_729_000_000.0),
                    AnnualReportedValue("2024-12-31", -82_999_000_000.0),
                    AnnualReportedValue("2025-12-31", -131_819_000_000.0),
                ),
                revenue = listOf(
                    AnnualReportedValue("2022-12-31", 513_983_000_000.0),
                    AnnualReportedValue("2023-12-31", 574_785_000_000.0),
                    AnnualReportedValue("2024-12-31", 637_959_000_000.0),
                    AnnualReportedValue("2025-12-31", 716_924_000_000.0),
                ),
                interestExpense = listOf(
                    AnnualReportedValue("2022-12-31", 2_367_000_000.0),
                    AnnualReportedValue("2023-12-31", 3_182_000_000.0),
                    AnnualReportedValue("2024-12-31", 2_406_000_000.0),
                    AnnualReportedValue("2025-12-31", 2_274_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2022-12-31", 0.21),
                    AnnualReportedValue("2023-12-31", 0.1896),
                    AnnualReportedValue("2024-12-31", 0.135),
                    AnnualReportedValue("2025-12-31", 0.1961),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2022-12-31", 178_500_000_000.0),
                    AnnualReportedValue("2023-12-31", 177_500_000_000.0),
                    AnnualReportedValue("2024-12-31", 178_000_000_000.0),
                    AnnualReportedValue("2025-12-31", 235_540_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2022-12-31", 0.21, concept = "ReportedMarginalTax"),
                    AnnualReportedValue("2023-12-31", 0.21, concept = "ReportedMarginalTax"),
                    AnnualReportedValue("2024-12-31", 0.21, concept = "ReportedMarginalTax"),
                    AnnualReportedValue("2025-12-31", 0.21, concept = "ReportedMarginalTax"),
                ),
            ),
            marketPriceCents = 23_933L,
        )

        rows += runCase(
            symbol = "ACGL",
            case = "checklist_acgl_ri",
            fundamentals = FundamentalSnapshot(
                symbol = "ACGL",
                sectorName = "Financial Services",
                industryName = "Insurance - Property & Casualty",
                marketCapDollars = 36_000_000_000L,
                sharesOutstanding = 349_390_000L,
                betaMillis = 292,
                returnOnEquityBps = 2_000,
                bookValuePerShareCents = 6_511L,
                priceToBookHundredths = 159,
                retentionBps = 7_000,
            ),
            timeseries = FundamentalTimeseries(),
            marketPriceCents = 10_336L,
        )

        val export = ParityExport(
            surface = "android",
            profileScope = "qa_baseline_cohort_20_plus_checklist_T_AMZN_ACGL",
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            rows = rows,
        )

        val out = repoTmpExportPath()
        Files.createDirectories(out.parent)
        Files.writeString(out, json.encodeToString(export))
        assertTrue(Files.isRegularFile(out), "export missing at $out")
        println("wrote Android QA parity snapshot: $out")
    }

    private fun runCase(
        symbol: String,
        case: String,
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long,
    ): ParityRow {
        val result = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = timeseries,
            marketPriceCents = marketPriceCents,
            marketParams = MarketParams(), // default provisional=true — Windows default_usd parity
        )
        return result.fold(
            onSuccess = { a ->
                ParityRow(
                    symbol = symbol,
                    caseName = case,
                    ok = true,
                    error = null,
                    engineVersion = a.engineVersion,
                    modelPolicyVersion = a.modelPolicyVersion,
                    businessClass = a.businessClass.name,
                    model = a.model.name,
                    discountRateKind = a.discountRateKind.name,
                    bearCents = a.bearIntrinsicValueCents,
                    baseCents = a.baseIntrinsicValueCents,
                    bullCents = a.bullIntrinsicValueCents,
                    waccBps = a.waccBps,
                    baseGrowthBps = a.baseGrowthBps,
                    netDebtDollars = a.netDebtDollars,
                    provisionalWaccUpliftBps = a.provisionalWaccUpliftBps,
                    latestFcfDollars = a.latestFcfDollars,
                    fcfRunRateDollars = a.fcfRunRateDollars,
                    fcfRunRateNormalized = a.fcfRunRateNormalized,
                    debtWeightBps = a.debtWeightBps,
                    pointEstimateUnreliable = a.pointEstimateUnreliable,
                    scenarioStress = a.scenarioStress,
                    waccBearBps = a.waccBearBps,
                    waccBullBps = a.waccBullBps,
                    valuationDriver = a.valuationDriver,
                    latestRevenueDollars = a.latestRevenueDollars,
                    normalizedFcffDollars = a.normalizedFcffDollars,
                    normalizedOcfMarginBps = a.normalizedOcfMarginBps,
                    normalizedCapexIntensityBps = a.normalizedCapexIntensityBps,
                    normalizedAfterTaxInterestMarginBps = a.normalizedAfterTaxInterestMarginBps,
                    capexSpikeYears = a.capexSpikeYears,
                    growthDriver = a.growthDriver,
                    driverInputFingerprint = a.driverInputFingerprint,
                    driverProvenance = a.driverProvenance,
                    waccMarketCapSource = a.waccInputs.marketCap.name,
                    waccBetaSource = a.waccInputs.beta.name,
                    waccTotalDebtSource = a.waccInputs.totalDebt.name,
                    waccTotalCashSource = a.waccInputs.totalCash.name,
                    waccCostOfDebtSource = a.waccInputs.costOfDebt.name,
                    waccTaxSource = a.waccInputs.taxRate.name,
                    reasonCodes = a.reasonCodes,
                )
            },
            onFailure = { err ->
                ParityRow(
                    symbol = symbol,
                    caseName = case,
                    ok = false,
                    error = err.message,
                )
            },
        )
    }

    private fun loadCohort(): CohortFile {
        val path = findFixturePath("apps/windows/src-tauri/tests/fixtures/valuation/baseline_cohort_2026-07-30.json")
        return json.decodeFromString(Files.readString(path))
    }

    private fun loadDriverData(): DriverFile {
        val path = findFixturePath("apps/windows/src-tauri/tests/fixtures/valuation/baseline_driver_data_2026-07-30.json")
        return json.decodeFromString(Files.readString(path))
    }

    private fun repoTmpExportPath(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            if (Files.isDirectory(current.resolve(".git")) || Files.isDirectory(current.resolve("apps"))) {
                return current.resolve(".agents/workspace/tmp/parity-android-qa.json").normalize()
            }
            current = current.parent ?: return@repeat
        }
        return Paths.get(".agents/workspace/tmp/parity-android-qa.json").toAbsolutePath().normalize()
    }

    private fun findFixturePath(relative: String): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve(relative).normalize()
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("fixture not found: $relative from ${Paths.get("").toAbsolutePath()}")
    }

    @Test
    fun export_random20_sp500_parity_snapshot() {
        val path = findFixturePath(".agents/workspace/tmp/random20-inputs.json")
        val file = json.decodeFromString<Random20File>(Files.readString(path))
        assertTrue(file.members.size == 20, "expected 20 random symbols, got ${file.members.size}")

        val rows = file.members.map { m ->
            val fundamentals = FundamentalSnapshot(
                symbol = m.symbol,
                sectorName = m.sectorName,
                industryName = m.industryName,
                marketCapDollars = m.marketCapDollars,
                sharesOutstanding = m.sharesOutstanding,
                betaMillis = m.betaMillis,
                totalDebtDollars = m.totalDebtDollars,
                totalCashDollars = m.totalCashDollars,
                bookValuePerShareCents = m.bookValuePerShareCents,
                returnOnEquityBps = m.returnOnEquityBps,
            )
            val timeseries = FundamentalTimeseries(
                freeCashFlow = m.fcfAnnual.map { point ->
                    AnnualReportedValue("${point.year}-12-31", point.valueDollars)
                },
            )
            runCase(
                symbol = m.symbol,
                case = "random20_sp500",
                fundamentals = fundamentals,
                timeseries = timeseries,
                marketPriceCents = m.marketPriceCents,
            )
        }

        val export = ParityExport(
            surface = "android",
            profileScope = "random20_sp500_seed_20260730",
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            rows = rows,
        )
        val out = repoRoot().resolve(".agents/workspace/tmp/parity-android-random20.json").normalize()
        Files.createDirectories(out.parent)
        Files.writeString(out, json.encodeToString(export))
        assertTrue(Files.isRegularFile(out), "export missing at $out")
        println("wrote Android random20 parity snapshot: $out")
    }

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            if (Files.isDirectory(current.resolve(".git")) || Files.isDirectory(current.resolve("apps"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        return Paths.get("").toAbsolutePath()
    }
}

@Serializable
private data class ParityExport(
    val surface: String,
    @SerialName("profile_scope") val profileScope: String,
    @SerialName("engine_version") val engineVersion: String,
    @SerialName("model_policy_version") val modelPolicyVersion: String,
    val rows: List<ParityRow>,
)

@Serializable
private data class ParityRow(
    val symbol: String,
    @SerialName("case") val caseName: String,
    val ok: Boolean,
    val error: String? = null,
    @SerialName("engine_version") val engineVersion: String? = null,
    @SerialName("model_policy_version") val modelPolicyVersion: String? = null,
    @SerialName("business_class") val businessClass: String? = null,
    val model: String? = null,
    @SerialName("discount_rate_kind") val discountRateKind: String? = null,
    @SerialName("bear_cents") val bearCents: Long? = null,
    @SerialName("base_cents") val baseCents: Long? = null,
    @SerialName("bull_cents") val bullCents: Long? = null,
    @SerialName("wacc_bps") val waccBps: Int? = null,
    @SerialName("base_growth_bps") val baseGrowthBps: Int? = null,
    @SerialName("net_debt_dollars") val netDebtDollars: Long? = null,
    @SerialName("provisional_wacc_uplift_bps") val provisionalWaccUpliftBps: Int? = null,
    @SerialName("latest_fcf_dollars") val latestFcfDollars: Long? = null,
    @SerialName("fcf_run_rate_dollars") val fcfRunRateDollars: Long? = null,
    @SerialName("fcf_run_rate_normalized") val fcfRunRateNormalized: Boolean? = null,
    @SerialName("debt_weight_bps") val debtWeightBps: Int? = null,
    @SerialName("point_estimate_unreliable") val pointEstimateUnreliable: Boolean? = null,
    @SerialName("scenario_stress") val scenarioStress: String? = null,
    @SerialName("wacc_bear_bps") val waccBearBps: Int? = null,
    @SerialName("wacc_bull_bps") val waccBullBps: Int? = null,
    @SerialName("valuation_driver") val valuationDriver: String? = null,
    @SerialName("latest_revenue_dollars") val latestRevenueDollars: Long? = null,
    @SerialName("normalized_fcff_dollars") val normalizedFcffDollars: Long? = null,
    @SerialName("normalized_ocf_margin_bps") val normalizedOcfMarginBps: Int? = null,
    @SerialName("normalized_capex_intensity_bps") val normalizedCapexIntensityBps: Int? = null,
    @SerialName("normalized_after_tax_interest_margin_bps") val normalizedAfterTaxInterestMarginBps: Int? = null,
    @SerialName("capex_spike_years") val capexSpikeYears: List<Int> = emptyList(),
    @SerialName("growth_driver") val growthDriver: String? = null,
    @SerialName("driver_input_fingerprint") val driverInputFingerprint: String? = null,
    @SerialName("driver_provenance") val driverProvenance: List<String> = emptyList(),
    @SerialName("wacc_market_cap_source") val waccMarketCapSource: String? = null,
    @SerialName("wacc_beta_source") val waccBetaSource: String? = null,
    @SerialName("wacc_total_debt_source") val waccTotalDebtSource: String? = null,
    @SerialName("wacc_total_cash_source") val waccTotalCashSource: String? = null,
    @SerialName("wacc_cost_of_debt_source") val waccCostOfDebtSource: String? = null,
    @SerialName("wacc_tax_source") val waccTaxSource: String? = null,
    @SerialName("reason_codes") val reasonCodes: List<String> = emptyList(),
)

@Serializable
private data class CohortFile(
    val members: List<CohortMember>,
)

@Serializable
private data class CohortMember(
    val symbol: String,
    val quarantine: Boolean = false,
    val inputs: CohortInputs,
)

@Serializable
private data class CohortInputs(
    @SerialName("market_price_cents") val marketPriceCents: Long,
    @SerialName("shares_outstanding") val sharesOutstanding: Long? = null,
    @SerialName("market_cap_dollars") val marketCapDollars: Long? = null,
    @SerialName("total_debt_dollars") val totalDebtDollars: Long,
    @SerialName("total_cash_dollars") val totalCashDollars: Long,
    @SerialName("beta_millis") val betaMillis: Int,
    @SerialName("sector_name") val sectorName: String,
    @SerialName("industry_name") val industryName: String,
    @SerialName("fcf_annual") val fcfAnnual: List<CohortFcf>,
)

@Serializable
private data class CohortFcf(
    val year: Int,
    @SerialName("value_dollars") val valueDollars: Double,
)

@Serializable
private data class DriverFile(
    val rows: Map<String, List<DriverAnnual>>,
)

@Serializable
private data class DriverAnnual(
    val year: Int,
    val ocf: Double,
    val capex: Double,
    val revenue: Double,
    val interest: Double,
    @SerialName("effective_tax_bps") val effectiveTaxBps: Int,
    val debt: Double? = null,
    @SerialName("marginal_tax_bps") val marginalTaxBps: Int,
)

@Serializable
private data class Random20File(
    val members: List<Random20Member>,
)

@Serializable
private data class Random20Member(
    val symbol: String,
    @SerialName("sector_name") val sectorName: String,
    @SerialName("industry_name") val industryName: String,
    @SerialName("market_price_cents") val marketPriceCents: Long,
    @SerialName("shares_outstanding") val sharesOutstanding: Long? = null,
    @SerialName("market_cap_dollars") val marketCapDollars: Long? = null,
    @SerialName("beta_millis") val betaMillis: Int = 1000,
    @SerialName("total_debt_dollars") val totalDebtDollars: Long = 0,
    @SerialName("total_cash_dollars") val totalCashDollars: Long = 0,
    @SerialName("book_value_per_share_cents") val bookValuePerShareCents: Long? = null,
    @SerialName("return_on_equity_bps") val returnOnEquityBps: Int? = null,
    @SerialName("fcf_annual") val fcfAnnual: List<CohortFcf> = emptyList(),
)
