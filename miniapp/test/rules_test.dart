import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:notifier_miniapp/rules.dart';

/// The same cases the phone runs (android RulesSpecTest): both engines must agree.
void main() {
  final spec = jsonDecode(File('../spec/rules-cases.json').readAsStringSync()) as Map<String, dynamic>;

  group('spec/rules-cases.json', () {
    for (final c in (spec['cases'] as List).cast<Map<String, dynamic>>()) {
      test(c['name'], () {
        final rules = Rule.listFromJson(c['rules']);
        final input = c['in'] as Map<String, dynamic>;
        final n = Incoming(
          pkg: input['pkg'] as String,
          title: input['title'] as String,
          text: input['text'] as String,
          app: (input['app'] as String?) ?? '',
        );
        final expect_ = c['expect'] as Map<String, dynamic>;
        final v = RuleEngine.evaluate(rules, n);
        expect(v.action?.key, expect_['action']);
        if (expect_.containsKey('title')) expect(v.title, expect_['title']);
        if (expect_.containsKey('text')) expect(v.text, expect_['text']);
      });
    }
  });

  group('RulesDiff', () {
    Rule r(String id, [String word = 'x']) => Rule(id: id, kind: RuleKind.mute, words: [word]);

    test('round trip: applying the diff gives the new list', () {
      final old = [r('a'), r('b'), r('c')];
      final updated = [r('c'), r('a', 'changed'), r('d')];
      final diff = RulesDiff.between(old, updated);
      expect(diff.del, ['b']);
      expect(diff.up.map((x) => x.id), ['a', 'd']);
      expect(diff.applyTo(old).map((x) => x.toJson()), updated.map((x) => x.toJson()));
    });

    test('no order needed when appending', () {
      final diff = RulesDiff.between([r('a')], [r('a'), r('b')]);
      expect(diff.ord, isNull);
    });

    test('nothing changed', () {
      expect(RulesDiff.between([r('a'), r('b')], [r('a'), r('b')]).isEmpty, isTrue);
    });

    test('survives JSON', () {
      final diff = RulesDiff.between([r('a'), r('b')], [r('b'), r('a')]);
      final back = RulesDiff.fromJson(jsonDecode(jsonEncode(diff.toJson())) as Map<String, dynamic>);
      expect(back.applyTo([r('a'), r('b')]).map((x) => x.id), ['b', 'a']);
    });
  });
}
