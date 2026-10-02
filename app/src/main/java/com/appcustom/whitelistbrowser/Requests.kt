package com.appcustom.whitelistbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends "please allow / please block this site" requests to the owner as GitHub issues.
 * A GitHub workflow in the repo notifies the owner and applies the change when they reply "approve".
 */
object Requests {
    /** GitHub refused this for good (e.g. expired key): retrying won't help. */
    class Refused(message: String) : IOException(message)

    enum class Action(val word: String) { ALLOW("allow"), BLOCK("block") }
    enum class Scope(val word: String) { PAGE("page"), SITE("site") }
    /** OFF = open without / block only photos and videos; ON = ask for them back; UNCHANGED = normal request. */
    enum class Media(val word: String) { OFF("off"), ON("on"), UNCHANGED("") }

    // Stored reversed at build time so the key isn't sitting in the app as plain text.
    private fun token(): String = BuildConfig.REQUESTS_TOKEN_REV.reversed()

    fun isSetUp() = token().isNotBlank() && Config.GITHUB_USERNAME != "YOUR_USERNAME"

    private const val PREFS = "requests"
    private const val REPEAT_WAIT_MS = 30 * 60 * 1000L

    /** True if the same request was already sent in the last 30 minutes. */
    fun recentlySent(ctx: Context, action: Action, subject: String): Boolean {
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("${action.word}|$subject", 0L)
        return System.currentTimeMillis() - last < REPEAT_WAIT_MS
    }

    /**
     * Saves a request in the outbox (it's sent by [Outbox.flush], now or when there's internet).
     * Returns the outbox id. [scope] PAGE = just [pageUrl]; SITE = all of [domain].
     */
    /** "photos and videos", "photos" or "videos". */
    /** "photos", "photos and sound", "photos, videos and sound" (kinds: "photos,sound"; "both" or "": all). */
    fun mediaWords(kinds: String): String {
        val k = kindList(kinds)
        return if (k.size <= 1) k.firstOrNull() ?: "photos, videos and sound" else k.dropLast(1).joinToString(", ") + " and " + k.last()
    }

    /** Photos, videos and/or sound, in that order ("both" or nothing: all three). */
    fun kindList(kinds: String): List<String> {
        val all = listOf("photos", "videos", "sound")
        val k = kinds.split(',').map { it.trim() }.filter { it in all }
        return if (k.isEmpty()) all else all.filter { it in k }
    }

    /** An address a link passes through: just that page or its whole site, and a home page tile or not. */
    data class Hop(val url: String, var scope: String = "page", var tile: Boolean = false)

    /** What "already asked about this" remembers a request by. */
    fun sentKey(subject: String, media: Media, kind: String) =
        "$subject|${media.word}" + if (media != Media.UNCHANGED && kind != "both") ":$kind" else ""

    fun queue(ctx: Context, action: Action, scope: Scope, media: Media, domain: String, pageUrl: String?, note: String,
              hops: List<Hop> = emptyList(), minutes: Int = 0, unverified: Boolean = false,
              filtered: List<String> = emptyList(), pin: String? = null, frames: List<String> = emptyList(),
              mediaKind: String = "both", tile: Boolean = true, timeMode: String? = null, item: String? = null): String {
        if (!isSetUp()) throw IOException("Requests aren't set up for this app yet")

        val page = if (scope == Scope.PAGE) pageUrl?.let { Whitelist.pageKey(it) } else null
        val subject = page ?: domain
        val what = if (page != null) "just this page" else "the whole site"
        val kind = if (page != null) "page" else "site"
        val mw = mediaWords(mediaKind)
        val headline = when {
            media == Media.ON -> "Turn $mw back on for $what"
            action == Action.ALLOW && media == Media.OFF -> "Open $what, without $mw"
            action == Action.BLOCK && media == Media.OFF -> "Block only the $mw on $what"
            action == Action.ALLOW -> "Open $what"
            else -> "Block $what"
        }.let { if (frames.isNotEmpty()) "Embedded content on $domain, from ${frames.joinToString(", ")}" else it } +
            if (minutes > 0) ", for ${duration(minutes)}" else ""
        val title = if (frames.isNotEmpty()) "Embedded content on $domain" else when {
            media == Media.ON -> "${mw.replaceFirstChar { it.uppercase() }} back on: $subject"
            action == Action.ALLOW && media == Media.OFF -> "Open $kind without $mw: $subject"
            action == Action.BLOCK && media == Media.OFF -> "Block $mw: $subject"
            action == Action.ALLOW -> "Open $kind: $subject"
            else -> "Block $kind: $subject"
        }
        val marker = JSONObject().put("action", action.word).put("scope", if (page != null) "page" else "site")
            .put("domain", domain).put("device", Device.id(ctx)).put("model", Device.model())
            .apply { Device.name(ctx)?.let { put("name", it) } }
            .apply { Device.first(ctx)?.let { put("first", it) }; Device.last(ctx)?.let { put("last", it) } }
            .apply { if (media != Media.UNCHANGED) put("media", media.word) }
            .apply { if (media != Media.UNCHANGED && kindList(mediaKind).size < 3) put("mediaKind", kindList(mediaKind).joinToString(",")) } // not all of them
            .apply { if (minutes > 0) put("minutes", minutes) }
            .apply { if (unverified) put("unverified", true) } // the phone couldn't find this site
            .apply { if (filtered.isNotEmpty()) put("filtered", JSONArray(filtered)) } // on a content filter's list
            .apply { if (pin != null) put("pin", pin) } // approval PIN: checked by GitHub, then removed from the request
            .apply {
                if (hops.isNotEmpty()) put("hops", JSONArray(hops.take(10).map {
                    JSONObject().put("url", it.url).put("scope", it.scope).put("tile", it.tile)
                }))
            }
            .apply { if (!tile) put("tile", false) }              // no home page tile, please
            .apply { if (timeMode != null) put("timeMode", timeMode) } // "use": count time on the site only
            .apply { if (item != null) put("item", item) }          // one photo or video
            .apply { if (frames.isNotEmpty()) put("frames", JSONArray(frames.take(10))) } // blocked embedded content on this site
            .apply { if (!pageUrl.isNullOrBlank()) put("url", pageUrl) }
            .apply { if (note.isNotBlank()) put("note", note.take(500)) }
        // Sealed (the app has the request key): nothing readable on GitHub, just that there's a request.
        if (Seal.canSeal) {
            val sealedPayload = JSONObject()
                .put("title", "Request from a phone")
                .put("body", "🔒 A request from a phone. Answer it on the admin page.\n\n${Seal.hiddenPart(marker)}")
                .put("labels", JSONArray().put("site request"))
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("${action.word}|${sentKey(subject, media, mediaKind)}", System.currentTimeMillis()).apply()
            return Outbox.add(ctx, "issue", sealedPayload,
                summary = if (frames.isNotEmpty()) "Embedded content on $domain (from ${frames.joinToString(", ")})" else "$headline: $subject",
                request = JSONObject(marker.toString()).apply { remove("pin") })
        }
        val body = buildString {
            appendLine("**$headline:** `$subject`")
            if (!pageUrl.isNullOrBlank()) appendLine("**Page:** $pageUrl")
            if (hops.isNotEmpty()) {
                appendLine("**Passes through on the way:**")
                hops.take(10).forEachIndexed { i, h ->
                    appendLine("${i + 1}. ${h.url} (${if (h.scope == "site") "whole site" else "this page"}${if (h.tile) ", with a tile" else ""})")
                }
            }
            if (note.isNotBlank()) appendLine("**Note:** ${note.replace("-->", "")}")
            if (unverified) appendLine("**⚠️ The phone couldn't find this site when asking.** It may be a typo.")
            if (filtered.isNotEmpty()) appendLine("**⚠️ On the ${filtered.joinToString(" and ") { if (it == "adult") "adult content" else it }} list.**")
            val name = Whitelist.state.deviceName ?: Device.name(ctx)
            appendLine("**Phone:** ${name ?: Device.model()} (`${Device.id(ctx)}`)")
            appendLine("**Asked:** ${utcNow().replace('T', ' ').removeSuffix("Z")} UTC")
            appendLine()
            appendLine("<!-- whitelist-request")
            appendLine(marker.toString())
            append("-->")
        }
        val payload = JSONObject()
            .put("title", title)
            .put("body", body)
            .put("labels", JSONArray().put("site request"))

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("${action.word}|${sentKey(subject, media, mediaKind)}", System.currentTimeMillis()).apply()
        return Outbox.add(ctx, "issue", payload,
            summary = if (frames.isNotEmpty()) "Embedded content on $domain (from ${frames.joinToString(", ")})" else "$headline: $subject",
            request = JSONObject(marker.toString()).apply { remove("pin") })
    }

    /** Sends a saved request and returns its issue number. Called by [Outbox.flush]. */
    fun deliverIssue(payload: JSONObject): Int = createIssue(payload)

    /**
     * The answer to request [number]: (status, message) once it's been answered, null while it's
     * still waiting. Blocking. Throws on connection problems.
     */
    fun answerTo(number: Int): Pair<String, String>? {
        val issue = get("issues/$number") ?: return Pair("closed", "This request was removed.")
        if (JSONObject(issue).optString("state") != "closed") {
            // Still waiting: a notice for the phone, if there is one (e.g. "Wrong PIN, so it was sent for approval").
            val open = JSONArray(get("issues/$number/comments?per_page=100") ?: "[]")
            for (i in open.length() - 1 downTo 0) {
                val o = hidden(open.getJSONObject(i).optString("body"), NOTICE_SEALED, NOTICE) ?: continue
                return Pair("notice", o.optString("message"))
            }
            return null
        }
        // The owner's final reply carries the answer for the phone in a hidden note.
        val comments = JSONArray(get("issues/$number/comments?per_page=100") ?: "[]")
        for (i in comments.length() - 1 downTo 0) {
            val body = comments.getJSONObject(i).optString("body")
            val o = hidden(body, RESPONSE_SEALED, RESPONSE) ?: continue
            val outcome = o.optString("outcome")
            return Pair(if (outcome == "approved" || outcome == "denied") outcome else "closed", o.optString("message"))
        }
        // Closed on GitHub without a reply the phone can read.
        return if (JSONObject(issue).optString("state_reason") == "not_planned") Pair("denied", "Not approved.")
            else Pair("closed", "Closed without an answer.")
    }

    private val NOTICE = Regex("<!-- whitelist-notice\\s*([\\s\\S]*?)-->")
    private val RESPONSE = Regex("<!-- whitelist-response\\s*([\\s\\S]*?)-->")
    private val NOTICE_SEALED = Regex("<!-- whitelist-notice-sealed\\s*([\\s\\S]*?)-->")
    private val RESPONSE_SEALED = Regex("<!-- whitelist-response-sealed\\s*([\\s\\S]*?)-->")

    /** The answer (or notice) in a comment: sealed for this phone, or plain (from before). Null if none. */
    private fun hidden(body: String, sealed: Regex, plain: Regex): JSONObject? {
        sealed.find(body)?.let { m -> return runCatching { Seal.open(JSONObject(m.groupValues[1].trim())) }.getOrNull() }
        return plain.find(body)?.let { m -> runCatching { JSONObject(m.groupValues[1]) }.getOrNull() }
    }

    /** GET from the repo's API. Null if it doesn't exist (404/410). Throws on other problems. */
    private fun get(path: String): String? {
        val conn = URL("https://api.github.com/repos/${PrivateRepo.FULL}/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer ${token()}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "WhitelistBrowser/${BuildConfig.VERSION_NAME}")
            return when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404, 410 -> null
                else -> throw IOException("GitHub returned ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 90 -> "1 hour 30 minutes". */
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return listOfNotNull(
            if (h > 0) "$h hour${if (h == 1) "" else "s"}" else null,
            if (m > 0) "$m minute${if (m == 1) "" else "s"}" else null
        ).joinToString(" ")
    }

    private fun utcNow(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    private fun statusBody(ctx: Context, register: Boolean): String {
        val id = Device.id(ctx)
        val now = utcNow()
        val marker = JSONObject().put("type", "register").put("id", id).put("model", Device.model())
            .apply { runCatching { Seal.publicKey() }.getOrNull()?.let { put("key", it) } }   // its lists are sealed with this
            .apply { Device.name(ctx)?.let { put("name", it) } }
            .apply { Device.first(ctx)?.let { put("first", it) }; Device.last(ctx)?.let { put("last", it) } }
            .put("lastSeen", now).put("installed", Device.installedOn(ctx))
            .put("version", BuildConfig.VERSION_NAME)
        if (Seal.canSeal) return (if (register) "🔒 A phone registered." else "🔒 A phone's record.") + "\n\n" + Seal.hiddenPart(marker)
        return (if (register) "A phone installed Whitelist Browser." else "Phone check-in record.") +
            "\n\n**Name they entered:** ${Device.name(ctx) ?: "(none)"}\n**Model:** ${Device.model()}\n**ID:** `$id`\n**Last seen:** ${now.replace('T', ' ').removeSuffix("Z")} UTC " +
            "(app ${BuildConfig.VERSION_NAME})\n\n<!-- whitelist-request\n$marker\n-->"
    }

    /**
     * Prepares this phone's registration the first time it runs (and after it was archived), even
     * with no internet: it waits in the outbox and is sent as soon as there's a connection.
     * Not repeated for a day after one was prepared, until the phone shows up in phones.json.
     */
    fun registerIfNeeded(ctx: Context) {
        if (!isSetUp() || Whitelist.state.registered || Outbox.has(ctx, "register")) return
        if (Device.name(ctx) == null) return // registers once they've entered their name (asked on first launch)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // (Its sealed lists didn't open with its key: send the key again now, not after the wait.)
        if (System.currentTimeMillis() < p.getLong("registerNext", 0L) && !Whitelist.keyMismatch) return
        // Not set up yet means it can't open anything, so try again after half an hour (not a day).
        p.edit().putLong("registerNext", System.currentTimeMillis() + 30 * 60_000L).apply()
        Outbox.add(ctx, "register", JSONObject())
    }

    /** Sends the registration (built now, so "last seen" is when it's actually sent). Called by [Outbox.flush]. */
    fun deliverRegistration(ctx: Context, @Suppress("UNUSED_PARAMETER") payload: JSONObject) {
        val number = createIssue(JSONObject()
            .put("title", if (Seal.canSeal) "New phone" else "New phone: ${Device.name(ctx) ?: Device.model()} (${Device.id(ctx)})")
            .put("body", statusBody(ctx, register = true))
            .put("labels", JSONArray().put("new phone")))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("statusIssue", number).putLong("lastCheckIn", System.currentTimeMillis()).apply()
    }

    /**
     * "I'm still here": updates this phone's record (its "New phone" issue) with the time it was
     * last used, at most every 12 hours. A daily GitHub job archives phones that stop checking in.
     * Editing an issue sends no notifications.
     */
    fun checkIn(ctx: Context) {
        if (!isSetUp() || !Whitelist.state.registered) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("lastCheckIn", 0L) < 12 * 3_600_000L) return
        var number = p.getInt("statusIssue", 0)
        if (number == 0) {
            // No record on this install yet (the app was reinstalled, or the phone was added by hand): make one.
            // The workflow files it away quietly because the phone is already known.
            number = createIssue(JSONObject()
                .put("title", if (Seal.canSeal) "Phone record" else "Phone record: ${Device.model()} (${Device.id(ctx)})")
                .put("body", statusBody(ctx, register = false))
                .put("labels", JSONArray().put("new phone")))
        } else {
            val code = call("PATCH", "issues/$number", JSONObject().put("body", statusBody(ctx, register = false)))
            if (code == 404 || code == 410) { p.edit().remove("statusIssue").apply(); return } // record deleted
            if (code !in 200..299) return
        }
        p.edit().putInt("statusIssue", number).putLong("lastCheckIn", System.currentTimeMillis()).apply()
    }

    /** Creates an issue and returns its number. */
    private fun createIssue(payload: JSONObject): Int {
        val conn = open("POST", "issues")
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 201) return JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optInt("number")
            // GitHub limits how fast one account can create issues; lots of phones at once can reach it.
            val busy = code == 429 || conn.getHeaderField("Retry-After") != null ||
                conn.getHeaderField("x-ratelimit-remaining") == "0" ||
                (conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "").contains("rate limit", ignoreCase = true)
            if (busy) throw IOException("GitHub is busy. Try again in a few minutes.")
            when (code) {
                401 -> throw Refused("The request key has expired. Ask the owner to renew it.")
                403, 404 -> throw Refused("The request key doesn't have permission. Ask the owner to check it.")
                410 -> throw Refused("Issues are turned off in the GitHub repository.")
                422 -> throw Refused("GitHub didn't accept the request.")
                else -> throw IOException("GitHub returned $code") // e.g. a server problem: try again later
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Withdraws a request that's still waiting (GitHub issue [number]): closes it, then notes it was
     * cancelled on the phone. Closed first, so GitHub's automation never reads the note as a reply.
     * Blocking. True if it worked.
     */
    fun cancel(number: Int): Boolean {
        if (number <= 0) return false
        val closed = call("PATCH", "issues/$number", JSONObject().put("state", "closed").put("state_reason", "not_planned"))
        if (closed !in 200..299) return false
        runCatching { call("POST", "issues/$number/comments", JSONObject().put("body", "🚫 Cancelled on the phone.")) }
        return true
    }

    /**
     * Approves or denies a waiting request (GitHub issue [number]) with the approval PIN ("My requests",
     * approval mode). The PIN goes in a hidden note, which GitHub deletes at once, then checks the PIN.
     * Blocking. True if the note was sent.
     */
    fun answerWithPin(number: Int, pin: String, approve: Boolean): Boolean {
        if (number <= 0) return false
        val note = JSONObject().put("pin", pin).put("action", if (approve) "approve" else "deny")
        val hidden = if (Seal.canSeal) "<!-- whitelist-pin-sealed ${Seal.seal(note)} -->" else "<!-- whitelist-pin $note -->"
        return call("POST", "issues/$number/comments", JSONObject().put("body", "🔑 $hidden")) in 200..299
    }

    private fun call(method: String, path: String, payload: JSONObject): Int {
        val conn = open(method, path)
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val conn = URL("https://api.github.com/repos/${PrivateRepo.FULL}/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
        } catch (e: java.net.ProtocolException) {
            // Very old Android versions refuse PATCH; ask GitHub to treat the POST as PATCH.
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", method)
        }
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${token()}")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "WhitelistBrowser/${BuildConfig.VERSION_NAME}")
        return conn
    }
}
