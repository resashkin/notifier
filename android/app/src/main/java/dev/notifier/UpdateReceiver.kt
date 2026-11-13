package dev.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.service.notification.NotificationListenerService

/**
 * Results of [Updater]'s install session, and the "app was updated" broadcast:
 * - Android 10/11 needs the user to confirm: shown as a notification (apps can't open screens from the background);
 * - a failure is reported to the chat;
 * - after the update, the listener is rebound and the chat is told the new version.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                NotificationListenerService.requestRebind(ComponentName(context, ForwarderService::class.java))
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                val version = Updater.installedVersion(context)
                prefs.updateStatus = "updated to $version"
                tellChat(prefs, "✅ Notifier updated to <b>${escapeHtml(version)}</b>")
            }
            ACTION_INSTALL_RESULT -> {
                val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        prefs.updateStatus = "waiting for “Update” tap on the phone"
                        askToConfirm(context, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), version)
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit  // ACTION_MY_PACKAGE_REPLACED follows
                    else -> {
                        val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                        prefs.updateStatus = "failed: $reason"
                        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                        tellChat(prefs, "⚠️ Update to $version failed: ${escapeHtml(reason)}")
                    }
                }
            }
        }
    }

    private fun askToConfirm(context: Context, confirm: Intent, version: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update Notifier to $version")
            .setContentText("Tap to install")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // No notification permission (Android 13+): the app's Settings tab still offers the update.
        }
        try {
            context.startActivity(confirm)  // works if Notifier is on screen; blocked from the background
        } catch (e: Exception) {
        }
    }

    private fun tellChat(prefs: Prefs, html: String) {
        if (prefs.botToken.isBlank() || prefs.chatId.isBlank()) return
        val pending = goAsync()
        Thread {
            try {
                Telegram.sendMessage(prefs.botToken, prefs.chatId, html, silent = false)
            } catch (e: Exception) {
                // Offline: not worth queueing.
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "dev.notifier.INSTALL_RESULT"
        const val EXTRA_VERSION = "version"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 2
    }
}
