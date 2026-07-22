import 'dart:convert';

import 'package:ds_core/ds_core.dart';
import 'package:http/http.dart' as http;

const _userAgent =
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36';
const _chartApi = 'https://query1.finance.yahoo.com/v8/finance/chart/';
const _quoteSummaryApi =
    'https://query1.finance.yahoo.com/v10/finance/quoteSummary/';
const _timeseriesApi =
    'https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/';
const _quoteSummaryModules =
    'price,financialData,defaultKeyStatistics,assetProfile,recommendationTrend';

class ProviderFetchResult {
  const ProviderFetchResult({
    required this.symbol,
    this.snapshot,
    this.externalSignal,
    this.fundamentals,
    this.companyName,
    this.error,
  });
  final String symbol;
  final MarketSnapshot? snapshot;
  final ExternalValuationSignal? externalSignal;
  final FundamentalSnapshot? fundamentals;
  final String? companyName;
  final String? error;
}

/// Yahoo Finance client (chart + quoteSummary + fundamentals timeseries).
class YahooFinanceClient {
  YahooFinanceClient({http.Client? httpClient})
      : _client = httpClient ?? http.Client();

  final http.Client _client;
  String? _crumb;
  String? _cookieHeader;

  Future<ProviderFetchResult> fetchSymbol(String symbol) async {
    final requestSymbol = yahooRequestSymbol(symbol);
    try {
      await _ensureSession();
      final summary = await _fetchQuoteSummary(requestSymbol);
      final result = summary['quoteSummary']?['result'];
      final first = result is List && result.isNotEmpty
          ? result.first as Map<String, dynamic>?
          : null;
      if (first == null) {
        return _chartOnlySnapshot(symbol);
      }
      return _parseQuoteSummary(symbol, first);
    } catch (e) {
      // Transient 401/404/network: reset session and try chart-only price so
      // the symbol still enters the tracked universe (QA: CTRA/HOLX gaps).
      try {
        _crumb = null;
        _cookieHeader = null;
        await _ensureSession();
        final retry = await _fetchQuoteSummary(requestSymbol);
        final result = retry['quoteSummary']?['result'];
        final first = result is List && result.isNotEmpty
            ? result.first as Map<String, dynamic>?
            : null;
        if (first != null) return _parseQuoteSummary(symbol, first);
      } catch (_) {}
      final chartOnly = await _chartOnlySnapshot(symbol);
      if (chartOnly.snapshot != null) return chartOnly;
      return ProviderFetchResult(symbol: symbol, error: e.toString());
    }
  }

  Future<ProviderFetchResult> _chartOnlySnapshot(String symbol) async {
    try {
      final candles = await fetchHistoricalCandles(symbol, ChartRange.day);
      if (candles.isEmpty) {
        return ProviderFetchResult(
          symbol: symbol,
          error: 'No quoteSummary/chart data',
        );
      }
      final price = candles.last.closeCents;
      return ProviderFetchResult(
        symbol: symbol,
        snapshot: MarketSnapshot(
          symbol: symbol,
          companyName: symbol,
          profitable: true,
          marketPriceCents: price,
          intrinsicValueCents: price,
        ),
      );
    } catch (e) {
      return ProviderFetchResult(symbol: symbol, error: e.toString());
    }
  }

  Future<List<HistoricalCandle>> fetchHistoricalCandles(
    String symbol,
    ChartRange range,
  ) async {
    final requestSymbol = yahooRequestSymbol(symbol);
    final (rangeToken, interval) = chartRangeSpec(range);
    final uri = Uri.parse('$_chartApi$requestSymbol').replace(
      queryParameters: {
        'range': rangeToken,
        'interval': interval,
        'includePrePost': 'false',
      },
    );
    final root = await _getJson(uri);
    final result = root['chart']?['result'];
    if (result is! List || result.isEmpty) return const [];
    final first = result.first as Map<String, dynamic>;
    final timestamps = first['timestamp'] as List? ?? const [];
    final quote = (first['indicators']?['quote'] as List?)?.first
        as Map<String, dynamic>?;
    if (quote == null) return const [];
    final opens = quote['open'] as List? ?? const [];
    final highs = quote['high'] as List? ?? const [];
    final lows = quote['low'] as List? ?? const [];
    final closes = quote['close'] as List? ?? const [];
    final volumes = quote['volume'] as List? ?? const [];

    final out = <HistoricalCandle>[];
    for (var i = 0; i < timestamps.length; i++) {
      final close = _asDouble(closes, i);
      if (close == null) continue;
      final open = _asDouble(opens, i) ?? close;
      final high = _asDouble(highs, i) ?? close;
      final low = _asDouble(lows, i) ?? close;
      final volume = _asInt(volumes, i) ?? 0;
      final ts = _asInt(timestamps, i);
      if (ts == null) continue;
      final openCents = dollarsToCents(open);
      final highCents = dollarsToCents(high);
      final lowCents = dollarsToCents(low);
      final closeCents = dollarsToCents(close);
      if (openCents == null ||
          highCents == null ||
          lowCents == null ||
          closeCents == null) {
        continue;
      }
      out.add(
        HistoricalCandle(
          epochSeconds: ts,
          openCents: openCents,
          highCents: highCents,
          lowCents: lowCents,
          closeCents: closeCents,
          volume: volume,
        ),
      );
    }
    return out;
  }

  Future<FundamentalTimeseries> fetchFundamentalTimeseries(String symbol) async {
    final requestSymbol = yahooRequestSymbol(symbol);
    final types = [
      'annualFreeCashFlow',
      'annualOperatingCashFlow',
      'annualCapitalExpenditure',
      'annualDilutedAverageShares',
      'annualInterestExpense',
      'annualPretaxIncome',
      'annualTaxRateForCalcs',
      'annualNetIncome',
    ].join(',');
    final uri = Uri.parse('$_timeseriesApi$requestSymbol').replace(
      queryParameters: {
        'type': types,
        'period1': '1262304000',
        'period2': '2524608000',
      },
    );
    final root = await _getJson(uri);
    return FundamentalTimeseries(
      freeCashFlow: _parseTimeseriesMetric(root, 'annualFreeCashFlow'),
      operatingCashFlow: _parseTimeseriesMetric(root, 'annualOperatingCashFlow'),
      capitalExpenditure:
          _parseTimeseriesMetric(root, 'annualCapitalExpenditure'),
      dilutedAverageShares:
          _parseTimeseriesMetric(root, 'annualDilutedAverageShares'),
      interestExpense: _parseTimeseriesMetric(root, 'annualInterestExpense'),
      pretaxIncome: _parseTimeseriesMetric(root, 'annualPretaxIncome'),
      taxRateForCalcs: _parseTimeseriesMetric(root, 'annualTaxRateForCalcs'),
      netIncome: _parseTimeseriesMetric(root, 'annualNetIncome'),
    );
  }

  ProviderFetchResult _parseQuoteSummary(
    String symbol,
    Map<String, dynamic> first,
  ) {
    final price = first['price'] as Map<String, dynamic>? ?? {};
    final financial =
        first['financialData'] as Map<String, dynamic>? ?? {};
    final stats =
        first['defaultKeyStatistics'] as Map<String, dynamic>? ?? {};
    final profile = first['assetProfile'] as Map<String, dynamic>? ?? {};
    final recTrend =
        first['recommendationTrend'] as Map<String, dynamic>? ?? {};

    final marketPrice = _rawNumber(price['regularMarketPrice']) ??
        _rawNumber(financial['currentPrice']);
    final marketPriceCents =
        marketPrice == null ? null : dollarsToCents(marketPrice);
    final targetMean = _rawNumber(financial['targetMeanPrice']);
    final targetLow = _rawNumber(financial['targetLowPrice']);
    final targetHigh = _rawNumber(financial['targetHighPrice']);
    final targetMedian = _rawNumber(financial['targetMedianPrice']);
    final numberOfAnalysts =
        _rawNumber(financial['numberOfAnalystOpinions'])?.round();
    final recommendationMean = _rawNumber(financial['recommendationMean']);

    final companyName =
        price['longName'] as String? ?? price['shortName'] as String? ?? symbol;

    final fairValueCents = targetMean == null ? null : dollarsToCents(targetMean);
    final intrinsic = fairValueCents ?? marketPriceCents ?? 0;
    final snapshot = marketPriceCents == null
        ? null
        : MarketSnapshot(
            symbol: symbol,
            companyName: companyName,
            profitable: true,
            marketPriceCents: marketPriceCents,
            intrinsicValueCents: intrinsic > 0 ? intrinsic : marketPriceCents,
          );

    final trend = (recTrend['trend'] as List?)?.cast<Map<String, dynamic>>() ??
        const [];
    Map<String, dynamic>? period0;
    for (final p in trend) {
      if (p['period'] == '0m') {
        period0 = p;
        break;
      }
    }
    period0 ??= trend.isEmpty ? null : trend.first;

    ExternalValuationSignal? external;
    if (fairValueCents != null && fairValueCents > 0) {
      external = ExternalValuationSignal(
        symbol: symbol,
        fairValueCents: fairValueCents,
        ageSeconds: 0,
        lowFairValueCents:
            targetLow == null ? null : dollarsToCents(targetLow),
        highFairValueCents:
            targetHigh == null ? null : dollarsToCents(targetHigh),
        analystOpinionCount: numberOfAnalysts,
        recommendationMeanHundredths: recommendationMean == null
            ? null
            : (recommendationMean * 100).round(),
        strongBuyCount: period0?['strongBuy'] as int?,
        buyCount: period0?['buy'] as int?,
        holdCount: period0?['hold'] as int?,
        sellCount: period0?['sell'] as int?,
        strongSellCount: period0?['strongSell'] as int?,
        weightedFairValueCents:
            targetMedian == null ? fairValueCents : dollarsToCents(targetMedian),
        weightedAnalystCount: numberOfAnalysts,
      );
    }

    final fundamentals = FundamentalSnapshot(
      symbol: symbol,
      sectorName: profile['sector'] as String?,
      industryName: profile['industry'] as String?,
      marketCapDollars: _rawNumber(price['marketCap'])?.round() ??
          _rawNumber(stats['enterpriseValue'])?.round(),
      sharesOutstanding: _rawNumber(stats['sharesOutstanding'])?.round(),
      trailingPeHundredths: _hundredths(_rawNumber(stats['trailingPE'])),
      forwardPeHundredths: _hundredths(_rawNumber(stats['forwardPE'])),
      priceToBookHundredths: _hundredths(_rawNumber(stats['priceToBook'])),
      returnOnEquityBps: _bps(_rawNumber(financial['returnOnEquity'])),
      ebitdaDollars: _rawNumber(financial['ebitda'])?.round(),
      enterpriseValueDollars: _rawNumber(stats['enterpriseValue'])?.round(),
      enterpriseToEbitdaHundredths:
          _hundredths(_rawNumber(stats['enterpriseToEbitda'])),
      totalDebtDollars: _rawNumber(financial['totalDebt'])?.round(),
      totalCashDollars: _rawNumber(financial['totalCash'])?.round(),
      debtToEquityHundredths: _hundredths(_rawNumber(financial['debtToEquity'])),
      freeCashFlowDollars: _rawNumber(financial['freeCashflow'])?.round(),
      operatingCashFlowDollars:
          _rawNumber(financial['operatingCashflow'])?.round(),
      betaMillis: _rawNumber(stats['beta']) == null
          ? null
          : (_rawNumber(stats['beta'])! * 1000).round(),
      trailingEpsCents: _rawNumber(stats['trailingEps']) == null
          ? null
          : dollarsToCents(_rawNumber(stats['trailingEps'])!),
      earningsGrowthBps: _bps(_rawNumber(financial['earningsGrowth'])),
    );

    return ProviderFetchResult(
      symbol: symbol,
      snapshot: snapshot,
      externalSignal: external,
      fundamentals: fundamentals.hasAnyValues() ? fundamentals : null,
      companyName: companyName,
    );
  }

  Future<void> _ensureSession() async {
    if (_crumb != null && _cookieHeader != null) return;
    final fc = await _client.get(
      Uri.parse('https://fc.yahoo.com'),
      headers: {'User-Agent': _userAgent},
    );
    final cookies = fc.headers['set-cookie'];
    _cookieHeader = cookies?.split(',').map((c) => c.split(';').first.trim()).join('; ');
    final crumbResp = await _client.get(
      Uri.parse('https://query1.finance.yahoo.com/v1/test/getcrumb'),
      headers: {
        'User-Agent': _userAgent,
        if (_cookieHeader != null) 'Cookie': _cookieHeader!,
      },
    );
    if (crumbResp.statusCode == 200 && crumbResp.body.isNotEmpty) {
      _crumb = crumbResp.body.trim();
    }
  }

  Future<Map<String, dynamic>> _fetchQuoteSummary(String requestSymbol) async {
    final uri = Uri.parse('$_quoteSummaryApi$requestSymbol').replace(
      queryParameters: {
        'modules': _quoteSummaryModules,
        if (_crumb != null) 'crumb': _crumb!,
      },
    );
    return _getJson(uri);
  }

  Future<Map<String, dynamic>> _getJson(Uri uri) async {
    final response = await _client.get(
      uri,
      headers: {
        'User-Agent': _userAgent,
        'Accept': 'application/json,text/plain,*/*',
        'Accept-Language': 'en-US,en;q=0.9',
        if (_cookieHeader != null) 'Cookie': _cookieHeader!,
      },
    );
    if (response.statusCode == 401 || response.statusCode == 403) {
      _crumb = null;
      _cookieHeader = null;
      await _ensureSession();
      final retry = await _client.get(
        uri,
        headers: {
          'User-Agent': _userAgent,
          'Accept': 'application/json,text/plain,*/*',
          if (_cookieHeader != null) 'Cookie': _cookieHeader!,
        },
      );
      if (retry.statusCode != 200) {
        throw StateError('HTTP ${retry.statusCode} for $uri');
      }
      return jsonDecode(retry.body) as Map<String, dynamic>;
    }
    if (response.statusCode != 200) {
      throw StateError('HTTP ${response.statusCode} for $uri: ${response.body}');
    }
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  List<AnnualReportedValue> _parseTimeseriesMetric(
    Map<String, dynamic> root,
    String type,
  ) {
    final result = root['timeseries']?['result'];
    if (result is! List) return const [];
    for (final entry in result) {
      if (entry is! Map) continue;
      final meta = entry['meta'] as Map?;
      if (meta?['type'] is List &&
          (meta!['type'] as List).contains(type) == false) {
        continue;
      }
      final series = entry[type];
      if (series is! List) continue;
      final out = <AnnualReportedValue>[];
      for (final point in series) {
        if (point is! Map) continue;
        final asOf = point['asOfDate'] as String?;
        final reported = point['reportedValue'];
        final raw = reported is Map
            ? reported['raw']
            : reported;
        if (asOf == null || raw is! num) continue;
        out.add(AnnualReportedValue(asOf, raw.toDouble()));
      }
      out.sort((a, b) => a.asOfDate.compareTo(b.asOfDate));
      return out;
    }
    return const [];
  }

  static (String, String) chartRangeSpec(ChartRange range) => switch (range) {
        ChartRange.day => ('1d', '5m'),
        ChartRange.week => ('5d', '15m'),
        ChartRange.month => ('1mo', '1d'),
        ChartRange.year => ('1y', '1wk'),
        ChartRange.fiveYears => ('5y', '1mo'),
        ChartRange.tenYears => ('10y', '1mo'),
      };

  static String yahooRequestSymbol(String symbol) {
    // Share classes: BF.B → BF-B. Leave exchange suffixes (YPFD.BA) alone.
    if (symbol.contains('.') &&
        !symbol.contains('-') &&
        RegExp(r'^[A-Z]+\.[A-Z]$').hasMatch(symbol)) {
      return symbol.replaceAll('.', '-');
    }
    return symbol;
  }

  static int? dollarsToCents(double dollars) {
    if (!dollars.isFinite) return null;
    return (dollars * 100).round();
  }

  static double? _rawNumber(dynamic node) {
    if (node == null) return null;
    if (node is num) return node.toDouble();
    if (node is Map) {
      final raw = node['raw'];
      if (raw is num) return raw.toDouble();
    }
    return null;
  }

  static int? _hundredths(double? value) =>
      value == null ? null : (value * 100).round();

  static int? _bps(double? value) =>
      value == null ? null : (value * 10000).round();

  static double? _asDouble(List list, int i) {
    if (i >= list.length || list[i] == null) return null;
    final v = list[i];
    if (v is num) return v.toDouble();
    return null;
  }

  static int? _asInt(List list, int i) {
    if (i >= list.length || list[i] == null) return null;
    final v = list[i];
    if (v is int) return v;
    if (v is num) return v.round();
    return null;
  }

  void close() => _client.close();
}
