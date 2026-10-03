package dev.notifier

import android.content.Context
import android.text.format.DateFormat
import org.json.JSONObject
import java.util.Date

/**
 * Lets the Telegram chat control forwarding: the Mute/Pause buttons under forwarded messages and
 * /pause, /resume, /muted, /status. Long-polls getUpdates; only the configured chat is obeyed.
 */
class BotController(private val context: Context) {

    private val prefs = Prefs(context)

    /** Bumped on every start/stop so a thread still finishing an old long poll exits instead of looping. */
    @Volatile
    private var generation = 0

    fun start() {
        val gen = ++generation
        Thread({ loop(gen) }, "bot-poll").apply { isDaemon = true }.start()
    }

    fun stop() {
        generation++
    }

    private fun loop(gen: Int) {
        var backoffMs = 5_000L
        var initialized = false
        while (gen == generation) {
            val token = prefs.botToken
            val chat = prefs.chatId
            if (!prefs.enabled || token.isBlank() || chat.isBlank()) {
                Thread.sleep(30_000)
                continue
            }
            try {
                if (!initialized) {
                    prefs.botDisplayName = Telegram.me(token).optString("first_name")
                    Telegram.setCommands(token, COMMANDS)
                    initialized = true
                }
                val updates = Telegram.getUpdates(token, prefs.updateOffset, 50)
                for (i in 0 until updates.length()) {
                    val update = updates.getJSONObject(i)
                    prefs.updateOffset = update.getLong("update_id") + 1
                    if (gen != generation) return
                    handle(token, chat, update)
                }
                backoffMs = 5_000L
            } catch (e: Exception) {
                // Offline, Doze, or another getUpdates call (409): wait and retry.
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(120_000L)
            }
        }
    }

    private fun handle(token: String, chat: String, update: JSONObject) {
        update.optJSONObject("callback_query")?.let { return onButton(token, chat, it) }
        val message = update.optJSONObject("message") ?: return
        if (message.getJSONObject("chat").get("id").toString() != chat) return  // not our owner
        message.optJSONObject("web_app_data")?.let { return onMiniAppData(token, chat, it.getString("data")) }
        val text = message.optString("text").trim()
        if (!text.startsWith("/")) return
        val command = text.substringBefore(' ').substringBefore('@').lowercase()
        val arg = text.substringAfter(' ', "").trim()

        when (command) {
            "/pause" -> {
                val minutes = arg.toIntOrNull()?.takeIf { it > 0 } ?: 60
                prefs.pausedUntil = System.currentTimeMillis() + minutes * 60_000L
                reply(token, chat, "⏸ Paused until <b>${time(prefs.pausedUntil)}</b>", Keyboards.resume())
            }
            "/resume" -> {
                prefs.pausedUntil = 0
                reply(token, chat, "▶️ Forwarding resumed")
            }
            "/muted" -> {
                val muted = prefs.mutedApps().map { it to appLabel(context, it) }
                if (muted.isEmpty()) reply(token, chat, "No muted apps.")
                else reply(token, chat, "🔕 Muted apps — tap to unmute:", Keyboards.mutedList(muted))
            }
            "/status" -> reply(token, chat, status())
            "/settings" -> {
                val link = MiniAppState.link(context, prefs)
                if (link == null) reply(token, chat, "Set the Mini App address (https://…) in the Notifier app on the phone first.")
                else reply(token, chat, "⚙️ Tap <b>Settings</b> below the message field.", Keyboards.settings(link))
            }
            else -> reply(token, chat, HELP)
        }
    }

    /** Changes saved in the settings Mini App. Replies with a fresh ⚙️ Settings button (new state). */
    private fun onMiniAppData(token: String, chat: String, data: String) {
        val summary = try {
            MiniAppState.apply(context, prefs, data)
        } catch (e: Exception) {
            "⚠️ Could not apply settings: ${escapeHtml(e.message.orEmpty())}"
        }
        val link = MiniAppState.link(context, prefs)
        reply(token, chat, "✅ <b>Settings saved</b>\n$summary", link?.let(Keyboards::settings))
    }

    private fun onButton(token: String, chat: String, callback: JSONObject) {
        val id = callback.getString("id")
        val message = callback.optJSONObject("message")
        if (message?.getJSONObject("chat")?.get("id")?.toString() != chat) {
            Telegram.answerCallback(token, id, "")
            return
        }
        val messageId = message.getLong("message_id")
        val data = callback.optString("data")
        val pkg = data.substringAfter(':', "")

        when (data.substringBefore(':')) {
            "m" -> {
                prefs.mute(pkg)
                val app = appLabel(context, pkg)
                Telegram.answerCallback(token, id, "🔕 $app muted")
                Telegram.editButtons(token, chat, messageId, Keyboards.unmute(pkg, app))
            }
            "u" -> {
                prefs.unmute(pkg)
                val app = appLabel(context, pkg)
                Telegram.answerCallback(token, id, "🔔 $app unmuted")
                Telegram.editButtons(token, chat, messageId, Keyboards.forNotification(pkg, app) ?: Keyboards.none())
            }
            "l" -> {
                prefs.unmute(pkg)
                Telegram.answerCallback(token, id, "🔔 ${appLabel(context, pkg)} unmuted")
                val muted = prefs.mutedApps().map { it to appLabel(context, it) }
                Telegram.editButtons(token, chat, messageId, Keyboards.mutedList(muted))
            }
            "p" -> {
                val minutes = pkg.toIntOrNull() ?: 60
                prefs.pausedUntil = System.currentTimeMillis() + minutes * 60_000L
                Telegram.answerCallback(token, id, "⏸ Paused until ${time(prefs.pausedUntil)}")
            }
            "r" -> {
                prefs.pausedUntil = 0
                Telegram.answerCallback(token, id, "▶️ Forwarding resumed")
                Telegram.editButtons(token, chat, messageId, Keyboards.none())
            }
            else -> Telegram.answerCallback(token, id, "")
        }
    }

    private fun status(): String = buildString {
        append(if (prefs.isPaused()) "⏸ Paused until <b>${time(prefs.pausedUntil)}</b>" else "▶️ Forwarding is on")
        val muted = prefs.mutedApps()
        append("\n🔕 Muted apps: ").append(if (muted.isEmpty()) "none" else muted.size.toString())
        append("\n📱 ").append(escapeHtml("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
    }

    private fun reply(token: String, chat: String, html: String, markup: JSONObject? = null) =
        Telegram.sendMessage(token, chat, html, silent = false, markup = markup)

    private fun time(epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

    companion object {
        val COMMANDS = listOf(
            "pause" to "Pause forwarding (/pause 30 = 30 min, default 1 hour)",
            "resume" to "Resume forwarding",
            "muted" to "List muted apps",
            "status" to "Show forwarding status",
            "settings" to "Open the settings Mini App",
        )

        private const val HELP = "Notifier bot\n" +
            "🔕 Tap <b>Mute</b> under a message to stop that app.\n" +
            "/pause [minutes] — pause forwarding\n" +
            "/resume — resume\n" +
            "/muted — muted apps\n" +
            "/status — current state\n" +
            "/settings — open settings (Mini App)"
    }
}

/** User-visible app name, falling back to the package name. */
fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

/** Telegram HTML mode only needs these three escaped. */
fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
