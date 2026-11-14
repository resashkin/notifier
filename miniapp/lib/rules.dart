/// Smart mute and mapper rules: the same model and engine as the phone's
/// `android/app/src/main/java/dev/notifier/Rules.kt`, used here to preview a rule while it's edited.
///
/// Both run the cases in `spec/rules-cases.json` (test/rules_test.dart here, RulesSpecTest on Android):
/// change both, and the cases, together.
library;

import 'dart:convert';
import 'dart:math';

enum RuleKind {
  always('always'),
  mute('mute'),
  map('map');

  const RuleKind(this.key);

  final String key;
}

/// [severity]: the strictest matching mute rule decides (drop > digest > silent).
enum RuleAction {
  silent('silent', 1),
  digest('digest', 2),
  drop('drop', 3);

  const RuleAction(this.key, this.severity);

  final String key;
  final int severity;
}

class Rule {
  Rule({
    String? id,
    required this.kind,
    this.on = true,
    this.name = '',
    this.apps = const [],
    this.chats = const [],
    this.words = const [],
    this.regex = '',
    this.action = RuleAction.drop,
    this.cut = const [],
    this.cutRegex = '',
    this.links = false,
    this.parse = '',
    this.out = '',
    this.outTitle = '',
  }) : id = id ?? newId();

  final String id;
  final RuleKind kind;
  final bool on;
  final String name;

  // Conditions: ANDed; a list matches if any of its entries does.
  final List<String> apps;
  final List<String> chats;
  final List<String> words;
  final String regex;

  // Mute
  final RuleAction action;

  // Map
  final List<String> cut;
  final String cutRegex;
  final bool links;
  final String parse;
  final String out;
  final String outTitle;

  static final _random = Random.secure();

  /// 8 hex characters, like the phone's (UUID prefix).
  static String newId() => List.generate(8, (_) => _random.nextInt(16).toRadixString(16)).join();

  bool get hasConditions => apps.isNotEmpty || chats.isNotEmpty || words.isNotEmpty || regex.trim().isNotEmpty;

  /// "Mute this app" as the per-app switches make it: drop, and the app is the only condition.
  bool get isAppMute =>
      kind == RuleKind.mute &&
      action == RuleAction.drop &&
      apps.isNotEmpty &&
      chats.isEmpty &&
      words.isEmpty &&
      regex.trim().isEmpty;

  Rule copyWith({
    RuleKind? kind,
    bool? on,
    String? name,
    List<String>? apps,
    List<String>? chats,
    List<String>? words,
    String? regex,
    RuleAction? action,
    List<String>? cut,
    String? cutRegex,
    bool? links,
    String? parse,
    String? out,
    String? outTitle,
  }) => Rule(
    id: id,
    kind: kind ?? this.kind,
    on: on ?? this.on,
    name: name ?? this.name,
    apps: apps ?? this.apps,
    chats: chats ?? this.chats,
    words: words ?? this.words,
    regex: regex ?? this.regex,
    action: action ?? this.action,
    cut: cut ?? this.cut,
    cutRegex: cutRegex ?? this.cutRegex,
    links: links ?? this.links,
    parse: parse ?? this.parse,
    out: out ?? this.out,
    outTitle: outTitle ?? this.outTitle,
  );

  /// Compact JSON (it travels in links and sendData): defaults are left out. Same keys as the phone.
  Map<String, Object> toJson() => {
    'id': id,
    'k': kind.key,
    if (!on) 'on': false,
    if (name.trim().isNotEmpty) 'n': name,
    if (apps.isNotEmpty) 'apps': apps,
    if (chats.isNotEmpty) 'chats': chats,
    if (words.isNotEmpty) 'words': words,
    if (regex.trim().isNotEmpty) 're': regex,
    if (kind == RuleKind.mute) 'a': action.key,
    if (cut.isNotEmpty) 'cut': cut,
    if (cutRegex.trim().isNotEmpty) 'cutRe': cutRegex,
    if (links) 'links': true,
    if (parse.trim().isNotEmpty) 'parse': parse,
    if (out.trim().isNotEmpty) 'out': out,
    if (outTitle.trim().isNotEmpty) 'outTitle': outTitle,
  };

  /// Null for an unknown kind (a newer phone), so it's skipped rather than misread.
  static Rule? fromJson(Map<String, dynamic> o) {
    final kind = RuleKind.values.where((k) => k.key == o['k']).firstOrNull;
    if (kind == null) return null;
    List<String> strings(String key) => [
      for (final s in (o[key] as List? ?? const []))
        if (s is String && s.trim().isNotEmpty) s.trim(),
    ];
    String str(String key) => o[key] is String ? o[key] as String : '';
    return Rule(
      id: str('id').trim().isEmpty ? null : str('id'),
      kind: kind,
      on: o['on'] != false,
      name: str('n'),
      apps: strings('apps'),
      chats: strings('chats'),
      words: strings('words'),
      regex: str('re'),
      action: RuleAction.values.where((a) => a.key == o['a']).firstOrNull ?? RuleAction.drop,
      cut: strings('cut'),
      cutRegex: str('cutRe'),
      links: o['links'] == true,
      parse: str('parse'),
      out: str('out'),
      outTitle: str('outTitle'),
    );
  }

  static List<Rule> listFromJson(Object? json) => [
    if (json is List)
      for (final o in json)
        if (o is Map<String, dynamic>) ?Rule.fromJson(o),
  ];

  /// Same rule, field for field (what the sync compares).
  bool sameAs(Rule other) => jsonEncode(toJson()) == jsonEncode(other.toJson());
}

/// A notification as the rules see it: [title] is the chat or sender, [text] the message.
class Incoming {
  const Incoming({required this.pkg, required this.title, required this.text, this.app = ''});

  final String pkg;
  final String title;
  final String text;
  final String app;
}

/// What happens to a notification: [action] null = forwarded normally. [title]/[text] after the mapper.
class Verdict {
  const Verdict(this.action, this.title, this.text, {this.mute, this.exception, this.map});

  final RuleAction? action;
  final String title;
  final String text;
  final Rule? mute;
  final Rule? exception;
  final Rule? map;
}

abstract final class RuleEngine {
  /// Compiled patterns by source; [_invalid] = doesn't compile (such a condition never matches).
  static final _patterns = <String, RegExp>{};
  static final _invalid = RegExp(r'(?!)');

  static final _links = _pattern(r'(?:https?://|www\.)\S+|\b(?:[a-z0-9-]+\.)+[a-z]{2,}/\S*');
  static final _placeholder = RegExp(r'\{([A-Za-z][A-Za-z0-9]*|\d+)\}');
  static final _spaces = RegExp('[ \\t\u00A0]+');
  static final _spaceBeforePunct = RegExp(r' +([,.!?:;])');

  /// Order: exceptions → mute (on the original text) → mapper (first matching map rule only).
  static Verdict evaluate(List<Rule> rules, Incoming n) {
    final active = rules.where((r) => r.on).toList();
    final exception = active.where((r) => r.kind == RuleKind.always && r.hasConditions && matches(r, n)).firstOrNull;
    Rule? mute;
    if (exception == null) {
      for (final r in active) {
        if (r.kind == RuleKind.mute && r.hasConditions && matches(r, n)) {
          if (mute == null || r.action.severity > mute.action.severity) mute = r;
        }
      }
    }
    if (mute?.action == RuleAction.drop) return Verdict(RuleAction.drop, n.title, n.text, mute: mute);
    final map = active.where((r) => r.kind == RuleKind.map && matches(r, n)).firstOrNull;
    final (title, text) = map == null ? (n.title, n.text) : apply(map, n);
    return Verdict(mute?.action, title, text, mute: mute, exception: exception, map: map);
  }

  static bool matches(Rule rule, Incoming n) {
    if (rule.apps.isNotEmpty && !rule.apps.contains(n.pkg)) return false;
    if (rule.chats.isNotEmpty && !rule.chats.any((c) => _fold(c.trim()) == _fold(n.title.trim()))) return false;
    final all = '${n.title}\n${n.text}';
    if (rule.words.isNotEmpty) {
      final haystack = _fold(all);
      if (!rule.words.any((w) => w.trim().isNotEmpty && haystack.contains(_fold(w.trim())))) return false;
    }
    if (rule.regex.trim().isNotEmpty && !_pattern(rule.regex).hasMatch(all)) return false;
    return true;
  }

  /// The map rule's result: (title, text). A template whose regex doesn't match leaves the cleaned original.
  static (String, String) apply(Rule rule, Incoming n) {
    var text = n.text;
    for (final phrase in rule.cut) {
      if (phrase.trim().isNotEmpty) text = text.replaceAll(_pattern(RegExp.escape(phrase.trim())), '');
    }
    if (rule.cutRegex.trim().isNotEmpty) text = text.replaceAll(_pattern(rule.cutRegex), '');
    if (rule.links) text = text.replaceAll(_links, '');
    text = tidy(text);

    var title = n.title;
    if (rule.parse.trim().isNotEmpty) {
      final p = _pattern(rule.parse);
      final m = p.firstMatch(n.text) ?? p.firstMatch('${n.title}\n${n.text}');
      if (m != null) {
        final cleaned = text;
        text = tidy(_fill(rule.out.trim().isEmpty ? '{text}' : rule.out, m, n, cleaned));
        if (rule.outTitle.trim().isNotEmpty) title = tidy(_fill(rule.outTitle, m, n, cleaned));
      }
    }
    return (title, text);
  }

  /// Whether [source] compiles (for the editor). Note: this checks JavaScript regex syntax; the phone uses
  /// Java's, which accepts a few more constructs. Common patterns behave the same in both.
  static bool isValidRegex(String source) => source.trim().isEmpty || !identical(_pattern(source), _invalid);

  /// Named groups of [source], for the template hint.
  static List<String> groupNames(String source) {
    final p = _pattern(source);
    if (identical(p, _invalid)) return const [];
    return [for (final m in RegExp(r'\(\?<([A-Za-z][A-Za-z0-9]*)>').allMatches(source)) m.group(1)!];
  }

  /// {name} = a named group, {1} = a numbered one; {title}, {text} (cleaned) and {app} are always there.
  static String _fill(String template, RegExpMatch m, Incoming n, String cleaned) =>
      template.replaceAllMapped(_placeholder, (p) {
        final key = p.group(1)!;
        String? value;
        final number = int.tryParse(key);
        if (number != null) {
          value = number <= m.groupCount ? m.group(number) : null;
        } else if (m.groupNames.contains(key)) {
          value = m.namedGroup(key);
        } else {
          value = switch (key) {
            'title' => n.title,
            'text' => cleaned,
            'app' => n.app,
            _ => null,
          };
        }
        return value?.trim() ?? '';
      });

  /// Single spaces, no space before punctuation, no leading/trailing blank lines, at most one blank line.
  static String tidy(String s) {
    final out = <String>[];
    for (final raw in s.split('\n')) {
      final line = raw.replaceAll(_spaces, ' ').replaceAllMapped(_spaceBeforePunct, (m) => m.group(1)!).trim();
      if (line.isEmpty && (out.isEmpty || out.last.isEmpty)) continue;
      out.add(line);
    }
    while (out.isNotEmpty && out.last.isEmpty) {
      out.removeLast();
    }
    return out.join('\n');
  }

  /// Case-insensitive comparison that also treats ё as е.
  static String _fold(String s) => s.toLowerCase().replaceAll('ё', 'е');

  static RegExp _pattern(String source) => _patterns.putIfAbsent(source, () {
    try {
      return RegExp(source, caseSensitive: false, unicode: true);
    } on FormatException {
      return _invalid;
    }
  });
}

/// Changes from one rule list to another, for the sync with the phone: [up] = new or changed rules,
/// [del] = removed ids, [ord] = the full id order, only when applying [up]/[del] alone wouldn't give it.
/// Same as the phone's RulesDiff.
class RulesDiff {
  const RulesDiff(this.up, this.del, this.ord);

  final List<Rule> up;
  final List<String> del;
  final List<String>? ord;

  bool get isEmpty => up.isEmpty && del.isEmpty && ord == null;

  /// How many rules it touches (for "Save N changes").
  int get size => up.length + del.length + (ord != null && up.isEmpty && del.isEmpty ? 1 : 0);

  Map<String, Object> toJson() => {
    'up': [for (final r in up) r.toJson()],
    'del': del,
    'ord': ?ord,
  };

  factory RulesDiff.fromJson(Map<String, dynamic> o) => RulesDiff(Rule.listFromJson(o['up']), [
    for (final id in (o['del'] as List? ?? const [])) '$id',
  ], o['ord'] is List ? [for (final id in o['ord'] as List) '$id'] : null);

  List<Rule> applyTo(List<Rule> base) {
    final changed = {for (final r in up) r.id: r};
    final removed = del.toSet();
    final out = [
      for (final r in base)
        if (!removed.contains(r.id)) changed[r.id] ?? r,
    ];
    final have = out.map((r) => r.id).toSet();
    out.addAll(up.where((r) => !have.contains(r.id)));
    final order = ord;
    if (order != null) {
      final index = {for (var i = 0; i < order.length; i++) order[i]: i};
      // Stable: rules missing from ord keep their order, last. (List.sort isn't guaranteed stable.)
      final positions = {for (var i = 0; i < out.length; i++) out[i].id: i};
      out.sort((a, b) {
        final c = (index[a.id] ?? 1 << 30).compareTo(index[b.id] ?? 1 << 30);
        return c != 0 ? c : positions[a.id]!.compareTo(positions[b.id]!);
      });
    }
    return out;
  }

  static RulesDiff between(List<Rule> old, List<Rule> updated) {
    final before = {for (final r in old) r.id: r};
    final newIds = updated.map((r) => r.id).toSet();
    final up = [
      for (final r in updated)
        if (!(before[r.id]?.sameAs(r) ?? false)) r,
    ];
    final del = [
      for (final r in old)
        if (!newIds.contains(r.id)) r.id,
    ];
    final plain = RulesDiff(up, del, null);
    final order = updated.map((r) => r.id).toList();
    final applied = plain.applyTo(old).map((r) => r.id).toList();
    return _sameList(applied, order) ? plain : RulesDiff(up, del, order);
  }

  static bool _sameList(List<String> a, List<String> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
