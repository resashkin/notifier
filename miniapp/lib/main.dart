import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import 'i18n.dart';
import 'model.dart';
import 'rules.dart';
import 'rules_ui.dart';
import 'sync.dart';
import 'telegram.dart';
import 'widgets.dart';

void main() {
  // Don't let Flutter rewrite the URL: Telegram keeps its launch data in the hash.
  setUrlStrategy(null);
  runApp(const NotifierMiniApp());
  WidgetsBinding.instance.addPostFrameCallback((_) => TelegramApp.ready());
}

class NotifierMiniApp extends StatelessWidget {
  const NotifierMiniApp({super.key});

  @override
  Widget build(BuildContext context) {
    final tg = TelegramApp.theme();
    return MaterialApp(
      title: 'Notifier',
      debugShowCheckedModeBanner: false,
      theme: tg == null ? ThemeData(colorSchemeSeed: const Color(0xFF2AABEE)) : _themeFrom(tg),
      darkTheme: tg == null
          ? ThemeData(colorSchemeSeed: const Color(0xFF2AABEE), brightness: Brightness.dark)
          : _themeFrom(tg),
      navigatorObservers: [_TelegramBackButton()],
      home: const SettingsScreen(),
    );
  }

  /// Telegram's settings look: page in secondary background, sections in section background.
  static ThemeData _themeFrom(TelegramTheme tg) {
    final brightness = tg.isDark ? Brightness.dark : Brightness.light;
    final seed = ColorScheme.fromSeed(seedColor: tg.button ?? const Color(0xFF2AABEE), brightness: brightness);
    final scheme = seed.copyWith(
      primary: tg.button,
      onPrimary: tg.buttonText,
      surface: tg.sectionBg ?? tg.bg,
      onSurface: tg.text,
      onSurfaceVariant: tg.subtitle ?? tg.hint,
      error: tg.destructive,
    );
    final page = tg.secondaryBg ?? tg.bg;
    return ThemeData(
      colorScheme: scheme,
      scaffoldBackgroundColor: page,
      appBarTheme: AppBarTheme(backgroundColor: page, foregroundColor: tg.text, elevation: 0),
      extensions: [SectionColors(header: tg.sectionHeader ?? tg.hint, hint: tg.hint)],
    );
  }
}

/// Shows Telegram's header back arrow while a screen (rules, rule editor) is open on top of the main one.
class _TelegramBackButton extends NavigatorObserver {
  void _sync() {
    final nav = navigator;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      TelegramApp.backButton(nav != null && nav.canPop() ? () => nav.maybePop() : null);
    });
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) => _sync();

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) => _sync();

  @override
  void didRemove(Route<dynamic> route, Route<dynamic>? previousRoute) => _sync();
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

/// Pause choice in the UI: keep as is, resume now, or pause for N minutes.
sealed class _Pause {
  const _Pause();
}

class _KeepPause extends _Pause {
  const _KeepPause();
}

class _Resume extends _Pause {
  const _Resume();
}

class _PauseFor extends _Pause {
  const _PauseFor(this.minutes);

  final int minutes;

  @override
  bool operator ==(Object other) => other is _PauseFor && other.minutes == minutes;

  @override
  int get hashCode => minutes.hashCode;
}

class _SettingsScreenState extends State<SettingsScreen> {
  PhoneState? _state;
  String? _error;
  bool _demo = false;

  /// Phones without rules: pkg -> forwarding on.
  late Map<String, bool> _forward;
  late Options _options;
  _Pause _pause = const _KeepPause();
  String _query = '';

  /// Null while the rules copy is loading.
  RulesStatus? _rulesStatus;

  /// The phone's rules as of the link, and the edited ones.
  List<Rule> _linkRules = const [];
  List<Rule> _rules = const [];

  /// Edited digest times ("HH:MM"); null when the phone has no digest.
  List<String>? _digest;
  bool _sending = false;

  @override
  void initState() {
    super.initState();
    final encoded = Uri.base.queryParameters['s'];
    try {
      if (encoded != null && encoded.isNotEmpty) {
        _state = PhoneState.decode(encoded);
      } else if (!TelegramApp.inTelegram) {
        _state = PhoneState.demo();
        _demo = true;
      }
    } on Object catch (e) {
      _error = '$e';
    }
    setLanguage(telegram: TelegramApp.languageCode, phone: _state?.lang);
    if (_state == null) {
      _error = _error == null
          ? tr(
              'Open settings from the bot: send /settings and tap ⚙️ Settings.',
              'Откройте настройки из бота: отправьте /settings и нажмите ⚙️ Настройки.',
            )
          : tr(
              'Could not read the settings link ($_error).\nSend /settings to the bot to get a fresh one.',
              'Не удалось прочитать ссылку настроек ($_error).\nОтправьте боту /settings, чтобы получить новую.',
            );
    }
    final state = _state;
    if (state != null) {
      _forward = {for (final a in state.apps) a.pkg: !a.muted};
      _options = state.options;
      _digest = state.digestTimes == null ? null : [...state.digestTimes!];
      _loadRules(state);
    }
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncMainButton());
  }

  Future<void> _loadRules(PhoneState state) async {
    final result = await RulesSync.resolve(state.rules);
    if (!mounted) return;
    setState(() {
      _rulesStatus = result.status;
      _linkRules = result.rules;
      _rules = result.rules;
    });
    _syncMainButton();
    if (result.status != RulesStatus.ready) return;
    // Opened from /rule (a reply to a notification) or /rules.
    if (state.open == 'rule' && state.example != null) {
      final rule = ruleFromExample(state.example!);
      await _openRules();
      if (!mounted) return;
      final edited = await openRuleEditor(context, state, rule, _rules, example: state.example, isNew: true);
      _syncMainButton();
      if (edited?.rule != null) _update(() => _rules = [..._rules, edited!.rule!]);
    } else if (state.open == 'rules') {
      await _openRules(wait: false);
    }
  }

  bool get _hasRules => _rulesStatus == RulesStatus.ready;

  Changes _changes() {
    final state = _state!;
    final options = <String, Object>{};
    final before = state.options.toJson();
    _options.toJson().forEach((k, v) {
      if (before[k] != v) options[k] = v;
    });
    final legacy = _rulesStatus == RulesStatus.unsupported;
    final digest = _digest;
    final digestChanged = digest != null && digest.join(',') != (state.digestTimes ?? const []).join(',');
    return Changes(
      mute: [
        if (legacy)
          for (final a in state.apps)
            if (!a.muted && _forward[a.pkg] == false) a.pkg,
      ],
      unmute: [
        if (legacy)
          for (final a in state.apps)
            if (a.muted && _forward[a.pkg] == true) a.pkg,
      ],
      options: options,
      pauseMinutes: switch (_pause) {
        _PauseFor(:final minutes) => minutes,
        _ => null,
      },
      resume: _pause is _Resume,
      rules: _hasRules ? RulesDiff.between(_linkRules, _rules) : null,
      digest: digestChanged ? digest : null,
    );
  }

  void _update(VoidCallback change) {
    TelegramApp.hapticSelection();
    setState(change);
    _syncMainButton();
  }

  void _syncMainButton() {
    if (!mounted || _state == null || _sending) return;
    if (ModalRoute.of(context)?.isCurrent == false && !_rulesOpen) return;
    final n = _changes().count;
    TelegramApp.mainButton(
      text: n == 0 ? tr('No changes', 'Нет изменений') : tr('Save ${changesCount(n)}', 'Сохранить: ${changesCount(n)}'),
      enabled: n > 0,
      onPressed: _save,
    );
  }

  bool _rulesOpen = false;

  Future<void> _openRules({bool wait = true}) async {
    final state = _state!;
    _rulesOpen = true;
    final route = Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => RulesScreen(
          state: state,
          rules: _rules,
          onChanged: (rules) => _update(() => _rules = rules),
          onReturn: _syncMainButton,
        ),
      ),
    );
    if (wait) {
      // Opened to add a rule from an example: the editor goes on top right away.
      _rulesOpen = true;
      route.then((_) {
        _rulesOpen = false;
        _syncMainButton();
      });
      return;
    }
    await route;
    _rulesOpen = false;
    _syncMainButton();
  }

  Future<void> _save() async {
    final changes = _changes();
    if (changes.isEmpty || _sending) return;
    final state = _state!;
    Map<String, String>? sync;
    if (_hasRules) {
      final link = state.rules!;
      sync = {'base': link.rev, 'rev': (changes.rules?.isEmpty ?? true) ? link.rev : Rule.newId()};
    }
    final json = changes.toJson(rulesSync: sync);
    if (utf8.encode(json).length > 4096) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            tr(
              'Too many changes for one save (Telegram passes 4 KB). Undo some, save, then make the rest.',
              'Слишком много изменений за раз (Telegram передаёт 4 КБ). Отмените часть, сохраните, потом остальное.',
            ),
          ),
        ),
      );
      return;
    }
    if (!TelegramApp.inTelegram) {
      showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(tr('Would send to the bot', 'Будет отправлено боту')),
          content: SelectableText(json),
          actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('OK'))],
        ),
      );
      return;
    }
    _sending = true;
    TelegramApp.mainButtonProgress();
    // The copy first: once the phone gets the changes it counts on this Mini App having them.
    if (sync != null) await RulesStore.save(sync['rev']!, _rules);
    TelegramApp.sendData(json); // Telegram closes the Mini App after this
  }

  void _reloadRules() {
    if (TelegramApp.inTelegram) TelegramApp.sendData(jsonEncode({'v': 1, 'rules_full': true}));
  }

  /// App switches with rules: off = an app-only drop rule.
  bool _appForwarded(String pkg) =>
      _hasRules ? !_rules.any((r) => r.on && r.isAppMute && r.apps.contains(pkg)) : _forward[pkg] ?? true;

  void _setAppForwarded(String pkg, bool forward) => _update(() {
    if (!_hasRules) {
      _forward[pkg] = forward;
      return;
    }
    if (forward) {
      _rules = [
        for (final r in _rules)
          if (!(r.on && r.isAppMute && r.apps.contains(pkg)))
            r
          else if (r.apps.length > 1)
            r.copyWith(apps: [...r.apps]..remove(pkg)),
      ];
    } else {
      // The rule from the link, back in its place, if it was there (so unmuting and muting again isn't a change).
      final previous = _linkRules
          .where((r) => r.on && r.isAppMute && r.apps.length == 1 && r.apps.first == pkg)
          .firstOrNull;
      if (previous != null && !_rules.any((r) => r.id == previous.id)) {
        _rules = [..._rules]..insert(_linkRules.indexOf(previous).clamp(0, _rules.length), previous);
      } else {
        _rules = [
          ..._rules,
          Rule(kind: RuleKind.mute, apps: [pkg]),
        ];
      }
    }
  });

  /// Rules other than the app switch that mention [pkg].
  bool _partlyMuted(String pkg) =>
      _hasRules && _rules.any((r) => r.on && r.kind == RuleKind.mute && !r.isAppMute && r.apps.contains(pkg));

  @override
  Widget build(BuildContext context) {
    final state = _state;
    if (state == null) {
      return Scaffold(
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(_error ?? '', textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodyLarge),
          ),
        ),
      );
    }

    final apps = state.apps
        .where((a) => _query.isEmpty || '${a.label} ${a.pkg}'.toLowerCase().contains(_query.toLowerCase()))
        .toList();
    final changeCount = _changes().count;

    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
          children: [
            if (_demo)
              Note(
                tr(
                  'Demo data - open this from the bot with /settings to manage your phone.',
                  'Демо-данные — откройте из бота командой /settings, чтобы управлять телефоном.',
                ),
              ),
            _statusHeader(context, state),
            if (state.health case final health?) _HealthSection(health, lowBattery: state.options.lowBatteryLevel),
            Section(title: tr('Forwarding', 'Пересылка'), children: [_pauseChips(context, state)]),
            _rulesSection(state),
            if (_digest != null) _digestSection(state),
            Section(
              title: tr('Apps', 'Приложения'),
              footer: state.apps.isEmpty
                  ? tr(
                      'No notifications seen yet. Apps appear here after they post a notification.',
                      'Уведомлений ещё не было. Приложения появятся здесь, когда пришлют уведомление.',
                    )
                  : tr(
                      'Off = all its notifications are dropped. For finer control use rules.',
                      'Выкл. — все уведомления приложения отбрасываются. Тоньше — через правила.',
                    ),
              children: [
                if (state.apps.length > 8)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
                    child: TextField(
                      decoration: InputDecoration(
                        prefixIcon: const Icon(Icons.search),
                        hintText: tr('Search apps', 'Поиск приложений'),
                        isDense: true,
                        border: const OutlineInputBorder(),
                      ),
                      onChanged: (v) => setState(() => _query = v),
                    ),
                  ),
                for (final app in apps)
                  SwitchListTile(
                    value: _appForwarded(app.pkg),
                    onChanged:
                        _rulesStatus == null ||
                            _rulesStatus == RulesStatus.outOfSync ||
                            _rulesStatus == RulesStatus.tooBig
                        ? null
                        : (v) => _setAppForwarded(app.pkg, v),
                    title: Text(app.label),
                    subtitle: Text(
                      [
                        '${notificationsCount(app.count)} · ${ago(app.lastSeen)}',
                        if (_partlyMuted(app.pkg)) tr('some muted by rules', 'часть заглушена правилами'),
                      ].join(' · '),
                    ),
                  ),
              ],
            ),
            Section(
              title: tr('Options', 'Параметры'),
              children: [
                SwitchListTile(
                  value: _options.skipOngoing,
                  onChanged: (v) => _update(() => _options = _options.copyWith(skipOngoing: v)),
                  title: Text(tr('Skip ongoing notifications', 'Пропускать постоянные уведомления')),
                  subtitle: Text(tr('Music, downloads, navigation', 'Музыка, загрузки, навигация')),
                ),
                SwitchListTile(
                  value: _options.skipGroupSummary,
                  onChanged: (v) => _update(() => _options = _options.copyWith(skipGroupSummary: v)),
                  title: Text(tr('Skip group summaries', 'Пропускать сводки групп')),
                  subtitle: Text(tr('"5 new messages"', '«5 новых сообщений»')),
                ),
                SwitchListTile(
                  value: _options.showApp,
                  onChanged: (v) => _update(() => _options = _options.copyWith(showApp: v)),
                  title: Text(tr('Show app name', 'Показывать название приложения')),
                ),
                SwitchListTile(
                  value: _options.silent,
                  onChanged: (v) => _update(() => _options = _options.copyWith(silent: v)),
                  title: Text(tr('Send silently', 'Присылать без звука')),
                  subtitle: Text(
                    tr('No Telegram sound for forwarded messages', 'Все пересланные — без звука в Telegram'),
                  ),
                ),
              ],
            ),
            Section(
              title: tr('Battery', 'Батарея'),
              footer: tr(
                'Sent once per discharge. An unplugged charger is always reported.',
                'Один раз за разряд. Об отключении зарядки сообщается всегда.',
              ),
              children: [
                SwitchListTile(
                  value: _options.lowBattery,
                  onChanged: (v) => _update(() => _options = _options.copyWith(lowBattery: v)),
                  title: Text(tr('Warn when the battery is low', 'Предупреждать о низком заряде')),
                  subtitle: Text(
                    tr('At ${_options.lowBatteryLevel} % or below', 'При ${_options.lowBatteryLevel} % и ниже'),
                  ),
                ),
                Slider(
                  value: _options.lowBatteryLevel.toDouble(),
                  min: Options.lowBatteryMin.toDouble(),
                  max: Options.lowBatteryMax.toDouble(),
                  divisions: Options.lowBatteryMax - Options.lowBatteryMin,
                  label: '${_options.lowBatteryLevel} %',
                  onChanged: _options.lowBattery
                      ? (v) => _update(() => _options = _options.copyWith(lowBatteryLevel: v.round()))
                      : null,
                ),
              ],
            ),
            if (!TelegramApp.inTelegram)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: FilledButton(
                  onPressed: changeCount == 0 ? null : _save,
                  child: Text(
                    changeCount == 0
                        ? tr('No changes', 'Нет изменений')
                        : tr('Save ${changesCount(changeCount)}', 'Сохранить: ${changesCount(changeCount)}'),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _rulesSection(PhoneState state) {
    final status = _rulesStatus;
    final title = tr('Rules', 'Правила');
    switch (status) {
      case null:
        return Section(
          title: title,
          children: [
            ListTile(
              leading: const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2)),
              title: Text(tr('Loading rules…', 'Загружаю правила…')),
            ),
          ],
        );
      case RulesStatus.unsupported:
        return Note(
          tr(
            'Update the Notifier app on the phone (send /update) to get mute rules, exceptions, rewriting and the digest.',
            'Обновите Notifier на телефоне (отправьте /update), чтобы появились правила, исключения, переписывание и дайджест.',
          ),
        );
      case RulesStatus.tooBig:
        return Note(
          tr(
            'The rules are too many to send in a link. Edit them in the Notifier app on the phone.',
            'Правил слишком много, чтобы передать их в ссылке. Измените их в приложении Notifier на телефоне.',
          ),
          warning: true,
        );
      case RulesStatus.outOfSync:
        return Note(
          tr(
            'The rules changed on the phone in a way this copy can\'t follow. Reload them: the Mini App closes, then open ⚙️ Settings again.',
            'Правила на телефоне изменились так, что эту копию не обновить. Загрузите их заново: Mini App закроется, затем снова откройте ⚙️ Настройки.',
          ),
          warning: true,
          action: tr('Reload rules', 'Загрузить правила'),
          onAction: _reloadRules,
        );
      case RulesStatus.ready:
        final on = _rules.where((r) => r.on);
        final counts = [
          if (on.any((r) => r.kind == RuleKind.mute))
            tr(
              'mute ${on.where((r) => r.kind == RuleKind.mute).length}',
              'заглушить ${on.where((r) => r.kind == RuleKind.mute).length}',
            ),
          if (on.any((r) => r.kind == RuleKind.always))
            tr(
              'always ${on.where((r) => r.kind == RuleKind.always).length}',
              'всегда ${on.where((r) => r.kind == RuleKind.always).length}',
            ),
          if (on.any((r) => r.kind == RuleKind.map))
            tr(
              'rewrite ${on.where((r) => r.kind == RuleKind.map).length}',
              'переписать ${on.where((r) => r.kind == RuleKind.map).length}',
            ),
        ];
        return Section(
          title: title,
          footer: tr(
            'Mute by app, chat, words or regex; always forward what matters; rewrite noisy texts.',
            'Глушите по приложению, чату, словам или regex; важное — всегда пересылать; шумные тексты — переписать.',
          ),
          children: [
            ListTile(
              leading: const Icon(Icons.rule),
              title: Text(_rules.isEmpty ? tr('No rules yet', 'Правил пока нет') : rulesCount(_rules.length)),
              subtitle: counts.isEmpty ? null : Text(counts.join(' · ')),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openRules(wait: false),
            ),
          ],
        );
    }
  }

  Widget _digestSection(PhoneState state) {
    final times = _digest!;
    return Section(
      title: tr('Digest', 'Дайджест'),
      footer: [
        if (state.digestWaiting > 0)
          tr(
            '${notificationsCount(state.digestWaiting)} waiting.',
            'Ждут: ${notificationsCount(state.digestWaiting)}.',
          ),
        times.isEmpty
            ? tr(
                'Off: notifications a rule sends to the digest wait until you add a time or send /digest.',
                'Выключен: уведомления для дайджеста ждут, пока не добавите время или не отправите /digest.',
              )
            : tr(
                'Notifications a rule sends to the digest arrive together at these times. Nothing is sent when nothing is waiting.',
                'Уведомления, которые правило отправляет в дайджест, приходят вместе в это время. Если ничего нет — ничего не придёт.',
              ),
      ].join(' '),
      children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final t in times)
                InputChip(label: Text(t), onDeleted: () => _update(() => _digest = [...times]..remove(t))),
              ActionChip(
                avatar: const Icon(Icons.add, size: 18),
                label: Text(tr('Add time', 'Добавить время')),
                onPressed: _addDigestTime,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _addDigestTime() async {
    final picked = await showTimePicker(
      context: context,
      initialTime: const TimeOfDay(hour: 12, minute: 0),
      builder: (context, child) =>
          MediaQuery(data: MediaQuery.of(context).copyWith(alwaysUse24HourFormat: true), child: child!),
    );
    if (picked == null) return;
    final value = '${picked.hour.toString().padLeft(2, '0')}:${picked.minute.toString().padLeft(2, '0')}';
    _update(() => _digest = ({..._digest!, value}.toList()..sort()));
  }

  Widget _statusHeader(BuildContext context, PhoneState state) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 4, 4, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(state.device, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 2),
          Text(
            tr(
              'Settings as of ${clock(state.generatedAt)} (${ago(state.generatedAt)})',
              'Настройки на ${clock(state.generatedAt)} (${ago(state.generatedAt)})',
            ),
            style: TextStyle(color: hintColor(context), fontSize: 13),
          ),
        ],
      ),
    );
  }

  Widget _pauseChips(BuildContext context, PhoneState state) {
    const durations = [30, 60, 240, 480, 1440];
    final selected = _pause;
    return Padding(
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            state.isPaused
                ? tr('⏸ Paused until ${clock(state.pausedUntil)}', '⏸ Пауза до ${clock(state.pausedUntil)}')
                : tr('▶️ Forwarding is on', '▶️ Пересылка включена'),
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              ChoiceChip(
                label: Text(state.isPaused ? tr('Keep paused', 'Оставить паузу') : tr('On', 'Вкл.')),
                selected: selected is _KeepPause,
                onSelected: (_) => _update(() => _pause = const _KeepPause()),
              ),
              if (state.isPaused)
                ChoiceChip(
                  label: Text(tr('Resume now', 'Продолжить')),
                  selected: selected is _Resume,
                  onSelected: (_) => _update(() => _pause = const _Resume()),
                ),
              for (final m in durations)
                ChoiceChip(
                  label: Text(tr('Pause ${_duration(m)}', 'Пауза ${_duration(m)}')),
                  selected: selected == _PauseFor(m),
                  onSelected: (_) => _update(() => _pause = _PauseFor(m)),
                ),
            ],
          ),
        ],
      ),
    );
  }

  static String _duration(int minutes) =>
      minutes < 60 ? tr('$minutes min', '$minutes мин') : tr('${minutes ~/ 60} h', '${minutes ~/ 60} ч');
}

/// Battery, Wi-Fi, mobile network and internet as reported by the phone when the link was made.
class _HealthSection extends StatelessWidget {
  const _HealthSection(this.health, {required this.lowBattery});

  final PhoneHealth health;

  /// Battery level (%) highlighted as low: the warning level chosen in the Battery section.
  final int lowBattery;

  /// Values worth a warning color.
  static const _hotC = 45.0;

  static String _status(String s) => !ru
      ? (s.isEmpty ? s : s[0].toUpperCase() + s.substring(1))
      : switch (s) {
          'charging' => 'Заряжается',
          'discharging' => 'Разряжается',
          'full' => 'Заряжена',
          'not charging' => 'Не заряжается',
          _ => s,
        };

  @override
  Widget build(BuildContext context) {
    final b = health.battery;
    final w = health.wifi;
    final m = health.mobile;
    final lowBattery = (b.level ?? 100) <= this.lowBattery && !b.isCharging;
    final hot = (b.tempC ?? 0) >= _hotC;

    return Section(
      title: tr('Phone', 'Телефон'),
      children: [
        _tile(
          context,
          icon: b.isCharging
              ? Icons.battery_charging_full
              : lowBattery
              ? Icons.battery_alert
              : Icons.battery_std,
          warn: lowBattery || hot || (b.health != null && b.health != 'good'),
          title: [
            if (b.level != null) '${b.level}%',
            if (b.status != null) _status(b.status!) + (b.plugged != null ? ' (${b.plugged})' : ''),
            if (b.left != null) tr('~${durationText(b.left!)} left', 'осталось ~${durationText(b.left!)}'),
          ].join(' · '),
          subtitle: [
            if (b.tempC != null) '${b.tempC!.toStringAsFixed(1)} °C${hot ? tr(' - hot!', ' — горячо!') : ''}',
            if (b.voltage != null) '${b.voltage!.toStringAsFixed(2)} V',
            if (b.currentMa != null) '${b.currentMa} mA',
            if (b.fullIn != null) tr('full in ${durationText(b.fullIn!)}', 'до полной ${durationText(b.fullIn!)}'),
            if (b.health != null) tr('health ${b.health}', 'состояние ${b.health}'),
          ].join(' · '),
        ),
        _tile(
          context,
          icon: w.connected ? Icons.wifi : Icons.wifi_off,
          warn: w.enabled && !w.connected,
          title: !w.enabled
              ? tr('Wi-Fi off', 'Wi-Fi выключен')
              : !w.connected
              ? tr('Wi-Fi not connected', 'Wi-Fi не подключён')
              : (w.ssid ?? tr('Wi-Fi connected', 'Wi-Fi подключён')),
          subtitle: !w.connected
              ? null
              : [
                  if (w.rssi != null) '${w.rssi} dBm (${w.level ?? '?'}/4)',
                  ?w.band,
                  if (w.mbps != null) tr('${w.mbps} Mbps', '${w.mbps} Мбит/с'),
                  ?w.ip,
                  if (w.ssid == null)
                    tr(
                      'name hidden: allow location in the Android app',
                      'имя скрыто: разрешите геолокацию в приложении',
                    ),
                ].join(' · '),
        ),
        _tile(
          context,
          icon: m.sim == 'ready' ? Icons.signal_cellular_alt : Icons.signal_cellular_off,
          warn: m.sim != null && m.sim != 'ready' && m.sim != 'no SIM',
          title: m.sim == 'ready'
              ? (m.operator ?? tr('Mobile network', 'Мобильная сеть'))
              : tr('Mobile: ${m.sim ?? 'unknown'}', 'Мобильная сеть: ${m.sim ?? 'неизвестно'}'),
          subtitle: m.sim != 'ready'
              ? null
              : [
                  ?m.type,
                  if (m.level != null)
                    '${tr('signal', 'сигнал')} ${m.level}/4${m.dbm != null ? ' (${m.dbm} dBm)' : ''}',
                  if (m.dataOn != null) m.dataOn! ? tr('data on', 'данные вкл.') : tr('data off', 'данные выкл.'),
                  if (m.roaming) tr('roaming', 'роуминг'),
                ].join(' · '),
        ),
        _tile(
          context,
          icon: health.internetOk ? Icons.public : Icons.public_off,
          warn: !health.internetOk,
          title: health.internetVia == 'none'
              ? tr('Offline', 'Нет сети')
              : health.internetOk
              ? tr('Online via ${health.internetVia}', 'Интернет через ${health.internetVia}')
              : tr(
                  'No internet access via ${health.internetVia}',
                  'Нет доступа в интернет через ${health.internetVia}',
                ),
          subtitle: tr('Up ${durationText(health.uptime)}', 'Работает ${durationText(health.uptime)}'),
        ),
      ],
    );
  }

  Widget _tile(
    BuildContext context, {
    required IconData icon,
    required String title,
    String? subtitle,
    bool warn = false,
  }) {
    final scheme = Theme.of(context).colorScheme;
    final color = warn ? scheme.error : null;
    return ListTile(
      leading: Icon(icon, color: color ?? scheme.primary),
      title: Text(title, style: TextStyle(color: color)),
      subtitle: subtitle == null || subtitle.isEmpty ? null : Text(subtitle),
    );
  }
}
