package dev.notifier

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** App settings, the rules, a short log of recent notifications, the queue of notifications waiting
 *  to be sent, the next digest and recent internet outages, in SharedPreferences. */
class Prefs(context: Context) {
    val raw: SharedPreferences = context.getSharedPreferences("notifier", Context.MODE_PRIVATE)

    var botToken by string("bot_token", "")
    var chatId by string("chat_id", "")
    var enabled by bool("enabled", false)
    var skipOngoing by bool("skip_ongoing", true)
    var skipGroupSummary by bool("skip_group_summary", true)
    var showApp by bool("show_app", true)
    var silent by bool("silent", false)

    /** Before rules: apps muted from the bot, one package per line. Read once to migrate (see [rules]). */
    private var ignoredPackages by string(KEY_IGNORED, DEFAULT_IGNORED)

    /** Language of the bot's messages, "ru" or "en": the owner's Telegram language, seen in their messages. */
    var lang by string(KEY_LANG, if (Locale.getDefault().language == "ru") "ru" else "en")

    /** When digest notifications are sent, "HH:MM" comma-separated (see [Digest]). */
    var digestTimes by string(KEY_DIGEST_TIMES, DEFAULT_DIGEST_TIMES)

    /** When the last digest went out (or its time passed with nothing to send). */
    var lastDigestAt by long("last_digest_at", 0L)

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

    /**
     * Mute, exception and map rules (Rules.kt). The phone is the source of truth; the Mini App keeps a copy
     * and syncs by revision ([rulesRev], see MiniAppState). The first call turns apps muted before rules
     * existed into one "drop" rule per app.
     */
    fun rules(): List<Rule> {
        if (!raw.contains(KEY_RULES)) migrate()
        val json = raw.getString(KEY_RULES, "[]") ?: "[]"
        cachedRules?.let { (source, list) -> if (source == json) return list }
        return Rule.listFromJson(json).also { cachedRules = json to it }
    }

    /** Revision of [rules], a random string that changes with every edit. */
    val rulesRev: String get() = rules().let { raw.getString(KEY_RULES_REV, "") ?: "" }

    /** Replaces the rules (read-modify-write under the shared lock); returns the new list. */
    fun editRules(rev: String = newRev(), change: (List<Rule>) -> List<Rule>): List<Rule> = synchronized(LOCK) {
        val updated = change(rules())
        raw.edit().putString(KEY_RULES, Rule.listToJson(updated)).putString(KEY_RULES_REV, rev).apply()
        updated
    }

    /** Mutes [pkg] with a drop rule (the Mute button under messages sent by older versions). */
    fun mute(pkg: String) = editRules { rules ->
        if (rules.any { it.on && it.isAppMute && pkg in it.apps }) rules
        else rules + Rule(kind = Rule.Kind.MUTE, apps = listOf(pkg), action = Rule.Action.DROP)
    }

    /** Takes [pkg] out of the app-only drop rules; a rule left without apps goes. */
    fun unmute(pkg: String) = editRules { rules ->
        rules.mapNotNull { r ->
            if (!r.isAppMute || pkg !in r.apps) r
            else r.copy(apps = r.apps - pkg).takeIf { it.apps.isNotEmpty() }
        }
    }

    /** Apps with an active app-only drop rule (the per-app switches). */
    fun mutedApps(): List<String> = rules().filter { it.on && it.isAppMute }.flatMap { it.apps }.distinct()

    private fun migrate() = synchronized(LOCK) {
        if (raw.contains(KEY_RULES)) return@synchronized
        val muted = ignoredPackages.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in DEFAULT_IGNORED_SET }
        val rules = muted.distinct().map { Rule(kind = Rule.Kind.MUTE, apps = listOf(it), action = Rule.Action.DROP) }
        raw.edit().putString(KEY_RULES, Rule.listToJson(rules)).putString(KEY_RULES_REV, newRev())
            .remove(KEY_IGNORED).apply()
    }

    /** The rules the Mini App is known to have (it confirmed them when it last saved), and their revision;
     *  "" = unknown, the next link carries all rules. */
    fun miniAppRules(): Pair<String, List<Rule>> =
        (raw.getString(KEY_SNAP_REV, "") ?: "") to Rule.listFromJson(raw.getString(KEY_SNAP, "[]") ?: "[]")

    fun setMiniAppRules(rev: String, rules: List<Rule>) =
        raw.edit().putString(KEY_SNAP_REV, rev).putString(KEY_SNAP, Rule.listToJson(rules)).apply()

    /** Rules as they were put in recent Mini App links, by revision: a save from the Mini App names the
     *  revision it started from, and the phone may have moved on since. */
    fun linkedRules(rev: String): List<Rule>? {
        if (rev == rulesRev) return rules()
        val all = JSONArray(raw.getString(KEY_LINKED, "[]"))
        return (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("rev") == rev }
            ?.let { Rule.listFromJson(it.optString("rules")) }
    }

    /**
     * Rule changes saved in the Mini App: [diff] made on top of revision [base] (the one in its link), saved
     * there as [rev]. If the phone still has [base], the result is taken as is; if the rules were edited on
     * the phone meanwhile, the changes are applied on top of those edits. Either way the phone records what
     * the Mini App now has, so the next link only carries what differs from it.
     */
    fun applyMiniAppRules(base: String, rev: String, diff: RulesDiff) = synchronized(LOCK) {
        val baseRules = linkedRules(base)
        val newRev = rev.ifBlank { newRev() }
        when {
            diff.isEmpty -> {}
            base == rulesRev -> editRules(newRev) { diff.applyTo(it) }
            else -> editRules { diff.applyTo(it) }
        }
        if (baseRules != null) setMiniAppRules(newRev, diff.applyTo(baseRules))
        else setMiniAppRules("", emptyList())  // can't tell what it has: send everything next time
    }

    fun rememberLinked(rev: String, rules: List<Rule>) = synchronized(LOCK) {
        val all = JSONArray(raw.getString(KEY_LINKED, "[]"))
        val kept = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.optString("rev") != rev }
        val entry = JSONObject().put("rev", rev).put("rules", Rule.listToJson(rules))
        raw.edit().putString(KEY_LINKED, JSONArray(listOf(entry) + kept.take(LINKED_SIZE - 1)).toString()).apply()
    }

    /** Notifications kept for the next digest, oldest first. */
    fun digest(): List<DigestItem> = list(KEY_DIGEST).map(DigestItem::fromJson)

    fun addDigest(item: DigestItem) = synchronized(LOCK) {
        putList(KEY_DIGEST, (digest() + item).takeLast(DIGEST_SIZE).map { it.toJson() })
    }

    /** Empties the digest and returns what was in it. */
    fun takeDigest(): List<DigestItem> = synchronized(LOCK) {
        digest().also { raw.edit().remove(KEY_DIGEST).apply() }
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

    /** Dropped notifications are logged too, but only the latest [DROPPED_SIZE] of them, so a muted chatty
     *  app can't push everything else out. */
    fun addLog(entry: LogEntry) = synchronized(LOCK) {
        var dropped = 0
        val kept = (listOf(entry) + recent()).filter { it.action != Rule.Action.DROP.key || ++dropped <= DROPPED_SIZE }
        putList(KEY_LOG, kept.take(LOG_SIZE).map { it.toJson() })
    }

    /** Changes the status of the log entry [id] (a queued notification that was sent later or dropped). */
    fun updateLog(id: String, status: String, mid: Long = 0) = synchronized(LOCK) {
        putList(KEY_LOG, recent().map {
            if (it.id == id) it.copy(status = status, mid = if (mid != 0L) mid else it.mid) else it
        }.map { it.toJson() })
    }

    /** The logged notification that became Telegram message [mid] (for /rule as a reply). */
    fun logByMessage(mid: Long): LogEntry? = recent().firstOrNull { it.mid == mid }

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
        const val KEY_RULES = "rules"
        const val KEY_LANG = "lang"
        const val KEY_DIGEST_TIMES = "digest_times"
        const val DEFAULT_DIGEST_TIMES = "09:00,13:00,19:00"
        private const val KEY_RULES_REV = "rules_rev"
        private const val KEY_SNAP = "miniapp_rules"
        private const val KEY_SNAP_REV = "miniapp_rules_rev"
        private const val KEY_LINKED = "linked_rules"
        private const val LINKED_SIZE = 5
        private const val KEY_DIGEST = "digest"
        private const val DIGEST_SIZE = 300
        const val LOG_SIZE = 60
        private const val DROPPED_SIZE = 20
        private const val PENDING_SIZE = 500
        private const val OUTAGES_SIZE = 20

        /** Prefs objects are created freely; read-modify-write of the JSON lists shares this lock. */
        private val LOCK = Any()
        private const val KEY_SEEN = "seen_apps"
        private const val SEEN_SIZE = 60
        const val DEFAULT_IGNORED = "android\ncom.android.systemui\ncom.google.android.gms\ndev.notifier"
        val DEFAULT_IGNORED_SET = DEFAULT_IGNORED.lines().toSet()

        fun newRev(): String = Rule.newId()

        /** Parsed [rules] for the JSON they came from; parsing on every notification would be wasteful. */
        @Volatile
        private var cachedRules: Pair<String, List<Rule>>? = null
    }
}

/**
 * A notification and what happened to it. [title]/[text] are the original (rules match those);
 * [outTitle]/[outText] what a map rule turned them into, empty if unchanged.
 * [status]: "sent", "queued", "sent N min late", "failed: …", "discarded", "dropped", "in digest" or "sent in digest".
 * [action]: the mute action that applied (Rule.Action key) or "", and [rule] the label of the rule that decided.
 * [id] links it to its [Pending] entry, [mid] to the Telegram message it became.
 */
data class LogEntry(
    val time: Long,
    val app: String,
    val title: String,
    val text: String,
    val status: String,
    val id: String = "",
    val pkg: String = "",
    val mid: Long = 0,
    val action: String = "",
    val rule: String = "",
    val outTitle: String = "",
    val outText: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("app", app).put("title", title).put("text", text).put("status", status).put("id", id)
        .put("pkg", pkg).put("mid", mid).put("action", action).put("rule", rule)
        .put("outTitle", outTitle).put("outText", outText)

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            o.getLong("time"), o.getString("app"), o.getString("title"), o.getString("text"), o.getString("status"),
            o.optString("id"), o.optString("pkg"), o.optLong("mid"), o.optString("action"), o.optString("rule"),
            o.optString("outTitle"), o.optString("outText"),
        )
    }
}

/** A notification kept for the next digest: [title]/[text] as they'll appear (after the mapper). */
data class DigestItem(val time: Long, val pkg: String, val app: String, val title: String, val text: String, val id: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("pkg", pkg).put("app", app).put("title", title).put("text", text).put("id", id)

    companion object {
        fun fromJson(o: JSONObject) = DigestItem(
            o.getLong("time"), o.optString("pkg"), o.optString("app"), o.optString("title"), o.optString("text"), o.optString("id"),
        )
    }
}

/** A notification waiting to be sent; [time] is when it was posted on the phone. [silent]: a mute rule said so.
 *  [html]: a message Notifier composed itself (the digest), sent as is instead of title and text. */
data class Pending(
    val id: String,
    val time: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val silent: Boolean = false,
    val html: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("time", time).put("pkg", pkg).put("app", app).put("title", title).put("text", text)
        .put("silent", silent).put("html", html)

    companion object {
        fun fromJson(o: JSONObject) = Pending(
            o.getString("id"), o.getLong("time"), o.getString("pkg"), o.getString("app"), o.getString("title"), o.getString("text"),
            o.optBoolean("silent"), o.optString("html"),
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
