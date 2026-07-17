import 'package:ds_data/ds_data.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'presentation/dashboard_controller.dart';
import 'storage_bootstrap.dart';
import 'ui/dashboard_screen.dart';
import 'ui/detail_screen.dart';
import 'ui/startup.dart';
import 'ui/theme.dart';

const _prefsNameKey = 'disclaimer_accepted_v1';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  final accepted = prefs.getBool(_prefsNameKey) ?? false;

  final profiles = await _loadProfilesFromAssets();
  final catalog = ProfileCatalog(profiles: profiles);
  final store = await openStateStore();

  final repository = DashboardRepository(
    profileCatalog: catalog,
    store: store,
    // Web has CORS limits against Yahoo; keep UI usable offline.
    liveMode: !kIsWeb,
    // Desktop can sustain higher concurrency than mobile.
    maxConcurrentFetches: kIsWeb ? 2 : 5,
  );

  final controller = DashboardController(
    repository: repository,
    // First paint only — background continue fills the rest; Refresh is full.
    liveFetchSymbolLimit: 40,
    backgroundContinueProfile: true,
  );

  runApp(
    DiscountScreenerApp(
      initialDisclaimerAccepted: accepted,
      prefs: prefs,
      controller: controller,
    ),
  );
}

Future<Map<String, List<String>>> _loadProfilesFromAssets() async {
  const ids = [
    'sp500',
    'dow',
    'russell',
    'merval',
    'nikkei',
    'europe',
    'asia',
  ];
  final out = <String, List<String>>{};
  for (final id in ids) {
    try {
      final body = await rootBundle.loadString('assets/profiles/$id.txt');
      out.addAll(ProfileCatalog.parseProfileText(id, body));
    } catch (_) {
      // Asset optional in tests.
    }
  }
  return out;
}

class DiscountScreenerApp extends StatefulWidget {
  const DiscountScreenerApp({
    super.key,
    required this.initialDisclaimerAccepted,
    required this.prefs,
    required this.controller,
  });

  final bool initialDisclaimerAccepted;
  final SharedPreferences prefs;
  final DashboardController controller;

  @override
  State<DiscountScreenerApp> createState() => _DiscountScreenerAppState();
}

class _DiscountScreenerAppState extends State<DiscountScreenerApp> {
  late final DashboardController _controller;

  @override
  void initState() {
    super.initState();
    _controller = widget.controller;
    if (widget.initialDisclaimerAccepted) {
      _controller.dispatch(const AcceptDisclaimerAction());
    }
    _controller.dispatch(const StartAction());
    Future<void>.delayed(splashMinimumDuration, () {
      if (mounted) _controller.markSplashElapsed();
    });
    _controller.addListener(_onChange);
  }

  void _onChange() => setState(() {});

  @override
  void dispose() {
    _controller.removeListener(_onChange);
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = _controller.state;
    final stage = startupStage(
      loading: state.loading,
      splashMinimumElapsed: state.splashMinimumElapsed,
      disclaimerAccepted: state.disclaimerAccepted,
    );

    return MaterialApp(
      title: 'Discount Screener',
      theme: lightTheme(),
      darkTheme: darkTheme(),
      themeMode: ThemeMode.system,
      home: switch (stage) {
        StartupStage.splash => StartupSplashScreen(loading: state.loading),
        StartupStage.disclaimer => DisclaimerGate(
            onAccept: () async {
              await widget.prefs.setBool(_prefsNameKey, true);
              _controller.dispatch(const AcceptDisclaimerAction());
            },
            onClose: closeApp,
          ),
        StartupStage.content => _ContentShell(controller: _controller),
      },
    );
  }
}

class _ContentShell extends StatelessWidget {
  const _ContentShell({required this.controller});
  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final width = MediaQuery.sizeOf(context).width;
    final masterDetail = width >= 840;

    if (masterDetail) {
      return DashboardScreen(
        controller: controller,
        embeddedDetail: state.detailRoute == null
            ? const Center(child: Text('Select a symbol'))
            : DetailScreen(controller: controller),
      );
    }

    if (state.detailRoute != null) {
      return DetailScreen(controller: controller);
    }
    return DashboardScreen(controller: controller);
  }
}
