package dev.notifier

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.zip.GZIPOutputStream

/**
 * Bridge to the settings Mini App (miniapp/ in this repo, Flutter web). No server involved:
 * - phone -> Mini App: the current state, gzip + base64url, in the `?s=` parameter of the button's link;
 * - Mini App -> phone: `Telegram.WebApp.sendData` delivers only the changes to the bot as `web_app_data`.
 *
 * Rules don't fit in a link for long, so the Mini App keeps its own copy (Telegram CloudStorage) and the link
 * carries only what changed since the copy the Mini App confirmed last ([Prefs.miniAppRules]):
 * `rules: {rev, base, up, del, ord}`; base "" = everything. A save names the revision it started from
 * ([Prefs.applyMiniAppRules]). Keep in sync with miniapp/lib/model.dart and miniapp/lib/sync.dart.
 */
object MiniAppState {

    /** Telegram doesn't document a URL limit for buttons; stay well within common 2 KB limits. */
    private const val MAX_ENCODED = 1800

    /** Range of the low-battery level (same in the app's Settings tab and the Mini App). */
    const val LOW_BATTERY_MIN = 5
    const val LOW_BATTERY_MAX = 50
    private val PACKAGE = Regex("^[A-Za-z0-9_.]{1,200}$")
    private const val RECENT = 15
    private const val RECENT_KEEP = 5

    /**
     * Link for the Mini App button, or null if the Mini App URL isn't configured.
     * [open]: the screen to start on ("rules", or "rule" with [example] = the notification to make a rule from).
     */
    fun link(context: Context, prefs: Prefs, open: String? = null, example: LogEntry? = null): String? {
        val base = prefs.miniAppUrl.trim().takeIf { it.startsWith("https://") } ?: return null
        return base + (if ('?' in base) "&" else "?") + "s=" + encode(context, prefs, open, example)
    }

    private fun encode(context: Context, prefs: Prefs, open: String?, example: LogEntry?): String {
        val muted = prefs.mutedApps().toSet()
        val seen = prefs.seenApps()
        val apps = seen.keys().asSequence().map { pkg ->
            val s = seen.getJSONObject(pkg)
            app(pkg, s.optString("l", pkg), pkg in muted, s.optInt("n"), s.optLong("t"))
        }.toMutableList()
        // Muted apps are always listed, so they can be unmuted even if they've gone quiet.
        (muted - seen.keys().asSequence().toSet()).forEach { apps += app(it, appLabel(context, it), true, 0, 0) }
        apps.sortByDescending { it.optLong("t") }
        val recent = prefs.recent().filter { it.pkg.isNotEmpty() && it.pkg != context.packageName }.take(RECENT)
            .map { entry(it, 200) }.toMutableList()
        val health = Health.snapshot(context)

        val rev = prefs.rulesRev
        val current = prefs.rules()
        prefs.rememberLinked(rev, current)
        val (snapRev, snapshot) = prefs.miniAppRules()
        var rules = JSONObject().put("rev", rev).put("base", snapRev)
            .apply { RulesDiff.between(if (snapRev.isEmpty()) emptyList() else snapshot, current).toJson().let { d -> d.keys().forEach { put(it, d.get(it)) } } }

        while (true) {
            val state = state(prefs, apps, recent, health, rules)
                .put("lang", prefs.lang)
                .apply { if (open != null) put("open", open) }
                .apply { if (example != null) put("ex", entry(example, 1000)) }
            val encoded = gzipBase64(state.toString())
            if (encoded.length <= MAX_ENCODED) return encoded
            // Too long. Rules come first; then the apps that are muted, the example, and a few recent ones.
            when {
                recent.size > RECENT_KEEP -> recent.removeAt(recent.size - 1)
                apps.any { it.optInt("m") == 0 } -> apps.removeAt(apps.indexOfLast { it.optInt("m") == 0 })
                recent.isNotEmpty() -> recent.removeAt(recent.size - 1)
                !rules.optBoolean("too_big") -> rules = JSONObject().put("rev", rev).put("base", "").put("too_big", true)
                else -> return encoded
            }
        }
    }

    private fun app(pkg: String, label: String, muted: Boolean, count: Int, lastSeen: Long) = JSONObject()
        .put("p", pkg).put("l", label).put("m", if (muted) 1 else 0).put("n", count).put("t", lastSeen)

    /** A logged notification for the Mini App's "recent" list and rule editor: the original title and text. */
    private fun entry(e: LogEntry, maxText: Int) = JSONObject()
        .put("p", e.pkg).put("a", e.app).put("t", e.title.take(100)).put("x", e.text.take(maxText))
        .put("s", e.action).put("time", e.time)

    private fun state(prefs: Prefs, apps: List<JSONObject>, recent: List<JSONObject>, health: JSONObject, rules: JSONObject) = JSONObject()
        .put("v", 1)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("ts", System.currentTimeMillis())
        .put("paused_until", prefs.pausedUntil)
        .put("skip_ongoing", prefs.skipOngoing)
        .put("skip_group_summary", prefs.skipGroupSummary)
        .put("show_app", prefs.showApp)
        .put("silent", prefs.silent)
        .put("low_battery", prefs.lowBatteryOn)
        .put("low_battery_level", prefs.lowBatteryLevel)
        .put("digest", JSONArray(Digest.times(prefs).map(Digest::format)))
        .put("digest_n", prefs.digest().size)
        .put("rules", rules)
        .put("apps", JSONArray(apps))
        .put("recent", JSONArray(recent))
        .put("health", health)

    private fun gzipBase64(text: String): String {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        return Base64.encodeToString(bytes.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Applies the changes sent by the Mini App; returns a summary for the bot's reply (HTML). */
    fun apply(context: Context, prefs: Prefs, data: String): String {
        val changes = JSONObject(data)
        val t = Lang.of(prefs)
        if (changes.optInt("v") != 1) return t("⚠️ Unknown settings format — update the Android app.", "⚠️ Неизвестный формат настроек — обновите приложение на телефоне.")
        val lines = mutableListOf<String>()

        fun packages(key: String) = changes.optJSONArray(key)?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.filter { PACKAGE.matches(it) }
        }.orEmpty()

        // Older Mini App versions mute and unmute whole apps.
        packages("mute").takeIf { it.isNotEmpty() }?.let { list ->
            list.forEach(prefs::mute)
            lines += t("🔕 Muted: ", "🔕 Без уведомлений: ") + list.joinToString { escapeHtml(appLabel(context, it)) }
        }
        packages("unmute").takeIf { it.isNotEmpty() }?.let { list ->
            list.forEach(prefs::unmute)
            lines += t("🔔 Unmuted: ", "🔔 Снова пересылаются: ") + list.joinToString { escapeHtml(appLabel(context, it)) }
        }

        changes.optJSONObject("rules")?.let { r ->
            val diff = RulesDiff.fromJson(r)
            val before = prefs.rules().associateBy { it.id }
            prefs.applyMiniAppRules(r.optString("base"), r.optString("rev"), diff)
            if (!diff.isEmpty) lines += rulesSummary(context, prefs, diff, before, t)
        }
        if (changes.optBoolean("rules_full")) {
            prefs.setMiniAppRules("", emptyList())
            lines += t("🔄 All rules will be in the next Settings link.", "🔄 Все правила придут со следующей ссылкой на настройки.")
        }

        if (changes.has("pause_minutes")) {
            val minutes = changes.getInt("pause_minutes").coerceIn(1, 7 * 24 * 60)
            prefs.pausedUntil = System.currentTimeMillis() + minutes * 60_000L
            lines += t("⏸ Paused until ", "⏸ Пауза до ") + DateFormat.getTimeFormat(context).format(Date(prefs.pausedUntil))
        } else if (changes.optBoolean("resume")) {
            prefs.pausedUntil = 0
            lines += t("▶️ Forwarding resumed", "▶️ Пересылка продолжена")
        }

        val switches = listOf(
            "skip_ongoing" to { v: Boolean -> prefs.skipOngoing = v },
            "skip_group_summary" to { v: Boolean -> prefs.skipGroupSummary = v },
            "show_app" to { v: Boolean -> prefs.showApp = v },
            "silent" to { v: Boolean -> prefs.silent = v },
        ).filter { (key, set) -> changes.has(key).also { if (it) set(changes.getBoolean(key)) } }
        if (switches.isNotEmpty()) lines += t("⚙️ Forwarding options updated", "⚙️ Параметры пересылки обновлены")
        if (changes.has("low_battery")) prefs.lowBatteryOn = changes.getBoolean("low_battery")
        if (changes.has("low_battery_level")) {
            prefs.lowBatteryLevel = changes.getInt("low_battery_level").coerceIn(LOW_BATTERY_MIN, LOW_BATTERY_MAX)
            prefs.lowBatteryWarned = false
        }
        if (changes.has("low_battery") || changes.has("low_battery_level")) {
            lines += if (prefs.lowBatteryOn) t("🪫 Low battery warning at ${prefs.lowBatteryLevel} %", "🪫 Предупреждение о батарее при ${prefs.lowBatteryLevel} %")
            else t("🪫 Low battery warning off", "🪫 Предупреждение о батарее выключено")
        }
        changes.optJSONArray("digest")?.let { arr ->
            val times = (0 until arr.length()).mapNotNull { Digest.parse(arr.optString(it)) }
            Digest.setTimes(context, prefs, times)
            lines += if (times.isEmpty()) t("📋 Digest off", "📋 Дайджест выключен")
            else t("📋 Digest at ", "📋 Дайджест в ") + Digest.times(prefs).joinToString(", ", transform = Digest::format)
        }

        return lines.joinToString("\n").ifEmpty { t("Nothing changed.", "Ничего не изменилось.") }
    }

    /** What changed in the rules, and how many recent notifications each new or edited rule would have caught. */
    private fun rulesSummary(context: Context, prefs: Prefs, diff: RulesDiff, before: Map<String, Rule>, t: Lang): List<String> {
        val added = diff.up.count { it.id !in before }
        val edited = diff.up.size - added
        val removed = diff.del.count { it in before }
        val counts = listOfNotNull(
            added.takeIf { it > 0 }?.let { t("$it added", "добавлено: $it") },
            edited.takeIf { it > 0 }?.let { t("$it changed", "изменено: $it") },
            removed.takeIf { it > 0 }?.let { t("$it removed", "удалено: $it") },
        )
        val lines = mutableListOf(t("📏 Rules: ", "📏 Правила: ") + counts.joinToString(", ").ifEmpty { t("order changed", "изменён порядок") })
        val log = prefs.recent().filter { it.pkg.isNotEmpty() && it.pkg != context.packageName }
        if (log.isEmpty()) return lines
        for (rule in diff.up.filter { it.on && (it.hasConditions || it.kind == Rule.Kind.MAP) }.take(5)) {
            val n = log.count { RuleEngine.matches(rule, Incoming(it.pkg, it.title, it.text, it.app)) }
            val label = escapeHtml(rule.label({ appLabel(context, it) }, t))
            lines += t("• «$label»: would match $n of the last ${log.size}", "• «$label»: совпало бы с $n из последних ${log.size}")
        }
        return lines
    }
}
