package dev.notifier

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.net.Inet4Address
import kotlin.math.abs

/**
 * Snapshot of the phone's health: battery, charging, Wi-Fi, mobile network, internet, uptime.
 * Read on demand (/status, /settings); only the time left on battery uses history ([BatteryEstimate]).
 *
 * Optional permissions: Wi-Fi name needs location ("Allow all the time" to read it from the background),
 * mobile network type needs READ_PHONE_STATE. Without them those fields are simply left out.
 * The JSON goes to the Mini App as-is: keep in sync with miniapp/lib/model.dart.
 */
object Health {

    fun snapshot(context: Context): JSONObject = JSONObject()
        .put("battery", battery(context))
        .put("wifi", wifi(context))
        .put("mobile", mobile(context))
        .put("internet", internet(context))
        .put("uptime_min", SystemClock.elapsedRealtime() / 60_000)

    private fun battery(context: Context): JSONObject {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return JSONObject()
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        val o = JSONObject()
            .put("level", i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale)
            .put("status", when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> "unknown"
            })
            .put("plugged", when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            })
            .put("temp_c", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            .put("voltage_v", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0)
            .put("health", when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> "unknown"
            })

        val bm = context.getSystemService(BatteryManager::class.java)
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current != Int.MIN_VALUE && current != 0) {
            // Should be µA, but some vendors report mA; the sign convention also varies, so report the magnitude.
            o.put("current_ma", if (abs(current) > 10_000) abs(current) / 1000 else abs(current))
        }
        val fullInMs = bm.computeChargeTimeRemaining()
        if (fullInMs > 0) o.put("full_in_min", fullInMs / 60_000)
        BatteryEstimate.minutesLeft(context)?.let { o.put("left_min", it) }
        return o
    }

    @Suppress("DEPRECATION") // WifiManager.connectionInfo: replacements need API 31+
    private fun wifi(context: Context): JSONObject {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val o = JSONObject().put("enabled", wm.isWifiEnabled)
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val info = wm.connectionInfo
        if (network == null || info == null) return o.put("connected", false)

        o.put("connected", true)
        // Without location permission Android reports "<unknown ssid>".
        info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }?.let { o.put("ssid", it) }
        o.put("rssi_dbm", info.rssi)
        o.put("level", WifiManager.calculateSignalLevel(info.rssi, 5))  // 0..4
        if (info.linkSpeed > 0) o.put("mbps", info.linkSpeed)
        o.put("band", when {
            info.frequency >= 5925 -> "6 GHz"
            info.frequency >= 4900 -> "5 GHz"
            info.frequency > 0 -> "2.4 GHz"
            else -> null
        })
        cm.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
            ?.let { o.put("ip", it.hostAddress) }
        return o
    }

    private fun mobile(context: Context): JSONObject {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val o = JSONObject().put("sim", when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_ABSENT -> "no SIM"
            TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED -> "locked"
            else -> "unknown"
        })
        tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { o.put("operator", it) }
        o.put("roaming", tm.isNetworkRoaming)
        try {
            o.put("data", tm.isDataEnabled)
        } catch (e: SecurityException) {
        }
        try {
            networkType(tm.dataNetworkType)?.let { o.put("type", it) }  // needs READ_PHONE_STATE
        } catch (e: SecurityException) {
        }
        try {
            tm.signalStrength?.let { s ->
                o.put("level", s.level)  // 0..4
                s.cellSignalStrengths.firstOrNull()?.dbm?.takeIf { it != Int.MAX_VALUE }?.let { o.put("dbm", it) }
            }
        } catch (e: Exception) {
        }
        return o
    }

    /** True if the phone has working (validated) internet that this app may use right now. */
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun internet(context: Context): JSONObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val via = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
        return JSONObject()
            .put("via", via)
            .put("ok", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }

    private fun networkType(type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        else -> null
    }

    /** Human-readable lines, used by /status (in the chat's language) and the app screen (English). */
    fun lines(h: JSONObject, t: Lang = Lang.EN): List<String> {
        val lines = mutableListOf<String>()

        h.optJSONObject("battery")?.takeIf { it.has("level") }?.let { b ->
            val plugged = b.optString("plugged").takeIf { it != "none" }
            val left = b.optLong("left_min").takeIf { it > 0 }?.let { t(" · ~${duration(it, t)} left", " · осталось ~${duration(it, t)}") } ?: ""
            val status = b.optString("status")
            val statusText = if (!t.ru) status else when (status) {
                "charging" -> "заряжается"
                "discharging" -> "разряжается"
                "full" -> "заряжена"
                "not charging" -> "не заряжается"
                else -> status
            }
            lines += "🔋 ${b.getInt("level")}% · $statusText${plugged?.let { " ($it)" } ?: ""}$left · ${b.optDouble("temp_c")} °C"
            lines += listOfNotNull(
                "%.2f V".format(b.optDouble("voltage_v")),
                b.optInt("current_ma").takeIf { it > 0 }?.let { "$it mA" },
                b.optLong("full_in_min").takeIf { it > 0 }?.let { t("full in ${duration(it, t)}", "до полной ${duration(it, t)}") },
                t("health ", "состояние ") + b.optString("health"),
            ).joinToString(" · ", prefix = "    ")
        }

        h.optJSONObject("wifi")?.let { w ->
            lines += when {
                !w.optBoolean("enabled") -> t("📶 Wi-Fi off", "📶 Wi-Fi выключен")
                !w.optBoolean("connected") -> t("📶 Wi-Fi not connected", "📶 Wi-Fi не подключён")
                else -> "📶 " + listOfNotNull(
                    w.optString("ssid").ifEmpty { t("Wi-Fi (name hidden)", "Wi-Fi (имя скрыто)") },
                    "${w.optInt("rssi_dbm")} dBm (${w.optInt("level")}/4)",
                    w.optString("band").ifEmpty { null },
                    w.optInt("mbps").takeIf { it > 0 }?.let { t("$it Mbps", "$it Мбит/с") },
                    w.optString("ip").ifEmpty { null },
                ).joinToString(" · ")
            }
        }

        h.optJSONObject("mobile")?.let { m ->
            lines += if (m.optString("sim") != "ready") t("📱 Mobile: ", "📱 Мобильная связь: ") + m.optString("sim") else "📱 " + listOfNotNull(
                m.optString("operator").ifEmpty { t("Mobile", "Мобильная связь") },
                m.optString("type").ifEmpty { null },
                m.takeIf { it.has("level") }?.let { t("signal ", "сигнал ") + "${it.getInt("level")}/4" + (if (it.has("dbm")) " (${it.getInt("dbm")} dBm)" else "") },
                m.takeIf { it.has("data") }?.let { if (it.getBoolean("data")) t("data on", "данные вкл.") else t("data off", "данные выкл.") },
                t("roaming", "роуминг").takeIf { m.optBoolean("roaming") },
            ).joinToString(" · ")
        }

        h.optJSONObject("internet")?.let { n ->
            val via = n.optString("via")
            val viaText = if (t.ru && via == "mobile") "мобильную сеть" else via
            lines += if (via == "none") t("🌐 No internet", "🌐 Нет интернета")
            else t("🌐 Internet via $viaText ", "🌐 Интернет через $viaText ") + if (n.optBoolean("ok")) "✓" else t("(no access)", "(нет доступа)")
        }
        lines += t("⏱ Up ", "⏱ Работает ") + duration(h.optLong("uptime_min"), t)
        return lines
    }

    fun duration(minutes: Long, t: Lang = Lang.EN): String = when {
        minutes < 60 -> t("$minutes min", "$minutes мин")
        minutes < 24 * 60 -> t("${minutes / 60} h ${minutes % 60} min", "${minutes / 60} ч ${minutes % 60} мин")
        else -> t("${minutes / (24 * 60)} d ${minutes % (24 * 60) / 60} h", "${minutes / (24 * 60)} д ${minutes % (24 * 60) / 60} ч")
    }
}
