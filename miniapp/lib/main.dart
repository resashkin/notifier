import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import 'model.dart';
import 'telegram.dart';

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
    return ThemeData(
      colorScheme: scheme,
      scaffoldBackgroundColor: tg.secondaryBg ?? tg.bg,
      extensions: [_SectionColors(header: tg.sectionHeader ?? tg.hint, hint: tg.hint)],
    );
  }
}

class _SectionColors extends ThemeExtension<_SectionColors> {
  const _SectionColors({this.header, this.hint});

  final Color? header;
  final Color? hint;

  @override
  _SectionColors copyWith({Color? header, Color? hint}) =>
      _SectionColors(header: header ?? this.header, hint: hint ?? this.hint);

  @override
  _SectionColors lerp(_SectionColors? other, double t) => this;
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

  late Map<String, bool> _forward; // pkg -> forwarding on
  late Rules _rules;
  _Pause _pause = const _KeepPause();
  String _query = '';

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
      } else {
        _error = 'Open settings from the bot: send /settings and tap ⚙️ Settings.';
      }
    } on Object catch (e) {
      _error = 'Could not read the settings link ($e).\nSend /settings to the bot to get a fresh one.';
    }
    final state = _state;
    if (state != null) {
      _forward = {for (final a in state.apps) a.pkg: !a.muted};
      _rules = state.rules;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncMainButton());
  }

  Changes _changes() {
    final state = _state!;
    final rules = <String, bool>{};
    final before = state.rules.toJson();
    _rules.toJson().forEach((k, v) {
      if (before[k] != v) rules[k] = v;
    });
    return Changes(
      mute: [
        for (final a in state.apps)
          if (!a.muted && _forward[a.pkg] == false) a.pkg,
      ],
      unmute: [
        for (final a in state.apps)
          if (a.muted && _forward[a.pkg] == true) a.pkg,
      ],
      rules: rules,
      pauseMinutes: switch (_pause) {
        _PauseFor(:final minutes) => minutes,
        _ => null,
      },
      resume: _pause is _Resume,
    );
  }

  int _changeCount(Changes c) =>
      c.mute.length + c.unmute.length + c.rules.length + (c.pauseMinutes != null || c.resume ? 1 : 0);

  void _update(VoidCallback change) {
    TelegramApp.hapticSelection();
    setState(change);
    _syncMainButton();
  }

  void _syncMainButton() {
    if (_state == null) return TelegramApp.mainButton();
    final changes = _changes();
    final n = _changeCount(changes);
    TelegramApp.mainButton(
      text: n == 0 ? 'No changes' : 'Save $n change${n == 1 ? '' : 's'}',
      enabled: n > 0,
      onPressed: _save,
    );
  }

  void _save() {
    final changes = _changes();
    if (changes.isEmpty) return;
    if (TelegramApp.inTelegram) {
      TelegramApp.mainButtonProgress();
      TelegramApp.sendData(changes.toJson()); // Telegram closes the Mini App after this
    } else {
      showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Would send to the bot'),
          content: SelectableText(changes.toJson()),
          actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('OK'))],
        ),
      );
    }
  }

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
    final changeCount = _changeCount(_changes());

    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
          children: [
            if (_demo) const _Note('Demo data - open this from the bot with /settings to manage your phone.'),
            _statusHeader(context, state),
            if (state.health case final health?) _HealthSection(health),
            _Section(title: 'Forwarding', children: [_pauseChips(context, state)]),
            _Section(
              title: 'Apps',
              footer: state.apps.isEmpty
                  ? 'No notifications seen yet. Apps appear here after they post a notification.'
                  : 'Off = muted. Muted apps are not forwarded to Telegram.',
              children: [
                if (state.apps.length > 8)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
                    child: TextField(
                      decoration: const InputDecoration(
                        prefixIcon: Icon(Icons.search),
                        hintText: 'Search apps',
                        isDense: true,
                        border: OutlineInputBorder(),
                      ),
                      onChanged: (v) => setState(() => _query = v),
                    ),
                  ),
                for (final app in apps)
                  SwitchListTile(
                    value: _forward[app.pkg] ?? true,
                    onChanged: (v) => _update(() => _forward[app.pkg] = v),
                    title: Text(app.label),
                    subtitle: Text('${app.count} notification${app.count == 1 ? '' : 's'} · ${_ago(app.lastSeen)}'),
                  ),
              ],
            ),
            _Section(
              title: 'Rules',
              children: [
                SwitchListTile(
                  value: _rules.skipOngoing,
                  onChanged: (v) => _update(() => _rules = _rules.copyWith(skipOngoing: v)),
                  title: const Text('Skip ongoing notifications'),
                  subtitle: const Text('Music, downloads, navigation'),
                ),
                SwitchListTile(
                  value: _rules.skipGroupSummary,
                  onChanged: (v) => _update(() => _rules = _rules.copyWith(skipGroupSummary: v)),
                  title: const Text('Skip group summaries'),
                  subtitle: const Text('"5 new messages"'),
                ),
                SwitchListTile(
                  value: _rules.showApp,
                  onChanged: (v) => _update(() => _rules = _rules.copyWith(showApp: v)),
                  title: const Text('Show app name'),
                ),
                SwitchListTile(
                  value: _rules.silent,
                  onChanged: (v) => _update(() => _rules = _rules.copyWith(silent: v)),
                  title: const Text('Send silently'),
                  subtitle: const Text('No Telegram sound for forwarded messages'),
                ),
              ],
            ),
            if (!TelegramApp.inTelegram)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: FilledButton(
                  onPressed: changeCount == 0 ? null : _save,
                  child: Text(changeCount == 0 ? 'No changes' : 'Save $changeCount changes'),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _statusHeader(BuildContext context, PhoneState state) {
    final hint = Theme.of(context).extension<_SectionColors>()?.hint ?? Theme.of(context).colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 4, 4, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(state.device, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 2),
          Text(
            'Settings as of ${_clock(state.generatedAt)} (${_ago(state.generatedAt)})',
            style: TextStyle(color: hint, fontSize: 13),
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
            state.isPaused ? '⏸ Paused until ${_clock(state.pausedUntil)}' : '▶️ Forwarding is on',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              ChoiceChip(
                label: Text(state.isPaused ? 'Keep paused' : 'On'),
                selected: selected is _KeepPause,
                onSelected: (_) => _update(() => _pause = const _KeepPause()),
              ),
              if (state.isPaused)
                ChoiceChip(
                  label: const Text('Resume now'),
                  selected: selected is _Resume,
                  onSelected: (_) => _update(() => _pause = const _Resume()),
                ),
              for (final m in durations)
                ChoiceChip(
                  label: Text('Pause ${_duration(m)}'),
                  selected: selected == _PauseFor(m),
                  onSelected: (_) => _update(() => _pause = _PauseFor(m)),
                ),
            ],
          ),
        ],
      ),
    );
  }

  static String _duration(int minutes) => minutes < 60 ? '$minutes min' : '${minutes ~/ 60} h';

  static String _clock(DateTime t) => '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  static String _ago(DateTime t) {
    if (t.millisecondsSinceEpoch == 0) return 'never';
    final d = DateTime.now().difference(t);
    if (d.inMinutes < 1) return 'just now';
    if (d.inHours < 1) return '${d.inMinutes} min ago';
    if (d.inDays < 1) return '${d.inHours} h ago';
    return '${d.inDays} d ago';
  }
}

/// Battery, Wi-Fi, mobile network and internet as reported by the phone when the link was made.
class _HealthSection extends StatelessWidget {
  const _HealthSection(this.health);

  final PhoneHealth health;

  /// Values worth a warning color.
  static const _hotC = 45.0;
  static const _lowBattery = 20;

  @override
  Widget build(BuildContext context) {
    final b = health.battery;
    final w = health.wifi;
    final m = health.mobile;
    final lowBattery = (b.level ?? 100) <= _lowBattery && !b.isCharging;
    final hot = (b.tempC ?? 0) >= _hotC;

    return _Section(
      title: 'Phone',
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
            if (b.status != null) _capitalize(b.status!) + (b.plugged != null ? ' (${b.plugged})' : ''),
          ].join(' · '),
          subtitle: [
            if (b.tempC != null) '${b.tempC!.toStringAsFixed(1)} °C${hot ? ' - hot!' : ''}',
            if (b.voltage != null) '${b.voltage!.toStringAsFixed(2)} V',
            if (b.currentMa != null) '${b.currentMa} mA',
            if (b.fullIn != null) 'full in ${_duration(b.fullIn!)}',
            if (b.health != null) 'health ${b.health}',
          ].join(' · '),
        ),
        _tile(
          context,
          icon: w.connected ? Icons.wifi : Icons.wifi_off,
          warn: w.enabled && !w.connected,
          title: !w.enabled
              ? 'Wi-Fi off'
              : !w.connected
              ? 'Wi-Fi not connected'
              : (w.ssid ?? 'Wi-Fi connected'),
          subtitle: !w.connected
              ? null
              : [
                  if (w.rssi != null) '${w.rssi} dBm (${w.level ?? '?'}/4)',
                  ?w.band,
                  if (w.mbps != null) '${w.mbps} Mbps',
                  ?w.ip,
                  if (w.ssid == null) 'name hidden: allow location in the Android app',
                ].join(' · '),
        ),
        _tile(
          context,
          icon: m.sim == 'ready' ? Icons.signal_cellular_alt : Icons.signal_cellular_off,
          warn: m.sim != null && m.sim != 'ready' && m.sim != 'no SIM',
          title: m.sim == 'ready' ? (m.operator ?? 'Mobile network') : 'Mobile: ${m.sim ?? 'unknown'}',
          subtitle: m.sim != 'ready'
              ? null
              : [
                  ?m.type,
                  if (m.level != null) 'signal ${m.level}/4${m.dbm != null ? ' (${m.dbm} dBm)' : ''}',
                  if (m.dataOn != null) m.dataOn! ? 'data on' : 'data off',
                  if (m.roaming) 'roaming',
                ].join(' · '),
        ),
        _tile(
          context,
          icon: health.internetOk ? Icons.public : Icons.public_off,
          warn: !health.internetOk,
          title: health.internetVia == 'none'
              ? 'Offline'
              : health.internetOk
              ? 'Online via ${health.internetVia}'
              : 'No internet access via ${health.internetVia}',
          subtitle: 'Up ${_duration(health.uptime)}',
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

  static String _capitalize(String s) => s.isEmpty ? s : s[0].toUpperCase() + s.substring(1);

  static String _duration(Duration d) {
    if (d.inMinutes < 60) return '${d.inMinutes} min';
    if (d.inHours < 24) return '${d.inHours} h ${d.inMinutes % 60} min';
    return '${d.inDays} d ${d.inHours % 24} h';
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.children, this.footer});

  final String title;
  final List<Widget> children;
  final String? footer;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).extension<_SectionColors>();
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 6),
            child: Text(
              title.toUpperCase(),
              style: TextStyle(
                fontSize: 13,
                letterSpacing: 0.4,
                color: colors?.header ?? scheme.primary,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          Material(
            color: scheme.surface,
            borderRadius: BorderRadius.circular(12),
            clipBehavior: Clip.antiAlias,
            child: Column(children: children),
          ),
          if (footer != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 0),
              child: Text(footer!, style: TextStyle(fontSize: 13, color: colors?.hint ?? scheme.onSurfaceVariant)),
            ),
        ],
      ),
    );
  }
}

class _Note extends StatelessWidget {
  const _Note(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: scheme.primaryContainer, borderRadius: BorderRadius.circular(12)),
      child: Text(text, style: TextStyle(color: scheme.onPrimaryContainer)),
    );
  }
}
