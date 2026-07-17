import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const splashMinimumDuration = Duration(milliseconds: 4000);

const disclaimerBody = '''
This project is provided for informational and educational purposes only. It is not trading advice, investment advice, financial advice, legal advice, tax advice, accounting advice, or a recommendation to buy, sell, or hold any security.

Market data, analyst targets, ratings, and derived signals may be delayed, incomplete, inaccurate, or unavailable. You are solely responsible for any decisions or actions you take based on this software or its output. Always verify information independently and consult a qualified professional where appropriate.
''';

enum StartupStage { splash, disclaimer, content }

StartupStage startupStage({
  required bool loading,
  required bool splashMinimumElapsed,
  required bool disclaimerAccepted,
}) {
  if (!splashMinimumElapsed || loading) return StartupStage.splash;
  if (!disclaimerAccepted) return StartupStage.disclaimer;
  return StartupStage.content;
}

class StartupSplashScreen extends StatelessWidget {
  const StartupSplashScreen({super.key, required this.loading});

  final bool loading;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Text('👮', style: TextStyle(fontSize: 56)),
              Text(
                'Discount Screener',
                style: theme.textTheme.headlineMedium
                    ?.copyWith(fontWeight: FontWeight.bold),
              ),
              Text('Copyright Juan Lescano', style: theme.textTheme.titleMedium),
              Text(
                'https://github.com/Thejuampi',
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: theme.colorScheme.tertiary),
              ),
              const SizedBox(height: 12),
              Text(
                loading ? 'Loading local database...' : 'Starting app...',
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class DisclaimerGate extends StatelessWidget {
  const DisclaimerGate({
    super.key,
    required this.onAccept,
    required this.onClose,
  });

  final VoidCallback onAccept;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            children: [
              Text(
                'Disclaimer',
                style: Theme.of(context)
                    .textTheme
                    .headlineSmall
                    ?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 16),
              Expanded(
                child: SingleChildScrollView(
                  child: Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(disclaimerBody.trim()),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: onClose,
                      child: const Text('Close app'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: onAccept,
                      child: const Text('Accept and continue'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

void closeApp() {
  SystemNavigator.pop();
}
