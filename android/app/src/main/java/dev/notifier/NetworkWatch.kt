package dev.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Watches the phone's internet while the listener runs. An outage starts when there is no validated
 * internet; once it's back [Outbox.flush] closes the outage, reports it to the chat and resends the queue.
 * Doze blocking our network isn't an outage, but its end also resends the queue.
 */
class NetworkWatch(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val prefs = Prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private val showAlert = Runnable { OfflineAlert.refresh(context) }
    private var alertScheduled = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            update(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

        override fun onLost(network: Network) = update(false)

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (!blocked) Outbox.flush(context)
        }
    }

    fun start() {
        // The callback only fires when there is a network; check the "no network at all" case here.
        update(Health.online(context))
        cm.registerDefaultNetworkCallback(callback, handler)
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Was not registered.
        }
        handler.removeCallbacks(showAlert)
        alertScheduled = false
    }

    /** Runs on the main thread. */
    private fun update(online: Boolean) {
        val now = System.currentTimeMillis()
        var since = prefs.offlineSince
        if (!online) {
            if (since == 0L) {
                since = now
                prefs.offlineSince = now
            }
            if (!alertScheduled) {
                alertScheduled = true
                handler.postDelayed(showAlert, (since + OfflineAlert.AFTER_MS - now).coerceAtLeast(0))
            }
            return
        }
        handler.removeCallbacks(showAlert)
        alertScheduled = false
        // Only the Outbox clears offlineSince, so the report always comes before the messages it delayed.
        if (since != 0L) Outbox.flush(context)
    }
}

/** Android notification shown after a minute without internet, with the number of notifications waiting. */
object OfflineAlert {

    const val AFTER_MS = 60_000L
    private const val CHANNEL = "offline"
    private const val ID = 1

    /** Shows or updates the alert if the phone has been offline long enough, otherwise removes it. */
    fun refresh(context: Context) {
        val prefs = Prefs(context)
        val since = prefs.offlineSince
        if (since == 0L || System.currentTimeMillis() - since < AFTER_MS) return cancel(context)

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "No internet", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while notifications can't reach Telegram"
            }
        )
        val waiting = prefs.pending().size
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_HEALTH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (waiting) {
            0 -> "Notifications will be sent to Telegram when the connection is back."
            1 -> "1 notification is waiting and will be sent when the connection is back."
            else -> "$waiting notifications are waiting and will be sent when the connection is back."
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("No internet since ${clock(context, since)}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        try {
            nm.notify(ID, notification)
        } catch (e: SecurityException) {
            // Android 13+ without the notification permission: the app screen still shows the banner.
        }
    }

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}
