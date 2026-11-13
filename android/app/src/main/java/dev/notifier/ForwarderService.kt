package dev.notifier

import android.app.Notification
import android.app.Person
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives every notification posted on the phone (once the user grants notification access)
 * and forwards the useful ones to a Telegram chat, one message per new item (see [Outbox]).
 * While connected it also runs the bot ([BotController]) and watches the internet ([NetworkWatch]).
 */
class ForwarderService : NotificationListenerService() {

    private val bot by lazy { BotController(this) }

    private val network by lazy { NetworkWatch(this) }

    private val power by lazy { PowerWatch(this) }

    /** Looks for a new release now and then; [Updater.autoCheck] does it at most once a day. */
    private val handler = Handler(Looper.getMainLooper())
    private val updateCheck = object : Runnable {
        override fun run() {
            Thread { Updater.autoCheck(this@ForwarderService) }.start()
            handler.postDelayed(this, 6 * 60 * 60_000L)
        }
    }

    /** Start/Stop on the app's Telegram tab. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_BOT_ENABLED) startOrStopBot()
    }

    /** pkg -> last time it was recorded in the seen-apps list (throttles chatty apps). */
    private val seenRecorded = ConcurrentHashMap<String, Long>()

    /** Plain notifications: key -> content last forwarded, so unchanged updates aren't re-sent. */
    private val lastSent = ConcurrentHashMap<String, String>()

    /** Chat messages / inbox lines already forwarded. Kept after dismissal: apps re-post unread history. */
    private val seenItems = BoundedSet(1000)

    override fun onListenerConnected() {
        connected = true
        Prefs(this).raw.registerOnSharedPreferenceChangeListener(prefsListener)
        startOrStopBot()
        network.start()
        power.start()
        Outbox.flush(this)  // anything left in the queue from before a restart
        handler.removeCallbacks(updateCheck)
        handler.postDelayed(updateCheck, 60_000)  // give the network a moment after boot
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
        Prefs(this).raw.unregisterOnSharedPreferenceChangeListener(prefsListener)
        bot.stop()
        network.stop()
        power.stop()
        handler.removeCallbacks(updateCheck)
    }

    private fun startOrStopBot() = if (Prefs(this).botEnabled) bot.start() else bot.stop()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        recordSeen(prefs, sbn)
        if (!prefs.enabled || shouldSkip(sbn, prefs)) return

        val items = newItems(sbn)  // marks them seen even while paused, so nothing floods in on resume
        if (prefs.isPaused()) return

        val app = appLabel(this, sbn.packageName)
        for (item in items) {
            if (isFromBotChat(sbn.packageName, item) || Outbox.isOurOwn(item.text)) continue
            Outbox.forward(this, sbn.packageName, app, item.title, item.text)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSent.remove(sbn.key)
    }

    override fun onDestroy() {
        bot.stop()
        network.stop()
        power.stop()
        handler.removeCallbacks(updateCheck)
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

    private fun shouldSkip(sbn: StatusBarNotification, prefs: Prefs): Boolean {
        val flags = sbn.notification.flags
        return sbn.packageName in prefs.ignoredSet() ||
            (prefs.skipOngoing && (sbn.isOngoing || flags and Notification.FLAG_FOREGROUND_SERVICE != 0)) ||
            (prefs.skipGroupSummary && flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }

    companion object {
        /** Whether Android has the listener bound right now (shown on the settings screen). */
        @Volatile
        var connected = false
    }
}
