package dev.notifier

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** App settings plus a short log of recently forwarded notifications, in SharedPreferences. */
class Prefs(context: Context) {
    val raw: SharedPreferences = context.getSharedPreferences("notifier", Context.MODE_PRIVATE)

    var botToken by string("bot_token", "")
    var chatId by string("chat_id", "")
    var enabled by bool("enabled", false)
    var skipOngoing by bool("skip_ongoing", true)
    var skipGroupSummary by bool("skip_group_summary", true)
    var showApp by bool("show_app", true)
    var silent by bool("silent", false)
    var ignoredPackages by string(KEY_IGNORED, DEFAULT_IGNORED)

    /** Set from the bot with /pause; forwarding resumes automatically after this time (epoch ms). */
    var pausedUntil by long(KEY_PAUSED_UNTIL, 0L)

    /** Next Telegram getUpdates offset, so bot commands aren't handled twice after a restart. */
    var updateOffset by long("update_offset", 0L)

    /** The bot's display name; notifications from its chat on this phone are never forwarded. */
    var botDisplayName by string("bot_display_name", "")

    /** HTTPS address of the settings Mini App (the Flutter web build); opened from the bot with /settings. */
    var miniAppUrl by string("mini_app_url", DEFAULT_MINI_APP_URL)

    fun ignoredSet(): Set<String> =
        ignoredPackages.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Apps muted by the user (the built-in system entries are left out). */
    fun mutedApps(): List<String> = (ignoredSet() - DEFAULT_IGNORED_SET).toList()

    fun mute(pkg: String) {
        ignoredPackages = (ignoredPackages.lines().map { it.trim() }.filter { it.isNotEmpty() } + pkg)
            .distinct().joinToString("\n")
    }

    fun unmute(pkg: String) {
        ignoredPackages = ignoredPackages.lines().map { it.trim() }.filter { it.isNotEmpty() && it != pkg }
            .joinToString("\n")
    }

    fun isPaused() = pausedUntil > System.currentTimeMillis()

    /** Apps that posted notifications: package -> {"l": label, "n": count, "t": last seen ms}. */
    fun seenApps(): JSONObject = JSONObject(raw.getString(KEY_SEEN, "{}"))

    @Synchronized
    fun recordSeen(pkg: String, label: String) {
        val all = seenApps()
        val entry = all.optJSONObject(pkg) ?: JSONObject()
        entry.put("l", label).put("n", entry.optInt("n") + 1).put("t", System.currentTimeMillis())
        all.put(pkg, entry)
        // Keep the most recently seen apps only.
        val keys = all.keys().asSequence().toList()
        if (keys.size > SEEN_SIZE) {
            keys.sortedBy { all.getJSONObject(it).optLong("t") }.take(keys.size - SEEN_SIZE).forEach { all.remove(it) }
        }
        raw.edit().putString(KEY_SEEN, all.toString()).apply()
    }

    fun recent(): List<LogEntry> {
        val arr = JSONArray(raw.getString(KEY_LOG, "[]"))
        return (0 until arr.length()).map { LogEntry.fromJson(arr.getJSONObject(it)) }
    }

    @Synchronized
    fun addLog(entry: LogEntry) {
        val list = listOf(entry) + recent().take(LOG_SIZE - 1)
        raw.edit().putString(KEY_LOG, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun clearLog() = raw.edit().remove(KEY_LOG).apply()

    private fun string(key: String, default: String) = Pref(
        { raw.getString(key, default) ?: default },
        { raw.edit().putString(key, it).apply() },
    )

    private fun long(key: String, default: Long) = Pref(
        { raw.getLong(key, default) },
        { raw.edit().putLong(key, it).apply() },
    )

    private fun bool(key: String, default: Boolean) = Pref(
        { raw.getBoolean(key, default) },
        { raw.edit().putBoolean(key, it).apply() },
    )

    private class Pref<T>(val get: () -> T, val set: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, property: Any?): T = get()
        operator fun setValue(thisRef: Any?, property: Any?, value: T) = set(value)
    }

    companion object {
        const val KEY_LOG = "log"

        /** Hosted build of miniapp/ (GitHub Pages, see .github/workflows/miniapp.yml). Static, holds no data:
         *  settings travel only in the link the bot sends. Point it at your own deployment if you fork. */
        const val DEFAULT_MINI_APP_URL = "https://resashkin.github.io/notifier/"
        const val KEY_IGNORED = "ignored_packages"
        const val KEY_PAUSED_UNTIL = "paused_until"
        private const val LOG_SIZE = 50
        private const val KEY_SEEN = "seen_apps"
        private const val SEEN_SIZE = 60
        const val DEFAULT_IGNORED = "android\ncom.android.systemui\ncom.google.android.gms\ndev.notifier"
        val DEFAULT_IGNORED_SET = DEFAULT_IGNORED.lines().toSet()
    }
}

data class LogEntry(
    val time: Long,
    val app: String,
    val title: String,
    val text: String,
    val status: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("app", app).put("title", title).put("text", text).put("status", status)

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            o.getLong("time"), o.getString("app"), o.getString("title"), o.getString("text"), o.getString("status"),
        )
    }
}
