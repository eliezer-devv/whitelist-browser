package com.example.whitelistbrowser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * The built-in home page. It's served by the app itself at a reserved address that never
 * reaches the internet (appassets.androidplatform.net is set aside by Android for this).
 */
object HomePage {
    const val HOST = "appassets.androidplatform.net"
    const val URL = "https://$HOST/home/"

    fun isHome(url: String?) = url != null && url.startsWith(URL)

    fun respond(ctx: Context, path: String): WebResourceResponse {
        val headers = mapOf("Cache-Control" to "no-store")
        return when (path) {
            "/home/", "/home/index.html" -> WebResourceResponse(
                "text/html", "utf-8", 200, "OK", headers, ctx.assets.open("home.html"))
            "/home/sites.json" -> WebResourceResponse(
                "application/json", "utf-8", 200, "OK", headers,
                ByteArrayInputStream(Whitelist.homeJson().toByteArray()))
            else -> WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(ByteArray(0)))
        }
    }
}
