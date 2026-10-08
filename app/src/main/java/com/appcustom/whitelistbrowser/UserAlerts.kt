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

/**
 * Notifications for the person using the phone: their request was answered, while the app isn't on screen (in it,
 * the app shows the answer itself). A background check (every 15 minutes or so, the least Android allows) runs only
 * while they have requests waiting.
 */
object UserAlerts {
    private const val CHANNEL = "answers"
    private const val JOB_ID = 4421

    /** Is the app on screen? (Set by the main screen.) */
    @Volatile var appVisible = false

    /** The background check: on while requests are waiting, off otherwise. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val waiting = MyRequests.all(ctx).any { !it.archived && it.status == "waiting" }
        if (!waiting) { if (js.getPendingJob(JOB_ID) != null) js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AnswerCheckJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(job) }.onFailure { AppLog.e("Notifications", "Couldn't set up the answers check", it) }
    }

    /** Looks for answers, and shows a notification for each new one (unless the app is on screen). Blocking. */
    fun check(ctx: Context) {
        val before = MyRequests.all(ctx).filter { it.status == "waiting" }.map { it.number }.toSet()
        if (before.isEmpty()) { schedule(ctx); return }
        MyRequests.check(ctx, 0L)
        // Approved: the change itself comes with the lists, so it's there when they tap.
        runCatching { Whitelist.refresh(ctx) }
        if (!appVisible) {
            MyRequests.all(ctx).filter { it.number in before && it.status != "waiting" }.forEach { item ->
                val title = when (item.status) {
                    "approved" -> "Request approved"
                    "denied" -> "Request not approved"
                    else -> "Your request was answered"
                }
                notify(ctx, title, item.summary + (item.message.takeIf { it.isNotBlank() && item.status != "approved" }?.let { ": $it" } ?: ""), item.number)
            }
        }
        schedule(ctx)
    }

    private fun notify(ctx: Context, title: String, text: String, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Answers to my requests", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a request you sent is approved or answered" })
        }
        val open = PendingIntent.getActivity(ctx, 20_000 + id, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else android.app.Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_d_check).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
        runCatching { nm.notify(20_000 + id, b.build()) }
        AppLog.i("Notifications", "Shown: $title")
    }
}

/** The background check for answers (Android runs it every 15 minutes or so, when online). */
class AnswerCheckJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                AppLog.ready(applicationContext)
                Whitelist.loadCache(applicationContext)                 // (the app may be closed)
                UserAlerts.check(applicationContext)
            } catch (e: Exception) {
                AppLog.e("Notifications", "Answers check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters?) = true
}
