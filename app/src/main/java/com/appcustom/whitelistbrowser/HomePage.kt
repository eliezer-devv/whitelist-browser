package com.appcustom.whitelistbrowser

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
            // In the app's look, light or dark, from the first moment it's drawn.
            "/home/", "/home/index.html" -> {
                val html = ctx.assets.open("home.html").bufferedReader().use { it.readText() }
                    .replaceFirst("<html lang=\"en\">", "<html lang=\"en\" data-theme=\"${if (Ui.dark) "dark" else "light"}\">")
                WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(html.toByteArray()))
            }
            // The app's fonts (packed in at build time), so the home page matches the rest of the app.
            "/home/fonts/Figtree.ttf", "/home/fonts/BricolageGrotesque.ttf" -> runCatching {
                WebResourceResponse("font/ttf", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400"),
                    ctx.assets.open("fonts/" + path.substringAfterLast('/')))
            }.getOrElse { WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(ByteArray(0))) }
            "/home/sites.json" -> WebResourceResponse(
                "application/json", "utf-8", 200, "OK", headers,
                ByteArrayInputStream(Whitelist.homeJson().toByteArray()))
            else -> WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(ByteArray(0)))
        }
    }
}
