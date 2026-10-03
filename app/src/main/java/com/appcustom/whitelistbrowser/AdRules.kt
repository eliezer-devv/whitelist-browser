package com.appcustom.whitelistbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fuller ad blocking, beyond whole ad sites (AdBlock), coming entirely from AdGuard: its filter lists
 * (Config.AD_FILTER_LISTS) and its own code for the scriptlets they use (Config.AD_SCRIPTLETS_URL). Phones download
 * them once a day, so AdGuard's updates (YouTube's included) arrive by themselves; a copy packed into the app when
 * it's built works from the first launch. The rules are read by AdFilters (plain Java, tested on its own).
 * All of it follows the "Block ads" setting.
 */
object AdRules {
    @Volatile private var groups: Map<String, AdFilters> = emptyMap()      // "ads", "trackers", "annoyances"
    @Volatile private var extendedCss: String? = null                      // AdGuard's ExtendedCss code
    @Volatile private var scriptlets: Map<String, String> = emptyMap()     // scriptlet name -> AdGuard's code for it
    @Volatile private var scriptletsVersion = ""
    @Volatile var status = "Not loaded yet"
        private set
    /** How many rules each list gave (for About this phone), e.g. "Base 98k · Mobile Ads 3k · …". */
    @Volatile var listsSummary = ""
        private set
    private const val PREFS = "adrules"
    private const val DAY = 24 * 3_600_000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "adlists").apply { mkdirs() }

    /** A list or the scriptlet code: the downloaded copy if there is one, else the one packed into the app. */
    private fun read(ctx: Context, name: String): String? {
        val f = File(dir(ctx), name)
        if (f.exists() && f.length() > 0) return runCatching { f.readText() }.getOrNull()
        return runCatching { ctx.assets.open("adlists/$name").bufferedReader().use { it.readText() } }.getOrNull()
    }

    /** [load], saying why if it can't (rather than "Not loaded yet" for ever). */
    fun loadSafely(ctx: Context) {
        status = "Loading…"
        val t0 = System.currentTimeMillis()
        try {
            load(ctx)
            AppLog.i("Ad blocking", "Loaded in ${System.currentTimeMillis() - t0} ms: $listsSummary; scriptlet code: ${scriptlets.size} scriptlets" +
                (if (extendedCss == null) "; advanced element rules: not available" else ""))
        } catch (t: Throwable) {
            status = "Couldn't load AdGuard's rules (${t.javaClass.simpleName}: ${t.message})"
            AppLog.e("Ad blocking", "Loading failed", t)
        }
    }

    /** Reads everything (in the background): at start, and after each update. */
    fun load(ctx: Context) {
        val loaded = HashMap<String, AdFilters>()
        val counts = ArrayList<String>()
        for ((group, lists) in Config.AD_FILTER_GROUPS) {
            val f = AdFilters()
            for ((name, _) in lists) {
                val before = f.kept
                val text = read(ctx, name)
                if (text != null) f.add(text)
                val n = f.kept - before
                val label = name.removePrefix("adguard-").removeSuffix(".txt").replaceFirstChar { it.uppercase() }
                counts += "$label " + when {
                    text == null -> "missing"
                    // Nothing usable in it: what it was (to see why), e.g. an error page or a notice.
                    n == 0 -> "0 (of ${text.lines().size} lines, starting \"${text.trim().lines().firstOrNull().orEmpty().take(50)}\")"
                    n >= 1000 -> "${n / 1000}k"
                    else -> "$n"
                }
            }
            loaded[group] = f
        }
        listsSummary = counts.joinToString(" · ")
        val lib = read(ctx, "scriptlets.json")?.let { parseScriptlets(it) }
        groups = loaded
        extendedCss = read(ctx, "extended-css.js")?.takeIf { it.contains("ExtendedCss") }
        synchronized(hideScripts) { hideScripts.clear() }
        if (lib != null) { scriptlets = lib.first; scriptletsVersion = lib.second }
        val updated = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("updated", 0L)
        status = (if (updated > 0) "From AdGuard, updated " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(updated))
            else "From AdGuard (the copy built into the app)") +
            (if (scriptlets.isEmpty()) "; scriptlets not available" else "") + (if (extendedCss == null) "; advanced rules not available" else "")
    }

    /** AdGuard's scriptlet code, as published for apps: {"scriptlets":[{"names":[…],"scriptlet":"function(source, args){…}"}]}. */
    private fun parseScriptlets(text: String): Pair<Map<String, String>, String>? = runCatching {
        val o = JSONObject(text)
        val arr = o.getJSONArray("scriptlets")
        val map = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val code = s.optString("scriptlet").trim()
            if (!code.startsWith("function")) continue
            val names = s.optJSONArray("names") ?: continue
            for (j in 0 until names.length()) map[names.getString(j)] = code
        }
        if (map.isEmpty()) null else map to o.optString("version")
    }.getOrNull()

    /**
     * Downloads AdGuard's lists and scriptlet code, at most once a day (in the background). A failed download
     * keeps the copy the phone has, and is tried again in an hour.
     */
    fun update(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("tried", 0L) < 3_600_000L) return
        if (now - p.getLong("updated", 0L) < DAY) return
        p.edit().putLong("tried", now).apply()
        var got = 0
        for ((name, urls) in Config.AD_FILTER_GROUPS.values.flatten()) {
            // The first address that gives a list (several may be given, " | " between them).
            val text = urls.split(" | ").map { it.trim() }.firstNotNullOfOrNull { u -> download(u)?.takeIf { it.lines().size >= 20 } }
            if (text == null) { AppLog.w("Ad blocking", "Daily download of $name: none of its addresses worked"); continue }
            File(dir(ctx), "$name.new").writeText(text)
            File(dir(ctx), "$name.new").renameTo(File(dir(ctx), name))
            got++
        }
        download(Config.AD_SCRIPTLETS_URL)?.let { if (parseScriptlets(it) != null) File(dir(ctx), "scriptlets.json").writeText(it) }
        for (u in Config.AD_EXTENDED_CSS_URLS) {
            val code = download(u) ?: continue
            if (code.contains("ExtendedCss")) { File(dir(ctx), "extended-css.js").writeText(code); break }
        }
        AppLog.i("Ad blocking", "Daily download: $got of ${Config.AD_FILTER_GROUPS.values.sumOf { it.size }} lists")
        if (got > 0) {
            p.edit().putLong("updated", now).apply()
            loadSafely(ctx)
        }
    }

    private fun download(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000; c.readTimeout = 30_000
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }.getOrNull()

    private fun on(which: Set<String>) = groups.filterKeys { it in which }.values

    /**
     * What AdGuard does on [host]'s pages (for About this phone, to see why ads might get through): its scriptlets
     * and scripts, whether AdGuard's code has each scriptlet, and how many elements are hidden.
     */
    fun describe(host: String?, which: Set<String>, runsFirst: Boolean): String {
        val h = host?.lowercase()?.removePrefix("www.") ?: return "No site open"
        if (which.isEmpty()) return "Off here"
        val rules = on(which).flatMap { it.scriptsFor(h) }.distinct()
        val names = rules.mapNotNull { AdFilters.scriptletParts(it)?.firstOrNull() }
        val missing = names.filter { it !in scriptlets }.distinct()
        val hidden = on(which).sumOf { it.hiddenCount(h) }                         // counted, not built (quick)
        val grouped = names.groupingBy { it }.eachCount().entries.joinToString(", ") { if (it.value > 1) "${it.key} ×${it.value}" else it.key }
        return "$h: ${names.size} scriptlets" + (if (grouped.isNotEmpty()) " ($grouped)" else "") +
            ", ${rules.size - names.size} scripts, $hidden elements hidden. Scripts run first: ${if (runsFirst) "yes" else "no (they may run too late)"}" +
            (if (missing.isNotEmpty()) ". AdGuard's code is missing: ${missing.joinToString(", ")}" else "")
    }

    /** Is this address (loaded by a page on [pageHost]) blocked by the groups switched on ([which])? */
    fun blocks(url: android.net.Uri, pageHost: String?, which: Set<String>): Boolean {
        val host = url.host ?: return false
        val rest = (url.encodedPath ?: "") + (url.encodedQuery?.let { "?$it" } ?: "")
        val whole = url.toString()
        return on(which).any { it.blocks(host, rest, pageHost, whole) }
    }

    /** A page's address without its tracking codes (utm_source, fbclid…), by the groups switched on. */
    fun cleanUrl(url: String, which: Set<String>): String = on(which).fold(url) { u, f -> f.cleanUrl(u) }

    /** AdGuard's advanced element rules for [host], run with AdGuard's ExtendedCss code (if it's available). */
    fun extendedScript(host: String?, which: Set<String>): String? {
        val lib = extendedCss ?: return null
        val h = host?.lowercase()?.removePrefix("www.") ?: return null
        val rules = on(which).flatMap { it.extendedFor(h) }.distinct()
        if (rules.isEmpty()) return null
        val list = JSONArray(rules).toString()
        return "(function(){if(window.__wlbEcss)return;window.__wlbEcss=1;try{\n$lib\n;var r=$list;" +
            "try{new ExtendedCss({cssRules:r}).apply();}catch(e){new ExtendedCss({styleSheet:r.join('\\n')}).apply();}}catch(e){}})();"
    }

    private val hideScripts = object : LinkedHashMap<String, String>(16, 0.75f, true) {     // the last few sites'
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8
    }

    /** The page script hiding ad elements on [host]'s pages. */
    fun hideScript(host: String?, which: Set<String>): String? {
        val h = host?.lowercase()?.removePrefix("www.") ?: return null
        val key = h + "|" + which.sorted().joinToString(",")
        synchronized(hideScripts) { hideScripts[key]?.let { return it } }
        val css = on(which).joinToString("") { it.hideCss(h) }
        if (css.isEmpty()) return null
        val js = "(function(){var id='wlb-ads';if(document.getElementById(id))return;var s=document.createElement('style');s.id=id;" +
            "s.textContent=" + JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);})();"
        synchronized(hideScripts) { hideScripts[key] = js }
        return js
    }

    /**
     * AdGuard's scripts for [host]'s pages: each scriptlet rule run with AdGuard's own code for it, and its plain
     * script rules.
     */
    fun pageScript(host: String?, which: Set<String>): String? {
        val h = host?.lowercase()?.removePrefix("www.") ?: return null
        val code = StringBuilder()
        for (rule in on(which).flatMap { it.scriptsFor(h) }.distinct()) {
            val parts = AdFilters.scriptletParts(rule)
            if (parts != null) {
                val fn = scriptlets[parts[0]] ?: continue
                val args = JSONArray(parts.drop(1))
                val source = JSONObject().put("name", parts[0]).put("args", args).put("engine", "corelibs")
                    .put("version", scriptletsVersion).put("verbose", false).put("ruleText", "$h#%#$rule")
                    .put("uniqueId", "wlb" + Integer.toHexString(rule.hashCode()))
                code.append("try{(").append(fn).append(")(").append(source).append(",").append(args).append(");}catch(e){}\n")
            } else if (!rule.startsWith("//")) {
                code.append("try{").append(rule).append("\n}catch(e){}\n")
            }
        }
        if (code.isEmpty()) return null
        return "(function(){if(window.__wlbAg)return;window.__wlbAg=1;\n$code})();"
    }
}
