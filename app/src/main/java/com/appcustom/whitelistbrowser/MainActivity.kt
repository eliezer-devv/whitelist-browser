package com.appcustom.whitelistbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebStorage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        const val BLOCKED_PAGE = "file:///android_asset/blocked.html"
        private const val REQ_PERMISSIONS = 42
    }

    private lateinit var web: WebView
    private lateinit var pageTitle: TextView
    private lateinit var status: TextView
    private lateinit var backBtn: ImageButton
    private lateinit var forwardBtn: ImageButton

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()        // whitelist fetches
    private val updateIo = Executors.newSingleThreadExecutor()  // app updates, requests, ad list downloads
    private val traceIo = Executors.newSingleThreadExecutor()   // finding where stopped links lead
    private lateinit var updateBanner: TextView
    private var availableUpdate: Updater.Release? = null
    private var updating = false
    private var isResumedNow = false
    private val refreshTask = Runnable { refreshWhitelist() }

    // Embedded content blocked on the open page: the sites it came from (for the "parts were blocked" bar).
    @Volatile private var topUrl: String? = null
    private val blockedFrames: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    private var frameNoteClosedFor: String? = null     // the page whose bar was closed (don't show it again there)

    // Quick checking: after a request is sent (and when an approval arrives), the phone checks for the
    // answer and the updated lists every 20 seconds for a while, instead of every few minutes, so an
    // approval shows up within moments of being published.
    @Volatile private var fastUntil = 0L
    private val fast get() = System.currentTimeMillis() < fastUntil
    private fun fastChecks(minutes: Int) {
        fastUntil = maxOf(fastUntil, System.currentTimeMillis() + minutes * 60_000L)
        main.removeCallbacks(refreshTask)
        if (isResumedNow) main.postDelayed(refreshTask, 20_000L)
    }

    // Small screens (under 360dp wide, e.g. 2.8-inch phones): Forward and Reload move into the menu,
    // dialogs get slimmer margins, and a few rows stack instead of sitting side by side.
    private val narrow get() = resources.configuration.screenWidthDp in 1 until 360
    // Forward and Reload only move into the ⋮ menu when the top bar is truly too tight for them.
    private val tinyBar get() = resources.configuration.screenWidthDp in 1 until 300

    // Temporary access: every 15 seconds while the app is on screen, count time spent on
    // "time on the site" grants, warn when time is nearly up, and close pages whose time has run out.
    private val tempTick = 15_000L
    private val warned = HashSet<String>()
    private val tempTask = object : Runnable {
        override fun run() {
            checkTemporary(countTime = true)
            if (isResumedNow) main.postDelayed(this, tempTick)
        }
    }

    // Android permission request waiting for the user's answer.
    private var pendingPermissionCallback: (() -> Unit)? = null
    // The addresses a tapped link has passed through so far (main thread only). Starts afresh with each
    // tap or typed address, so when a link is stopped we know the route it took to get there.
    private val trail = ArrayList<String>()
    // Route taken to each stopped address, before the stop.
    private val trailBefore = HashMap<String, List<String>>()

    // Is the open page in "no photos or videos" mode? Read by the request filter on a background thread.
    // Photos and/or videos off on the open page (lists: "noPhotos", "noVideos", "noMedia" for both).
    @Volatile private var photosOffHere = false
    @Volatile private var videosOffHere = false
    private val mediaOffHere get() = photosOffHere || videosOffHere

    // Told by Android when the phone gets a connection, so waiting items go out and lists update.
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // Per-site answers to "allow camera/mic/location?", remembered until the app is closed.
    private val siteChoices = mutableMapOf<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light or dark: the phone's own setting, unless chosen in ⋮ → Appearance.
        Ui.applyTheme(this)
        setTheme(if (Ui.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        web.setBackgroundColor(Ui.PAGE)            // no white flash between pages in dark mode
        pageTitle = findViewById(R.id.pageTitle)
        // The app's fonts (packed in at build time; the phone's own font if they're missing).
        Ui.init(this)
        pageTitle.typeface = Ui.boldFace
        findViewById<TextView>(R.id.status).typeface = Ui.bodyFace
        findViewById<TextView>(R.id.updateBanner).typeface = Ui.boldFace
        // Hidden way into the admin page: tap the name at the top 7 times within 4 seconds.
        var taps = 0
        var firstTap = 0L
        pageTitle.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - firstTap > 4000) { firstTap = now; taps = 0 }
            if (++taps >= 7) {
                taps = 0
                startActivity(Intent(this, AdminActivity::class.java))
            }
        }
        status = findViewById(R.id.status)
        backBtn = findViewById(R.id.back)
        forwardBtn = findViewById(R.id.forward)
        updateBanner = findViewById(R.id.updateBanner)
        updateBanner.setOnClickListener { availableUpdate?.let { startUpdate(it) } }

        TempTime.load(this)       // time already used on "time on the site" temporary access
        Whitelist.loadCache(this) // last known list, so it works offline
        // Ad list: load the saved copy (or the one in the app) first, then fetch a fresh one if a week
        // has passed. In that order, so an old copy can never overwrite a fresh one.
        io.execute {
            runCatching { AdBlock.load(applicationContext) }
            updateIo.execute { runCatching { AdBlock.refreshIfDue(applicationContext) } }
            prepareFilters()
        }
        Passthrough.userAgent = android.webkit.WebSettings.getDefaultUserAgent(this) // look like the browser when tracing links

        // Set up locally first, with or without internet: the phone's ID and install date, and its
        // registration waiting in the outbox. Then send whatever is waiting, if there's a connection.
        Device.id(this)
        Device.installedOn(this)
        sendWaiting(registerFirst = true)
        watchForConnection()
        if (Requests.isSetUp() && Device.name(this) == null) askName()
        setupWebView()
        setupToolbar()

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else goHome()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        refreshWhitelist() // check GitHub every time the app comes to the front
        maybeAutoCheckForUpdate()
        if (Config.LOCK_TASK) runCatching { startLockTask() }
        main.removeCallbacks(tempTask)
        main.postDelayed(tempTask, tempTick)
    }

    override fun onPause() {
        isResumedNow = false
        main.removeCallbacks(refreshTask)
        main.removeCallbacks(tempTask)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        networkCallback?.let { runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        io.shutdownNow()
        updateIo.shutdownNow()
        traceIo.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            backSteps() != 0 -> goBackSkippingBlocked()
            Config.LOCK_TASK -> Unit
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------- WebView + enforcement ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)        // target=_blank opens in this view, and gets checked
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        web.webViewClient = object : WebViewClient() {
            // Links, form posts, JS navigation and server redirects.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Buttons on our own pages ("Ask for this site") use wlb: links. Websites can't use them.
                if (url.startsWith("wlb://ask-media")) {
                    // A tapped "Photo blocked" or "Video blocked" placeholder: ask for that kind on this page.
                    if (request.isForMainFrame && request.hasGesture() && mediaOffHere) {
                        val kind = request.url.getQueryParameter("kind")
                        showRequestDialog(Requests.Action.ALLOW, view?.url, mediaBack = true,
                            mediaKind = if (kind == "photos" || kind == "videos") kind else "both")
                    }
                    return true
                }
                if (url.startsWith("wlb:")) {
                    val here = view?.url
                    if (request.isForMainFrame && (HomePage.isHome(here) || here?.startsWith(BLOCKED_PAGE) == true)) {
                        handleAppLink(Uri.parse(url))
                    }
                    return true
                }
                when (ExternalLinks.kind(url)) {
                    ExternalLinks.Kind.EXTERNAL -> {
                        if (request.isForMainFrame) openExternal(url, userTapped = request.hasGesture())
                        return true
                    }
                    ExternalLinks.Kind.FORBIDDEN -> return true
                    ExternalLinks.Kind.WEB -> Unit
                }
                if (request.isForMainFrame) {
                    if (request.hasGesture() && !request.isRedirect) trail.clear() // a new tap: a new route
                    if (trail.lastOrNull() != url) trail += url
                    while (trail.size > 20) trail.removeAt(0)
                }
                if (Whitelist.isAllowed(url, request.isForMainFrame)) return false
                // Frames inside a page on a site with "Allow content embedded from other sites" may come
                // from anywhere (the ad and adult filters still apply). Leaving the page is still checked.
                if (!request.isForMainFrame && frameOk(url, request.url.host ?: "", view?.url)) return false
                if (request.isForMainFrame) showBlocked(url) else request.url.host?.let { noteBlockedFrame(it) }
                return true
            }

            // Safety net: never fetch a non-listed page. Images/scripts/CDNs are not filtered,
            // so whitelisted sites keep working.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && request.url.host == HomePage.HOST) {
                    return HomePage.respond(this@MainActivity, request.url.path ?: "")
                }
                // "No photos or videos" pages: media files and players get an empty answer.
                if (request != null && !request.isForMainFrame && mediaOffHere &&
                    ((photosOffHere && MediaBlock.isImage(request)) || (videosOffHere && MediaBlock.isVideo(request)))) {
                    return MediaBlock.emptyResponse()
                }
                // Ad blocking: things a page loads from ad and tracker domains get an empty answer.
                if (request != null && !request.isForMainFrame && Whitelist.state.adblock) {
                    val host = request.url.host
                    if (host != null && AdBlock.isAd(host, Whitelist.state.adblockExceptions)) return AdBlock.blockedResponse()
                }
                // Content filters (adult, gambling, malware): anything a page loads from a listed site gets an
                // empty answer, even from sites on the phone's lists, unless that site was approved "anyway".
                if (request != null && !request.isForMainFrame) {
                    val host = request.url.host
                    val st = Whitelist.state
                    if (host != null && (st.adult || st.gambling || st.malware) && !Whitelist.isUnfiltered(host)) {
                        val exceptions = st.adblockExceptions
                        if (st.malware && Filters.malware.blocks(host, exceptions)) return Filters.malware.blockedResponse()
                        if (st.adult && Filters.adult.blocks(host, exceptions)) return Filters.adult.blockedResponse()
                        if (st.gambling && Filters.gambling.blocks(host, exceptions)) return Filters.gambling.blockedResponse()
                    }
                }
                // Embedded frames (a video, a map, a sign-in box) from sites that aren't allowed on this page:
                // a small note in their place, and the "parts were blocked" bar. Allowed if the frame's site is
                // on the lists, or this page's site allows it (its embeds, or "content from other sites").
                if (request != null && !request.isForMainFrame && isFrameDocument(request)) {
                    val u = request.url.toString()
                    val host = request.url.host
                    if (host != null && (u.startsWith("https://") || u.startsWith("http://")) && !frameOk(u, host, topUrl)) {
                        noteBlockedFrame(host)
                        return frameBlockedResponse(host)
                    }
                }
                if (request != null && request.isForMainFrame && !Whitelist.isAllowed(request.url.toString())) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(),
                        ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                topUrl = url
                blockedFrames.clear()
                frameNoteClosedFor = null      // closing the bar only lasts until the page loads again
                hideFrameNote()
                setMediaMode(url)
                if (url != null && !url.startsWith(BLOCKED_PAGE) && !HomePage.isHome(url) && trail.lastOrNull() != url) trail += url
                if (url != null && !Whitelist.isAllowed(url)) {
                    view?.stopLoading()
                    showBlocked(url)
                }
                updateUi()
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (mediaOffHere) view?.evaluateJavascript(MediaBlock.script(photosOffHere, videosOffHere), null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (mediaOffHere) view?.evaluateJavascript(MediaBlock.script(photosOffHere, videosOffHere), null)
                updateUi()
            }

            // Sites like YouTube change pages without reloading. Check those page changes too.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                topUrl = url
                if (url != null && !Whitelist.isAllowed(url)) showBlocked(url)
                // Sites that change pages without reloading: a new page may have a different media setting.
                val photosBefore = photosOffHere
                val videosBefore = videosOffHere
                setMediaMode(url)
                if ((photosOffHere && !photosBefore) || (videosOffHere && !videosBefore)) {
                    view?.evaluateJavascript(MediaBlock.script(photosOffHere, videosOffHere), null)
                }
                if ((!photosOffHere && photosBefore) || (!videosOffHere && videosBefore)) view?.reload()
                updateUi()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request != null) handleMediaRequest(request)
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (origin != null && callback != null) handleLocationRequest(origin, callback)
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    // ---------- navigation ----------

    private fun navigate(input: String) {
        var text = input.trim()
        if (text.isEmpty()) return
        trail.clear() // typed or home page: a new route
        // Typed "tel:123", "mailto:x@y.z" etc. go to their own app ("example.com:8080" is still a web address).
        if (Regex("^[a-zA-Z][a-zA-Z0-9+-]*:").containsMatchIn(text) && !text.startsWith("localhost:") &&
            ExternalLinks.kind(text) != ExternalLinks.Kind.WEB) {
            openExternal(text, userTapped = true)
            return
        }
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text) && !text.startsWith("about:")) {
            text = "https://$text"
        }
        if (Whitelist.isAllowed(text)) web.loadUrl(text) else showBlocked(text)
    }

    private fun goHome() {
        val custom = Whitelist.state.homepage
        if (custom != null) navigate(custom) else web.loadUrl(HomePage.URL)
    }

    private fun showBlocked(url: String) {
        // Remember the route that led here, and find out where the link goes next (in the background),
        // so "Ask to open" can name the real destination and everything on the way.
        val before = trail.takeWhile { it != url }.filter { !it.startsWith(BLOCKED_PAGE) }
        if (trailBefore.size > 50) trailBefore.clear()
        trailBefore[url] = before
        if (url.startsWith("http://") || url.startsWith("https://")) {
            traceIo.execute { runCatching { Passthrough.trace(url, before) } }
        }
        val filtered = Uri.parse(url).host?.let { Whitelist.filteredAs(it) }.orEmpty()
        val reason = when {
            filtered.isNotEmpty() && !Uri.parse(url).host.orEmpty().let { Whitelist.isUnfiltered(it) } -> "filtered"
            Whitelist.tempEnded(url) -> "expired"
            Whitelist.state.allow.isEmpty() -> "empty"
            Whitelist.isPageRestricted(url) -> "page"
            else -> "not-listed"
        }
        val cats = if (reason == "filtered") "&filters=${filtered.joinToString(",")}" else ""
        val look = if (Ui.dark) "&theme=dark" else ""
        main.post { web.loadUrl("$BLOCKED_PAGE#reason=$reason&url=${Uri.encode(url)}$cats$look") }
    }

    /** The site the blocked page is standing in for, or null if we're not on the blocked page. */
    private fun blockedTarget(url: String?): String? {
        if (url == null || !url.startsWith(BLOCKED_PAGE)) return null
        val frag = Uri.parse(url).encodedFragment ?: return ""
        return Uri.parse("x://x?$frag").getQueryParameter("url") ?: ""
    }

    /** Re-check the open page after the list changes. */
    private fun enforceCurrent() {
        val cur = web.url
        val target = blockedTarget(cur)
        when {
            cur == null || cur == "about:blank" -> goHome()
            target != null -> when {
                // Site was just approved: open it by itself.
                target.isNotEmpty() && Whitelist.isAllowed(target) -> navigate(target)
                target.isEmpty() && Whitelist.state.allow.isNotEmpty() -> goHome()
            }
            !Whitelist.isAllowed(cur) -> showBlocked(cur)
        }
    }

    // ---------- whitelist refresh loop ----------

    private fun refreshWhitelist() {
        main.removeCallbacks(refreshTask)
        io.execute {
            val before = Whitelist.state
            val error = try {
                Whitelist.refresh(applicationContext); null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                if (isDestroyed) return@post
                Whitelist.lastError = error
                // Photos and videos were switched on or off for the open page: reload it.
                if (mediaChanged(web.url)) { setMediaMode(web.url); web.reload() }
                // Home page shows the list, so redraw it when the list changed.
                if (HomePage.isHome(web.url) && !Whitelist.state.sameContent(before)) web.reload()
                enforceCurrent()
                updateUi()
                if (isResumedNow) main.postDelayed(refreshTask, if (fast) 20_000L else Whitelist.state.refreshMinutes * 60_000L)
                // Online: send anything waiting (a registration, requests made offline), and check in
                // so the phone isn't archived as unused (every 12 hours at most).
                if (error == null) {
                    sendWaiting(registerFirst = true, checkIn = true)
                    prepareFilters() // a filter may have been switched on
                }
            }
        }
    }

    // ---------- toolbar ----------

    private fun setupToolbar() {
        backBtn.setOnClickListener { goBackSkippingBlocked() }
        forwardBtn.setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<View>(R.id.reload).setOnClickListener { web.reload() }
        // The "parts were blocked" bar, in the app's colours (light or dark).
        findViewById<View>(R.id.frameNote).setBackgroundColor(Ui.AMBER_BG)
        findViewById<TextView>(R.id.frameNoteText).apply { setTextColor(Ui.AMBER_INK); typeface = Ui.bodyFace }
        findViewById<TextView>(R.id.frameNoteAsk).apply { setTextColor(Ui.AMBER_INK); typeface = Ui.boldFace }
        findViewById<android.widget.ImageButton>(R.id.frameNoteClose).imageTintList = android.content.res.ColorStateList.valueOf(Ui.AMBER_INK)
        findViewById<View>(R.id.frameNoteAsk).setOnClickListener { showFramesRequest() }
        findViewById<View>(R.id.frameNoteClose).setOnClickListener { frameNoteClosedFor = web.url; hideFrameNote() }
        if (tinyBar) {                             // room for the site's name: these two go in the ⋮ menu
            forwardBtn.visibility = View.GONE
            findViewById<View>(R.id.reload).visibility = View.GONE
        }
        findViewById<View>(R.id.home).setOnClickListener { goHome() }
        findViewById<View>(R.id.menu).setOnClickListener { showMenu(it) }
        status.setOnClickListener {
            status.text = getString(R.string.checking)
            refreshWhitelist()
        }
    }

    private fun updateUi() {
        if (isDestroyed) return
        backBtn.isEnabled = backSteps() != 0
        forwardBtn.isEnabled = web.canGoForward()
        backBtn.alpha = if (backBtn.isEnabled) 1f else 0.35f
        forwardBtn.alpha = if (forwardBtn.isEnabled) 1f else 0.35f

        val cur = web.url
        val target = blockedTarget(cur)
        // The top bar shows the open site's name (no address bar: sites open from the home page).
        pageTitle.text = when {
            cur == null || cur == "about:blank" || HomePage.isHome(cur) -> "Home"
            target != null -> target.takeIf { it.isNotEmpty() }?.let { Uri.parse(it).host?.removePrefix("www.") } ?: "Not on the list"
            else -> web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: Uri.parse(cur).host?.removePrefix("www.") ?: ""
        }

        val s = Whitelist.state
        val err = Whitelist.lastError
        val tempNote = Whitelist.tempStatus(cur)?.let { "$it " } ?: ""
        val mediaNote = tempNote + when {
            photosOffHere && videosOffHere -> "Photos and videos are off on this page. "
            photosOffHere -> "Photos are off on this page. "
            videosOffHere -> "Videos are off on this page. "
            else -> ""
        }
        status.text = mediaNote + when {
            err != null && s.allow.isEmpty() -> "No list loaded. Tap to retry."
            err != null -> "Offline, using saved list of ${s.allow.distinct().size} sites. Tap to retry."
            s.updatedAt == 0L -> getString(R.string.checking)
            else -> "${s.allow.distinct().size} sites allowed, list checked ${ago(s.updatedAt)}. Tap to check now."
        }
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            else -> "${m / 60} h ago"
        }
    }

    // ---------- menu ----------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            val cur = web.url
            val onRealSite = cur != null && !HomePage.isHome(cur) && blockedTarget(cur) == null &&
                (cur.startsWith("https://") || cur.startsWith("http://"))
            if (tinyBar) {                         // on tiny screens, Forward and Reload live here
                menu.add(0, 10, 0, "Forward").isEnabled = web.canGoForward()
                menu.add(0, 11, 0, "Reload")
            }
            if (blockedFrames.isNotEmpty() && Requests.isSetUp()) {
                menu.add(0, 13, 0, "Ask for blocked parts (${blockedFrames.size})")
            }
            menu.add(0, 4, 0, "Ask for a new site")
            menu.add(0, 9, 0, "My requests")
            menu.add(0, 5, 1, "Ask to block").isEnabled = onRealSite
            if (mediaOffHere && onRealSite) menu.add(0, 8, 1, when {
                photosOffHere && videosOffHere -> "Ask for photos and videos"
                photosOffHere -> "Ask for photos"
                else -> "Ask for videos"
            })
            menu.add(0, 6, 2, "Clear cache")
            menu.add(0, 7, 3, "Clear cookies and site data")
            menu.add(0, 1, 4, "Check for app update")
            menu.add(0, 2, 5, "Check list now")
            menu.add(0, 12, 6, "Appearance")
            menu.add(0, 3, 6, "About this phone")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    4 -> showRequestDialog(Requests.Action.ALLOW, null)
                    13 -> showFramesRequest()
                    9 -> showMyRequests()
                    10 -> if (web.canGoForward()) web.goForward()
                    11 -> web.reload()
                    6 -> clearCache()
                    3 -> showAbout()
                    12 -> showAppearance()
                    7 -> confirmClearCookies()
                    5 -> showRequestDialog(Requests.Action.BLOCK, cur)
                    8 -> showRequestDialog(Requests.Action.ALLOW, cur, mediaBack = true,
                        mediaKind = if (photosOffHere && videosOffHere) "both" else if (photosOffHere) "photos" else "videos")
                    1 -> checkForUpdate(manual = true)
                    2 -> { status.text = getString(R.string.checking); refreshWhitelist() }
                }
                true
            }
            show()
        }
    }

    // ---------- app updates ----------

    private fun maybeAutoCheckForUpdate() {
        val prefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last >= Config.UPDATE_CHECK_HOURS * 3_600_000L) {
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            checkForUpdate(manual = false)
        }
    }

    private fun checkForUpdate(manual: Boolean) {
        if (updating) return
        if (manual) toast("Checking GitHub for a new version")
        updateIo.execute {
            val result = runCatching { Updater.fetchLatest() }
            main.post {
                if (isDestroyed) return@post
                val release = result.getOrNull()
                when {
                    release != null && Updater.isNewer(release) -> {
                        availableUpdate = release
                        showBanner("Version ${release.versionName} is available. Tap to update.")
                    }
                    result.isFailure && manual ->
                        toast("Couldn't check for updates: ${result.exceptionOrNull()?.message}")
                    manual -> toast("You have the latest version (${BuildConfig.VERSION_NAME})")
                }
            }
        }
    }

    private fun startUpdate(release: Updater.Release) {
        if (updating) return
        // Android 8+ asks once whether this app may install updates.
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow this app to install updates, then come back and tap the banner again")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updating = true
        showBanner("Downloading version ${release.versionName}")
        updateIo.execute {
            try {
                val apk = Updater.download(this, release) { pct ->
                    main.post { showBanner("Downloading version ${release.versionName}: $pct%") }
                }
                main.post {
                    showBanner("Installing version ${release.versionName}")
                    if (Config.LOCK_TASK) runCatching { stopLockTask() } // the installer screen needs to open
                }
                Updater.install(applicationContext, apk)
            } catch (e: Exception) {
                main.post { showBanner("Update failed: ${e.message ?: "unknown error"}. Tap to try again.") }
            } finally {
                main.post { updating = false }
            }
        }
    }

    private fun showBanner(text: String) {
        updateBanner.text = text
        updateBanner.visibility = View.VISIBLE
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---------- links for other apps ----------

    private fun openExternal(url: String, userTapped: Boolean) {
        when (val r = ExternalLinks.resolve(this, url)) {
            is ExternalLinks.Result.OpenHere -> navigate(r.url)
            ExternalLinks.Result.Forbidden -> toast("This kind of link can't be opened")
            ExternalLinks.Result.NoApp -> toast("No app on this phone can open this link")
            is ExternalLinks.Result.Launch -> {
                val launch = {
                    try { startActivity(r.intent) } catch (e: ActivityNotFoundException) {
                        toast("No app on this phone can open this link")
                    } catch (e: SecurityException) {
                        toast("That app can't be opened from here")
                    }
                }
                // A page opening another app without a tap has to be confirmed.
                if (userTapped) launch()
                else Ui.AppDialog(this, sheet = false).apply {
                    title("Open ${r.appName ?: "another app"}?", sub = "This page wants to open it.", icon = R.drawable.ic_d_app)
                    button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
                    button("Open", Ui.Kind.PRIMARY) { it.dismiss(); launch() }
                }.show()
            }
        }
    }

    // ---------- downloads ----------

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("This file can't be downloaded")
            return
        }
        // Android 9 and older need storage permission to save into Downloads.
        val perms = if (Build.VERSION.SDK_INT < 29) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray()
        withAndroidPermissions(perms) { granted ->
            if (perms.isNotEmpty() && granted.isEmpty()) {
                toast("Storage permission is needed to download files")
                return@withAndroidPermissions
            }
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    if (mimeType != null) setMimeType(mimeType)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    if (userAgent != null) addRequestHeader("User-Agent", userAgent)
                    setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading $name to the Downloads folder")
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
            }
        }
    }

    // ---------- camera, microphone, location ----------

    private fun handleMediaRequest(request: PermissionRequest) {
        val wanted = mutableListOf<String>()
        val androidPerms = mutableListOf<String>()
        val what = mutableListOf<String>()
        for (r in request.resources) when (r) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> { wanted += r; androidPerms += Manifest.permission.CAMERA; what += "camera" }
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> { wanted += r; androidPerms += Manifest.permission.RECORD_AUDIO; what += "microphone" }
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> wanted += r // protected video playback, no prompt needed
        }
        if (androidPerms.isEmpty()) {
            if (wanted.isEmpty()) request.deny() else request.grant(wanted.toTypedArray())
            return
        }
        val host = request.origin.host ?: request.origin.toString()
        askSite(host, what.joinToString(" and ")) { ok ->
            if (!ok) return@askSite request.deny()
            withAndroidPermissions(androidPerms.toTypedArray()) { granted ->
                val grant = wanted.filter {
                    when (it) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA in granted
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO in granted
                        else -> true
                    }
                }
                if (grant.isEmpty()) request.deny() else request.grant(grant.toTypedArray())
            }
        }
    }

    private fun handleLocationRequest(origin: String, callback: GeolocationPermissions.Callback) {
        val host = Uri.parse(origin).host ?: origin
        askSite(host, "location") { ok ->
            if (!ok) return@askSite callback.invoke(origin, false, false)
            withAndroidPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted ->
                callback.invoke(origin, granted.isNotEmpty(), false)
            }
        }
    }

    /** "en.wikipedia.org wants to use your camera. Allow?" Remembered per site until the app closes. */
    private fun askSite(host: String, what: String, onResult: (Boolean) -> Unit) {
        val key = "$host|$what"
        siteChoices[key]?.let { return onResult(it) }
        var answered = false
        val answer = { ok: Boolean ->
            if (!answered) { answered = true; siteChoices[key] = ok; onResult(ok) }
        }
        Ui.AppDialog(this, sheet = false).apply {
            title("Use your $what?", sub = host,
                icon = if ("location" in what) R.drawable.ic_d_pin else R.drawable.ic_d_cam, iconBg = Ui.AMBER_BG, iconFg = Ui.AMBER_INK)
            add(Ui.text(this@MainActivity, "This site wants to use it. Your answer is remembered until the app is closed.", 14.5f, Ui.MUTED))
            button("Block", Ui.Kind.SECONDARY) { answer(false); it.dismiss() }
            button("Allow", Ui.Kind.PRIMARY) { answer(true); it.dismiss() }
            onDismiss { answer(false) }
        }.show()
    }

    /** Asks Android for any missing permissions, then reports which of [perms] are granted. */
    private fun withAndroidPermissions(perms: Array<String>, then: (Set<String>) -> Unit) {
        val grantedNow = { perms.filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet() }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return then(grantedNow())
        pendingPermissionCallback?.invoke() // answer any older request with what's granted so far
        pendingPermissionCallback = { then(grantedNow()) }
        requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val cb = pendingPermissionCallback
            pendingPermissionCallback = null
            cb?.invoke()
        }
    }

    // ---------- requests to allow or block a site ----------

    /**
     * How many steps back the nearest page that isn't blocked is (as a negative number), or 0 if
     * there's none. Blocked addresses can end up in the history on sites that change pages without
     * reloading, and plain Back would land on one, get blocked again and never get anywhere.
     */
    private fun backSteps(): Int {
        val list = web.copyBackForwardList()
        var i = list.currentIndex - 1
        while (i >= 0) {
            val u = list.getItemAtIndex(i)?.url
            if (u != null && !u.startsWith(BLOCKED_PAGE) && Whitelist.isAllowed(u)) return i - list.currentIndex
            i--
        }
        return 0
    }

    private fun goBackSkippingBlocked() {
        val steps = backSteps()
        if (steps != 0) web.goBackOrForward(steps) else goHome()
    }

    /** wlb://request?action=allow&url=... and wlb://back, from the home page or blocked page. */
    private fun handleAppLink(uri: Uri) {
        if (uri.host == "back") { goBackSkippingBlocked(); return }
        if (uri.host != "request") return
        val action = if (uri.getQueryParameter("action") == "block") Requests.Action.BLOCK else Requests.Action.ALLOW
        val url = uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }
        if (action == Requests.Action.ALLOW && url != null) askToOpen(url) else showRequestDialog(action, url)
    }

    /**
     * "Ask to open" on the blocked page: first find where the stopped link really leads (usually
     * already done in the background), then ask about the real destination, listing the route.
     */
    private fun askToOpen(stopped: String) {
        Passthrough.cached(stopped)?.let { showRequestDialog(Requests.Action.ALLOW, it.destination, route = it); return }
        toast("Checking where this link leads")
        val before = trailBefore[stopped] ?: emptyList()
        traceIo.execute {
            val route = runCatching { Passthrough.trace(stopped, before) }.getOrNull()
            main.post {
                if (!isDestroyed) showRequestDialog(Requests.Action.ALLOW, route?.destination ?: stopped, route = route)
            }
        }
    }

    /**
     * One screen for every request: for an open page it offers "Just this page" or "Whole site",
     * the photos-and-videos choice, and an optional note. With no page (Ask for a new site) the user
     * types the site instead. [mediaBack] = asking for photos and videos on a page that has them off.
     */
    private fun showRequestDialog(action: Requests.Action, pageUrl: String?, mediaBack: Boolean = false,
                                  route: Passthrough.Route? = null, mediaKind: String = "both") {
        if (!Requests.isSetUp()) {
            toast("Requests aren't set up for this app yet")
            return
        }
        val siteDomain = pageUrl?.let { siteScope(it) }          // "coolmathgames.com", "google.com" ...
        val pageKey = pageUrl?.let { Whitelist.pageKey(it) }     // "youtube.com/watch?v=abc"
        // A site's front page is the same as "whole site", so only offer the choice for deeper pages.
        val canChoose = siteDomain != null && pageKey != null && pageKey.substringAfter('/').isNotEmpty()
        // On a content filter's list? Say which, and make the button "Ask anyway" (the owner sees the list too).
        val filteredNow = if (action == Requests.Action.ALLOW && !mediaBack && siteDomain != null) Whitelist.filteredAs(siteDomain) else emptyList()

        val verb = if (action == Requests.Action.ALLOW) "open" else "block"
        val title = when {
            mediaBack && mediaKind == "photos" -> "Ask for photos"
            mediaBack && mediaKind == "videos" -> "Ask for videos"
            mediaBack -> "Ask for photos and videos"
            siteDomain == null -> "Ask for a new site"
            else -> "Ask to $verb"
        }
        val d = Ui.AppDialog(this, sheet = true)
        d.title(title, sub = siteDomain)
        d.add(Ui.text(this, "Your request goes to whoever manages this browser.", 14.5f, Ui.MUTED), 6)

        val filterWarning = Ui.box(this, filterWarningText(siteDomain ?: "", filteredNow, pinNote = Whitelist.state.pinApproval), "red").apply {
            visibility = if (filteredNow.isNotEmpty()) View.VISIBLE else View.GONE
        }
        d.add(filterWarning)

        // The "Approve here with a PIN" option (added further down, below the note).
        val (pinRow, pinSwitch) = Ui.switchRow(this, "Approve here with a PIN", "If whoever manages this browser is with you")
        val pinField = Ui.field(this, "Approval PIN",
            type = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD).apply {
            visibility = View.GONE
        }

        val siteField = Ui.field(this, "Website, e.g. scratch.mit.edu",
            type = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        if (siteDomain == null) { d.add(Ui.label(this, "Website")); d.add(siteField, 6) }
        lateinit var sendButton: Button
        // After a site couldn't be found: the address that "Send anyway" would send. Editing it resets this.
        var sendAnyway: String? = null
        // A typed site on a filter's list: the address "Ask anyway" confirmed. Editing it resets this too.
        var askAnyway: String? = null
        siteField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (sendAnyway != null || askAnyway != null) {
                    sendAnyway = null
                    askAnyway = null
                    siteField.error = null
                    filterWarning.visibility = View.GONE
                    if (Whitelist.state.pinApproval) pinRow.visibility = View.VISIBLE
                    sendButton.text = "Send"
                }
            }
        })
        // "Checking scratch.mit.edu…" while a typed site is being checked.
        val checking = Ui.text(this, "", 13.5f, Ui.MUTED).apply { visibility = View.GONE }
        if (siteDomain == null) d.add(checking, 6)

        // The addresses the link passes through on the way, so it's clear what else must be opened.
        val hops = route?.hops.orEmpty().filter { it != pageUrl }
        if (hops.isNotEmpty()) {
            d.add(Ui.box(this, "This link passes through:\n" +
                hops.joinToString("\n") { "→ " + (Whitelist.pageKey(it)?.substringBefore('?') ?: it) } +
                "\nThose are included in your request.", "info"))
        }

        // Just this page, or the whole site.
        var pageScope = canChoose
        if (canChoose) {
            val which = Ui.text(this, pageKey ?: "", 13f, Ui.MUTED)
            d.add(Ui.label(this, "What"))
            d.add(Ui.Segmented(this, listOf("Just this page", "Whole site"), 0, narrow) {
                pageScope = it == 0
                which.text = if (pageScope) pageKey else siteDomain
            }.view, 6)
            d.add(which, 4)
        }

        // "For how long?": always, or just for a while, chosen on two scroll wheels (hours and minutes).
        var forAWhile = false
        val hoursWheel = android.widget.NumberPicker(this).apply {
            minValue = 0; maxValue = 24; value = 0
            wrapSelectorWheel = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS // flick only, no keyboard
            setFormatter { String.format(java.util.Locale.US, "%02d", it) }
        }
        val minutesWheel = android.widget.NumberPicker(this).apply {
            minValue = 0; maxValue = 11; value = 6                                    // steps of 5: 0-55, starts at 30
            displayedValues = Array(12) { String.format(java.util.Locale.US, "%02d", it * 5) }
            wrapSelectorWheel = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val wheels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            background = Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 14).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
            val gap = Ui.dp(this@MainActivity, if (narrow) 6 else 10)
            addView(hoursWheel)
            addView(Ui.text(this@MainActivity, if (narrow) "h" else "hours", 13f, Ui.MUTED).apply { setPadding(gap / 2, 0, gap * 2, 0) })
            addView(minutesWheel)
            addView(Ui.text(this@MainActivity, "min", 13f, Ui.MUTED).apply { setPadding(gap / 2, 0, 0, 0) })
            visibility = View.GONE
        }
        if (action == Requests.Action.ALLOW) {
            d.add(Ui.label(this, "For how long?"))
            d.add(Ui.Segmented(this, listOf("Always", "For a while"), 0, false) {
                forAWhile = it == 1
                wheels.visibility = if (forAWhile) View.VISIBLE else View.GONE
            }.view, 6)
            d.add(wheels, 8)
        }

        // Photos and videos, separately: opening without photos and/or without videos; blocking completely,
        // or only photos, only videos, or both; or (asking for them back) photos, videos, or both.
        val (noPhotosRow, noPhotosSwitch) = Ui.switchRow(this, "Without photos")
        val (noVideosRow, noVideosSwitch) = Ui.switchRow(this, "Without videos")
        var blockWhat = 0                      // 0 completely, 1 only photos, 2 only videos, 3 photos and videos
        var backKind = mediaKind               // asking for them back: "photos", "videos" or "both"
        if (!mediaBack) {
            if (action == Requests.Action.ALLOW) {
                d.add(noPhotosRow)
                d.add(noVideosRow, 8)
            } else {
                d.add(Ui.label(this, "Block"))
                d.add(Ui.Segmented(this, listOf("Completely", "Only photos", "Only videos", "Photos and videos"), 0, true) {
                    blockWhat = it
                }.view, 6)
            }
        } else if (mediaKind == "both") {
            // Both are off here: ask for photos, videos, or both.
            d.add(Ui.label(this, "Which?"))
            d.add(Ui.Segmented(this, listOf("Photos", "Videos", "Both"), 2, false) {
                backKind = listOf("photos", "videos", "both")[it]
            }.view, 6)
        }

        val noteField = Ui.field(this, "e.g. for maths homework",
            type = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        d.add(Ui.label(this, "Why? (optional)"))
        d.add(noteField, 6)

        // Approve here with a PIN: when whoever manages this browser is with them. Only offered if a PIN is
        // set for this phone. The phone doesn't check it: GitHub does, then removes it from the request.
        if (Whitelist.state.pinApproval && filteredNow.isEmpty()) {   // the PIN can't open filtered sites
            d.add(pinRow)
            d.add(pinField, 8)
            pinSwitch.setOnCheckedChangeListener { _, on ->
                pinField.visibility = if (on) View.VISIBLE else View.GONE
                if (on) pinField.requestFocus()
            }
        }

        d.button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
        sendButton = d.button(if (filteredNow.isNotEmpty()) "Ask anyway" else "Send", Ui.Kind.PRIMARY) {
            val domain = siteDomain ?: Whitelist.normalize(siteField.text.toString())
            if (domain == null) {
                siteField.error = "Type a website address"
                return@button
            }
            val scope = if (canChoose && pageScope) Requests.Scope.PAGE else Requests.Scope.SITE
            val subject = if (scope == Requests.Scope.PAGE) pageKey!! else domain
            val media = when {
                mediaBack -> Requests.Media.ON
                action == Requests.Action.ALLOW && (noPhotosSwitch.isChecked || noVideosSwitch.isChecked) -> Requests.Media.OFF
                action == Requests.Action.BLOCK && blockWhat != 0 -> Requests.Media.OFF
                else -> Requests.Media.UNCHANGED
            }
            // Which of them: "photos", "videos" or "both".
            val kind = when {
                mediaBack -> backKind
                action == Requests.Action.ALLOW && noPhotosSwitch.isChecked && !noVideosSwitch.isChecked -> "photos"
                action == Requests.Action.ALLOW && noVideosSwitch.isChecked && !noPhotosSwitch.isChecked -> "videos"
                action == Requests.Action.BLOCK && blockWhat == 1 -> "photos"
                action == Requests.Action.BLOCK && blockWhat == 2 -> "videos"
                else -> "both"
            }
            val minutes = if (action == Requests.Action.ALLOW && forAWhile)
                hoursWheel.value * 60 + minutesWheel.value * 5 else 0
            if (action == Requests.Action.ALLOW && forAWhile && minutes == 0) {
                toast("Choose how long on the wheels, or pick Always") // the sheet stays open
                return@button
            }
            val pinOffered = pinRow.parent != null && pinRow.visibility == View.VISIBLE
            val pin = if (pinOffered && pinSwitch.isChecked) pinField.text.toString().trim() else null
            if (pin != null && !Regex("^\\d{4,8}$").matches(pin)) {
                pinField.error = "The PIN is 4 to 8 digits"
                pinField.requestFocus()
                return@button
            }
            if (pin == null && Requests.recentlySent(this, action, Requests.sentKey(subject, media, kind))) {
                toast("You already asked about $subject. Wait for an answer.")
                d.dismiss()
                return@button
            }
            val note = noteField.text.toString().trim()
            // "Send anyway" after the site couldn't be found: send it, marked as not found.
            if (siteDomain == null && sendAnyway == domain) {
                d.dismiss()
                sendRequest(action, scope, media, domain, null, note, hops, minutes, unverified = true, pin = pin, mediaKind = kind)
                return@button
            }
            if (siteDomain != null) {
                d.dismiss()
                sendRequest(action, scope, media, domain, pageUrl, note, hops, minutes, pin = pin, mediaKind = kind)
                return@button
            }
            // A typed site on a content filter's list: say which, and ask them to confirm first.
            val typedFiltered = if (action == Requests.Action.ALLOW) Whitelist.filteredAs(domain) else emptyList()
            if (typedFiltered.isNotEmpty() && askAnyway != domain) {
                filterWarning.text = filterWarningText(domain, typedFiltered, pinNote = Whitelist.state.pinApproval)
                filterWarning.visibility = View.VISIBLE
                pinSwitch.isChecked = false
                pinRow.visibility = View.GONE
                askAnyway = domain
                sendButton.text = "Ask anyway"
                return@button
            }
            // A typed site: check it exists before sending. The sheet stays open while checking.
            sendButton.isEnabled = false
            checking.text = "Checking $domain…"
            checking.visibility = View.VISIBLE
            traceIo.execute {
                val result = SiteCheck.check(applicationContext, domain)
                main.post {
                    if (isDestroyed || !d.isShowing) return@post
                    sendButton.isEnabled = true
                    checking.visibility = View.GONE
                    if (result == SiteCheck.Result.NOT_FOUND) {
                        // Real sites can look missing too (a Wi-Fi filter, a school-only site),
                        // so offer to send it anyway.
                        siteField.error = "Couldn't find $domain. Check the spelling, or tap Send anyway if you're sure it's right."
                        siteField.requestFocus()
                        sendAnyway = domain
                        sendButton.text = "Send anyway"
                    } else {
                        // Found, or no internet to check with: the request is sent (or saved until online).
                        d.dismiss()
                        sendRequest(action, scope, media, domain, null, note, hops, minutes, pin = pin, mediaKind = kind)
                    }
                }
            }
        }
        d.show()
    }





    /** "⚠️ example.com is on the gambling list, so it's blocked. You can still ask: …" */
    private fun filterWarningText(domain: String, filters: List<String>, pinNote: Boolean = false): String {
        val names = filters.map { when (it) { "adult" -> "adult content"; "malware" -> "malware and scams"; else -> it } }
        val lists = names.joinToString(" and ") + if (names.size > 1) " lists" else " list"
        return "⚠️ $domain is on the $lists, so it's blocked. You can still ask: whoever manages this browser " +
            "will see that it's on this list, and decide." +
            if (pinNote) " (The approval PIN can't open sites on this list.)" else ""
    }

    /**
     * Saves the request in the outbox and sends it straight away if there's internet. Without
     * internet it waits and goes out automatically when the phone is back online.
     */
    private fun sendRequest(action: Requests.Action, scope: Requests.Scope, media: Requests.Media,
                            domain: String, pageUrl: String?, note: String, hops: List<String> = emptyList(),
                            minutes: Int = 0, unverified: Boolean = false, pin: String? = null,
                            frames: List<String> = emptyList(), mediaKind: String = "both") {
        toast(if (pin != null) "Checking the PIN" else "Sending request")
        updateIo.execute {
            val outcome = runCatching {
                // Which content filters list it (so you see that before approving).
                val filtered = if (action == Requests.Action.ALLOW) Whitelist.filteredAs(domain) else emptyList()
                val id = Requests.queue(applicationContext, action, scope, media, domain, pageUrl, note, hops, minutes, unverified,
                    if (frames.isEmpty()) filtered else emptyList(), pin, frames, mediaKind)
                val r = Outbox.flush(applicationContext)
                when {
                    id in r.sent -> null
                    id in r.refused -> "Couldn't send the request: ${r.refused[id]}"
                    else -> "No connection right now. Your request is saved and will be sent automatically."
                }
            }
            main.post {
                fastChecks(10) // watch for the answer (and the updated lists) for the next 10 minutes
                if (isDestroyed) return@post
                val problem = outcome.getOrElse { "Couldn't send the request: ${it.message}" }
                toast(problem ?: when {
                    pin != null -> "Checking the PIN. If it's right, this happens by itself within a minute or two."
                    frames.isNotEmpty() -> "Request sent. If it's approved, reload the page to see the blocked parts."
                    action == Requests.Action.BLOCK -> "Request sent."
                    media == Requests.Media.ON -> "Request sent. If it's approved, ${Requests.mediaWords(mediaKind)} come back by themselves."
                    pageUrl != null -> "Request sent. If it's approved, it opens by itself."
                    else -> "Request sent. If it's approved, $domain appears on the home page."
                })
            }
        }
    }

    // ---------- sending what's waiting ----------

    /**
     * Prepares the registration if needed (works offline), then sends everything waiting in the
     * outbox. Runs on `updateIo`, one at a time, so nothing is ever sent twice.
     */
    private fun sendWaiting(registerFirst: Boolean = false, checkIn: Boolean = false) {
        updateIo.execute {
            if (registerFirst) runCatching { Requests.registerIfNeeded(applicationContext) }
            runCatching { Outbox.flush(applicationContext) }
            if (checkIn) {
                runCatching { Requests.checkIn(applicationContext) }
                runCatching { MyRequests.check(applicationContext, if (fast) 20_000L else 5 * 60_000L) } // answers to this phone's requests
            }
            main.post { if (!isDestroyed) showNewAnswers() }
        }
    }

    // ---------- answers to this phone's requests ----------

    private fun shortDate(t: Long): String =
        java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    /** Pops up answers that arrived since the user last looked. */
    private fun showNewAnswers() {
        val fresh = MyRequests.takeNewAnswers(this)
        if (fresh.isEmpty()) return
        // Approved: the updated list may still be on its way (a minute or two), so keep checking quickly.
        if (fresh.any { it.status == "approved" }) fastChecks(5)
        Ui.AppDialog(this, sheet = false).apply {
            title(if (fresh.size == 1) "Answer to your request" else "Answers to your requests")
            val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            fresh.forEachIndexed { i, answer ->
                list.addView(requestRow(answer.status, answer.summary, answer.message, null, card = true), LinearLayout.LayoutParams(-1, -2).apply {
                    if (i > 0) topMargin = Ui.dp(this@MainActivity, 10)
                })
            }
            add(list)
            button("My requests", Ui.Kind.SECONDARY) { it.dismiss(); showMyRequests() }
            button("OK", Ui.Kind.PRIMARY) { it.dismiss() }
        }.show()
    }

    /**
     * One request in a list: a coloured badge (✓ approved, ✕ not approved, clock waiting, ⚠ not sent or
     * failed), what it was for, the answer, and when. [card]: its own white card (answers pop-up).
     */
    private fun requestRow(status: String, summary: String, message: String, time: String?, card: Boolean = false): LinearLayout {
        val (icon, bg, fg, label) = when (status) {
            "approved" -> RowLook(R.drawable.ic_d_check, Ui.SOFT, Ui.ACCENT_TEXT, "Approved")
            "denied" -> RowLook(R.drawable.ic_d_x, Ui.RED_BG, Ui.RED_INK, "Not approved")
            "notsent" -> RowLook(R.drawable.ic_d_send, Ui.AMBER_BG, Ui.AMBER_INK, "Not sent yet")
            "failed" -> RowLook(R.drawable.ic_d_warn, Ui.AMBER_BG, Ui.AMBER_INK, "Couldn't be sent")
            "waiting" -> RowLook(R.drawable.ic_d_clock, Ui.SEG, Ui.INK2, "Waiting for an answer")
            else -> RowLook(R.drawable.ic_d_clock, Ui.SEG, Ui.INK2, "Closed")
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = Ui.dp(this@MainActivity, 14)
            setPadding(p, Ui.dp(this@MainActivity, 12), p, Ui.dp(this@MainActivity, 12))
            if (card) background = Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 16).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
            addView(Ui.badge(this@MainActivity, icon, bg, fg, 34, 17).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(this@MainActivity, 12)
            })
            val words = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            words.addView(Ui.text(this@MainActivity, label, 12.5f, fg, "bold"))
            words.addView(Ui.text(this@MainActivity, summary, 15f, Ui.INK, "bold"))
            if (message.isNotEmpty()) words.addView(Ui.text(this@MainActivity, message, 13.5f, Ui.MUTED))
            if (time != null) words.addView(Ui.text(this@MainActivity, time, 12f, Ui.MUTED))
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private data class RowLook(val icon: Int, val bg: Int, val fg: Int, val label: String)

    /** Every request from this phone: not sent yet, waiting, and answered (newest first). */
    /**
     * ⋮ → My requests. Answered requests can be swiped sideways (or tapped) to archive them; they also move
     * to the archive by themselves after 30 days. [archive]: show the archive instead, where swiping deletes
     * and tapping offers Restore. Waiting and not-yet-sent requests always stay in the main list.
     */
    private fun showMyRequests(archive: Boolean = false) {
        val d = Ui.AppDialog(this, sheet = true)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gap = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) }
        fun archivedCount() = MyRequests.all(this).count { it.archived }
        fun whenText(r: MyRequests.Item) = if (r.status == "waiting") "Asked ${shortDate(r.asked)}" else "Answered ${shortDate(r.answered)}"
        if (!archive) {
            val notSent = Outbox.waitingRequests(this).reversed()
            val sent = MyRequests.all(this).filter { !it.archived }
            var archiveButton: Button? = null
            d.title("My requests")
            if (notSent.isEmpty() && sent.isEmpty()) {
                d.add(Ui.text(this, "Nothing here right now.", 15f, Ui.MUTED))
            } else if (sent.any { it.status != "waiting" }) {
                d.add(Ui.text(this, "Swipe an answered request sideways to archive it, or tap it for more.", 13f, Ui.MUTED), 4)
            }
            notSent.forEach {
                list.addView(requestRow("notsent", it.first, "No connection: it's sent when the phone is online.", "Asked ${shortDate(it.second)}", card = true), LinearLayout.LayoutParams(gap))
            }
            sent.forEach { item ->
                val row = requestRow(item.status, item.summary, item.message, whenText(item), card = true)
                if (item.status != "waiting") {
                    val gone = {
                        list.removeView(row)
                        archiveButton?.text = "Archived (${archivedCount()})"
                    }
                    swipeable(row) { MyRequests.setArchived(this, item.asked, true); gone(); toast("Archived") }
                    row.setOnClickListener {
                        requestOptions(item.summary, "Archive", onMain = { MyRequests.setArchived(this, item.asked, true); gone(); toast("Archived") },
                            onDelete = { MyRequests.delete(this, item.asked); gone(); toast("Deleted") })
                    }
                }
                list.addView(row, LinearLayout.LayoutParams(gap))
            }
            d.add(list, 8)
            archiveButton = d.button("Archived (${archivedCount()})", Ui.Kind.SECONDARY) { it.dismiss(); showMyRequests(archive = true) }
            d.button("Close", Ui.Kind.PRIMARY) { it.dismiss() }
        } else {
            val items = MyRequests.all(this).filter { it.archived }
            d.title("Archived requests")
            d.add(Ui.text(this, if (items.isEmpty()) "Nothing archived." else
                "Swipe one sideways to delete it, or tap it to put it back. Only this phone's copy is deleted.", 13f, Ui.MUTED), 4)
            items.forEach { item ->
                val row = requestRow(item.status, item.summary, item.message, whenText(item), card = true)
                swipeable(row) { MyRequests.delete(this, item.asked); list.removeView(row); toast("Deleted") }
                row.setOnClickListener {
                    requestOptions(item.summary, "Put back", onMain = { MyRequests.setArchived(this, item.asked, false); list.removeView(row); toast("Put back in My requests") },
                        onDelete = { MyRequests.delete(this, item.asked); list.removeView(row); toast("Deleted") })
                }
                list.addView(row, LinearLayout.LayoutParams(gap))
            }
            d.add(list, 8)
            if (items.isNotEmpty()) d.button("Delete all", Ui.Kind.GHOST) { dlg ->
                Ui.AppDialog(this, sheet = false).apply {
                    title("Delete all archived requests?", icon = R.drawable.ic_d_trash, iconBg = Ui.RED_BG, iconFg = Ui.RED_INK)
                    add(Ui.text(this@MainActivity, "Only this phone's copies are deleted.", 14.5f, Ui.MUTED))
                    button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
                    button("Delete", Ui.Kind.DANGER) {
                        it.dismiss(); dlg.dismiss()
                        MyRequests.deleteArchived(this@MainActivity)
                        showMyRequests(archive = true)
                    }
                }.show()
            }
            d.button("Back", Ui.Kind.PRIMARY) { it.dismiss(); showMyRequests() }
        }
        d.show()
    }

    /** Tapping a request: its main action (Archive, or Put back), or Delete. */
    private fun requestOptions(summary: String, main: String, onMain: () -> Unit, onDelete: () -> Unit) {
        Ui.AppDialog(this, sheet = false).apply {
            title(summary)
            button("Delete", Ui.Kind.GHOST) { it.dismiss(); onDelete() }
            button(main, Ui.Kind.PRIMARY) { it.dismiss(); onMain() }
        }.show()
    }

    /**
     * Lets [row] be swiped sideways (past a third of its width) to call [onSwiped]. Only a clearly sideways
     * move starts it, so the list still scrolls up and down; a plain tap still works as a tap.
     */
    private fun swipeable(row: View, onSwiped: () -> Unit) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)   // the list stops scrolling while swiping
                    }
                    if (dragging) {
                        v.translationX = dx
                        v.alpha = 1f - minOf(0.7f, Math.abs(dx) / maxOf(1, v.width))
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    if (dragging && e.actionMasked == android.view.MotionEvent.ACTION_UP && Math.abs(dx) > v.width / 3f) {
                        v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat()).alpha(0f)
                            .setDuration(160).withEndAction { onSwiped() }.start()
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(160).start()
                        val moved = Math.abs(dx) > slop || Math.abs(e.rawY - downY) > slop
                        if (!dragging && !moved && e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** When the phone gets a connection: send what's waiting and fetch the latest lists. */
    private fun watchForConnection() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                main.post { if (!isDestroyed) refreshWhitelist() } // on success this also sends what's waiting
                sendWaiting()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); networkCallback = cb }
    }

    // ---------- clearing data ----------

    /** Stored copies of pages and pictures. Nothing else changes; sites just load fresh. */
    private fun clearCache() {
        web.clearCache(true)
        toast("Cache cleared")
    }

    private fun confirmClearCookies() {
        val scope = siteScope(web.url)
        Ui.AppDialog(this, sheet = false).apply {
            title("Clear cookies and site data?", icon = R.drawable.ic_d_trash, iconBg = Ui.RED_BG, iconFg = Ui.RED_INK)
            var choice = 0
            if (scope == null) {
                // Home page or blocked page: there's no "this site", so it's everything.
                add(Ui.text(this@MainActivity, "You'll be signed out of all websites, and sites forget saved settings. " +
                    "Camera, microphone and location answers are reset too.", 14.5f, Ui.MUTED))
                choice = 1
            } else {
                val explain = Ui.text(this@MainActivity, "Signs you out of this site only.", 13.5f, Ui.MUTED)
                add(Ui.Segmented(this@MainActivity, listOf("Just $scope", "All sites"), 0, true) {
                    choice = it
                    explain.text = if (it == 0) "Signs you out of this site only."
                        else "Signs you out everywhere, and resets camera, microphone and location answers."
                }.view)
                add(explain, 8)
            }
            button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
            button("Clear", Ui.Kind.DANGER) {
                it.dismiss()
                if (scope != null && choice == 0) clearSiteData(scope) else clearCookiesAndSiteData()
            }
        }.show()
    }

    /**
     * The site the open page belongs to, as listed (e.g. "google.com" for mail.google.com), or the
     * page's own host if that site doesn't include subdomains. Null on the home and blocked pages.
     */
    private fun siteScope(url: String?): String? {
        if (url == null || HomePage.isHome(url) || blockedTarget(url) != null) return null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val host = Uri.parse(url).host?.lowercase() ?: return null
        val site = Whitelist.state.sites.firstOrNull { it.matches(host) }
        // Ask for the site itself: m.youtube.com and www.youtube.com both mean youtube.com.
        val bare = host.replace(Regex("^(www|m|mobile)\\.(?=[^.]+\\.[^.]+)"), "")
        return if (site != null && site.subdomains) site.domain.removePrefix("www.") else bare
    }

    /** Clears cookies, stored data and permission answers for one site and its subdomains. */
    private fun clearSiteData(scope: String) {
        fun belongs(host: String) = host == scope || host.endsWith(".$scope")
        val cur = web.url ?: ""
        val curUri = Uri.parse(cur)
        val curHost = curUri.host?.lowercase() ?: scope
        val hosts = linkedSetOf(scope, "www.$scope", curHost).filter { belongs(it) }

        // 1. Stored data the open page can reach: local storage, databases, offline workers.
        web.evaluateJavascript("""
            (function(){ try { localStorage.clear(); sessionStorage.clear(); } catch(e) {}
              try { indexedDB.databases().then(function(d){ d.forEach(function(x){ indexedDB.deleteDatabase(x.name); }); }); } catch(e) {}
              try { navigator.serviceWorker.getRegistrations().then(function(r){ r.forEach(function(x){ x.unregister(); }); }); } catch(e) {}
              try { caches.keys().then(function(k){ k.forEach(function(n){ caches.delete(n); }); }); } catch(e) {}
            })();""".trimIndent(), null)

        // 2. Stored data for every origin of this site the browser knows about.
        val storage = WebStorage.getInstance()
        hosts.forEach { h -> listOf("https://$h", "http://$h").forEach { storage.deleteOrigin(it) } }
        storage.getOrigins { origins ->
            (origins as? Map<*, *>)?.keys?.forEach { o ->
                val h = Uri.parse(o.toString()).host?.lowercase()
                if (h != null && belongs(h)) storage.deleteOrigin(o.toString())
            }
        }

        // 3. Cookies. Android can't list a site's cookies directly, so each cookie the site can see
        //    is overwritten with an expired copy, for every domain and path it might have been set on.
        val cm = CookieManager.getInstance()
        val paths = mutableListOf("/")
        if (belongs(curHost)) {
            var acc = ""
            (curUri.path ?: "").split('/').filter { it.isNotEmpty() }.forEach { seg -> acc += "/$seg"; paths += acc }
        }
        hosts.forEach { h ->
            val domains = generateSequence(h) { it.substringAfter('.', "") }.takeWhile { it.isNotEmpty() && belongs(it) }.toList()
            val urls = listOf("https://$h/") + if (h == curHost && belongs(curHost)) listOf(cur) else emptyList()
            val names = urls.flatMap { u ->
                (cm.getCookie(u) ?: "").split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            }.toSet()
            names.forEach { name ->
                paths.forEach { path ->
                    cm.setCookie("https://$h$path", "$name=; Max-Age=0; Path=$path; Secure")
                    cm.setCookie("http://$h$path", "$name=; Max-Age=0; Path=$path")
                    domains.forEach { d -> cm.setCookie("https://$h$path", "$name=; Max-Age=0; Path=$path; Domain=$d; Secure") }
                }
            }
        }
        cm.flush()

        // 4. Camera, microphone and location answers for this site.
        siteChoices.keys.removeAll { belongs(it.substringBefore('|')) }
        hosts.forEach { h -> GeolocationPermissions.getInstance().clear("https://$h") }

        web.reload()
        toast("Cookies and site data cleared for $scope")
    }

    private fun clearCookiesAndSiteData() {
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()        // local storage, databases, offline data
        GeolocationPermissions.getInstance().clearAll()
        web.clearFormData()
        siteChoices.clear()                             // camera / mic / location answers
        web.clearCache(true)
        web.reload()
        toast("Cookies and site data cleared")
    }

    // ---------- embedded content ----------

    /** Is this request a frame's page (not a picture, script or style)? Frames ask for a web page. */
    private fun isFrameDocument(r: WebResourceRequest): Boolean {
        val h = r.requestHeaders ?: return false
        val dest = h.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        if (dest != null) return dest == "iframe" || dest == "frame"
        return h.entries.firstOrNull { it.key.equals("Accept", true) }?.value?.contains("text/html") == true
    }

    /** May a frame from [host] show inside the page [top]? */
    private fun frameOk(url: String, host: String, top: String?): Boolean =
        Whitelist.isAllowed(url, false) || Whitelist.framesAllowedOn(top) || Whitelist.embedAllowed(top, host)

    /** What shows in a blocked frame's place: a small note, in the app's look. */
    private fun frameBlockedResponse(host: String): WebResourceResponse {
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
        val site = host.removePrefix("www.").replace("<", "")
        val html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<body style='margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;" +
            "background:${hex(Ui.SEG)};color:${hex(Ui.MUTED)};font:600 14px/1.4 sans-serif;text-align:center;padding:8px;box-sizing:border-box'>" +
            "<div>Blocked: content from $site</div></body>"
        return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), java.io.ByteArrayInputStream(html.toByteArray()))
    }

    /** Records a blocked frame's site, and shows the "parts were blocked" bar. (Any thread.) */
    private fun noteBlockedFrame(host: String) {
        val site = host.lowercase().removePrefix("www.")
        if (blockedFrames.add(site)) main.post { showFrameNote() }
    }

    private fun showFrameNote() {
        val top = web.url
        val note = findViewById<View>(R.id.frameNote)
        if (top == null || HomePage.isHome(top) || top.startsWith(BLOCKED_PAGE) || blockedFrames.isEmpty() || top == frameNoteClosedFor) return
        val sites = blockedFrames.toList()
        findViewById<TextView>(R.id.frameNoteText).text = "Parts of this page were blocked (from ${sites.first()}" +
            (if (sites.size > 1) " and ${sites.size - 1} more)" else ")")
        findViewById<View>(R.id.frameNoteAsk).visibility = if (Requests.isSetUp()) View.VISIBLE else View.GONE
        note.visibility = View.VISIBLE
    }

    private fun hideFrameNote() {
        findViewById<View>(R.id.frameNote)?.visibility = View.GONE
    }

    /** "Ask" on the bar: a request for the blocked parts, to show inside this site's pages. */
    private fun showFramesRequest() {
        val top = web.url ?: return
        val site = siteScope(top) ?: return
        val sites = blockedFrames.toList().filter { it != site && !it.endsWith(".$site") }
        if (sites.isEmpty()) { hideFrameNote(); return }
        if (!Requests.isSetUp()) { toast("Requests aren't set up for this app yet"); return }
        val d = Ui.AppDialog(this, sheet = true)
        d.title("Ask for the blocked parts", sub = site)
        d.add(Ui.text(this, "Parts of this page, like a video or a map, come from other sites and were blocked. " +
            "If they're approved, they show inside $site's pages only.", 14.5f, Ui.MUTED), 6)
        d.add(Ui.label(this, if (sites.size == 1) "Blocked content from" else "Which parts? Untick any you don't need"))
        // One tick box per blocked site (all ticked to start with).
        val boxes = sites.map { h ->
            android.widget.CheckBox(this).apply {
                text = h
                isChecked = true
                setTextColor(Ui.INK)
                typeface = Ui.boldFace
                textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                minHeight = Ui.dp(this@MainActivity, 48)
                setPadding(Ui.dp(this@MainActivity, 6), 0, 0, 0)
            }
        }
        d.add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 14).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
            setPadding(Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 2), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 2))
            boxes.forEachIndexed { i, b ->
                if (i > 0) addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE2) }, LinearLayout.LayoutParams(-1, Ui.dp(this@MainActivity, 1)))
                addView(b)
            }
        }, 6)
        val noteField = Ui.field(this, "e.g. the video for my homework",
            type = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        d.add(Ui.label(this, "Why? (optional)"))
        d.add(noteField, 6)
        val (pinRow, pinSwitch) = Ui.switchRow(this, "Approve here with a PIN", "If whoever manages this browser is with you")
        val pinField = Ui.field(this, "Approval PIN",
            type = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD).apply { visibility = View.GONE }
        if (Whitelist.state.pinApproval) {
            d.add(pinRow)
            d.add(pinField, 8)
            pinSwitch.setOnCheckedChangeListener { _, on -> pinField.visibility = if (on) View.VISIBLE else View.GONE }
        }
        d.button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
        d.button("Send", Ui.Kind.PRIMARY) {
            val pin = if (Whitelist.state.pinApproval && pinSwitch.isChecked) pinField.text.toString().trim() else null
            if (pin != null && !Regex("^\\d{4,8}$").matches(pin)) { pinField.error = "The PIN is 4 to 8 digits"; return@button }
            val chosen = sites.filterIndexed { i, _ -> boxes[i].isChecked }
            if (chosen.isEmpty()) { toast("Tick at least one, or tap Cancel"); return@button }
            it.dismiss()
            hideFrameNote()
            sendRequest(Requests.Action.ALLOW, Requests.Scope.SITE, Requests.Media.UNCHANGED, site, top,
                noteField.text.toString().trim(), pin = pin, frames = chosen)
        }
        d.show()
    }

    // ---------- light or dark ----------

    /** ⋮ → Appearance: the phone's own setting (the default), light or dark. Remembered on this phone. */
    private fun showAppearance() {
        val options = listOf("system" to "Phone's setting", "light" to "Light", "dark" to "Dark")
        val current = options.indexOfFirst { it.first == Ui.choice(this) }.coerceAtLeast(0)
        var picked = current
        Ui.AppDialog(this, sheet = false).apply {
            title("Appearance", icon = R.drawable.ic_d_theme)
            add(Ui.Segmented(this@MainActivity, options.map { it.second }, current, true) { picked = it }.view)
            add(Ui.text(this@MainActivity, "\"Phone's setting\" switches between light and dark along with the phone.", 13.5f, Ui.MUTED), 8)
            button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
            button("Done", Ui.Kind.PRIMARY) {
                it.dismiss()
                if (picked != current) {
                    Ui.setChoice(this@MainActivity, options[picked].first)
                    if (Ui.wantsDark(this@MainActivity) != Ui.dark) recreate()   // redraw in the new look (pages are kept)
                }
            }
        }.show()
    }

    /** The phone switched between light and dark: follow it (when Appearance is "Phone's setting"). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Ui.wantsDark(this, newConfig) != Ui.dark) recreate()
    }

    // ---------- content filters ----------

    /**
     * Loads the lists of the filters that are on (they're large, so filters that are off aren't loaded),
     * and fetches fresh copies weekly. Runs at start and after each list update, in case a filter was
     * switched on.
     */
    private fun prepareFilters() {
        val ctx = applicationContext
        io.execute {
            val st = Whitelist.state
            val on = listOfNotNull(Filters.adult.takeIf { st.adult }, Filters.gambling.takeIf { st.gambling }, Filters.malware.takeIf { st.malware })
            on.forEach { it.ensureLoaded(ctx) }
            updateIo.execute { on.forEach { it.refreshIfDue(ctx) } }
        }
    }

    // ---------- temporary access ----------

    /**
     * Adds on-screen time to "time on the site" grants for the open page, warns 5 minutes before
     * temporary access ends, and closes the page (or turns photos and videos off) when it has.
     */
    private fun checkTemporary(countTime: Boolean) {
        val cur = web.url ?: return
        if (countTime) TempTime.add(Whitelist.usingNow(cur).map { it.id }, tempTick)
        Whitelist.endingSoon(cur)?.let { t ->
            if (warned.add(t.id)) toast("Only a few minutes left" + if (t.mode == "use") " of your time on this site." else " on this site.")
        }
        if ((cur.startsWith("https://") || cur.startsWith("http://")) && !HomePage.isHome(cur)) {
            if (!Whitelist.isAllowed(cur)) {
                toast("Time's up for ${Uri.parse(cur).host?.removePrefix("www.") ?: "this site"}")
                showBlocked(cur)
                return
            }
            if (mediaChanged(cur)) { setMediaMode(cur); web.reload() }
        }
        updateUi()
    }

    // ---------- photos and videos ----------

    /** Switches "no photos" and "no videos" on or off for the page at [url]. */
    private fun setMediaMode(url: String?) {
        photosOffHere = Whitelist.photosBlocked(url)
        videosOffHere = Whitelist.videosBlocked(url)
        val loadImages = !photosOffHere
        if (web.settings.loadsImagesAutomatically != loadImages) web.settings.loadsImagesAutomatically = loadImages
    }

    /** Have photos or videos been switched on or off for [url] since the page opened? */
    private fun mediaChanged(url: String?) =
        Whitelist.photosBlocked(url) != photosOffHere || Whitelist.videosBlocked(url) != videosOffHere

    // ---------- first launch: their name ----------

    /**
     * Asks for the user's name the first time the app opens (works offline). It's sent when the phone
     * registers and becomes the phone's name on GitHub and the admin page, where it can be changed.
     */
    private fun askName() {
        val field = Ui.field(this, "e.g. Emma",
            type = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        Ui.AppDialog(this, sheet = false, cancelable = false).apply {
            title("What's your name?", icon = R.drawable.ic_d_user)
            add(Ui.text(this@MainActivity, "So whoever manages this browser knows whose phone this is.", 14.5f, Ui.MUTED))
            add(Ui.label(this@MainActivity, "Your name"))
            add(field, 6)
            button("Continue", Ui.Kind.PRIMARY) {
                val name = field.text.toString().trim()
                if (name.isEmpty()) { field.error = "Type your name"; return@button }
                Device.setName(this@MainActivity, name)
                it.dismiss()
                sendWaiting(registerFirst = true) // registers now (or as soon as it's online)
            }
        }.show()
    }

    // ---------- about this phone ----------

    /** Shows this phone's ID (to add it in the admin page), its name and which lists it uses. */
    private fun showAbout() {
        val id = Device.id(this)
        val st = Whitelist.state
        val copy = {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Phone ID", id))
            toast("Phone ID copied")
        }
        fun row(key: String, value: String): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = Ui.dp(this@MainActivity, 14)
            setPadding(p, Ui.dp(this@MainActivity, 11), p, Ui.dp(this@MainActivity, 11))
            addView(Ui.text(this@MainActivity, key, 14f, Ui.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.text(this@MainActivity, value, 14.5f, Ui.INK, "bold").apply { gravity = android.view.Gravity.END },
                LinearLayout.LayoutParams(0, -2, 1.4f))
        }
        fun table(rows: List<LinearLayout>) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 16).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
            rows.forEachIndexed { i, r ->
                if (i > 0) addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE2) }, LinearLayout.LayoutParams(-1, Ui.dp(this@MainActivity, 1)))
                addView(r)
            }
        }
        fun f(on: Boolean, filter: Filter) = if (on) "On · ${filter.blockedCount.get()} blocked" else "Off"
        val phone = mutableListOf(
            row("Phone ID", id),
            row("Name", (st.deviceName ?: Device.name(this) ?: "Not set") + if (st.registered) "" else " (not registered yet)"),
            row("Lists", st.listNames.ifEmpty { listOf("none") }.joinToString(", ")),
            row("Allowed sites", st.allow.distinct().size.toString()))
        val waiting = Outbox.count(this)
        if (waiting > 0) phone += row("Waiting to send", "$waiting (${Outbox.lastProblem ?: "sends when online"})")
        phone += row("App version", BuildConfig.VERSION_NAME)
        Ui.AppDialog(this, sheet = true).apply {
            title("About this phone", icon = R.drawable.ic_d_user)
            add(table(phone))
            add(Ui.label(this@MainActivity, "Filters"))
            add(table(listOf(
                row("Ads and trackers", if (st.adblock) "On · ${AdBlock.blockedCount.get()} blocked" else "Off"),
                row("Adult content", f(st.adult, Filters.adult)),
                row("Gambling", f(st.gambling, Filters.gambling)),
                row("Malware and scams", f(st.malware, Filters.malware)))), 6)
            button("Copy ID", Ui.Kind.SECONDARY) { copy() }
            button("Close", Ui.Kind.PRIMARY) { it.dismiss() }
        }.show()
    }
}
