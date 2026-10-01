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
