package com.appcustom.whitelistbrowser

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
    private const val PREFS = "update"

    /**
     * Downloads the update with Android's own download manager, so it carries on if the app is minimised or closed
     * (with Android's progress notification). When it's done, UpdateDownloadReceiver installs it.
     */
    fun startDownload(ctx: Context, r: Release): Long {
        val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getLong("id", -1L).takeIf { it >= 0 }?.let { runCatching { dm.remove(it) } }      // an earlier one: replaced
        val name = "update-${r.versionCode}.apk"
        ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { File(it, name).delete() }
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(r.apkUrl))
            .setTitle("Whitelist Browser ${r.versionName}")
            .setDescription("App update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, android.os.Environment.DIRECTORY_DOWNLOADS, name)
        val id = dm.enqueue(req)
        AppLog.i("Update", "Downloading version ${r.versionName} in the background")
        p.edit().putLong("id", id).putString("file", name).putString("version", r.versionName).apply()
        return id
    }

    /** How far the update's download has got: a percentage, or null if there isn't one going. */
    fun progress(ctx: Context): Int? {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("id", -1L).takeIf { it >= 0 } ?: return null
        val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
        dm.query(android.app.DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
            if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) return 100
            if (status == android.app.DownloadManager.STATUS_FAILED) return null
            val done = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return if (total > 0) (done * 100 / total).toInt() else 0
        }
        return null
    }

    /** The finished download (if [id] is the update's), or null. */
    fun finished(ctx: Context, id: Long): File? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getLong("id", -1L) != id) return null
        val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
        val ok = dm.query(android.app.DownloadManager.Query().setFilterById(id))?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)) == android.app.DownloadManager.STATUS_SUCCESSFUL
        } ?: false
        if (!ok) return null
        return ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { File(it, p.getString("file", "update.apk")!!) }?.takeIf { it.exists() }
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
