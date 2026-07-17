/// Loads symbol profile universes (sp500, dow, …).
class ProfileCatalog {
  ProfileCatalog({Map<String, List<String>>? profiles})
      : _profiles = profiles ?? {};

  final Map<String, List<String>> _profiles;

  List<String> availableProfiles() =>
      (_profiles.keys.toList()..sort());

  List<String> loadProfile(String name) =>
      List.unmodifiable(_profiles[name] ?? const []);

  /// Parse Android-style profile text (one ticker per line, `#` comments).
  static Map<String, List<String>> parseProfileText(
    String profileId,
    String body,
  ) {
    final symbols = <String>[];
    for (final line in body.split(RegExp(r'\r?\n'))) {
      final trimmed = line.trim();
      if (trimmed.isEmpty || trimmed.startsWith('#')) continue;
      symbols.add(trimmed.toUpperCase());
    }
    return {profileId: symbols};
  }

  List<({String symbol, String? company})> searchTickers(
    String query, {
    String? preferredProfile,
    int limit = 8,
  }) {
    final q = query.trim().toUpperCase();
    if (q.isEmpty) return const [];
    final hits = <({String symbol, String? company, int rank})>[];
    for (final entry in _profiles.entries) {
      for (final symbol in entry.value) {
        final upper = symbol.toUpperCase();
        if (!upper.contains(q)) continue;
        final prefix = upper.startsWith(q) ? 0 : 1;
        final preferred =
            preferredProfile != null && entry.key == preferredProfile ? 0 : 1;
        hits.add((symbol: upper, company: null, rank: preferred * 10 + prefix));
      }
    }
    hits.sort((a, b) {
      final r = a.rank.compareTo(b.rank);
      if (r != 0) return r;
      return a.symbol.compareTo(b.symbol);
    });
    final seen = <String>{};
    final out = <({String symbol, String? company})>[];
    for (final h in hits) {
      if (!seen.add(h.symbol)) continue;
      out.add((symbol: h.symbol, company: h.company));
      if (out.length >= limit) break;
    }
    return out;
  }
}
