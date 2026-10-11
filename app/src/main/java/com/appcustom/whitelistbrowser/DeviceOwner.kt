package com.appcustom.whitelistbrowser

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager

/**
 * The managed browser as a device admin, and (when set up that way) a device owner, on a supervised phone.
 *
 * Device admin (the weaker, no-computer option): Android won't let an active device admin be uninstalled until it's
 * switched off as a device admin first — and that switch-off screen is covered by [GuardService]. Nothing else.
 *
 * Device owner (the stronger option, set up once with a computer): can switch blocked apps off so they won't open,
 * block its own uninstall outright, and keep automatic date and time on (so timers can't be dodged). Set up by
 * whoever manages the phone; undone from the admin page at any time, which puts the phone back to normal.
 */
object DeviceOwner {
    private const val TAG = "DeviceOwner"

    fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isAdminActive(ctx: Context): Boolean = runCatching { dpm(ctx).isAdminActive(component(ctx)) }.getOrDefault(false)
    fun isOwner(ctx: Context): Boolean = runCatching { dpm(ctx).isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

    /** The intent for the "Activate this device admin app?" screen (the no-computer uninstall protection). */
    fun addAdminIntent(ctx: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "This lets whoever manages this browser keep it from being removed. You can see and change this any time.")

    /**
     * Puts the current protection into effect (device owner only): switch blocked apps off, block this app's own
     * uninstall, keep automatic time on, and optionally ask before new apps install. Safe to call often. Call off
     * the main thread.
     */
    fun apply(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx); val cn = component(ctx)
        val p = Whitelist.state.protect
        runCatching { dpm.setUninstallBlocked(cn, ctx.packageName, p != null && p.mode != "off") }.logged(TAG, "Blocking its own uninstall")
        // Blocked apps: switched off (hidden) so they won't open at all. Allowed/again-permitted ones switched back on.
        if (Build.VERSION.SDK_INT >= 24) {
            val blocked = BrowserGuard.blockedPackages(ctx)
            for (pkg in blocked) runCatching { dpm.setApplicationHidden(cn, pkg, true) }.logged(TAG, "Switching an app off")
            // Anything we switched off before that's now allowed: back on.
            val were = ctx.getSharedPreferences("browser_guard", Context.MODE_PRIVATE).getStringSet("ownerHidden", emptySet()) ?: emptySet()
            for (pkg in were - blocked) runCatching { dpm.setApplicationHidden(cn, pkg, false) }.logged(TAG, "Switching an app back on")
            ctx.getSharedPreferences("browser_guard", Context.MODE_PRIVATE).edit().putStringSet("ownerHidden", blocked).apply()
        }
        // Keep the clock honest (so timers and "for a while" can't be dodged), unless the admin turned that off.
        runCatching {
            if (p?.lockClock != false) dpm.addUserRestriction(cn, UserManager.DISALLOW_CONFIG_DATE_TIME)
            else dpm.clearUserRestriction(cn, UserManager.DISALLOW_CONFIG_DATE_TIME)
            if (Build.VERSION.SDK_INT >= 28 && p?.lockClock != false) dpm.setAutoTimeRequired(cn, true)
        }.logged(TAG, "Locking the clock")
        // Optional: new apps need the admin's OK (so another browser can't just be installed).
        runCatching {
            if (p?.blockInstalls == true) dpm.addUserRestriction(cn, UserManager.DISALLOW_INSTALL_APPS)
            else dpm.clearUserRestriction(cn, UserManager.DISALLOW_INSTALL_APPS)
        }.logged(TAG, "The new-apps setting")
    }

    /** Give the phone back: switch every app back on, and drop the restrictions. Keeps device owner unless [release]. */
    fun clear(ctx: Context, release: Boolean) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx); val cn = component(ctx)
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                val were = ctx.getSharedPreferences("browser_guard", Context.MODE_PRIVATE).getStringSet("ownerHidden", emptySet()) ?: emptySet()
                for (pkg in were) runCatching { dpm.setApplicationHidden(cn, pkg, false) }.logged(TAG, "Switching an app back on")
                ctx.getSharedPreferences("browser_guard", Context.MODE_PRIVATE).edit().remove("ownerHidden").apply()
            }
            dpm.setUninstallBlocked(cn, ctx.packageName, false)
            dpm.clearUserRestriction(cn, UserManager.DISALLOW_CONFIG_DATE_TIME)
            dpm.clearUserRestriction(cn, UserManager.DISALLOW_INSTALL_APPS)
            if (release && Build.VERSION.SDK_INT >= 24) dpm.clearDeviceOwnerApp(ctx.packageName)
        }.logged(TAG, "Clearing protection")
    }
}

/**
 * The device admin (and, when set up, device owner) receiver. Being active here is what Android checks before letting
 * the app be uninstalled. [onDisableRequested] shows a plain message, so whoever uses the phone always sees that the
 * browser is managed — it never hides.
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Whitelist Browser is managed by whoever set it up. Turning this off removes its protection; they'll be told."

    override fun onEnabled(context: Context, intent: Intent) {
        AppLog.ready(context); AppLog.i("DeviceOwner", "Device admin turned on")
        runCatching { Requests.checkInSoon(context) }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        AppLog.ready(context); AppLog.w("DeviceOwner", "Device admin was turned off — protection is reduced")
        runCatching { Requests.checkInSoon(context) }   // tell the admin page its protection changed
    }
}
