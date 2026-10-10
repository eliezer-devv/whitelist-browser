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
                    val pages: List<String> = emptyList(),   // empty = the whole site; else only these pages
                    val frames: Boolean = false,             // content embedded from any site works on its pages
                    val unfiltered: Boolean = false,         // approved "anyway": the content filters don't apply to it
                    val filtersOff: Set<String> = emptySet()) { // AdGuard's groups switched off on its pages
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
    data class Temp(val id: String, val what: String, val entry: String, val mode: String, val minutes: Int, val from: Long,
                    val unfiltered: Boolean = false) {
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
        val adult: Boolean = true,                  // content filters (admin page: Settings → Filters)
        val gambling: Boolean = true,
        val pinApproval: Boolean = false,           // an approval PIN is set for this phone (admin page)
        val malware: Boolean = true,
        val adblock: Boolean = true,                // block ads and trackers (set in the admin page)
        val adblockExceptions: List<String> = emptyList(), // domains never blocked as ads
        val trackers: Boolean = true,                      // AdGuard's tracker lists (and tracking codes in addresses)
        val annoyances: Boolean = true,                    // AdGuard's annoyance lists (cookie notices, pop-ups, widgets, social)
        val sitesFiltersOff: Map<String, Set<String>> = emptyMap(), // site -> groups switched off on its pages
        val logRequested: Long = 0L,                       // the admin page asked for this phone's log (when)
        val adminPhone: Boolean = false,                   // an admin phone (phone notifications; set on the admin page)
        val pinLockedUntil: Long = 0L,                     // PIN approvals locked until (5 wrong PINs)
        val pinUnlocks: Int = 0,                           // times an admin pressed Unlock for this phone (clears its own lock too)
        // Embedded content allowed on a site: site -> sites whose content may show inside its pages
        // (a list's "embeds", approved from a request after the phone blocked it).
        val embeds: Map<String, List<String>> = emptyMap(),
        // Just one of them off: "noPhotos" / "noVideos" (sites, and pages). "noMedia" above is both.
        val noPhotos: List<String> = emptyList(),
        val noPhotosPages: List<String> = emptyList(),
        val noVideos: List<String> = emptyList(),
        val noVideosPages: List<String> = emptyList(),
        val noSound: List<String> = emptyList(),
        val noSoundPages: List<String> = emptyList(),
        // Single photos or videos allowed where they're otherwise off: host + path, no "?..." (lowercase).
        val mediaAllow: List<String> = emptyList(),
        // "Back on" (sites, and pages): turned back on, whatever any list's "off" says (also over the whole site's).
        val photosOn: List<String> = emptyList(),
        val photosOnPages: List<String> = emptyList(),
        val videosOn: List<String> = emptyList(),
        val videosOnPages: List<String> = emptyList(),
        val soundOn: List<String> = emptyList(),
        val soundOnPages: List<String> = emptyList(),
        // This phone's default for photos, videos and sound: the kinds it blocks on every site that isn't set to
        // Open or Blocked on its own (set on the admin page; empty = all open, as before).
        val mediaDefault: Set<String> = emptySet(),
        val search: Boolean = false,                       // text search is on for this phone
        val searchApprovedOnly: Boolean = false            // …showing only results this phone can open
    ) {
        val allow: List<String> get() = sites.map { it.domain }
        fun sameContent(o: State) = sites == o.sites && block == o.block && blockPages == o.blockPages &&
            noMedia == o.noMedia && noMediaPages == o.noMediaPages && temps == o.temps && homepage == o.homepage && embeds == o.embeds &&
            noPhotos == o.noPhotos && noPhotosPages == o.noPhotosPages && noVideos == o.noVideos && noVideosPages == o.noVideosPages &&
            noSound == o.noSound && noSoundPages == o.noSoundPages && mediaAllow == o.mediaAllow &&
            photosOn == o.photosOn && photosOnPages == o.photosOnPages && videosOn == o.videosOn && videosOnPages == o.videosOnPages &&
            soundOn == o.soundOn && soundOnPages == o.soundOnPages && mediaDefault == o.mediaDefault && search == o.search &&
            searchApprovedOnly == o.searchApprovedOnly
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

    /** The name a site was given in the lists (e.g. "Khan Academy" for khanacademy.org), if any. */
    fun siteNameFor(host: String): String? {
        val h = host.lowercase().removePrefix("www.")
        val site = state.sites.firstOrNull { val d = it.domain.removePrefix("www."); h == d || h.endsWith(".$d") } ?: return null
        val name = site.name.trim()
        // Just its address again (no name of its own): no name.
        return name.takeIf { it.isNotEmpty() && it.lowercase().removePrefix("www.") != site.domain.lowercase().removePrefix("www.") }
    }

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
                Site(domain, name, tileUrl, item.optBoolean("home", true), item.optBoolean("subdomains", true), pages,
                    item.optBoolean("frames", false), item.optBoolean("unfiltered", false),
                    item.optJSONArray("filtersOff")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
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
        // "noPhotos" / "noVideos": just one of them off (same format).
        fun sitesAndPages(name: String): Pair<List<String>, List<String>> {
            val a = o.optJSONArray(name) ?: JSONArray()
            val raw = (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
            return raw.filterNot { isPage(it) }.mapNotNull { normalize(it) }.distinct() to raw.filter { isPage(it) }.mapNotNull { pageKey(it) }.distinct()
        }
        val (noPhotos, noPhotosPages) = sitesAndPages("noPhotos")
        val (noVideos, noVideosPages) = sitesAndPages("noVideos")
        val (noSound, noSoundPages) = sitesAndPages("noSound")
        val (photosOn, photosOnPages) = sitesAndPages("photosOn")
        val (videosOn, videosOnPages) = sitesAndPages("videosOn")
        val (soundOn, soundOnPages) = sitesAndPages("soundOn")
        val allowA = o.optJSONArray("mediaAllow") ?: JSONArray()
        val mediaAllow = (0 until allowA.length()).map { allowA.optString(it).trim().lowercase().removePrefix("www.") }.filter { it.isNotEmpty() }
        val home = o.optString("homepage").takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val tArr = o.optJSONArray("temporary") ?: JSONArray()
        val temps = (0 until tArr.length()).mapNotNull { i ->
            val t = tArr.optJSONObject(i) ?: return@mapNotNull null
            val raw = t.optString("entry")
            val entry = (if (isPage(raw)) pageKey(raw) else normalize(raw)) ?: return@mapNotNull null
            val what = t.optString("what").takeIf { it == "site" || it == "page" || it in MEDIA_TEMPS } ?: return@mapNotNull null
            val from = parseTime(t.optString("from")) ?: return@mapNotNull null
            Temp(t.optString("id").ifEmpty { "$what|$entry|$from" }, what, entry,
                if (t.optString("mode") == "use") "use" else "clock", t.optInt("minutes").coerceIn(1, 24 * 60 + 55), from,
                t.optBoolean("unfiltered", false))
        }
        val embeds = mutableMapOf<String, List<String>>()
        o.optJSONObject("embeds")?.let { e ->
            for (key in e.keys()) {
                val site = normalize(key) ?: continue
                val from = e.optJSONArray(key) ?: continue
                // A site ("player.vimeo.com"), or one exact part ("youtube.com/embed/abc": host + path).
                embeds[site] = (0 until from.length()).mapNotNull { i ->
                    val e = from.optString(i).trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
                        .substringBefore('?').substringBefore('#').trimEnd('/')
                    if ('/' in e) e.takeIf { it.substringBefore('/').contains('.') } else normalize(e)
                }
            }
        }
        return State(sites, block, blockPages, noMedia, noMediaPages, temps, home, maxOf(1, o.optInt("refreshMinutes", 5)), fetchedAt,
            embeds = embeds, noPhotos = noPhotos, noPhotosPages = noPhotosPages, noVideos = noVideos, noVideosPages = noVideosPages,
            noSound = noSound, noSoundPages = noSoundPages, mediaAllow = mediaAllow,
            photosOn = photosOn, photosOnPages = photosOnPages, videosOn = videosOn, videosOnPages = videosOnPages,
            soundOn = soundOn, soundOnPages = soundOnPages)
    }

    /** Where a list lives: "public" is whitelist.json, others are lists/<name>.json. */
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
        val allSites = states.flatMap { it.sites }
        return State(
            sites = allSites,
            block = states.flatMap { it.block }.distinct(),
            blockPages = states.flatMap { it.blockPages }.distinct(),
            noMedia = states.flatMap { it.noMedia }.distinct(),
            noMediaPages = states.flatMap { it.noMediaPages }.distinct(),
            noPhotos = states.flatMap { it.noPhotos }.distinct(),
            noPhotosPages = states.flatMap { it.noPhotosPages }.distinct(),
            noVideos = states.flatMap { it.noVideos }.distinct(),
            noVideosPages = states.flatMap { it.noVideosPages }.distinct(),
            noSound = states.flatMap { it.noSound }.distinct(),
            noSoundPages = states.flatMap { it.noSoundPages }.distinct(),
            mediaAllow = states.flatMap { it.mediaAllow }.distinct(),
            photosOn = states.flatMap { it.photosOn }.distinct(),
            photosOnPages = states.flatMap { it.photosOnPages }.distinct(),
            videosOn = states.flatMap { it.videosOn }.distinct(),
            videosOnPages = states.flatMap { it.videosOnPages }.distinct(),
            soundOn = states.flatMap { it.soundOn }.distinct(),
            soundOnPages = states.flatMap { it.soundOnPages }.distinct(),
            temps = states.flatMap { it.temps }.distinctBy { it.id },
            embeds = states.flatMap { it.embeds.entries }.groupBy({ it.key }, { it.value })
                .mapValues { (_, v) -> v.flatten().distinct() },
            homepage = states.firstNotNullOfOrNull { it.homepage },
            refreshMinutes = states.minOfOrNull { it.refreshMinutes } ?: 5,
            updatedAt = fetchedAt,
            listNames = parts.map { it.getString("name") },
            deviceName = device?.optString("name")?.takeIf { it.isNotBlank() },
            registered = device != null,
            adblock = b.optBoolean("adblock", true),
            adult = b.optBoolean("adult", true),
            gambling = b.optBoolean("gambling", true),
            pinApproval = b.optBoolean("pin", false),
            malware = b.optBoolean("malware", true),
            trackers = b.optBoolean("trackers", true),
            logRequested = device?.optLong("logRequested", 0L) ?: 0L,
            adminPhone = device?.optBoolean("admin", false) ?: false,
            pinUnlocks = device?.optInt("pinUnlocks", 0) ?: 0,
            pinLockedUntil = device?.optString("pinLockedUntil")?.takeIf { it.isNotBlank() }?.let { t ->
                runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(t.take(19))!!.time }.getOrNull() } ?: 0L,
            annoyances = b.optBoolean("annoyances", true),
            mediaDefault = device?.optJSONObject("media")?.let { m -> MEDIA_KINDS.filter { m.optString(it) == "blocked" }.toSet() } ?: emptySet(),
            search = device?.optBoolean("search", false) ?: false,
            searchApprovedOnly = device?.optBoolean("searchApprovedOnly", false) ?: false,
            adblockExceptions = b.optJSONArray("adblockExceptions")?.let { a ->
                (0 until a.length()).mapNotNull { normalize(a.optString(it)) } } ?: emptyList(),
            // From each site's settings in this phone's lists (a site in several lists: all of them together).
            sitesFiltersOff = allSites.filter { it.filtersOff.isNotEmpty() }.groupBy { it.domain.removePrefix("www.") }
                .mapValues { (_, l) -> l.flatMap { it.filtersOff }.toSet() }
        )
    }

    /** The lists saved from the last download (a phone that has never had them is "setting up" until it does). */
    fun loadCache(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bundle = p.getString("bundle", null) ?: return
        runCatching { state = parseBundle(bundle, p.getLong("fetchedAt", 0L)) }
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

    // ---- Quick checks: the newest lists straight from the repository, instead of waiting for GitHub Pages ----
    // GitHub Pages takes a minute or two to rebuild after each change, and can then hand out an older copy for up to
    // 10 minutes. Instead, the phone asks GitHub which version of the public repository is newest (a tiny request;
    // "nothing new" answers don't count against GitHub's hourly allowance), and only when there's something new
    // downloads its bundle at exactly that version. Spaced by how many phones there are (all phones share one
    // hourly allowance, with the admin pages), and if GitHub says no (allowance used up, a hiccup, a blocked
    // address), it falls back to GitHub Pages as before: never worse than that.
    private sealed class Quick {
        object Same : Quick()                                // nothing new since the version this phone has
        class Newer(val sha: String) : Quick()               // something new: this version
        object Skip : Quick()                                // too soon to ask again (and it was fine moments ago)
        object Unavailable : Quick()                         // can't ask now: use GitHub Pages
    }
    private const val QUICK_SHARE_PER_HOUR = 3000            // of GitHub's 5,000: room left for requests and admin pages

    /** How long between quick checks: 1 minute, longer with many phones (so together they stay inside the allowance). */
    fun quickGapMs(ctx: Context): Long {
        val phones = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("phoneCount", 1).coerceAtLeast(1)
        return (phones * 3_600_000L / QUICK_SHARE_PER_HOUR).coerceIn(60_000L, 20 * 60_000L)
    }
    /** Are quick checks working (not waiting after GitHub said no)? The app then checks every [quickGapMs]. */
    fun quickOn(ctx: Context) = System.currentTimeMillis() >= ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("quickBackoffUntil", 0L)

    /** Asks GitHub for the public repository's newest version. [eager]: right after a request, ask anyway. */
    private fun quickCheck(ctx: Context, eager: Boolean): Quick {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now < p.getLong("quickBackoffUntil", 0L)) return Quick.Unavailable
        val loaded = p.getString("quickLoadedSha", null)
        if (!eager && now - p.getLong("quickAskedAt", 0L) < quickGapMs(ctx) - 2_000L) {
            // Too soon: what this phone has was confirmed newest moments ago, so nothing to do.
            return if (loaded != null && now - p.getLong("quickOkAt", 0L) < 15 * 60_000L) Quick.Skip else Quick.Unavailable
        }
        p.edit().putLong("quickAskedAt", now).apply()
        val backoff = { minutes: Long, why: String ->
            AppLog.w("Lists", "Quick check off for $minutes minutes ($why): using GitHub Pages")
            p.edit().putLong("quickBackoffUntil", now + minutes * 60_000L).apply()
            Quick.Unavailable
        }
        val conn = runCatching { URL("https://api.github.com/repos/${Config.GITHUB_REPO}/commits/HEAD").openConnection() as HttpURLConnection }
            .getOrElse { return backoff(5L, it.javaClass.simpleName) }
        try {
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/vnd.github.sha")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "WhitelistBrowser/${BuildConfig.VERSION_NAME}")
            // (With the app's token: its allowance is far bigger. If the token can't be used for this, without it.)
            val noAuth = p.getBoolean("quickNoAuth", false)
            if (!noAuth) runCatching { BuildConfig.REQUESTS_TOKEN_REV.reversed() }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("__") }
                ?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val etag = p.getString("quickEtag", null)
            val known = p.getString("quickSha", null)
            if (etag != null && known != null) conn.setRequestProperty("If-None-Match", etag)
            val code = conn.responseCode
            val left = conn.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
            return when {
                code == 304 && known != null -> {
                    p.edit().putLong("quickOkAt", now).apply()
                    if (known == loaded) Quick.Same else Quick.Newer(known)
                }
                code == 200 -> {
                    val sha = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (!Regex("^[0-9a-f]{40}$").matches(sha)) return backoff(5L, "an odd answer")
                    p.edit().putString("quickSha", sha).putString("quickEtag", conn.getHeaderField("ETag")).putLong("quickOkAt", now).apply()
                    // Getting low (other phones, the admin pages): leave the rest for requests for a while.
                    if (left != null && left < 500) p.edit().putLong("quickBackoffUntil", now + 30 * 60_000L).apply()
                    if (sha == loaded) Quick.Same else Quick.Newer(sha)
                }
                code == 403 || code == 429 -> if (left == 0 || code == 429) backoff(30L, "GitHub's allowance used up for now")
                    else if (!noAuth) { p.edit().putBoolean("quickNoAuth", true).apply(); backoff(1L, "GitHub said $code with the token") }
                    else backoff(360L, "GitHub said $code")
                code == 401 -> if (!noAuth) { p.edit().putBoolean("quickNoAuth", true).apply(); backoff(1L, "the token wasn't accepted") } else backoff(360L, "GitHub said 401")
                else -> backoff(5L, "GitHub said $code")
            }
        } catch (e: Exception) {
            return backoff(5L, e.javaClass.simpleName)
        } finally { conn.disconnect() }
    }

    /**
     * This phone's bundle at exactly version [sha], straight from the repository. Throws on any failure, including
     * "not there" (then GitHub Pages decides: a phone never loses its lists over a quick download that didn't work).
     */
    private fun fetchAt(sha: String, path: String): String {
        val conn = URL("https://raw.githubusercontent.com/${Config.GITHUB_REPO}/$sha/docs/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000; conn.readTimeout = 10_000
            if (conn.responseCode != 200) throw IOException("List fetch failed: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    /** This phone's sealed bundle couldn't be opened (its key changed): it registers its key again. */
    @Volatile var keyMismatch = false

    /**
     * Blocking network call. Run off the main thread. Throws on failure and keeps the old lists.
     * This phone's lists come sealed, so only it can read them: one bundle in the public repository (named
     * from its ID, without showing it) with its settings and every list it uses. No bundle yet: the phone
     * isn't set up, so it opens nothing until it is (its registration, with its key, makes one).
     */
    fun refresh(ctx: Context, eager: Boolean = false) {
        val id = Device.id(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = "p/${Seal.bundleName(id)}.json"
        // Quickly, straight from the repository when it can; else GitHub Pages, as before.
        val quick = quickCheck(ctx, eager)
        if (quick is Quick.Same) {                           // nothing new: the lists stay as they are (just checked)
            val now = System.currentTimeMillis()
            state = state.copy(updatedAt = now)
            prefs.edit().putLong("fetchedAt", now).apply()
            return
        }
        if (quick is Quick.Skip) return                      // checked moments ago
        var quickSha: String? = null
        var sealed: String? = null
        var viaPages = true
        if (quick is Quick.Newer) {
            val got = runCatching { fetchAt(quick.sha, path) }
            if (got.isSuccess) { sealed = got.getOrNull(); quickSha = quick.sha; viaPages = false }
            else AppLog.w("Lists", "Quick download failed (${got.exceptionOrNull()?.message}): using GitHub Pages")
        }
        if (viaPages) sealed = fetch("${Config.PAGES_BASE}$path")
        val opened = sealed?.let {
            runCatching { Seal.open(JSONObject(it)) }.getOrElse { e ->
                if (e is org.json.JSONException) throw IOException("The lists file is broken") // keep the old lists
                keyMismatch = true; null                    // not for this key: register the key again
            }
        }
        if (opened != null) keyMismatch = false
        // Never back to an older copy (GitHub Pages can lag behind what a quick check already got).
        val at = opened?.optLong("at", 0L) ?: 0L
        if (opened != null && at < prefs.getLong("bundleAt", 0L)) { AppLog.i("Lists", "An older copy of the lists: kept the newer one"); return }
        if (opened != null) {
            val e = prefs.edit().putLong("bundleAt", at).putInt("phoneCount", opened.optInt("count", 1).coerceAtLeast(1))
            if (quickSha != null) e.putString("quickLoadedSha", quickSha)
            e.apply()
        }
        val devices = opened?.optJSONObject("phones")
        val listData = opened?.optJSONObject("lists") ?: JSONObject()
        val device = devices?.optJSONObject("devices")?.optJSONObject(id)
        val listArr = device?.optJSONArray("lists")
        val names = (0 until (listArr?.length() ?: 0)).map { listArr!!.optString(it) }
            .filter { LIST_NAME.matches(it) }.distinct()

        val lists = JSONArray()
        for (name in names) {
            val json = listData.optJSONObject(name)?.toString() ?: continue // a deleted list is simply skipped
            lists.put(JSONObject().put("name", name).put("json", json))
        }
        // Ad blocking: on for all phones unless the admin page says otherwise; a phone's own
        // setting ("on"/"off") overrides that.
        val adblock = when (device?.optString("adblock")) {
            "on" -> true
            "off" -> false
            else -> devices?.optBoolean("adblock", true) ?: true
        }
        // The content filters, the same way: on unless the admin page turns them off.
        fun filter(name: String) = when (device?.optString(name)) {
            "on" -> true
            "off" -> false
            else -> devices?.optBoolean(name, true) ?: true
        }
        val bundle = JSONObject().put("device", device ?: JSONObject.NULL).put("lists", lists)
            .put("adblock", adblock).put("adult", filter("adult")).put("gambling", filter("gambling")).put("malware", filter("malware"))
            .put("trackers", filter("trackers")).put("annoyances", filter("annoyances"))
            .put("pin", if (device?.has("pin") == true) device.optBoolean("pin") else devices?.optBoolean("pin", false) ?: false)
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
        // The content filters win over the lists (and temporary access), unless the site was approved "anyway".
        if (filteredAs(host).isNotEmpty() && !isUnfiltered(host)) return false
        if (tempsFor(url, host, "site", "page").isNotEmpty()) return true // temporary access wins, even over blocks
        return allowedForGood(url, host, mainFrame)
    }

    /**
     * The content filters that list [host] and are on for this phone (e.g. ["adult"]). Empty if none.
     * Used to block it, and to say which list it's on (blocked page, requests).
     */
    fun filteredAs(host: String): List<String> {
        val s = state
        val ex = s.adblockExceptions
        return listOfNotNull(
            "adult".takeIf { s.adult && Filters.adult.blocks(host, ex) },
            "gambling".takeIf { s.gambling && Filters.gambling.blocks(host, ex) },
            "malware".takeIf { s.malware && Filters.malware.blocks(host, ex) })
    }

    /** Was [host] approved "anyway" (in spite of a filter)? Then the filters don't apply to it. */
    fun isUnfiltered(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (state.sites.any { it.unfiltered && it.matches(h) }) return true
        return state.temps.any { it.unfiltered && it.what !in MEDIA_TEMPS && it.active() && it.covers(h, null) }
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

    /**
     * Does the page [topUrl] allow content embedded from any site (videos, maps, sign-in boxes)?
     * Yes if it's on a site with "Allow content embedded from other sites" (for a site limited to some
     * pages: on one of those pages). Leaving the page is still checked as usual.
     */
    fun framesAllowedOn(topUrl: String?): Boolean {
        val host = hostOf(topUrl) ?: return false
        val key = pageKey(topUrl!!)
        return state.sites.any { site ->
            site.frames && site.matches(host) &&
                (site.pages.isEmpty() || (key != null && site.pages.any { pageMatches(key, it) }))
        }
    }

    /**
     * May a frame from [frameHost] show inside the page [topUrl]? Yes if one of the phone's lists allows
     * content from that site on the page's site (a list's "embeds"). The site itself still doesn't open.
     */
    fun embedAllowed(topUrl: String?, frameUrl: String): Boolean {
        val top = hostOf(topUrl)?.removePrefix("www.") ?: return false
        val u = Uri.parse(frameUrl)
        val from = (u.host ?: frameUrl).lowercase().trimEnd('.').removePrefix("www.")
        val key = from + (u.path ?: "").lowercase().trimEnd('/')
        return state.embeds.any { (site, sources) ->
            val s = site.removePrefix("www.")
            (top == s || top.endsWith(".$s")) && sources.any {
                val e = it.removePrefix("www.")
                if ('/' in e) key == e || key.startsWith("$e/")      // one exact part (or below it)
                else from == e || from.endsWith(".$e")               // everything from that site
            }
        }
    }

    // Temporary access that turns photos and/or videos back on for a while.
    val MEDIA_TEMPS = setOf("media", "photos", "videos", "sound")

    /** Should photos be off on this page? (If any of the phone's lists says so, and nothing turns them on for a while.) */
    fun photosBlocked(url: String?): Boolean = kindBlocked(url, "photos")

    /** Should videos be off on this page? */
    fun videosBlocked(url: String?): Boolean = kindBlocked(url, "videos")

    /** Should sound be off on this page? */
    fun soundBlocked(url: String?): Boolean = kindBlocked(url, "sound")

    /** Photos, videos or sound (any of them) off on this page. */
    fun mediaBlocked(url: String?): Boolean = photosBlocked(url) || videosBlocked(url) || soundBlocked(url)

    /** Is this one photo or video allowed even where they're off ("mediaAllow")? */
    fun mediaAllowed(url: String?): Boolean {
        if (url == null || state.mediaAllow.isEmpty()) return false
        val u = Uri.parse(url)
        val key = ((u.host ?: return false) + (u.path ?: "")).lowercase().removePrefix("www.")
        return key in state.mediaAllow
    }

    /**
     * Is one embedded video player allowed one by one? Then its video data is let through even where videos
     * are off: other players' frames are still checked one by one, so only an allowed one can ask for it.
     */
    fun hasAllowedPlayer(): Boolean = state.mediaAllow.any { MediaBlock.isPlayerKey(it) }

    /** The addresses of the photos and videos allowed one by one (for the page script). */
    fun mediaAllowList(): List<String> = state.mediaAllow

    val MEDIA_KINDS = listOf("photos", "videos", "sound")

    /** Does this phone block [kind] by default (on sites not set to Open or Blocked on their own)? */
    fun defaultBlocks(kind: String) = kind in state.mediaDefault

    private fun kindBlocked(url: String?, kind: String): Boolean {
        if (url.isNullOrEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) return false
        val host = Uri.parse(url).host?.lowercase()?.trimEnd('.') ?: return false
        if (host == HomePage.HOST) return false                              // the app's own pages (home, search)
        if (tempsFor(url, host, "media", kind).isNotEmpty()) return false  // on for a while (all, or this one)
        return kindBlockedForGood(url, host, kind)
    }

    /** [kind] ("photos" or "videos") off by the lists, ignoring temporary access. */
    private fun kindBlockedForGood(url: String, host: String, kind: String): Boolean {
        val s = state
        // Turned back on ("back on") in any list: that wins over every "off" (and over the whole site's, for a page).
        val onSites = when (kind) { "photos" -> s.photosOn; "videos" -> s.videosOn; else -> s.soundOn }
        val onPages = when (kind) { "photos" -> s.photosOnPages; "videos" -> s.videosOnPages; else -> s.soundOnPages }
        if (covers(onSites, host)) return false
        val here = pageKey(url)
        if (here != null && onPages.any { pageMatches(here, it) }) return false
        val sites = s.noMedia + when (kind) { "photos" -> s.noPhotos; "videos" -> s.noVideos; else -> s.noSound }
        if (covers(sites, host)) return true
        val key = pageKey(url)
        val pages = s.noMediaPages + when (kind) { "photos" -> s.noPhotosPages; "videos" -> s.noVideosPages; else -> s.noSoundPages }
        if (key != null && pages.any { pageMatches(key, it) }) return true
        // Not set on this site or page: this phone's default.
        return kind in s.mediaDefault
    }

    /**
     * Left out of search results altogether: on a content filter's list (adult, gambling, malware) and not approved
     * anyway, or on this phone's always-blocked list.
     */
    fun hiddenFromSearch(url: String): Boolean {
        val host = hostOf(url) ?: return true
        if (filteredAs(host).isNotEmpty() && !isUnfiltered(host)) return true
        if (covers(state.block, host)) return true
        val key = pageKey(url) ?: return false
        return state.blockPages.any { pageMatches(key, it) }
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
        val mediaFor = if (mediaBlockedForGood(url, host)) tempsFor(url, host, "media", "photos", "videos", "sound") else emptyList()
        return (openFor + mediaFor).filter { it.mode == "use" }
    }

    private fun mediaBlockedForGood(url: String, host: String): Boolean =
        kindBlockedForGood(url, host, "photos") || kindBlockedForGood(url, host, "videos") || kindBlockedForGood(url, host, "sound")

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
            tempsFor(url, host, "media", "photos", "videos", "sound").maxByOrNull { it.left() }?.let {
                val what = when (it.what) { "photos" -> "Photos"; "videos" -> "Videos"; "sound" -> "Sound"; else -> "Photos, videos and sound" }
                return "$what on: ${fmt(it)}."
            }
        }
        return null
    }

    /** Minutes left of temporary access on [url] if it's about to run out (for a warning), else null. */
    fun endingSoon(url: String?): Temp? {
        val host = hostOf(url) ?: return null
        return tempsFor(url!!, host, "site", "page", "media", "photos", "videos", "sound").firstOrNull { it.left() in 1..5 * 60_000L }
    }

    /** True if [url] had temporary access that has run out (so the blocked page can say "Time's up"). */
    fun tempEnded(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        val key = pageKey(url!!)
        return state.temps.any { it.what !in MEDIA_TEMPS && !it.active() && it.covers(host, key) }
    }

    /** True when the site is allowed but only some of its pages, and this isn't one of them. */
    fun isPageRestricted(url: String?): Boolean = isAllowed(url, mainFrame = false) && !isAllowed(url)

    /** JSON the home page reads: the tiles to show. */
    fun homeJson(ctx: Context): String {
        val s = state
        val list = ArrayList<JSONObject>()
        val shown = HashSet<String>()
        s.sites.filter { it.home }.distinctBy { it.domain }.forEach {
            shown += it.domain
            list += JSONObject().put("name", it.name).put("url", it.url).put("domain", it.domain)
        }
        // Sites open temporarily get a tile too, with a timer badge.
        s.temps.filter { it.what == "site" && it.active() && it.entry !in shown }.forEach {
            val min = ((it.left() + 59_999) / 60_000).toInt()
            list += JSONObject().put("name", defaultName(it.entry)).put("url", "https://${it.entry}").put("domain", it.entry)
                .put("temp", if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m")
        }
        // In this phone's order (dragged on the home page); new ones at the end. And where each was left off.
        val order = Tiles.order(ctx)
        val sorted = list.withIndex().sortedWith(compareBy({ order.indexOf(it.value.optString("domain")).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }
        val tiles = JSONArray()
        sorted.forEach { t ->
            Tiles.lastPage(ctx, t.optString("domain"))?.let { (url, title) ->
                if (url.trimEnd('/') != t.optString("url").trimEnd('/')) t.put("last", JSONObject().put("url", url).put("title", title))
            }
            tiles.put(t)
        }
        return JSONObject()
            .put("tiles", tiles)
            .put("siteCount", s.sites.size)
            // Not set up yet: its sealed lists aren't there yet (only the first minute or two, after registering).
            .put("settingUp", !s.registered && Requests.isSetUp())
            .put("loaded", s.updatedAt != 0L || s.sites.isNotEmpty())
            .put("canRequest", Requests.isSetUp())
            .put("search", s.search && s.registered)
            .toString()
    }
}
