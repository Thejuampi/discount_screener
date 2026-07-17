import 'package:discount_screener/presentation/dashboard_controller.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('dashboard controller seeds opportunities on start', () {
    final controller = DashboardController();
    controller.dispatch(const StartAction());
    expect(controller.state.loading, isFalse);
    expect(controller.state.opportunityRows, isNotEmpty);
    expect(controller.state.opportunityScoringModel.name, 'aggressiveV2');
    controller.dispose();
  });

  test('open detail and back clears route', () {
    final controller = DashboardController();
    controller.dispatch(const StartAction());
    final symbol = controller.state.opportunityRows.first.symbol;
    controller.dispatch(OpenDetailAction(symbol));
    expect(controller.state.detailRoute?.symbol, symbol);
    controller.dispatch(const BackFromDetailAction());
    expect(controller.state.detailRoute, isNull);
    controller.dispose();
  });
}
