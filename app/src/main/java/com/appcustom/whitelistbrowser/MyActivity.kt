package com.appcustom.whitelistbrowser

import android.content.Context
import org.json.JSONObject

/**
 * "My activity": time spent on each site, for the person using this phone to see (Settings → This phone → My
 * activity). Only on this phone: never sent anywhere, not even to the admin page. Counted while a site is on screen
 * (the same 15-second ticks as "time on the site"), by day, kept 30 days.
 */
object MyActivity {
    private const val PREFS = "my_activity"
    private const val KEEP_DAYS = 30
    private var days: JSONObject? = null                 // "2026-10-11" -> { "youtube.com": ms, … }
    private var dirtySince = 0L

    private fun dayKey(t: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(t))

    @Synchronized private fun load(ctx: Context): JSONObject {
        days?.let { return it }
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("days", null)
        val o = runCatching { JSONObject(raw ?: "{}") }.getOrElse { AppLog.w("Activity", "Couldn't read it: ${it.message}"); JSONObject() }
        days = o
        return o
    }

    /** [ms] more on [site] (a site's name, or "Search"). Saved every minute or so. */
    @Synchronized fun add(ctx: Context, site: String, ms: Long) {
        if (site.isBlank() || ms <= 0) return
        val all = load(ctx)
        val key = dayKey(TrustedTime.now())
        val day = all.optJSONObject(key) ?: JSONObject().also { all.put(key, it) }
        day.put(site, day.optLong(site, 0L) + ms)
        val now = System.currentTimeMillis()
        if (dirtySince == 0L) dirtySince = now
        if (now - dirtySince >= 60_000L || now < dirtySince) save(ctx)
    }

    @Synchronized fun save(ctx: Context) {
        val all = days ?: return
        dirtySince = 0L
        // Older than 30 days: gone.
        val cutoff = dayKey(TrustedTime.now() - KEEP_DAYS * 86_400_000L)
        all.keys().asSequence().toList().filter { it < cutoff }.forEach { all.remove(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("days", all.toString()).apply()
    }

    /** Time per site over the last [n] days (1 = today), most first. */
    @Synchronized fun totals(ctx: Context, n: Int): List<Pair<String, Long>> {
        val all = load(ctx)
        val now = TrustedTime.now()
        val keys = (0 until n).map { dayKey(now - it * 86_400_000L) }.toSet()
        val sum = HashMap<String, Long>()
        for (k in keys) all.optJSONObject(k)?.let { d -> d.keys().forEach { s -> sum[s] = (sum[s] ?: 0L) + d.optLong(s) } }
        return sum.entries.map { it.key to it.value }.filter { it.second >= 30_000L }.sortedByDescending { it.second }
    }

    /** 9_000_000 -> "2 h 30 min"; under a minute: "under a minute". */
    fun fmt(ms: Long): String {
        val m = ms / 60_000L
        if (m < 1) return "under a minute"
        val h = m / 60
        return if (h > 0) "$h h${if (m % 60 > 0) " ${m % 60} min" else ""}" else "$m min"
    }
}
