package com.appcustom.whitelistbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Everything the phone sends to GitHub (its registration, and requests) goes through here.
 * Items are saved on the phone first, so they survive having no internet and app restarts, and
 * are sent in order as soon as there's a connection.
 *
 * [flush] must only be called from one thread at a time (MainActivity uses its single `updateIo`
 * thread for it), so an item can never be sent twice.
 */
object Outbox {
    private const val PREFS = "outbox"

    /** What happened in one [flush]: items sent, and items GitHub refused for good (with why). */
    class Result(val sent: Set<String>, val refused: Map<String, String>)

    /** The last reason something couldn't be sent (shown in About this phone). */
    @Volatile var lastProblem: String? = null

    @Synchronized private fun read(ctx: Context): JSONArray =
        runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]")) }
            .getOrDefault(JSONArray())

    @Synchronized private fun write(ctx: Context, items: JSONArray) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", items.toString()).apply()

    /** Saves an item to send. [kind]: "register" or "issue" ([summary] = how My requests shows it). Returns its id. */
    @Synchronized fun add(ctx: Context, kind: String, payload: JSONObject, summary: String? = null, request: JSONObject? = null): String {
        val id = UUID.randomUUID().toString()
        val items = read(ctx)
        if (kind == "log") {                                     // only the newest log waits
            for (i in items.length() - 1 downTo 0) if (items.getJSONObject(i).optString("kind") == "log") items.remove(i)
        }
        items.put(JSONObject().put("id", id).put("kind", kind).put("payload", payload)
            .put("summary", summary ?: "").put("created", System.currentTimeMillis())
            .apply { if (request != null) put("request", request) })   // what it asks for (kept on the phone)
        write(ctx, items)
        return id
    }

    @Synchronized fun count(ctx: Context): Int {                // (requests and registrations, not logs)
        val items = read(ctx)
        return (0 until items.length()).count { items.getJSONObject(it).optString("kind") != "log" }
    }

    /** Requests not sent yet: (summary, time asked), oldest first. */
    @Synchronized fun waitingRequests(ctx: Context): List<Pair<String, Long>> {
        val items = read(ctx)
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("kind") == "issue" }
            .map { it.optString("summary") to it.optLong("created") }
    }

    /** Requests not sent yet, with their outbox ids: (id, summary, time asked), oldest first. */
    @Synchronized fun waitingRequestsWithIds(ctx: Context): List<Triple<String, String, Long>> {
        val items = read(ctx)
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("kind") == "issue" }
            .map { Triple(it.optString("id"), it.optString("summary"), it.optLong("created")) }
    }

    /** "Don't send": takes a request that hasn't been sent yet out of the outbox. */
    /** Withdraws a request that hasn't been sent: it can be asked again straight away. */
    @Synchronized fun cancel(ctx: Context, id: String) {
        val items = read(ctx)
        for (i in 0 until items.length()) items.getJSONObject(i).let { if (it.optString("id") == id) Requests.forget(ctx, it.optJSONObject("request")) }
        remove(ctx, id)
    }

    @Synchronized fun has(ctx: Context, kind: String): Boolean {
        val items = read(ctx)
        return (0 until items.length()).any { items.getJSONObject(it).optString("kind") == kind }
    }

    @Synchronized private fun remove(ctx: Context, id: String) {
        val items = read(ctx)
        val kept = JSONArray()
        for (i in 0 until items.length()) items.getJSONObject(i).let { if (it.optString("id") != id) kept.put(it) }
        write(ctx, kept)
    }

    /**
     * Sends what's waiting, oldest first. Stops at the first connection problem (the rest waits for
     * next time). Items GitHub refuses for good (e.g. an expired key) are dropped. Blocking.
     */
    fun flush(ctx: Context): Result {
        val sent = HashSet<String>()
        val refused = HashMap<String, String>()
        if (!Requests.isSetUp()) return Result(sent, refused)
        while (true) {
            val item = synchronized(this) { read(ctx).optJSONObject(0) } ?: break
            val id = item.optString("id")
            try {
                val payload = item.getJSONObject("payload")
                if (item.optString("kind") == "register") Requests.deliverRegistration(ctx, payload)
                else if (item.optString("kind") == "log") { Requests.deliverIssue(payload); AppLog.i("Log", "Sent to the admin") }  // not one of My requests
                else MyRequests.sent(ctx, Requests.deliverIssue(payload), item.optString("summary"), item.optLong("created"),
                    item.optJSONObject("request"))
                remove(ctx, id)
                sent += id
                lastProblem = null
            } catch (e: Requests.Refused) {
                remove(ctx, id)             // will never work as it is: don't block the rest
                refused[id] = e.message ?: "refused"
                if (item.optString("kind") == "issue") {
                    MyRequests.failed(ctx, item.optString("summary"), item.optLong("created"), e.message ?: "refused")
                }
                lastProblem = e.message
                AppLog.w("Requests", "Refused by GitHub: ${e.message}")
            } catch (e: Exception) {
                AppLog.w("Requests", "Not sent yet (${e.javaClass.simpleName}: ${e.message}); will try again")
                lastProblem = if (e is java.io.IOException && e.message?.contains("busy") == true) e.message
                    else "No connection"
                break                        // no internet or GitHub busy: try again later
            }
        }
        return Result(sent, refused)
    }
}
