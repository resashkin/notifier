package dev.notifier

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import org.json.JSONObject
import java.util.Date

/**
 * Lets the Telegram chat control forwarding: /pause, /resume, /rules, /rule, /digest, /status, /settings,
 * /update, and the settings Mini App's saves. Long-polls getUpdates; only the configured chat is obeyed.
 * Replies in the owner's Telegram language (Russian or English, see [Lang]).
 * Started and stopped with the Start/Stop button in the app (Prefs.botEnabled); forwarding works either way.
 */
class BotController(private val context: Context) {

    private val prefs = Prefs(context)

    /** Bumped on every start/stop so a thread still finishing an old long poll exits instead of looping. */
    @Volatile
    private var generation = 0

    fun start() {
        val gen = ++generation
        running = true
        Thread({ loop(gen) }, "bot-poll").apply { isDaemon = true }.start()
    }

    fun stop() {
        generation++
        running = false
        error = null
    }

    private fun loop(gen: Int) {
        var backoffMs = 5_000L
        var initializedFor = ""
        while (gen == generation) {
            val token = prefs.botToken
            val chat = prefs.chatId
            if (token.isBlank() || chat.isBlank()) {
                Thread.sleep(30_000)
                continue
            }
            try {
                if (initializedFor != token) {
                    prefs.botDisplayName = Telegram.me(token).optString("first_name")
                    Telegram.setCommands(token, commands(Lang.EN))
                    Telegram.setCommands(token, commands(Lang.RU), "ru")
                    initializedFor = token
                }
                val updates = Telegram.getUpdates(token, prefs.updateOffset, 50)
                if (gen != generation) return
                lastPollAt = System.currentTimeMillis()
                error = null
                for (i in 0 until updates.length()) {
                    val update = updates.getJSONObject(i)
                    prefs.updateOffset = update.getLong("update_id") + 1
                    if (gen != generation) return
                    handle(token, chat, update)
                }
                backoffMs = 5_000L
            } catch (e: Exception) {
                // Offline, Doze, or another getUpdates call (409): wait and retry.
                if (gen == generation) error =
                    if (e is TelegramException && e.setupProblem) "Telegram refused: ${e.message} (check the token and chat ID)"
                    else "Can't reach Telegram, retrying: ${e.message ?: e.javaClass.simpleName}"
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(120_000L)
            }
        }
    }

    private fun handle(token: String, chat: String, update: JSONObject) {
        update.optJSONObject("callback_query")?.let { return onButton(token, chat, it) }
        val message = update.optJSONObject("message") ?: return
        if (message.getJSONObject("chat").get("id").toString() != chat) return  // not our owner
        message.optJSONObject("from")?.optString("language_code")?.takeIf { it.isNotBlank() }?.let {
            val lang = Lang.fromTelegram(it)
            if (prefs.lang != lang) prefs.lang = lang
        }
        val t = Lang.of(prefs)
        message.optJSONObject("web_app_data")?.let { return onMiniAppData(token, chat, it.getString("data"), t) }
        val text = message.optString("text").trim()
        if (!text.startsWith("/")) return
        val command = text.substringBefore(' ').substringBefore('@').lowercase()
        val arg = text.substringAfter(' ', "").trim()

        when (command) {
            "/pause" -> {
                val minutes = arg.toIntOrNull()?.takeIf { it > 0 } ?: 60
                prefs.pausedUntil = System.currentTimeMillis() + minutes * 60_000L
                reply(token, chat, t("⏸ Paused until <b>", "⏸ Пауза до <b>") + time(prefs.pausedUntil) + "</b>", Keyboards.resume(t))
            }
            "/resume" -> {
                prefs.pausedUntil = 0
                reply(token, chat, t("▶️ Forwarding resumed", "▶️ Пересылка продолжена"))
            }
            "/rules", "/muted" -> reply(token, chat, rulesText(t), settingsButton(t, open = "rules"))
            "/rule" -> onRule(token, chat, message.optJSONObject("reply_to_message"), t)
            "/digest" -> {
                val waiting = prefs.digest().size
                if (waiting == 0) reply(token, chat, t("📋 Nothing waiting for the digest.", "📋 Для дайджеста ничего нет."))
                else Digest.send(context)
            }
            "/status" -> reply(token, chat, status(t))
            "/update" -> {
                reply(token, chat, t("🔎 Checking ", "🔎 Проверяю ") + escapeHtml(prefs.updateRepo) + t(" for updates…", " на обновления…"))
                val result = try {
                    Updater.check(context, install = true, t = t)
                } catch (e: Exception) {
                    t("Update failed: ", "Не удалось обновить: ") + e.message
                }
                reply(token, chat, escapeHtml(result))
            }
            "/settings" -> {
                val button = settingsButton(t)
                if (button == null) reply(token, chat, noMiniApp(t))
                else reply(token, chat, t("⚙️ Tap <b>Settings</b> below the message field.", "⚙️ Нажмите <b>Настройки</b> под полем ввода."), button)
            }
            else -> reply(token, chat, help(t))
        }
    }

    /** /rule sent as a reply to a forwarded notification: a Mini App button that opens the rule editor with it. */
    private fun onRule(token: String, chat: String, replyTo: JSONObject?, t: Lang) {
        if (replyTo == null) return reply(
            token, chat, t(
                "✏️ Reply with /rule to a forwarded notification to make a rule from it. Or open ⚙️ Settings → Rules and pick one of the recent notifications.",
                "✏️ Ответьте командой /rule на пересланное уведомление, чтобы сделать из него правило. Или откройте ⚙️ Настройки → Правила и выберите одно из недавних.",
            )
        )
        val entry = prefs.logByMessage(replyTo.optLong("message_id"))
            ?: return reply(
                token, chat, t(
                    "I don't have that notification any more: the phone keeps the last ${Prefs.LOG_SIZE}. Pick one in ⚙️ Settings → Rules → Recent.",
                    "Этого уведомления уже нет: телефон хранит последние ${Prefs.LOG_SIZE}. Выберите в ⚙️ Настройки → Правила → Недавние.",
                )
            )
        val link = MiniAppState.link(context, prefs, open = "rule", example = entry) ?: return reply(token, chat, noMiniApp(t))
        val markup = Keyboards.miniApp(t("✏️ New rule", "✏️ Новое правило"), link).put("one_time_keyboard", true).put("is_persistent", false)
        reply(
            token, chat,
            t("✏️ Tap <b>New rule</b> below the message field: the editor opens with this notification.",
                "✏️ Нажмите <b>Новое правило</b> под полем ввода: редактор откроется с этим уведомлением."),
            markup,
        )
    }

    /** Changes saved in the settings Mini App. Replies with a fresh ⚙️ Settings button (new state). */
    private fun onMiniAppData(token: String, chat: String, data: String, t: Lang) {
        val summary = try {
            MiniAppState.apply(context, prefs, data)
        } catch (e: Exception) {
            t("⚠️ Could not apply settings: ", "⚠️ Не удалось применить настройки: ") + escapeHtml(e.message.orEmpty())
        }
        reply(token, chat, t("✅ <b>Settings saved</b>\n", "✅ <b>Настройки сохранены</b>\n") + summary, settingsButton(t))
    }

    private fun onButton(token: String, chat: String, callback: JSONObject) {
        val id = callback.getString("id")
        val message = callback.optJSONObject("message")
        if (message?.getJSONObject("chat")?.get("id")?.toString() != chat) {
            Telegram.answerCallback(token, id, "")
            return
        }
        val t = Lang.of(prefs)
        val messageId = message.getLong("message_id")
        val data = callback.optString("data")
        val pkg = data.substringAfter(':', "")

        when (data.substringBefore(':')) {
            // m, u, l: buttons under messages sent by older versions.
            "m" -> {
                prefs.mute(pkg)
                val app = appLabel(context, pkg)
                Telegram.answerCallback(token, id, t("🔕 $app muted", "🔕 $app: без уведомлений"))
                Telegram.editButtons(token, chat, messageId, Keyboards.unmute(pkg, app, t))
            }
            "u", "l" -> {
                prefs.unmute(pkg)
                Telegram.answerCallback(token, id, t("🔔 ${appLabel(context, pkg)} unmuted", "🔔 ${appLabel(context, pkg)}: снова пересылается"))
                Telegram.editButtons(token, chat, messageId, Keyboards.none())
            }
            "p" -> {
                val minutes = pkg.toIntOrNull() ?: 60
                prefs.pausedUntil = System.currentTimeMillis() + minutes * 60_000L
                Telegram.answerCallback(token, id, t("⏸ Paused until ", "⏸ Пауза до ") + time(prefs.pausedUntil))
            }
            "r" -> {
                prefs.pausedUntil = 0
                Telegram.answerCallback(token, id, t("▶️ Forwarding resumed", "▶️ Пересылка продолжена"))
                Telegram.editButtons(token, chat, messageId, Keyboards.none())
            }
            else -> Telegram.answerCallback(token, id, "")
        }
    }

    private fun settingsButton(t: Lang, open: String? = null): JSONObject? =
        MiniAppState.link(context, prefs, open)?.let { Keyboards.settings(it, t) }

    private fun noMiniApp(t: Lang) = t(
        "Set the Mini App address (https://…) in the Notifier app on the phone first.",
        "Сначала укажите адрес Mini App (https://…) в приложении Notifier на телефоне.",
    )

    /** /rules: the rules by kind, with what each does. */
    private fun rulesText(t: Lang): String {
        val rules = prefs.rules()
        if (rules.isEmpty()) return t(
            "📏 No rules yet. Make one in ⚙️ Settings → Rules, or reply /rule to a forwarded notification.",
            "📏 Правил пока нет. Создайте в ⚙️ Настройки → Правила или ответьте /rule на пересланное уведомление.",
        )
        val app = { pkg: String -> appLabel(context, pkg) }
        fun line(r: Rule, what: String) = buildString {
            append("• ").append(escapeHtml(r.label(app, t)))
            if (what.isNotEmpty()) append(" — ").append(what)
            if (!r.on) append(t(" <i>(off)</i>", " <i>(выкл.)</i>"))
        }
        val sections = listOf(
            Rule.Kind.ALWAYS to t("✅ <b>Always forward</b>", "✅ <b>Всегда пересылать</b>"),
            Rule.Kind.MUTE to t("🔕 <b>Mute</b>", "🔕 <b>Заглушить</b>"),
            Rule.Kind.MAP to t("✏️ <b>Rewrite</b>", "✏️ <b>Переписать</b>"),
        )
        return sections.mapNotNull { (kind, header) ->
            val list = rules.filter { it.kind == kind }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            header + "\n" + list.joinToString("\n") { r ->
                line(r, when (kind) {
                    Rule.Kind.MUTE -> actionName(r.action, t)
                    Rule.Kind.MAP -> mapSummary(r, t)
                    Rule.Kind.ALWAYS -> ""
                })
            }
        }.joinToString("\n\n") + t("\n\nEdit them in ⚙️ Settings below.", "\n\nИзменить — в ⚙️ Настройках ниже.")
    }

    private fun actionName(a: Rule.Action, t: Lang) = when (a) {
        Rule.Action.DROP -> t("drop", "отбросить")
        Rule.Action.SILENT -> t("silent", "без звука")
        Rule.Action.DIGEST -> t("digest", "в дайджест")
    }

    private fun mapSummary(r: Rule, t: Lang) = listOfNotNull(
        r.cut.takeIf { it.isNotEmpty() }?.let { t("cut ${it.size}", "вырезать ${it.size}") },
        t("cut regex", "вырезать regex").takeIf { r.cutRegex.isNotBlank() },
        t("no links", "без ссылок").takeIf { r.links },
        t("template", "шаблон").takeIf { r.parse.isNotBlank() },
    ).joinToString(", ")

    private fun status(t: Lang): String = buildString {
        append(if (prefs.isPaused()) t("⏸ Paused until <b>", "⏸ Пауза до <b>") + time(prefs.pausedUntil) + "</b>"
        else t("▶️ Forwarding is on", "▶️ Пересылка включена"))
        val rules = prefs.rules().filter { it.on }
        val mute = rules.count { it.kind == Rule.Kind.MUTE }
        val always = rules.count { it.kind == Rule.Kind.ALWAYS }
        val map = rules.count { it.kind == Rule.Kind.MAP }
        append("\n📏 ").append(
            if (rules.isEmpty()) t("No rules", "Правил нет")
            else t("Rules: $mute mute · $always always · $map rewrite", "Правила: заглушить $mute · всегда $always · переписать $map")
        )
        val waiting = prefs.digest().size
        if (waiting > 0) append(t("\n📋 Digest: $waiting waiting", "\n📋 В дайджесте: $waiting"))
        append("\n\n<b>").append(escapeHtml("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")).append("</b>\n")
        append(Health.lines(Health.snapshot(context), t).joinToString("\n") { escapeHtml(it) })
    }

    private fun reply(token: String, chat: String, html: String, markup: JSONObject? = null) {
        Telegram.sendMessage(token, chat, html, silent = false, markup = markup)
    }

    private fun time(epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

    companion object {
        /** For the app's Telegram tab: whether the poll loop runs, when Telegram last answered,
         *  and the last error (null once it answers again). */
        @Volatile
        var running = false

        @Volatile
        var lastPollAt = 0L

        @Volatile
        var error: String? = null

        fun commands(t: Lang) = listOf(
            "rules" to t("Mute, always-forward and rewrite rules", "Правила: заглушить, всегда пересылать, переписать"),
            "rule" to t("Reply to a notification: make a rule from it", "Ответом на уведомление: сделать из него правило"),
            "digest" to t("Send the digest now", "Прислать дайджест сейчас"),
            "pause" to t("Pause forwarding (/pause 30 = 30 min, default 1 hour)", "Пауза (/pause 30 = 30 мин, по умолчанию час)"),
            "resume" to t("Resume forwarding", "Продолжить пересылку"),
            "status" to t("Forwarding state and phone health", "Состояние пересылки и телефона"),
            "settings" to t("Open the settings Mini App", "Открыть настройки (Mini App)"),
            "update" to t("Update the Notifier app on the phone", "Обновить Notifier на телефоне"),
        )

        private fun help(t: Lang) = t("Notifier bot", "Бот Notifier") + "\n" +
            commands(t).joinToString("\n") { (c, d) -> "/$c — ${escapeHtml(d)}" }
    }
}

/** User-visible app name, falling back to the package name. */
fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

/** Time of day for today, with the date for older moments. */
fun clock(context: Context, epochMs: Long): String =
    if (DateUtils.isToday(epochMs)) DateFormat.getTimeFormat(context).format(Date(epochMs))
    else DateUtils.formatDateTime(
        context, epochMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
    )

/** Telegram HTML mode only needs these three escaped. */
fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
