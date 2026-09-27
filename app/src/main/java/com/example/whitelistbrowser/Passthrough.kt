package com.example.whitelistbrowser

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds where a stopped link really leads. Many links pass through other addresses first
 * (link shorteners, "you are leaving this site" pages, tracking redirects), and the whitelist stops
 * them at the first one that isn't allowed. [trace] follows the rest of the route in the background,
 * like a browser would but without showing anything, so a request can name the real destination
 * and every address on the way.
 */
object Passthrough {
    /** [hops]: every address before [destination], in order. */
    data class Route(val hops: List<String>, val destination: String)

    private val cache = ConcurrentHashMap<String, Route>()
    @Volatile var userAgent: String = "Mozilla/5.0"
    private const val MAX_HOPS = 8

    fun cached(url: String): Route? = cache[url]

    /**
     * Blocking; run off the main thread. [before] = addresses the phone already went through on the
     * way to [url] (from the link that was tapped). Never throws: on any problem, the route simply ends.
     */
    fun trace(url: String, before: List<String>): Route {
        cache[url]?.let { return it }
        val seen = mutableListOf(url)
        var current = url
        for (hop in 1..MAX_HOPS) {
            val next = runCatching { nextHop(current) }.getOrNull() ?: break
            if (next in seen || !(next.startsWith("http://") || next.startsWith("https://"))) break
            seen += next
            current = next
        }
        val route = Route((before + seen.dropLast(1)).distinct().filter { it != seen.last() }, seen.last())
        cache[url] = route
        return route
    }

    private val META_REFRESH = Regex("""<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["']?\s*\d*\s*;?\s*url\s*=\s*['"]?([^"'>\s]+)""", RegexOption.IGNORE_CASE)
    private val JS_REDIRECT = Regex("""(?:window\.|document\.|top\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']|location\.replace\(\s*["']([^"']+)["']\s*\)""")

    /** The next address after [url]: an HTTP redirect, or a "redirecting…" page. Null = this is the destination. */
    private fun nextHop(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "text/html,*/*")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return null
                return URL(URL(url), loc).toString()
            }
            if (code != 200 || conn.contentType?.contains("html", ignoreCase = true) != true) return null
            // Read the start of the page only: redirecting pages are small.
            val bytes = conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024); var n = 0
                while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
            val html = String(bytes)
            META_REFRESH.find(html)?.let { return URL(URL(url), it.groupValues[1].replace("&amp;", "&")).toString() }
            // Script redirects only count on small pages, which is what redirecting pages look like;
            // a real page with location code in its scripts isn't a pass-through.
            if (bytes.size < 20_000) {
                JS_REDIRECT.find(html)?.let { m ->
                    val target = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    if (target.isNotBlank()) return URL(URL(url), target.replace("\\/", "/").replace("&amp;", "&")).toString()
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }
}
