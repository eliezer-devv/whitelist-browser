package com.appcustom.whitelistbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * This phone's own requests and their answers, kept on the phone.
 *
 * Once a request is sent, the phone checks it on GitHub now and then. When it's been answered, the
 * owner's reply carries a hidden "whitelist-response" note with the answer in plain words, which the
 * phone saves here and shows the user.
 */
object MyRequests {
    private const val PREFS = "my_requests"
    private const val KEEP = 100                         // remember the latest 100 (archived ones included)
    private const val AUTO_ARCHIVE_MS = 30L * 24 * 3_600_000 // answered ones move to the archive after 30 days
    private const val GIVE_UP_MS = 60L * 24 * 3_600_000  // stop checking after 60 days

    /** One request. [status]: waiting, approved, denied, closed, failed. [archived]: in the archive (by
     *  hand, or answered more than 30 days ago). [asked] also identifies it. */
    class Item(val number: Int, val summary: String, val asked: Long, val status: String,
               val message: String, val answered: Long, val seen: Boolean, val archived: Boolean = false,
               val request: JSONObject? = null,   // what was asked (to tell when the change has reached the phone)
               val pinChecking: Boolean = false)  // a PIN was sent for it: waiting for GitHub to check it

    @Synchronized private fun read(ctx: Context): JSONArray =
        runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]")) }
            .getOrDefault(JSONArray())

    @Synchronized private fun write(ctx: Context, items: JSONArray) {
        val trimmed = JSONArray()
        val from = maxOf(0, items.length() - KEEP)
        for (i in from until items.length()) trimmed.put(items.get(i))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", trimmed.toString()).apply()
    }

    private fun toItem(o: JSONObject): Item {
        val status = o.optString("status", "waiting")
        val answered = o.optLong("answered")
        // (No archive any more: requests stay in the list until deleted, including ones archived before.)
        return Item(o.optInt("number"), o.optString("summary"), o.optLong("asked"), status, o.optString("message"),
            answered, o.optBoolean("seen", true), false,
            o.optJSONObject("request"), status == "waiting" && o.optBoolean("pinChecking", false))
    }

    /** A request was sent (as GitHub issue [number]). [request]: what it asked for. */
    @Synchronized fun sent(ctx: Context, number: Int, summary: String, asked: Long, request: JSONObject? = null) {
        val items = read(ctx)
        val o = JSONObject().put("number", number).put("summary", summary).put("asked", asked).put("status", "waiting")
        if (request != null) o.put("request", request)
        items.put(o)
        write(ctx, items)
    }

    /** A request that GitHub refused for good, so the user knows it didn't go through. */
    @Synchronized fun failed(ctx: Context, summary: String, asked: Long, reason: String) {
        val items = read(ctx)
        items.put(JSONObject().put("number", 0).put("summary", summary).put("asked", asked).put("status", "failed")
            .put("message", "Couldn't be sent: $reason").put("answered", System.currentTimeMillis()).put("seen", false))
        write(ctx, items)
    }

    /** All requests, newest first. */
    fun all(ctx: Context): List<Item> {
        val items = read(ctx)
        return (0 until items.length()).map { toItem(items.getJSONObject(it)) }.reversed()
    }

    /** Moves an answered request to the archive, or back ([archive] = false). Waiting ones stay put. */
    @Synchronized fun setArchived(ctx: Context, asked: Long, archive: Boolean) {
        val items = read(ctx)
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.optLong("asked") == asked && o.optString("status", "waiting") != "waiting") {
                o.put("archived", archive).put("restored", !archive)
            }
        }
        write(ctx, items)
    }

    /** A PIN was sent for these waiting requests: they say so until GitHub answers. */
    @Synchronized fun markCheckingPin(ctx: Context, numbers: Collection<Int>) {
        val items = read(ctx)
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.optInt("number") in numbers && o.optString("status", "waiting") == "waiting")
                o.put("message", "Checking the PIN…").put("pinChecking", true).put("pinSentAt", System.currentTimeMillis())
        }
        write(ctx, items)
    }

    /** A waiting request was withdrawn on the phone ("Cancel request"). */
    @Synchronized fun markCancelled(ctx: Context, asked: Long) {
        read(ctx).let { items -> for (i in 0 until items.length()) items.getJSONObject(i).let { if (it.optLong("asked") == asked) Requests.forget(ctx, it.optJSONObject("request")) } }
        val items = read(ctx)
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.optLong("asked") == asked && o.optString("status", "waiting") == "waiting") {
                o.put("status", "cancelled").put("message", "You cancelled it.").put("answered", System.currentTimeMillis()).put("seen", true)
            }
        }
        write(ctx, items)
    }

    /** Deletes one request from this phone's history (it stays on GitHub). */
    @Synchronized fun delete(ctx: Context, asked: Long) {
        val items = read(ctx)
        val kept = JSONArray()
        for (i in 0 until items.length()) if (items.getJSONObject(i).optLong("asked") != asked) kept.put(items.get(i))
        write(ctx, kept)
    }

    /** Deletes everything in the archive. */
    @Synchronized fun deleteArchived(ctx: Context) {
        val items = read(ctx)
        val kept = JSONArray()
        for (i in 0 until items.length()) if (!toItem(items.getJSONObject(i)).archived) kept.put(items.get(i))
        write(ctx, kept)
    }

    // An approval is held until the change reaches the phone, but not longer than this.
    private const val HOLD_MAX_MS = 10 * 60_000L

    /**
     * Answers the user hasn't seen yet; marks them seen. An approval is held back until [arrived] says the
     * change has reached the phone (its lists show it), so the answer is true when it's read; after 10
     * minutes it's shown anyway, saying it may take a little longer. Denials and notices come at once.
     */
    @Synchronized fun takeNewAnswers(ctx: Context, arrived: (Item) -> Boolean = { true }): List<Item> {
        val items = read(ctx)
        val fresh = ArrayList<Item>()
        val now = System.currentTimeMillis()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.optBoolean("seen", true)) continue
            val item = toItem(o)
            if (item.status == "approved" && !arrived(item)) {
                if (now - item.answered < HOLD_MAX_MS) continue          // not on the phone yet: keep it back
                o.put("message", item.message + " It may take a few more minutes to reach this phone.")
            }
            o.put("seen", true)
            fresh += toItem(o)
        }
        if (fresh.isNotEmpty()) write(ctx, items)
        // Shown in the app now: a notification about the same answer (from the background check) goes.
        fresh.forEach { UserAlerts.clear(ctx, it.number) }
        return fresh
    }

    /** Are approvals being held back, waiting for the change to reach the phone? */
    @Synchronized fun anyHeld(ctx: Context): Boolean {
        val items = read(ctx)
        return (0 until items.length()).any { val o = items.getJSONObject(it); !o.optBoolean("seen", true) && o.optString("status") == "approved" }
    }

    /**
     * Asks GitHub about requests still waiting for an answer. Blocking; run off the main thread.
     * At most every 5 minutes. Stops at the first connection problem.
     */
    /** PIN approvals locked on this phone (5 wrong PINs) until this time, or 0. */
    fun pinLockedUntil(ctx: Context): Long {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The lock noted here from GitHub's answer counts only until the lists have caught up with it (a few minutes):
        // after that the lists decide, so unlocking it on the admin page works.
        val noted = p.getLong("pinLockNotedAt", 0L)
        // An admin pressed Unlock since this phone noted its lock: unlocked, whatever else this phone remembers.
        val unlocked = Whitelist.state.pinUnlocks > p.getInt("pinUnlocksSeen", 0)
        val here = if (unlocked || (noted > 0L && Whitelist.state.updatedAt > noted + 4 * 60_000L)) 0L else p.getLong("pinLockedUntil", 0L)
        return maxOf(here, Whitelist.state.pinLockedUntil).takeIf { it > System.currentTimeMillis() } ?: 0L
    }

    /**
     * Answers changed on the admin page after this phone had them ("Changed to denied", or approved after all):
     * published with the lists. Each is taken once (by when it was changed), and shown like a new answer.
     */
    @Synchronized fun applyAnswerChanges(ctx: Context) {
        val raw = Whitelist.state.answerChanges
        if (raw.isBlank()) return
        val changes = runCatching { JSONArray(raw) }.getOrElse { AppLog.w("MyRequests", "Couldn't read changed answers: ${it.message}"); return }
        val items = read(ctx)
        var changed = false
        for (c in 0 until changes.length()) {
            val ch = changes.optJSONObject(c) ?: continue
            val n = ch.optInt("n"); val at = ch.optString("at")
            val status = ch.optString("o").takeIf { it == "approved" || it == "denied" } ?: continue
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                if (o.optInt("number") != n || n <= 0 || o.optString("changedAt") >= at) continue
                // (Still waiting here: the first answer hasn't reached this phone yet. It's simply this answer now.)
                o.put("status", status).put("message", ch.optString("m")).put("changedAt", at)
                    .put("answered", System.currentTimeMillis()).put("seen", false).put("pinChecking", false)
                Requests.forget(ctx, o.optJSONObject("request"))
                AppLog.i("MyRequests", "Request #$n changed to $status")
                changed = true
            }
        }
        if (changed) write(ctx, items)
    }

    fun check(ctx: Context, minGapMs: Long = 5 * 60_000L) {
        runCatching { applyAnswerChanges(ctx) }.onFailure { AppLog.w("MyRequests", "Changed answers: ${it.message}") }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("lastCheck", 0L) < minGapMs) return
        p.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        val waiting = synchronized(this) {
            val items = read(ctx)
            (0 until items.length()).map { items.getJSONObject(it) }
                .filter { it.optString("status") == "waiting" && it.optInt("number") > 0 }
                .map { it.optInt("number") to it.optLong("asked") }
        }
        for ((number, asked) in waiting) {
            val answer = runCatching { Requests.answerTo(number) }.getOrElse { return } // offline: try later
            // A notice while it's still waiting: show it once, and keep waiting for the answer.
            if (answer?.first == "notice") {
                synchronized(this) {
                    val items = read(ctx)
                    for (i in 0 until items.length()) {
                        val o = items.getJSONObject(i)
                        if (o.optInt("number") != number) continue
                        // A notice from before the PIN was sent (an earlier wrong PIN, say) isn't the answer to it.
                        if (o.optBoolean("pinChecking") && answer.third in 1 until o.optLong("pinSentAt") - 60_000L) continue
                        if (answer.second.contains("PIN approvals are now locked")) {
                            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putLong("pinLockedUntil", System.currentTimeMillis() + 24 * 3_600_000L)
                                .putLong("pinLockNotedAt", System.currentTimeMillis())
                                .putInt("pinUnlocksSeen", Whitelist.state.pinUnlocks).apply()
                        }
                        if (o.optString("message") != answer.second || o.optBoolean("pinChecking")) {
                            // e.g. "Wrong PIN": it can be ticked again in approval mode.
                            o.put("message", answer.second).put("answered", System.currentTimeMillis()).put("seen", false).put("pinChecking", false)
                        }
                    }
                    write(ctx, items)
                }
                continue
            }
            val status: String
            val message: String
            when {
                answer != null -> { status = answer.first; message = answer.second }
                System.currentTimeMillis() - asked > GIVE_UP_MS -> { status = "closed"; message = "No answer after 60 days." }
                else -> continue // still waiting
            }
            synchronized(this) {
                val items = read(ctx)
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    if (o.optInt("number") == number) {
                        o.put("status", status).put("message", message)
                            .put("answered", System.currentTimeMillis()).put("seen", false)
                        Requests.forget(ctx, o.optJSONObject("request"))      // answered: it can be asked again
                    }
                }
                write(ctx, items)
            }
        }
    }
}
