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
 * Read on demand (/status, /settings) - nothing runs in the background.
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

    /** Human-readable lines, used by /status and the app screen. */
    fun lines(h: JSONObject): List<String> {
        val lines = mutableListOf<String>()

        h.optJSONObject("battery")?.takeIf { it.has("level") }?.let { b ->
            val plugged = b.optString("plugged").takeIf { it != "none" }
            lines += "🔋 ${b.getInt("level")}% · ${b.optString("status")}${plugged?.let { " ($it)" } ?: ""} · ${b.optDouble("temp_c")} °C"
            lines += listOfNotNull(
                "%.2f V".format(b.optDouble("voltage_v")),
                b.optInt("current_ma").takeIf { it > 0 }?.let { "$it mA" },
                b.optLong("full_in_min").takeIf { it > 0 }?.let { "full in ${duration(it)}" },
                "health ${b.optString("health")}",
            ).joinToString(" · ", prefix = "    ")
        }

        h.optJSONObject("wifi")?.let { w ->
            lines += when {
                !w.optBoolean("enabled") -> "📶 Wi-Fi off"
                !w.optBoolean("connected") -> "📶 Wi-Fi not connected"
                else -> "📶 " + listOfNotNull(
                    w.optString("ssid").ifEmpty { "Wi-Fi (name hidden)" },
                    "${w.optInt("rssi_dbm")} dBm (${w.optInt("level")}/4)",
                    w.optString("band").ifEmpty { null },
                    w.optInt("mbps").takeIf { it > 0 }?.let { "$it Mbps" },
                    w.optString("ip").ifEmpty { null },
                ).joinToString(" · ")
            }
        }

        h.optJSONObject("mobile")?.let { m ->
            lines += if (m.optString("sim") != "ready") "📱 Mobile: ${m.optString("sim")}" else "📱 " + listOfNotNull(
                m.optString("operator").ifEmpty { "Mobile" },
                m.optString("type").ifEmpty { null },
                m.takeIf { it.has("level") }?.let { "signal ${it.getInt("level")}/4" + (if (it.has("dbm")) " (${it.getInt("dbm")} dBm)" else "") },
                m.takeIf { it.has("data") }?.let { if (it.getBoolean("data")) "data on" else "data off" },
                "roaming".takeIf { m.optBoolean("roaming") },
            ).joinToString(" · ")
        }

        h.optJSONObject("internet")?.let { n ->
            lines += if (n.optString("via") == "none") "🌐 No internet"
            else "🌐 Internet via ${n.optString("via")} " + if (n.optBoolean("ok")) "✓" else "(no access)"
        }
        lines += "⏱ Up ${duration(h.optLong("uptime_min"))}"
        return lines
    }

    private fun duration(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "${minutes / (24 * 60)} d ${minutes % (24 * 60) / 60} h"
    }
}
