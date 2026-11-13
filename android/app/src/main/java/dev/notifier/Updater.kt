package dev.notifier

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases, without root: the release's APK is downloaded and handed to PackageInstaller.
 * Android 12+ lets an app update itself silently; older versions show the system "Update this app?" dialog,
 * opened from a notification ([UpdateReceiver]). Android refuses an APK signed with a different key.
 *
 * Releases come from .github/workflows/android-release.yml, one per tag vX.Y.Z. The version code is derived
 * from the tag by [versionCode] — the same formula is in app/build.gradle.kts.
 */
object Updater {

    data class Release(val version: String, val code: Long, val apkUrl: String)

    @Volatile
    private var busy = false

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun installedCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** "v1.2.3" -> 10203; null if the tag isn't a version. */
    fun versionCode(tag: String): Long? {
        val parts = tag.trim().removePrefix("v").split('.')
        if (parts.size !in 1..3) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..99 } ?: return null } + List(3 - parts.size) { 0 }
        return n[0] * 10_000L + n[1] * 100L + n[2]
    }

    /** Latest release of [repo] ("owner/name"), or null if it has none. */
    fun latest(repo: String): Release? {
        val json = JSONObject(get("https://api.github.com/repos/$repo/releases/latest") ?: return null)
        val tag = json.getString("tag_name")
        val code = versionCode(tag) ?: throw IOException("Release tag $tag is not vX.Y.Z")
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?: throw IOException("Release $tag has no APK")
        return Release(tag.removePrefix("v"), code, apk.getString("browser_download_url"))
    }

    /** Checks for a newer release and installs it if [install]. Blocking; returns a message for the user. */
    fun check(context: Context, install: Boolean): String {
        val prefs = Prefs(context)
        prefs.lastUpdateCheck = System.currentTimeMillis()
        val current = installedVersion(context)
        val release = latest(prefs.updateRepo) ?: return "No releases in ${prefs.updateRepo} yet."
        if (release.code <= installedCode(context)) return "Notifier $current is up to date."
        if (!install) return "Notifier ${release.version} is available (you have $current). Send /update to install."
        install(context, release)
        return "Installing Notifier ${release.version} (you have $current)…" +
            if (silent) "" else "\nTap “Update” on the phone to finish (Android ${Build.VERSION.RELEASE} asks once per update)."
    }

    /** Daily check from the service: announces a new version in the chat once, and installs it if allowed. */
    fun autoCheck(context: Context) {
        val prefs = Prefs(context)
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < CHECK_EVERY_MS) return
        if (prefs.botToken.isBlank() || prefs.chatId.isBlank()) return
        try {
            prefs.lastUpdateCheck = System.currentTimeMillis()
            val release = latest(prefs.updateRepo) ?: return
            if (release.code <= installedCode(context) || release.version == prefs.announcedUpdate) return
            prefs.announcedUpdate = release.version
            val message = check(context, install = prefs.autoUpdate)
            Telegram.sendMessage(prefs.botToken, prefs.chatId, "⬆️ " + escapeHtml(message), silent = false)
        } catch (e: Exception) {
            prefs.updateStatus = "check failed: ${e.message}"
        }
    }

    /** Android 12+ installs updates of the installing app itself without asking. */
    private val silent get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun install(context: Context, release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            throw IOException("Notifier isn't allowed to install updates: open the app → Settings → Allow installing updates")
        }
        if (busy) throw IOException("An update is already being installed")
        busy = true
        try {
            val prefs = Prefs(context)
            prefs.updateStatus = "downloading ${release.version}"
            val apk = File(context.cacheDir, "update.apk")
            download(release.apkUrl, apk)

            // Only ever install a newer Notifier, whatever the release contains.
            val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
                ?: throw IOException("Downloaded file is not an APK")
            if (info.packageName != context.packageName) throw IOException("APK is ${info.packageName}, not Notifier")
            if (info.longVersionCode != release.code) throw IOException("APK version ${info.longVersionCode} doesn't match tag ${release.version}")

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (silent) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("notifier.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // The system fills in the result, so the PendingIntent must be mutable.
                val result = Intent(context, UpdateReceiver::class.java)
                    .setAction(UpdateReceiver.ACTION_INSTALL_RESULT)
                    .putExtra(UpdateReceiver.EXTRA_VERSION, release.version)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
            }
            prefs.updateStatus = "installing ${release.version}"
        } catch (e: Exception) {
            Prefs(context).updateStatus = "failed: ${e.message}"
            throw e
        } finally {
            busy = false
        }
    }

    /** Body of a GET, or null on 404. */
    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) throw IOException("GitHub: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        val conn = URL(url).openConnection() as HttpURLConnection  // follows GitHub's redirect to its file host
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) throw IOException("Download: HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    const val CHECK_EVERY_MS = 24 * 60 * 60_000L
}
