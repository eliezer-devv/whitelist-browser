package com.appcustom.whitelistbrowser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * A blocklist of whole domains, in AdGuard's format ("||domain^" blocks, "@@||domain^" never blocks).
 * Anything a page loads from a listed domain (scripts, images, frames, pixels) gets an empty answer.
 * A copy is packed into the app when it's built, so blocking works from the first launch, and the
 * phone fetches a fresh one weekly. Used for [AdBlock] and the content [Filters].
 */
open class DomainList(private val listUrl: String, private val file: String, private val what: String) {
    private val refreshMs = 7 * 24 * 3_600_000L

    @Volatile private var block: Set<String> = emptySet()   // domains to block
    @Volatile private var allow: Set<String> = emptySet()   // domains the list says never to block (@@ rules)
    val blockedCount = AtomicInteger(0)

    /** Loads the saved list, or the copy packed into the app. Run off the main thread. */
    fun load(ctx: Context) {
        val saved = File(ctx.filesDir, file)
        val text = if (saved.exists()) saved.readText()
            else runCatching { ctx.assets.open(file).bufferedReader().use { it.readText() } }.getOrNull()
        text?.let { apply(it) }
    }

    /** Downloads a fresh list once a week. Run off the main thread. Keeps the old list on failure. */
    fun refreshIfDue(ctx: Context) {
        val saved = File(ctx.filesDir, file)
        if (saved.exists() && System.currentTimeMillis() - saved.lastModified() < refreshMs) return
        val conn = URL(listUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) throw IOException("$what list download failed: HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (!apply(text)) throw IOException("$what list looks wrong")
            val tmp = File(ctx.filesDir, "$file.tmp")
            tmp.writeText(text)
            tmp.renameTo(saved)
        } finally {
            conn.disconnect()
        }
    }

    private val domain = Regex("^[a-z0-9.-]+\\.[a-z0-9-]+$")

    /**
     * Reads the rules: "||ads.example.com^" blocks, "@@||example.com^" never blocks, "!" starts a
     * comment. Rules with wildcards, paths or conditions are skipped (this works by whole domains,
     * like a DNS blocker). Returns false, and changes nothing, if the list looks broken.
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
            if (!domain.matches(d)) return@forEach
            if (exception) a += d else b += d
        }
        if (b.size < 1000) return false
        block = b
        allow = a
        return true
    }

    /**
     * Is this host on the list? Checks the host and each parent domain, so "example.com" also covers
     * "img.example.com". The list's own exceptions and the admin page's "Never block these" always win.
     */
    fun blocks(host: String, exceptions: List<String>): Boolean {
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

/** Ads and trackers: the AdGuard DNS filter, the list behind AdGuard DNS and AdGuard Home. */
object AdBlock : DomainList("https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", "adblock-adguard.txt", "Ad") {
    fun isAd(host: String, exceptions: List<String>) = blocks(host, exceptions)
}

// The content filters (admin page: Settings → Filters). Each is one or more lists; a site on any of them
// is blocked. Their lists are only loaded when the filter is on, since they're large.
object AdultHagezi : DomainList("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/nsfw.txt", "adult-hagezi.txt", "Adult content (HaGeZi)")
object AdultOisd : DomainList("https://nsfw.oisd.nl/", "adult-oisd.txt", "Adult content (OISD)")
object GamblingList : DomainList("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/gambling.txt", "gambling-hagezi.txt", "Gambling")
object MalwareList : DomainList("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.mini.txt", "malware-hagezi.txt", "Malware")

/** A content filter made of one or more lists. */
class Filter(private vararg val lists: DomainList) {
    val blockedCount = AtomicInteger(0)
    @Volatile private var loaded = false

    /** Loads its lists the first time it's needed (saved copy, or the one packed into the app). */
    @Synchronized fun ensureLoaded(ctx: Context) {
        if (loaded) return
        lists.forEach { runCatching { it.load(ctx) } }
        loaded = true
    }

    /** A fresh copy of each list, weekly. Only for filters that are on. */
    fun refreshIfDue(ctx: Context) = lists.forEach { runCatching { it.refreshIfDue(ctx) } }

    fun blocks(host: String, exceptions: List<String>) = loaded && lists.any { it.blocks(host, exceptions) }

    fun blockedResponse(): WebResourceResponse {
        blockedCount.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

object Filters {
    val adult = Filter(AdultHagezi, AdultOisd)   // the two lists Mullvad's and HaGeZi's services use
    val gambling = Filter(GamblingList)
    val malware = Filter(MalwareList)
}
