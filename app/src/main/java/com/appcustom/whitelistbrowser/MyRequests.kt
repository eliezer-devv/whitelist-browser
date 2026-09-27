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
    private const val KEEP = 50                          // remember the latest 50
    private const val GIVE_UP_MS = 60L * 24 * 3_600_000  // stop checking after 60 days

    /** One request. [status]: waiting, approved, denied, closed, failed. */
    class Item(val number: Int, val summary: String, val asked: Long, val status: String,
               val message: String, val answered: Long, val seen: Boolean)

    @Synchronized private fun read(ctx: Context): JSONArray =
        runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]")) }
            .getOrDefault(JSONArray())

    @Synchronized private fun write(ctx: Context, items: JSONArray) {
        val trimmed = JSONArray()
        val from = maxOf(0, items.length() - KEEP)
        for (i in from until items.length()) trimmed.put(items.get(i))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", trimmed.toString()).apply()
    }

    private fun toItem(o: JSONObject) = Item(o.optInt("number"), o.optString("summary"), o.optLong("asked"),
        o.optString("status", "waiting"), o.optString("message"), o.optLong("answered"), o.optBoolean("seen", true))

    /** A request was sent (as GitHub issue [number]). */
    @Synchronized fun sent(ctx: Context, number: Int, summary: String, asked: Long) {
        val items = read(ctx)
        items.put(JSONObject().put("number", number).put("summary", summary).put("asked", asked).put("status", "waiting"))
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

    /** Answers the user hasn't seen yet; marks them seen. */
    @Synchronized fun takeNewAnswers(ctx: Context): List<Item> {
        val items = read(ctx)
        val fresh = ArrayList<Item>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (!o.optBoolean("seen", true)) { fresh += toItem(o); o.put("seen", true) }
        }
        if (fresh.isNotEmpty()) write(ctx, items)
        return fresh
    }

    /**
     * Asks GitHub about requests still waiting for an answer. Blocking; run off the main thread.
     * At most every 5 minutes. Stops at the first connection problem.
     */
    fun check(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("lastCheck", 0L) < 5 * 60_000L) return
        p.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        val waiting = synchronized(this) {
            val items = read(ctx)
            (0 until items.length()).map { items.getJSONObject(it) }
                .filter { it.optString("status") == "waiting" && it.optInt("number") > 0 }
                .map { it.optInt("number") to it.optLong("asked") }
        }
        for ((number, asked) in waiting) {
            val answer = runCatching { Requests.answerTo(number) }.getOrElse { return } // offline: try later
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
                    }
                }
                write(ctx, items)
            }
        }
    }
}
