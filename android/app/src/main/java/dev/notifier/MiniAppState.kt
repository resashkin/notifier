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
 * Keep in sync with miniapp/lib/model.dart.
 */
object MiniAppState {

    /** Telegram doesn't document a URL limit for buttons; stay well within common 2 KB limits. */
    private const val MAX_ENCODED = 1800
    private val PACKAGE = Regex("^[A-Za-z0-9_.]{1,200}$")

    /** Link for the ⚙️ Settings button, or null if the Mini App URL isn't configured. */
    fun link(context: Context, prefs: Prefs): String? {
        val base = prefs.miniAppUrl.trim().takeIf { it.startsWith("https://") } ?: return null
        return base + (if ('?' in base) "&" else "?") + "s=" + encode(context, prefs)
    }

    private fun encode(context: Context, prefs: Prefs): String {
        val muted = prefs.mutedApps().toSet()
        val seen = prefs.seenApps()
        val apps = seen.keys().asSequence().map { pkg ->
            val s = seen.getJSONObject(pkg)
            app(pkg, s.optString("l", pkg), pkg in muted, s.optInt("n"), s.optLong("t"))
        }.toMutableList()
        // Muted apps are always listed, so they can be unmuted even if they've gone quiet.
        (muted - seen.keys().asSequence().toSet()).forEach { apps += app(it, appLabel(context, it), true, 0, 0) }
        apps.sortByDescending { it.optLong("t") }
        val health = Health.snapshot(context)

        while (true) {
            val encoded = gzipBase64(state(prefs, apps, health).toString())
            if (encoded.length <= MAX_ENCODED) return encoded
            // Too long: drop the least recently seen unmuted app.
            val drop = apps.indexOfLast { it.optInt("m") == 0 }
            if (drop < 0) return encoded
            apps.removeAt(drop)
        }
    }

    private fun app(pkg: String, label: String, muted: Boolean, count: Int, lastSeen: Long) = JSONObject()
        .put("p", pkg).put("l", label).put("m", if (muted) 1 else 0).put("n", count).put("t", lastSeen)

    private fun state(prefs: Prefs, apps: List<JSONObject>, health: JSONObject) = JSONObject()
        .put("v", 1)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("ts", System.currentTimeMillis())
        .put("paused_until", prefs.pausedUntil)
        .put("skip_ongoing", prefs.skipOngoing)
        .put("skip_group_summary", prefs.skipGroupSummary)
        .put("show_app", prefs.showApp)
        .put("silent", prefs.silent)
        .put("apps", JSONArray(apps))
        .put("health", health)

    private fun gzipBase64(text: String): String {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        return Base64.encodeToString(bytes.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Applies the changes sent by the Mini App; returns a summary for the bot's reply (HTML). */
    fun apply(context: Context, prefs: Prefs, data: String): String {
        val changes = JSONObject(data)
        if (changes.optInt("v") != 1) return "⚠️ Unknown settings format — update the Android app."
        val lines = mutableListOf<String>()

        fun packages(key: String) = changes.optJSONArray(key)?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.filter { PACKAGE.matches(it) }
        }.orEmpty()

        packages("mute").takeIf { it.isNotEmpty() }?.let { list ->
            list.forEach(prefs::mute)
            lines += "🔕 Muted: " + list.joinToString { escapeHtml(appLabel(context, it)) }
        }
        packages("unmute").takeIf { it.isNotEmpty() }?.let { list ->
            list.forEach(prefs::unmute)
            lines += "🔔 Unmuted: " + list.joinToString { escapeHtml(appLabel(context, it)) }
        }

        if (changes.has("pause_minutes")) {
            val minutes = changes.getInt("pause_minutes").coerceIn(1, 7 * 24 * 60)
            prefs.pausedUntil = System.currentTimeMillis() + minutes * 60_000L
            lines += "⏸ Paused until " + DateFormat.getTimeFormat(context).format(Date(prefs.pausedUntil))
        } else if (changes.optBoolean("resume")) {
            prefs.pausedUntil = 0
            lines += "▶️ Forwarding resumed"
        }

        val rules = listOf(
            "skip_ongoing" to { v: Boolean -> prefs.skipOngoing = v },
            "skip_group_summary" to { v: Boolean -> prefs.skipGroupSummary = v },
            "show_app" to { v: Boolean -> prefs.showApp = v },
            "silent" to { v: Boolean -> prefs.silent = v },
        ).filter { (key, set) -> changes.has(key).also { if (it) set(changes.getBoolean(key)) } }
        if (rules.isNotEmpty()) lines += "⚙️ Rules updated"

        return lines.joinToString("\n").ifEmpty { "Nothing changed." }
    }
}
