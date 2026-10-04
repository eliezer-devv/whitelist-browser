package com.appcustom.whitelistbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Receives progress from Android's installer after Updater.install(). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.ready(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Updater.asking(context, status == PackageInstaller.STATUS_PENDING_USER_ACTION)   // (finished, or asking now)
        AppLog.i("Update", "Installer: status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Show Android's "Do you want to update this app?" screen.
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) {
                    // The app open: its screen opens Android's "update?" screen (a background part of an app often
                    // isn't allowed to). Otherwise a notification does it ("Tap to install").
                    val show = showConfirm
                    if (show != null) android.os.Handler(android.os.Looper.getMainLooper()).post { show(confirm) }
                    else notify(context, "Update ready", "Tap to install the new version of Whitelist Browser", confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android restarts the app on the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // user tapped Cancel
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                toast(context, "Update refused: it's signed with a different key. Uninstall this app, then install the latest version from GitHub.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                toast(context, "Update failed: $msg")
            }
        }
    }

    private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

    companion object {
        /** Set while the app's screen is open: it opens Android's "update?" screen itself. */
        @Volatile var showConfirm: ((Intent) -> Unit)? = null
        private const val CHANNEL = "updates"
        private const val ID = 4418

        /** A notification about the app update ([open]: what tapping it opens). */
        fun notify(ctx: Context, title: String, text: String, open: Intent?) {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "App updates", android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else android.app.Notification.Builder(ctx)
            b.setSmallIcon(R.drawable.ic_d_update).setContentTitle(title).setContentText(text).setAutoCancel(true)
            if (open != null) b.setContentIntent(android.app.PendingIntent.getActivity(ctx, 7, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            runCatching { nm.notify(ID, b.build()) }
        }
    }
}
