package com.appcustom.whitelistbrowser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/**
 * Text search (when it's on for this phone): a results page made by the app itself, at the app's own address (like
 * the home page), so nothing from the search engine's own page ever shows. Words only: no pictures, videos, sound or
 * ads. Each result is a page (several from one site is fine), with its site's little icon, where it is, its title and a
 * snippet, and whether it opens on this phone. Results on a content filter's list, or on this phone's always-blocked
 * list, are simply left out. Opening one goes through the phone's lists like any link.
 */
object SearchPage {
    const val URL = "https://${HomePage.HOST}/search/"

    fun isSearch(url: String?) = url != null && url.startsWith(URL)
    fun urlFor(q: String) = URL + "?q=" + URLEncoder.encode(q.trim(), "UTF-8")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** "bbc.co.uk › bitesize › guides" (the address, readably: at most three steps of its path). */
    fun breadcrumb(url: String): String {
        val u = Uri.parse(url)
        val host = (u.host ?: "").lowercase().removePrefix("www.")
        val steps = u.pathSegments.orEmpty().filter { it.isNotBlank() }.take(3).map { it.take(40) }
        return (listOf(host) + steps).joinToString(" › ")
    }

    /** Called on the WebView's own thread for loading (not the main thread): searching here is fine. */
    fun respond(ctx: Context, uri: Uri): WebResourceResponse {
        val q = uri.getQueryParameter("q").orEmpty().trim().take(200)
        val pages = (uri.getQueryParameter("p")?.toIntOrNull() ?: 0).coerceIn(0, 9)
        val s = Whitelist.state
        val body = StringBuilder()
        body.append("<form class=\"search\" action=\"/search/\" method=\"get\" role=\"search\">")
            .append(ICON_SEARCH)
            .append("<input type=\"search\" name=\"q\" value=\"${esc(q)}\" placeholder=\"Search\" aria-label=\"Search\" autocomplete=\"off\" enterkeyhint=\"search\"")
            .append(if (q.isEmpty()) " autofocus" else "").append("></form>")
        when {
            !s.search -> body.append("<p class=\"note\">Search isn't turned on for this phone. You can ask for it: ⋮ → Ask for search.</p>")
            q.isEmpty() -> body.append("<p class=\"note\">Type what you're looking for.</p>")
            else -> try {
                val shown = ArrayList<String>()
                var firstOfLast = 0
                var lastPageHadResults = true
                for (p in 0..pages) {
                    val got = Discovery.searchPages(q, p)
                    if (p == pages) firstOfLast = shown.size
                    lastPageHadResults = got.isNotEmpty()
                    for (r in got) {
                        if (Whitelist.hiddenFromSearch(r.url)) continue          // filtered or always blocked: not shown
                        val opens = Whitelist.isAllowed(r.url)
                        if (s.searchApprovedOnly && !opens) continue
                        shown += result(r, opens, if (shown.size == firstOfLast && p == pages && p > 0) "more" else null)
                    }
                    if (got.isEmpty()) break
                }
                if (shown.isEmpty()) body.append("<p class=\"note\">No results. Try other words.</p>")
                else shown.forEach { body.append(it) }
                if (lastPageHadResults && pages < 9)
                    body.append("<a class=\"more\" href=\"/search/?q=${esc(URLEncoder.encode(q, "UTF-8"))}&amp;p=${pages + 1}#more\">More results</a>")
                AppLog.i("Search", "Searched (${shown.size} results shown)")
            } catch (e: Discovery.Challenge) {
                body.append("<div class=\"note\"><p>DuckDuckGo wants to check that a person is searching (there have been many searches).</p>")
                    .append("<a class=\"btn\" href=\"wlb://search-check?u=${esc(URLEncoder.encode(e.url, "UTF-8"))}\">Answer the check</a></div>")
            } catch (e: Exception) {
                body.append("<div class=\"note\"><p>Couldn't search right now. Check the internet connection.</p>")
                    .append("<a class=\"btn\" href=\"${esc(urlFor(q))}\">Try again</a></div>")
            }
        }
        val html = PAGE.replace("{{title}}", esc(if (q.isEmpty()) "Search" else q)).replace("{{body}}", body.toString())
            .replaceFirst("<html lang=\"en\">", "<html lang=\"en\" data-theme=\"${if (Ui.dark) "dark" else "light"}\">")
            .replaceFirst("</head>", "<style>:root,:root[data-theme=\"dark\"]{--bg:${Ui.hex(Ui.PAGE)};--ink:${Ui.hex(Ui.INK)};" +
                "--muted:${Ui.hex(Ui.MUTED)};--tile:${Ui.hex(Ui.CARD)};--edge:${Ui.hex(Ui.LINE)};--accent:${Ui.hex(Ui.ACCENT)};" +
                "--accent-text:${Ui.hex(Ui.ACCENT_TEXT)};--soft:${Ui.hex(Ui.SOFT)}}</style></head>")
        return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(html.toByteArray()))
    }

    private fun result(r: Discovery.Page, opens: Boolean, id: String?): String {
        val letter = esc(r.domain.take(1).uppercase())
        val icon = "https://www.google.com/s2/favicons?sz=64&domain=" + URLEncoder.encode(r.domain, "UTF-8")
        return "<article class=\"r\"${if (id != null) " id=\"$id\"" else ""}>" +
            "<div class=\"where\"><span class=\"fav\" data-l=\"$letter\"><img alt=\"\" loading=\"lazy\" src=\"${esc(icon)}\" onerror=\"this.parentNode.textContent=this.parentNode.dataset.l\"></span>" +
            "<span class=\"crumb\">${esc(breadcrumb(r.url))}</span></div>" +
            "<a class=\"t\" href=\"${esc(r.url)}\">${esc(r.title)}</a>" +
            (if (r.snippet.isNotBlank()) "<p class=\"s\">${esc(r.snippet)}</p>" else "") +
            "<span class=\"tag ${if (opens) "ok" else "ask"}\">${if (opens) "Opens on this phone" else "Ask to open this page"}</span></article>"
    }

    private const val ICON_SEARCH = "<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" aria-hidden=\"true\"><circle cx=\"11\" cy=\"11\" r=\"7\"/><path d=\"m20 20-4-4\"/></svg>"

    private val PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{title}}</title>
<style>
  @font-face { font-family: 'Figtree'; src: url('/home/fonts/Figtree.ttf') format('truetype'); font-weight: 300 900; font-display: swap; }
  :root { --bg: #F5F3EE; --ink: #1C2B2D; --muted: #5B6B69; --tile: #FFFFFF; --edge: #E7E3DB; --accent: #1F5F55; --accent-text: #1F5F55; --soft: #E1EEEA; }
  :root[data-theme="dark"] { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--ink); font: 15px/1.4 Figtree, system-ui, sans-serif; -webkit-tap-highlight-color: transparent; }
  main { max-width: 40rem; margin: 0 auto; padding: .9rem 1rem 3rem; display: flex; flex-direction: column; gap: .7rem; }
  .search { display: flex; align-items: center; gap: .6rem; min-height: 48px; border-radius: 24px; background: var(--tile); border: 1px solid var(--edge); padding: 0 1rem; }
  .search svg { flex: none; color: var(--muted); }
  .search input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--ink); font: 16px Figtree, system-ui, sans-serif; min-height: 44px; }
  .search:focus-within { border-color: var(--accent); }
  .r { background: var(--tile); border: 1px solid var(--edge); border-radius: 14px; padding: .75rem .9rem; display: flex; flex-direction: column; gap: .3rem; scroll-margin-top: .8rem; }
  .where { display: flex; align-items: center; gap: .5rem; min-width: 0; }
  .fav { flex: none; width: 22px; height: 22px; border-radius: 6px; background: var(--soft); color: var(--accent-text); display: grid; place-items: center; font-size: 12px; font-weight: 700; overflow: hidden; }
  .fav img { width: 18px; height: 18px; }
  .crumb { font-size: .8rem; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  a.t { color: var(--accent-text); font-size: 1.05rem; font-weight: 600; line-height: 1.3; text-decoration: none; overflow-wrap: anywhere; }
  a.t:active { text-decoration: underline; }
  .s { margin: 0; font-size: .88rem; line-height: 1.45; overflow-wrap: anywhere; }
  .tag { align-self: flex-start; font-size: .72rem; font-weight: 700; border-radius: 8px; padding: 2px 8px; }
  .tag.ok { background: var(--soft); color: var(--accent-text); }
  .tag.ask { background: var(--edge); color: var(--muted); }
  .more { align-self: center; color: var(--accent-text); font-weight: 700; min-height: 48px; display: flex; align-items: center; padding: 0 1rem; text-decoration: none; }
  .note { color: var(--muted); text-align: center; padding: 1.5rem .5rem; margin: 0; }
  .note p { margin: 0 0 1rem; }
  .btn { display: inline-flex; align-items: center; min-height: 46px; padding: 0 1.2rem; border-radius: 999px; background: var(--accent); color: #fff; font-weight: 700; text-decoration: none; }
</style>
</head>
<body><main>{{body}}</main></body>
</html>"""
}
