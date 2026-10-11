package com.appcustom.whitelistbrowser

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The screen cover: when a blocked app, or a settings screen that could switch this protection off, comes to the
 * front, it's covered by a plain full-screen panel that names this browser and says who manages it. A Back button
 * sends the phone Home. The panel is a system overlay, so it also covers split-screen and pop-up windows.
 *
 * This only ever looks at *which* app or settings screen is open — never what's typed or shown. It's part of a
 * supervised, parental-control setup and is always visible for what it is; the admin can switch it off remotely.
 */
class GuardService : AccessibilityService() {
    companion object {
        @Volatile var running = false
            private set
        // The settings screens where this protection could be switched off (covered so it can't be, unawares).
        private val GUARDED_CLASS_HINTS = listOf("accessibility", "deviceadmin", "installedappdetails", "appinfo", "manageapplications")
    }

    private var wm: WindowManager? = null
    private var cover: View? = null
    private var coveringFor: String? = null

    override fun onServiceConnected() {
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        AppLog.ready(applicationContext)
        AppLog.i("Cover", "Screen cover is on")
        runCatching { Requests.checkInSoon(applicationContext) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) { hide(); return }
        runCatching {
            if (!BrowserGuard.coverOn()) { hide(); return }
            val cls = event.className?.toString()?.lowercase().orEmpty()
            when {
                BrowserGuard.blocked(applicationContext, pkg) -> show(blockedTitle(pkg),
                    "Websites open in Whitelist Browser on this phone. You can ask to use this app.", "blocked:$pkg")
                isGuardedSetting(pkg, cls) -> show("This setting is locked",
                    "It's set by whoever manages Whitelist Browser on this phone.", "setting:$cls")
                else -> hide()
            }
        }.logged("Cover", "Checking the open app")
    }

    private fun blockedTitle(pkg: String): String {
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("This app")
        return "$label is blocked on this phone"
    }

    /** A settings screen that could switch this protection off (Accessibility, Device admin, this app's App info). */
    private fun isGuardedSetting(pkg: String, cls: String): Boolean {
        if (!pkg.contains("settings")) return false
        return GUARDED_CLASS_HINTS.any { cls.contains(it) }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        hide()
        AppLog.w("Cover", "Screen cover was switched off")
        runCatching { Requests.checkInSoon(applicationContext) }
        return super.onUnbind(intent)
    }

    private fun show(title: String, sub: String, forWhat: String) {
        if (coveringFor == forWhat && cover != null) return
        hide()
        val ctx = this
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (isDark()) Color.parseColor("#1F2B2C") else Color.parseColor("#F5F3EE"))
            val pad = (resources.displayMetrics.density * 28).toInt()
            setPadding(pad, pad, pad, pad)
            addView(TextView(ctx).apply {
                text = title; textSize = 23f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (isDark()) Color.parseColor("#ECF2F0") else Color.parseColor("#1C2B2D"))
            })
            addView(TextView(ctx).apply {
                text = sub; textSize = 15f; gravity = Gravity.CENTER
                setTextColor(if (isDark()) Color.parseColor("#AEBFBB") else Color.parseColor("#3E4B49"))
                val m = (resources.displayMetrics.density * 12).toInt(); setPadding(0, m, 0, m * 2)
            })
            addView(TextView(ctx).apply {
                text = "  Back  "; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                val h = (resources.displayMetrics.density * 50).toInt()
                val padx = (resources.displayMetrics.density * 26).toInt()
                minHeight = h; setPadding(padx, 0, padx, 0)
                background = Ui.rounded(Color.parseColor("#1F5F55"), resources.displayMetrics.density * 25)
                setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME) }
            })
        }
        val type = if (Build.VERSION.SDK_INT >= 22) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type,
            0,                                  // focusable and opaque: it swallows taps on whatever is behind it
            PixelFormat.OPAQUE)
        runCatching { wm?.addView(panel, lp); cover = panel; coveringFor = forWhat }.logged("Cover", "Showing the cover")
        // Blocked app: also step back to Home so the app isn't left running underneath.
        if (forWhat.startsWith("blocked:")) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun hide() {
        cover?.let { c -> runCatching { wm?.removeView(c) }.logged("Cover", "Removing the cover") }
        cover = null; coveringFor = null
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
}
