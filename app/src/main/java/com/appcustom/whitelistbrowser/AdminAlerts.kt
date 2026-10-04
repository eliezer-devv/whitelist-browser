package com.appcustom.whitelistbrowser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone notifications for admin phones (admin page: Phones → the phone → Admin phone): new requests, new phones, logs
 * someone sent, and crashes. GitHub's automation seals a short note for each admin phone (only it can read it); the
 * phone looks for new ones every minute while the app is open, and in the background every 15 minutes or so (the
 * least Android allows). Tapping one opens the admin screen (which still needs its PIN).
 */
object AdminAlerts {
    const val ACTION_OPEN_ADMIN = "com.appcustom.whitelistbrowser.OPEN_ADMIN"
    private const val PREFS = "adminAlerts"
    private const val CHANNEL = "requests"
    private const val JOB_ID = 4420

    /** Is this an admin phone (set on the admin page; it arrives in this phone's sealed lists)? */
    fun isAdminPhone(): Boolean = Whitelist.state.adminPhone

    /** Phone notifications: on unless switched off in Settings (admin phones only). */
    fun wanted(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)
    fun setWanted(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        schedule(ctx)
    }
    fun active(ctx: Context) = isAdminPhone() && wanted(ctx) && Requests.isSetUp()

    /** The background check: on for an admin phone that wants them, off otherwise. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!active(ctx)) { if (js.getPendingJob(JOB_ID) != null) js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AdminCheckJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(job) }.onFailure { AppLog.e("Notifications", "Couldn't set up the background check", it) }
    }

    /** Looks for new notes for this phone, and shows each as a notification. Blocking. */
    @Synchronized fun check(ctx: Context) {
        if (!active(ctx)) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = p.getString("since", null)
        if (since == null) { p.edit().putString("since", iso(now)).apply(); return }      // from now on (not earlier ones)
        val raw = runCatching { Requests.read("issues/comments?since=$since&sort=created&direction=asc&per_page=100") }
            .onFailure { AppLog.w("Notifications", "Couldn't check: ${it.message}") }.getOrNull() ?: return
        val marker = Regex("<!-- whitelist-admin-${Regex.escape(Device.id(ctx))}\\s*([\\s\\S]*?)-->")
        var latest = since
        val list = JSONArray(raw)
        for (i in 0 until list.length()) {
            val c = list.getJSONObject(i)
            val created = c.optString("created_at")
            if (created > latest) latest = created
            if (created <= since) continue
            val m = marker.find(c.optString("body")) ?: continue
            val note = runCatching { Seal.open(JSONObject(m.groupValues[1].trim())) }.getOrNull() ?: continue
            notify(ctx, note.optString("title"), note.optString("text"), note.optInt("number", i))
        }
        if (latest != since) p.edit().putString("since", latest).apply()
    }

    private fun iso(t: Long) = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t))

    private fun notify(ctx: Context, title: String, text: String, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Requests and logs (admin phone)", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "New requests, new phones, logs someone sent, and crashes" })
        }
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).setAction(ACTION_OPEN_ADMIN)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else android.app.Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_d_inbox).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
        runCatching { nm.notify(10_000 + id, b.build()) }
        AppLog.i("Notifications", "Shown: $title")
    }
}

/** The background check for admin phones (Android runs it every 15 minutes or so, when online). */
class AdminCheckJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                AppLog.ready(applicationContext)
                Whitelist.loadCache(applicationContext)                 // (the app may be closed)
                AdminAlerts.check(applicationContext)
            } catch (e: Exception) {
                AppLog.e("Notifications", "Background check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters?) = true
}
