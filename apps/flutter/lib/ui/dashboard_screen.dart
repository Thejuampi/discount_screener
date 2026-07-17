import 'package:ds_core/ds_core.dart';
import 'package:flutter/material.dart';

import '../presentation/dashboard_controller.dart';
import 'formatters.dart';
import 'metric_widgets.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({
    super.key,
    required this.controller,
    this.embeddedDetail,
  });

  final DashboardController controller;
  final Widget? embeddedDetail;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 840;
        final list = _DashboardBody(controller: controller);
        if (wide && embeddedDetail != null && state.detailRoute != null) {
          return Row(
            children: [
              SizedBox(width: constraints.maxWidth * 0.38, child: list),
              const VerticalDivider(width: 1),
              Expanded(child: embeddedDetail!),
            ],
          );
        }
        return list;
      },
    );
  }
}

class _DashboardBody extends StatefulWidget {
  const _DashboardBody({required this.controller});
  final DashboardController controller;

  @override
  State<_DashboardBody> createState() => _DashboardBodyState();
}

class _DashboardBodyState extends State<_DashboardBody>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(
      length: DashboardTab.values.length,
      vsync: this,
      initialIndex: widget.controller.state.currentTab.index,
    );
    _tabs.addListener(() {
      if (!_tabs.indexIsChanging) {
        widget.controller.dispatch(
          SelectTabAction(DashboardTab.values[_tabs.index]),
        );
      }
    });
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final state = controller.state;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Discount Screener'),
        actions: [
          TextButton(
            onPressed: () => controller.dispatch(const RefreshAction()),
            child: const Text('Refresh'),
          ),
          TextButton(
            onPressed: () => _showAddDialog(context),
            child: const Text('Add'),
          ),
          TextButton(
            onPressed: () => _showProfileDialog(context),
            child: Text(state.currentProfile.toUpperCase()),
          ),
        ],
        bottom: TabBar(
          controller: _tabs,
          isScrollable: true,
          tabs: [
            Tab(text: 'Opps ${state.opportunityRows.length}'),
            Tab(text: 'Upside ${state.trackedRows.length}'),
            Tab(text: 'Watch ${state.watchlistSymbols.length}'),
            const Tab(text: 'System'),
            const Tab(text: 'Estimates'),
          ],
        ),
      ),
      body: Column(
        children: [
          if (state.refreshing) const LinearProgressIndicator(),
          if (state.statusMessage != null)
            Material(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    state.statusMessage!,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: TextField(
              decoration: InputDecoration(
                labelText: 'Ticker',
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.search),
                  onPressed: () =>
                      controller.dispatch(const SubmitTickerSearchAction()),
                ),
              ),
              textCapitalization: TextCapitalization.characters,
              onChanged: (v) =>
                  controller.dispatch(UpdateTickerSearchQueryAction(v)),
              onSubmitted: (_) =>
                  controller.dispatch(const SubmitTickerSearchAction()),
            ),
          ),
          Expanded(
            child: TabBarView(
              controller: _tabs,
              children: [
                _OpportunitiesPane(controller: controller),
                _TrackedPane(controller: controller, watchOnly: false),
                _TrackedPane(controller: controller, watchOnly: true),
                _SystemPane(controller: controller),
                _EstimatesPane(controller: controller),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _showAddDialog(BuildContext context) async {
    final field = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Add symbols'),
        content: TextField(
          controller: field,
          decoration: const InputDecoration(
            labelText: 'Comma-separated symbols',
            border: OutlineInputBorder(),
          ),
          textCapitalization: TextCapitalization.characters,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Add'),
          ),
        ],
      ),
    );
    if (ok == true && field.text.trim().isNotEmpty) {
      widget.controller.dispatch(AddSymbolsAction(field.text));
    }
  }

  Future<void> _showProfileDialog(BuildContext context) async {
    final state = widget.controller.state;
    await showDialog<void>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('Profiles'),
        children: [
          for (final p in state.availableProfiles)
            SimpleDialogOption(
              onPressed: () {
                widget.controller.dispatch(SelectProfileAction(p));
                Navigator.pop(ctx);
              },
              child: Text(
                p.toUpperCase() + (p == state.currentProfile ? '  ✓' : ''),
              ),
            ),
        ],
      ),
    );
  }
}

class _OpportunitiesPane extends StatelessWidget {
  const _OpportunitiesPane({required this.controller});
  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final models = [
      OpportunityScoringModel.aggressiveV3,
      OpportunityScoringModel.aggressiveV2,
      OpportunityScoringModel.aggressive,
      OpportunityScoringModel.legacy,
    ];
    return Column(
      children: [
        SizedBox(
          height: 48,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 8),
            children: [
              for (final m in models)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: FilterChip(
                    label: Text(scoringModelLabel(m.name)),
                    selected: state.opportunityScoringModel == m,
                    onSelected: (_) => controller
                        .dispatch(SetOpportunityScoringModelAction(m)),
                  ),
                ),
            ],
          ),
        ),
        Expanded(
          child: state.opportunityRows.isEmpty
              ? const _Empty(
                  title: 'No ranked opportunities',
                  detail:
                      'Opportunity ranks appear once restored data or live coverage is strong enough to score the current universe.',
                )
              : ListView.builder(
                  padding: const EdgeInsets.only(bottom: 12),
                  itemCount: state.opportunityRows.length,
                  itemBuilder: (context, i) {
                    final row = state.opportunityRows[i];
                    return OpportunityListCard(
                      row: row,
                      model: state.opportunityScoringModel,
                      onTap: () =>
                          controller.dispatch(OpenDetailAction(row.symbol)),
                    );
                  },
                ),
        ),
      ],
    );
  }
}

class _TrackedPane extends StatelessWidget {
  const _TrackedPane({required this.controller, required this.watchOnly});
  final DashboardController controller;
  final bool watchOnly;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final rows = watchOnly
        ? state.trackedRows
            .where((r) => state.watchlistSymbols.contains(r.symbol))
            .toList()
        : state.trackedRows;

    if (rows.isEmpty) {
      return _Empty(
        title: watchOnly ? 'Watchlist is empty' : 'No tracked symbols',
        detail: watchOnly
            ? 'Add symbols to the watchlist from Upside or Opps.'
            : 'Load a profile or add symbols to begin.',
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.only(bottom: 12, top: 4),
      itemCount: rows.length,
      itemBuilder: (context, i) {
        final row = rows[i];
        final watched = state.watchlistSymbols.contains(row.symbol);
        return TrackedListCard(
          row: row,
          watched: watched,
          onTap: row.marketPriceCents > 0
              ? () => controller.dispatch(OpenDetailAction(row.symbol))
              : null,
          onToggleWatch: () =>
              controller.dispatch(ToggleWatchlistAction(row.symbol)),
        );
      },
    );
  }
}

class _SystemPane extends StatelessWidget {
  const _SystemPane({required this.controller});
  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final activeIssues =
        state.issues.where((i) => i.active).toList(growable: false);
    final watched = state.watchlistSymbols.length;
    final qualified =
        state.trackedRows.where((r) => r.isQualified).length;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        SectionCard(
          title: 'Refresh status',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow('Status', state.statusMessage ?? 'Ready'),
              KeyValueRow('Loading', state.loading ? 'yes' : 'no'),
              KeyValueRow('Refreshing', state.refreshing ? 'yes' : 'no'),
              KeyValueRow(
                'Data mode',
                state.usingDemoData ? 'Demo seed' : 'Live / restored',
              ),
              KeyValueRow('Scoring model', scoringModelLabel(state.opportunityScoringModel.name)),
            ],
          ),
        ),
        SectionCard(
          title: 'Universe',
          child: Column(
            children: [
              KeyValueRow('Profile', state.currentProfile.toUpperCase()),
              KeyValueRow('Tracked (list)', '${state.trackedRows.length}'),
              KeyValueRow('Qualified', '$qualified'),
              KeyValueRow('Watchlist', '$watched'),
              KeyValueRow('Opportunities', '${state.opportunityRows.length}'),
              KeyValueRow(
                'Tip',
                'Refresh reloads the full profile (not the startup batch).',
              ),
            ],
          ),
        ),
        SectionCard(
          title: 'Issues (${activeIssues.length} active)',
          child: activeIssues.isEmpty
              ? const Text('No active errors')
              : Column(
                  children: [
                    for (final issue in activeIssues.take(12))
                      ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(issue.title),
                        subtitle: Text(
                          issue.detail,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                        ),
                        trailing: Text(
                          '×${issue.count}',
                          style: Theme.of(context).textTheme.labelSmall,
                        ),
                      ),
                  ],
                ),
        ),
        SectionCard(
          title: 'Maintenance',
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              OutlinedButton(
                onPressed: () => controller.dispatch(const RefreshAction()),
                child: const Text('Refresh now'),
              ),
              OutlinedButton(
                onPressed: () {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text(
                        'State is JSON-persisted under app documents '
                        '(report_state.json + candles.json).',
                      ),
                    ),
                  );
                },
                child: const Text('Storage info'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _EstimatesPane extends StatelessWidget {
  const _EstimatesPane({required this.controller});
  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    final report = controller.state.estimates;
    if (report == null) {
      return const _Empty(
        title: 'No estimates yet',
        detail: 'Load a profile or wait for market-cap coverage.',
      );
    }
    final coverage = report.dcfCoverage;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Index estimates · ${report.profileName.toUpperCase()}',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                Text(
                  'Cap-weighted current ${moneyFromCents(report.currentWeightedPriceCents)} · '
                  '${report.totalSymbols} eligible symbols',
                ),
                const SizedBox(height: 12),
                Chip(
                  label: Text(
                    'DCF coverage: ${coverage.status.name} '
                    '(${coverage.coveredSymbols}/${coverage.totalEligibleSymbols} · '
                    '${pctFromBps(coverage.coverageBps)})',
                  ),
                  backgroundColor:
                      Theme.of(context).colorScheme.secondaryContainer,
                ),
              ],
            ),
          ),
        ),
        for (final s in report.scenarios)
          Card(
            child: ListTile(
              title: Text(_scenarioLabel(s.scenario)),
              subtitle: Text(
                'Weighted ${moneyFromCents(s.weightedPriceCents)} · '
                'Upside ${pctFromBps(s.impliedUpsideBps)} · '
                'Coverage ${s.coverageCount}',
              ),
              trailing: Text(
                pctFromBps(s.impliedUpsideBps),
                style: TextStyle(
                  color: (s.impliedUpsideBps) >= 0 ? Colors.green : Colors.red,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
      ],
    );
  }

  String _scenarioLabel(EstimateScenario s) => switch (s) {
        EstimateScenario.bearDcf => 'Bear DCF',
        EstimateScenario.baseDcf => 'Base DCF',
        EstimateScenario.bullDcf => 'Bull DCF',
        EstimateScenario.analystLow => 'Analyst low',
        EstimateScenario.analystHigh => 'Analyst high',
      };
}

class _Empty extends StatelessWidget {
  const _Empty({required this.title, required this.detail});
  final String title;
  final String detail;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(detail, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
