package com.example.whitelistbrowser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Blocks ads and trackers: anything a page loads from a domain on the ad list (scripts, images,
 * frames, tracking pixels) gets an empty answer instead.
 *
 * The list is the AdGuard DNS filter, the list behind AdGuard DNS and AdGuard Home. A copy is packed
 * into the app when it's built, so blocking works from the first launch, and the phone fetches a
 * fresh one weekly.
 */
object AdBlock {
    private const val LIST_URL = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
    private const val FILE = "adblock-adguard.txt"
    private const val REFRESH_MS = 7 * 24 * 3_600_000L

    @Volatile private var block: Set<String> = emptySet()   // domains to block
    @Volatile private var allow: Set<String> = emptySet()   // domains AdGuard says never to block (@@ rules)
    val blockedCount = AtomicInteger(0)

    /** Loads the saved list, or the copy packed into the app. Run off the main thread. */
    fun load(ctx: Context) {
        val saved = File(ctx.filesDir, FILE)
        val text = if (saved.exists()) saved.readText()
            else runCatching { ctx.assets.open(FILE).bufferedReader().use { it.readText() } }.getOrNull()
        text?.let { apply(it) }
    }

    /** Downloads a fresh list once a week. Run off the main thread. Keeps the old list on failure. */
    fun refreshIfDue(ctx: Context) {
        val saved = File(ctx.filesDir, FILE)
        if (saved.exists() && System.currentTimeMillis() - saved.lastModified() < REFRESH_MS) return
        val conn = URL(LIST_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) throw IOException("Ad list download failed: HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (!apply(text)) throw IOException("Ad list looks wrong")
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(saved)
        } finally {
            conn.disconnect()
        }
    }

    private val DOMAIN = Regex("^[a-z0-9.-]+\\.[a-z0-9-]+$")

    /**
     * Reads AdGuard rules: "||ads.example.com^" blocks, "@@||example.com^" never blocks, "!" starts a
     * comment. Rules with wildcards, paths or conditions are skipped (this blocker works by whole
     * domains, like AdGuard DNS). Returns false, and changes nothing, if the list looks broken.
     */
    private fun apply(text: String): Boolean {
        val b = HashSet<String>(150_000)
        val a = HashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val exception = line.startsWith("@@||")
            if (!exception && !line.startsWith("||")) return@forEach
            val body = line.removePrefix("@@").removePrefix("||")
            val options = body.substringAfter('$', "")
            if (options.isNotEmpty() && options != "important") return@forEach
            val d = body.substringBefore('$').removeSuffix("|").removeSuffix("^").lowercase()
            if (!DOMAIN.matches(d)) return@forEach
            if (exception) a += d else b += d
        }
        if (b.size < 1000) return false
        block = b
        allow = a
        return true
    }

    /**
     * Is this an ad or tracker host? Checks the host and each parent domain, so "doubleclick.net"
     * also covers "ad.doubleclick.net". AdGuard's exceptions and the admin page's
     * "Never block these" always win.
     */
    fun isAd(host: String, exceptions: List<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (exceptions.any { h == it || h.endsWith(".$it") }) return false
        var d = h
        var blocked = false
        while ('.' in d) {
            if (d in allow) return false
            if (d in block) blocked = true
            d = d.substringAfter('.')
        }
        return blocked
    }

    fun blockedResponse(): WebResourceResponse {
        blockedCount.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
