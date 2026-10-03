package com.appcustom.whitelistbrowser

import android.content.Context
import android.os.Build
import java.io.File

/**
 * A small rolling log kept on the phone, so problems that don't crash (a download that fails, a list that doesn't
 * load, a notification Android refuses) can be seen afterwards: About this phone → Share log. Site names only (never
 * full addresses or anything typed). About 400 KB at most: the oldest half goes when it's full.
 */
object AppLog {
    private const val MAX_BYTES = 400_000L
    private var file: File? = null
    private var started = false
    private val time = java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.UK)

    /** For parts of the app that can run with it closed (receivers, the media service): the log, without the banner. */
    fun ready(ctx: Context) { if (file == null) file = File(ctx.applicationContext.filesDir, "app-log.txt") }

    /** Starts the log for this run: the app, the phone and its browser engine. */
    fun start(ctx: Context) {
        if (file != null && started) return
        started = true
        ready(ctx)
        val engine = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(ctx)?.let { "${it.packageName} ${it.versionName}" } }.getOrNull()
        i("App", "Started: version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.RELEASE} " +
            "(SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}; browser engine: ${engine ?: "unknown"}")
    }

    fun i(area: String, text: String) = write("  ", area, text)
    fun w(area: String, text: String) = write("! ", area, text)
    fun e(area: String, text: String, t: Throwable? = null) =
        write("!!", area, text + (t?.let { " (${it.javaClass.simpleName}: ${it.message}${where(it)})" } ?: ""))

    /** Where in the app's own code an error happened (its first line there). */
    private fun where(t: Throwable): String =
        t.stackTrace.firstOrNull { it.className.startsWith("com.appcustom") }?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""

    /** A site's name from an address (the log never keeps full addresses). */
    fun site(url: String?): String = url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }?.removePrefix("www.") ?: "(none)"

    @Synchronized private fun write(level: String, area: String, text: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {                                 // full: keep the newer half
                val keep = f.readText().let { it.substring(it.length / 2) }
                f.writeText("(older entries removed)\n" + keep.substringAfter('\n'))
            }
            f.appendText("${time.format(java.util.Date())} $level [$area] ${text.replace('\n', ' ')}\n")
        }
    }

    /** The whole log, for sharing (newest last). */
    @Synchronized fun text(): String = runCatching { file?.readText() }.getOrNull().orEmpty()
}
