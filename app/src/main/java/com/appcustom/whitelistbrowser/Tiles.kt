package com.appcustom.whitelistbrowser

import android.content.Context
import org.json.JSONObject

/**
 * Home page tiles, as arranged on this phone: their order (dragged on the home page) and the last page
 * opened on each site ("Open where you left off"). Kept on the phone only.
 */
object Tiles {
    private const val PREFS = "tiles"

    /** The tiles' order: their sites' domains, first to last. Sites not in it come after, as listed. */
    fun order(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("order", "")!!
            .split('\n').filter { it.isNotEmpty() }

    fun setOrder(ctx: Context, domains: List<String>) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("order", domains.take(200).joinToString("\n")).apply()

    /**
     * Folders on the home page (made by dragging one tile onto another): [{"id","name","sites":[domains]}], kept on the
     * phone like the order. A folder's place in the order is the key "folder:<id>".
     */
    fun folders(ctx: Context): org.json.JSONArray =
        runCatching { org.json.JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("folders", "[]")) }
            .onFailure { AppLog.w("Home", "Folders couldn't be read: ${it.message}") }.getOrDefault(org.json.JSONArray())

    /** Saves the folders (from the home page), checked: at most 50, names up to 30 characters, sites as domains. */
    fun setFolders(ctx: Context, raw: org.json.JSONArray) {
        val clean = org.json.JSONArray()
        for (i in 0 until minOf(raw.length(), 50)) {
            val f = raw.optJSONObject(i) ?: continue
            val id = f.optString("id").takeIf { Regex("^[a-z0-9]{1,20}$").matches(it) } ?: continue
            val sitesA = f.optJSONArray("sites") ?: continue
            val sites = (0 until sitesA.length()).mapNotNull { Whitelist.normalize(sitesA.optString(it)) }.distinct().take(60)
            if (sites.isEmpty()) continue
            clean.put(org.json.JSONObject().put("id", id).put("name", f.optString("name").replace(Regex("[\\r\\n<>]"), " ").trim().take(30).ifEmpty { "Folder" })
                .put("sites", org.json.JSONArray(sites)))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("folders", clean.toString()).apply()
        AppLog.i("Home", "Folders saved (${clean.length()})")
    }

    /** Remembers the page now open on [site] (its address and title). */
    fun rememberPage(ctx: Context, site: String, url: String, title: String?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = runCatching { JSONObject(p.getString("last", "{}")!!) }.getOrDefault(JSONObject())
        all.put(site, JSONObject().put("url", url).put("title", title ?: ""))
        p.edit().putString("last", all.toString()).apply()
    }

    /** The last page opened on [site]: address and title, or null. */
    fun lastPage(ctx: Context, site: String): Pair<String, String>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = runCatching { JSONObject(p.getString("last", "{}")!!) }.getOrDefault(JSONObject())
        val o = all.optJSONObject(site.removePrefix("www.")) ?: return null
        val url = o.optString("url").takeIf { it.isNotEmpty() } ?: return null
        return url to o.optString("title")
    }
}
