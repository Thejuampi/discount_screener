import 'package:ds_core/ds_core.dart';
import 'package:flutter/material.dart';

import 'formatters.dart';

/// Compact metric chip matching Android `MetricToken` density.
class MetricToken extends StatelessWidget {
  const MetricToken(this.label, {super.key, this.color});

  final String label;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fg = color ?? scheme.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: fg.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: fg,
              fontWeight: FontWeight.w600,
            ),
      ),
    );
  }
}

class ChangeBadge extends StatelessWidget {
  const ChangeBadge(
    this.label, {
    super.key,
    this.foreground,
    this.background,
  });

  final String label;
  final Color? foreground;
  final Color? background;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fg = foreground ?? scheme.primary;
    final bg = background ?? fg.withValues(alpha: 0.12);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: fg,
              fontWeight: FontWeight.w700,
            ),
      ),
    );
  }
}

class SymbolCompanyTitle extends StatelessWidget {
  const SymbolCompanyTitle({
    super.key,
    required this.symbol,
    this.companyName,
  });

  final String symbol;
  final String? companyName;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final name = companyName?.trim();
    final showName = name != null &&
        name.isNotEmpty &&
        name.toLowerCase() != 'null' &&
        name.toUpperCase() != symbol.toUpperCase();
    return Row(
      children: [
        Text(
          symbol,
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w800,
                color: scheme.primary,
              ),
        ),
        if (showName) ...[
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
            ),
          ),
        ],
      ],
    );
  }
}

class ScoreBadge extends StatelessWidget {
  const ScoreBadge({super.key, required this.score, required this.model});

  final int score;
  final OpportunityScoringModel model;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final continuous = model == OpportunityScoringModel.aggressiveV2 ||
        model == OpportunityScoringModel.aggressiveV3;
    final color = score >= (continuous ? 30 : 10)
        ? Colors.green.shade700
        : score < (continuous ? 0 : 8)
            ? scheme.error
            : scheme.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Text(
        continuous ? '$score' : 'Score $score',
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: color,
              fontWeight: FontWeight.w800,
            ),
      ),
    );
  }
}

class MetricFlow extends StatelessWidget {
  const MetricFlow({super.key, required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 6,
      runSpacing: 4,
      children: children,
    );
  }
}

/// Key/value row used on detail valuation / fundamentals sections.
class KeyValueRow extends StatelessWidget {
  const KeyValueRow(this.label, this.value, {super.key, this.valueColor});

  final String label;
  final String value;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
            ),
          ),
          Text(
            value,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                  color: valueColor,
                ),
          ),
        ],
      ),
    );
  }
}

Color upsideColor(BuildContext context, int? bps) {
  if (bps == null) return Theme.of(context).colorScheme.onSurfaceVariant;
  if (bps >= 2000) return Colors.green.shade700;
  if (bps <= -1000) return Theme.of(context).colorScheme.error;
  return Theme.of(context).colorScheme.onSurfaceVariant;
}

Color confidenceColor(BuildContext context, ConfidenceBand band) {
  final scheme = Theme.of(context).colorScheme;
  return switch (band) {
    ConfidenceBand.high => Colors.green.shade700,
    ConfidenceBand.provisional => Colors.orange.shade800,
    ConfidenceBand.low => scheme.error,
  };
}

/// Opportunity list card matching Android density (F/T/Fc + Disc/Upside/Conf).
class OpportunityListCard extends StatelessWidget {
  const OpportunityListCard({
    super.key,
    required this.row,
    required this.model,
    required this.onTap,
  });

  final OpportunityRow row;
  final OpportunityScoringModel model;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: SymbolCompanyTitle(
                      symbol: row.symbol,
                      companyName: row.companyName,
                    ),
                  ),
                  ScoreBadge(score: row.compositeScore, model: model),
                ],
              ),
              const SizedBox(height: 6),
              MetricFlow(
                children: [
                  if (row.isWatched)
                    ChangeBadge(
                      'Watchlist',
                      foreground: Theme.of(context).colorScheme.primary,
                    ),
                  MetricToken(
                    'F ${formatOpportunityBucket(row.fundamentalsScore, model)}',
                    color: Colors.indigo.shade600,
                  ),
                  MetricToken(
                    'T ${formatOpportunityBucket(row.technicalScore, model)}',
                    color: Colors.teal.shade700,
                  ),
                  MetricToken(
                    'Fc ${formatOpportunityBucket(row.forecastScore, model)}',
                    color: Colors.deepPurple.shade600,
                  ),
                  MetricToken(
                    'Disc ${pctFromBps(row.gapBps)}',
                    color: Colors.blueGrey.shade700,
                  ),
                  MetricToken(
                    'Upside ${pctFromBps(row.upsideBps)}',
                    color: upsideColor(context, row.upsideBps),
                  ),
                  MetricToken(
                    'Conf ${confidenceWireName(row.confidence).toLowerCase()}',
                    color: confidenceColor(context, row.confidence),
                  ),
                  MetricToken(
                    'Price ${moneyFromCents(row.marketPriceCents)}',
                  ),
                  MetricToken(
                    'Fair ${moneyFromCents(row.intrinsicValueCents)}',
                  ),
                  MetricToken('Cov ${row.coverageCount}/3'),
                ],
              ),
              if (row.fundamentalsSignals.isNotEmpty ||
                  row.technicalSignals.isNotEmpty ||
                  row.forecastSignals.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  [
                    ...row.fundamentalsSignals.take(2),
                    ...row.technicalSignals.take(2),
                    ...row.forecastSignals.take(2),
                  ].join(' · '),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Tracked / Upside list card matching Android density.
class TrackedListCard extends StatelessWidget {
  const TrackedListCard({
    super.key,
    required this.row,
    required this.watched,
    required this.onTap,
    required this.onToggleWatch,
  });

  final CandidateRow row;
  final bool watched;
  final VoidCallback? onTap;
  final VoidCallback onToggleWatch;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SymbolCompanyTitle(
                      symbol: row.symbol,
                      companyName: row.companyName,
                    ),
                    const SizedBox(height: 6),
                    MetricFlow(
                      children: [
                        MetricToken(
                          'Price ${moneyFromCents(row.marketPriceCents)}',
                        ),
                        MetricToken(
                          'Fair ${moneyFromCents(row.intrinsicValueCents)}',
                        ),
                        MetricToken(
                          'Disc ${pctFromBps(row.gapBps)}',
                          color: Colors.blueGrey.shade700,
                        ),
                        MetricToken(
                          'Upside ${pctFromBps(row.upsideBps)}',
                          color: upsideColor(context, row.upsideBps),
                        ),
                        MetricToken(
                          'Conf ${confidenceWireName(row.confidence).toLowerCase()}',
                          color: confidenceColor(context, row.confidence),
                        ),
                        if (row.isQualified)
                          ChangeBadge(
                            'Qualified',
                            foreground: Colors.green.shade700,
                          )
                        else
                          ChangeBadge(
                            'Gap small',
                            foreground: Colors.orange.shade800,
                          ),
                      ],
                    ),
                  ],
                ),
              ),
              IconButton(
                tooltip: watched ? 'Remove from watchlist' : 'Add to watchlist',
                icon: Icon(watched ? Icons.star : Icons.star_border),
                onPressed: onToggleWatch,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class SectionCard extends StatelessWidget {
  const SectionCard({
    super.key,
    required this.title,
    required this.child,
    this.trailing,
  });

  final String title;
  final Widget child;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w800,
                        ),
                  ),
                ),
                if (trailing != null) trailing!,
              ],
            ),
            const SizedBox(height: 8),
            child,
          ],
        ),
      ),
    );
  }
}
