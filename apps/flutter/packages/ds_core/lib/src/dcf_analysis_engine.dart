import 'dart:math' as math;

import 'models.dart';

const int _riskFreeRateBps = 400;
const int _equityRiskPremiumBps = 500;
const int _defaultTaxRateBps = 2100;
const int _defaultCostOfDebtBps = 550;
const int _minCostOfDebtBps = 200;
const int _maxCostOfDebtBps = 1200;
const int _minWaccBps = 500;
const int _maxWaccBps = 1800;
const int _dcfProjectionYears = 5;
const int _baseGrowthMinBps = -1000;
const int _baseGrowthMaxBps = 1800;
const int _scenarioGrowthSpreadBps = 400;
const int _bearGrowthMinBps = -1200;
const int _bearGrowthMaxBps = 1400;
const int _bullGrowthMinBps = -400;
const int _bullGrowthMaxBps = 2400;
const int _bearTerminalGrowthBps = 200;
const int _baseTerminalGrowthBps = 250;
const int _bullTerminalGrowthBps = 300;

/// Free-cash-flow DCF engine (Android `DcfAnalysisEngine`).
class DcfAnalysisEngine {
  static DcfAnalysis compute({
    required FundamentalSnapshot fundamentals,
    required FundamentalTimeseries timeseries,
    int? marketPriceCents,
  }) {
    if (timeseries.freeCashFlow.length < 3) {
      throw StateError(
        'DCF unavailable: need at least 3 annual free cash flow points.',
      );
    }
    final latestFcf = timeseries.freeCashFlow.last.value;
    if (latestFcf <= 0) {
      throw StateError(
        'DCF unavailable: latest annual free cash flow is not positive.',
      );
    }
    final currentShares = _latestShareCount(fundamentals, timeseries);
    if (currentShares == null) {
      throw StateError('DCF unavailable: share count is missing.');
    }
    final fcfPerShare =
        _freeCashFlowPerShareSeries(fundamentals, timeseries, currentShares);
    final rawBaseGrowthBps = _deriveBaseGrowthBps(fcfPerShare);
    if (rawBaseGrowthBps == null) {
      throw StateError(
        'DCF unavailable: insufficient positive free cash flow per share history.',
      );
    }
    final resolvedWacc =
        _deriveWacc(fundamentals, timeseries, marketPriceCents);
    final netDebtDollars =
        (fundamentals.totalDebtDollars ?? 0) - (fundamentals.totalCashDollars ?? 0);

    final bearGrowthBps = (rawBaseGrowthBps - _scenarioGrowthSpreadBps)
        .clamp(_bearGrowthMinBps, _bearGrowthMaxBps);
    final baseGrowthBps =
        rawBaseGrowthBps.clamp(_baseGrowthMinBps, _baseGrowthMaxBps);
    final bullGrowthBps = (baseGrowthBps + _scenarioGrowthSpreadBps)
        .clamp(_bullGrowthMinBps, _bullGrowthMaxBps);

    final bearIntrinsic = _discountedIntrinsicValuePerShareCents(
      latestFcf,
      currentShares,
      netDebtDollars,
      bearGrowthBps,
      _clampTerminalGrowthBps(_bearTerminalGrowthBps, resolvedWacc.waccBps),
      resolvedWacc.waccBps,
    );
    final baseIntrinsic = _discountedIntrinsicValuePerShareCents(
      latestFcf,
      currentShares,
      netDebtDollars,
      baseGrowthBps,
      _clampTerminalGrowthBps(_baseTerminalGrowthBps, resolvedWacc.waccBps),
      resolvedWacc.waccBps,
    );
    final bullIntrinsic = _discountedIntrinsicValuePerShareCents(
      latestFcf,
      currentShares,
      netDebtDollars,
      bullGrowthBps,
      _clampTerminalGrowthBps(_bullTerminalGrowthBps, resolvedWacc.waccBps),
      resolvedWacc.waccBps,
    );
    if (bearIntrinsic == null || baseIntrinsic == null || bullIntrinsic == null) {
      throw StateError('DCF unavailable: scenario produced an invalid value.');
    }

    return DcfAnalysis(
      bearIntrinsicValueCents: bearIntrinsic,
      baseIntrinsicValueCents: baseIntrinsic,
      bullIntrinsicValueCents: bullIntrinsic,
      waccBps: resolvedWacc.waccBps,
      baseGrowthBps: baseGrowthBps,
      netDebtDollars: netDebtDollars,
      source: DcfSource.yahooFinance,
      waccInputs: resolvedWacc.inputs,
    );
  }

  static double? _latestShareCount(
    FundamentalSnapshot fundamentals,
    FundamentalTimeseries timeseries,
  ) {
    final diluted = timeseries.dilutedAverageShares;
    if (diluted.isNotEmpty && diluted.last.value > 0) {
      return diluted.last.value;
    }
    final shares = fundamentals.sharesOutstanding;
    return shares != null && shares > 0 ? shares.toDouble() : null;
  }

  static List<(String, double)> _freeCashFlowPerShareSeries(
    FundamentalSnapshot fundamentals,
    FundamentalTimeseries timeseries,
    double currentShares,
  ) {
    final out = <(String, double)>[];
    for (final point in timeseries.freeCashFlow) {
      final shares = _shareCountForDate(timeseries, point.asOfDate) ??
          fundamentals.sharesOutstanding?.toDouble() ??
          currentShares;
      if (shares <= 0) continue;
      out.add((point.asOfDate, point.value / shares));
    }
    return out;
  }

  static double? _shareCountForDate(
    FundamentalTimeseries timeseries,
    String asOfDate,
  ) {
    for (final point in timeseries.dilutedAverageShares.reversed) {
      if (point.asOfDate.compareTo(asOfDate) <= 0 && point.value > 0) {
        return point.value;
      }
    }
    return null;
  }

  static int? _deriveBaseGrowthBps(List<(String, double)> points) {
    var latestIndex = -1;
    var firstIndex = -1;
    for (var i = 0; i < points.length; i++) {
      if (points[i].$2 > 0) {
        if (firstIndex < 0) firstIndex = i;
        latestIndex = i;
      }
    }
    if (latestIndex < 0 || firstIndex < 0) return null;
    final latest = points[latestIndex];
    final first = points[firstIndex];
    final years = _elapsedYearsBetween(first.$1, latest.$1) ??
        (latestIndex - firstIndex).toDouble();
    if (years <= 0) return null;
    final cagr = math.pow(latest.$2 / first.$2, 1.0 / years) - 1.0;
    if (!cagr.isFinite) return null;
    return (cagr * 10000).round();
  }

  static double? _elapsedYearsBetween(String start, String end) {
    final startDate = DateTime.tryParse(start);
    final endDate = DateTime.tryParse(end);
    if (startDate == null || endDate == null) return null;
    final days = endDate.difference(startDate).inDays;
    return days > 0 ? days / 365.2425 : null;
  }

  static ({int waccBps, WaccInputProvenance inputs}) _deriveWacc(
    FundamentalSnapshot fundamentals,
    FundamentalTimeseries timeseries,
    int? marketPriceCents,
  ) {
    final marketCapResolved =
        _resolveMarketCapDollars(fundamentals, timeseries, marketPriceCents);
    if (marketCapResolved == null) {
      throw StateError('DCF unavailable: market cap is missing.');
    }
    final marketCap = marketCapResolved.$1;
    final marketCapSource = marketCapResolved.$2;
    final betaSource = fundamentals.betaMillis != null
        ? WaccFieldSource.reported
        : WaccFieldSource.defaulted;
    final beta = (fundamentals.betaMillis ?? 1000) / 1000.0;
    final costOfEquityBps =
        _riskFreeRateBps + (beta * _equityRiskPremiumBps).round();

    final totalDebtSource = fundamentals.totalDebtDollars != null
        ? WaccFieldSource.reported
        : WaccFieldSource.assumedZero;
    final totalCashSource = fundamentals.totalCashDollars != null
        ? WaccFieldSource.reported
        : WaccFieldSource.assumedZero;
    final totalDebt = math.max(0, fundamentals.totalDebtDollars ?? 0).toDouble();
    final totalCash = math.max(0, fundamentals.totalCashDollars ?? 0).toDouble();
    final netDebt = math.max(0.0, totalDebt - totalCash);
    final debtWeightBase = marketCap + netDebt;
    final equityWeight = debtWeightBase > 0 ? marketCap / debtWeightBase : 1.0;
    final debtWeight = debtWeightBase > 0 ? netDebt / debtWeightBase : 0.0;

    final latestInterest = timeseries.interestExpense.isEmpty
        ? null
        : timeseries.interestExpense.last.value.abs();
    late final WaccFieldSource costOfDebtSource;
    late final int costOfDebtBps;
    if (totalDebt > 0) {
      if (latestInterest != null) {
        costOfDebtSource = WaccFieldSource.interestOverDebt;
        costOfDebtBps = ((latestInterest / totalDebt) * 10000)
            .round()
            .clamp(_minCostOfDebtBps, _maxCostOfDebtBps);
      } else {
        costOfDebtSource = WaccFieldSource.defaulted;
        costOfDebtBps = _defaultCostOfDebtBps;
      }
    } else {
      costOfDebtSource = WaccFieldSource.reported;
      costOfDebtBps = _defaultCostOfDebtBps;
    }

    final taxRateSource = timeseries.taxRateForCalcs.isNotEmpty
        ? WaccFieldSource.reported
        : WaccFieldSource.defaulted;
    final taxRateBps = (timeseries.taxRateForCalcs.isNotEmpty
            ? (timeseries.taxRateForCalcs.last.value * 10000).round()
            : _defaultTaxRateBps)
        .clamp(0, 3500);
    final afterTaxCostOfDebtBps =
        (costOfDebtBps * (1.0 - taxRateBps / 10000.0)).round();
    final weighted =
        (equityWeight * costOfEquityBps) + (debtWeight * afterTaxCostOfDebtBps);
    final unclamped = weighted.round();
    final waccBps = unclamped.clamp(_minWaccBps, _maxWaccBps);

    return (
      waccBps: waccBps,
      inputs: WaccInputProvenance(
        marketCap: marketCapSource,
        beta: betaSource,
        totalDebt: totalDebtSource,
        totalCash: totalCashSource,
        costOfDebt: costOfDebtSource,
        taxRate: taxRateSource,
        waccClamped: unclamped != waccBps,
      ),
    );
  }

  static (double, WaccFieldSource)? _resolveMarketCapDollars(
    FundamentalSnapshot fundamentals,
    FundamentalTimeseries timeseries,
    int? marketPriceCents,
  ) {
    final reported = fundamentals.marketCapDollars;
    if (reported != null && reported > 0) {
      return (reported.toDouble(), WaccFieldSource.reported);
    }
    final shares = _latestShareCount(fundamentals, timeseries);
    final priceCents = marketPriceCents;
    if (shares == null || priceCents == null || priceCents <= 0) return null;
    final derived = (priceCents / 100.0) * shares;
    if (!derived.isFinite || derived <= 0) return null;
    return (derived, WaccFieldSource.derivedPriceTimesShares);
  }

  static int _clampTerminalGrowthBps(int terminalGrowthBps, int waccBps) =>
      terminalGrowthBps.clamp(50, waccBps - 50);

  static int? _discountedIntrinsicValuePerShareCents(
    double latestFcfDollars,
    double currentShares,
    int netDebtDollars,
    int growthBps,
    int terminalGrowthBps,
    int waccBps,
  ) {
    if (latestFcfDollars <= 0 ||
        currentShares <= 0 ||
        terminalGrowthBps >= waccBps) {
      return null;
    }
    final growth = growthBps / 10000.0;
    final terminalGrowth = terminalGrowthBps / 10000.0;
    final wacc = waccBps / 10000.0;
    var projectedFcf = latestFcfDollars;
    var presentValue = 0.0;
    for (var year = 1; year <= _dcfProjectionYears; year++) {
      projectedFcf *= 1.0 + growth;
      presentValue += projectedFcf / math.pow(1.0 + wacc, year);
    }
    final terminalCashFlow = projectedFcf * (1.0 + terminalGrowth);
    final terminalValue = terminalCashFlow / (wacc - terminalGrowth);
    final enterpriseValue =
        presentValue + terminalValue / math.pow(1.0 + wacc, _dcfProjectionYears);
    final equityValue = enterpriseValue - netDebtDollars;
    final perShareDollars = equityValue / currentShares;
    if (!perShareDollars.isFinite || perShareDollars <= 0) return null;
    return (perShareDollars * 100).round();
  }
}
