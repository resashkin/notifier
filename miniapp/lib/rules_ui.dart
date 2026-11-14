/// The rules list and the rule editor (with a live preview on recent notifications).
library;

import 'package:flutter/material.dart';

import 'i18n.dart';
import 'model.dart';
import 'rules.dart';
import 'telegram.dart';
import 'widgets.dart';

/// The name, or a summary of the conditions: Messages · «900» · “sale”. Same as the phone's Rule.label.
String ruleLabel(Rule r, PhoneState state) {
  if (r.name.trim().isNotEmpty) return r.name;
  final parts = [
    if (r.apps.isNotEmpty) r.apps.map(state.appLabel).join(', '),
    if (r.chats.isNotEmpty) r.chats.map((c) => '«$c»').join(', '),
    if (r.words.isNotEmpty) r.words.map((w) => '“$w”').join(', '),
    if (r.regex.trim().isNotEmpty) '/${r.regex}/',
  ];
  return parts.isEmpty ? tr('all notifications', 'все уведомления') : parts.join(' · ');
}

String actionText(RuleAction a) => switch (a) {
  RuleAction.drop => tr('Drop', 'Отбросить'),
  RuleAction.digest => tr('Into the digest', 'В дайджест'),
  RuleAction.silent => tr('Send silently', 'Без звука'),
};

String _mapSummary(Rule r) => [
  if (r.cut.isNotEmpty) tr('cut ${r.cut.length}', 'вырезать ${r.cut.length}'),
  if (r.cutRegex.trim().isNotEmpty) tr('cut regex', 'вырезать regex'),
  if (r.links) tr('no links', 'без ссылок'),
  if (r.parse.trim().isNotEmpty) tr('template', 'шаблон'),
].join(', ');

String _kindTitle(RuleKind k) => switch (k) {
  RuleKind.always => tr('Always forward', 'Всегда пересылать'),
  RuleKind.mute => tr('Mute', 'Заглушить'),
  RuleKind.map => tr('Rewrite', 'Переписать'),
};

/// A new rule for [item]: mute this chat in this app (the user picks what to keep).
Rule ruleFromExample(RecentItem item) => Rule(
  kind: RuleKind.mute,
  apps: item.pkg.isEmpty ? const [] : [item.pkg],
  chats: item.title.trim().isEmpty ? const [] : [item.title.trim()],
);

/// What happened (or would happen) to a notification, in a few words.
String outcomeText(RuleAction? action) => switch (action) {
  RuleAction.drop => tr('🔕 dropped', '🔕 отброшено'),
  RuleAction.digest => tr('📋 into the digest', '📋 в дайджест'),
  RuleAction.silent => tr('🤫 sent silently', '🤫 без звука'),
  null => tr('✅ forwarded', '✅ пересылается'),
};

class RulesScreen extends StatefulWidget {
  const RulesScreen({
    super.key,
    required this.state,
    required this.rules,
    required this.onChanged,
    required this.onReturn,
  });

  final PhoneState state;
  final List<Rule> rules;
  final ValueChanged<List<Rule>> onChanged;

  /// Called when coming back from the editor (the Telegram main button belongs to this screen again).
  final VoidCallback onReturn;

  @override
  State<RulesScreen> createState() => _RulesScreenState();
}

class _RulesScreenState extends State<RulesScreen> {
  late List<Rule> _rules = widget.rules;

  void _set(List<Rule> rules) {
    TelegramApp.hapticSelection();
    setState(() => _rules = rules);
    widget.onChanged(rules);
  }

  Future<void> _edit(Rule rule, {RecentItem? example}) async {
    final isNew = !_rules.any((r) => r.id == rule.id);
    final result = await openRuleEditor(context, widget.state, rule, _rules, example: example, isNew: isNew);
    widget.onReturn();
    if (result == null) return;
    if (result.deleted) {
      return _set([
        for (final r in _rules)
          if (r.id != rule.id) r,
      ]);
    }
    final edited = result.rule!;
    _set(isNew ? [..._rules, edited] : [for (final r in _rules) r.id == edited.id ? edited : r]);
  }

  void _moveUp(Rule rule) {
    // Order matters for rewrite rules (the first match applies): swap with the previous rewrite rule.
    final list = [..._rules];
    final i = list.indexWhere((r) => r.id == rule.id);
    final j = list.lastIndexWhere((r) => r.kind == RuleKind.map, i - 1);
    if (i < 0 || j < 0) return;
    list[i] = list[j];
    list[j] = rule;
    _set(list);
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    Widget tile(Rule r, {bool canMoveUp = false}) {
      final subtitle = switch (r.kind) {
        RuleKind.mute => actionText(r.action),
        RuleKind.map => _mapSummary(r),
        RuleKind.always => '',
      };
      return ListTile(
        leading: canMoveUp
            ? IconButton(
                icon: const Icon(Icons.arrow_upward),
                tooltip: tr('Move up', 'Выше'),
                onPressed: () => _moveUp(r),
              )
            : null,
        title: Text(ruleLabel(r, state), maxLines: 2, overflow: TextOverflow.ellipsis),
        subtitle: subtitle.isEmpty ? null : Text(subtitle),
        trailing: Switch(
          value: r.on,
          onChanged: (v) => _set([for (final x in _rules) x.id == r.id ? x.copyWith(on: v) : x]),
        ),
        onTap: () => _edit(r),
      );
    }

    Widget addTile(RuleKind kind) => ListTile(
      leading: const Icon(Icons.add),
      title: Text(tr('Add', 'Добавить')),
      onTap: () => _edit(Rule(kind: kind)),
    );

    final always = _rules.where((r) => r.kind == RuleKind.always).toList();
    final mute = _rules.where((r) => r.kind == RuleKind.mute).toList();
    final map = _rules.where((r) => r.kind == RuleKind.map).toList();

    return Scaffold(
      appBar: AppBar(title: Text(tr('Rules', 'Правила')), automaticallyImplyLeading: !TelegramApp.inTelegram),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
          children: [
            Section(
              title: _kindTitle(RuleKind.always),
              footer: tr(
                'Forwarded even when a mute rule matches: codes, family, work.',
                'Пересылаются, даже если подходит правило «заглушить»: коды, семья, работа.',
              ),
              children: [for (final r in always) tile(r), addTile(RuleKind.always)],
            ),
            Section(
              title: _kindTitle(RuleKind.mute),
              footer: tr(
                'Drop, send silently or keep for the digest. If several match, the strictest wins: drop, then digest, then silent.',
                'Отбросить, прислать без звука или в дайджест. Если подходят несколько — строже всего: отбросить, затем дайджест, затем без звука.',
              ),
              children: [for (final r in mute) tile(r), addTile(RuleKind.mute)],
            ),
            Section(
              title: _kindTitle(RuleKind.map),
              footer: tr(
                'Changes what is sent: cut noise, or turn the text into a short line with a template. Only the first matching rule applies.',
                'Меняет то, что придёт в Telegram: убрать шум или собрать короткую строку по шаблону. Применяется только первое подходящее правило.',
              ),
              children: [
                for (var i = 0; i < map.length; i++) tile(map[i], canMoveUp: i > 0),
                addTile(RuleKind.map),
              ],
            ),
            if (state.recent.isNotEmpty)
              Section(
                title: tr('From a recent notification', 'Из недавнего уведомления'),
                footer: tr(
                  'Tap one to make a rule from it. Or reply /rule to a forwarded message in the chat.',
                  'Нажмите, чтобы сделать из него правило. Или ответьте /rule на пересланное сообщение в чате.',
                ),
                children: [
                  for (final item in state.recent)
                    ListTile(
                      title: Text(
                        '${item.app}${item.title.isEmpty ? '' : ' · ${item.title}'}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        [if (item.status.isNotEmpty) _statusIcon(item.status), item.text].join(' '),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      trailing: Text(clock(item.time), style: TextStyle(color: hintColor(context), fontSize: 12)),
                      onTap: () => _edit(ruleFromExample(item), example: item),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  static String _statusIcon(String status) => switch (status) {
    'drop' => '🔕',
    'digest' => '📋',
    'silent' => '🤫',
    _ => '',
  };
}

/// Result of the editor: the edited rule, or [deleted].
class RuleEditResult {
  const RuleEditResult.saved(Rule this.rule) : deleted = false;

  const RuleEditResult.deleted() : rule = null, deleted = true;

  final Rule? rule;
  final bool deleted;
}

Future<RuleEditResult?> openRuleEditor(
  BuildContext context,
  PhoneState state,
  Rule rule,
  List<Rule> allRules, {
  RecentItem? example,
  required bool isNew,
}) => Navigator.of(context).push<RuleEditResult>(
  MaterialPageRoute(
    builder: (_) => RuleEditor(state: state, initial: rule, allRules: allRules, example: example, isNew: isNew),
  ),
);

class RuleEditor extends StatefulWidget {
  const RuleEditor({
    super.key,
    required this.state,
    required this.initial,
    required this.allRules,
    required this.isNew,
    this.example,
  });

  final PhoneState state;
  final Rule initial;

  /// The other rules, so the preview shows what would really happen.
  final List<Rule> allRules;
  final bool isNew;

  /// The notification this rule is made from, shown first in the preview.
  final RecentItem? example;

  @override
  State<RuleEditor> createState() => _RuleEditorState();
}

class _RuleEditorState extends State<RuleEditor> {
  late Rule _rule = widget.initial;
  late final _name = TextEditingController(text: widget.initial.name);
  late final _regex = TextEditingController(text: widget.initial.regex);
  late final _cutRegex = TextEditingController(text: widget.initial.cutRegex);
  late final _parse = TextEditingController(text: widget.initial.parse);
  late final _out = TextEditingController(text: widget.initial.out);
  late final _outTitle = TextEditingController(text: widget.initial.outTitle);

  RecentItem? get _example => widget.example ?? widget.state.example;

  @override
  void initState() {
    super.initState();
    for (final c in [_name, _regex, _cutRegex, _parse, _out, _outTitle]) {
      c.addListener(_fromFields);
    }
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncMainButton());
  }

  @override
  void dispose() {
    for (final c in [_name, _regex, _cutRegex, _parse, _out, _outTitle]) {
      c.dispose();
    }
    super.dispose();
  }

  void _fromFields() => _update(
    (r) => r.copyWith(
      name: _name.text,
      regex: _regex.text,
      cutRegex: _cutRegex.text,
      parse: _parse.text,
      out: _out.text,
      outTitle: _outTitle.text,
    ),
  );

  void _update(Rule Function(Rule) change) {
    setState(() => _rule = change(_rule));
    _syncMainButton();
  }

  /// Why the rule can't be applied yet, or null.
  String? get _problem {
    final r = _rule;
    if (r.kind != RuleKind.map && !r.hasConditions) {
      return tr(
        'Add at least one condition: an app, a chat, a word or a regex.',
        'Добавьте хотя бы одно условие: приложение, чат, слово или regex.',
      );
    }
    for (final re in [r.regex, r.cutRegex, r.parse]) {
      if (!RuleEngine.isValidRegex(re)) return tr('A regex has an error.', 'В regex ошибка.');
    }
    return null;
  }

  void _syncMainButton() {
    if (!mounted || ModalRoute.of(context)?.isCurrent == false) return;
    TelegramApp.mainButton(
      text: widget.isNew ? tr('Add rule', 'Добавить правило') : tr('Done', 'Готово'),
      enabled: _problem == null,
      onPressed: _apply,
    );
  }

  void _apply() {
    if (_problem != null) return;
    Navigator.of(context).pop(RuleEditResult.saved(_rule));
  }

  List<Rule> get _withThis {
    final others = widget.allRules.where((r) => r.id != _rule.id);
    return widget.isNew ? [...others, _rule] : [for (final r in widget.allRules) r.id == _rule.id ? _rule : r];
  }

  Future<void> _pickApps() async {
    final state = widget.state;
    final known = <String, String>{
      for (final a in state.apps) a.pkg: a.label,
      for (final r in state.recent) r.pkg: r.app,
      if (_example != null) _example!.pkg: _example!.app,
      for (final p in _rule.apps) p: state.appLabel(p),
    }..remove('');
    final selected = {..._rule.apps};
    final result = await showModalBottomSheet<Set<String>>(
      context: context,
      isScrollControlled: true,
      builder: (context) => StatefulBuilder(
        builder: (context, setSheet) => SafeArea(
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(tr('Apps', 'Приложения'), style: Theme.of(context).textTheme.titleMedium),
                ),
                Flexible(
                  child: ListView(
                    shrinkWrap: true,
                    children: [
                      for (final e in (known.entries.toList()..sort((a, b) => a.value.compareTo(b.value))))
                        CheckboxListTile(
                          value: selected.contains(e.key),
                          title: Text(e.value),
                          subtitle: Text(e.key, style: const TextStyle(fontSize: 12)),
                          onChanged: (v) => setSheet(() => v == true ? selected.add(e.key) : selected.remove(e.key)),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: FilledButton(
                    onPressed: () => Navigator.pop(context, selected),
                    child: Text(tr('Done', 'Готово')),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (result != null) _update((r) => r.copyWith(apps: result.toList()));
  }

  @override
  Widget build(BuildContext context) {
    final r = _rule;
    final state = widget.state;
    final problem = _problem;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.isNew ? tr('New rule', 'Новое правило') : tr('Rule', 'Правило')),
        automaticallyImplyLeading: !TelegramApp.inTelegram,
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
          children: [
            SegmentedButton<RuleKind>(
              segments: [for (final k in RuleKind.values) ButtonSegment(value: k, label: Text(_kindTitle(k)))],
              selected: {r.kind},
              showSelectedIcon: false,
              onSelectionChanged: (s) => _update((r) => r.copyWith(kind: s.first)),
            ),
            if (r.kind == RuleKind.mute)
              Section(
                title: tr('Action', 'Действие'),
                children: [
                  RadioGroup<RuleAction>(
                    groupValue: r.action,
                    onChanged: (v) => _update((r) => r.copyWith(action: v)),
                    child: Column(
                      children: [
                        for (final a in [RuleAction.drop, RuleAction.digest, RuleAction.silent])
                          RadioListTile<RuleAction>(
                            value: a,
                            title: Text(actionText(a)),
                            subtitle: Text(switch (a) {
                              RuleAction.drop => tr('Not sent at all', 'Не отправлять совсем'),
                              RuleAction.digest => tr(
                                'Sent together at the digest times',
                                'Придёт вместе с другими в часы дайджеста',
                              ),
                              RuleAction.silent => tr('Sent without a sound', 'Придёт без звука'),
                            }),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            Section(
              title: tr('When', 'Когда'),
              footer: tr(
                'All filled conditions must match; within a list, any entry. Letters case and ё/е don\'t matter.',
                'Должны совпасть все заполненные условия; в списке — любой из вариантов. Регистр и ё/е не важны.',
              ),
              children: [
                ListTile(
                  title: Text(tr('Apps', 'Приложения')),
                  subtitle: Text(r.apps.isEmpty ? tr('any', 'любые') : r.apps.map(state.appLabel).join(', ')),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _pickApps,
                ),
                TagsField(
                  label: tr('Chat or sender (exact)', 'Чат или отправитель (точно)'),
                  hint: '900',
                  values: r.chats,
                  splitOnComma: false,
                  onChanged: (v) => _update((r) => r.copyWith(chats: v)),
                ),
                TagsField(
                  label: tr('Words or phrases (any)', 'Слова или фразы (любое)'),
                  hint: tr('sale, promo', 'акция, скидк'),
                  values: r.words,
                  onChanged: (v) => _update((r) => r.copyWith(words: v)),
                ),
                _field(_regex, tr('Regex', 'Regex'), hint: r'\d{4,6}'),
              ],
            ),
            if (r.kind == RuleKind.map) ..._mapFields(r),
            Section(title: tr('Name', 'Название'), children: [_field(_name, tr('Optional', 'Необязательно'))]),
            SwitchListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 4),
              value: r.on,
              title: Text(tr('Rule is on', 'Правило включено')),
              onChanged: (v) => _update((r) => r.copyWith(on: v)),
            ),
            if (problem != null) Note(problem, warning: true),
            ..._preview(),
            if (!TelegramApp.inTelegram)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: FilledButton(onPressed: problem == null ? _apply : null, child: Text(tr('Done', 'Готово'))),
              ),
            if (!widget.isNew)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: TextButton(
                  style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
                  onPressed: () => Navigator.of(context).pop(const RuleEditResult.deleted()),
                  child: Text(tr('Delete rule', 'Удалить правило')),
                ),
              ),
          ],
        ),
      ),
    );
  }

  List<Widget> _mapFields(Rule r) {
    final groups = RuleEngine.groupNames(r.parse);
    final ex = _example;
    final templateMisses =
        ex != null &&
        r.parse.trim().isNotEmpty &&
        RuleEngine.isValidRegex(r.parse) &&
        !RuleEngine.matches(r.copyWith(apps: const [], chats: const [], words: const [], regex: r.parse), ex.incoming);
    return [
      Section(
        title: tr('Clean up', 'Очистка'),
        children: [
          TagsField(
            label: tr('Cut phrases', 'Вырезать фразы'),
            hint: tr('Unsubscribe', 'ПАО Сбербанк'),
            values: r.cut,
            onChanged: (v) => _update((r) => r.copyWith(cut: v)),
          ),
          _field(_cutRegex, tr('Cut regex', 'Вырезать regex'), hint: r'\s*Отписаться:.*$'),
          SwitchListTile(
            value: r.links,
            title: Text(tr('Remove links', 'Убрать ссылки')),
            onChanged: (v) => _update((r) => r.copyWith(links: v)),
          ),
        ],
      ),
      Section(
        title: tr('Template', 'Шаблон'),
        footer: [
          if (groups.isNotEmpty) tr('Groups: ', 'Группы: ') + groups.map((g) => '{$g}').join(' '),
          tr(
            'Name parts of the text with (?<name>…) and use {name} below; also {title}, {text}, {app}, {1}. If the regex doesn\'t match, the cleaned text is sent.',
            'Отметьте части текста как (?<name>…) и используйте {name} ниже; ещё есть {title}, {text}, {app}, {1}. Если regex не совпал, придёт очищенный текст.',
          ),
          if (templateMisses) tr('⚠️ Doesn\'t match the example.', '⚠️ Не совпадает с примером.'),
        ].join('\n'),
        children: [
          _field(
            _parse,
            tr('Regex for the text', 'Regex для текста'),
            hint: r'Покупка (?<amount>[\d ]+)р (?<shop>.+?) Баланс',
          ),
          _field(_out, tr('Text', 'Текст'), hint: '💳 −{amount} ₽ · {shop}'),
          _field(_outTitle, tr('Title (empty = keep)', 'Заголовок (пусто = как был)'), hint: '{app}'),
        ],
      ),
    ];
  }

  Widget _field(TextEditingController c, String label, {String? hint}) {
    final isRegex = c == _regex || c == _cutRegex || c == _parse;
    final invalid = isRegex && !RuleEngine.isValidRegex(c.text);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: TextField(
        controller: c,
        autocorrect: false,
        enableSuggestions: !isRegex,
        style: isRegex ? const TextStyle(fontFamily: 'monospace') : null,
        decoration: InputDecoration(
          labelText: label,
          hintText: hint,
          isDense: true,
          errorText: invalid ? tr('Not a valid regex', 'Ошибка в regex') : null,
        ),
      ),
    );
  }

  /// The example, then the recent notifications this rule matches, each with what would happen to it.
  List<Widget> _preview() {
    final rules = _withThis;
    final ex = _example;
    final recent = widget.state.recent;
    final matching = [
      for (final item in recent)
        if (_rule.on && (_rule.kind == RuleKind.map || _rule.hasConditions) && RuleEngine.matches(_rule, item.incoming))
          item,
    ];
    return [
      if (ex != null) Section(title: tr('Example', 'Пример'), children: [_previewTile(ex, rules, showMatch: true)]),
      if (recent.isNotEmpty)
        Section(
          title: tr(
            'Matches ${matching.length} of the last ${recent.length}',
            'Совпадает с ${matching.length} из последних ${recent.length}',
          ),
          footer: matching.isEmpty
              ? null
              : tr(
                  'With all your rules applied. The phone reports again after saving.',
                  'С учётом всех правил. После сохранения телефон пришлёт свой отчёт.',
                ),
          children: [for (final item in matching.take(10)) _previewTile(item, rules)],
        ),
    ];
  }

  Widget _previewTile(RecentItem item, List<Rule> rules, {bool showMatch = false}) {
    final v = RuleEngine.evaluate(rules, item.incoming);
    final changed = v.action != RuleAction.drop && (v.title != item.title || v.text != item.text);
    final matches = (_rule.kind == RuleKind.map || _rule.hasConditions) && RuleEngine.matches(_rule, item.incoming);
    final hint = hintColor(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${item.app} · ${clock(item.time)}${showMatch ? ' · ${matches ? tr('rule matches', 'правило подходит') : tr('rule doesn\'t match', 'правило не подходит')}' : ''}',
            style: TextStyle(color: hint, fontSize: 12),
          ),
          if (item.title.isNotEmpty) Text(item.title, style: const TextStyle(fontWeight: FontWeight.w600)),
          Text(item.text, maxLines: changed ? 2 : 4, overflow: TextOverflow.ellipsis),
          const SizedBox(height: 4),
          Text(outcomeText(v.action), style: const TextStyle(fontWeight: FontWeight.w600)),
          if (changed)
            Container(
              margin: const EdgeInsets.only(top: 4),
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.5),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (v.title.isNotEmpty) Text(v.title, style: const TextStyle(fontWeight: FontWeight.w600)),
                  Text(v.text),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
