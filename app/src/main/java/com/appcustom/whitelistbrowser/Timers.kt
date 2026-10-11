package com.appcustom.whitelistbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Timers set on the admin page (they come with this phone's lists): changes that switch on at set times.
 *
 *  - "schedule": days and hours, every week (21:00 to 07:00 runs into the next morning).
 *  - "once": from a time, for so long, then it's over.
 *  - "allowance": so much time a day on some sites or lists; when it's used up, they're blocked until tomorrow (or
 *    only their videos and sound are).
 *
 * While a schedule or once timer is on, its "during" changes apply: which sites open (only some lists, these lists
 * too, or nothing), photos, videos and sound (open or blocked everywhere), search, asking for sites, and temporary
 * access. Timers that overlap all apply, and the strictest wins: an allowance that's used up blocks its sites even
 * while another timer opens them.
 *
 * Times are GitHub's ([TrustedTime]), in each timer's own time zone (the one it was made in). "Ask for time" grants
 * (approved on the admin page) come as temporary entries: "timeroff" (the timer is off for a while) and "timermore"
 * (more of an allowance today).
 */
object Timers {
    private const val TAG = "Timers"
    private const val PREFS = "timers"

    class Timer(
        val id: String, val name: String, val kind: String, val tz: TimeZone,
        val days: Set<Int>, val from: Int, val to: Int,           // schedule (minutes of the day); days 0 = Sunday
        val startAt: Long, val minutes: Int,                      // once (and minutes a day for an allowance)
        val sites: String?, val lists: List<String>,              // during: "only" / "also" / "none"
        val media: Map<String, String>,                           // during: kind -> "open" / "blocked"
        val search: String?, val noAsk: Boolean, val pauseTemps: Boolean,
        val onSites: List<String>, val onLists: List<String>, val usedUp: String   // allowance
    )

    fun parse(arr: JSONArray?): List<Timer> {
        if (arr == null) return emptyList()
        val out = ArrayList<Timer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                val kind = o.optString("kind")
                if (kind !in setOf("schedule", "once", "allowance")) return@runCatching
                val d = o.optJSONObject("during") ?: JSONObject()
                val on = o.optJSONObject("on") ?: JSONObject()
                fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }
                fun hm(s: String): Int = s.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                out += Timer(
                    id = o.optString("id"), name = o.optString("name").ifBlank { "Timer" }, kind = kind,
                    tz = TimeZone.getTimeZone(o.optString("tz").ifBlank { "UTC" }),
                    days = (0 until (o.optJSONArray("days")?.length() ?: 0)).map { o.getJSONArray("days").optInt(it, -1) }.filter { it in 0..6 }.toSet(),
                    from = hm(o.optString("from", "00:00")), to = hm(o.optString("to", "00:00")),
                    startAt = o.optLong("startAt", 0L), minutes = o.optInt("minutes", 0).coerceAtLeast(0),
                    sites = d.optString("sites").takeIf { it in setOf("only", "also", "none") },
                    lists = strings(d.optJSONArray("lists")),
                    media = Whitelist.MEDIA_KINDS.mapNotNull { k -> d.optString(k).takeIf { it == "open" || it == "blocked" }?.let { k to it } }.toMap(),
                    search = d.optString("search").takeIf { it == "on" || it == "off" },
                    noAsk = d.optString("ask") == "no", pauseTemps = d.optString("temporary") == "paused",
                    onSites = strings(on.optJSONArray("sites")).map { it.lowercase().removePrefix("www.") },
                    onLists = strings(on.optJSONArray("lists")),
                    usedUp = if (o.optString("usedUp") == "media") "media" else "block")
            }.onFailure { AppLog.w(TAG, "A timer that couldn't be read: ${it.message}") }
        }
        return out
    }

    private fun cal(t: Timer, at: Long): Calendar = Calendar.getInstance(t.tz).apply { timeInMillis = at }
    private fun dayOf(c: Calendar) = c.get(Calendar.DAY_OF_WEEK) - 1          // 0 = Sunday
    private fun minuteOf(c: Calendar) = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    /** "2026-10-11" in the timer's own time zone (an allowance starts again each day). */
    fun dayKey(t: Timer, at: Long): String = cal(t, at).let { "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }

    /** Is a schedule or once timer on at [at]? (An allowance: does it apply that day?) */
    fun onAt(t: Timer, at: Long): Boolean {
        if (t.kind == "once") return at >= t.startAt && at < t.startAt + t.minutes * 60_000L
        val c = cal(t, at); val day = dayOf(c); val min = minuteOf(c)
        if (t.kind == "allowance") return day in t.days
        return when {
            t.from == t.to -> day in t.days
            t.from < t.to -> day in t.days && min >= t.from && min < t.to
            else -> (day in t.days && min >= t.from) || (((day + 6) % 7) in t.days && min < t.to)
        }
    }

    /** When the current stretch of a schedule or once timer ends (for "until 18:00"). */
    fun endsAt(t: Timer, at: Long): Long? {
        if (t.kind == "once") return t.startAt + t.minutes * 60_000L
        if (t.kind != "schedule" || !onAt(t, at)) return null
        // Minute by minute would be slow: step to the "to" time today or tomorrow.
        val c = cal(t, at)
        val min = minuteOf(c)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.set(Calendar.HOUR_OF_DAY, t.to / 60); c.set(Calendar.MINUTE, t.to % 60)
        if (t.from == t.to) { c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.add(Calendar.DAY_OF_MONTH, 1) }
        else if (t.from > t.to && min >= t.from) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** Minutes until a schedule or once timer starts, if it starts within [withinMs]. */
    fun startsWithin(t: Timer, at: Long, withinMs: Long): Long? {
        if (t.kind == "allowance" || onAt(t, at)) return null
        var step = 60_000L
        while (step <= withinMs) { if (onAt(t, at + step)) return step; step += 60_000L }
        return null
    }

    private val state get() = Whitelist.state
    private fun now() = TrustedTime.now()

    /** An approved "Ask for time": this timer is off on this phone for a while (clock time from when it was approved). */
    private fun offNow(t: Timer, at: Long): Boolean = state.temps.any { it.what == "timeroff" && it.entry == t.id && at >= it.from && at < it.from + it.minutes * 60_000L }

    /** Each reading of the time (when not sure of GitHub's, both: the stricter answer wins). */
    private fun readings() = TrustedTime.readings()

    /**
     * The schedule and once timers on now (and not switched off by an approved "Ask for time"). Not sure of the time:
     * for restrictions, on by either reading; for what a timer opens ([loosen]), only when on by both.
     */
    fun activeNow(loosen: Boolean = false): List<Timer> {
        val ts = state.timers
        if (ts.isEmpty()) return emptyList()
        val rs = readings()
        return ts.filter { t -> t.kind != "allowance" && (if (loosen) rs.all { onAt(t, it) && !offNow(t, it) } else rs.any { onAt(t, it) && !offNow(t, it) }) }
    }

    // ---- daily allowances: time used today, kept on this phone ----
    private var used: JSONObject? = null
    private var usedDirty = 0L

    @Synchronized private fun usedMap(ctx: Context): JSONObject {
        used?.let { return it }
        val o = runCatching { JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("used", "{}") ?: "{}") }
            .getOrElse { AppLog.w(TAG, "Couldn't read the time used: ${it.message}"); JSONObject() }
        used = o
        return o
    }

    @Synchronized fun save(ctx: Context) {
        val o = used ?: return
        usedDirty = 0L
        // Only the last few days.
        val keep = o.keys().asSequence().toList().sortedByDescending { it.substringAfter('|') }.take(200).toSet()
        o.keys().asSequence().toList().filter { it !in keep }.forEach { o.remove(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("used", o.toString()).apply()
    }

    /** Does an allowance count time on [url]? (One of its sites, or a site one of its lists opens.) */
    fun counts(t: Timer, url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase()?.trimEnd('.')?.removePrefix("www.") ?: return false
        if (t.onSites.any { host == it || host.endsWith(".$it") }) return true
        return t.onLists.any { n -> state.timerLists[n]?.let { Whitelist.listAllows(it, url, host) } == true }
    }

    /** Extra minutes of [t] approved for today ("Ask for more time"). */
    private fun extraToday(t: Timer, at: Long): Long {
        val today = dayKey(t, at)
        return state.temps.filter { it.what == "timermore" && it.entry == t.id && dayKey(t, it.from) == today }.sumOf { it.minutes * 60_000L }
    }

    /** Milliseconds of [t] left today (0 when used up). */
    fun leftToday(ctx: Context, t: Timer): Long {
        val at = now()
        if (!onAt(t, at)) return Long.MAX_VALUE                 // not a day it applies
        if (offNow(t, at)) return Long.MAX_VALUE                // switched off for a while ("Ask for time")
        // Reinstalled today: what was used before is gone with the old install, so today's counts as used.
        if (TrustedTime.reinstalledOn(dayKey(t, at)) { k -> dayKey(t, k) }) return maxOf(0L, extraToday(t, at) - usedMap(ctx).optLong("${t.id}|${dayKey(t, at)}"))
        val usedMs = usedMap(ctx).optLong("${t.id}|${dayKey(t, at)}")
        return maxOf(0L, t.minutes * 60_000L + extraToday(t, at) - usedMs)
    }

    /** [ms] more on screen at [url]: counted on every allowance that covers it today. */
    @Synchronized fun addUse(ctx: Context, url: String, ms: Long) {
        val at = now()
        val ts = state.timers.filter { it.kind == "allowance" && onAt(it, at) && counts(it, url) }
        if (ts.isEmpty()) return
        val o = usedMap(ctx)
        for (t in ts) { val k = "${t.id}|${dayKey(t, at)}"; o.put(k, o.optLong(k) + ms) }
        val wall = System.currentTimeMillis()
        if (usedDirty == 0L) usedDirty = wall
        if (wall - usedDirty >= 60_000L || wall < usedDirty) save(ctx)
    }

    /** The allowances that cover [url] and are used up today. */
    fun usedUpFor(ctx: Context, url: String): List<Timer> =
        state.timers.filter { it.kind == "allowance" && counts(it, url) && leftToday(ctx, it) <= 0L }

    /** The allowance covering [url] with the least time left today, and how much (for "32 min left today"). */
    fun allowanceHere(ctx: Context, url: String): Pair<Timer, Long>? =
        state.timers.filter { it.kind == "allowance" && onAt(it, now()) && counts(it, url) }
            .map { it to leftToday(ctx, it) }.filter { it.second != Long.MAX_VALUE }.minByOrNull { it.second }

    // ---- what the timers change ----
    private var appCtx: Context? = null
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    /** Why a timer blocks [url] (the main page), or null. [base]: whether the lists (and temporary access) allow it. */
    fun blocks(url: String, host: String, base: Boolean): Timer? {
        val active = activeNow()
        active.firstOrNull { it.sites == "none" }?.let { return it }
        // Only some lists: each such timer's lists must open it (or temporary access, unless that's paused too).
        for (t in active.filter { it.sites == "only" }) {
            val byLists = t.lists.any { n -> state.timerLists[n]?.let { Whitelist.listAllows(it, url, host) } == true }
            if (!byLists && !Whitelist.tempOpens(url, host)) return t
        }
        // A daily allowance that's used up, when it blocks its sites.
        appCtx?.let { ctx -> usedUpFor(ctx, url).firstOrNull { it.usedUp == "block" }?.let { return it } }
        return null
    }

    /** "Also these lists": opens [url] although the phone's own lists don't. */
    fun alsoOpens(url: String, host: String): Boolean =
        activeNow(loosen = true).any { t -> t.sites == "also" && t.lists.any { n -> state.timerLists[n]?.let { Whitelist.listAllows(it, url, host) } == true } }

    /** A timer's say on [kind] for [url]: true = blocked, false = open, null = no change. Blocked wins. */
    fun mediaRule(url: String, kind: String): Boolean? {
        val active = activeNow()
        if (active.any { it.media[kind] == "blocked" }) return true
        if (kind != "photos") appCtx?.let { ctx -> if (usedUpFor(ctx, url).any { it.usedUp == "media" }) return true }
        if (activeNow(loosen = true).any { it.media[kind] == "open" }) return false
        return null
    }

    /** Search on or off by a timer (off wins), or null: as the phone is set. */
    fun searchRule(): Boolean? {
        val active = activeNow()
        if (active.any { it.search == "off" }) return false
        if (activeNow(loosen = true).any { it.search == "on" }) return true
        return null
    }

    /** A timer on now that says nothing new can be asked for. */
    fun askBlockedBy(): Timer? = activeNow().firstOrNull { it.noAsk }

    /** Temporary access paused by a timer on now. */
    fun tempsPaused(): Boolean = activeNow().any { it.pauseTemps }

    /** The timer to show on the home page (the strictest on now), and until when. */
    fun headline(): Pair<Timer, Long?>? {
        val active = activeNow()
        val t = active.firstOrNull { it.sites == "none" } ?: active.firstOrNull { it.sites == "only" } ?: active.firstOrNull() ?: return null
        return t to endsAt(t, now())
    }

    /** "18:00" (or "Mon 18:00" if not today), in the phone's own time zone. */
    fun clock(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance().apply { timeInMillis = now() }
        val hhmm = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        return if (c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == today.get(Calendar.YEAR)) hhmm
            else java.text.SimpleDateFormat("EEE", java.util.Locale.UK).format(c.time) + " " + hhmm
    }

    /** Something that changes what's allowed, to tell when the timers switch (the home page is redrawn then). */
    fun signature(ctx: Context): String = activeNow().joinToString(",") { it.id } + "|" +
        state.timers.filter { it.kind == "allowance" }.joinToString(",") { "${it.id}:${leftToday(ctx, it) <= 0L}" }

    fun byId(id: String): Timer? = state.timers.firstOrNull { it.id == id }
}
