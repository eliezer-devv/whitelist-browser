package com.appcustom.whitelistbrowser

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finding a website by what it's about ("maths homework help", "nasa"), for people who don't know its address. No
 * search engine or key: Wikipedia's search finds the articles that match, and Wikidata gives each one's official
 * website (the same data Wikipedia's info boxes show). Results are sites, never pages: their address, name and a
 * short description. Good for organisations, brands and well-known sites; small sites without an article won't show.
 */
object Discovery {
    data class Site(val domain: String, val name: String, val description: String)

    private const val AGENT = "WhitelistBrowser/${BuildConfig.VERSION_NAME} (a family/school browser; site discovery)"

    /** Sites matching [query], best first (at most [max]). Blocking; throws when offline. */
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

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", AGENT)          // Wikimedia asks every app to name itself
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw java.io.IOException("Search answered ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** A site's little icon (DuckDuckGo's icon service: no key, and it doesn't pass on who's asking). Null if none. */
    fun icon(domain: String): android.graphics.Bitmap? = runCatching {
        val c = URL("https://icons.duckduckgo.com/ip3/$domain.ico").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", AGENT)
            if (c.responseCode != 200) null else c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } finally { c.disconnect() }
    }.getOrNull()
}
