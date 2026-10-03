package com.appcustom.whitelistbrowser

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The app update has finished downloading (Android's download manager tells the app, even if it's closed): it's
 * handed to Android's installer. If the app isn't open, InstallReceiver shows "Tap to install".
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        AppLog.ready(context)
        val pending = goAsync()
        Thread {
            try {
                val apk = Updater.finished(context.applicationContext, id)   // only the update's own download
                if (apk != null) { AppLog.i("Update", "Downloaded; installing"); Updater.install(context.applicationContext, apk) }
            } catch (e: Exception) {
                AppLog.e("Update", "Installing failed", e)
                InstallReceiver.notify(context, "Update failed", e.message ?: "unknown error", null)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
