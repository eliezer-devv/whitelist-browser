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

    fun isSetUp() = token().isNotBlank() && Config.GITHUB_USERNAME != "YOUR_USERNAME" && Seal.canSeal   // sealed, always

    private const val PREFS = "requests"
    private const val REPEAT_WAIT_MS = 30 * 60 * 1000L

    /** True if the same request was already sent in the last 30 minutes. */
    /**
     * Sends the app's log to whoever manages this browser ([why]: "sent from the phone", "asked for", "after a crash"):
     * compressed, and sealed like a request (only GitHub's automation can read it). Queued, so it goes once online;
     * only the newest waits. Trimmed to its most recent part if it's too big for one message.
     */
    /** A message to whoever manages this browser: [subject], [message], and the app's log if [log] isn't null. */
    fun queueMessage(ctx: Context, subject: String, message: String, log: String?) {
        if (!isSetUp()) return
        val text = "Subject: $subject\n\n$message" + (log?.let { "\n\n───── The app's log ─────\n\n$it" } ?: "")
        queueLog(ctx, "message", text, extra = JSONObject().put("subject", subject))
    }

    fun queueLog(ctx: Context, why: String, text: String, extra: JSONObject? = null) {
        if (!isSetUp()) return
        var keep = text.length
        while (true) {
            val part = if (keep >= text.length) text else "(earlier entries left out)\n" + text.takeLast(keep)
            val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(part.toByteArray(Charsets.UTF_8)) } }.toByteArray()
            val marker = (extra?.let { JSONObject(it.toString()) } ?: JSONObject()).put("type", "log").put("device", Device.id(ctx)).put("why", why)
                .put("version", BuildConfig.VERSION_NAME).put("gz", android.util.Base64.encodeToString(gz, android.util.Base64.NO_WRAP))
            val body = "🔒 A log from a phone.\n\n${Seal.hiddenPart(marker)}"
            if (body.length < 60_000 || keep < 5_000) {                  // GitHub's limit for one message is 65,536
                // (A message is its own kind: only logs replace each other while waiting.)
                Outbox.add(ctx, if (why == "message") "message" else "log",
                    JSONObject().put("title", if (why == "message") "Message from a phone" else "Log from a phone").put("body", body), summary = "Log ($why)")
                AppLog.i("Log", "Queued for the admin ($why, ${gz.size / 1024} KB compressed)")
                return
            }
            keep /= 2
        }
    }

    /** Forgets that something was asked (it was cancelled, or answered): it can be asked again straight away. */
    fun forget(ctx: Context, request: JSONObject?) {
        val key = request?.optString("sentKey")?.takeIf { it.isNotEmpty() } ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    fun recentlySent(ctx: Context, action: Action, subject: String): Boolean = alreadyAsked(ctx, "${action.word}|$subject")

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

    /** The whole key a request is remembered by (embedded parts: which ones, so other parts can still be asked). */
    fun fullKey(action: Action, subject: String, media: Media, kind: String, frames: List<String> = emptyList()) =
        "${action.word}|${sentKey(subject, media, kind)}" + if (frames.isNotEmpty()) "|frames:" + frames.sorted().joinToString(",") else ""

    /**
     * Already asked: in the last 30 minutes, or still waiting for an answer (however long ago). Cancelling it or an
     * answer arriving lets it be asked again.
     */
    fun alreadyAsked(ctx: Context, key: String): Boolean {
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        if (last == 0L) return false                                   // (forgotten: cancelled or answered)
        if (System.currentTimeMillis() - last < REPEAT_WAIT_MS) return true
        return MyRequests.all(ctx).any { !it.archived && it.status == "waiting" && it.request?.optString("sentKey") == key }
    }

    fun queue(ctx: Context, action: Action, scope: Scope, media: Media, domain: String, pageUrl: String?, note: String,
              hops: List<Hop> = emptyList(), minutes: Int = 0, unverified: Boolean = false,
              filtered: List<String> = emptyList(), frames: List<String> = emptyList(),
              mediaKind: String = "both", tile: Boolean = true, timeMode: String? = null, item: String? = null,
              mediaOpen: String = ""): String {
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
        val marker = JSONObject().put("action", action.word).put("scope", if (page != null) "page" else "site")
            .put("domain", domain).put("device", Device.id(ctx)).put("model", Device.model())
            .apply { Device.name(ctx)?.let { put("name", it) } }
            .apply { Device.first(ctx)?.let { put("first", it) }; Device.last(ctx)?.let { put("last", it) } }
            .apply { if (media != Media.UNCHANGED) put("media", media.word) }
            .apply { if (media != Media.UNCHANGED && kindList(mediaKind).size < 3) put("mediaKind", kindList(mediaKind).joinToString(",")) } // not all of them
            .apply { if (action == Action.ALLOW && media != Media.ON && mediaOpen.isNotBlank()) put("mediaOpen", mediaOpen) } // asked for Open on it
            .apply { if (minutes > 0) put("minutes", minutes) }
            .apply { if (unverified) put("unverified", true) } // the phone couldn't find this site
            .apply { if (filtered.isNotEmpty()) put("filtered", JSONArray(filtered)) } // on a content filter's list
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
        // Sealed: nothing readable on GitHub, just that there's a request (only GitHub's automation can read it).
        val payload = JSONObject()
            .put("title", "Request from a phone")
            .put("body", "🔒 A request from a phone. Answer it on the admin page.\n\n${Seal.hiddenPart(marker)}")
            .put("labels", JSONArray().put("site request"))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(fullKey(action, subject, media, mediaKind, frames), System.currentTimeMillis()).apply()
        return Outbox.add(ctx, "issue", payload,
            summary = if (frames.isNotEmpty()) "Embedded content on $domain (from ${frames.joinToString(", ")})" else "$headline: $subject",
            request = JSONObject(marker.toString()).put("sentKey", fullKey(action, subject, media, mediaKind, frames)))   // (kept on the phone)
    }

    /**
     * Asking about this phone itself (not a site): [type] "mediaDefault" (its default for photos, videos and sound:
     * [extra] has "want") or "search" (text search on: [extra] has "approvedOnly"). Answered on the admin page by
     * someone who manages this phone, never with a PIN. Shows in My requests like any other.
     */
    fun queuePhone(ctx: Context, type: String, extra: JSONObject, note: String, summary: String): String {
        if (!isSetUp()) throw IOException("Requests aren't set up for this app yet")
        val marker = JSONObject(extra.toString()).put("type", type).put("device", Device.id(ctx)).put("model", Device.model())
            .apply { Device.name(ctx)?.let { put("name", it) } }
            .apply { Device.first(ctx)?.let { put("first", it) }; Device.last(ctx)?.let { put("last", it) } }
            .apply { if (note.isNotBlank()) put("note", note.take(500)) }
        val payload = JSONObject()
            .put("title", "Request from a phone")
            .put("body", "🔒 A request from a phone. Answer it on the admin page.\n\n${Seal.hiddenPart(marker)}")
            .put("labels", JSONArray().put("site request"))
        val key = "phone|$type"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
        return Outbox.add(ctx, "issue", payload, summary = summary, request = JSONObject(marker.toString()).put("sentKey", key))
    }

    /** Sends a saved request and returns its issue number. Called by [Outbox.flush]. */
    fun deliverIssue(payload: JSONObject): Int = createIssue(payload)

    /**
     * The answer to request [number]: (status, message) once it's been answered, null while it's
     * still waiting. Blocking. Throws on connection problems.
     */
    /** A GitHub time ("2026-10-03T12:34:56Z") in milliseconds (0 if it can't be read). */
    private fun githubTime(t: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(t)!!.time
    }.getOrDefault(0L)

    /** The answer to request [number] (kind, message, when it was posted), or null while it's still waiting. */
    fun answerTo(number: Int): Triple<String, String, Long>? = answerPair(number)

    private fun answerPair(number: Int): Triple<String, String, Long>? {
        val issue = get("issues/$number") ?: return Triple("closed", "This request was removed.", 0L)
        if (JSONObject(issue).optString("state") != "closed") {
            // Still waiting: a notice for the phone, if there is one (e.g. "Wrong PIN, so it was sent for approval").
            val open = JSONArray(get("issues/$number/comments?per_page=100") ?: "[]")
            for (i in open.length() - 1 downTo 0) {
                val c = open.getJSONObject(i)
                val o = hidden(c.optString("body"), NOTICE_SEALED) ?: continue
                return Triple("notice", o.optString("message"), githubTime(c.optString("created_at")))
            }
            return null
        }
        // The owner's final reply carries the answer for the phone in a hidden note.
        val comments = JSONArray(get("issues/$number/comments?per_page=100") ?: "[]")
        for (i in comments.length() - 1 downTo 0) {
            val body = comments.getJSONObject(i).optString("body")
            val o = hidden(body, RESPONSE_SEALED) ?: continue
            val outcome = o.optString("outcome")
            return Triple(if (outcome == "approved" || outcome == "denied") outcome else "closed", o.optString("message"), 0L)
        }
        // Closed on GitHub without a reply the phone can read.
        return if (JSONObject(issue).optString("state_reason") == "not_planned") Triple("denied", "Not approved.", 0L)
            else Triple("closed", "Closed without an answer.", 0L)
    }

    private val NOTICE_SEALED = Regex("<!-- whitelist-notice-sealed\\s*([\\s\\S]*?)-->")
    private val RESPONSE_SEALED = Regex("<!-- whitelist-response-sealed\\s*([\\s\\S]*?)-->")

    /** The answer (or notice) in a comment, sealed for this phone. Null if none (or not for this phone). */
    private fun hidden(body: String, sealed: Regex): JSONObject? =
        sealed.find(body)?.let { m -> runCatching { Seal.open(JSONObject(m.groupValues[1].trim())) }.getOrNull() }

    /** GET from the repo's API. Null if it doesn't exist (404/410). Throws on other problems. */
    /** Reads [path] from the private repository's API (null if it isn't there). Blocking. */
    fun read(path: String): String? = get(path)

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
        return (if (register) "🔒 A phone registered." else "🔒 A phone's record.") + "\n\n" + Seal.hiddenPart(marker)
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
        val now = System.currentTimeMillis()
        // Send at once, without waiting, if this phone hasn't sent its current key yet (e.g. just updated, or
        // reinstalled), or its sealed lists didn't open with it. A wait left by an older version (a day) is ignored.
        val keyNow = runCatching { Seal.publicKey() }.getOrNull()
        val keyNotSent = keyNow != null && p.getString("keySent", null) != keyNow
        val waitUntil = p.getLong("registerNext", 0L).coerceAtMost(now + 30 * 60_000L)
        if (now < waitUntil && !Whitelist.keyMismatch && !keyNotSent) return
        // Not set up yet means it can't open anything, so try again after half an hour (not a day).
        p.edit().putLong("registerNext", now + 30 * 60_000L).apply()
        AppLog.i("Setup", "Registering this phone (${Device.name(ctx)}): sent as soon as it's online")
        Outbox.add(ctx, "register", JSONObject())
    }

    /** Sends the registration (built now, so "last seen" is when it's actually sent). Called by [Outbox.flush]. */
    fun deliverRegistration(ctx: Context, @Suppress("UNUSED_PARAMETER") payload: JSONObject) {
        val number = createIssue(JSONObject()
            .put("title", "New phone")
            .put("body", statusBody(ctx, register = true))
            .put("labels", JSONArray().put("new phone")))
        val keyJustSent = runCatching { Seal.publicKey() }.getOrNull()       // this key has gone (if there is one)
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("statusIssue", number).putLong("lastCheckIn", System.currentTimeMillis())
        if (keyJustSent != null) e.putString("keySent", keyJustSent)
        e.apply()
    }

    /** Where setting up has got to (for About this phone): set up, waiting, or what's wrong. */
    fun setupStatus(ctx: Context): String {
        if (!isSetUp()) return "Requests aren't set up in this app"
        if (Whitelist.state.registered) return "Set up"
        val key = runCatching { Seal.publicKey() }
        if (key.isFailure) return "Can't make this phone's key: ${key.exceptionOrNull()?.message ?: "unknown problem"}"
        if (Whitelist.keyMismatch) return "Its lists are locked with an old key: sending the new one"
        if (Device.name(ctx) == null) return "Waiting for a name"
        if (Outbox.has(ctx, "register")) return "Waiting to send its key (needs internet)" + (Outbox.lastProblem?.let { ": $it" } ?: "")
        val sent = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("keySent", null) == key.getOrNull()
        return if (sent) "Key sent: waiting for its lists (a minute or two)" else "About to send its key"
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
                .put("title", "Phone record")
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
    fun answerWithPin(numbers: List<Int>, pin: String, approve: Boolean): Boolean {
        val all = numbers.filter { it > 0 }.distinct()
        val number = all.firstOrNull() ?: return false
        // One note for all of them: GitHub checks the PIN once, then answers each.
        val note = JSONObject().put("pin", pin).put("action", if (approve) "approve" else "deny")
            .put("numbers", org.json.JSONArray(all))
        val hidden = "<!-- whitelist-pin-sealed ${Seal.seal(note)} -->"
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
