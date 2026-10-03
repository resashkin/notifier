package dev.notifier

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.format.DateFormat
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Date

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val listener = ComponentName("dev.notifier", ForwarderService::class.java.name)

    private val token by lazy { findViewById<EditText>(R.id.bot_token) }
    private val chatId by lazy { findViewById<EditText>(R.id.chat_id) }
    private val miniAppUrl by lazy { findViewById<EditText>(R.id.mini_app_url) }
    private val enabled by lazy { findViewById<Switch>(R.id.enabled) }
    private val skipOngoing by lazy { findViewById<CheckBox>(R.id.skip_ongoing) }
    private val skipGroupSummary by lazy { findViewById<CheckBox>(R.id.skip_group_summary) }
    private val showApp by lazy { findViewById<CheckBox>(R.id.show_app) }
    private val silent by lazy { findViewById<CheckBox>(R.id.silent) }
    private val ignored by lazy { findViewById<EditText>(R.id.ignored) }

    /** Keeps the screen in sync with changes made from the bot (mute, pause) and new log entries. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        runOnUiThread {
            when (key) {
                Prefs.KEY_LOG -> showRecent()
                Prefs.KEY_IGNORED -> if (!ignored.hasFocus()) ignored.setText(prefs.ignoredPackages)
                Prefs.KEY_PAUSED_UNTIL -> refreshStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        token.setText(prefs.botToken)
        chatId.setText(prefs.chatId)
        miniAppUrl.setText(prefs.miniAppUrl)
        enabled.isChecked = prefs.enabled
        skipOngoing.isChecked = prefs.skipOngoing
        skipGroupSummary.isChecked = prefs.skipGroupSummary
        showApp.isChecked = prefs.showApp
        silent.isChecked = prefs.silent
        ignored.setText(prefs.ignoredPackages)

        findViewById<Button>(R.id.grant_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.battery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.save).setOnClickListener { save(); toast("Saved") }
        findViewById<Button>(R.id.test).setOnClickListener { save(); sendTest() }
        findViewById<Button>(R.id.detect).setOnClickListener { save(); detectChat() }
        findViewById<Button>(R.id.clear).setOnClickListener { prefs.clearLog() }
        enabled.setOnCheckedChangeListener { _, _ -> save(); refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        prefs.raw.registerOnSharedPreferenceChangeListener(prefsListener)
        ignored.setText(prefs.ignoredPackages)  // may have changed from the bot while we were away
        refreshStatus()
        showRecent()
    }

    override fun onPause() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefsListener)
        save()
        super.onPause()
    }

    private fun save() {
        prefs.botToken = token.text.toString().trim()
        prefs.chatId = chatId.text.toString().trim()
        prefs.miniAppUrl = miniAppUrl.text.toString().trim()
        prefs.enabled = enabled.isChecked
        prefs.skipOngoing = skipOngoing.isChecked
        prefs.skipGroupSummary = skipGroupSummary.isChecked
        prefs.showApp = showApp.isChecked
        prefs.silent = silent.isChecked
        prefs.ignoredPackages = ignored.text.toString()
    }

    private fun refreshStatus() {
        val access = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        if (access && !ForwarderService.connected) NotificationListenerService.requestRebind(listener)

        val ready = access && prefs.enabled && prefs.botToken.isNotBlank() && prefs.chatId.isNotBlank()
        findViewById<TextView>(R.id.status).text = buildString {
            append(
                when {
                    !ready -> "⏸ Forwarding is not active"
                    prefs.isPaused() -> "⏸ Paused from Telegram until ${DateFormat.getTimeFormat(this@MainActivity).format(Date(prefs.pausedUntil))}"
                    else -> "✅ Forwarding is active"
                }
            ).append("\n")
            append(if (access) "✓ Notification access granted" else "✗ Notification access needed").append("\n")
            append(if (battery) "✓ Allowed to run in background" else "• Background running may be limited").append("\n")
            append(if (prefs.botToken.isNotBlank() && prefs.chatId.isNotBlank()) "✓ Telegram configured" else "✗ Telegram bot not set up")
        }
        findViewById<Button>(R.id.grant_access).visibility = if (access) Button.GONE else Button.VISIBLE
        findViewById<Button>(R.id.battery).visibility = if (battery) Button.GONE else Button.VISIBLE
    }

    private fun showRecent() {
        val fmt = DateFormat.getTimeFormat(this)
        val entries = prefs.recent()
        findViewById<TextView>(R.id.recent).text = if (entries.isEmpty()) "Nothing forwarded yet." else
            entries.joinToString("\n\n") { e ->
                val mark = if (e.status == "sent") "✓" else "✗ ${e.status}"
                "${fmt.format(Date(e.time))}  ${e.app}  $mark\n${e.title}\n${e.text}".trim()
            }
    }

    private fun sendTest() = background {
        val bot = Telegram.botName(prefs.botToken)
        Telegram.sendMessage(prefs.botToken, prefs.chatId, "✅ <b>Notifier test</b>\nFrom ${Build.MANUFACTURER} ${Build.MODEL}", prefs.silent)
        "Test sent via $bot"
    }

    private fun detectChat() = background {
        val (id, name) = Telegram.latestChat(prefs.botToken)
            ?: return@background "No messages yet — send /start to your bot first"
        prefs.chatId = id
        runOnUiThread { chatId.setText(id); refreshStatus() }
        "Chat detected: $name"
    }

    /** Runs a network call off the main thread and toasts its result or error. */
    private fun background(block: () -> String) {
        Thread {
            val message = try { block() } catch (e: Exception) { "Error: ${e.message}" }
            runOnUiThread { toast(message) }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
