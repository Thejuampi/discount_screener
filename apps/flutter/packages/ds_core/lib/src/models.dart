// Fixed-point financial domain models (port of Android Models.kt).

enum ConfidenceBand { low, provisional, high }

enum QualificationStatus { qualified, unprofitable, gapTooSmall }

enum ExternalSignalStatus { missing, stale, supportive, divergent }

enum AlertKind { enteredQualified, exitedQualified, confidenceUpgraded }

enum ChartRange { day, week, month, year, fiveYears, tenYears }

enum DcfSignal { opportunity, fair, expensive }

enum OpportunityScoringModel {
  legacy,
  aggressive,
  aggressiveV2,
  aggressiveV3,
}

enum DcfSource { yahooFinance, secEdgar, derived, restored, unknown }

enum ProviderState {
  live,
  restoredOnly,
  stale,
  unavailable,
  notEligible,
  unsupportedSymbol,
  providerDisabled,
  parseUncertain,
  providerUncertain,
  rejected,
  cancelled,
}

enum ResolverState {
  selected,
  unavailable,
  notEligible,
  restoredOnly,
  providerUncertain,
  cancelled,
}

enum RefreshDisposition {
  retryableRefresh,
  terminalUntilInputsChange,
  blockedUntilProviderEnabled,
  notApplicable,
}

enum ProviderDecisionReasonCode {
  networkUnavailable,
  httpStatus,
  rateLimited,
  providerDisabled,
  providerConfigurationAbsent,
  noEnabledProviders,
  desktopSecDeferred,
  symbolUnsupported,
  nonUsIssuerUnsupported,
  fundOrEtfUnsupported,
  missingCik,
  missingAnnualFcf,
  latestFcfNonPositive,
  insufficientAnnualPeriods,
  missingMarketCap,
  missingShares,
  missingDebtOrCash,
  missingBeta,
  staleFiscalPeriod,
  fiscalPeriodMisaligned,
  providerDisagreement,
  restoredWithoutLiveRefresh,
  legacySourceFreePayload,
  generationSuperseded,
  cancelled,
}

enum WaccFieldSource {
  reported,
  defaulted,
  derivedPriceTimesShares,
  assumedZero,
  interestOverDebt,
}

enum EstimateScenario { bearDcf, baseDcf, bullDcf, analystLow, analystHigh }

enum DcfCoverageStatus {
  unavailable,
  lowConfidence,
  partial,
  provisional,
  ready,
}

class ViewFilter {
  const ViewFilter({this.query = '', this.watchlistOnly = false});
  final String query;
  final bool watchlistOnly;
}

class MarketSnapshot {
  const MarketSnapshot({
    required this.symbol,
    this.companyName,
    required this.profitable,
    required this.marketPriceCents,
    required this.intrinsicValueCents,
  });
  final String symbol;
  final String? companyName;
  final bool profitable;
  final int marketPriceCents;
  final int intrinsicValueCents;
}

class ExternalValuationSignal {
  const ExternalValuationSignal({
    required this.symbol,
    required this.fairValueCents,
    required this.ageSeconds,
    this.lowFairValueCents,
    this.highFairValueCents,
    this.analystOpinionCount,
    this.recommendationMeanHundredths,
    this.strongBuyCount,
    this.buyCount,
    this.holdCount,
    this.sellCount,
    this.strongSellCount,
    this.weightedFairValueCents,
    this.weightedAnalystCount,
  });

  final String symbol;
  final int fairValueCents;
  final int ageSeconds;
  final int? lowFairValueCents;
  final int? highFairValueCents;
  final int? analystOpinionCount;
  final int? recommendationMeanHundredths;
  final int? strongBuyCount;
  final int? buyCount;
  final int? holdCount;
  final int? sellCount;
  final int? strongSellCount;
  final int? weightedFairValueCents;
  final int? weightedAnalystCount;

  ExternalValuationSignal copyWith({
    int? weightedFairValueCents,
    int? weightedAnalystCount,
    bool clearWeighted = false,
  }) =>
      ExternalValuationSignal(
        symbol: symbol,
        fairValueCents: fairValueCents,
        ageSeconds: ageSeconds,
        lowFairValueCents: lowFairValueCents,
        highFairValueCents: highFairValueCents,
        analystOpinionCount: analystOpinionCount,
        recommendationMeanHundredths: recommendationMeanHundredths,
        strongBuyCount: strongBuyCount,
        buyCount: buyCount,
        holdCount: holdCount,
        sellCount: sellCount,
        strongSellCount: strongSellCount,
        weightedFairValueCents:
            clearWeighted ? null : (weightedFairValueCents ?? this.weightedFairValueCents),
        weightedAnalystCount:
            clearWeighted ? null : (weightedAnalystCount ?? this.weightedAnalystCount),
      );
}

class FundamentalSnapshot {
  const FundamentalSnapshot({
    required this.symbol,
    this.sectorKey,
    this.sectorName,
    this.industryKey,
    this.industryName,
    this.marketCapDollars,
    this.sharesOutstanding,
    this.trailingPeHundredths,
    this.forwardPeHundredths,
    this.priceToBookHundredths,
    this.returnOnEquityBps,
    this.ebitdaDollars,
    this.enterpriseValueDollars,
    this.enterpriseToEbitdaHundredths,
    this.totalDebtDollars,
    this.totalCashDollars,
    this.debtToEquityHundredths,
    this.freeCashFlowDollars,
    this.operatingCashFlowDollars,
    this.betaMillis,
    this.trailingEpsCents,
    this.earningsGrowthBps,
  });

  final String symbol;
  final String? sectorKey;
  final String? sectorName;
  final String? industryKey;
  final String? industryName;
  final int? marketCapDollars;
  final int? sharesOutstanding;
  final int? trailingPeHundredths;
  final int? forwardPeHundredths;
  final int? priceToBookHundredths;
  final int? returnOnEquityBps;
  final int? ebitdaDollars;
  final int? enterpriseValueDollars;
  final int? enterpriseToEbitdaHundredths;
  final int? totalDebtDollars;
  final int? totalCashDollars;
  final int? debtToEquityHundredths;
  final int? freeCashFlowDollars;
  final int? operatingCashFlowDollars;
  final int? betaMillis;
  final int? trailingEpsCents;
  final int? earningsGrowthBps;

  bool hasAnyValues() => [
        sectorKey,
        sectorName,
        industryKey,
        industryName,
        marketCapDollars,
        sharesOutstanding,
        trailingPeHundredths,
        forwardPeHundredths,
        priceToBookHundredths,
        returnOnEquityBps,
        ebitdaDollars,
        enterpriseValueDollars,
        enterpriseToEbitdaHundredths,
        totalDebtDollars,
        totalCashDollars,
        debtToEquityHundredths,
        freeCashFlowDollars,
        operatingCashFlowDollars,
        betaMillis,
        trailingEpsCents,
        earningsGrowthBps,
      ].any((v) => v != null);
}

class CandidateRow {
  const CandidateRow({
    required this.symbol,
    required this.marketPriceCents,
    required this.intrinsicValueCents,
    required this.gapBps,
    required this.upsideBps,
    required this.isQualified,
    required this.confidence,
    this.companyName,
  });
  final String symbol;
  final int marketPriceCents;
  final int intrinsicValueCents;
  final int gapBps;
  final int upsideBps;
  final bool isQualified;
  final ConfidenceBand confidence;
  final String? companyName;
}

class TapeEvent {
  const TapeEvent({
    required this.symbol,
    required this.gapBps,
    required this.isQualified,
    required this.confidence,
  });
  final String symbol;
  final int gapBps;
  final bool isQualified;
  final ConfidenceBand confidence;
}

class AlertEvent {
  const AlertEvent({
    required this.symbol,
    required this.kind,
    required this.sequence,
  });
  final String symbol;
  final AlertKind kind;
  final int sequence;
}

class PriceHistoryPoint {
  const PriceHistoryPoint({required this.sequence, required this.marketPriceCents});
  final int sequence;
  final int marketPriceCents;
}

class SymbolDetail {
  const SymbolDetail({
    required this.symbol,
    required this.profitable,
    required this.marketPriceCents,
    required this.intrinsicValueCents,
    required this.gapBps,
    required this.upsideBps,
    required this.minimumGapBps,
    required this.qualification,
    required this.externalStatus,
    this.externalSignalFairValueCents,
    this.externalSignalLowFairValueCents,
    this.externalSignalHighFairValueCents,
    this.weightedExternalSignalFairValueCents,
    this.weightedAnalystCount,
    this.externalSignalGapBps,
    this.externalSignalAgeSeconds,
    required this.externalSignalMaxAgeSeconds,
    this.analystOpinionCount,
    this.recommendationMeanHundredths,
    this.strongBuyCount,
    this.buyCount,
    this.holdCount,
    this.sellCount,
    this.strongSellCount,
    this.fundamentals,
    required this.confidence,
    required this.lastSequence,
    required this.updateCount,
    required this.isWatched,
    this.companyName,
  });

  final String symbol;
  final bool profitable;
  final int marketPriceCents;
  final int intrinsicValueCents;
  final int gapBps;
  final int upsideBps;
  final int minimumGapBps;
  final QualificationStatus qualification;
  final ExternalSignalStatus externalStatus;
  final int? externalSignalFairValueCents;
  final int? externalSignalLowFairValueCents;
  final int? externalSignalHighFairValueCents;
  final int? weightedExternalSignalFairValueCents;
  final int? weightedAnalystCount;
  final int? externalSignalGapBps;
  final int? externalSignalAgeSeconds;
  final int externalSignalMaxAgeSeconds;
  final int? analystOpinionCount;
  final int? recommendationMeanHundredths;
  final int? strongBuyCount;
  final int? buyCount;
  final int? holdCount;
  final int? sellCount;
  final int? strongSellCount;
  final FundamentalSnapshot? fundamentals;
  final ConfidenceBand confidence;
  final int lastSequence;
  final int updateCount;
  final bool isWatched;
  final String? companyName;
}

class HistoricalCandle {
  const HistoricalCandle({
    required this.epochSeconds,
    required this.openCents,
    required this.highCents,
    required this.lowCents,
    required this.closeCents,
    required this.volume,
  });
  final int epochSeconds;
  final int openCents;
  final int highCents;
  final int lowCents;
  final int closeCents;
  final int volume;
}

class AnnualReportedValue {
  const AnnualReportedValue(this.asOfDate, this.value);
  final String asOfDate;
  final double value;
}

class FundamentalTimeseries {
  const FundamentalTimeseries({
    this.freeCashFlow = const [],
    this.operatingCashFlow = const [],
    this.capitalExpenditure = const [],
    this.dilutedAverageShares = const [],
    this.interestExpense = const [],
    this.pretaxIncome = const [],
    this.taxRateForCalcs = const [],
    this.netIncome = const [],
  });

  final List<AnnualReportedValue> freeCashFlow;
  final List<AnnualReportedValue> operatingCashFlow;
  final List<AnnualReportedValue> capitalExpenditure;
  final List<AnnualReportedValue> dilutedAverageShares;
  final List<AnnualReportedValue> interestExpense;
  final List<AnnualReportedValue> pretaxIncome;
  final List<AnnualReportedValue> taxRateForCalcs;
  final List<AnnualReportedValue> netIncome;
}

class ProviderDecisionReason {
  const ProviderDecisionReason({
    required this.code,
    this.provider = DcfSource.unknown,
    this.symbol,
    this.affectedField,
    this.fiscalPeriod,
    this.upstreamStatus,
    this.thresholdBps,
    this.comparisonBps,
  });

  final ProviderDecisionReasonCode code;
  final DcfSource provider;
  final String? symbol;
  final String? affectedField;
  final String? fiscalPeriod;
  final String? upstreamStatus;
  final int? thresholdBps;
  final int? comparisonBps;
}

class DataProvenance {
  const DataProvenance({
    this.source = DcfSource.unknown,
    this.providerState = ProviderState.restoredOnly,
    this.capturedAtEpochSeconds,
    this.asOfDate,
    this.endpoint,
    this.factFamily,
    this.qualityFlags = const [],
    this.fallbackReason,
  });

  final DcfSource source;
  final ProviderState providerState;
  final int? capturedAtEpochSeconds;
  final String? asOfDate;
  final String? endpoint;
  final String? factFamily;
  final List<ProviderDecisionReasonCode> qualityFlags;
  final ProviderDecisionReasonCode? fallbackReason;

  DataProvenance copyWith({
    DcfSource? source,
    ProviderState? providerState,
    ProviderDecisionReasonCode? fallbackReason,
    bool clearFallback = false,
  }) =>
      DataProvenance(
        source: source ?? this.source,
        providerState: providerState ?? this.providerState,
        capturedAtEpochSeconds: capturedAtEpochSeconds,
        asOfDate: asOfDate,
        endpoint: endpoint,
        factFamily: factFamily,
        qualityFlags: qualityFlags,
        fallbackReason: clearFallback ? null : (fallbackReason ?? this.fallbackReason),
      );
}

class WaccInputProvenance {
  const WaccInputProvenance({
    this.marketCap = WaccFieldSource.reported,
    this.beta = WaccFieldSource.reported,
    this.totalDebt = WaccFieldSource.reported,
    this.totalCash = WaccFieldSource.reported,
    this.costOfDebt = WaccFieldSource.reported,
    this.taxRate = WaccFieldSource.reported,
    this.waccClamped = false,
  });

  final WaccFieldSource marketCap;
  final WaccFieldSource beta;
  final WaccFieldSource totalDebt;
  final WaccFieldSource totalCash;
  final WaccFieldSource costOfDebt;
  final WaccFieldSource taxRate;
  final bool waccClamped;

  List<String> summaryLabels() {
    final labels = <String>[];
    if (marketCap == WaccFieldSource.derivedPriceTimesShares) {
      labels.add('market cap=price×shares');
    }
    if (beta == WaccFieldSource.defaulted) labels.add('beta=default');
    if (totalDebt == WaccFieldSource.assumedZero) labels.add('debt=assumed 0');
    if (totalCash == WaccFieldSource.assumedZero) labels.add('cash=assumed 0');
    if (costOfDebt == WaccFieldSource.defaulted) labels.add('cost of debt=default');
    if (taxRate == WaccFieldSource.defaulted) labels.add('tax=default');
    if (waccClamped) labels.add('wacc=clamped');
    return labels;
  }

  bool isProvisional() => summaryLabels().isNotEmpty;
}

class DcfAnalysis {
  DcfAnalysis({
    required this.bearIntrinsicValueCents,
    required this.baseIntrinsicValueCents,
    required this.bullIntrinsicValueCents,
    required this.waccBps,
    required this.baseGrowthBps,
    required this.netDebtDollars,
    this.source,
    this.sourceFingerprint,
    ResolverState? resolverState,
    this.decisionFingerprint,
    DataProvenance? provenance,
    List<ProviderDecisionReason>? providerReasons,
    this.waccInputs = const WaccInputProvenance(),
  })  : resolverState = resolverState ??
            (source == null ? ResolverState.restoredOnly : ResolverState.selected),
        provenance = provenance ??
            DataProvenance(
              source: source ?? DcfSource.unknown,
              providerState:
                  source == null ? ProviderState.restoredOnly : ProviderState.live,
              fallbackReason: source == null
                  ? ProviderDecisionReasonCode.legacySourceFreePayload
                  : null,
            ),
        providerReasons = providerReasons ??
            (source == null
                ? const [
                    ProviderDecisionReason(
                      code: ProviderDecisionReasonCode.legacySourceFreePayload,
                    ),
                    ProviderDecisionReason(
                      code: ProviderDecisionReasonCode.restoredWithoutLiveRefresh,
                      provider: DcfSource.restored,
                    ),
                  ]
                : const []);

  final int bearIntrinsicValueCents;
  final int baseIntrinsicValueCents;
  final int bullIntrinsicValueCents;
  final int waccBps;
  final int baseGrowthBps;
  final int netDebtDollars;
  final DcfSource? source;
  final String? sourceFingerprint;
  final ResolverState resolverState;
  final String? decisionFingerprint;
  final DataProvenance provenance;
  final List<ProviderDecisionReason> providerReasons;
  final WaccInputProvenance waccInputs;

  DcfAnalysis copyWith({
    DcfSource? source,
    String? sourceFingerprint,
    ResolverState? resolverState,
    String? decisionFingerprint,
    DataProvenance? provenance,
    List<ProviderDecisionReason>? providerReasons,
  }) =>
      DcfAnalysis(
        bearIntrinsicValueCents: bearIntrinsicValueCents,
        baseIntrinsicValueCents: baseIntrinsicValueCents,
        bullIntrinsicValueCents: bullIntrinsicValueCents,
        waccBps: waccBps,
        baseGrowthBps: baseGrowthBps,
        netDebtDollars: netDebtDollars,
        source: source ?? this.source,
        sourceFingerprint: sourceFingerprint ?? this.sourceFingerprint,
        resolverState: resolverState ?? this.resolverState,
        decisionFingerprint: decisionFingerprint ?? this.decisionFingerprint,
        provenance: provenance ?? this.provenance,
        providerReasons: providerReasons ?? this.providerReasons,
        waccInputs: waccInputs,
      );
}

class ChartRangeSummary {
  const ChartRangeSummary({
    required this.range,
    required this.capturedAt,
    required this.candleCount,
    this.latestCloseCents,
    this.ema20Cents,
    this.ema50Cents,
    this.ema200Cents,
    this.macdCents,
    this.signalCents,
    this.histogramCents,
    this.latestWilderRsi,
    this.latestRsiSlope,
    this.volumeRatioHundredths,
  });

  final ChartRange range;
  final int capturedAt;
  final int candleCount;
  final int? latestCloseCents;
  final int? ema20Cents;
  final int? ema50Cents;
  final int? ema200Cents;
  final int? macdCents;
  final int? signalCents;
  final int? histogramCents;
  final double? latestWilderRsi;
  final double? latestRsiSlope;
  final int? volumeRatioHundredths;
}

class OpportunityRow {
  const OpportunityRow({
    required this.symbol,
    required this.marketPriceCents,
    required this.intrinsicValueCents,
    required this.gapBps,
    required this.upsideBps,
    required this.confidence,
    required this.isWatched,
    this.fundamentalsScore,
    this.technicalScore,
    this.forecastScore,
    required this.compositeScore,
    required this.coverageCount,
    this.fundamentalsSignals = const [],
    this.technicalSignals = const [],
    this.forecastSignals = const [],
    this.companyName,
  });

  final String symbol;
  final int marketPriceCents;
  final int intrinsicValueCents;
  final int gapBps;
  final int upsideBps;
  final ConfidenceBand confidence;
  final bool isWatched;
  final int? fundamentalsScore;
  final int? technicalScore;
  final int? forecastScore;
  final int compositeScore;
  final int coverageCount;
  final List<String> fundamentalsSignals;
  final List<String> technicalSignals;
  final List<String> forecastSignals;
  final String? companyName;
}

class IssueRecord {
  const IssueRecord({
    required this.key,
    required this.title,
    required this.detail,
    required this.severity,
    required this.active,
    required this.count,
    required this.lastSeenEpochSeconds,
  });

  final String key;
  final String title;
  final String detail;
  final String severity;
  final bool active;
  final int count;
  final int lastSeenEpochSeconds;
}

class PersistedSymbolState {
  const PersistedSymbolState({
    required this.symbol,
    this.snapshot,
    this.externalSignal,
    this.fundamentals,
    this.lastSequence = 0,
    this.updateCount = 0,
    this.priceHistory = const [],
    this.dcfAnalysis,
  });

  final String symbol;
  final MarketSnapshot? snapshot;
  final ExternalValuationSignal? externalSignal;
  final FundamentalSnapshot? fundamentals;
  final int lastSequence;
  final int updateCount;
  final List<PriceHistoryPoint> priceHistory;
  final DcfAnalysis? dcfAnalysis;
}

class PersistedReportState {
  const PersistedReportState({
    this.trackedSymbols = const [],
    this.watchlist = const [],
    this.symbolStates = const [],
    this.issues = const [],
    this.lastPersistedAtEpochSeconds,
  });

  final List<String> trackedSymbols;
  final List<String> watchlist;
  final List<PersistedSymbolState> symbolStates;
  final List<IssueRecord> issues;
  final int? lastPersistedAtEpochSeconds;
}

class DcfProviderQuality {
  const DcfProviderQuality({
    required this.source,
    required this.providerState,
    this.acceptedAnnualFcfPoints = 0,
    this.latestFiscalPeriod,
    this.reasons = const [],
  });

  final DcfSource source;
  final ProviderState providerState;
  final int acceptedAnnualFcfPoints;
  final String? latestFiscalPeriod;
  final List<ProviderDecisionReason> reasons;
}

class DcfSourcePolicyConfig {
  const DcfSourcePolicyConfig({
    this.providerPriority = const [DcfSource.secEdgar, DcfSource.yahooFinance],
    this.disagreementThresholdBps = 1000,
    this.nearZeroFcfFloor = 1.0,
  });

  final List<DcfSource> providerPriority;
  final int disagreementThresholdBps;
  final double nearZeroFcfFloor;
}

class DcfSourceCandidate {
  const DcfSourceCandidate({
    required this.source,
    this.timeseries,
    this.analysis,
    this.providerState = ProviderState.live,
    this.reasons = const [],
    this.quality,
  });

  final DcfSource source;
  final FundamentalTimeseries? timeseries;
  final DcfAnalysis? analysis;
  final ProviderState providerState;
  final List<ProviderDecisionReason> reasons;
  final DcfProviderQuality? quality;
}

class DcfSourceSelection {
  const DcfSourceSelection({
    this.selectedSource,
    this.timeseries,
    this.analysis,
    this.resolverState = ResolverState.unavailable,
    this.refreshDisposition = RefreshDisposition.notApplicable,
    this.providerQualities = const [],
    this.reasons = const [],
    this.inputFingerprint,
    this.decisionFingerprint,
  });

  final DcfSource? selectedSource;
  final FundamentalTimeseries? timeseries;
  final DcfAnalysis? analysis;
  final ResolverState resolverState;
  final RefreshDisposition refreshDisposition;
  final List<DcfProviderQuality> providerQualities;
  final List<ProviderDecisionReason> reasons;
  final String? inputFingerprint;
  final String? decisionFingerprint;
}

class ScenarioEstimate {
  const ScenarioEstimate({
    required this.scenario,
    required this.weightedPriceCents,
    required this.coverageCount,
    required this.impliedUpsideBps,
  });

  final EstimateScenario scenario;
  final int weightedPriceCents;
  final int coverageCount;
  final int impliedUpsideBps;
}

class DcfSourceDistribution {
  const DcfSourceDistribution({
    this.yahooCount = 0,
    this.secCount = 0,
    this.restoredCount = 0,
    this.uncertainCount = 0,
    this.notEligibleCount = 0,
    this.unknownCount = 0,
    this.unavailableCount = 0,
  });

  final int yahooCount;
  final int secCount;
  final int restoredCount;
  final int uncertainCount;
  final int notEligibleCount;
  final int unknownCount;
  final int unavailableCount;
}

class DcfCoverageSummary {
  const DcfCoverageSummary({
    this.totalEligibleSymbols = 0,
    this.coveredSymbols = 0,
    this.coverageBps = 0,
    this.status = DcfCoverageStatus.unavailable,
    this.sourceDistribution = const DcfSourceDistribution(),
  });

  final int totalEligibleSymbols;
  final int coveredSymbols;
  final int coverageBps;
  final DcfCoverageStatus status;
  final DcfSourceDistribution sourceDistribution;
}

class IndexEstimatesReport {
  const IndexEstimatesReport({
    required this.profileName,
    required this.currentWeightedPriceCents,
    required this.totalSymbols,
    required this.scenarios,
    required this.computedAtEpochSeconds,
    this.dcfCoverage = const DcfCoverageSummary(),
  });

  final String profileName;
  final int currentWeightedPriceCents;
  final int totalSymbols;
  final List<ScenarioEstimate> scenarios;
  final int computedAtEpochSeconds;
  final DcfCoverageSummary dcfCoverage;
}

/// Kotlin enum name for contract JSON (PascalCase).
String dcfSourceWireName(DcfSource source) => switch (source) {
      DcfSource.yahooFinance => 'YahooFinance',
      DcfSource.secEdgar => 'SecEdgar',
      DcfSource.derived => 'Derived',
      DcfSource.restored => 'Restored',
      DcfSource.unknown => 'Unknown',
    };

String resolverStateWireName(ResolverState state) => switch (state) {
      ResolverState.selected => 'Selected',
      ResolverState.unavailable => 'Unavailable',
      ResolverState.notEligible => 'NotEligible',
      ResolverState.restoredOnly => 'RestoredOnly',
      ResolverState.providerUncertain => 'ProviderUncertain',
      ResolverState.cancelled => 'Cancelled',
    };

String chartRangeWireName(ChartRange range) => switch (range) {
      ChartRange.day => 'Day',
      ChartRange.week => 'Week',
      ChartRange.month => 'Month',
      ChartRange.year => 'Year',
      ChartRange.fiveYears => 'FiveYears',
      ChartRange.tenYears => 'TenYears',
    };

ChartRange? chartRangeFromWire(String id) => switch (id) {
      'Day' => ChartRange.day,
      'Week' => ChartRange.week,
      'Month' => ChartRange.month,
      'Year' => ChartRange.year,
      'FiveYears' => ChartRange.fiveYears,
      'TenYears' => ChartRange.tenYears,
      _ => null,
    };

String qualificationWireName(QualificationStatus s) => switch (s) {
      QualificationStatus.qualified => 'Qualified',
      QualificationStatus.unprofitable => 'Unprofitable',
      QualificationStatus.gapTooSmall => 'GapTooSmall',
    };

String externalStatusWireName(ExternalSignalStatus s) => switch (s) {
      ExternalSignalStatus.missing => 'Missing',
      ExternalSignalStatus.stale => 'Stale',
      ExternalSignalStatus.supportive => 'Supportive',
      ExternalSignalStatus.divergent => 'Divergent',
    };

String confidenceWireName(ConfidenceBand b) => switch (b) {
      ConfidenceBand.low => 'Low',
      ConfidenceBand.provisional => 'Provisional',
      ConfidenceBand.high => 'High',
    };
