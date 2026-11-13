package dev.notifier

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** App settings, a short log of recently forwarded notifications, the queue of notifications waiting
 *  to be sent and recent internet outages, in SharedPreferences. */
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

    /** Start/Stop on the Telegram tab: whether the bot answers commands and buttons. Forwarding doesn't depend on it. */
    var botEnabled by bool(KEY_BOT_ENABLED, true)

    /** When the phone lost internet (epoch ms), 0 while online. Kept across restarts so the outage is still reported. */
    var offlineSince by long(KEY_OFFLINE_SINCE, 0L)

    /** Warn in the chat when the battery drops to [lowBatteryLevel] % on battery. */
    var lowBatteryOn by bool("low_battery_on", true)
    var lowBatteryLevel by int("low_battery_level", 20)

    /** The low-battery warning was sent for the current discharge (reset when charging). */
    var lowBatteryWarned by bool("low_battery_warned", false)

    /** When the charger was unplugged (a warning was sent), 0 otherwise. */
    var unpluggedAt by long("unplugged_at", 0L)

    /** Current discharge session for [BatteryEstimate]: start time and level, 0 while charging. */
    var dischargeStart by long("discharge_start", 0L)
    var dischargeStartLevel by int("discharge_start_level", 0)

    /** Battery drain learned from earlier discharges, % per hour (0 = unknown). */
    var drainRate by float("drain_rate", 0f)

    /** Set from the bot with /pause; forwarding resumes automatically after this time (epoch ms). */
    var pausedUntil by long(KEY_PAUSED_UNTIL, 0L)

    /** Next Telegram getUpdates offset, so bot commands aren't handled twice after a restart. */
    var updateOffset by long("update_offset", 0L)

    /** The bot's display name; notifications from its chat on this phone are never forwarded. */
    var botDisplayName by string("bot_display_name", "")

    /** HTTPS address of the settings Mini App (the Flutter web build); opened from the bot with /settings. */
    var miniAppUrl by string("mini_app_url", DEFAULT_MINI_APP_URL)

    /** GitHub repository ("owner/name") whose releases the app updates itself from. */
    var updateRepo by string("update_repo", DEFAULT_UPDATE_REPO)

    /** Install new releases found by the daily check without waiting for /update. */
    var autoUpdate by bool("auto_update", true)

    var lastUpdateCheck by long("last_update_check", 0L)

    /** Last version announced in the chat, so the daily check mentions it once. */
    var announcedUpdate by string("announced_update", "")

    /** Shown on the Settings tab: "downloading 0.2.0", "failed: …", … */
    var updateStatus by string(KEY_UPDATE_STATUS, "")

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

    fun recordSeen(pkg: String, label: String) = synchronized(LOCK) {
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

    fun recent(): List<LogEntry> = list(KEY_LOG).map(LogEntry::fromJson)

    fun addLog(entry: LogEntry) = synchronized(LOCK) {
        putList(KEY_LOG, (listOf(entry) + recent().take(LOG_SIZE - 1)).map { it.toJson() })
    }

    /** Changes the status of the log entry [id] (a queued notification that was sent later or dropped). */
    fun updateLog(id: String, status: String) = synchronized(LOCK) {
        putList(KEY_LOG, recent().map { if (it.id == id) it.copy(status = status) else it }.map { it.toJson() })
    }

    fun clearLog() = raw.edit().remove(KEY_LOG).apply()

    /** Notifications not delivered yet, oldest first. */
    fun pending(): List<Pending> = list(KEY_PENDING).map(Pending::fromJson)

    /** Adds [p] to the queue; returns the oldest entries dropped to make room. */
    fun enqueue(p: Pending): List<Pending> = synchronized(LOCK) {
        val all = pending() + p
        val dropped = all.take((all.size - PENDING_SIZE).coerceAtLeast(0))
        putList(KEY_PENDING, all.drop(dropped.size).map { it.toJson() })
        dropped
    }

    fun removePending(id: String) = synchronized(LOCK) {
        putList(KEY_PENDING, pending().filter { it.id != id }.map { it.toJson() })
    }

    /** Empties the queue and returns what was in it. */
    fun clearPending(): List<Pending> = synchronized(LOCK) {
        pending().also { raw.edit().remove(KEY_PENDING).apply() }
    }

    /** Recent internet outages, newest first. */
    fun outages(): List<Outage> = list(KEY_OUTAGES).map(Outage::fromJson)

    fun addOutage(o: Outage) = synchronized(LOCK) {
        putList(KEY_OUTAGES, (listOf(o) + outages().take(OUTAGES_SIZE - 1)).map { it.toJson() })
    }

    /** Marks the outage that started at [start] as reported to the chat. */
    fun markReported(start: Long) = synchronized(LOCK) {
        putList(KEY_OUTAGES, outages().map { if (it.start == start) it.copy(reported = true) else it }.map { it.toJson() })
    }

    private fun list(key: String): List<JSONObject> {
        val arr = JSONArray(raw.getString(key, "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun putList(key: String, items: List<JSONObject>) =
        raw.edit().putString(key, JSONArray(items).toString()).apply()

    private fun string(key: String, default: String) = Pref(
        { raw.getString(key, default) ?: default },
        { raw.edit().putString(key, it).apply() },
    )

    private fun long(key: String, default: Long) = Pref(
        { raw.getLong(key, default) },
        { raw.edit().putLong(key, it).apply() },
    )

    private fun int(key: String, default: Int) = Pref(
        { raw.getInt(key, default) },
        { raw.edit().putInt(key, it).apply() },
    )

    private fun float(key: String, default: Float) = Pref(
        { raw.getFloat(key, default) },
        { raw.edit().putFloat(key, it).apply() },
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
        /** Where releases are published (.github/workflows/android-release.yml). Point it at your fork if you have one. */
        const val DEFAULT_UPDATE_REPO = "resashkin/notifier"
        const val KEY_UPDATE_STATUS = "update_status"
        const val KEY_IGNORED = "ignored_packages"
        const val KEY_PAUSED_UNTIL = "paused_until"
        const val KEY_BOT_ENABLED = "bot_enabled"
        const val KEY_OFFLINE_SINCE = "offline_since"
        const val KEY_PENDING = "pending"
        const val KEY_OUTAGES = "outages"
        private const val LOG_SIZE = 50
        private const val PENDING_SIZE = 500
        private const val OUTAGES_SIZE = 20

        /** Prefs objects are created freely; read-modify-write of the JSON lists shares this lock. */
        private val LOCK = Any()
        private const val KEY_SEEN = "seen_apps"
        private const val SEEN_SIZE = 60
        const val DEFAULT_IGNORED = "android\ncom.android.systemui\ncom.google.android.gms\ndev.notifier"
        val DEFAULT_IGNORED_SET = DEFAULT_IGNORED.lines().toSet()
    }
}

/** [status]: "sent", "queued", "sent N min late", "failed: …" or "discarded". [id] links it to its [Pending] entry. */
data class LogEntry(
    val time: Long,
    val app: String,
    val title: String,
    val text: String,
    val status: String,
    val id: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("app", app).put("title", title).put("text", text).put("status", status).put("id", id)

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            o.getLong("time"), o.getString("app"), o.getString("title"), o.getString("text"), o.getString("status"),
            o.optString("id"),
        )
    }
}

/** A notification waiting to be sent; [time] is when it was posted on the phone. */
data class Pending(
    val id: String,
    val time: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("time", time).put("pkg", pkg).put("app", app).put("title", title).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = Pending(
            o.getString("id"), o.getLong("time"), o.getString("pkg"), o.getString("app"), o.getString("title"), o.getString("text"),
        )
    }
}

/** No internet from [start] to [end] (epoch ms); [missed] notifications were queued meanwhile. */
data class Outage(val start: Long, val end: Long, val missed: Int, val reported: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("start", start).put("end", end).put("missed", missed).put("reported", reported)

    companion object {
        fun fromJson(o: JSONObject) = Outage(o.getLong("start"), o.getLong("end"), o.optInt("missed"), o.optBoolean("reported"))
    }
}
