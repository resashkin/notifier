/// Settings snapshot sent by the phone in the `?s=` link parameter, and the changes sent back.
///
/// Link format (built by the Android app's `MiniAppState.kt`): gzip(JSON), base64url without padding.
/// Back channel: `Telegram.WebApp.sendData(json)`, ≤ 4096 bytes, containing only what changed.
/// Rules travel as a diff against the copy this Mini App keeps (see sync.dart).
library;

import 'dart:convert';

import 'package:archive/archive.dart';

import 'rules.dart';

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

/// Forwarding options (switches) and the battery warning.
class Options {
  const Options({
    required this.skipOngoing,
    required this.skipGroupSummary,
    required this.showApp,
    required this.silent,
    this.lowBattery = true,
    this.lowBatteryLevel = 20,
  });

  /// Range of the low-battery level, same as the Android app (`MiniAppState.LOW_BATTERY_MIN/MAX`).
  static const lowBatteryMin = 5;
  static const lowBatteryMax = 50;

  final bool skipOngoing;
  final bool skipGroupSummary;
  final bool showApp;
  final bool silent;

  /// Warn in the chat when the battery drops to [lowBatteryLevel] %.
  final bool lowBattery;
  final int lowBatteryLevel;

  Options copyWith({
    bool? skipOngoing,
    bool? skipGroupSummary,
    bool? showApp,
    bool? silent,
    bool? lowBattery,
    int? lowBatteryLevel,
  }) => Options(
    skipOngoing: skipOngoing ?? this.skipOngoing,
    skipGroupSummary: skipGroupSummary ?? this.skipGroupSummary,
    showApp: showApp ?? this.showApp,
    silent: silent ?? this.silent,
    lowBattery: lowBattery ?? this.lowBattery,
    lowBatteryLevel: lowBatteryLevel ?? this.lowBatteryLevel,
  );

  Map<String, Object> toJson() => {
    'skip_ongoing': skipOngoing,
    'skip_group_summary': skipGroupSummary,
    'show_app': showApp,
    'silent': silent,
    'low_battery': lowBattery,
    'low_battery_level': lowBatteryLevel,
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
    this.left,
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
    left: j['left_min'] == null ? null : Duration(minutes: (j['left_min'] as num).toInt()),
  );

  final int? level;
  final String? status;
  final String? plugged;
  final double? tempC;
  final double? voltage;
  final String? health;
  final int? currentMa;
  final Duration? fullIn;

  /// Approximate time on battery until empty (phone's estimate, only while not charging).
  final Duration? left;

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

/// A recent notification as the phone logged it (original title and text), to build or preview rules.
/// [status]: the mute action that applied ("drop", "digest", "silent") or "" when forwarded.
class RecentItem {
  const RecentItem({
    required this.pkg,
    required this.app,
    required this.title,
    required this.text,
    this.status = '',
    required this.time,
  });

  factory RecentItem.fromJson(Map<String, dynamic> j) => RecentItem(
    pkg: (j['p'] as String?) ?? '',
    app: (j['a'] as String?) ?? '',
    title: (j['t'] as String?) ?? '',
    text: (j['x'] as String?) ?? '',
    status: (j['s'] as String?) ?? '',
    time: DateTime.fromMillisecondsSinceEpoch((j['time'] as num?)?.toInt() ?? 0),
  );

  final String pkg;
  final String app;
  final String title;
  final String text;
  final String status;
  final DateTime time;

  Incoming get incoming => Incoming(pkg: pkg, title: title, text: text, app: app);
}

/// The rules part of the link: what changed on the phone since the copy this Mini App confirmed last.
/// [base] = the revision the diff applies to ("" = the diff is everything); [rev] = the phone's revision now.
/// [tooBig]: the rules didn't fit in the link.
class RulesLink {
  const RulesLink({required this.rev, required this.base, required this.diff, this.tooBig = false});

  factory RulesLink.fromJson(Map<String, dynamic> j) => RulesLink(
    rev: (j['rev'] as String?) ?? '',
    base: (j['base'] as String?) ?? '',
    diff: RulesDiff.fromJson(j),
    tooBig: j['too_big'] == true,
  );

  final String rev;
  final String base;
  final RulesDiff diff;
  final bool tooBig;
}

class PhoneState {
  PhoneState({
    required this.device,
    required this.generatedAt,
    required this.pausedUntil,
    required this.options,
    required this.apps,
    this.health,
    this.lang,
    this.rules,
    this.digestTimes,
    this.digestWaiting = 0,
    this.recent = const [],
    this.example,
    this.open,
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
      options: Options(
        skipOngoing: json['skip_ongoing'] as bool? ?? true,
        skipGroupSummary: json['skip_group_summary'] as bool? ?? true,
        showApp: json['show_app'] as bool? ?? true,
        silent: json['silent'] as bool? ?? false,
        lowBattery: json['low_battery'] as bool? ?? true,
        lowBatteryLevel: (json['low_battery_level'] as num?)?.toInt() ?? 20,
      ),
      apps: [for (final a in (json['apps'] as List? ?? const [])) AppInfo.fromJson(a as Map<String, dynamic>)]
        ..sort((a, b) => b.lastSeen.compareTo(a.lastSeen)),
      health: json['health'] is Map<String, dynamic>
          ? PhoneHealth.fromJson(json['health'] as Map<String, dynamic>)
          : null, // links from Android builds before phone health
      lang: json['lang'] as String?,
      // Absent in links from Android builds before rules.
      rules: json['rules'] is Map<String, dynamic> ? RulesLink.fromJson(json['rules'] as Map<String, dynamic>) : null,
      digestTimes: json['digest'] is List ? [for (final t in json['digest'] as List) '$t'] : null,
      digestWaiting: (json['digest_n'] as num?)?.toInt() ?? 0,
      recent: [
        for (final r in (json['recent'] as List? ?? const []))
          if (r is Map<String, dynamic>) RecentItem.fromJson(r),
      ],
      example: json['ex'] is Map<String, dynamic> ? RecentItem.fromJson(json['ex'] as Map<String, dynamic>) : null,
      open: json['open'] as String?,
    );
  }

  /// Example data for running outside Telegram (`flutter run -d chrome`).
  factory PhoneState.demo() {
    final now = DateTime.now();
    return PhoneState(
      device: 'Demo phone',
      generatedAt: now,
      pausedUntil: DateTime.fromMillisecondsSinceEpoch(0),
      options: const Options(skipOngoing: true, skipGroupSummary: true, showApp: true, silent: false),
      lang: 'en',
      rules: RulesLink(
        rev: 'demo',
        base: '',
        diff: RulesDiff(
          [
            Rule(id: 'demo0001', kind: RuleKind.always, words: const ['code', 'код']),
            Rule(id: 'demo0002', kind: RuleKind.mute, apps: const ['com.google.android.googlequicksearchbox']),
            Rule(id: 'demo0003', kind: RuleKind.mute, words: const ['sale', 'скидк'], action: RuleAction.digest),
            Rule(
              id: 'demo0004',
              kind: RuleKind.map,
              chats: const ['900'],
              links: true,
              parse: r'Покупка (?<amount>[\d ]+(?:[.,]\d+)?) ?(?<cur>р|₽|RUB) (?<shop>.+?) Баланс',
              out: '💳 −{amount} {cur} · {shop}',
            ),
          ],
          const [],
          null,
        ),
      ),
      digestTimes: const ['09:00', '13:00', '19:00'],
      digestWaiting: 3,
      recent: [
        RecentItem(
          pkg: 'com.google.android.apps.messaging',
          app: 'Messages',
          title: '900',
          text: 'MIR-1234 14:05 Покупка 1 250р PYATEROCHKA 7712 Баланс: 10 500р',
          time: now.subtract(const Duration(minutes: 5)),
        ),
        RecentItem(
          pkg: 'com.shop.app',
          app: 'Shop',
          title: 'Big sale',
          text: 'Up to 50% off today only https://shop.example/sale',
          status: 'digest',
          time: now.subtract(const Duration(minutes: 30)),
        ),
        RecentItem(
          pkg: 'com.whatsapp',
          app: 'WhatsApp',
          title: 'Work chat',
          text: 'Ann: meeting moved to 15:00',
          time: now.subtract(const Duration(hours: 1)),
        ),
      ],
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
  final Options options;
  final List<AppInfo> apps;
  final PhoneHealth? health;

  /// The phone's bot language ("ru"/"en"), used when Telegram doesn't tell us the user's.
  final String? lang;

  /// Null when the phone's app predates rules: only whole apps can be muted then.
  final RulesLink? rules;

  /// Digest times "HH:MM" (empty = off); null from phones without the digest.
  final List<String>? digestTimes;
  final int digestWaiting;
  final List<RecentItem> recent;

  /// The notification to make a rule from (/rule as a reply in the chat).
  final RecentItem? example;

  /// Screen to start on: "rules", or "rule" (the editor with [example]).
  final String? open;

  bool get isPaused => pausedUntil.isAfter(DateTime.now());

  String appLabel(String pkg) =>
      apps.where((a) => a.pkg == pkg).firstOrNull?.label ??
      recent.where((r) => r.pkg == pkg).firstOrNull?.app ??
      (example?.pkg == pkg ? example!.app : pkg);
}

/// What the user changed. Sent to the bot; the phone applies it.
class Changes {
  Changes({
    this.mute = const [],
    this.unmute = const [],
    this.options = const {},
    this.pauseMinutes,
    this.resume = false,
    this.rules,
    this.digest,
  });

  /// Whole apps, for phones without rules.
  final List<String> mute;
  final List<String> unmute;

  /// Changed forwarding options and battery settings (keys as the phone names them).
  final Map<String, Object> options;
  final int? pauseMinutes;
  final bool resume;

  /// Rule changes made on top of the link's revision.
  final RulesDiff? rules;

  /// New digest times, when changed.
  final List<String>? digest;

  /// How many things changed, for "Save N changes".
  int get count =>
      mute.length +
      unmute.length +
      options.length +
      (pauseMinutes != null || resume ? 1 : 0) +
      (rules?.size ?? 0) +
      (digest != null ? 1 : 0);

  bool get isEmpty => count == 0;

  /// [rulesSync] = {base, rev} for the rules (sent with every save once rules are loaded, so the phone
  /// knows which copy this Mini App now keeps).
  String toJson({Map<String, String>? rulesSync}) => jsonEncode({
    'v': 1,
    if (mute.isNotEmpty) 'mute': mute,
    if (unmute.isNotEmpty) 'unmute': unmute,
    ...options,
    if (pauseMinutes != null) 'pause_minutes': pauseMinutes,
    if (resume) 'resume': true,
    if (rulesSync != null) 'rules': {...rulesSync, ...(rules ?? const RulesDiff([], [], null)).toJson()},
    'digest': ?digest,
  });
}
