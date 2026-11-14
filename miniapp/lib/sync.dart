/// The rules sync between the phone and this Mini App, with no server in between.
///
/// The phone is the source of truth. This Mini App keeps a copy of the rules in Telegram CloudStorage
/// (per user and bot) and the phone remembers which revision of them the Mini App confirmed last, so a link
/// only carries the difference ([RulesLink]). When saving, the Mini App stores its new copy first, then sends
/// the changes with `{base: the link's revision, rev: the new one}`; the phone applies them and records that
/// the Mini App now has `rev` (android Prefs.applyMiniAppRules).
library;

import 'dart:convert';

import 'model.dart';
import 'rules.dart';
import 'telegram.dart';

enum RulesStatus {
  /// The phone's app predates rules.
  unsupported,

  /// Loaded: the phone's current rules are known.
  ready,

  /// The link's changes don't apply to the copy stored here (it was lost, or saved from an older link).
  outOfSync,

  /// The phone couldn't fit the rules in the link and there's no matching copy here.
  tooBig,
}

class RulesSyncResult {
  const RulesSyncResult(this.status, [this.rules = const []]);

  final RulesStatus status;

  /// The phone's rules as of the link (when [status] is ready).
  final List<Rule> rules;
}

abstract final class RulesSync {
  static Future<RulesSyncResult> resolve(RulesLink? link) async {
    if (link == null) return const RulesSyncResult(RulesStatus.unsupported);
    if (link.base.isEmpty && !link.tooBig) return RulesSyncResult(RulesStatus.ready, link.diff.applyTo(const []));
    final stored = await RulesStore.load();
    if (stored != null) {
      final (rev, rules) = stored;
      if (rev == link.rev) return RulesSyncResult(RulesStatus.ready, rules);
      if (rev == link.base && !link.tooBig) return RulesSyncResult(RulesStatus.ready, link.diff.applyTo(rules));
    }
    return RulesSyncResult(link.tooBig ? RulesStatus.tooBig : RulesStatus.outOfSync);
  }
}

/// The rules copy in CloudStorage: JSON split into chunks (values are limited to 4096 characters),
/// and `rules_meta` = "revision:chunks", written last so a half-written copy is never read.
abstract final class RulesStore {
  static const _meta = 'rules_meta';
  static const _chunkSize = 3000;

  static String _key(int i) => 'rules_$i';

  static Future<(String, List<Rule>)?> load() async {
    final meta = (await TelegramApp.cloudGet([_meta]))?[_meta] ?? '';
    final parts = meta.split(':');
    final count = parts.length == 2 ? int.tryParse(parts[1]) : null;
    if (count == null || parts[0].isEmpty) return null;
    if (count == 0) return (parts[0], const <Rule>[]);
    final chunks = await TelegramApp.cloudGet([for (var i = 0; i < count; i++) _key(i)]);
    if (chunks == null) return null;
    try {
      final json = [for (var i = 0; i < count; i++) chunks[_key(i)] ?? ''].join();
      return (parts[0], Rule.listFromJson(jsonDecode(json)));
    } on FormatException {
      return null;
    }
  }

  /// Stores [rules] as revision [rev]; false if Telegram didn't confirm every write.
  static Future<bool> save(String rev, List<Rule> rules) async {
    final json = jsonEncode([for (final r in rules) r.toJson()]);
    final chunks = [
      for (var i = 0; i < json.length; i += _chunkSize) json.substring(i, (i + _chunkSize).clamp(0, json.length)),
    ];
    if (!await TelegramApp.cloudSet(_meta, '')) return false;
    for (var i = 0; i < chunks.length; i++) {
      if (!await TelegramApp.cloudSet(_key(i), chunks[i])) return false;
    }
    return TelegramApp.cloudSet(_meta, '$rev:${chunks.length}');
  }
}
