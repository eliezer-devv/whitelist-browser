package com.appcustom.whitelistbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * This app has just been updated (Android tells it, even when it isn't running): "Whitelist Browser updated to
 * 1.0.x", tapping it opens the app. (An update closes the app, so this says it happened.)
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLog.ready(context)
        AppLog.i("Update", "Updated to ${BuildConfig.VERSION_NAME}")
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        InstallReceiver.notify(context, "${context.getString(R.string.app_name)} updated to ${BuildConfig.VERSION_NAME}", "Tap to open it", open)
    }
}
