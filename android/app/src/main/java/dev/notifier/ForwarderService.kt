package dev.notifier

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Receives every notification posted on the phone (once the user grants notification access)
 * and forwards the useful ones to a Telegram chat, one message per new item.
 */
class ForwarderService : NotificationListenerService() {

    private val sender = Executors.newSingleThreadExecutor()

    private val bot by lazy { BotController(this) }

    /** pkg -> last time it was recorded in the seen-apps list (throttles chatty apps). */
    private val seenRecorded = ConcurrentHashMap<String, Long>()

    /** Plain notifications: key -> content last forwarded, so unchanged updates aren't re-sent. */
    private val lastSent = ConcurrentHashMap<String, String>()

    /** Chat messages / inbox lines already forwarded. Kept after dismissal: apps re-post unread history. */
    private val seenItems = BoundedSet(1000)

    /** Normalized bodies we sent recently. If the bot's chat lives on this phone, its own
     *  Telegram notifications would otherwise be forwarded again, nesting forever. */
    private val ourMessages = BoundedSet(100)

    override fun onListenerConnected() {
        connected = true
        bot.start()
        // Our memory is RAM-only: after a reboot/update, treat what's already in the shade as forwarded
        // so unread history isn't sent again.
        try {
            val prefs = Prefs(this)
            activeNotifications?.forEach {
                recordSeen(prefs, it)  // so the settings Mini App lists these apps right away
                newItems(it)
            }
        } catch (e: Exception) {
            // Not fatal: worst case some already-shown items are forwarded once more.
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        bot.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        recordSeen(prefs, sbn)
        if (!prefs.enabled || shouldSkip(sbn, prefs)) return

        val items = newItems(sbn)  // marks them seen even while paused, so nothing floods in on resume
        if (prefs.isPaused()) return

        val app = appLabel(this, sbn.packageName)
        for (item in items) {
            if (isFromBotChat(sbn.packageName, item) || isOurOwnMessage(item.text)) continue
            sender.execute { forward(prefs, sbn.packageName, sbn.key, app, item) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSent.remove(sbn.key)
    }

    override fun onDestroy() {
        bot.stop()
        sender.shutdown()
        super.onDestroy()
    }

    private data class Item(val title: String, val text: String)

    /** Splits a notification into the items not forwarded yet. */
    private fun newItems(sbn: StatusBarNotification): List<Item> {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""

        // Chat apps (MessagingStyle): one Telegram message per chat message.
        val messages = messagingStyleMessages(extras)
        if (messages.isNotEmpty()) {
            val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
            return messages.filter { seenItems.add("${sbn.packageName}|$title|${it.time}|${it.text}") }
                .map { m ->
                    val text = if (isGroup && m.sender.isNotBlank()) "${m.sender}: ${m.text}" else m.text
                    Item(title, text)
                }
        }

        // Inbox style ("3 new emails"): one Telegram message per new line.
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            return lines.map { it.toString() }.filter { it.isNotBlank() }
                .filter { seenItems.add("${sbn.packageName}|${sbn.key}|$it") }
                .map { Item(title, it) }
        }

        // Plain notification: forward when its content changes.
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        if (title.isBlank() && text.isBlank()) return emptyList()
        val signature = "$title\u0000$text"
        if (lastSent.put(sbn.key, signature) == signature) return emptyList()
        return listOf(Item(title, text))
    }

    private data class Message(val text: String, val time: Long, val sender: String)

    @Suppress("DEPRECATION")
    private fun messagingStyleMessages(extras: Bundle): List<Message> {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return bundles.mapNotNull { it as? Bundle }.mapNotNull { b ->
            val text = b.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sender = b.getCharSequence("sender")?.toString()
                ?: b.getParcelable<Person>("sender_person")?.name?.toString()
                ?: ""
            Message(text, b.getLong("time"), sender)
        }
    }

    private fun forward(prefs: Prefs, pkg: String, key: String, app: String, item: Item) {
        // Register before sending: Telegram may deliver the bot's message here before the API call returns.
        ourMessages.add(normalize(plain(prefs, app, item)))
        val status = try {
            sendWithRetry(prefs, format(prefs, app, item), Keyboards.forNotification(pkg, app))
            "sent"
        } catch (e: Exception) {
            lastSent.remove(key)  // allow a later update to retry
            "failed: ${e.message}"
        }
        prefs.addLog(LogEntry(System.currentTimeMillis(), app, item.title, item.text, status))
    }

    /** Remembers which apps post notifications, for the per-app switches in the settings Mini App. */
    private fun recordSeen(prefs: Prefs, sbn: StatusBarNotification) {
        if (sbn.isOngoing || sbn.packageName in Prefs.DEFAULT_IGNORED_SET) return
        val now = System.currentTimeMillis()
        val last = seenRecorded[sbn.packageName] ?: 0L
        if (now - last < 30_000) return
        seenRecorded[sbn.packageName] = now
        prefs.recordSeen(sbn.packageName, appLabel(this, sbn.packageName))
    }

    /** The bot's chat as it appears in a Telegram app on this phone: its replies must not loop back. */
    private fun isFromBotChat(pkg: String, item: Item): Boolean {
        val botName = Prefs(this).botDisplayName
        val telegramApp = "telegram" in pkg || "challegram" in pkg
        return telegramApp && botName.isNotBlank() && item.title == botName
    }

    /** True if [text] is (part of) a message this app sent, seen again as a Telegram notification. */
    private fun isOurOwnMessage(text: String): Boolean {
        val n = normalize(text)
        return n.isNotEmpty() && ourMessages.any { it == n || n.endsWith(it) }
    }

    private fun shouldSkip(sbn: StatusBarNotification, prefs: Prefs): Boolean {
        val flags = sbn.notification.flags
        return sbn.packageName in prefs.ignoredSet() ||
            (prefs.skipOngoing && (sbn.isOngoing || flags and Notification.FLAG_FOREGROUND_SERVICE != 0)) ||
            (prefs.skipGroupSummary && flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }

    private fun sendWithRetry(prefs: Prefs, html: String, markup: JSONObject?) {
        var delayMs = 2_000L
        repeat(3) { attempt ->
            try {
                Telegram.sendMessage(prefs.botToken, prefs.chatId, html, prefs.silent, markup)
                return
            } catch (e: Exception) {
                if (attempt == 2) throw e
                Thread.sleep(delayMs)
                delayMs *= 5
            }
        }
    }

    private fun format(prefs: Prefs, app: String, item: Item): String = buildString {
        if (prefs.showApp) append("<i>").append(escapeHtml(app)).append("</i>\n")
        if (item.title.isNotBlank()) append("<b>").append(escapeHtml(item.title)).append("</b>\n")
        append(escapeHtml(item.text))
    }.trim()

    /** The message as Telegram displays it (and as it reappears in Telegram's own notification). */
    private fun plain(prefs: Prefs, app: String, item: Item): String =
        listOfNotNull(app.takeIf { prefs.showApp }, item.title.takeIf { it.isNotBlank() }, item.text)
            .joinToString("\n")

    private fun normalize(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** Thread-safe insertion-ordered set that forgets its oldest entries. add() returns false if present. */
    private class BoundedSet(private val max: Int) {
        private val items = LinkedHashSet<String>()

        @Synchronized
        fun add(item: String): Boolean {
            if (!items.add(item)) return false
            if (items.size > max) items.remove(items.first())
            return true
        }

        @Synchronized
        fun any(predicate: (String) -> Boolean): Boolean = items.any(predicate)
    }

    companion object {
        /** Whether Android has the listener bound right now (shown on the settings screen). */
        @Volatile
        var connected = false
    }
}
