package com.appcustom.whitelistbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * An app was installed or removed: check the browsers again straight away (so a newly installed browser is found and
 * blocked within moments, not only at the next few-minute check). Part of the supervised "other browsers" protection.
 */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.schemeSpecificPart == context.packageName) return   // our own update
        AppLog.ready(context)
        val ctx = context.applicationContext
        runCatching { Whitelist.loadCache(ctx) }                              // so protection settings are loaded
        Thread {
            runCatching { BrowserGuard.report(ctx) }.logged("Browsers", "After an app changed")
            runCatching { DeviceOwner.apply(ctx) }.logged("Browsers", "After an app changed")
        }.start()
    }
}
