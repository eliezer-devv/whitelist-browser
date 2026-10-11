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

    // ---- Test versions: a phone (or admin app) set to get them updates to test builds too ----
    // Build APK normally makes a TEST build (a GitHub pre-release): phones never see it, except those with this on
    // (admin page inside the app → Settings → Your account → This device → Get test versions). "Release to
    // everyone" then makes it the normal release, for every phone.
    private const val TEST_PREFS = "updateChannel"
    fun testVersions(ctx: Context) = ctx.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).getBoolean("test", false)
    fun setTestVersions(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).edit().putBoolean("test", on).apply()
        AppLog.i("Update", if (on) "Test versions: on" else "Test versions: off")
    }
    @Volatile private var appCtx: Context? = null
    /** (So the update check knows this phone's choice: set once when the app starts.) */
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    /** Newest published release, or null if there isn't one. Blocking. With test versions on: test builds too. */
    fun fetchLatest(): Release? {
        val repo = repo() ?: throw IOException("Set GITHUB_USERNAME in Config.kt")
        val test = appCtx?.let { testVersions(it) } ?: false
        // The newest release holds both apps, each under its own name: each app takes only its own file
        // (whitelist-browser.apk or whitelist-admin.apk), so neither can ever pick up the other.
        if (test) runCatching { newestOf(repo) }.onFailure { AppLog.w("Update", "Couldn't look for test versions: ${it.message}") }.getOrNull()?.let { return it }
        val conn = open("https://api.github.com/repos/$repo/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw IOException("GitHub returned ${conn.responseCode}")
            return releaseFrom(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            conn.disconnect()
        }
    }

    /** The newest of the recent releases, test builds included (for phones with test versions on). */
    private fun newestOf(repo: String): Release? {
        val conn = open("https://api.github.com/repos/$repo/releases?per_page=15")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw IOException("GitHub returned ${conn.responseCode}")
            val list = org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            return (0 until list.length()).map { list.getJSONObject(it) }.filter { !it.optBoolean("draft") }
                .mapNotNull { runCatching { releaseFrom(it) }.getOrNull() }.maxByOrNull { it.versionCode }
        } finally {
            conn.disconnect()
        }
    }

    /** One release: its version, and this app's own file in it (null if it has none). */
    private fun releaseFrom(o: JSONObject): Release? {
        run {   // (one release's details)
            val label = o.getString("tag_name")                     // e.g. v1.0.42
            val code = Regex("(\\d+)$").find(label.trim())?.value?.toIntOrNull() ?: return null
            val version = Regex("(\\d+\\.\\d+\\.\\d+)$").find(label.trim())?.value ?: "1.0.$code"
            // (The admin app's file is named so it comes after the browser's: older browsers took the first file.)
            val want = if (BuildConfig.ADMIN_APP) listOf("whitelist-for-admins.apk", "whitelist-admin.apk") else listOf("whitelist-browser.apk")
            val assets = o.getJSONArray("assets")
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
            val apk = want.firstNotNullOfOrNull { w -> all.firstOrNull { it.getString("name") == w } } ?: return null   // only its own file, never the other app
            return Release(code, version, apk.getString("browser_download_url"))
        }
    }

    fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    /** A downloaded update Android refused (the wrong app's file, say): forgotten, so the next check downloads afresh. */
    fun forget(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("file", null)?.let { name -> ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { File(it, name).delete() } }
        p.getLong("id", -1L).takeIf { it >= 0 }?.let { id -> runCatching { ctx.getSystemService(android.app.DownloadManager::class.java).remove(id) } }
        p.edit().remove("id").remove("file").remove("version").putBoolean("pending", false).apply()
        AppLog.w("Update", "The downloaded update wasn't right for this app: it's downloaded again next time")
    }

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
            .setTitle("${ctx.getString(R.string.app_name)} ${r.versionName}")
            .setDescription("App update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, android.os.Environment.DIRECTORY_DOWNLOADS, name)
        val id = dm.enqueue(req)
        AppLog.i("Update", "Downloading version ${r.versionName} in the background")
        p.edit().putLong("id", id).putString("file", name).putString("version", r.versionName).apply()
        return id
    }

    /**
     * The download finished, or something that held it up has stopped: installed now, unless sound is playing (an
     * update closes the app, which would cut it off): then it waits until the playing notification goes.
     */
    fun installWhenFree(ctx: Context) {
        val apk = waiting(ctx) ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (PlaybackService.isPlaying) {
            if (!p.getBoolean("pending", false)) AppLog.i("Update", "Downloaded; installing once nothing's playing")
            p.edit().putBoolean("pending", true).apply()
            return
        }
        install(ctx, apk)
    }

    /** Android is asking about an update now (its "update?" screen), or has finished asking. */
    fun asking(ctx: Context, now: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("asking", if (now) System.currentTimeMillis() else 0L).apply()

    /** An update that waited for the sound to stop. */
    fun isPending(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pending", false)

    /** The update [r], already downloaded and waiting to be installed (or null). */
    fun readyFile(ctx: Context, r: Release): File? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("version", null) != r.versionName) return null
        val id = p.getLong("id", -1L).takeIf { it >= 0 } ?: return null
        return finished(ctx, id)
    }

    /**
     * A downloaded update, newer than this app, still waiting to be installed (the download finished with the app
     * closed, say, and Android's "update?" screen wasn't shown): its file, or null.
     */
    fun waiting(ctx: Context): File? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = p.getString("file", null) ?: return null
        val code = name.removePrefix("update-").removeSuffix(".apk").toIntOrNull() ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val id = p.getLong("id", -1L).takeIf { it >= 0 } ?: return null
        return finished(ctx, id)
    }

    /** Is the update [r] downloading right now? */
    fun downloading(ctx: Context, r: Release): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("version", null) != r.versionName) return false
        val pct = progress(ctx) ?: return false
        return pct < 100
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
     * Hands the APK to Android's installer, which refuses it unless it's signed with the same key as the installed
     * app. Android 12+: without asking, where Android allows it (when this app installed its current version);
     * otherwise Android asks first (InstallReceiver shows its screen, or "Tap to install").
     */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        // Android already asking about this update (in the last 2 minutes): not another (it asked twice in a row).
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("asking", 0L) < 120_000L) { AppLog.i("Update", "Android's already asking about it: not again"); return }
        // An older one left unfinished: closed, so only one is ever asked about.
        runCatching { installer.mySessions.forEach { installer.abandonSession(it.sessionId) } }.logged("Update", "Clearing old installs")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pending", false).apply()
        AppLog.i("Update", "Installing" + if (Build.VERSION.SDK_INT >= 31) " (without asking, if Android allows)" else "")
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
