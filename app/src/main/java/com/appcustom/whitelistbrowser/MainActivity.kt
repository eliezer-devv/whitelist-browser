package com.appcustom.whitelistbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
    @Volatile private var mediaOffHere = false

    // Told by Android when the phone gets a connection, so waiting items go out and lists update.
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // Per-site answers to "allow camera/mic/location?", remembered until the app is closed.
    private val siteChoices = mutableMapOf<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        pageTitle = findViewById(R.id.pageTitle)
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
                    // A tapped "Photo blocked" placeholder: ask for photos and videos on this page.
                    if (request.isForMainFrame && request.hasGesture() && mediaOffHere) {
                        showRequestDialog(Requests.Action.ALLOW, view?.url, mediaBack = true)
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
                if (!request.isForMainFrame && Whitelist.framesAllowedOn(view?.url)) return false
                if (request.isForMainFrame) showBlocked(url)
                return true
            }

            // Safety net: never fetch a non-listed page. Images/scripts/CDNs are not filtered,
            // so whitelisted sites keep working.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && request.url.host == HomePage.HOST) {
                    return HomePage.respond(this@MainActivity, request.url.path ?: "")
                }
                // "No photos or videos" pages: media files and players get an empty answer.
                if (request != null && !request.isForMainFrame && mediaOffHere && MediaBlock.isMedia(request)) {
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
                if (request != null && request.isForMainFrame && !Whitelist.isAllowed(request.url.toString())) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(),
                        ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                setMediaMode(url)
                if (url != null && !url.startsWith(BLOCKED_PAGE) && !HomePage.isHome(url) && trail.lastOrNull() != url) trail += url
                if (url != null && !Whitelist.isAllowed(url)) {
                    view?.stopLoading()
                    showBlocked(url)
                }
                updateUi()
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (mediaOffHere) view?.evaluateJavascript(MediaBlock.PAGE_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (mediaOffHere) view?.evaluateJavascript(MediaBlock.PAGE_SCRIPT, null)
                updateUi()
            }

            // Sites like YouTube change pages without reloading. Check those page changes too.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null && !Whitelist.isAllowed(url)) showBlocked(url)
                // Sites that change pages without reloading: a new page may have a different media setting.
                val before = mediaOffHere
                setMediaMode(url)
                if (mediaOffHere && !before) view?.evaluateJavascript(MediaBlock.PAGE_SCRIPT, null)
                if (!mediaOffHere && before) view?.reload()
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
        main.post { web.loadUrl("$BLOCKED_PAGE#reason=$reason&url=${Uri.encode(url)}$cats") }
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
                if (Whitelist.mediaBlocked(web.url) != mediaOffHere) { setMediaMode(web.url); web.reload() }
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
        if (narrow) {                              // room for the site's name: these two go in the ⋮ menu
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
        val mediaNote = tempNote + if (mediaOffHere) "Photos and videos are off on this page. " else ""
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
            if (narrow) {                          // on small screens, Forward and Reload live here
                menu.add(0, 10, 0, "Forward").isEnabled = web.canGoForward()
                menu.add(0, 11, 0, "Reload")
            }
            menu.add(0, 4, 0, "Ask for a new site")
            menu.add(0, 9, 0, "My requests")
            menu.add(0, 5, 1, "Ask to block").isEnabled = onRealSite
            if (mediaOffHere && onRealSite) menu.add(0, 8, 1, "Ask for photos and videos")
            menu.add(0, 6, 2, "Clear cache")
            menu.add(0, 7, 3, "Clear cookies and site data")
            menu.add(0, 1, 4, "Check for app update")
            menu.add(0, 2, 5, "Check list now")
            menu.add(0, 3, 6, "About this phone")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    4 -> showRequestDialog(Requests.Action.ALLOW, null)
                    9 -> showMyRequests()
                    10 -> if (web.canGoForward()) web.goForward()
                    11 -> web.reload()
                    6 -> clearCache()
                    3 -> showAbout()
                    7 -> confirmClearCookies()
                    5 -> showRequestDialog(Requests.Action.BLOCK, cur)
                    8 -> showRequestDialog(Requests.Action.ALLOW, cur, mediaBack = true)
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
                else AlertDialog.Builder(this)
                    .setMessage("This page wants to open ${r.appName ?: "another app"}.")
                    .setPositiveButton("Open") { _, _ -> launch() }
                    .setNegativeButton("Cancel", null)
                    .show()
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
        AlertDialog.Builder(this)
            .setTitle("$host wants to use your $what")
            .setPositiveButton("Allow") { _, _ -> answer(true) }
            .setNegativeButton("Block") { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .show()
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
                                  route: Passthrough.Route? = null) {
        if (!Requests.isSetUp()) {
            toast("Requests aren't set up for this app yet")
            return
        }
        val density = resources.displayMetrics.density
        val pad = ((if (narrow) 12 else 20) * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(introText("Your request goes to whoever manages this browser."))

        val siteDomain = pageUrl?.let { siteScope(it) }          // "coolmathgames.com", "google.com" ...
        val pageKey = pageUrl?.let { Whitelist.pageKey(it) }     // "youtube.com/watch?v=abc"
        // A site's front page is the same as "whole site", so only offer the choice for deeper pages.
        val canChoose = siteDomain != null && pageKey != null && pageKey.substringAfter('/').isNotEmpty()

        // On a content filter's list? Say which, and make the button "Ask anyway" (the owner sees the list too).
        val filteredNow = if (action == Requests.Action.ALLOW && !mediaBack && siteDomain != null) Whitelist.filteredAs(siteDomain) else emptyList()
        val filterWarning = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFD93025.toInt())
            setPadding(0, 0, 0, (8 * density).toInt())
            visibility = if (filteredNow.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            text = filterWarningText(siteDomain ?: "", filteredNow, pinNote = Whitelist.state.pinApproval)
        }
        box.addView(filterWarning)

        // The "Approve here with a PIN" option (added to the screen further down, below the note).
        val pinCheck = android.widget.CheckBox(this).apply { text = "Approve here with a PIN" }
        val pinField = EditText(this).apply {
            hint = "Approval PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            visibility = android.view.View.GONE
        }

        val siteField = EditText(this).apply {
            hint = "Website, e.g. scratch.mit.edu"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        if (siteDomain == null) box.addView(siteField)
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
                    filterWarning.visibility = android.view.View.GONE
                    if (Whitelist.state.pinApproval) pinCheck.visibility = android.view.View.VISIBLE
                    (siteField.tag as? android.widget.Button)?.text = "Send"
                }
            }
        })
        // "Checking scratch.mit.edu…" while a typed site is being checked.
        val checking = TextView(this).apply { textSize = 13f; visibility = android.view.View.GONE }
        if (siteDomain == null) box.addView(checking)

        // The addresses the link passes through on the way, so it's clear what else must be opened.
        val hops = route?.hops.orEmpty().filter { it != pageUrl }
        if (hops.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "This link passes through:\n" + hops.joinToString("\n") { "→ " + (Whitelist.pageKey(it)?.substringBefore('?') ?: it) } +
                    "\n\nThose are included in your request."
                textSize = 13f
                setPadding(0, 0, 0, (8 * density).toInt())
            })
        }

        val pageOption = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Just this page\n$pageKey"
            isChecked = true
        }
        val siteOption = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Whole site\n$siteDomain"
        }
        if (canChoose) {
            val group = android.widget.RadioGroup(this)
            listOf(pageOption, siteOption).forEach { rb ->
                rb.setPadding(rb.paddingLeft, (6 * density).toInt(), rb.paddingRight, (6 * density).toInt())
                group.addView(rb)
            }
            box.addView(group)
        }

        // "For how long?": always, or just for a while, chosen on two scroll wheels (hours and minutes),
        // like the admin page. For opening, and for asking for photos and videos back.
        val always = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId(); text = "Always"; isChecked = true
        }
        val forAWhile = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId(); text = "Just for a while"
        }
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
            val gap = ((if (narrow) 6 else 10) * density).toInt()
            addView(hoursWheel)
            addView(TextView(this@MainActivity).apply { text = if (narrow) "h" else "hours"; setPadding(gap / 2, 0, gap * 2, 0) })
            addView(minutesWheel)
            addView(TextView(this@MainActivity).apply { text = "min"; setPadding(gap / 2, 0, 0, 0) })
            visibility = android.view.View.GONE
        }
        if (action == Requests.Action.ALLOW) {
            box.addView(TextView(this).apply { text = "For how long?"; setPadding(0, (8 * density).toInt(), 0, 0) })
            box.addView(android.widget.RadioGroup(this).apply {
                orientation = if (narrow) android.widget.RadioGroup.VERTICAL else android.widget.RadioGroup.HORIZONTAL
                addView(always); addView(forAWhile)
                setOnCheckedChangeListener { _, checked ->
                    wheels.visibility = if (checked == forAWhile.id) android.view.View.VISIBLE else android.view.View.GONE
                }
            })
            box.addView(wheels)
        }

        // Photos and videos: opening "without photos and videos", or blocking "only photos and videos".
        val withoutMedia = android.widget.CheckBox(this).apply { text = "Without photos and videos" }
        val blockAll = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId(); text = "Block it completely"; isChecked = true
        }
        val blockMediaOnly = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId(); text = "Only block photos and videos"
        }
        if (!mediaBack) {
            if (action == Requests.Action.ALLOW) {
                box.addView(withoutMedia)
            } else {
                val group = android.widget.RadioGroup(this)
                listOf(blockAll, blockMediaOnly).forEach { group.addView(it) }
                box.addView(group)
            }
        }

        val noteField = EditText(this).apply {
            hint = "Why? (optional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(noteField)

        // Approve here with a PIN: when whoever manages this browser is with them. Only offered if a PIN is
        // set for this phone. The phone doesn't check it: GitHub does, then removes it from the request.
        if (Whitelist.state.pinApproval && filteredNow.isEmpty()) {   // the PIN can't open filtered sites
            box.addView(pinCheck)
            box.addView(pinField)
            pinCheck.setOnCheckedChangeListener { _, on ->
                pinField.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
                if (on) pinField.requestFocus()
            }
        }

        val verb = if (action == Requests.Action.ALLOW) "open" else "block"
        val title = when {
            mediaBack -> "Ask for photos and videos"
            siteDomain == null -> "Ask for a new site"
            canChoose -> "Ask to $verb"
            else -> "Ask to $verb $siteDomain"
        }
        val dialog = AlertDialog.Builder(this)
            .titled(title, box)
            .setView(scrollable(box))           // scrolls on small screens, buttons stay on screen
            .setPositiveButton("Send", null) // set below so bad input doesn't close the dialog
            .setNegativeButton("Cancel", null)
            .create()
            .fitAboveKeyboard()
        dialog.setOnShowListener {
            siteField.tag = dialog.getButton(AlertDialog.BUTTON_POSITIVE) // so editing the address can reset "Send anyway"
            if (filteredNow.isNotEmpty()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "Ask anyway"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val domain = siteDomain ?: Whitelist.normalize(siteField.text.toString())
                if (domain == null) {
                    siteField.error = "Type a website address"
                    return@setOnClickListener
                }
                val scope = if (canChoose && pageOption.isChecked) Requests.Scope.PAGE else Requests.Scope.SITE
                val subject = if (scope == Requests.Scope.PAGE) pageKey!! else domain
                val media = when {
                    mediaBack -> Requests.Media.ON
                    action == Requests.Action.ALLOW && withoutMedia.isChecked -> Requests.Media.OFF
                    action == Requests.Action.BLOCK && blockMediaOnly.isChecked -> Requests.Media.OFF
                    else -> Requests.Media.UNCHANGED
                }
                val minutes = if (action == Requests.Action.ALLOW && forAWhile.isChecked)
                    hoursWheel.value * 60 + minutesWheel.value * 5 else 0
                if (action == Requests.Action.ALLOW && forAWhile.isChecked && minutes == 0) {
                    toast("Choose how long on the wheels, or pick Always") // the box stays open
                    return@setOnClickListener
                }
                val pin = if (pinCheck.isChecked && pinCheck.visibility == android.view.View.VISIBLE) pinField.text.toString().trim() else null
                if (pin != null && !Regex("^\\d{4,8}$").matches(pin)) {
                    pinField.error = "The PIN is 4 to 8 digits"
                    pinField.requestFocus()
                    return@setOnClickListener
                }
                if (pin == null && Requests.recentlySent(this, action, "$subject|${media.word}")) {
                    toast("You already asked about $subject. Wait for an answer.")
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val note = noteField.text.toString().trim()
                // "Send anyway" after the site couldn't be found: send it, marked as not found.
                if (siteDomain == null && sendAnyway == domain) {
                    dialog.dismiss()
                    sendRequest(action, scope, media, domain, null, note, hops, minutes, unverified = true, pin = pin)
                    return@setOnClickListener
                }
                if (siteDomain != null) {
                    dialog.dismiss()
                    sendRequest(action, scope, media, domain, pageUrl, note, hops, minutes, pin = pin)
                    return@setOnClickListener
                }
                // A typed site on a content filter's list: say which, and ask them to confirm first.
                val typedFiltered = if (action == Requests.Action.ALLOW) Whitelist.filteredAs(domain) else emptyList()
                if (typedFiltered.isNotEmpty() && askAnyway != domain) {
                    filterWarning.text = filterWarningText(domain, typedFiltered, pinNote = Whitelist.state.pinApproval)
                    filterWarning.visibility = android.view.View.VISIBLE
                    pinCheck.isChecked = false
                    pinCheck.visibility = android.view.View.GONE
                    askAnyway = domain
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "Ask anyway"
                    return@setOnClickListener
                }
                // A typed site: check it exists before sending. The box stays open while checking.
                val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                send.isEnabled = false
                checking.text = "Checking $domain…"
                checking.visibility = android.view.View.VISIBLE
                traceIo.execute {
                    val result = SiteCheck.check(applicationContext, domain)
                    main.post {
                        if (isDestroyed || !dialog.isShowing) return@post
                        send.isEnabled = true
                        checking.visibility = android.view.View.GONE
                        if (result == SiteCheck.Result.NOT_FOUND) {
                            // Real sites can look missing too (a Wi-Fi filter, a school-only site),
                            // so offer to send it anyway.
                            siteField.error = "Couldn't find $domain. Check the spelling, or tap Send anyway if you're sure it's right."
                            siteField.requestFocus()
                            sendAnyway = domain
                            send.text = "Send anyway"
                        } else {
                            // Found, or no internet to check with: the request is sent (or saved until online).
                            dialog.dismiss()
                            sendRequest(action, scope, media, domain, null, note, hops, minutes, pin = pin)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Wraps a dialog's content so it scrolls when the screen is too short for it (small phones, keyboard
     * up), without ever pushing the dialog's buttons off the screen.
     */
    private fun scrollable(content: View) = MaxHeightScrollView(this, reservedDp = if (narrow) 96 else 170).apply { addView(content) }

    /**
     * A dialog's title: in the usual title bar, or on small screens inside the scrolling content (so with
     * the keyboard up there's still room for the content and the buttons).
     */
    private fun AlertDialog.Builder.titled(title: String, box: LinearLayout): AlertDialog.Builder {
        if (!narrow) return setTitle(title)
        box.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
        }, 0)
        return this
    }

    /** A dialog's intro line, inside its scrolling content (one scroll area instead of two). */
    private fun introText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
    }

    /** Keeps a dialog above the keyboard: it shrinks (and its content scrolls) instead of being covered. */
    private fun AlertDialog.fitAboveKeyboard(): AlertDialog {
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return this
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
                            minutes: Int = 0, unverified: Boolean = false, pin: String? = null) {
        toast(if (pin != null) "Checking the PIN" else "Sending request")
        updateIo.execute {
            val outcome = runCatching {
                // Which content filters list it (so you see that before approving).
                val filtered = if (action == Requests.Action.ALLOW) Whitelist.filteredAs(domain) else emptyList()
                val id = Requests.queue(applicationContext, action, scope, media, domain, pageUrl, note, hops, minutes, unverified, filtered, pin)
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
                    action == Requests.Action.BLOCK -> "Request sent."
                    media == Requests.Media.ON -> "Request sent. If it's approved, photos and videos come back by themselves."
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

    private fun statusIcon(status: String) = when (status) {
        "approved" -> "✅"
        "denied" -> "❌"
        "waiting" -> "⏳"
        "failed" -> "⚠️"
        else -> "•"
    }

    private fun shortDate(t: Long): String =
        java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    /** Pops up answers that arrived since the user last looked. */
    private fun showNewAnswers() {
        val fresh = MyRequests.takeNewAnswers(this)
        if (fresh.isEmpty()) return
        // Approved: the updated list may still be on its way (a minute or two), so keep checking quickly.
        if (fresh.any { it.status == "approved" }) fastChecks(5)
        val text = fresh.joinToString("\n\n") { "${statusIcon(it.status)} ${it.summary}\n${it.message}" }
        AlertDialog.Builder(this)
            .setTitle(if (fresh.size == 1) "Answer to your request" else "Answers to your requests")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("My requests") { _, _ -> showMyRequests() }
            .show()
    }

    /** Every request from this phone: not sent yet, waiting, and answered (newest first). */
    private fun showMyRequests() {
        val notSent = Outbox.waitingRequests(this).reversed().map {
            "📤 ${it.first}\nNot sent yet (no connection).\nAsked ${shortDate(it.second)}."
        }
        val sent = MyRequests.all(this).map {
            val when_ = if (it.status == "waiting") "Asked ${shortDate(it.asked)}." else "Answered ${shortDate(it.answered)}."
            val msg = if (it.status == "waiting") it.message.ifEmpty { "Waiting for an answer." } else it.message
            "${statusIcon(it.status)} ${it.summary}\n$msg\n$when_"
        }
        val all = notSent + sent
        AlertDialog.Builder(this)
            .setTitle("My requests")
            .setMessage(if (all.isEmpty()) "You haven't asked for anything yet." else all.joinToString("\n\n"))
            .setPositiveButton("Close", null)
            .show()
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
        val builder = AlertDialog.Builder(this).setTitle("Clear cookies and site data?")
        if (scope == null) {
            // Home page or blocked page: there's no "this site", so it's everything.
            builder.setMessage("You'll be signed out of all websites, and sites forget saved settings. " +
                "Camera, microphone and location answers are reset too.")
                .setPositiveButton("Clear") { _, _ -> clearCookiesAndSiteData() }
        } else {
            var choice = 0
            val options = arrayOf<CharSequence>(
                "Just $scope\nSigns you out of this site only",
                "All sites\nSigns you out everywhere and resets camera, microphone and location answers"
            )
            builder.setSingleChoiceItems(options, 0) { _, which -> choice = which }
                .setPositiveButton("Clear") { _, _ ->
                    if (choice == 0) clearSiteData(scope) else clearCookiesAndSiteData()
                }
        }
        builder.setNegativeButton("Cancel", null).show()
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
            if (Whitelist.mediaBlocked(cur) != mediaOffHere) { setMediaMode(cur); web.reload() }
        }
        updateUi()
    }

    // ---------- photos and videos ----------

    /** Switches "no photos or videos" mode on or off for the page at [url]. */
    private fun setMediaMode(url: String?) {
        val off = Whitelist.mediaBlocked(url)
        mediaOffHere = off
        if (web.settings.loadsImagesAutomatically == off) web.settings.loadsImagesAutomatically = !off
    }

    // ---------- first launch: their name ----------

    /**
     * Asks for the user's name the first time the app opens (works offline). It's sent when the phone
     * registers and becomes the phone's name on GitHub and the admin page, where it can be changed.
     */
    private fun askName() {
        val pad = ((if (narrow) 12 else 20) * resources.displayMetrics.density).toInt()
        val field = EditText(this).apply {
            hint = "Your name"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(introText("So whoever manages this browser knows whose phone this is."))
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .titled("What's your name?", box)
            .setView(scrollable(box))
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .create()
            .fitAboveKeyboard()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = field.text.toString().trim()
                if (name.isEmpty()) { field.error = "Type your name"; return@setOnClickListener }
                Device.setName(this, name)
                dialog.dismiss()
                sendWaiting(registerFirst = true) // registers now (or as soon as it's online)
            }
        }
        dialog.show()
    }

    // ---------- about this phone ----------

    /** Shows this phone's ID (to add it in the admin page), its name and which lists it uses. */
    private fun showAbout() {
        val id = Device.id(this)
        val st = Whitelist.state
        val msg = buildString {
            appendLine("Phone ID: $id")
            appendLine("Name: ${st.deviceName ?: Device.name(this@MainActivity) ?: "not set"}" + if (st.registered) "" else " (not registered yet)")
            appendLine("Lists: ${st.listNames.ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine("Allowed sites: ${st.allow.distinct().size}")
            appendLine("Ad blocking: " + if (st.adblock) "on (${AdBlock.blockedCount.get()} blocked since the app opened)" else "off")
            fun f(on: Boolean, filter: Filter) = if (on) "on (${filter.blockedCount.get()} blocked)" else "off"
            appendLine("Adult content filter: ${f(st.adult, Filters.adult)}")
            appendLine("Gambling filter: ${f(st.gambling, Filters.gambling)}")
            appendLine("Malware filter: ${f(st.malware, Filters.malware)}")
            val waiting = Outbox.count(this@MainActivity)
            if (waiting > 0) appendLine("Waiting to send: $waiting (${Outbox.lastProblem ?: "sends when online"})")
            append("App version: ${BuildConfig.VERSION_NAME}")
        }
        AlertDialog.Builder(this)
            .setTitle("About this phone")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy ID") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Phone ID", id))
                toast("Phone ID copied")
            }
            .show()
    }
}
