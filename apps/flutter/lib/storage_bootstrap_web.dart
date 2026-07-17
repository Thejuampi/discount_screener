import 'package:ds_data/ds_data.dart';

/// Web: no filesystem; session-scoped memory store.
Future<StateStore> openStateStore() async => MemoryStateStore();
