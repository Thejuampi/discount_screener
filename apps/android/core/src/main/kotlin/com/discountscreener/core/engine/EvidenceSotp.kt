package com.discountscreener.core.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigInteger
import kotlin.jvm.JvmName

/**
 * Portable point-in-time evidence and component/SOTP kernel.
 *
 * The Android shell is only an adapter: it supplies observations and persists
 * the typed result. This file deliberately has no network, SQLite, or Compose
 * dependency. Public numeric values remain fixed-point integers.
 */
object EvidenceSotpEngine {
    const val SPEC_ID = "SPEC-valuation-evidence-sotp"
    const val ENGINE_VERSION = "valuation-evidence-sotp/1"
    const val MODEL_POLICY_VERSION = "evidence-sotp-policy/1"
    const val RESOLVER_POLICY_VERSION = "pit-evidence-resolver/1"

    fun replay(observations: List<EvidenceObservation>, decisionAt: String): PitReplay {
        require(decisionAt.isNotBlank()) { "invalid_evidence: decision_at is empty" }
        val rejected = mutableListOf<EvidenceRejection>()
        val candidates = linkedMapOf<Pair<String, String>, MutableList<EvidenceObservation>>()
        observations.forEach { observation ->
            val validation = observation.validationError()
            if (observation.retrievalState == RetrievalState.Failed || observation.extractionMethod == ExtractionMethod.RetrievalFailure) {
                rejected += EvidenceRejection(observation.id, EvidenceRejectionCode.RetrievalFailure, "retrieval failed; no zero or imputed value is admitted")
            } else if (validation != null) {
                rejected += EvidenceRejection(observation.id, EvidenceRejectionCode.InvalidEvidence, validation)
            } else if (observation.knowledgeAt > decisionAt || observation.publicationAt > decisionAt || observation.retrievedAt > decisionAt) {
                rejected += EvidenceRejection(observation.id, EvidenceRejectionCode.NotKnownAtDecision, "observation was not available at the replay decision time")
            } else {
                candidates.getOrPut(observation.factKey to observation.economicPeriodEnd) { mutableListOf() } += observation
            }
        }
        val selected = mutableListOf<EvidenceObservation>()
        candidates.values.forEach { rows ->
            rows.sortWith(compareBy<EvidenceObservation> { it.knowledgeAt }.thenBy { it.publicationAt }.thenBy { it.revisionId })
            if (rows.size >= 2) {
                val last = rows[rows.lastIndex]
                val previous = rows[rows.lastIndex - 1]
                if (last.knowledgeAt == previous.knowledgeAt && last.publicationAt == previous.publicationAt && last.valueKey() != previous.valueKey()) {
                    throw ValuationRefusalException("duplicate_evidence", "conflicting evidence at the same publication rank for ${last.factKey}")
                }
            }
            rows.lastOrNull()?.let(selected::add)
        }
        selected.sortBy { it.id }
        rejected.sortBy { it.observationId }
        val canonical = (selected.map { "selected|${it.canonical()}" } + rejected.map { "rejected|${it.observationId}|${it.code.serialized}|${it.detail}" }).sorted().joinToString("\n")
        return PitReplay(decisionAt, selected, rejected, "fnv1a64:${fnv1a64(canonical)}")
    }

    fun route(input: ClassificationInput): ComponentFamily {
        if (input.assetClass != AssetClass.Equity) return ComponentFamily.NotEligible
        val sector = normalize(input.sector)
        val industry = normalize(input.industry)
        val blob = "$sector $industry"
        if (sector.isBlank() && industry.isBlank()) return ComponentFamily.Unclassified
        if (blob.containsAny("financial", "insurance", "bank", "capital markets", "asset management", "credit services", "mortgage finance", "reinsurance", "broker", "healthcare plans", "health care plans", "managed care", "health insurance")) return ComponentFamily.FinancialServices
        if (sector.containsAny("utilities") || industry.containsAny("regulated electric", "regulated gas", "regulated water", "regulated utility", "electric utilities", "gas utilities", "water utilities")) return ComponentFamily.RegulatedUtility
        if (industry.containsAny("midstream", "pipeline", "toll road", "airport services", "marine ports", "railroad infrastructure", "contracted infrastructure")) return ComponentFamily.ContractedInfrastructure
        if (sector.containsAny("energy", "basic materials") || industry.containsAny("oil", "gas", "coal", "gold", "silver", "copper", "mining", "metals", "exploration", "production", "resource producer")) return ComponentFamily.ResourceProducer
        if (sector.containsAny("technology", "industrials", "consumer", "healthcare", "communication", "materials", "real estate services") || industry.containsAny("software", "semiconductor", "pharma", "biotech", "drug", "medical device", "specialty industrial", "retail")) return ComponentFamily.OperatingNonFinancial
        return ComponentFamily.Unclassified
    }

    /** Closed-world valuation boundary: no generic FCFF default for unknown classes. */
    fun requireEligibleComponent(input: ClassificationInput): ComponentFamily = when (val family = route(input)) {
        ComponentFamily.Unclassified -> fail("unclassified_sector", "no valuation family for sector=${input.sector}, industry=${input.industry}")
        ComponentFamily.NotEligible -> fail("not_eligible", "asset class is not eligible for intrinsic valuation")
        else -> family
    }

    fun consolidate(input: SotpInput): SotpOutput {
        val reasons = mutableSetOf<String>()
        var coveredEv = BigInteger.ZERO
        var allValued = input.issuer.isNotBlank() && input.sourceFingerprint.isNotBlank()
        if (input.issuer.isBlank() || input.sourceFingerprint.isBlank()) reasons += RefusalCode.InvalidEvidence.serialized
        var quality = EvidenceQuality.Solid
        input.components.forEach { component ->
            val valuation = component.valuation
            if (valuation != null) {
                if (valuation.componentId != component.componentId || valuation.status != ComponentStatus.Publishable || valuation.evidenceRefs.none(String::isNotBlank)) {
                    allValued = false
                    reasons += RefusalCode.InvalidEvidence.serialized
                } else {
                    coveredEv = coveredEv.add(valuation.enterpriseValueCents.bi())
                    if (valuation.quality.evidenceQuality == EvidenceQuality.Provisional) quality = EvidenceQuality.Provisional
                }
            } else if (component.material) {
                allValued = false
                reasons += component.refusal?.code ?: RefusalCode.IncompleteSegmentDisclosures.serialized
            } else {
                quality = EvidenceQuality.Provisional
                reasons += "immaterial_component_unresolved"
            }
        }
        val overhead = input.corporateOverhead
        when {
            overhead == null -> {
                reasons += "unallocated_overhead_ambiguity"
                allValued = false
            }
            overhead.evidenceRefs.none(String::isNotBlank) -> {
                reasons += "unallocated_overhead_ambiguity"
                allValued = false
                if (!overhead.material) quality = EvidenceQuality.Provisional
            }
            overhead.enterpriseValueCents > 0L -> {
                reasons += "unallocated_overhead_ambiguity"
                allValued = false
            }
            else -> {
                coveredEv = coveredEv.add(overhead.enterpriseValueCents.bi())
                if (!overhead.material) quality = EvidenceQuality.Provisional
            }
        }
        input.bridge.separatelyValuedInvestments.forEach { investment ->
            if (investment.evidenceRefs.isEmpty()) {
                allValued = false
                reasons += RefusalCode.UnresolvedCapitalBridge.serialized
            } else {
                coveredEv = coveredEv.add(investment.amountCents.bi())
            }
        }
        var claims = BigInteger.ZERO
        listOf(
            input.bridge.netDebt,
            input.bridge.nonControllingInterest,
            input.bridge.preferredClaims,
            input.bridge.otherSeniorClaims,
        ).forEach { item ->
            if (item != null && item.evidenceRefs.isNotEmpty()) {
                claims = claims.add(item.amountCents.bi())
            } else {
                allValued = false
                reasons += RefusalCode.UnresolvedCapitalBridge.serialized
            }
        }
        val covered = coveredEv.toLongOrNull()
        val shares = input.shares?.takeIf { it.amountCents > 0L && it.evidenceRefs.isNotEmpty() }
        val equityAndPrice: Pair<BigInteger?, Long?> = if (allValued && shares != null) {
            val equity = (coveredEv - claims).max(BigInteger.ZERO)
            equity to roundDiv(equity, shares.amountCents.bi())
        } else {
            if (shares == null) reasons += RefusalCode.MissingShares.serialized
            null to null
        }
        val reasonCodes = reasons.toList().sorted()
        val status = when {
            equityAndPrice.first != null -> SotpStatus.Published
            covered != null -> SotpStatus.CoveredEvOnly
            else -> SotpStatus.Unavailable
        }
        return SotpOutput(
            status = status,
            coveredEnterpriseValueCents = covered,
            equityValueCents = equityAndPrice.first?.toLongOrNull(),
            intrinsicPriceCents = equityAndPrice.second,
            valuationScoreEligible = equityAndPrice.first != null && reasonCodes.isEmpty(),
            reasonCodes = reasonCodes,
            componentQuality = quality,
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            resolverPolicyVersion = RESOLVER_POLICY_VERSION,
            sourceFingerprint = input.sourceFingerprint,
        )
    }

    fun compareExternalRange(intrinsicBaseCents: Long, intrinsicHorizonDays: Int, intrinsicDefinition: String, analyst: AnalystRange): DisagreementResult {
        if (intrinsicBaseCents <= 0L || analyst.baseCents <= 0L || analyst.lowCents > analyst.baseCents || analyst.baseCents > analyst.highCents || analyst.horizonDays != intrinsicHorizonDays || analyst.definition != intrinsicDefinition || analyst.evidenceRefs.isEmpty()) {
            return DisagreementResult(DisagreementStatus.Unavailable, null, listOf("incompatible_external_anchor"))
        }
        val gap = roundDiv((intrinsicBaseCents - analyst.baseCents).bi().abs() * BigInteger.TEN.pow(4), analyst.baseCents.bi())?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
        val status = when {
            gap <= 2_500 -> DisagreementStatus.Aligned
            gap <= 5_000 -> DisagreementStatus.Tension
            else -> DisagreementStatus.Disputed
        }
        return DisagreementResult(status, gap, listOf("external_range_diagnostic_only"))
    }

    fun validateDriverForecast(coverage: HistoricalValidationCoverage, forecast: DriverForecast, actual: DriverActual): DriverValidationResult {
        val decisionDate = forecast.decisionAt.take(10)
        val hasMembership = coverage.membership.any { membership ->
            membership.symbol == forecast.symbol &&
                membership.effectiveFrom <= decisionDate &&
                (membership.effectiveTo == null || decisionDate < membership.effectiveTo) &&
                membership.knowledgeAt <= forecast.decisionAt &&
                membership.sourceLocation.isNotBlank()
        }
        if (!hasMembership || forecast.symbol != actual.symbol || forecast.driver != actual.driver || actual.knowledgeAt <= forecast.decisionAt) {
            return DriverValidationResult(ValidationStatus.Unavailable, 0, null, hasMembership, null, false, listOf(RefusalCode.HistoricalValidationCoverageUnavailable.serialized))
        }
        val error = roundDiv((forecast.forecastMillis - actual.actualMillis).bi().abs() * BigInteger.TEN.pow(4), actual.actualMillis.bi().abs().max(BigInteger.ONE))?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
        return DriverValidationResult(ValidationStatus.Measured, 1, error, true, null, false, listOf("primary=reported_driver_accuracy"))
    }

    fun cacheDisposition(status: SotpStatus): CacheDisposition = when (status) {
        SotpStatus.Published -> CacheDisposition.StorePublishable
        SotpStatus.CoveredEvOnly -> CacheDisposition.StoreCoveredEvOnly
        SotpStatus.Unavailable -> CacheDisposition.ClearStaleIntrinsic
    }

    fun valueOperatingComponent(input: OperatingComponentInput): ComponentValuation {
        requireSupported(input.sourceRegime)
        requireEvidence(input.componentId, input.evidenceRefs)
        requirePeriodEvidence(input.evidencePeriods)
        val latest = input.fcffByYear.maxByOrNull { it.year }?.fcffCents ?: fail("missing_required_driver", "operating FCFF history is empty")
        require(latest > 0L && input.explicitYears > 0 && input.waccBps > 0) { "missing_required_driver: positive FCFF, WACC, and horizon are required" }
        require(input.stableGrowthBps < input.waccBps) { "missing_terminal_reinvestment_link: stable growth must remain strictly below WACC" }
        val impliedGrowth = mulDiv(bi(input.terminalRoicBps), bi(input.terminalReinvestmentBps), BI_10_000)?.toInt()
        require(input.terminalNopatCents > 0L && input.terminalRoicBps > 0 && input.terminalReinvestmentBps in 0..10_000 && impliedGrowth == input.stableGrowthBps) {
            "missing_terminal_reinvestment_link: terminal growth must declare consistent ROIC and reinvestment"
        }
        require(input.sbcTreatment != SbcTreatment.Unreconciled) { "unreconciled_sbc_treatment: SBC must be included as expense or represented once through dilution" }
        val spread = input.scenarioSpreadBps.coerceAtLeast(0)
        val bear = discountedOperatingValue(latest, input.nearGrowthBps - spread, input)
        val base = discountedOperatingValue(latest, input.nearGrowthBps, input)
        val bull = discountedOperatingValue(latest, input.nearGrowthBps + spread, input)
        return ComponentValuation(
            componentId = input.componentId,
            family = ComponentFamily.OperatingNonFinancial,
            model = ComponentModel.FcffWacc,
            status = ComponentStatus.Publishable,
            enterpriseValueCents = base,
            scenarios = ScenarioValues(bear, base, bull),
            discountRateBps = input.waccBps,
            discountRateKind = DiscountRateKind.Wacc,
            sourceRegime = input.sourceRegime,
            evidenceRefs = input.evidenceRefs,
            quality = componentQuality(input.evidencePeriods, bear, base, bull),
            reasonCodes = listOf("model=fcff_wacc", "terminal_growth=linked_to_roic_and_reinvestment", "sbc_treatment=" + input.sbcTreatment.name.lowercase()),
        )
    }

    fun valueFinancialServices(input: FinancialServicesComponentInput): ComponentValuation {
        requireSupported(input.sourceRegime)
        requireEvidence(input.componentId, input.evidenceRefs)
        requirePeriodEvidence(input.evidencePeriods)
        require(input.bookEquityCents > 0L && input.shares > 0L && input.explicitYears > 0 && input.costOfEquityBps > 0 && input.retentionBps in 0..10_000) {
            "missing_required_driver: positive book, shares, cost of equity, retention, and horizon are required"
        }
        val spread = input.scenarioRoeSpreadBps.coerceAtLeast(0)
        val rateSpread = input.scenarioRateSpreadBps.coerceAtLeast(0)
        val bear = residualIncomeValue(input, input.roeBps - spread, input.costOfEquityBps + rateSpread)
        val base = residualIncomeValue(input, input.roeBps, input.costOfEquityBps)
        val bull = residualIncomeValue(input, input.roeBps + spread, (input.costOfEquityBps - rateSpread).coerceAtLeast(1))
        return ComponentValuation(
            componentId = input.componentId,
            family = ComponentFamily.FinancialServices,
            model = ComponentModel.ResidualIncomeEquity,
            status = ComponentStatus.Publishable,
            enterpriseValueCents = base,
            scenarios = ScenarioValues(bear, base, bull),
            discountRateBps = input.costOfEquityBps,
            discountRateKind = DiscountRateKind.CostOfEquity,
            sourceRegime = input.sourceRegime,
            evidenceRefs = input.evidenceRefs,
            quality = componentQuality(input.evidencePeriods, bear, base, bull),
            reasonCodes = listOf("model=residual_income_equity", "primary_cash_flow_definition=not_ocf_minus_ppe_capex", "terminal_roe=fades_to_competitive_long_run"),
        )
    }

    private fun discountedOperatingValue(latest: Long, growthBps: Int, input: OperatingComponentInput): Long {
        var cashFlow = bi(latest)
        var presentValue = BigInteger.ZERO
        repeat(input.explicitYears) { index ->
            cashFlow = mulDiv(cashFlow, bi(10_000L + growthBps), BI_10_000) ?: fail("missing_required_driver", "FCFF projection overflow")
            val factor = discountFactorPpm(input.waccBps, index + 1)
            presentValue = presentValue.add(roundBig(cashFlow.multiply(factor), BI_1_000_000) ?: fail("missing_required_driver", "FCFF PV invalid"))
        }
        val terminalFcff = mulDiv(bi(input.terminalNopatCents), bi(10_000L - input.terminalReinvestmentBps), BI_10_000) ?: fail("missing_required_driver", "terminal FCFF invalid")
        val terminal = terminalFcff.multiply(BI_10_000).divide(bi(input.waccBps - input.stableGrowthBps))
        val factor = discountFactorPpm(input.waccBps, input.explicitYears)
        presentValue = presentValue.add(roundBig(terminal.multiply(factor), BI_1_000_000) ?: fail("missing_required_driver", "terminal PV invalid"))
        return exactLong(presentValue, "operating enterprise value exceeds fixed-point range")
    }

    private fun residualIncomeValue(input: FinancialServicesComponentInput, initialRoeBps: Int, reBps: Int): Long {
        require(reBps > 0) { "missing_required_driver: cost of equity must be positive" }
        var book = bi(input.bookEquityCents)
        var presentValue = BigInteger.ZERO
        repeat(input.explicitYears) { index ->
            val year = bi((index + 1).toLong())
            val roe = bi(initialRoeBps).add(bi(input.stableRoeBps - initialRoeBps).multiply(year).divide(bi(input.explicitYears.toLong())))
            val residual = book.multiply(roe.subtract(bi(reBps))).divide(BI_10_000)
            val factor = discountFactorPpm(reBps, index + 1)
            presentValue = presentValue.add(roundBig(residual.multiply(factor), BI_1_000_000) ?: fail("missing_required_driver", "residual income PV invalid"))
            book = book.add(mulDiv(book, roe.multiply(bi(input.retentionBps.toLong())), BI_100_000_000) ?: fail("missing_required_driver", "book value overflow"))
            require(book > BigInteger.ZERO) { "missing_required_driver: clean-surplus book value became non-positive" }
        }
        val stableGrowth = exactInt(mulDiv(bi(input.stableRoeBps.toLong()), bi(input.retentionBps.toLong()), BI_10_000), "stable book growth invalid")
        require(input.stableRoeBps == reBps || stableGrowth < reBps) { "missing_terminal_reinvestment_link: stable book growth must remain below cost of equity" }
        val terminal = if (input.stableRoeBps == reBps) {
            book
        } else {
            val nextBook = mulDiv(book, bi(10_000L + stableGrowth), BI_10_000) ?: fail("missing_required_driver", "terminal book value invalid")
            val residualNext = mulDiv(nextBook, bi((input.stableRoeBps - reBps).toLong()), BI_10_000) ?: fail("missing_required_driver", "terminal residual invalid")
            book.add(residualNext.multiply(BI_10_000).divide(bi((reBps - stableGrowth).toLong())))
        }
        val factor = discountFactorPpm(reBps, input.explicitYears)
        val total = bi(input.bookEquityCents).add(presentValue).add(roundBig(terminal.multiply(factor), BI_1_000_000) ?: fail("missing_required_driver", "terminal PV invalid"))
        return exactLong(total, "residual income value exceeds fixed-point range")
    }

    private fun requireSupported(sourceRegime: SourceRegime) {
        require(sourceRegime == SourceRegime.DomesticUsGaap) { "source_regime_unsupported: source regime has no native normalizer" }
    }

    private fun requireEvidence(name: String, refs: List<String>) {
        require(refs.any(String::isNotBlank)) { "missing_driver_evidence: " + name + " has no evidence reference" }
    }

    private fun requirePeriodEvidence(periods: Int) {
        require(periods > 0) { "missing_driver_evidence: at least one dated evidence period is required" }
    }

    private fun componentQuality(periods: Int, bear: Long, base: Long, bull: Long): ComponentQuality {
        val evidence = when {
            periods >= 3 -> EvidenceQuality.Solid
            periods > 0 -> EvidenceQuality.Provisional
            else -> EvidenceQuality.Unavailable
        }
        val uncertainty = if (base == 0L) 10_000 else (roundBig((bull - bear).bi().abs() * BI_10_000, base.bi().abs().max(BigInteger.ONE)) ?: BigInteger.valueOf(Int.MAX_VALUE.toLong())).coerceAtMost(Int.MAX_VALUE.toLong().bi()).toInt()
        return ComponentQuality(evidence, if (evidence == EvidenceQuality.Solid) ConfidenceBand.Solid else ConfidenceBand.Provisional, uncertainty, uncertainty, 0)
    }

    private fun discountFactorPpm(rateBps: Int, years: Int): BigInteger {
        require(rateBps > 0) { "missing_required_driver: discount rate must be positive" }
        var factor = BI_1_000_000
        repeat(years) {
            factor = roundBig(factor.multiply(BI_10_000), bi(10_000L + rateBps)) ?: fail("missing_required_driver", "discount factor invalid")
        }
        return factor
    }

    fun valueResourceProducer(input: ResourceProducerInput): ComponentValuation {
        requireSupported(input.sourceRegime)
        requirePeriodEvidence(input.evidencePeriods)
        require(input.commodities.isNotEmpty() && input.discountRateBps > 0) { "missing_required_driver: resource valuation needs commodities and a positive family discount rate" }
        if (input.requiresRbl) {
            when (input.rbl?.status) {
                RblStatus.Converged -> {
                    require(input.rbl.fixedPointCount <= 1 && input.rbl.iterations > 0) {
                        if (input.rbl.fixedPointCount > 1) "multiple_rbl_fixed_points: RBL diagnostics report multiple fixed points" else "non_converged_rbl_iteration: RBL diagnostics report no iterations"
                    }
                }
                RblStatus.MultipleFixedPoints -> fail("multiple_rbl_fixed_points", "RBL solve has multiple fixed points")
                RblStatus.NonConverged, RblStatus.Unavailable, null -> fail("non_converged_rbl_iteration", "RBL solve did not converge")
            }
        }
        val targetBase = input.commodities.first().volumetricBase
        var value = BigInteger.ZERO
        var horizon = 0
        input.commodities.forEach { driver ->
            requireEvidence(driver.commodity, driver.evidenceRefs)
            val years = driver.finiteHorizonYears ?: fail("missing_finite_resource_horizon", driver.commodity + " has no finite reserve horizon")
            require(years > 0 && driver.reservesMillis > 0L && driver.volumeMillis.isNotEmpty()) { "missing_finite_resource_horizon: " + driver.commodity + " has no usable finite reserves" }
            require(!driver.hedgeIsUnrealized) { "unhedged_resource_driver: " + driver.commodity + " uses unrealized hedge marks" }
            require(driver.declineBps in 0..10_000 && driver.priceCentsPerUnit > 0L && driver.cashCostCentsPerUnit >= 0L && driver.sustainingCapexCentsPerUnit >= 0L && driver.developmentCapexCentsPerYear >= 0L) {
                "missing_required_driver: ${driver.commodity} has invalid fixed-point resource drivers"
            }
            if (driver.volumetricBase != targetBase) {
                val reconciliation = driver.baseReconciliation
                require(reconciliation != null && reconciliation.from == driver.volumetricBase && reconciliation.to == targetBase && reconciliation.evidenceRefs.any(String::isNotBlank)) { "volumetric_base_mismatch: incompatible volume bases lack evidenced reconciliation" }
                require(reconciliation.adjustmentBps in 0..20_000) { "volumetric_base_mismatch: reconciliation adjustment is outside fixed-point bounds" }
            }
            horizon = maxOf(horizon, years)
            val initial = driver.volumeMillis.maxByOrNull { it.year }?.valueMillis ?: 0L
            require(initial > 0L) { "missing_required_driver: " + driver.commodity + " production volume is non-positive" }
            var reserveRemaining = driver.reservesMillis.bi()
            repeat(years) { index ->
                if (reserveRemaining <= BigInteger.ZERO) return@repeat
                val declineFactor = powRatio(bi(10_000L - driver.declineBps), BI_10_000, index)
                var volume = mulDiv(initial.bi(), declineFactor, BI_1_000_000) ?: fail("missing_required_driver", "resource volume overflow")
                if (driver.volumetricBase != targetBase) {
                    volume = mulDiv(volume, bi(driver.baseReconciliation!!.adjustmentBps.toLong()), BI_10_000) ?: fail("volumetric_base_mismatch", "resource reconciliation overflow")
                }
                volume = volume.min(reserveRemaining).max(BigInteger.ZERO)
                reserveRemaining -= volume
                val price = driver.priceCentsPerUnit + (driver.hedgeCentsPerUnit ?: 0L)
                val revenue = mulDiv(volume, price.bi(), BI_1_000) ?: fail("missing_required_driver", "resource revenue overflow")
                val cashCost = mulDiv(volume, driver.cashCostCentsPerUnit.bi(), BI_1_000) ?: fail("missing_required_driver", "resource cost overflow")
                val sustaining = mulDiv(volume, driver.sustainingCapexCentsPerUnit.bi(), BI_1_000) ?: fail("missing_required_driver", "resource capex overflow")
                val cashFlow = revenue.subtract(cashCost).subtract(sustaining).subtract(driver.developmentCapexCentsPerYear.bi())
                val factor = discountFactorPpm(input.discountRateBps, index + 1)
                value = value.add(roundBig(cashFlow.multiply(factor), BI_1_000_000) ?: fail("missing_required_driver", "resource PV invalid"))
            }
        }
        val base = exactLong(value, "resource enterprise value exceeds fixed-point range")
        val solverStabilityBps = input.rbl?.let { rbl ->
            roundBig(rbl.maxDeltaCents.toBigInteger().abs() * BI_10_000, base.bi().abs().max(BigInteger.ONE))
                ?.coerceAtMost(Int.MAX_VALUE.toLong().bi())?.toInt() ?: Int.MAX_VALUE
        } ?: 0
        return ComponentValuation(
            componentId = input.componentId,
            family = ComponentFamily.ResourceProducer,
            model = ComponentModel.ResourceFinite,
            status = ComponentStatus.Publishable,
            enterpriseValueCents = base,
            scenarios = ScenarioValues(base, base, base),
            discountRateBps = input.discountRateBps,
            discountRateKind = DiscountRateKind.FamilySpecific,
            sourceRegime = input.sourceRegime,
            evidenceRefs = input.commodities.flatMap { it.evidenceRefs },
            quality = ComponentQuality(
                evidenceQuality = if (input.evidencePeriods >= 3) EvidenceQuality.Solid else EvidenceQuality.Provisional,
                confidence = if (input.evidencePeriods >= 3) ConfidenceBand.Solid else ConfidenceBand.Provisional,
                uncertaintyBps = if (input.evidencePeriods >= 3) 2_500 else 5_000,
                sensitivityBps = 2_500,
                solverStabilityBps = solverStabilityBps,
            ),
            reasonCodes = listOf("model=resource_finite", "reserve_horizon_years=" + horizon, "hedges=realized_or_contractual_only"),
        )
    }

    fun valueContractedInfrastructure(input: ContractedInfrastructureInput): ComponentValuation {
        requireSupported(input.sourceRegime)
        requirePeriodEvidence(input.evidencePeriods)
        require(input.exposures.isNotEmpty() && input.discountRateBps > 0) { "missing_required_driver: contracted infrastructure needs exposures and a positive rate" }
        val horizon = input.exposures.map { it.remainingYears ?: fail("missing_contract_term", it.name + " has no expiry horizon") }.maxOrNull() ?: fail("missing_contract_term", "contract term is missing")
        require(horizon > 0) { "missing_contract_term: contract term is zero" }
        var value = BigInteger.ZERO
        repeat(horizon) { index ->
            var revenue = BigInteger.ZERO
            input.exposures.forEach { exposure ->
                val years = exposure.remainingYears ?: fail("missing_contract_term", exposure.name + " has no expiry horizon")
                if (index < years) {
                    requireEvidence(exposure.name, exposure.evidenceRefs)
                    val base = when (exposure.kind) {
                        ContractExposureKind.TakeOrPay -> exposure.baseRevenueCentsPerYear.bi()
                        ContractExposureKind.FeeVolumetric -> {
                            val volume = exposure.volumeMillisPerYear ?: fail("unsupported_contract_exposure", exposure.name + " lacks contracted volume")
                            val fee = exposure.feeCentsPerUnit ?: fail("unsupported_contract_exposure", exposure.name + " lacks fee evidence")
                            mulDiv(volume.bi(), fee.bi(), BI_1_000) ?: fail("unsupported_contract_exposure", exposure.name + " fee overflow")
                        }
                        ContractExposureKind.PercentOfProceeds -> {
                            val proceeds = exposure.proceedsCentsPerYear ?: fail("unsupported_contract_exposure", exposure.name + " lacks proceeds evidence")
                            val share = exposure.percentOfProceedsBps ?: fail("unsupported_contract_exposure", exposure.name + " lacks proceeds share")
                            mulDiv(proceeds.bi(), bi(share.toLong()), BI_10_000) ?: fail("unsupported_contract_exposure", exposure.name + " proceeds overflow")
                        }
                        ContractExposureKind.Unsupported -> fail("unsupported_contract_exposure", exposure.name + " has unsupported revenue exposure")
                    }
                    val scale = powRatio(bi(10_000L + exposure.annualEscalationBps.coerceAtLeast(-9_999)), BI_10_000, index)
                    revenue = revenue.add(mulDiv(base, scale, BI_1_000_000) ?: fail("unsupported_contract_exposure", "contract escalation overflow"))
                }
            }
            val cashFlow = revenue.subtract(input.operatingCostCentsPerYear.bi()).subtract(input.maintenanceCapexCentsPerYear.bi())
            val factor = discountFactorPpm(input.discountRateBps, index + 1)
            value = value.add(roundBig(cashFlow.multiply(factor), BI_1_000_000) ?: fail("missing_required_driver", "contract PV invalid"))
        }
        val base = exactLong(value, "contract enterprise value exceeds fixed-point range")
        return ComponentValuation(
            componentId = input.componentId,
            family = ComponentFamily.ContractedInfrastructure,
            model = ComponentModel.ContractedInfrastructure,
            status = ComponentStatus.Publishable,
            enterpriseValueCents = base,
            scenarios = ScenarioValues(base, base, base),
            discountRateBps = input.discountRateBps,
            discountRateKind = DiscountRateKind.FamilySpecific,
            sourceRegime = input.sourceRegime,
            evidenceRefs = input.exposures.flatMap { it.evidenceRefs },
            quality = componentQuality(input.evidencePeriods, base, base, base),
            reasonCodes = listOf("model=contracted_infrastructure", "revenue=contract-exposure-specific", "terminal_value=finite_contract_horizon"),
        )
    }

    fun valueRegulatedUtility(input: RegulatedUtilityInput): ComponentValuation {
        requireSupported(input.sourceRegime)
        requireEvidence(input.componentId, input.evidenceRefs)
        requirePeriodEvidence(input.evidencePeriods)
        require(input.rateBaseCents > 0L && input.allowedRoeBps > 0 && input.costOfEquityBps > 0 && input.explicitYears > 0 && input.reinvestmentBps in 0..10_000) {
            "missing_required_driver: rate base, allowed ROE, cost of equity, reinvestment, and horizon are required"
        }
        var value = input.rateBaseCents.bi()
        repeat(input.explicitYears) { index ->
            val reinvested = mulDiv(input.rateBaseCents.bi(), bi(input.reinvestmentBps.toLong()), BI_10_000) ?: fail("missing_required_driver", "utility reinvestment overflow")
            val excess = mulDiv(reinvested, bi((input.allowedRoeBps - input.costOfEquityBps).toLong()), BI_10_000) ?: fail("missing_required_driver", "utility excess return overflow")
            val factor = discountFactorPpm(input.costOfEquityBps, index + 1)
            value = value.add(roundBig(excess.multiply(factor), BI_1_000_000) ?: fail("missing_required_driver", "utility PV invalid"))
        }
        val base = exactLong(value, "utility enterprise value exceeds fixed-point range")
        return ComponentValuation(
            componentId = input.componentId,
            family = ComponentFamily.RegulatedUtility,
            model = ComponentModel.RegulatedUtility,
            status = ComponentStatus.Publishable,
            enterpriseValueCents = base,
            scenarios = ScenarioValues(base, base, base),
            discountRateBps = input.costOfEquityBps,
            discountRateKind = DiscountRateKind.FamilySpecific,
            sourceRegime = input.sourceRegime,
            evidenceRefs = input.evidenceRefs,
            quality = componentQuality(input.evidencePeriods, base, base, base),
            reasonCodes = listOf("model=regulated_utility", "driver=allowed_roe_and_rate_base"),
        )
    }

    private fun powRatio(numerator: BigInteger, denominator: BigInteger, exponent: Int): BigInteger {
        var result = BI_1_000_000
        repeat(exponent) {
            result = mulDiv(result, numerator, denominator) ?: fail("missing_required_driver", "fixed-point ratio overflow")
        }
        return result
    }



    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase().replace('_', ' ').replace('-', ' ')
    private fun String.containsAny(vararg terms: String): Boolean = terms.any(::contains)

    private fun fnv1a64(value: String): String {
        var hash = -3750763034362895579L
        value.toByteArray().forEach { byte ->
            hash = (hash xor (byte.toLong() and 0xffL)) * 1099511628211L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}

@JvmName("biValue")
private fun bi(value: Long): BigInteger = BigInteger.valueOf(value)
private fun bi(value: Int): BigInteger = BigInteger.valueOf(value.toLong())
private fun Long.bi(): BigInteger = BigInteger.valueOf(this)
private fun BigInteger.toLongOrNull(): Long? = try { longValueExact() } catch (_: ArithmeticException) { null }
private fun roundDiv(numerator: BigInteger, denominator: BigInteger): Long? {
    if (denominator <= BigInteger.ZERO) return null
    val absolute = numerator.abs().add(denominator.divide(BigInteger.valueOf(2))).divide(denominator)
    val rounded = if (numerator.signum() < 0) absolute.negate() else absolute
    return rounded.toLongOrNull()
}

private fun roundBig(numerator: BigInteger, denominator: BigInteger): BigInteger? {
    if (denominator <= BigInteger.ZERO) return null
    val absolute = numerator.abs().add(denominator.divide(BigInteger.valueOf(2))).divide(denominator)
    return if (numerator.signum() < 0) absolute.negate() else absolute
}

private fun mulDiv(a: BigInteger, b: BigInteger, denominator: BigInteger): BigInteger? = roundBig(a.multiply(b), denominator)

private fun exactLong(value: BigInteger, detail: String): Long = try {
    value.longValueExact()
} catch (_: ArithmeticException) {
    error("missing_required_driver: $detail")
}

private fun exactInt(value: BigInteger?, detail: String): Int = try {
    value?.intValueExact() ?: error(detail)
} catch (_: ArithmeticException) {
    error("missing_required_driver: $detail")
}

class ValuationRefusalException(
    val reasonCode: String,
    val detail: String,
) : IllegalArgumentException("$reasonCode: $detail")

private fun fail(code: String, detail: String): Nothing = throw ValuationRefusalException(code, detail)

private val BI_10_000 = BigInteger.valueOf(10_000L)
private val BI_100_000_000 = BigInteger.valueOf(100_000_000L)
private val BI_1_000 = BigInteger.valueOf(1_000L)
private val BI_1_000_000 = BigInteger.valueOf(1_000_000L)


@Serializable
enum class SourceRegime {
    @SerialName("domestic_us_gaap") DomesticUsGaap,
    @SerialName("ifrs") Ifrs,
    @SerialName("unsupported") Unsupported,
}

@Serializable
enum class EvidenceQuality {
    @SerialName("solid") Solid,
    @SerialName("provisional") Provisional,
    @SerialName("unavailable") Unavailable,
    @SerialName("rejected") Rejected,
}

@Serializable
enum class EvidenceUnit {
    @SerialName("money_cents") MoneyCents,
    @SerialName("rate_bps") RateBps,
    @SerialName("quantity_millis") QuantityMillis,
    @SerialName("shares") Shares,
    @SerialName("text") Text,
    @SerialName("boolean") Boolean,
}

@Serializable
enum class ExtractionMethod {
    @SerialName("structured_xbrl") StructuredXbrl,
    @SerialName("filing_table") FilingTable,
    @SerialName("filing_narrative") FilingNarrative,
    @SerialName("company_guidance") CompanyGuidance,
    @SerialName("macro_series") MacroSeries,
    @SerialName("security_master") SecurityMaster,
    @SerialName("manual_review") ManualReview,
    @SerialName("retrieval_failure") RetrievalFailure,
}

@Serializable
enum class RetrievalState {
    @SerialName("retrieved") Retrieved,
    @SerialName("failed") Failed,
}

@Serializable
data class EvidenceObservation(
    val id: String,
    val factKey: String,
    val economicPeriodStart: String,
    val economicPeriodEnd: String,
    val knowledgeAt: String,
    val publicationAt: String,
    val revisionId: String,
    val supersedes: String? = null,
    val sourceVintage: String,
    val retrievedAt: String,
    val sourceRegime: SourceRegime,
    val unit: EvidenceUnit,
    val valueCents: Long? = null,
    val valueBps: Int? = null,
    val valueMillis: Long? = null,
    val textValue: String? = null,
    val currency: String? = null,
    val definition: String,
    val sourceLocation: String,
    val extractionMethod: ExtractionMethod,
    val quality: EvidenceQuality,
    val retrievalState: RetrievalState,
) {
    fun valueKey(): String = listOf(valueCents, valueBps, valueMillis, textValue).joinToString("|")
    fun validationError(): String? {
        if (listOf(id, factKey, economicPeriodStart, economicPeriodEnd, knowledgeAt, publicationAt, revisionId, sourceVintage, retrievedAt, definition, sourceLocation).any(String::isBlank)) return "mandatory evidence field is empty"
        if (economicPeriodStart > economicPeriodEnd) return "economic period is inverted"
        if (listOf(valueCents, valueBps, valueMillis, textValue).count { it != null } != 1) return "exactly one fixed-point value is required"
        val matches = when (unit) {
            EvidenceUnit.MoneyCents -> valueCents != null && currency != null
            EvidenceUnit.RateBps -> valueBps != null
            EvidenceUnit.QuantityMillis, EvidenceUnit.Shares -> valueMillis != null
            EvidenceUnit.Text, EvidenceUnit.Boolean -> textValue != null
        }
        return if (matches) null else "value/unit mismatch"
    }
    fun canonical(): String = listOf(id, factKey, economicPeriodStart, economicPeriodEnd, knowledgeAt, publicationAt, revisionId, supersedes.orEmpty(), sourceVintage, retrievedAt, sourceRegime.name, unit.name, valueCents?.toString().orEmpty(), valueBps?.toString().orEmpty(), valueMillis?.toString().orEmpty(), textValue.orEmpty(), currency.orEmpty(), definition, sourceLocation, extractionMethod.name, quality.name).joinToString("|")
}

@Serializable
enum class EvidenceRejectionCode {
    @SerialName("invalid_evidence") InvalidEvidence,
    @SerialName("retrieval_failure") RetrievalFailure,
    @SerialName("not_known_at_decision") NotKnownAtDecision,
    @SerialName("duplicate_evidence") DuplicateEvidence;
    val serialized: String get() = when (this) {
        InvalidEvidence -> "invalid_evidence"
        RetrievalFailure -> "retrieval_failure"
        NotKnownAtDecision -> "not_known_at_decision"
        DuplicateEvidence -> "duplicate_evidence"
    }
}

@Serializable
data class EvidenceRejection(val observationId: String, val code: EvidenceRejectionCode, val detail: String)

@Serializable
data class PitReplay(val decisionAt: String, val selected: List<EvidenceObservation>, val rejected: List<EvidenceRejection>, val fingerprint: String)

@Serializable
enum class AssetClass {
    @SerialName("equity") Equity,
    @SerialName("etf") Etf,
    @SerialName("fund") Fund,
    @SerialName("crypto") Crypto,
    @SerialName("reit") Reit,
    @SerialName("unknown") Unknown,
}

@Serializable
enum class ComponentFamily {
    @SerialName("operating_non_financial") OperatingNonFinancial,
    @SerialName("financial_services") FinancialServices,
    @SerialName("resource_producer") ResourceProducer,
    @SerialName("contracted_infrastructure") ContractedInfrastructure,
    @SerialName("regulated_utility") RegulatedUtility,
    @SerialName("not_eligible") NotEligible,
    @SerialName("unclassified") Unclassified;
    fun model(): ComponentModel = when (this) {
        OperatingNonFinancial -> ComponentModel.FcffWacc
        FinancialServices -> ComponentModel.ResidualIncomeEquity
        ResourceProducer -> ComponentModel.ResourceFinite
        ContractedInfrastructure -> ComponentModel.ContractedInfrastructure
        RegulatedUtility -> ComponentModel.RegulatedUtility
        NotEligible, Unclassified -> ComponentModel.None
    }
}

@Serializable
enum class ComponentModel {
    @SerialName("fcff_wacc") FcffWacc,
    @SerialName("residual_income_equity") ResidualIncomeEquity,
    @SerialName("resource_finite") ResourceFinite,
    @SerialName("contracted_infrastructure") ContractedInfrastructure,
    @SerialName("regulated_utility") RegulatedUtility,
    @SerialName("none") None,
}

@Serializable
data class ClassificationInput(val sector: String? = null, val industry: String? = null, val assetClass: AssetClass = AssetClass.Equity)

@Serializable
enum class DiscountRateKind {
    @SerialName("wacc") Wacc,
    @SerialName("cost_of_equity") CostOfEquity,
    @SerialName("family_specific") FamilySpecific,
}

@Serializable
enum class SbcTreatment {
    @SerialName("expense_included") ExpenseIncluded,
    @SerialName("dilution_projected") DilutionProjected,
    @SerialName("unreconciled") Unreconciled,
}

@Serializable
data class ScenarioValues(val bearCents: Long, val baseCents: Long, val bullCents: Long)

@Serializable
enum class ConfidenceBand {
    @SerialName("solid") Solid,
    @SerialName("provisional") Provisional,
}

@Serializable
data class ComponentQuality(
    val evidenceQuality: EvidenceQuality,
    val confidence: ConfidenceBand,
    val uncertaintyBps: Int,
    val sensitivityBps: Int,
    val solverStabilityBps: Int,
)

@Serializable
enum class ComponentStatus {
    @SerialName("publishable") Publishable,
    @SerialName("unavailable") Unavailable,
    @SerialName("not_eligible") NotEligible,
}

@Serializable
data class ComponentValuation(
    val componentId: String,
    val family: ComponentFamily,
    val model: ComponentModel,
    val status: ComponentStatus,
    val enterpriseValueCents: Long,
    val scenarios: ScenarioValues,
    val discountRateBps: Int,
    val discountRateKind: DiscountRateKind,
    val sourceRegime: SourceRegime,
    val evidenceRefs: List<String>,
    val quality: ComponentQuality,
    val reasonCodes: List<String>,
)

@Serializable
data class AnnualFcff(val year: Int, val fcffCents: Long)

@Serializable
data class OperatingComponentInput(
    val componentId: String,
    val sourceRegime: SourceRegime,
    val fcffByYear: List<AnnualFcff>,
    val waccBps: Int,
    val nearGrowthBps: Int,
    val stableGrowthBps: Int,
    val terminalNopatCents: Long,
    val terminalRoicBps: Int,
    val terminalReinvestmentBps: Int,
    val explicitYears: Int,
    val sbcTreatment: SbcTreatment,
    val evidenceRefs: List<String>,
    val evidencePeriods: Int,
    val scenarioSpreadBps: Int,
)

@Serializable
data class FinancialServicesComponentInput(
    val componentId: String,
    val sourceRegime: SourceRegime,
    val bookEquityCents: Long,
    val shares: Long,
    val roeBps: Int,
    val retentionBps: Int,
    val costOfEquityBps: Int,
    val stableRoeBps: Int,
    val explicitYears: Int,
    val evidenceRefs: List<String>,
    val evidencePeriods: Int,
    val scenarioRoeSpreadBps: Int,
    val scenarioRateSpreadBps: Int,
)

@Serializable
enum class VolumetricBase {
    @SerialName("gross") Gross,
    @SerialName("working_interest") WorkingInterest,
    @SerialName("net_revenue_interest") NetRevenueInterest,
}

@Serializable
data class AnnualQuantity(val year: Int, val valueMillis: Long)

@Serializable
data class VolumeReconciliation(
    val from: VolumetricBase,
    val to: VolumetricBase,
    val adjustmentBps: Int,
    val evidenceRefs: List<String>,
)

@Serializable
data class CommodityDriver(
    val commodity: String,
    val volumeMillis: List<AnnualQuantity>,
    val volumetricBase: VolumetricBase,
    val baseReconciliation: VolumeReconciliation? = null,
    val priceCentsPerUnit: Long,
    val hedgeCentsPerUnit: Long? = null,
    val hedgeIsUnrealized: Boolean,
    val cashCostCentsPerUnit: Long,
    val sustainingCapexCentsPerUnit: Long,
    val reservesMillis: Long,
    val declineBps: Int,
    val developmentCapexCentsPerYear: Long,
    val finiteHorizonYears: Int? = null,
    val evidenceRefs: List<String>,
)

@Serializable
enum class RblStatus {
    @SerialName("converged") Converged,
    @SerialName("non_converged") NonConverged,
    @SerialName("multiple_fixed_points") MultipleFixedPoints,
    @SerialName("unavailable") Unavailable,
}

@Serializable
data class RblDiagnostics(
    val status: RblStatus,
    val iterations: Int,
    val maxDeltaCents: Long,
    val fixedPointCount: Int,
    val evidenceRefs: List<String>,
)

@Serializable
data class ResourceProducerInput(
    val componentId: String,
    val sourceRegime: SourceRegime,
    val commodities: List<CommodityDriver>,
    val discountRateBps: Int,
    val requiresRbl: Boolean,
    val rbl: RblDiagnostics? = null,
    val evidencePeriods: Int,
)

@Serializable
enum class ContractExposureKind {
    @SerialName("take_or_pay") TakeOrPay,
    @SerialName("fee_volumetric") FeeVolumetric,
    @SerialName("percent_of_proceeds") PercentOfProceeds,
    @SerialName("unsupported") Unsupported,
}

@Serializable
data class ContractExposure(
    val name: String,
    val kind: ContractExposureKind,
    val baseRevenueCentsPerYear: Long,
    val annualEscalationBps: Int,
    val remainingYears: Int? = null,
    val volumeMillisPerYear: Long? = null,
    val feeCentsPerUnit: Long? = null,
    val proceedsCentsPerYear: Long? = null,
    val percentOfProceedsBps: Int? = null,
    val evidenceRefs: List<String>,
    val material: Boolean,
)

@Serializable
data class ContractedInfrastructureInput(
    val componentId: String,
    val sourceRegime: SourceRegime,
    val exposures: List<ContractExposure>,
    val operatingCostCentsPerYear: Long,
    val maintenanceCapexCentsPerYear: Long,
    val discountRateBps: Int,
    val evidencePeriods: Int,
)

@Serializable
data class RegulatedUtilityInput(
    val componentId: String,
    val sourceRegime: SourceRegime,
    val rateBaseCents: Long,
    val allowedRoeBps: Int,
    val costOfEquityBps: Int,
    val reinvestmentBps: Int,
    val explicitYears: Int,
    val evidenceRefs: List<String>,
    val evidencePeriods: Int,
)


@Serializable
data class SotpComponent(
    val componentId: String,
    val material: Boolean,
    val valuation: ComponentValuation? = null,
    val refusal: ValuationRefusalWire? = null,
)

@Serializable
data class ValuationRefusalWire(val code: String, val detail: String)

@Serializable
data class BridgeEvidence(val amountCents: Long, val evidenceRefs: List<String>)

@Serializable
data class CapitalBridge(
    val netDebt: BridgeEvidence? = null,
    val nonControllingInterest: BridgeEvidence? = null,
    val preferredClaims: BridgeEvidence? = null,
    val otherSeniorClaims: BridgeEvidence? = null,
    val separatelyValuedInvestments: List<BridgeEvidence> = emptyList(),
)

@Serializable
data class CorporateOverhead(val enterpriseValueCents: Long, val material: Boolean, val evidenceRefs: List<String>)

@Serializable
data class SotpInput(
    val issuer: String,
    val components: List<SotpComponent>,
    val corporateOverhead: CorporateOverhead?,
    val bridge: CapitalBridge,
    val shares: BridgeEvidence?,
    val sourceFingerprint: String,
)

@Serializable
enum class SotpStatus {
    @SerialName("published") Published,
    @SerialName("covered_ev_only") CoveredEvOnly,
    @SerialName("unavailable") Unavailable,
}

@Serializable
data class SotpOutput(
    val status: SotpStatus,
    val coveredEnterpriseValueCents: Long?,
    val equityValueCents: Long?,
    val intrinsicPriceCents: Long?,
    val valuationScoreEligible: Boolean,
    val reasonCodes: List<String>,
    val componentQuality: EvidenceQuality,
    val engineVersion: String,
    val modelPolicyVersion: String,
    val resolverPolicyVersion: String,
    val sourceFingerprint: String,
)

@Serializable
enum class CacheDisposition {
    @SerialName("store_publishable") StorePublishable,
    @SerialName("store_covered_ev_only") StoreCoveredEvOnly,
    @SerialName("clear_stale_intrinsic") ClearStaleIntrinsic,
}

@Serializable
data class ValuationCacheKey(
    val issuer: String,
    val sourceFingerprint: String,
    val driverFingerprint: String,
    val engineVersion: String,
    val modelPolicyVersion: String,
    val resolverPolicyVersion: String,
)

@Serializable
enum class RefusalCode {
    @SerialName("invalid_evidence") InvalidEvidence,
    @SerialName("incomplete_segment_disclosures") IncompleteSegmentDisclosures,
    @SerialName("unresolved_capital_bridge") UnresolvedCapitalBridge,
    @SerialName("missing_shares") MissingShares,
    @SerialName("historical_validation_coverage_unavailable") HistoricalValidationCoverageUnavailable;
    val serialized: String get() = when (this) {
        InvalidEvidence -> "invalid_evidence"
        IncompleteSegmentDisclosures -> "incomplete_segment_disclosures"
        UnresolvedCapitalBridge -> "unresolved_capital_bridge"
        MissingShares -> "missing_shares"
        HistoricalValidationCoverageUnavailable -> "historical_validation_coverage_unavailable"
    }
}

@Serializable
enum class DisagreementStatus {
    @SerialName("aligned") Aligned,
    @SerialName("tension") Tension,
    @SerialName("disputed") Disputed,
    @SerialName("unavailable") Unavailable,
}

@Serializable
data class AnalystRange(val lowCents: Long, val baseCents: Long, val highCents: Long, val horizonDays: Int, val definition: String, val evidenceRefs: List<String>)

@Serializable
data class DisagreementResult(val status: DisagreementStatus, val anchorGapBps: Int?, val reasonCodes: List<String>)

@Serializable
data class HistoricalMembership(
    val symbol: String,
    val effectiveFrom: String,
    val effectiveTo: String? = null,
    val knowledgeAt: String,
    val sourceLocation: String,
)

@Serializable
data class HistoricalValidationCoverage(
    val membership: List<HistoricalMembership> = emptyList(),
    val delistings: List<EvidenceObservation> = emptyList(),
    val corporateActions: List<EvidenceObservation> = emptyList(),
    val classifications: List<EvidenceObservation> = emptyList(),
    val componentDefinitions: List<EvidenceObservation> = emptyList(),
)

@Serializable
data class DriverForecast(val symbol: String, val driver: String, val decisionAt: String, val forecastMillis: Long)

@Serializable
data class DriverActual(val symbol: String, val driver: String, val economicPeriodEnd: String, val knowledgeAt: String, val actualMillis: Long)

@Serializable
enum class ValidationStatus {
    @SerialName("measured") Measured,
    @SerialName("unavailable") Unavailable,
}

@Serializable
data class DriverValidationResult(
    val status: ValidationStatus,
    val sampleCount: Int,
    val meanAbsoluteErrorBps: Int?,
    val usedHistoricalMembership: Boolean,
    val marketOutcomeDiagnosticBps: Int?,
    val marketOutcomeDiagnosticUsedForPrimary: Boolean,
    val reasonCodes: List<String>,
)
