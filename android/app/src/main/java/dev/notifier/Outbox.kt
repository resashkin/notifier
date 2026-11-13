package dev.notifier

import android.content.Context
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Sends forwarded notifications to Telegram, one at a time on a single thread. What can't be delivered
 * (no internet, Telegram unreachable) waits in a persistent queue and is resent oldest first, marked with
 * its original time, when the internet comes back, on "Retry now", or by a backoff timer.
 * A finished internet outage is reported to the chat before the notifications it delayed.
 */
object Outbox {

    private val executor = Executors.newSingleThreadScheduledExecutor()

    /** Only touched on [executor]. */
    private var retry: ScheduledFuture<*>? = null
    private var retryDelayMs = RETRY_MIN_MS

    /** Normalized bodies we sent recently. If the bot's chat lives on this phone, its own
     *  Telegram notifications would otherwise be forwarded again, nesting forever. */
    private val ourMessages = BoundedSet(100)

    fun forward(context: Context, pkg: String, app: String, title: String, text: String) {
        val ctx = context.applicationContext
        val p = Pending(UUID.randomUUID().toString(), System.currentTimeMillis(), pkg, app, title, text)
        executor.execute {
            val prefs = Prefs(ctx)
            if (prefs.botToken.isBlank() || prefs.chatId.isBlank()) return@execute log(prefs, p, "failed: Telegram bot not set up")
            // Earlier notifications still waiting, or an outage not reported yet: queue to keep the order.
            if (prefs.pending().isEmpty() && prefs.offlineSince == 0L) {
                try {
                    // Offline: one quick attempt, then the queue; no point in waiting a minute for retries.
                    deliver(ctx, prefs, p, late = false, attempts = if (Health.online(ctx)) 3 else 1)
                    return@execute log(prefs, p, "sent")
                } catch (e: TelegramException) {
                    if (e.rejected) return@execute log(prefs, p, "failed: ${e.message}")
                } catch (e: Exception) {
                    // Network error: queue it.
                }
            }
            queue(ctx, prefs, p)
        }
    }

    /** A message from Notifier itself (charger unplugged, …): queued like a notification, without app name or buttons. */
    fun notice(context: Context, title: String, text: String) =
        forward(context, context.packageName, "Notifier", title, text)

    /** Resends the queue now (internet is back, or "Retry now"). */
    fun flush(context: Context) {
        val ctx = context.applicationContext
        executor.execute {
            retryDelayMs = RETRY_MIN_MS
            flushQueue(ctx)
        }
    }

    /** Drops everything waiting in the queue. */
    fun discard(context: Context) {
        val ctx = context.applicationContext
        executor.execute {
            val prefs = Prefs(ctx)
            prefs.clearPending().forEach { prefs.updateLog(it.id, "discarded") }
            OfflineAlert.refresh(ctx)
        }
    }

    /** Ends the outage that started at offlineSince, before anything is resent, so its report counts the
     *  delayed notifications and comes first. Short blips that delayed nothing aren't worth a message. */
    private fun closeOutage(ctx: Context, prefs: Prefs, start: Long) {
        val end = System.currentTimeMillis()
        val missed = prefs.pending().count { it.time >= start }
        if (end - start >= OUTAGE_MIN_MS || missed > 0) prefs.addOutage(Outage(start, end, missed, reported = false))
        prefs.offlineSince = 0
        OfflineAlert.cancel(ctx)
    }

    /** True if [text] is (part of) a message this app sent, seen again as a Telegram notification. */
    fun isOurOwn(text: String): Boolean {
        val n = normalize(text)
        return n.isNotEmpty() && ourMessages.any { it == n || n.endsWith(it) }
    }

    private fun queue(ctx: Context, prefs: Prefs, p: Pending) {
        prefs.enqueue(p).forEach { prefs.updateLog(it.id, "failed: queue full") }
        log(prefs, p, "queued")
        OfflineAlert.refresh(ctx)
        if (retry == null) scheduleRetry(ctx)
    }

    private fun flushQueue(ctx: Context) {
        retry?.cancel(false)
        retry = null
        val prefs = Prefs(ctx)
        if (prefs.botToken.isBlank() || prefs.chatId.isBlank()) return
        val offlineSince = prefs.offlineSince
        if (offlineSince != 0L) {
            // A network shows up before Android has checked it reaches the internet; NetworkWatch flushes
            // again once it has. Sending now would deliver the delayed messages before the outage report.
            if (!Health.online(ctx)) return
            closeOutage(ctx, prefs, offlineSince)
        }
        try {
            for (o in prefs.outages().filter { !it.reported }.sortedBy { it.start }) {
                Telegram.sendMessage(prefs.botToken, prefs.chatId, outageReport(ctx, o), prefs.silent)
                prefs.markReported(o.start)
            }
            for (p in prefs.pending()) {
                try {
                    deliver(ctx, prefs, p, late = true, attempts = 1)
                    prefs.removePending(p.id)
                    val lateMin = (System.currentTimeMillis() - p.time) / 60_000
                    prefs.updateLog(p.id, if (lateMin < 1) "sent" else "sent ${Health.duration(lateMin)} late")
                } catch (e: TelegramException) {
                    if (!e.rejected) throw e
                    prefs.removePending(p.id)
                    prefs.updateLog(p.id, "failed: ${e.message}")
                }
                Thread.sleep(SEND_GAP_MS)  // Telegram allows about one message per second to a chat
            }
            retryDelayMs = RETRY_MIN_MS
        } catch (e: Exception) {
            scheduleRetry(ctx)
        } finally {
            OfflineAlert.refresh(ctx)
        }
    }

    private fun scheduleRetry(ctx: Context) {
        retry = executor.schedule({ flushQueue(ctx) }, retryDelayMs, TimeUnit.MILLISECONDS)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    /** Sends [p], retrying network errors up to [attempts] times. Late ones go silently: after an
     *  outage the report message rings once, not every notification behind it. */
    private fun deliver(ctx: Context, prefs: Prefs, p: Pending, late: Boolean, attempts: Int) {
        val own = p.pkg == ctx.packageName
        val header = listOfNotNull(
            p.app.takeIf { prefs.showApp && !own },
            "🕓 ${clock(ctx, p.time)}".takeIf { late },
        ).joinToString(" · ")
        val html = buildString {
            if (header.isNotEmpty()) append("<i>").append(escapeHtml(header)).append("</i>\n")
            if (p.title.isNotBlank()) append("<b>").append(escapeHtml(p.title)).append("</b>\n")
            append(escapeHtml(p.text))
        }.trim()
        // The message as Telegram displays it (and as it reappears in Telegram's own notification).
        // Registered before sending: Telegram may deliver it here before the API call returns.
        ourMessages.add(normalize(listOfNotNull(header.ifEmpty { null }, p.title.ifBlank { null }, p.text).joinToString("\n")))

        val markup: JSONObject? = if (own) null else Keyboards.forNotification(p.pkg, p.app)
        var delayMs = 2_000L
        for (attempt in 1..attempts) {
            try {
                Telegram.sendMessage(prefs.botToken, prefs.chatId, html, prefs.silent || late, markup)
                return
            } catch (e: TelegramException) {
                if (e.code != 429 || attempt == attempts) throw e
                Thread.sleep(e.retryAfterSec.coerceIn(1, 60) * 1000L)
            } catch (e: Exception) {
                if (attempt == attempts) throw e
                Thread.sleep(delayMs)
                delayMs *= 5
            }
        }
    }

    private fun outageReport(ctx: Context, o: Outage): String = buildString {
        val minutes = ((o.end - o.start) / 60_000).coerceAtLeast(1)
        append("⚠️ <b>No internet</b> ").append(clock(ctx, o.start)).append(" – ").append(clock(ctx, o.end))
        append(" (").append(Health.duration(minutes)).append(")\n")
        append(
            when (o.missed) {
                0 -> "Nothing was missed."
                1 -> "1 notification was delayed, it follows."
                else -> "${o.missed} notifications were delayed, they follow."
            }
        )
    }

    private fun log(prefs: Prefs, p: Pending, status: String) =
        prefs.addLog(LogEntry(p.time, p.app, p.title, p.text, status, p.id))

    private fun normalize(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private const val RETRY_MIN_MS = 30_000L
    private const val RETRY_MAX_MS = 15 * 60_000L
    private const val SEND_GAP_MS = 1_000L

    /** Shorter outages that delayed nothing are not reported. */
    const val OUTAGE_MIN_MS = 60_000L
}

/** Thread-safe insertion-ordered set that forgets its oldest entries. add() returns false if present. */
class BoundedSet(private val max: Int) {
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
