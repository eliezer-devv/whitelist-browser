package com.appcustom.whitelistbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * This phone's ID, like "K7M4-Q2XP".
 *
 * It's worked out from Android's ID for this app on this phone (ANDROID_ID), so it stays the same
 * when the app is uninstalled and reinstalled. Android ties that ID to the phone, the user profile
 * and the app's signing key, which never changes. It only changes after a factory reset, in another
 * user profile, or on another phone. The ID is a scrambled (hashed) form, so Android's own ID isn't shared.
 */
object Device {
    private const val PREFS = "device"
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // 32 letters: no 0/O or 1/I

    // Some very old phones all report this same ID, so it can't be used.
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

    @Volatile private var cached: String? = null

    fun id(ctx: Context): String {
        cached?.let { return it }
        val id = fromAndroidId(ctx) ?: run {
            // No usable Android ID (very rare): make a random one and keep it.
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("id", null) ?: random().also { p.edit().putString("id", it).apply() }
        }
        cached = id
        return id
    }

    @SuppressLint("HardwareIds")
    private fun fromAndroidId(ctx: Context): String? {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        if (androidId.isNullOrBlank() || androidId == BROKEN_ANDROID_ID) return null
        val hash = MessageDigest.getInstance("SHA-256").digest("whitelist-browser:$androidId".toByteArray())
        // 8 letters x 5 bits = the first 40 bits of the hash.
        var bits = 0L
        for (i in 0 until 5) bits = (bits shl 8) or (hash[i].toLong() and 0xFF)
        val chars = (7 downTo 0).map { ALPHABET[((bits shr (it * 5)) and 31).toInt()] }.joinToString("")
        return chars.substring(0, 4) + "-" + chars.substring(4)
    }

    private fun random(): String {
        val rnd = SecureRandom()
        val raw = (1..8).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
        return raw.substring(0, 4) + "-" + raw.substring(4)
    }

    fun model() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** The name the user typed on first launch (kept on the phone and sent when it registers). */
    fun name(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("name", null)

    fun setName(ctx: Context, name: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("name", name.trim().take(60)).apply()

    /** First and last name, as typed on first launch. [name] is the two together. */
    fun first(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("first", null)
    fun last(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)
    fun setNames(ctx: Context, first: String, last: String) {
        val f = first.trim().take(30)
        val l = last.trim().take(30)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("first", f).putString("last", l).putString("name", listOf(f, l).filter { it.isNotEmpty() }.joinToString(" ")).apply()
    }

    /** The date the app first ran on this phone (kept on the phone), e.g. "2026-09-23". */
    fun installedOn(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("installed", null)?.let { return it }
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        p.edit().putString("installed", today).apply()
        return today
    }
}
