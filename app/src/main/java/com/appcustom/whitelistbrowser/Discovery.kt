package com.appcustom.whitelistbrowser

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finding a website by what it's about ("maths homework help", "nasa"), for people who don't know its address. No key
 * or account: DuckDuckGo's plain results page (with Safe Search on strict), each result reduced to its site.
 * Results are sites, never pages: address, name and a short description.
 */
object Discovery {
    data class Site(val domain: String, val name: String, val description: String)
    /** One search result (text search): a page, with its title and snippet. Several can be on the same site. */
    data class Page(val url: String, val domain: String, val title: String, val snippet: String)

    /**
     * DuckDuckGo wants to check a person is searching (too many searches from this internet connection). The app never
     * answers it itself: the person does, in a small window showing DuckDuckGo's own check ([url]); then searching
     * carries on, with the pass DuckDuckGo gives (a cookie, shared with that window).
     */
    class Challenge(val url: String) : java.io.IOException("asked to prove it's a person")

    private const val AGENT = "WhitelistBrowser/${BuildConfig.VERSION_NAME} (a family/school browser; site discovery)"

    /**
     * One page of sites for [query], best first: [page] 0, 1, 2… (about 30 results a page, fewer sites). Blocking;
     * throws when DuckDuckGo can't be reached (offline, or it's turning the phone away for now).
     */
    fun searchPage(query: String, page: Int): List<Site> {
        val q = query.trim().take(100)
        if (q.isEmpty()) return emptyList()
        // The same words again (searching again, or back and forth): the answer from the last 15 minutes.
        val key = "${q.lowercase()}|$page"
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000L }?.let { return it.second } }
        return pageFresh(q, page).also { list -> if (list.isNotEmpty()) synchronized(cache) { cache[key] = System.currentTimeMillis() to list } }
    }

    /**
     * Text search: one page of results for [query] ([page] 0, 1, 2…), as pages (several from one site is fine), in
     * DuckDuckGo's order, ads left out, Safe Search strict. Blocking; throws [Challenge] or when it can't be reached.
     */
    fun searchPages(query: String, page: Int): List<Page> {
        val q = query.trim().take(200)
        if (q.isEmpty()) return emptyList()
        val key = "${q.lowercase()}|$page"
        synchronized(pageCache) { pageCache[key]?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000L }?.let { return it.second } }
        val got = runCatching { pagesOf(q, page) }
            .recoverCatching { if (it is Challenge) throw it
                AppLog.w("Search", "DuckDuckGo didn't answer (${it.message}); trying again"); Thread.sleep(800); pagesOf(q, page) }
            .onFailure { if (it !is Challenge) AppLog.w("Search", "DuckDuckGo didn't answer again (${it.message})") }
            .getOrThrow()
        if (got.isNotEmpty()) synchronized(pageCache) { pageCache[key] = System.currentTimeMillis() to got }
        return got
    }
    private fun pagesOf(q: String, page: Int): List<Page> {
        val url = searchUrl(q, page)
        val html = get(url, accept = "text/html", agent = BROWSER_AGENT, cookies = true)
        if (isChallenge(html)) throw Challenge(url)
        return parsePages(html)
    }
    private val pageCache = object : LinkedHashMap<String, Pair<Long, List<Page>>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, List<Page>>>?) = size > 30
    }

    /** Reads DuckDuckGo's results page as pages: each result's address, title and snippet, in order (ads left out). */
    fun parsePages(html: String): List<Page> {
        val out = LinkedHashMap<String, Page>()
        val blocks = html.split(Regex("<div[^>]+class=\"[^\"]*?\\bresult\\b")).drop(1)
        for (b in blocks) {
            val head = b.substringBefore('>')
            if (head.contains("result--ad")) continue
            val a = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b)
                ?: Regex("<a[^>]+href=\"([^\"]+)\"[^>]+class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b) ?: continue
            val url = realUrl(unescape(a.groupValues[1])) ?: continue
            val domain = domainOf(url) ?: continue
            if (domain == "duckduckgo.com" || url in out) continue
            val title = plain(a.groupValues[2])
            val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
            out[url] = Page(url, domain, title.ifBlank { domain }, snippet)
        }
        return out.values.toList()
    }

    private val cache = object : LinkedHashMap<String, Pair<Long, List<Site>>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, List<Site>>>?) = size > 30
    }

    private fun pageFresh(q: String, page: Int): List<Site> {
        // Given time, and a second try.
        return runCatching { duckDuckGo(q, page) }
            .recoverCatching { if (it is Challenge) throw it        // (trying again won't help: a person has to answer it)
                AppLog.w("Find", "DuckDuckGo didn't answer (${it.message}); trying again"); Thread.sleep(800); duckDuckGo(q, page) }
            .onFailure { AppLog.w("Find", "DuckDuckGo didn't answer again (${it.message})") }
            .getOrThrow()
    }

    /** DuckDuckGo's plain (HTML) results page [page], as sites. Ads are left out. */
    private fun duckDuckGo(q: String, page: Int): List<Site> {
        val url = searchUrl(q, page)
        val html = get(url, accept = "text/html", agent = BROWSER_AGENT, cookies = true)
        if (isChallenge(html)) throw Challenge(url)
        return parseDuckDuckGo(html)
    }
    private fun searchUrl(q: String, page: Int) =
        "https://html.duckduckgo.com/html/?q=${enc(q)}&kp=1&kl=wt-wt" + if (page > 0) "&s=${page * 30}&dc=${page * 30 + 1}" else ""

    /** Is this DuckDuckGo's "are you a person?" check (rather than results)? */
    fun isChallenge(html: String) = html.contains("anomaly-modal") || html.contains("challenge-form")

    /** Where the check window may go: DuckDuckGo's plain results page and its check, nothing else (not its full site). */
    fun checkMayOpen(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        val path = u.path.orEmpty()
        return u.scheme == "https" && (host == "html.duckduckgo.com" && (path.startsWith("/html") || path.startsWith("/anomaly") || path.startsWith("/t/")) ||
            host == "duckduckgo.com" && (path.startsWith("/anomaly") || path.startsWith("/t/")))
    }

    /** Reads DuckDuckGo's results page: each result's site, title and snippet, in order, one per site. */
    fun parseDuckDuckGo(html: String): List<Site> {
        val out = LinkedHashMap<String, Site>()
        // Each result is a block starting at class="result…"; ads are "result--ad".
        val blocks = html.split(Regex("<div[^>]+class=\"[^\"]*?\\bresult\\b")).drop(1)
        for (b in blocks) {
            val head = b.substringBefore('>')
            if (head.contains("result--ad")) continue
            val a = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b)
                ?: Regex("<a[^>]+href=\"([^\"]+)\"[^>]+class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b) ?: continue
            val url = realUrl(unescape(a.groupValues[1])) ?: continue
            val domain = domainOf(url) ?: continue
            if (domain == "duckduckgo.com" || domain in out) continue
            val title = plain(a.groupValues[2])
            val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
            out[domain] = Site(domain, title.ifBlank { domain }, snippet)
        }
        return out.values.toList()
    }

    /** "//duckduckgo.com/l/?uddg=https%3A%2F%2Fnasa.gov%2F&rut=…" → "https://nasa.gov/" (or the address itself). */
    private fun realUrl(href: String): String? {
        val h = if (href.startsWith("//")) "https:$href" else href
        val uddg = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)
        val u = uddg?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: h
        return u.takeIf { it.startsWith("http") }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
    private fun plain(html: String) = unescape(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    /** "https://www.khanacademy.org/math" → "khanacademy.org" (the site, never a page). */
    fun domainOf(url: String): String? {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase() ?: return null
        val h = host.removePrefix("www.").removePrefix("m.")
        return h.takeIf { it.contains('.') && it.matches(Regex("[a-z0-9.-]+")) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** DuckDuckGo turns away apps that name themselves; its results page expects a browser. */
    const val BROWSER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private fun get(url: String, accept: String = "application/json", agent: String = AGENT, connectMs: Int = 10_000, readMs: Int = 15_000,
                    cookies: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs; c.readTimeout = readMs
            c.setRequestProperty("User-Agent", agent)
            c.setRequestProperty("Accept", accept)
            // DuckDuckGo's pass, once a person answered its check (kept by Android's web cookies, like a browser).
            if (cookies) runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) }
            if (c.responseCode != 200) throw java.io.IOException("Search answered ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * A site's little icon, as a picture Android can read (Google's icon service gives PNGs; DuckDuckGo's .ico files
     * often can't be read): Google's, else DuckDuckGo's. Null if neither has one.
     */
    fun icon(domain: String): android.graphics.Bitmap? =
        fetchImage("https://www.google.com/s2/favicons?sz=64&domain=${enc(domain)}")
            ?: fetchImage("https://icons.duckduckgo.com/ip3/$domain.ico")

    private fun fetchImage(url: String): android.graphics.Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", AGENT)
            if (c.responseCode != 200) null
            else c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }?.takeIf { it.width > 1 }
        } finally { c.disconnect() }
    }.onFailure { AppLog.w("Find", "Icon not fetched (${it.javaClass.simpleName})") }.getOrNull()
}
