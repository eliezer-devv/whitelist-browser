package com.example.whitelistbrowser

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

object Updater {
    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

    /** The app's own repo ("owner/repo") from Config. */
    fun repo(): String? = Config.GITHUB_REPO.takeUnless { Config.GITHUB_USERNAME == "YOUR_USERNAME" }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WhitelistBrowser/${BuildConfig.VERSION_NAME}")
        }

    /** Newest published release, or null if there isn't one. Blocking. */
    fun fetchLatest(): Release? {
        val repo = repo() ?: throw IOException("Set GITHUB_USERNAME in Config.kt")
        val conn = open("https://api.github.com/repos/$repo/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw IOException("GitHub returned ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name")                       // e.g. v1.0.42
            val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
            val assets = o.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(code, tag.removePrefix("v"), a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    /** Downloads the APK into app-private storage. Blocking. */
    fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update.apk")
        val conn = open(r.apkUrl)
        try {
            if (conn.responseCode != 200) throw IOException("Download failed: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Hands the APK to Android's installer. Android shows its own confirmation screen and
     * refuses the update unless it's signed with the same key as the installed app.
     */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, sessionId, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }
}
