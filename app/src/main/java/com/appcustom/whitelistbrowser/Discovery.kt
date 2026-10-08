package com.appcustom.whitelistbrowser

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finding a website by what it's about ("maths homework help", "nasa"), for people who don't know its address. No key
 * or account: DuckDuckGo's plain results page (with Safe Search on strict), each result reduced to its site; if that
 * gives nothing (DuckDuckGo changed its page, or is limiting the phone), Wikipedia: its search, and each article's
 * official website from Wikidata. Results are sites, never pages: address, name and a short description.
 */
object Discovery {
    data class Site(val domain: String, val name: String, val description: String)

    private const val AGENT = "WhitelistBrowser/${BuildConfig.VERSION_NAME} (a family/school browser; site discovery)"

    /**
     * One page of sites for [query], best first: [page] 0, 1, 2… DuckDuckGo first (about 30 results a page, fewer
     * sites); if it gives nothing on the first page, Wikipedia's (one page only). Blocking; throws when offline.
     */
    fun searchPage(query: String, page: Int): List<Site> {
        val q = query.trim().take(100)
        if (q.isEmpty()) return emptyList()
        val ddg = runCatching { duckDuckGo(q, page) }
            .onFailure { AppLog.w("Find", "DuckDuckGo didn't answer (${it.message}); trying Wikipedia") }.getOrNull().orEmpty()
        if (ddg.isNotEmpty() || page > 0) return ddg
        return search(q)
    }

    /** DuckDuckGo's plain (HTML) results page [page], as sites. Ads are left out. */
    private fun duckDuckGo(q: String, page: Int): List<Site> {
        val html = get("https://html.duckduckgo.com/html/?q=${enc(q)}&kp=1&kl=wt-wt" + if (page > 0) "&s=${page * 30}&dc=${page * 30 + 1}" else "",
            accept = "text/html", agent = BROWSER_AGENT)
        if (html.contains("anomaly-modal") || html.contains("challenge-form")) throw java.io.IOException("asked to prove it's a person")
        return parseDuckDuckGo(html)
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

    /** Wikipedia's matches (organisations, brands, well-known sites). Blocking; throws when offline. */
    fun search(query: String, language: String = java.util.Locale.getDefault().language, max: Int = 15): List<Site> {
        val q = query.trim().take(100)
        if (q.isEmpty()) return emptyList()
        val langs = listOf("en", language).filter { it.matches(Regex("[a-z]{2,3}")) }.distinct()
        // 1. By name (Wikidata: "khan academy" → Khan Academy), then 2. by topic (Wikipedia's full-text search).
        val ids = LinkedHashSet<String>()
        runCatching { ids += byName(q, langs.first()) }
        for (l in langs) runCatching { ids += byTopic(q, l) }
        if (ids.isEmpty()) return emptyList()
        // 3. Each one's official website (one call for up to 50).
        val sites = LinkedHashMap<String, Site>()
        for (chunk in ids.toList().take(50).chunked(50)) {
            val ents = parseEntities(get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims%7Clabels%7Cdescriptions" +
                "&languages=${langs.joinToString("%7C")}&ids=${chunk.joinToString("%7C")}"), langs)
            for (id in chunk) ents[id]?.let { s -> if (s.domain !in sites) sites[s.domain] = s }
        }
        return sites.values.take(max)
    }

    private fun byName(q: String, lang: String): List<String> =
        parseNameSearch(get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=7" +
            "&language=$lang&uselang=$lang&search=${enc(q)}"))

    private fun byTopic(q: String, lang: String): List<String> =
        parseTopicSearch(get("https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=20" +
            "&prop=pageprops&ppprop=wikibase_item&redirects=1&gsrsearch=${enc(q)}"))

    // ---- reading the answers (kept separate, so they can be tested without the internet) ----

    /** wbsearchentities: the item IDs, in order. */
    fun parseNameSearch(json: String): List<String> {
        val arr = JSONObject(json).optJSONArray("search") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.startsWith("Q") } }
    }

    /** Wikipedia's search, as pages: each one's Wikidata item, in the search's order (its "index"). */
    fun parseTopicSearch(json: String): List<String> {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return pages.keys().asSequence().mapNotNull { k -> pages.optJSONObject(k) }
            .sortedBy { it.optInt("index", 999) }
            .mapNotNull { it.optJSONObject("pageprops")?.optString("wikibase_item")?.takeIf { id -> id.startsWith("Q") } }
            .toList()
    }

    /** wbgetentities: item ID → its site (official website, P856; preferred, else the first), name and description. */
    fun parseEntities(json: String, langs: List<String>): Map<String, Site> {
        val ents = JSONObject(json).optJSONObject("entities") ?: return emptyMap()
        val out = HashMap<String, Site>()
        for (id in ents.keys()) {
            val e = ents.optJSONObject(id) ?: continue
            val claims = e.optJSONObject("claims")?.optJSONArray("P856") ?: continue
            val list = (0 until claims.length()).mapNotNull { claims.optJSONObject(it) }.filter { it.optString("rank") != "deprecated" }
            val best = list.firstOrNull { it.optString("rank") == "preferred" } ?: list.firstOrNull() ?: continue
            val url = best.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value") ?: continue
            val domain = domainOf(url) ?: continue
            fun text(field: String) = e.optJSONObject(field)?.let { o -> langs.reversed().firstNotNullOfOrNull { l -> o.optJSONObject(l)?.optString("value") } }.orEmpty()
            out[id] = Site(domain, text("labels").ifBlank { domain }, text("descriptions"))
        }
        return out
    }

    /** "https://www.khanacademy.org/math" → "khanacademy.org" (the site, never a page). */
    fun domainOf(url: String): String? {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase() ?: return null
        val h = host.removePrefix("www.").removePrefix("m.")
        return h.takeIf { it.contains('.') && it.matches(Regex("[a-z0-9.-]+")) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** DuckDuckGo turns away apps that name themselves; its results page expects a browser. */
    private const val BROWSER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private fun get(url: String, accept: String = "application/json", agent: String = AGENT): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", agent)          // (Wikimedia asks every app to name itself)
            c.setRequestProperty("Accept", accept)
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
