/// Thin wrapper over the Telegram Mini App JS API (`window.Telegram.WebApp`).
///
/// Loaded by `web/index.html`. Outside Telegram (e.g. `flutter run -d chrome`) the script still
/// defines the object but [TelegramApp.inTelegram] is false and Telegram-only calls are no-ops.
library;

import 'dart:js_interop';
import 'dart:ui';

@JS('Telegram.WebApp')
external _WebApp? get _webApp;

@JS('removeLoader')
external void _removeLoader();

extension type _WebApp._(JSObject _) implements JSObject {
  external String get initData;
  external String get colorScheme;
  external _ThemeParams get themeParams;
  @JS('MainButton')
  external _MainButton get mainButton;
  @JS('HapticFeedback')
  external _Haptic get haptic;
  external void ready();
  external void expand();
  external void sendData(String data);
}

extension type _ThemeParams._(JSObject _) implements JSObject {
  @JS('bg_color')
  external String? get bgColor;
  @JS('secondary_bg_color')
  external String? get secondaryBgColor;
  @JS('section_bg_color')
  external String? get sectionBgColor;
  @JS('text_color')
  external String? get textColor;
  @JS('hint_color')
  external String? get hintColor;
  @JS('subtitle_text_color')
  external String? get subtitleTextColor;
  @JS('section_header_text_color')
  external String? get sectionHeaderTextColor;
  @JS('button_color')
  external String? get buttonColor;
  @JS('button_text_color')
  external String? get buttonTextColor;
  @JS('accent_text_color')
  external String? get accentTextColor;
  @JS('destructive_text_color')
  external String? get destructiveTextColor;
}

extension type _MainButton._(JSObject _) implements JSObject {
  external void setText(String text);
  external void show();
  external void hide();
  external void enable();
  external void disable();
  external void showProgress(bool leaveActive);
  external void hideProgress();
  external void onClick(JSFunction callback);
  external void offClick(JSFunction callback);
}

extension type _Haptic._(JSObject _) implements JSObject {
  external void selectionChanged();
  external void notificationOccurred(String type);
}

/// Colors Telegram asked us to use (they follow the user's Telegram theme).
class TelegramTheme {
  const TelegramTheme({
    required this.isDark,
    this.bg,
    this.sectionBg,
    this.secondaryBg,
    this.text,
    this.hint,
    this.subtitle,
    this.sectionHeader,
    this.button,
    this.buttonText,
    this.accent,
    this.destructive,
  });

  final bool isDark;
  final Color? bg, sectionBg, secondaryBg, text, hint, subtitle, sectionHeader, button, buttonText, accent, destructive;
}

abstract final class TelegramApp {
  static _WebApp? get _app {
    try {
      return _webApp;
    } catch (_) {
      return null; // telegram-web-app.js failed to load
    }
  }

  /// True when actually opened inside Telegram (Telegram passes signed launch data).
  static bool get inTelegram => (_app?.initData ?? '').isNotEmpty;

  /// Tells Telegram the app is ready (hides its placeholder) and opens it full height.
  static void ready() {
    try {
      _removeLoader();
    } catch (_) {}
    _app?.ready();
    _app?.expand();
  }

  static TelegramTheme? theme() {
    final app = _app;
    if (app == null || !inTelegram) return null;
    final t = app.themeParams;
    return TelegramTheme(
      isDark: app.colorScheme == 'dark',
      bg: _color(t.bgColor),
      sectionBg: _color(t.sectionBgColor),
      secondaryBg: _color(t.secondaryBgColor),
      text: _color(t.textColor),
      hint: _color(t.hintColor),
      subtitle: _color(t.subtitleTextColor),
      sectionHeader: _color(t.sectionHeaderTextColor),
      button: _color(t.buttonColor),
      buttonText: _color(t.buttonTextColor),
      accent: _color(t.accentTextColor),
      destructive: _color(t.destructiveTextColor),
    );
  }

  static JSFunction? _mainButtonHandler;

  /// Configures Telegram's native bottom button. Hidden when [text] is null.
  static void mainButton({String? text, bool enabled = true, VoidCallback? onPressed}) {
    final button = _app?.mainButton;
    if (button == null || !inTelegram) return;
    if (_mainButtonHandler != null) button.offClick(_mainButtonHandler!);
    _mainButtonHandler = null;
    if (text == null) {
      button.hide();
      return;
    }
    button.setText(text);
    enabled ? button.enable() : button.disable();
    if (onPressed != null) {
      _mainButtonHandler = onPressed.toJS;
      button.onClick(_mainButtonHandler!);
    }
    button.show();
  }

  static void mainButtonProgress() => _app?.mainButton.showProgress(false);

  /// Sends [data] (max 4096 bytes) to the bot as a message and closes the Mini App.
  /// Only works when the app was opened from a keyboard button.
  static void sendData(String data) => _app?.sendData(data);

  static void hapticSelection() {
    if (inTelegram) _app?.haptic.selectionChanged();
  }

  static Color? _color(String? hex) {
    if (hex == null || hex.length != 7 || !hex.startsWith('#')) return null;
    final value = int.tryParse(hex.substring(1), radix: 16);
    return value == null ? null : Color(0xFF000000 | value);
  }
}
