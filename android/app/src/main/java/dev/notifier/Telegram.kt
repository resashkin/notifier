package dev.notifier

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Telegram Bot API client. Blocking: call off the main thread. */
object Telegram {

    /** [markup] is an inline keyboard (see [Keyboards]) or null. */
    fun sendMessage(token: String, chatId: String, html: String, silent: Boolean, markup: JSONObject? = null) {
        val params = mutableMapOf(
            "chat_id" to chatId,
            "text" to html,
            "parse_mode" to "HTML",
            "disable_notification" to silent.toString(),
            "link_preview_options" to """{"is_disabled":true}""",
        )
        if (markup != null) params["reply_markup"] = markup.toString()
        call(token, "sendMessage", params)
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

    /** Fills the bot's "/" menu. [commands] = command (without slash) to description. */
    fun setCommands(token: String, commands: List<Pair<String, String>>) {
        val json = JSONArray(commands.map { (c, d) -> JSONObject().put("command", c).put("description", d) })
        call(token, "setMyCommands", mapOf("commands" to json.toString()))
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
            if (!json.optBoolean("ok")) throw IOException(json.optString("description", "HTTP ${conn.responseCode}"))
            return json
        } finally {
            conn.disconnect()
        }
    }
}

/** Inline keyboards. Callback data: "m:<pkg>" mute, "u:<pkg>" unmute, "l:<pkg>" unmute from /muted list,
 *  "p:<minutes>" pause, "r" resume. Telegram limits callback data to 64 bytes. */
object Keyboards {
    fun forNotification(pkg: String, app: String): JSONObject? {
        val mute = button("🔕 Mute $app", "m:$pkg") ?: return null
        return keyboard(listOf(listOfNotNull(mute, button("⏸ Pause 1h", "p:60"))))
    }

    fun unmute(pkg: String, app: String): JSONObject =
        keyboard(listOf(listOfNotNull(button("🔔 Unmute $app", "u:$pkg"))))

    fun mutedList(apps: List<Pair<String, String>>): JSONObject =
        keyboard(apps.mapNotNull { (pkg, label) -> button("🔔 Unmute $label", "l:$pkg")?.let { listOf(it) } })

    fun resume(): JSONObject = keyboard(listOf(listOfNotNull(button("▶️ Resume now", "r"))))

    fun none(): JSONObject = keyboard(emptyList())

    /** Reply keyboard (below the message field) that opens the settings Mini App. Mini Apps can only
     *  send data back to the bot (sendData) when opened from this kind of button, not inline buttons. */
    fun settings(url: String): JSONObject = JSONObject()
        .put("keyboard", JSONArray().put(JSONArray().put(JSONObject().put("text", "⚙️ Settings").put("web_app", JSONObject().put("url", url)))))
        .put("resize_keyboard", true)
        .put("is_persistent", true)

    private fun button(text: String, data: String): JSONObject? =
        if (data.toByteArray().size > 64) null else JSONObject().put("text", text).put("callback_data", data)

    private fun keyboard(rows: List<List<JSONObject>>) =
        JSONObject().put("inline_keyboard", JSONArray(rows.filter { it.isNotEmpty() }.map { JSONArray(it) }))
}
