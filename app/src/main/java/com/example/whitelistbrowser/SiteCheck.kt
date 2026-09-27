package com.example.whitelistbrowser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Checks that a typed website actually exists before asking for it: its name has to be found on
 * the internet (as typed, or with "www." in front). Can't check without internet.
 */
object SiteCheck {
    enum class Result { FOUND, NOT_FOUND, NO_INTERNET }

    /** Blocking; run off the main thread. */
    fun check(ctx: Context, domain: String): Result {
        if (!online(ctx)) return Result.NO_INTERNET
        for (name in listOf(domain, "www.$domain").distinct()) {
            try {
                if (InetAddress.getAllByName(name).isNotEmpty()) return Result.FOUND
            } catch (e: UnknownHostException) {
                // not this spelling; try the next
            } catch (e: Exception) {
                return Result.NO_INTERNET // something else went wrong: don't block the request on it
            }
        }
        return Result.NOT_FOUND
    }

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
