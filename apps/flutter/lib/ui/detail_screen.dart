import 'package:ds_core/ds_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../presentation/dashboard_controller.dart';
import 'charts.dart';
import 'formatters.dart';
import 'metric_widgets.dart';

class DetailScreen extends StatelessWidget {
  const DetailScreen({super.key, required this.controller});

  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final route = state.detailRoute;
    final detail = state.detail;
    if (route == null) {
      return const Scaffold(body: Center(child: Text('No symbol selected')));
    }

    return DefaultTabController(
      length: 3,
      initialIndex: route.subtab.index,
      child: CallbackShortcuts(
        bindings: {
          const SingleActivator(LogicalKeyboardKey.arrowLeft): () =>
              controller.dispatch(const StepReplayBackAction()),
          const SingleActivator(LogicalKeyboardKey.arrowRight): () =>
              controller.dispatch(const StepReplayForwardAction()),
        },
        child: Focus(
          autofocus: true,
          child: Scaffold(
            appBar: AppBar(
              leading: IconButton(
                icon: const Icon(Icons.arrow_back),
                onPressed: () =>
                    controller.dispatch(const BackFromDetailAction()),
              ),
              title: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    detail?.companyName ?? route.symbol,
                    style: const TextStyle(fontSize: 16),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(
                    route.symbol,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () =>
                      controller.dispatch(const PrevTickerAction()),
                  child: const Text('Prev'),
                ),
                TextButton(
                  onPressed: () =>
                      controller.dispatch(const NextTickerAction()),
                  child: const Text('Next'),
                ),
                TextButton(
                  onPressed: () => controller
                      .dispatch(ToggleWatchlistAction(route.symbol)),
                  child: Text(detail?.isWatched == true ? 'Unwatch' : 'Watch'),
                ),
              ],
              bottom: TabBar(
                tabs: const [
                  Tab(text: 'Snapshot'),
                  Tab(text: 'Lens'),
                  Tab(text: 'History'),
                ],
                onTap: (i) => controller.dispatch(
                  SetDetailSubtabAction(DetailSubtab.values[i]),
                ),
              ),
            ),
            body: TabBarView(
              children: [
                _SnapshotBody(
                  controller: controller,
                  detail: detail,
                  route: route,
                  chart: state.projectedChart,
                  quantLens: state.quantLens,
                ),
                _LensBody(report: state.quantLens),
                _HistoryBody(
                  route: route,
                  chart: state.projectedChart,
                  controller: controller,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SnapshotBody extends StatelessWidget {
  const _SnapshotBody({
    required this.controller,
    required this.detail,
    required this.route,
    required this.chart,
    required this.quantLens,
  });

  final DashboardController controller;
  final SymbolDetail? detail;
  final DetailRoute route;
  final ProjectedChartData? chart;
  final QuantLensReport? quantLens;

  @override
  Widget build(BuildContext context) {
    if (detail == null) {
      return const Center(child: Text('Waiting for symbol data…'));
    }
    final d = detail!;
    final signals = chart?.analysis.technicalSignals ?? const [];
    final f = d.fundamentals;
    final lensChips = _lensHeaderChips(quantLens);

    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Text(
          'Price ${moneyFromCents(d.marketPriceCents)}  '
          'Fair ${moneyFromCents(d.intrinsicValueCents)}  '
          'Disc ${pctFromBps(d.gapBps)}  '
          'Upside ${pctFromBps(d.upsideBps)}',
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w800,
              ),
        ),
        const SizedBox(height: 4),
        Text(
          'Qual ${qualificationWireName(d.qualification).toLowerCase()}  ·  '
          'Conf ${confidenceWireName(d.confidence).toLowerCase()}  ·  '
          'External ${externalStatusWireName(d.externalStatus).toLowerCase()}',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 8),
        MetricFlow(
          children: [
            if (d.isWatched)
              ChangeBadge(
                'Watchlist',
                foreground: Theme.of(context).colorScheme.primary,
              ),
            MetricToken(
              'Upside ${pctFromBps(d.upsideBps)}',
              color: upsideColor(context, d.upsideBps),
            ),
            MetricToken(
              'Disc ${pctFromBps(d.gapBps)}',
              color: Colors.blueGrey.shade700,
            ),
            MetricToken(
              'Conf ${confidenceWireName(d.confidence).toLowerCase()}',
              color: confidenceColor(context, d.confidence),
            ),
            for (final chip in lensChips) MetricToken(chip),
          ],
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 6,
          runSpacing: 4,
          children: [
            for (final r in ChartRange.values)
              ChoiceChip(
                label: Text(chartRangeChipLabel(r)),
                selected: route.chartRange == r,
                onSelected: (_) =>
                    controller.dispatch(SetChartRangeAction(r)),
              ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            OutlinedButton(
              onPressed: () =>
                  controller.dispatch(const StepReplayBackAction()),
              child: const Text('Back'),
            ),
            const SizedBox(width: 8),
            OutlinedButton(
              onPressed: () =>
                  controller.dispatch(const StepReplayForwardAction()),
              child: const Text('Forward'),
            ),
            const SizedBox(width: 8),
            FilledButton.tonal(
              onPressed: () => controller.dispatch(const ResetReplayAction()),
              child: const Text('Live'),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                chart == null
                    ? 'No chart yet'
                    : 'Showing ${chart!.analysis.replayWindow.visibleCandles} / '
                        '${chart!.analysis.replayWindow.totalCandles} · '
                        'replay ${route.replayOffset}',
                style: Theme.of(context).textTheme.bodySmall,
                maxLines: 2,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        if (signals.isNotEmpty) ...[
          Text('Trend signals', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          for (final s in signals)
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                s.bias == ProjectedTechnicalSignalBias.bull
                    ? Icons.trending_up
                    : Icons.trending_down,
                color: s.bias == ProjectedTechnicalSignalBias.bull
                    ? Colors.green
                    : Colors.red,
              ),
              title: Text(s.title),
              subtitle: Text(s.meaning),
            ),
          const SizedBox(height: 8),
        ],
        Text('Price', style: Theme.of(context).textTheme.titleSmall),
        PriceChart(data: chart),
        const SizedBox(height: 8),
        Text('Volume', style: Theme.of(context).textTheme.titleSmall),
        VolumeChart(data: chart),
        const SizedBox(height: 8),
        Text('MACD', style: Theme.of(context).textTheme.titleSmall),
        MacdChart(data: chart),
        const SizedBox(height: 8),
        Text('RSI', style: Theme.of(context).textTheme.titleSmall),
        RsiChart(data: chart),
        const SizedBox(height: 12),
        SectionCard(
          title: 'Valuation map',
          child: Column(
            children: [
              KeyValueRow('Market price', moneyFromCents(d.marketPriceCents)),
              KeyValueRow(
                'Primary fair',
                moneyFromCents(d.intrinsicValueCents),
                valueColor: upsideColor(context, d.upsideBps),
              ),
              KeyValueRow('Discount', pctFromBps(d.gapBps)),
              KeyValueRow('Upside', pctFromBps(d.upsideBps)),
              KeyValueRow(
                'Analyst mean',
                moneyFromCents(d.externalSignalFairValueCents),
              ),
              KeyValueRow(
                'Analyst weighted',
                moneyFromCents(d.weightedExternalSignalFairValueCents),
              ),
              KeyValueRow(
                'Analyst low',
                moneyFromCents(d.externalSignalLowFairValueCents),
              ),
              KeyValueRow(
                'Analyst high',
                moneyFromCents(d.externalSignalHighFairValueCents),
              ),
              KeyValueRow(
                'Ext. gap',
                pctFromBps(d.externalSignalGapBps),
              ),
            ],
          ),
        ),
        SectionCard(
          title: 'Analyst consensus',
          child: Column(
            children: [
              KeyValueRow('Opinions', '${d.analystOpinionCount ?? '—'}'),
              KeyValueRow(
                'Weighted count',
                '${d.weightedAnalystCount ?? '—'}',
              ),
              KeyValueRow(
                'Rec mean',
                d.recommendationMeanHundredths == null
                    ? '—'
                    : (d.recommendationMeanHundredths! / 100).toStringAsFixed(2),
              ),
              KeyValueRow('Strong buy', '${d.strongBuyCount ?? '—'}'),
              KeyValueRow('Buy', '${d.buyCount ?? '—'}'),
              KeyValueRow('Hold', '${d.holdCount ?? '—'}'),
              KeyValueRow('Sell', '${d.sellCount ?? '—'}'),
              KeyValueRow('Strong sell', '${d.strongSellCount ?? '—'}'),
            ],
          ),
        ),
        if (f != null)
          SectionCard(
            title: 'Fundamentals',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                MetricFlow(
                  children: [
                    if (f.sectorName != null) MetricToken(f.sectorName!),
                    if (f.industryName != null) MetricToken(f.industryName!),
                    if (f.returnOnEquityBps != null)
                      MetricToken('ROE ${pctFromBps(f.returnOnEquityBps)}'),
                    if (f.freeCashFlowDollars != null)
                      MetricToken('FCF ${compactDollars(f.freeCashFlowDollars)}'),
                    if (f.operatingCashFlowDollars != null)
                      MetricToken(
                        'OCF ${compactDollars(f.operatingCashFlowDollars)}',
                      ),
                    if (f.trailingPeHundredths != null)
                      MetricToken(
                        'P/E ${ratioFromHundredths(f.trailingPeHundredths)}',
                      ),
                    if (f.forwardPeHundredths != null)
                      MetricToken(
                        'Fwd P/E ${ratioFromHundredths(f.forwardPeHundredths)}',
                      ),
                    if (f.priceToBookHundredths != null)
                      MetricToken(
                        'P/B ${ratioFromHundredths(f.priceToBookHundredths)}',
                      ),
                    if (f.enterpriseToEbitdaHundredths != null)
                      MetricToken(
                        'EV/EBITDA ${ratioFromHundredths(f.enterpriseToEbitdaHundredths)}',
                      ),
                    if (f.debtToEquityHundredths != null)
                      MetricToken(
                        'D/E ${ratioFromHundredths(f.debtToEquityHundredths)}',
                      ),
                    if (f.marketCapDollars != null)
                      MetricToken(
                        'Mkt cap ${compactDollars(f.marketCapDollars)}',
                      ),
                    if (f.betaMillis != null)
                      MetricToken(
                        'Beta ${(f.betaMillis! / 1000).toStringAsFixed(2)}',
                      ),
                    if (f.earningsGrowthBps != null)
                      MetricToken(
                        'Earn gr ${pctFromBps(f.earningsGrowthBps)}',
                      ),
                  ],
                ),
                const SizedBox(height: 8),
                KeyValueRow('Cash', compactDollars(f.totalCashDollars)),
                KeyValueRow('Debt', compactDollars(f.totalDebtDollars)),
                KeyValueRow('EBITDA', compactDollars(f.ebitdaDollars)),
                KeyValueRow(
                  'Shares',
                  f.sharesOutstanding == null
                      ? '—'
                      : compactDollars(f.sharesOutstanding),
                ),
              ],
            ),
          ),
        SectionCard(
          title: 'Quality',
          child: Column(
            children: [
              KeyValueRow(
                'Qualification',
                qualificationWireName(d.qualification),
              ),
              KeyValueRow('Confidence', confidenceWireName(d.confidence)),
              KeyValueRow('External', externalStatusWireName(d.externalStatus)),
              KeyValueRow('Profitable', d.profitable ? 'yes' : 'no'),
              KeyValueRow('Min gap', pctFromBps(d.minimumGapBps)),
              KeyValueRow('Updates', '${d.updateCount}'),
              KeyValueRow('Sequence', '${d.lastSequence}'),
            ],
          ),
        ),
      ],
    );
  }

  List<String> _lensHeaderChips(QuantLensReport? report) {
    if (report == null) return const ['Lens loading'];
    return [
      'Lens ${report.primaryStatus.name}',
      if (report.expectedValueRange.midCents != null)
        'EV mid ${moneyFromCents(report.expectedValueRange.midCents)}',
      'Evidence ${report.evidenceStrength.band.name}',
    ];
  }
}

class _LensBody extends StatelessWidget {
  const _LensBody({required this.report});
  final QuantLensReport? report;

  @override
  Widget build(BuildContext context) {
    if (report == null) {
      return ListView(
        padding: const EdgeInsets.all(12),
        children: const [
          SectionCard(
            title: 'Quant Lens',
            child: Text(
              'Open Snapshot first so charts/DCF load, then return here. '
              'Lens needs price, valuation, and enough candle history.',
            ),
          ),
        ],
      );
    }
    final r = report!;
    final ev = r.expectedValueRange;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        SectionCard(
          title: 'Quant Lens · ${r.symbol}',
          child: MetricFlow(
            children: [
              MetricToken('Status ${r.primaryStatus.name}'),
              MetricToken('Evidence ${r.evidenceStrength.band.name}'),
              MetricToken('EV ${ev.band.name}'),
              MetricToken('Corr ${r.correlationRisk.band.name}'),
              MetricToken('Trend ${r.trendReliability.band.name}'),
              MetricToken('Setups ${r.similarSetups.band.name}'),
            ],
          ),
        ),
        SectionCard(
          title: 'Evidence strength',
          child: Column(
            children: [
              KeyValueRow('Primary', r.evidenceStrength.primaryStatus.name),
              KeyValueRow('Band', r.evidenceStrength.band.name),
              KeyValueRow(
                'Strength',
                pctFromBps(r.evidenceStrength.strengthBps),
              ),
              KeyValueRow('Support', '${r.evidenceStrength.supportCount}'),
              KeyValueRow('Conflict', '${r.evidenceStrength.conflictCount}'),
              KeyValueRow('Neutral', '${r.evidenceStrength.neutralCount}'),
            ],
          ),
        ),
        SectionCard(
          title: 'Expected value range',
          child: Column(
            children: [
              KeyValueRow('Source', ev.source.name),
              KeyValueRow('Low', moneyFromCents(ev.lowCents)),
              KeyValueRow('Mid', moneyFromCents(ev.midCents)),
              KeyValueRow('High', moneyFromCents(ev.highCents)),
              KeyValueRow('Upside low', pctFromBps(ev.upsideLowBps)),
              KeyValueRow('Upside mid', pctFromBps(ev.upsideMidBps)),
              KeyValueRow('Upside high', pctFromBps(ev.upsideHighBps)),
            ],
          ),
        ),
        SectionCard(
          title: 'Correlation risk',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow('Band', r.correlationRisk.band.name),
              KeyValueRow(
                'Avg |corr|',
                (r.correlationRisk.averageAbsCorrelationMillis / 1000)
                    .toStringAsFixed(2),
              ),
              KeyValueRow(
                'Peers',
                '${r.correlationRisk.pairs.length}',
              ),
              for (final p in r.correlationRisk.pairs.take(8))
                KeyValueRow(
                  p.peerSymbol,
                  (p.correlationMillis / 1000).toStringAsFixed(2),
                ),
            ],
          ),
        ),
        SectionCard(
          title: 'Trend reliability',
          child: Column(
            children: [
              KeyValueRow('Band', r.trendReliability.band.name),
              KeyValueRow('Samples', '${r.trendReliability.sampleCount}'),
              KeyValueRow(
                'Persistence',
                pctFromBps(r.trendReliability.persistenceBps),
              ),
            ],
          ),
        ),
        SectionCard(
          title: 'Similar setups',
          child: Column(
            children: [
              KeyValueRow('Band', r.similarSetups.band.name),
              KeyValueRow('Matches', '${r.similarSetups.matches.length}'),
              for (final m in r.similarSetups.matches.take(6))
                KeyValueRow(
                  m.peerSymbol,
                  'sim ${pctFromBps(m.similarityBps)} · fwd ${pctFromBps(m.forwardMoveBps)}',
                ),
            ],
          ),
        ),
        SectionCard(
          title: 'Horizon context',
          child: Column(
            children: [
              for (final h in r.horizonContext.horizons)
                KeyValueRow(
                  h.horizon.name,
                  'n=${h.sampleCount} mid ${pctFromBps(h.medianAbsoluteMoveBps)}',
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _HistoryBody extends StatelessWidget {
  const _HistoryBody({
    required this.route,
    required this.chart,
    required this.controller,
  });

  final DetailRoute route;
  final ProjectedChartData? chart;
  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    final candles = chart?.candles ?? const <HistoricalCandle>[];
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'Candles · ${chartRangeChipLabel(route.chartRange)} · '
                  '${candles.length} bars',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
              TextButton(
                onPressed: () => controller.dispatch(
                  SetDetailSubtabAction(DetailSubtab.snapshot),
                ),
                child: const Text('Charts'),
              ),
            ],
          ),
        ),
        Expanded(
          child: candles.isEmpty
              ? const Center(child: Text('No candles for this range yet'))
              : ListView.separated(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  itemCount: candles.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (context, i) {
                    final c = candles[candles.length - 1 - i];
                    final dt = DateTime.fromMillisecondsSinceEpoch(
                      c.epochSeconds * 1000,
                      isUtc: true,
                    ).toLocal();
                    final date =
                        '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
                    return ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(date),
                      subtitle: Text(
                        'O ${moneyFromCents(c.openCents)}  '
                        'H ${moneyFromCents(c.highCents)}  '
                        'L ${moneyFromCents(c.lowCents)}  '
                        'C ${moneyFromCents(c.closeCents)}',
                      ),
                      trailing: Text(
                        'Vol ${compactDollars(c.volume)}',
                        style: Theme.of(context).textTheme.labelSmall,
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }
}
