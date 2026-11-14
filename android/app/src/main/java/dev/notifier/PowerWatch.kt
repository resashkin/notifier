package dev.notifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * Watches the charger while the listener runs: a warning goes to the chat when it's unplugged (or the power
 * is cut), and a note when it's back; replugging within a few seconds is ignored. Another warning when the
 * battery drops to the low level from Settings, once per discharge. Messages go through the
 * [Outbox] queue: a power cut often takes the Wi-Fi router down too, so they follow once the internet is back.
 * Also feeds [BatteryEstimate] with battery levels. Power broadcasts only reach receivers registered at
 * runtime (Android 8+), hence here and not in the manifest.
 */
class PowerWatch(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)

    /** Sent only if the charger stays unplugged for [DEBOUNCE_MS]. */
    private val warn = Runnable {
        if (BatteryEstimate.plugged(context)) return@Runnable
        prefs.unpluggedAt = System.currentTimeMillis()
        val level = BatteryEstimate.level(context)
        val left = BatteryEstimate.minutesLeft(context)
        val t = Lang.of(prefs)
        Outbox.notice(
            context, t("🔌 Charger disconnected", "🔌 Зарядка отключена"),
            t("Running on battery: $level %", "Работает от батареи: $level %") +
                (left?.let { t(" · about ${Health.duration(it, t)} left", " · осталось около ${Health.duration(it, t)}") } ?: ""),
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
                        intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    BatteryEstimate.record(context, level, plugged)
                    checkLow(level, plugged)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    handler.removeCallbacks(warn)
                    handler.postDelayed(warn, DEBOUNCE_MS)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    handler.removeCallbacks(warn)
                    val since = prefs.unpluggedAt
                    if (since == 0L) return  // a short blip: no warning was sent
                    prefs.unpluggedAt = 0
                    val minutes = (System.currentTimeMillis() - since) / 60_000
                    val t = Lang.of(prefs)
                    Outbox.notice(
                        context, t("⚡ Charger connected", "⚡ Зарядка подключена"),
                        t("Battery ${BatteryEstimate.level(context)} % · was on battery ${Health.duration(minutes, t)}",
                            "Батарея ${BatteryEstimate.level(context)} % · без зарядки ${Health.duration(minutes, t)}"),
                    )
                }
            }
        }
    }

    private fun checkLow(level: Int, plugged: Boolean) {
        if (level < 0) return
        if (plugged || level > prefs.lowBatteryLevel) {
            if (prefs.lowBatteryWarned) prefs.lowBatteryWarned = false
            return
        }
        if (!prefs.lowBatteryOn || prefs.lowBatteryWarned) return
        prefs.lowBatteryWarned = true
        val left = BatteryEstimate.minutesLeft(context)
        val t = Lang.of(prefs)
        Outbox.notice(
            context, t("🪫 Battery low: $level %", "🪫 Батарея садится: $level %"),
            (left?.let { t("About ${Health.duration(it, t)} left. ", "Осталось около ${Health.duration(it, t)}. ") } ?: "") +
                t("Plug in the charger.", "Подключите зарядку."),
        )
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
    }

    fun stop() {
        handler.removeCallbacks(warn)
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Was not registered.
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 5_000L
    }
}

/**
 * Approximate time left on battery (Android only estimates time to full). In order of preference:
 * the drain measured since the charger was unplugged (once it's meaningful), the drain learned from earlier
 * discharges, and finally the charge counter divided by the current draw right now.
 */
object BatteryEstimate {

    private const val MIN_DROP = 3                  // % before the measured drain is trusted
    private const val MIN_SPAN_MS = 15 * 60_000L

    fun level(context: Context): Int {
        val bm = context.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun plugged(context: Context): Boolean = battery(context)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    /** Plugged in or still reporting "charging" (the status lags behind the cable for a moment): no estimate then,
     *  the current draw would read near zero. */
    private fun charging(context: Context): Boolean {
        val i = battery(context) ?: return false
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun battery(context: Context) = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /** Tracks discharge sessions: starts one when unplugged, learns its drain rate when plugged back in. */
    fun record(context: Context, level: Int, plugged: Boolean) {
        if (level < 0) return
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        if (plugged) {
            measuredRate(prefs, level, now)?.let { rate ->
                prefs.drainRate = if (prefs.drainRate > 0) (prefs.drainRate + rate) / 2 else rate
            }
            prefs.dischargeStart = 0
        } else if (prefs.dischargeStart == 0L) {
            prefs.dischargeStart = now
            prefs.dischargeStartLevel = level
        }
    }

    /** Minutes until empty while on battery, null while charging or when there's nothing to go on. */
    fun minutesLeft(context: Context): Long? {
        if (charging(context)) return null
        val prefs = Prefs(context)
        val level = level(context)
        val rate = measuredRate(prefs, level, System.currentTimeMillis())   // % per hour
            ?: prefs.drainRate.takeIf { it > 0 }
            ?: instantRate(context)
            ?: return null
        return (level / rate * 60).toLong().takeIf { it in 1..60L * 24 * 30 }
    }

    private fun measuredRate(prefs: Prefs, level: Int, now: Long): Float? {
        val start = prefs.dischargeStart
        if (start == 0L) return null
        val drop = prefs.dischargeStartLevel - level
        val span = now - start
        if (drop < MIN_DROP || span < MIN_SPAN_MS) return null
        return drop / (span / 3_600_000f)
    }

    /** % per hour from the current draw; vendors differ in units and sign, so only plausible values count. */
    private fun instantRate(context: Context): Float? {
        val bm = context.getSystemService(BatteryManager::class.java)
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)  // µAh left
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)     // µA
        val level = level(context)
        if (counter <= 0 || current == 0 || current == Int.MIN_VALUE || level <= 0) return null
        val hoursLeft = counter.toFloat() / kotlin.math.abs(current)
        if (hoursLeft !in 0.5f..240f) return null  // a near-zero reading means the battery is barely draining or still charging
        return level / hoursLeft
    }
}
