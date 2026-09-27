package com.appcustom.whitelistbrowser

import android.content.Context

/**
 * Time actually spent on each "time on the site" temporary grant, kept on this phone.
 * MainActivity adds time while a covered page is open on screen.
 */
object TempTime {
    private const val PREFS = "temp_time"
    @Volatile private var used: Map<String, Long> = emptyMap()
    private var appContext: Context? = null

    fun load(ctx: Context) {
        appContext = ctx.applicationContext
        val all = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        used = all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
    }

    fun used(id: String): Long = used[id] ?: 0L

    @Synchronized fun add(ids: Collection<String>, ms: Long) {
        if (ids.isEmpty()) return
        val next = used.toMutableMap()
        ids.forEach { next[it] = (next[it] ?: 0L) + ms }
        used = next
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply {
            ids.forEach { putLong(it, next[it] ?: 0L) }
            apply()
        }
    }
}
