package dev.notifier

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Telegram Bot API client. Blocking: call off the main thread. */
object Telegram {

    /** [markup] is a keyboard (see [Keyboards]) or null. Returns the new message's id. */
    fun sendMessage(token: String, chatId: String, html: String, silent: Boolean, markup: JSONObject? = null): Long {
        val params = mutableMapOf(
            "chat_id" to chatId,
            "text" to html,
            "parse_mode" to "HTML",
            "disable_notification" to silent.toString(),
            "link_preview_options" to """{"is_disabled":true}""",
        )
        if (markup != null) params["reply_markup"] = markup.toString()
        return call(token, "sendMessage", params).optJSONObject("result")?.optLong("message_id") ?: 0L
    }

    /** The bot's own user object (username, first_name); throws if the token is wrong. */
    fun me(token: String): JSONObject = call(token, "getMe", emptyMap()).getJSONObject("result")

    /** Returns "@username" of the bot. */
    fun botName(token: String): String = "@" + me(token).getString("username")

    /** Chat id and display name of the latest chat that messaged the bot, or null. */
    fun latestChat(token: String): Pair<String, String>? {
        val updates = call(token, "getUpdates", emptyMap()).getJSONArray("result")
        for (i in updates.length() - 1 downTo 0) {
            val update = updates.getJSONObject(i)
            val message = update.optJSONObject("message") ?: update.optJSONObject("channel_post") ?: continue
            val chat = message.getJSONObject("chat")
            val name = chat.optString("title").ifEmpty { chat.optString("username") }.ifEmpty { chat.optString("first_name") }
            return chat.get("id").toString() to name
        }
        return null
    }

    /** Long poll: waits up to [timeoutSec] for new messages / button taps. */
    fun getUpdates(token: String, offset: Long, timeoutSec: Int): JSONArray = call(
        token, "getUpdates", mapOf(
            "offset" to offset.toString(),
            "timeout" to timeoutSec.toString(),
            "allowed_updates" to """["message","callback_query"]""",
        ),
        readTimeoutMs = (timeoutSec + 15) * 1000,
    ).getJSONArray("result")

    /** Stops the button's loading spinner and shows [text] as a small toast in Telegram. */
    fun answerCallback(token: String, callbackId: String, text: String) {
        call(token, "answerCallbackQuery", mapOf("callback_query_id" to callbackId, "text" to text))
    }

    fun editButtons(token: String, chatId: String, messageId: Long, markup: JSONObject) {
        call(
            token, "editMessageReplyMarkup", mapOf(
                "chat_id" to chatId,
                "message_id" to messageId.toString(),
                "reply_markup" to markup.toString(),
            )
        )
    }

    /** Fills the bot's "/" menu. [commands] = command (without slash) to description; [language] = for users
     *  whose Telegram is in that language ("ru"), null = everyone else. */
    fun setCommands(token: String, commands: List<Pair<String, String>>, language: String? = null) {
        val json = JSONArray(commands.map { (c, d) -> JSONObject().put("command", c).put("description", d) })
        call(token, "setMyCommands", listOfNotNull("commands" to json.toString(), language?.let { "language_code" to it }).toMap())
    }

    private fun call(
        token: String,
        method: String,
        params: Map<String, String>,
        readTimeoutMs: Int = 15_000,
    ): JSONObject {
        if (token.isBlank()) throw IOException("Bot token is empty")
        val body = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("https://api.telegram.org/bot${token.trim()}/$method").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val json = JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
            if (!json.optBoolean("ok")) throw TelegramException(
                json.optInt("error_code", conn.responseCode),
                json.optString("description", "HTTP ${conn.responseCode}"),
                json.optJSONObject("parameters")?.optInt("retry_after") ?: 0,
            )
            return json
        } finally {
            conn.disconnect()
        }
    }
}

/** An error answered by Telegram itself (as opposed to no connection). */
class TelegramException(val code: Int, description: String, val retryAfterSec: Int) : IOException(description) {
    /** Telegram refuses this particular message (too long, bad markup): retrying it won't help.
     *  "Chat not found" is a 400 too, but that's the setup, not the message. */
    val rejected get() = code == 400 && "chat not found" !in message.orEmpty().lowercase()

    /** Wrong token, bot blocked or chat gone: nothing gets through until the setup is fixed. */
    val setupProblem get() = code == 401 || code == 403 || (code == 400 && !rejected)
}

/** Keyboards. Callback data: "p:<minutes>" pause, "r" resume. Messages sent by older versions carry
 *  "m:<pkg>" mute, "u:<pkg>" unmute and "l:<pkg>" (unmute from the old /muted list); those still work.
 *  Telegram limits callback data to 64 bytes. */
object Keyboards {
    fun unmute(pkg: String, app: String, t: Lang): JSONObject =
        keyboard(listOf(listOfNotNull(button(t("🔔 Unmute $app", "🔔 Включить $app"), "u:$pkg"))))

    fun resume(t: Lang): JSONObject = keyboard(listOf(listOfNotNull(button(t("▶️ Resume now", "▶️ Продолжить"), "r"))))

    fun none(): JSONObject = keyboard(emptyList())

    /** Reply keyboard (below the message field) that opens the settings Mini App at [url]. Mini Apps can only
     *  send data back to the bot (sendData) when opened from this kind of button, not inline buttons. */
    fun miniApp(text: String, url: String): JSONObject = JSONObject()
        .put("keyboard", JSONArray().put(JSONArray().put(JSONObject().put("text", text).put("web_app", JSONObject().put("url", url)))))
        .put("resize_keyboard", true)
        .put("is_persistent", true)

    fun settings(url: String, t: Lang): JSONObject = miniApp(t("⚙️ Settings", "⚙️ Настройки"), url)

    private fun button(text: String, data: String): JSONObject? =
        if (data.toByteArray().size > 64) null else JSONObject().put("text", text).put("callback_data", data)

    private fun keyboard(rows: List<List<JSONObject>>) =
        JSONObject().put("inline_keyboard", JSONArray(rows.filter { it.isNotEmpty() }.map { JSONArray(it) }))
}
