package com.appcustom.whitelistbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import java.net.HttpURLConnection

/**
 * The time, as GitHub says it is, so changing the phone's clock doesn't get around timers or "for a while".
 *
 * Every answer from GitHub carries the time (its Date header). The phone notes it together with the time since the
 * phone started (which the clock setting can't change): until the phone restarts, the time is exactly GitHub's plus
 * the time since. It also keeps how far the phone's clock was from GitHub's ([offset]) and the latest time it was
 * sure of ([lastTrusted]), so after a restart it carries on from there, and never goes back past [lastTrusted].
 *
 * Until it hears from GitHub again after a restart (or after the clock was changed while the app was stopped), it's
 * [sure] = false: timers then use whichever reading is stricter (see [stricterNow]).
 */
object TrustedTime {
    private const val PREFS = "trusted_time"
    private const val TAG = "Clock"
    private var appContext: Context? = null

    // This boot: GitHub's time and the time since start when it was read.
    @Volatile private var syncGithub = 0L
    @Volatile private var syncElapsed = -1L
    @Volatile private var offset = 0L                  // GitHub's time minus the phone's clock (kept across restarts)
    @Volatile private var lastTrusted = 0L
    @Volatile private var lastSaved = 0L
    @Volatile private var haveOffset = false
    @Volatile private var installAt = 0L               // GitHub's time when this install first heard from it (0: not yet)

    /**
     * "Time on the site" grants count time spent only if given after this install. One from before (the app was
     * reinstalled, which forgets the time spent) counts as clock time from when it was given instead.
     */
    fun countsUse(grantedAt: Long): Boolean = installAt in 1..grantedAt

    /**
     * The first time this install fetched its lists: were they there already (sealed for this phone's ID, maybe with
     * an older key)? Then this is a reinstall, and what was used of today's allowances went with the old install.
     */
    @Volatile private var reinstall = false
    fun noteFirstFetch(bundleThere: Boolean) {
        val p = prefs() ?: return
        if (p.contains("reinstall")) return
        reinstall = bundleThere && installAt != 1L
        p.edit().putBoolean("reinstall", reinstall).apply()
        if (reinstall) AppLog.w(TAG, "Installed again on a phone that had the app: today's allowances count as used")
    }
    /** Reinstalled on the day [today] (as [dayOf] gives a time's day). */
    fun reinstalledOn(today: String, dayOf: (Long) -> String): Boolean = reinstall && installAt > 1L && dayOf(installAt) == today

    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        val p = prefs() ?: return
        offset = p.getLong("offset", 0L)
        haveOffset = p.contains("offset")
        lastTrusted = p.getLong("lastTrusted", 0L)
        // Installed before this was kept (an update, not a new install): everything counts from before.
        if (!p.contains("installAt") && (Device.name(ctx) != null || ctx.getSharedPreferences("my_requests", Context.MODE_PRIVATE).contains("items")))
            p.edit().putLong("installAt", 1L).apply()
        installAt = p.getLong("installAt", 0L)
        reinstall = p.getBoolean("reinstall", false)
        // The same boot as the last reading from GitHub: still exact (the time since start carries on).
        if (p.getInt("boot", -1) == bootCount() && bootCount() >= 0) {
            val e = p.getLong("syncElapsed", -1L)
            if (e in 0..SystemClock.elapsedRealtime()) { syncElapsed = e; syncGithub = p.getLong("syncGithub", 0L) }
        }
        checkClock("start")
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun bootCount(): Int = appContext?.let { runCatching { Settings.Global.getInt(it.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull() } ?: -1

    /** Heard from GitHub since the phone last started: the time is exact. */
    val sure: Boolean get() = syncElapsed >= 0

    /** GitHub's time now (as well as can be known). */
    fun now(): Long {
        val wall = System.currentTimeMillis()
        val t = if (syncElapsed >= 0) syncGithub + (SystemClock.elapsedRealtime() - syncElapsed)
            else if (haveOffset) wall + offset else wall
        val out = maxOf(t, lastTrusted)
        if (out - lastSaved > 60_000L) remember(out)
        return out
    }

    /**
     * For timers when not [sure]: the later of the two readings for when something ends (it ends sooner), and both
     * for whether a restriction applies. [now] already never goes back; this adds the phone's own clock.
     */
    fun readings(): List<Long> = if (sure) listOf(now()) else listOf(now(), maxOf(System.currentTimeMillis(), lastTrusted)).distinct()
    fun stricterNow(): Long = readings().max()

    private fun remember(t: Long) {
        lastTrusted = maxOf(lastTrusted, t)
        lastSaved = t
        prefs()?.edit()?.putLong("lastTrusted", lastTrusted)?.apply()
    }

    /** An answer from GitHub (any request to github.com, its Pages or raw files): its Date header. */
    fun fromResponse(conn: HttpURLConnection) {
        runCatching {
            val elapsed = SystemClock.elapsedRealtime()
            val date = conn.getHeaderFieldDate("Date", 0L)
            if (date > 1_600_000_000_000L) sync(date + 500L, elapsed)   // (the header is to the second: the middle of it)
        }.onFailure { AppLog.w(TAG, "Couldn't read GitHub's time: ${it.message}") }
    }

    @Synchronized fun sync(githubMs: Long, elapsed: Long) {
        val wasSure = sure
        // A slightly older answer (it was cached, say) doesn't move the time back.
        if (syncElapsed >= 0 && githubMs < syncGithub + (elapsed - syncElapsed) - 5_000L) return
        syncGithub = githubMs; syncElapsed = elapsed
        val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsed)
        val newOffset = githubMs - wall
        if (!wasSure) AppLog.i(TAG, "Time from GitHub: the phone's clock is ${fmtOffset(newOffset)}")
        offset = newOffset; haveOffset = true
        remember(githubMs)
        if (installAt == 0L) { installAt = githubMs; prefs()?.edit()?.putLong("installAt", installAt)?.apply() }
        prefs()?.edit()?.putLong("offset", offset)?.putLong("syncGithub", syncGithub)?.putLong("syncElapsed", syncElapsed)
            ?.putInt("boot", bootCount())?.putLong("bootWall", wall - elapsed)?.apply()
    }

    private fun fmtOffset(ms: Long): String {
        val m = Math.round(Math.abs(ms) / 60_000.0)
        return if (m == 0L) "right" else "$m minute${if (m == 1L) "" else "s"} ${if (ms > 0) "behind" else "ahead"}"
    }

    /** Is the phone's automatic date and time on? */
    fun autoTime(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 1) != 0 }.getOrDefault(true)

    /**
     * The clock may have been changed (the TIME_SET broadcast, or the app starting): the time it was moved by is how
     * far "when the phone started" moved. Within this boot, after a reading from GitHub, nothing changes (the time
     * since start is used); otherwise the kept difference is corrected by the same amount, so it stays right.
     * Changed by hand (automatic time off, more than 2 minutes): noted, and told to the admin page.
     */
    @Synchronized fun checkClock(why: String) {
        val ctx = appContext ?: return
        val p = prefs() ?: return
        val bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val sameBoot = p.getInt("boot", -1) == bootCount() && bootCount() >= 0
        val before = p.getLong("bootWall", Long.MIN_VALUE)
        val e = p.edit()
        if (sameBoot && before != Long.MIN_VALUE) {
            val moved = bootWall - before
            if (Math.abs(moved) > 2 * 60_000L) {
                offset -= moved                       // the phone's clock moved; GitHub's didn't
                e.putLong("offset", offset)
                val auto = autoTime(ctx)
                AppLog.w(TAG, "The phone's clock was changed by ${fmtOffset(-moved).replace("behind", "back").replace("ahead", "forward")} ($why, automatic time ${if (auto) "on" else "off"})")
                if (!auto) { e.putLong("changedAt", now()); Requests.checkInSoon(ctx) }
            }
        } else if (!sameBoot && why == "start") {
            // A restart: exact again once GitHub answers. Until then the kept difference is used.
            AppLog.i(TAG, "Phone restarted: using the kept time until GitHub answers")
        }
        e.putLong("bootWall", bootWall).putInt("boot", bootCount()).apply()
        // Automatic time switched off or on since last time: the admin page is told.
        val auto = autoTime(ctx)
        if (p.getBoolean("auto", true) != auto) {
            p.edit().putBoolean("auto", auto).apply()
            AppLog.w(TAG, "Automatic date and time is now ${if (auto) "on" else "off"}")
            Requests.checkInSoon(ctx)
        }
    }

    /** For the check-in record (the admin page shows it): automatic time, when it was changed by hand, how far off. */
    fun report(ctx: Context): org.json.JSONObject {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return org.json.JSONObject().put("auto", autoTime(ctx))
            .apply { p.getLong("changedAt", 0L).takeIf { it > 0 }?.let { put("changedAt", it) } }
            .apply { if (haveOffset) put("offsetMin", Math.round(-offset / 60_000.0)) }
            .put("sure", sure)
    }
}

/** The clock or time zone was changed (Android tells apps even when they aren't open, unless force stopped). */
class ClockChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            AppLog.ready(context)
            TrustedTime.init(context)
            TrustedTime.checkClock(if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) "time zone" else "clock set")
        }.onFailure { runCatching { AppLog.e("Clock", "Clock change", it) } }
    }
}
