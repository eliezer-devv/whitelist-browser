package com.appcustom.whitelistbrowser

import android.content.Context

/**
 * Records a crash as it happens (when, which part of the app, the error), so it can be seen in About this phone and
 * copied to whoever looks after the app. Android then handles the crash as usual.
 */
object CrashLog {
    private const val PREFS = "crash"
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val when_ = java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.UK).format(java.util.Date())
                val ours = e.stackTrace.filter { it.className.startsWith("com.appcustom") }.take(6)
                val cause = generateSequence(e) { it.cause }.last()
                val text = buildString {
                    append("$when_ (version ${BuildConfig.VERSION_NAME}, thread ${t.name})\n")
                    append("${e.javaClass.simpleName}: ${e.message}\n")
                    if (cause !== e) append("caused by ${cause.javaClass.simpleName}: ${cause.message}\n")
                    (ours.ifEmpty { e.stackTrace.take(6).toList() }).forEach { append("at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}\n") }
                }
                // commit(): written before the app closes
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", text.trim()).putBoolean("unsent", true).commit()
                AppLog.e("Crash", text.trim())
            }
            before?.uncaughtException(t, e)
        }
    }

    /** A crash not sent to the admin yet: true once (it's then marked sent). */
    fun takeUnsent(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("unsent", false)) return false
        p.edit().putBoolean("unsent", false).apply()
        return true
    }

    /** The last crash recorded, or null. */
    fun last(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)
}
