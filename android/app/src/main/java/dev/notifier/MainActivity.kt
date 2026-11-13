package dev.notifier

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Date

/** Bottom navigation with four tabs: Health (status, permissions, internet, phone), Telegram (bot Start/Stop and setup),
 *  Notifications (waiting queue and recent log), Settings (forwarding switch and rules). */
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
    private val autoUpdate by lazy { findViewById<Switch>(R.id.auto_update) }
    private val lowBatteryOn by lazy { findViewById<Switch>(R.id.low_battery_on) }
    private val lowBatteryLevel by lazy { findViewById<SeekBar>(R.id.low_battery_level) }
    private val updateRepo by lazy { findViewById<EditText>(R.id.update_repo) }

    private val tabs by lazy {
        listOf(
            R.id.tab_health to R.id.page_health,
            R.id.tab_telegram to R.id.page_telegram,
            R.id.tab_notifications to R.id.page_notifications,
            R.id.tab_settings to R.id.page_settings,
        ).map { (tab, page) -> findViewById<TextView>(tab) to findViewById<View>(page) }
    }
    private var currentTab = TAB_HEALTH

    /** Keeps the screen in sync with changes made from the bot (mute, pause), the queue and new log entries. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        runOnUiThread {
            when (key) {
                Prefs.KEY_LOG -> showRecent()
                Prefs.KEY_PENDING -> { showPending(); refreshInternet() }
                Prefs.KEY_OUTAGES, Prefs.KEY_OFFLINE_SINCE -> refreshInternet()
                Prefs.KEY_IGNORED -> if (!ignored.hasFocus()) ignored.setText(prefs.ignoredPackages)
                Prefs.KEY_PAUSED_UNTIL -> refreshStatus()
                Prefs.KEY_BOT_ENABLED -> refreshBot()
                Prefs.KEY_UPDATE_STATUS -> refreshUpdate()
            }
        }
    }

    /** Bot state, internet and phone health change without a prefs write: refresh while visible. */
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshBot()
            refreshInternet()
            refreshHealth()
            handler.postDelayed(this, 5_000)
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
        autoUpdate.isChecked = prefs.autoUpdate
        lowBatteryOn.isChecked = prefs.lowBatteryOn
        lowBatteryLevel.progress = prefs.lowBatteryLevel
        showLowBatteryLevel()
        lowBatteryOn.setOnCheckedChangeListener { _, _ -> showLowBatteryLevel() }
        lowBatteryLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = showLowBatteryLevel()
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        updateRepo.setText(prefs.updateRepo)

        tabs.forEachIndexed { i, (tab, _) -> tab.setOnClickListener { selectTab(i) } }
        selectTab(savedInstanceState?.getInt(EXTRA_TAB) ?: intent.getIntExtra(EXTRA_TAB, TAB_HEALTH))

        findViewById<Button>(R.id.grant_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.battery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.alert_permission).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_ALERTS)
        }
        findViewById<Button>(R.id.health_permissions).setOnClickListener {
            requestPermissions(missingHealthPermissions().toTypedArray(), REQUEST_HEALTH)
        }
        findViewById<Button>(R.id.bot_toggle).setOnClickListener {
            save()
            prefs.botEnabled = !prefs.botEnabled  // ForwarderService starts or stops the bot
            toast(if (prefs.botEnabled) "Bot started" else "Bot stopped")
        }
        findViewById<Button>(R.id.allow_install).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.check_update).setOnClickListener {
            save()
            toast("Checking ${prefs.updateRepo}…")
            background { Updater.check(this, install = true) }
        }
        findViewById<Button>(R.id.save).setOnClickListener { save(); toast("Saved") }
        findViewById<Button>(R.id.test).setOnClickListener { save(); sendTest() }
        findViewById<Button>(R.id.detect).setOnClickListener { save(); detectChat() }
        findViewById<Button>(R.id.retry).setOnClickListener { save(); Outbox.flush(this); toast("Sending…") }
        findViewById<Button>(R.id.discard).setOnClickListener { Outbox.discard(this) }
        findViewById<Button>(R.id.clear).setOnClickListener { prefs.clearLog() }
        enabled.setOnCheckedChangeListener { _, _ -> save(); refreshStatus() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) selectTab(intent.getIntExtra(EXTRA_TAB, TAB_HEALTH))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_TAB, currentTab)
    }

    override fun onResume() {
        super.onResume()
        prefs.raw.registerOnSharedPreferenceChangeListener(prefsListener)
        ignored.setText(prefs.ignoredPackages)  // may have changed from the bot while we were away
        refreshStatus()
        refreshUpdate()
        showPending()
        showRecent()
        ticker.run()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Android 11+ grants background location only in a separate step (opens its settings page).
        val needsBackground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (requestCode == REQUEST_HEALTH && needsBackground) {
            toast("Choose “Allow all the time” so the Wi-Fi name is visible from Telegram")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_HEALTH_BACKGROUND)
        }
        refreshStatus()
        refreshHealth()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefsListener)
        save()
        super.onPause()
    }

    private fun selectTab(index: Int) {
        currentTab = index.coerceIn(tabs.indices)
        tabs.forEachIndexed { i, (tab, page) ->
            tab.isSelected = i == currentTab
            page.visibility = if (i == currentTab) View.VISIBLE else View.GONE
        }
        currentFocus?.clearFocus()
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
        prefs.autoUpdate = autoUpdate.isChecked
        prefs.lowBatteryOn = lowBatteryOn.isChecked
        if (prefs.lowBatteryLevel != lowBatteryLevel.progress) {
            prefs.lowBatteryLevel = lowBatteryLevel.progress
            prefs.lowBatteryWarned = false  // the new level may not have been warned about yet
        }
        prefs.updateRepo = updateRepo.text.toString().trim().removePrefix("https://github.com/").trim('/')
            .ifEmpty { Prefs.DEFAULT_UPDATE_REPO }
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
        findViewById<Button>(R.id.grant_access).visibility = if (access) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.battery).visibility = if (battery) View.GONE else View.VISIBLE
        val alertsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
        findViewById<Button>(R.id.alert_permission).visibility = if (alertsAllowed) View.GONE else View.VISIBLE
    }

    private fun refreshBot() {
        val configured = prefs.botToken.isNotBlank() && prefs.chatId.isNotBlank()
        findViewById<TextView>(R.id.bot_status).text = when {
            !prefs.botEnabled -> "⏹ Bot stopped"
            !configured -> "⏸ Bot waiting for the token and chat ID"
            !BotController.running -> "⏸ Bot not running: grant notification access on the Health tab"
            BotController.error != null -> "⚠️ ${BotController.error}"
            BotController.lastPollAt == 0L -> "⏳ Bot starting…"
            else -> "🟢 Bot running · last check ${DateFormat.getTimeFormat(this).format(Date(BotController.lastPollAt))}"
        }
        findViewById<Button>(R.id.bot_toggle).text = if (prefs.botEnabled) "Stop bot" else "Start bot"
    }

    private fun refreshInternet() {
        val since = prefs.offlineSince
        val waiting = prefs.pending().size
        val banner = findViewById<TextView>(R.id.internet_banner)
        val offline = since != 0L || !Health.online(this)
        banner.visibility = if (offline || waiting > 0) View.VISIBLE else View.GONE
        banner.text = buildString {
            if (offline) {
                append("⚠️ No internet")
                if (since != 0L) append(" since ").append(clock(this@MainActivity, since))
                    .append(" (").append(Health.duration((System.currentTimeMillis() - since) / 60_000)).append(")")
            } else {
                append("⚠️ Telegram can't be reached")
            }
            if (waiting > 0) append("\n").append(waitingText(waiting)).append(" — see Notifications")
        }

        val outages = prefs.outages()
        findViewById<TextView>(R.id.outages).text = if (outages.isEmpty()) "None recorded." else
            outages.joinToString("\n") { o ->
                val minutes = ((o.end - o.start) / 60_000).coerceAtLeast(1)
                "• ${clock(this, o.start)} – ${clock(this, o.end)} · ${Health.duration(minutes)}" +
                    (if (o.missed > 0) " · ${o.missed} delayed" else "") +
                    (if (o.reported) "" else " · not reported yet")
            }
    }

    private fun showLowBatteryLevel() {
        lowBatteryLevel.isEnabled = lowBatteryOn.isChecked
        findViewById<TextView>(R.id.low_battery_label).apply {
            text = "${lowBatteryLevel.progress} %"
            alpha = if (lowBatteryOn.isChecked) 1f else 0.5f
        }
    }

    private fun refreshUpdate() {
        findViewById<TextView>(R.id.update_status).text = buildString {
            append("Version ").append(Updater.installedVersion(this@MainActivity))
            if (prefs.lastUpdateCheck > 0) append(" · checked ").append(clock(this@MainActivity, prefs.lastUpdateCheck))
            if (prefs.updateStatus.isNotBlank()) append("\n").append(prefs.updateStatus)
        }
        findViewById<Button>(R.id.allow_install).visibility =
            if (packageManager.canRequestPackageInstalls()) View.GONE else View.VISIBLE
    }

    private fun refreshHealth() {
        findViewById<TextView>(R.id.health).text = Health.lines(Health.snapshot(this)).joinToString("\n")
        findViewById<Button>(R.id.health_permissions).visibility =
            if (missingHealthPermissions().isEmpty()) View.GONE else View.VISIBLE
    }

    /** Optional: Wi-Fi name needs location (all the time, since /status is answered in the background),
     *  mobile network type needs phone state. */
    private fun missingHealthPermissions(): List<String> = listOfNotNull(
        Manifest.permission.ACCESS_FINE_LOCATION,
        // Android 10 asks for background location in the same dialog; 11+ needs a second step.
        Manifest.permission.ACCESS_BACKGROUND_LOCATION.takeIf { Build.VERSION.SDK_INT == Build.VERSION_CODES.Q },
        Manifest.permission.READ_PHONE_STATE,
    ).filterNot(::granted) + listOfNotNull(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION.takeIf {
            Build.VERSION.SDK_INT > Build.VERSION_CODES.Q && !granted(it) && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        },
    )

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun showPending() {
        val pending = prefs.pending()
        findViewById<TextView>(R.id.pending_title).text =
            if (pending.isEmpty()) "Nothing waiting to send" else "Waiting to send (${pending.size})"
        val visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        listOf(R.id.pending_hint, R.id.pending_list, R.id.pending_actions).forEach { findViewById<View>(it).visibility = visibility }
        fillList(findViewById(R.id.pending_list), pending.reversed().map { p ->
            Item(p.time, p.app, p.title, p.text, "🕓 waiting", COLOR_WAITING)
        })
        findViewById<TextView>(R.id.pending_badge).apply {
            this.visibility = visibility
            text = if (pending.size > 99) "99+" else pending.size.toString()
        }
    }

    private fun showRecent() {
        val entries = prefs.recent()
        findViewById<View>(R.id.recent_empty).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        fillList(findViewById(R.id.recent_list), entries.map { e ->
            val (status, color) = when {
                e.status == "queued" -> "🕓 waiting" to COLOR_WAITING
                e.status.startsWith("sent") -> "✓ ${e.status}" to COLOR_SENT
                e.status == "discarded" -> "– discarded" to COLOR_DROPPED
                else -> "✗ ${e.status}" to COLOR_FAILED
            }
            Item(e.time, e.app, e.title, e.text, status, color)
        })
    }

    /** One card on the Notifications tab. */
    private class Item(val time: Long, val app: String, val title: String, val text: String, val status: String, val color: Int)

    /** Shows [items] as cards (the newest [LIST_MAX]); tapping a card shows its full text. */
    private fun fillList(container: LinearLayout, items: List<Item>) {
        container.removeAllViews()
        for (item in items.take(LIST_MAX)) {
            val card = layoutInflater.inflate(R.layout.item_notification, container, false)
            card.findViewById<TextView>(R.id.item_app).text = item.app
            card.findViewById<TextView>(R.id.item_time).text = clock(this, item.time)
            card.findViewById<TextView>(R.id.item_title).apply {
                text = item.title
                visibility = if (item.title.isBlank()) View.GONE else View.VISIBLE
            }
            val body = card.findViewById<TextView>(R.id.item_text).apply {
                text = item.text
                visibility = if (item.text.isBlank()) View.GONE else View.VISIBLE
            }
            card.findViewById<TextView>(R.id.item_status).apply {
                text = item.status
                setTextColor(item.color)
            }
            card.setOnClickListener { body.maxLines = if (body.maxLines == Int.MAX_VALUE) 3 else Int.MAX_VALUE }
            container.addView(card)
        }
        if (items.size > LIST_MAX) {
            container.addView(TextView(this).apply {
                text = "…and ${items.size - LIST_MAX} more"
                alpha = 0.7f
            })
        }
    }

    private fun waitingText(n: Int) = if (n == 1) "1 notification waiting" else "$n notifications waiting"

    private fun sendTest() = background {
        val bot = Telegram.botName(prefs.botToken)
        Telegram.sendMessage(prefs.botToken, prefs.chatId, "✅ <b>Notifier test</b>\nFrom ${Build.MANUFACTURER} ${Build.MODEL}", prefs.silent)
        "Test sent via $bot"
    }

    private fun detectChat() = background {
        val (id, name) = Telegram.latestChat(prefs.botToken)
            ?: return@background "No messages yet — send /start to your bot first"
        prefs.chatId = id
        runOnUiThread { chatId.setText(id); refreshStatus(); refreshBot() }
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

    companion object {
        /** Intent extra / saved state: which tab to show. */
        const val EXTRA_TAB = "tab"
        const val TAB_HEALTH = 0

        private const val REQUEST_HEALTH = 1
        private const val REQUEST_HEALTH_BACKGROUND = 2
        private const val REQUEST_ALERTS = 3

        private const val LIST_MAX = 100
        private const val COLOR_SENT = 0xFF2E7D32.toInt()
        private const val COLOR_WAITING = 0xFFEF6C00.toInt()
        private const val COLOR_FAILED = 0xFFC62828.toInt()
        private const val COLOR_DROPPED = 0xFF757575.toInt()
    }
}
