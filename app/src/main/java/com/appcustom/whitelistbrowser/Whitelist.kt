package com.appcustom.whitelistbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Whitelist {
    /**
     * One allowed website. [home] = show a tile for it on the home page.
     * [subdomains] = also allow every subdomain (mail.example.com ...). When false, only the exact
     * site is allowed, plus its "www." twin, since those are normally the same site.
     */
    data class Site(val domain: String, val name: String, val url: String, val home: Boolean,
                    val subdomains: Boolean = true,
                    val pages: List<String> = emptyList()) {  // empty = the whole site; else only these pages
        fun matches(host: String): Boolean {
            // "www.example.com" means the site itself, example.com, as does "example.com".
            val base = domain.removePrefix("www.")
            if (host == domain || host == base || host == "www.$base") return true
            return subdomains && host.endsWith(".$base")
        }
    }

    /**
     * Temporary access from a list's "temporary" section. [what]: site, page or media.
     * [mode] clock = open for [minutes] from [from]; use = [minutes] of time spent on it, within 7 days.
     */
    data class Temp(val id: String, val what: String, val entry: String, val mode: String, val minutes: Int, val from: Long) {
        private val isPage get() = '/' in entry
        val end: Long get() = from + (if (mode == "use") 7L * 24 * 60 else minutes.toLong()) * 60_000L
        /** Milliseconds left: of clock time, or of use. */
        fun left(now: Long = System.currentTimeMillis()): Long =
            if (now >= end) 0L
            else if (mode == "use") maxOf(0L, minutes * 60_000L - TempTime.used(id))
            else end - now
        fun active() = left() > 0L
        fun covers(host: String, key: String?): Boolean {
            if (isPage) return key != null && Whitelist.pageMatches(key, entry)
            val e = entry.removePrefix("www.")
            val h = host.removePrefix("www.")
            return h == e || h.endsWith(".$e")
        }
    }

    data class State(
        val sites: List<Site> = emptyList(),     // empty = fail closed, nothing allowed
        val block: List<String> = emptyList(),   // always blocked sites, even under an allowed domain
        val blockPages: List<String> = emptyList(), // always blocked pages (page keys)
        val noMedia: List<String> = emptyList(),    // sites that open without photos and videos
        val noMediaPages: List<String> = emptyList(), // pages that open without photos and videos
        val temps: List<Temp> = emptyList(),        // temporary access
        val homepage: String? = null,            // null = the built-in home page
        val refreshMinutes: Int = 5,
        val updatedAt: Long = 0L,
        val listNames: List<String> = emptyList(),  // lists this phone uses, e.g. [public, emma]
        val deviceName: String? = null,             // (names are private now: the phone uses the one typed on it)
        val registered: Boolean = false,            // this phone is in phones.json
        val adblock: Boolean = true,                // block ads and trackers (set in the admin page)
        val adblockExceptions: List<String> = emptyList() // domains never blocked as ads
    ) {
        val allow: List<String> get() = sites.map { it.domain }
        fun sameContent(o: State) = sites == o.sites && block == o.block && blockPages == o.blockPages &&
            noMedia == o.noMedia && noMediaPages == o.noMediaPages && temps == o.temps && homepage == o.homepage
    }

    @Volatile var state = State()
        private set
    @Volatile var lastError: String? = null

    private const val PREFS = "whitelist"

    // Patterns used on every address check, built once.
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val HOST_CHARS = Regex("^[a-z0-9.-]+$")
    private val MOBILE_PREFIX = Regex("^(www|m|mobile)\\.")
    private val PAGE_ENTRY = Regex("[/?].")
    private val LIST_NAME = Regex("^[a-z0-9_-]+$")

    // "https://www.Example.com/path" -> "www.example.com", "*.example.com" -> "example.com"
    fun normalize(entry: String): String? {
        var s = entry.trim().lowercase()
        if (s.isEmpty() || s.startsWith("#")) return null
        s = s.replace(SCHEME, "").removePrefix("*.")
        s = s.substringBefore('/').substringBefore('?').substringBefore(':').trimEnd('.')
        return if (HOST_CHARS.matches(s) && '.' in s) s else null
    }

    fun defaultName(domain: String) = domain.removePrefix("www.")

    /**
     * A page address in comparable form: no scheme, no #part, lower-case host without
     * "www.", "m." or "mobile." (so mobile redirects still match), then path and query as typed.
     * "https://m.YouTube.com/@khanacademy/videos" -> "youtube.com/@khanacademy/videos"
     */
    fun pageKey(entry: String): String? {
        var s = entry.trim()
        if (s.isEmpty()) return null
        s = s.replace(SCHEME, "").substringBefore('#')
        val cut = s.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) s.length else it }
        val host = s.substring(0, cut).lowercase().substringBefore(':').trimEnd('.')
            .replace(MOBILE_PREFIX, "")
        if (!HOST_CHARS.matches(host) || '.' !in host) return null
        var rest = s.substring(cut)
        if (rest.isEmpty()) rest = "/"
        if (rest.startsWith("?")) rest = "/$rest"
        return host + rest
    }

    /** A page entry covers that page and everything below it (".../videos", "...&t=30"). */
    fun pageMatches(key: String, entry: String): Boolean {
        if (!key.startsWith(entry)) return false
        if (key.length == entry.length || entry.last() in "/?&=") return true
        return key[entry.length] in "/?&#"
    }

    private fun parseSite(item: Any?): Site? {
        return when (item) {
            is JSONObject -> {
                val domain = normalize(item.optString("domain")) ?: return null
                val name = item.optString("name").trim().ifEmpty { defaultName(domain) }
                val url = item.optString("url").trim()
                    .takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: "https://$domain"
                val pagesArr = item.optJSONArray("pages") ?: JSONArray()
                val rawPages = (0 until pagesArr.length()).map { pagesArr.optString(it).trim() }.filter { it.isNotEmpty() }
                val pages = rawPages.mapNotNull { pageKey(it) }.distinct()
                // A page-only site's tile opens its first page unless "url" says otherwise.
                val tileUrl = if (item.optString("url").isBlank() && pages.isNotEmpty()) "https://${pages[0]}" else url
                Site(domain, name, tileUrl, item.optBoolean("home", true), item.optBoolean("subdomains", true), pages)
            }
            else -> null
        }
    }

    private fun isPage(e: String) = PAGE_ENTRY.containsMatchIn(e.replace(SCHEME, ""))

    /** "2026-09-24T10:00:00.000Z" -> milliseconds. */
    private fun parseTime(iso: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso.take(19))?.time
    }.getOrNull()

    /** One list file. */
    private fun parse(json: String, fetchedAt: Long): State {
        val o = JSONObject(json)
        val arr: JSONArray = o.optJSONArray("sites") ?: throw IOException("A list file needs a \"sites\" list")
        val sites = (0 until arr.length()).mapNotNull { parseSite(arr.opt(it)) }.distinctBy { it.domain }
        val blockArr = o.optJSONArray("block") ?: JSONArray()
        // Entries with a path ("youtube.com/watch?v=abc") block that page; others block a whole site.
        val blockRaw = (0 until blockArr.length()).map { blockArr.optString(it).trim() }.filter { it.isNotEmpty() }
        val block = blockRaw.filterNot { isPage(it) }.mapNotNull { normalize(it) }.distinct()
        val blockPages = blockRaw.filter { isPage(it) }.mapNotNull { pageKey(it) }.distinct()
        // "noMedia": sites or pages that open without photos and videos (same format as "block").
        val nmArr = o.optJSONArray("noMedia") ?: JSONArray()
        val nmRaw = (0 until nmArr.length()).map { nmArr.optString(it).trim() }.filter { it.isNotEmpty() }
        val noMedia = nmRaw.filterNot { isPage(it) }.mapNotNull { normalize(it) }.distinct()
        val noMediaPages = nmRaw.filter { isPage(it) }.mapNotNull { pageKey(it) }.distinct()
        val home = o.optString("homepage").takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val tArr = o.optJSONArray("temporary") ?: JSONArray()
        val temps = (0 until tArr.length()).mapNotNull { i ->
            val t = tArr.optJSONObject(i) ?: return@mapNotNull null
            val raw = t.optString("entry")
            val entry = (if (isPage(raw)) pageKey(raw) else normalize(raw)) ?: return@mapNotNull null
            val what = t.optString("what").takeIf { it == "site" || it == "page" || it == "media" } ?: return@mapNotNull null
            val from = parseTime(t.optString("from")) ?: return@mapNotNull null
            Temp(t.optString("id").ifEmpty { "$what|$entry|$from" }, what, entry,
                if (t.optString("mode") == "use") "use" else "clock", t.optInt("minutes").coerceIn(1, 24 * 60 + 55), from)
        }
        return State(sites, block, blockPages, noMedia, noMediaPages, temps, home, maxOf(1, o.optInt("refreshMinutes", 5)), fetchedAt)
    }

    /** Where a list lives: "public" is whitelist.json, others are lists/<name>.json. */
    fun listUrl(name: String) =
        if (name == "public") Config.WHITELIST_URL else "${Config.PAGES_BASE}lists/$name.json"

    /**
     * Combines this phone's lists: a site is allowed if any list allows it, and blocked if any
     * list blocks it. [bundle] = {"device": {...} or null, "lists": [{"name": .., "json": ..}]}.
     */
    private fun parseBundle(bundle: String, fetchedAt: Long): State {
        val b = JSONObject(bundle)
        val device = b.optJSONObject("device")
        val arr = b.getJSONArray("lists")
        val parts = (0 until arr.length()).map { arr.getJSONObject(it) }
        val states = parts.map { parse(it.getString("json"), fetchedAt) }
        return State(
            sites = states.flatMap { it.sites },
            block = states.flatMap { it.block }.distinct(),
            blockPages = states.flatMap { it.blockPages }.distinct(),
            noMedia = states.flatMap { it.noMedia }.distinct(),
            noMediaPages = states.flatMap { it.noMediaPages }.distinct(),
            temps = states.flatMap { it.temps }.distinctBy { it.id },
            homepage = states.firstNotNullOfOrNull { it.homepage },
            refreshMinutes = states.minOfOrNull { it.refreshMinutes } ?: 5,
            updatedAt = fetchedAt,
            listNames = parts.map { it.getString("name") },
            deviceName = device?.optString("name")?.takeIf { it.isNotBlank() },
            registered = device != null,
            adblock = b.optBoolean("adblock", true),
            adblockExceptions = b.optJSONArray("adblockExceptions")?.let { a ->
                (0 until a.length()).mapNotNull { normalize(a.optString(it)) } } ?: emptyList()
        )
    }

    /**
     * The lists saved from the last download. On a phone that has never been online: the lists new
     * phones start with, packed into the app when it was built (assets/initial-bundle.json), so it's
     * usable straight away. The admin page decides which lists those are (possibly none).
     */
    fun loadCache(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bundle = p.getString("bundle", null)
        if (bundle != null) { runCatching { state = parseBundle(bundle, p.getLong("fetchedAt", 0L)) }; return }
        val builtIn = runCatching { ctx.assets.open("initial-bundle.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return
        runCatching { state = parseBundle(builtIn, 0L) }
    }

    /** GET a file from GitHub Pages. Null if it doesn't exist (404). Throws on other failures. */
    private fun fetch(url: String): String? {
        val u = URL(url + (if ('?' in url) "&" else "?") + "t=" + System.currentTimeMillis()) // bust Pages cache
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw IOException("List fetch failed: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Blocking network call. Run off the main thread. Throws on failure and keeps the old lists.
     * 1. phones.json says which lists this phone uses (or the default lists for unknown phones).
     *    It's the public part of the private repository's devices.json: IDs and lists, no names.
     * 2. Each list is downloaded and they're combined.
     */
    fun refresh(ctx: Context) {
        val id = Device.id(ctx)
        val devices = fetch("${Config.PAGES_BASE}phones.json")?.let { JSONObject(it) }
        val device = devices?.optJSONObject("devices")?.optJSONObject(id)
        val listArr = device?.optJSONArray("lists") ?: devices?.optJSONArray("default")
        val names = (0 until (listArr?.length() ?: 0)).map { listArr!!.optString(it) }
            .filter { LIST_NAME.matches(it) }.distinct()
            .ifEmpty { if (listArr == null) listOf("public") else emptyList() }

        val lists = JSONArray()
        for (name in names) {
            val json = fetch(listUrl(name)) ?: continue // a deleted list is simply skipped
            JSONObject(json) // throws on a broken file, so a bad edit never replaces a good list
            lists.put(JSONObject().put("name", name).put("json", json))
        }
        // Ad blocking: on for all phones unless the admin page says otherwise; a phone's own
        // setting ("on"/"off") overrides that.
        val adblock = when (device?.optString("adblock")) {
            "on" -> true
            "off" -> false
            else -> devices?.optBoolean("adblock", true) ?: true
        }
        val bundle = JSONObject().put("device", device ?: JSONObject.NULL).put("lists", lists)
            .put("adblock", adblock)
            .put("adblockExceptions", devices?.optJSONArray("adblockExceptions") ?: JSONArray())
            .toString()
        val now = System.currentTimeMillis()
        state = parseBundle(bundle, now)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("bundle", bundle).putLong("fetchedAt", now).apply()
    }

    /** Is [host] one of these sites, or under one? A "www." entry counts as the site itself. */
    private fun covers(list: List<String>, host: String) = list.any {
        val e = it.removePrefix("www.")
        host == it || host == e || host.endsWith(".$e")
    }

    /**
     * [mainFrame] = the page itself. Frames inside an allowed page (embedded videos, maps) only
     * need their site to be allowed, not a listed page.
     */
    fun isAllowed(url: String?, mainFrame: Boolean = true): Boolean {
        if (url.isNullOrEmpty()) return false
        if (url.startsWith("about:")) return true
        if (url.startsWith(MainActivity.BLOCKED_PAGE)) return true // our own blocked page
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (host == HomePage.HOST) return true
        if (tempsFor(url, host, "site", "page").isNotEmpty()) return true // temporary access wins, even over blocks
        return allowedForGood(url, host, mainFrame)
    }

    /** Allowed by the lists themselves, ignoring temporary access. */
    private fun allowedForGood(url: String, host: String, mainFrame: Boolean): Boolean {
        val s = state
        val sites = s.sites.filter { it.matches(host) }
        if (sites.isEmpty() || covers(s.block, host)) return false
        if (!mainFrame) return true
        val key = pageKey(url) ?: return false
        if (s.blockPages.any { pageMatches(key, it) }) return false
        if (sites.any { it.pages.isEmpty() }) return true
        return sites.any { site -> site.pages.any { pageMatches(key, it) } }
    }

    /** Should this page open without photos and videos? (If any of the phone's lists says so.) */
    fun mediaBlocked(url: String?): Boolean {
        if (url.isNullOrEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) return false
        val s = state
        val host = Uri.parse(url).host?.lowercase()?.trimEnd('.') ?: return false
        if (tempsFor(url, host, "media").isNotEmpty()) return false // photos and videos on for a while
        if (covers(s.noMedia, host)) return true
        val key = pageKey(url) ?: return false
        return s.noMediaPages.any { pageMatches(key, it) }
    }

    /** Active temporary access of these kinds that covers [url]. */
    private fun tempsFor(url: String, host: String, vararg kinds: String): List<Temp> {
        val key = pageKey(url)
        return state.temps.filter { it.what in kinds && it.active() && it.covers(host, key) }
    }

    private fun hostOf(url: String?): String? =
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) null
        else Uri.parse(url).host?.lowercase()?.trimEnd('.')

    /** "Time on the site" grants being used right now on [url] (to add time to while it's on screen). */
    fun usingNow(url: String?): List<Temp> {
        val host = hostOf(url) ?: return emptyList()
        val openFor = if (!allowedForGood(url!!, host, true)) tempsFor(url, host, "site", "page") else emptyList()
        val mediaFor = if (mediaBlockedForGood(url, host)) tempsFor(url, host, "media") else emptyList()
        return (openFor + mediaFor).filter { it.mode == "use" }
    }

    private fun mediaBlockedForGood(url: String, host: String): Boolean {
        val s = state
        if (covers(s.noMedia, host)) return true
        val key = pageKey(url) ?: return false
        return s.noMediaPages.any { pageMatches(key, it) }
    }

    /**
     * What to show about temporary access on [url], e.g. "Temporary: 23 min left", or null.
     * Only when the page (or its photos and videos) is open just because of it.
     */
    fun tempStatus(url: String?): String? {
        val host = hostOf(url) ?: return null
        fun fmt(t: Temp): String {
            val min = ((t.left() + 59_999) / 60_000).toInt()
            val time = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
            return if (t.mode == "use") "$time of use left" else "$time left"
        }
        if (!allowedForGood(url!!, host, true)) {
            tempsFor(url, host, "site", "page").maxByOrNull { it.left() }?.let { return "Temporary: ${fmt(it)}." }
        } else if (mediaBlockedForGood(url, host)) {
            tempsFor(url, host, "media").maxByOrNull { it.left() }?.let { return "Photos and videos on: ${fmt(it)}." }
        }
        return null
    }

    /** Minutes left of temporary access on [url] if it's about to run out (for a warning), else null. */
    fun endingSoon(url: String?): Temp? {
        val host = hostOf(url) ?: return null
        return tempsFor(url!!, host, "site", "page", "media").firstOrNull { it.left() in 1..5 * 60_000L }
    }

    /** True if [url] had temporary access that has run out (so the blocked page can say "Time's up"). */
    fun tempEnded(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        val key = pageKey(url!!)
        return state.temps.any { it.what != "media" && !it.active() && it.covers(host, key) }
    }

    /** True when the site is allowed but only some of its pages, and this isn't one of them. */
    fun isPageRestricted(url: String?): Boolean = isAllowed(url, mainFrame = false) && !isAllowed(url)

    /** JSON the home page reads: the tiles to show. */
    fun homeJson(): String {
        val s = state
        val tiles = JSONArray()
        val shown = HashSet<String>()
        s.sites.filter { it.home }.distinctBy { it.domain }.forEach {
            shown += it.domain
            tiles.put(JSONObject().put("name", it.name).put("url", it.url).put("domain", it.domain))
        }
        // Sites open for a while get a tile too, with a timer badge.
        s.temps.filter { it.what == "site" && it.active() && it.entry !in shown }.forEach {
            val min = ((it.left() + 59_999) / 60_000).toInt()
            tiles.put(JSONObject().put("name", defaultName(it.entry)).put("url", "https://${it.entry}").put("domain", it.entry)
                .put("temp", if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"))
        }
        return JSONObject()
            .put("tiles", tiles)
            .put("siteCount", s.sites.size)
            .put("loaded", s.updatedAt != 0L || s.sites.isNotEmpty())
            .put("canRequest", Requests.isSetUp())
            .toString()
    }
}
