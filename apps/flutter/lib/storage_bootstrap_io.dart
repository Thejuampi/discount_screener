import 'dart:io';

import 'package:ds_data/ds_data.dart';
import 'package:ds_data/state_store_io.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// Native platforms: JSON files under application documents.
Future<StateStore> openStateStore() async {
  try {
    final docs = await getApplicationDocumentsDirectory();
    final dir = Directory(p.join(docs.path, 'discount_screener'));
    return JsonStateStore(dir);
  } catch (_) {
    final dir = Directory(
      p.join(Directory.systemTemp.path, 'discount_screener_state'),
    );
    return JsonStateStore(dir);
  }
}
