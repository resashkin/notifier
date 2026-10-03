/// Settings snapshot sent by the phone in the `?s=` link parameter, and the changes sent back.
///
/// Link format (built by the Android app's `MiniAppState.kt`): gzip(JSON), base64url without padding.
/// Back channel: `Telegram.WebApp.sendData(json)`, ≤ 4096 bytes, containing only what changed.
library;

import 'dart:convert';

import 'package:archive/archive.dart';

class AppInfo {
  AppInfo({required this.pkg, required this.label, required this.muted, required this.count, required this.lastSeen});

  factory AppInfo.fromJson(Map<String, dynamic> j) => AppInfo(
    pkg: j['p'] as String,
    label: (j['l'] as String?) ?? (j['p'] as String),
    muted: j['m'] == 1 || j['m'] == true,
    count: (j['n'] as num?)?.toInt() ?? 0,
    lastSeen: DateTime.fromMillisecondsSinceEpoch((j['t'] as num?)?.toInt() ?? 0),
  );

  final String pkg;
  final String label;
  final bool muted;
  final int count;
  final DateTime lastSeen;
}

class Rules {
  const Rules({required this.skipOngoing, required this.skipGroupSummary, required this.showApp, required this.silent});

  final bool skipOngoing;
  final bool skipGroupSummary;
  final bool showApp;
  final bool silent;

  Rules copyWith({bool? skipOngoing, bool? skipGroupSummary, bool? showApp, bool? silent}) => Rules(
    skipOngoing: skipOngoing ?? this.skipOngoing,
    skipGroupSummary: skipGroupSummary ?? this.skipGroupSummary,
    showApp: showApp ?? this.showApp,
    silent: silent ?? this.silent,
  );

  Map<String, bool> toJson() => {
    'skip_ongoing': skipOngoing,
    'skip_group_summary': skipGroupSummary,
    'show_app': showApp,
    'silent': silent,
  };
}

class PhoneState {
  PhoneState({
    required this.device,
    required this.generatedAt,
    required this.pausedUntil,
    required this.rules,
    required this.apps,
  });

  /// Decodes the `s` link parameter. Throws [FormatException] if it isn't valid.
  factory PhoneState.decode(String encoded) {
    final padded = encoded.padRight((encoded.length + 3) ~/ 4 * 4, '=');
    final gz = base64Url.decode(padded);
    final json = jsonDecode(utf8.decode(const GZipDecoder().decodeBytes(gz))) as Map<String, dynamic>;
    if (json['v'] != 1) throw const FormatException('Unsupported settings version - update the Android app');
    return PhoneState(
      device: (json['device'] as String?) ?? 'Phone',
      generatedAt: DateTime.fromMillisecondsSinceEpoch((json['ts'] as num).toInt()),
      pausedUntil: DateTime.fromMillisecondsSinceEpoch((json['paused_until'] as num?)?.toInt() ?? 0),
      rules: Rules(
        skipOngoing: json['skip_ongoing'] as bool? ?? true,
        skipGroupSummary: json['skip_group_summary'] as bool? ?? true,
        showApp: json['show_app'] as bool? ?? true,
        silent: json['silent'] as bool? ?? false,
      ),
      apps: [for (final a in (json['apps'] as List? ?? const [])) AppInfo.fromJson(a as Map<String, dynamic>)]
        ..sort((a, b) => b.lastSeen.compareTo(a.lastSeen)),
    );
  }

  /// Example data for running outside Telegram (`flutter run -d chrome`).
  factory PhoneState.demo() {
    final now = DateTime.now();
    return PhoneState(
      device: 'Demo phone',
      generatedAt: now,
      pausedUntil: DateTime.fromMillisecondsSinceEpoch(0),
      rules: const Rules(skipOngoing: true, skipGroupSummary: true, showApp: true, silent: false),
      apps: [
        AppInfo(
          pkg: 'org.telegram.messenger',
          label: 'Telegram',
          muted: false,
          count: 42,
          lastSeen: now.subtract(const Duration(minutes: 2)),
        ),
        AppInfo(
          pkg: 'com.whatsapp',
          label: 'WhatsApp',
          muted: false,
          count: 17,
          lastSeen: now.subtract(const Duration(minutes: 40)),
        ),
        AppInfo(
          pkg: 'com.google.android.googlequicksearchbox',
          label: 'Google',
          muted: true,
          count: 9,
          lastSeen: now.subtract(const Duration(hours: 3)),
        ),
        AppInfo(
          pkg: 'rs.yettelbank.app',
          label: 'Yettel Bank',
          muted: false,
          count: 3,
          lastSeen: now.subtract(const Duration(days: 1)),
        ),
      ],
    );
  }

  final String device;
  final DateTime generatedAt;
  final DateTime pausedUntil;
  final Rules rules;
  final List<AppInfo> apps;

  bool get isPaused => pausedUntil.isAfter(DateTime.now());
}

/// What the user changed. Sent to the bot; the phone applies it.
class Changes {
  Changes({required this.mute, required this.unmute, required this.rules, this.pauseMinutes, this.resume = false});

  final List<String> mute;
  final List<String> unmute;
  final Map<String, bool> rules;
  final int? pauseMinutes;
  final bool resume;

  bool get isEmpty => mute.isEmpty && unmute.isEmpty && rules.isEmpty && pauseMinutes == null && !resume;

  String toJson() => jsonEncode({
    'v': 1,
    if (mute.isNotEmpty) 'mute': mute,
    if (unmute.isNotEmpty) 'unmute': unmute,
    ...rules,
    if (pauseMinutes != null) 'pause_minutes': pauseMinutes,
    if (resume) 'resume': true,
  });
}
