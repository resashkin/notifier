package dev.notifier

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Notifications a mute rule sends to the digest wait in Prefs and go out together at fixed times of day
 * (09:00, 13:00, 19:00 by default): one message, grouped by app, the latest few lines of each, already
 * mapped. Nothing is sent when there's nothing waiting. The message goes through the [Outbox] queue and
 * rings, unlike the notifications in it.
 *
 * An inexact alarm wakes the phone for each time ([DigestReceiver]); a time that passed while the phone was
 * off or the alarm was late is caught up by [check], which also runs when the listener connects.
 */
object Digest {

    private const val LINES_PER_APP = 5
    private const val LINE_MAX = 140
    private const val MESSAGE_MAX = 3500  // Telegram allows 4096 characters

    /** Digest times as minutes after midnight, sorted. */
    fun times(prefs: Prefs): List<Int> = prefs.digestTimes.split(',').mapNotNull { parse(it) }.distinct().sorted()

    /** "9:30" or "09:30" -> 570, null if it isn't a time of day. */
    fun parse(s: String): Int? {
        val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    fun format(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** New digest times (empty = digest off). A time just added doesn't send anything waiting right away. */
    fun setTimes(context: Context, prefs: Prefs, times: List<Int>) {
        prefs.digestTimes = times.distinct().sorted().joinToString(",") { format(it) }
        prefs.lastDigestAt = System.currentTimeMillis()
        schedule(context)
    }

    /** Sends the digest if one of its times has passed since the last one, then sets the alarm for the next. */
    fun check(context: Context) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val due = slots(prefs, now).lastOrNull { it <= now }
        if (due != null && prefs.lastDigestAt < due) {
            prefs.lastDigestAt = now
            send(context)
        }
        schedule(context)
    }

    /** Sends what's waiting now; returns how many notifications that was. */
    fun send(context: Context): Int {
        val prefs = Prefs(context)
        val items = prefs.takeDigest()
        if (items.isEmpty()) return 0
        val t = Lang.of(prefs)
        val summary = t.notifications(items.size)

        val blocks = items.groupBy { it.app.ifBlank { it.pkg } }.entries
            .sortedByDescending { it.value.size }
            .map { (app, list) -> block(context, app, list, t) }
        val messages = mutableListOf(StringBuilder())
        for (block in blocks) {
            if (messages.last().length + block.length > MESSAGE_MAX && messages.last().isNotEmpty()) messages += StringBuilder()
            messages.last().append(block)
        }
        messages.forEachIndexed { i, body ->
            val part = if (messages.size > 1) " (${i + 1}/${messages.size})" else ""
            val html = "📋 <b>${t("Digest", "Дайджест")}</b> · $summary$part\n" + body.toString().trimEnd()
            Outbox.composed(context, "📋 " + t("Digest", "Дайджест"), summary + part, html)
        }
        items.forEach { prefs.updateLog(it.id, "sent in digest") }
        return items.size
    }

    private fun block(context: Context, app: String, list: List<DigestItem>, t: Lang): String = buildString {
        append("\n<b>").append(escapeHtml(app)).append("</b>")
        if (list.size > 1) append(" · ").append(list.size)
        append('\n')
        val shown = list.takeLast(LINES_PER_APP)
        if (list.size > shown.size) {
            append("<i>").append(t("… ${list.size - shown.size} earlier", "… ещё ${list.size - shown.size} раньше")).append("</i>\n")
        }
        for (item in shown) {
            val who = item.title.takeIf { it.isNotBlank() && it != item.app }?.let { "$it: " }.orEmpty()
            var line = (who + item.text).replace(Regex("\\s+"), " ").trim()
            if (line.length > LINE_MAX) line = line.take(LINE_MAX - 1).trimEnd() + "…"
            append(clock(context, item.time)).append("  ").append(escapeHtml(line)).append('\n')
        }
    }

    /** Sets the alarm for the next digest time, or cancels it when the digest is off. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, DigestReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val now = System.currentTimeMillis()
        val next = slots(Prefs(context), now).firstOrNull { it > now } ?: return am.cancel(intent)
        // Inexact: exact alarms need a permission Android 14 denies by default; a few minutes late is fine.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
    }

    /** The digest times of yesterday, today and tomorrow (local time), as epoch ms, sorted. */
    private fun slots(prefs: Prefs, now: Long): List<Long> {
        val times = times(prefs)
        return (-1..1).flatMap { day ->
            times.map { minutes ->
                Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, day)
                    set(Calendar.HOUR_OF_DAY, minutes / 60)
                    set(Calendar.MINUTE, minutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
        }.sorted()
    }
}

/** The digest alarm. */
class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Digest.check(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
