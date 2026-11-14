/// Ready-made rules for common noisy notifications (assets/presets.json), offered on the rules screen.
/// The phone checks the same file in its tests (android PresetsTest), so both engines agree on them.
library;

import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;

import 'i18n.dart';
import 'rules.dart';

class Preset {
  Preset._(this.id, this._en, this._ru, this._aboutEn, this._aboutRu, this._rule);

  static Preset? fromJson(Map<String, dynamic> j) {
    final rule = j['rule'];
    if (rule is! Map<String, dynamic> || Rule.fromJson(rule) == null) return null;
    String s(String key) => j[key] is String ? j[key] as String : '';
    return Preset._(s('id'), s('en'), s('ru'), s('about_en'), s('about_ru'), rule);
  }

  final String id;
  final String _en;
  final String _ru;
  final String _aboutEn;
  final String _aboutRu;
  final Map<String, dynamic> _rule;

  String get name => tr(_en, _ru);

  String get about => tr(_aboutEn, _aboutRu);

  /// A rule named after this preset (in either language) means it was added already.
  bool isIn(List<Rule> rules) => rules.any((r) => r.name == _en || r.name == _ru);

  /// A new rule (with a fresh id) from this preset, named after it.
  Rule newRule() => Rule.fromJson({..._rule, 'n': name})!;
}

abstract final class Presets {
  static Future<List<Preset>>? _all;

  static Future<List<Preset>> load() =>
      _all ??= rootBundle.loadString('assets/presets.json').then(parse).catchError((Object _) => <Preset>[]);

  static List<Preset> parse(String json) {
    final list = (jsonDecode(json) as Map<String, dynamic>)['presets'];
    return [
      if (list is List)
        for (final p in list.whereType<Map<String, dynamic>>()) ?Preset.fromJson(p),
    ];
  }
}
