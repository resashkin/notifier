/// Russian or English, following the user's Telegram language (the phone's bot language as a fallback).
/// Strings sit next to their use: `tr('Rules', 'Правила')`.
library;

bool ru = false;

/// Decides the language once at start: Telegram's language_code first, then the link's `lang`.
void setLanguage({String? telegram, String? phone}) {
  final code = (telegram?.isNotEmpty ?? false) ? telegram! : (phone ?? 'en');
  ru = code.toLowerCase().startsWith('ru');
}

String tr(String en, String ruText) => ru ? ruText : en;

/// "1 rule" / "5 rules"; Russian needs three forms ("правило", "правила", "правил").
String plural(int n, String en1, String enMany, String ru1, String ru2, String ru5) {
  if (!ru) return '$n ${n == 1 ? en1 : enMany}';
  final tens = n % 100;
  final ones = n % 10;
  final word = tens >= 11 && tens <= 14
      ? ru5
      : ones == 1
      ? ru1
      : ones >= 2 && ones <= 4
      ? ru2
      : ru5;
  return '$n $word';
}

String notificationsCount(int n) =>
    plural(n, 'notification', 'notifications', 'уведомление', 'уведомления', 'уведомлений');

String rulesCount(int n) => plural(n, 'rule', 'rules', 'правило', 'правила', 'правил');

String changesCount(int n) => plural(n, 'change', 'changes', 'изменение', 'изменения', 'изменений');

String durationText(Duration d) {
  if (d.inMinutes < 60) return tr('${d.inMinutes} min', '${d.inMinutes} мин');
  if (d.inHours < 24) return tr('${d.inHours} h ${d.inMinutes % 60} min', '${d.inHours} ч ${d.inMinutes % 60} мин');
  return tr('${d.inDays} d ${d.inHours % 24} h', '${d.inDays} д ${d.inHours % 24} ч');
}

String ago(DateTime t) {
  if (t.millisecondsSinceEpoch == 0) return tr('never', 'никогда');
  final d = DateTime.now().difference(t);
  if (d.inMinutes < 1) return tr('just now', 'только что');
  if (d.inHours < 1) return tr('${d.inMinutes} min ago', '${d.inMinutes} мин назад');
  if (d.inDays < 1) return tr('${d.inHours} h ago', '${d.inHours} ч назад');
  return tr('${d.inDays} d ago', '${d.inDays} д назад');
}

String clock(DateTime t) => '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
