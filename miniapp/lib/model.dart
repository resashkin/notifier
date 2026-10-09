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

/// Phone health at the time the link was made (Android `Health.kt`). Fields are null when the phone
/// couldn't read them (e.g. Wi-Fi name without location permission).
class PhoneHealth {
  const PhoneHealth({
    required this.battery,
    required this.wifi,
    required this.mobile,
    required this.internetVia,
    required this.internetOk,
    required this.uptime,
  });

  factory PhoneHealth.fromJson(Map<String, dynamic> j) {
    final internet = (j['internet'] as Map<String, dynamic>?) ?? const {};
    return PhoneHealth(
      battery: BatteryInfo.fromJson((j['battery'] as Map<String, dynamic>?) ?? const {}),
      wifi: WifiInfo.fromJson((j['wifi'] as Map<String, dynamic>?) ?? const {}),
      mobile: MobileInfo.fromJson((j['mobile'] as Map<String, dynamic>?) ?? const {}),
      internetVia: (internet['via'] as String?) ?? 'none',
      internetOk: internet['ok'] == true,
      uptime: Duration(minutes: (j['uptime_min'] as num?)?.toInt() ?? 0),
    );
  }

  final BatteryInfo battery;
  final WifiInfo wifi;
  final MobileInfo mobile;
  final String internetVia;
  final bool internetOk;
  final Duration uptime;
}

class BatteryInfo {
  const BatteryInfo({
    this.level,
    this.status,
    this.plugged,
    this.tempC,
    this.voltage,
    this.health,
    this.currentMa,
    this.fullIn,
  });

  factory BatteryInfo.fromJson(Map<String, dynamic> j) => BatteryInfo(
    level: (j['level'] as num?)?.toInt(),
    status: j['status'] as String?,
    plugged: j['plugged'] == 'none' ? null : j['plugged'] as String?,
    tempC: (j['temp_c'] as num?)?.toDouble(),
    voltage: (j['voltage_v'] as num?)?.toDouble(),
    health: j['health'] as String?,
    currentMa: (j['current_ma'] as num?)?.toInt(),
    fullIn: j['full_in_min'] == null ? null : Duration(minutes: (j['full_in_min'] as num).toInt()),
  );

  final int? level;
  final String? status;
  final String? plugged;
  final double? tempC;
  final double? voltage;
  final String? health;
  final int? currentMa;
  final Duration? fullIn;

  bool get isCharging => status == 'charging' || status == 'full';
}

class WifiInfo {
  const WifiInfo({
    this.enabled = false,
    this.connected = false,
    this.ssid,
    this.rssi,
    this.level,
    this.band,
    this.mbps,
    this.ip,
  });

  factory WifiInfo.fromJson(Map<String, dynamic> j) => WifiInfo(
    enabled: j['enabled'] == true,
    connected: j['connected'] == true,
    ssid: j['ssid'] as String?,
    rssi: (j['rssi_dbm'] as num?)?.toInt(),
    level: (j['level'] as num?)?.toInt(),
    band: j['band'] as String?,
    mbps: (j['mbps'] as num?)?.toInt(),
    ip: j['ip'] as String?,
  );

  final bool enabled;
  final bool connected;
  final String? ssid;
  final int? rssi;
  final int? level;
  final String? band;
  final int? mbps;
  final String? ip;
}

class MobileInfo {
  const MobileInfo({this.sim, this.operator, this.type, this.level, this.dbm, this.dataOn, this.roaming = false});

  factory MobileInfo.fromJson(Map<String, dynamic> j) => MobileInfo(
    sim: j['sim'] as String?,
    operator: j['operator'] as String?,
    type: j['type'] as String?,
    level: (j['level'] as num?)?.toInt(),
    dbm: (j['dbm'] as num?)?.toInt(),
    dataOn: j['data'] as bool?,
    roaming: j['roaming'] == true,
  );

  final String? sim;
  final String? operator;
  final String? type;
  final int? level;
  final int? dbm;
  final bool? dataOn;
  final bool roaming;
}

class PhoneState {
  PhoneState({
    required this.device,
    required this.generatedAt,
    required this.pausedUntil,
    required this.rules,
    required this.apps,
    this.health,
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
      health: json['health'] is Map<String, dynamic>
          ? PhoneHealth.fromJson(json['health'] as Map<String, dynamic>)
          : null, // links from Android builds before phone health
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
      health: const PhoneHealth(
        battery: BatteryInfo(
          level: 86,
          status: 'charging',
          plugged: 'AC',
          tempC: 31.5,
          voltage: 4.21,
          health: 'good',
          currentMa: 1250,
          fullIn: Duration(minutes: 38),
        ),
        wifi: WifiInfo(
          enabled: true,
          connected: true,
          ssid: 'Home',
          rssi: -54,
          level: 4,
          band: '5 GHz',
          mbps: 433,
          ip: '192.168.1.20',
        ),
        mobile: MobileInfo(sim: 'ready', operator: 'Yettel', type: 'LTE', level: 3, dbm: -97, dataOn: true),
        internetVia: 'Wi-Fi',
        internetOk: true,
        uptime: Duration(days: 3, hours: 4),
      ),
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
  final PhoneHealth? health;

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
