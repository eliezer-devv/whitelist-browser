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
import android.view.ViewGroup
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
        private const val FILE_REQUEST = 43          // choosing files to upload to a website
    }

    private lateinit var web: WebView
    private lateinit var loadBar: View
    // What was blocked on the open page (for the log, when it's loaded).
    private val pageAds = java.util.concurrent.atomic.AtomicInteger()
    private val pageMedia = java.util.concurrent.atomic.AtomicInteger()
    private val pageFrames = java.util.concurrent.atomic.AtomicInteger()
    private val pageScriptErrors = java.util.concurrent.atomic.AtomicInteger()
    private var pageStartedAt = 0L
    /**
     * A blocked ad or tracker: an empty reply that allows the page that asked (else the page reports a "CORS" error
     * for every blocked request; AdGuard's blocking doesn't cause those).
     */
    private fun blockedAd(request: WebResourceRequest): WebResourceResponse {
        pageAds.incrementAndGet()
        val r = AdBlock.blockedResponse()
        val origin = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Origin", true) }?.value
        r.responseHeaders = if (origin != null && origin != "null")
            mapOf("Access-Control-Allow-Origin" to origin, "Access-Control-Allow-Credentials" to "true")
        else mapOf("Access-Control-Allow-Origin" to "*")
        return r
    }

    /** The loading bar: shown at once when a page starts opening, filling as it loads, gone when it's done. */
    private fun showLoading(percent: Int) {
        if (percent >= 100) {
            if (loadBar.visibility != View.VISIBLE) return
            loadBar.animate().scaleX(1f).setDuration(120).withEndAction {
                loadBar.animate().alpha(0f).setDuration(200).withEndAction { loadBar.visibility = View.GONE }.start()
            }.start()
            return
        }
        if (loadBar.visibility != View.VISIBLE) { loadBar.visibility = View.VISIBLE; loadBar.alpha = 1f; loadBar.scaleX = 0f }
        loadBar.animate().cancel()
        loadBar.alpha = 1f
        loadBar.animate().scaleX(maxOf(percent, 8) / 100f).setDuration(200).start()
    }
    private lateinit var pageTitle: TextView
    private lateinit var status: TextView
    private lateinit var backBtn: ImageButton
    private lateinit var forwardBtn: ImageButton

    private val main = Handler(Looper.getMainLooper())
    // (Each drops work handed to it after the screen has closed, rather than crashing the app.)
    private val io = quietQueue()                                // whitelist fetches
    private val updateIo = quietQueue()                          // app updates, requests, ad list downloads
    private val traceIo = quietQueue()                           // finding where stopped links lead
    private lateinit var updateBanner: TextView
    private var availableUpdate: Updater.Release? = null
    private var updating = false
    private var isResumedNow = false
    private val refreshTask = Runnable { refreshWhitelist() }

    // Embedded content blocked on the open page: the sites it came from (for the "parts were blocked" bar).
    @Volatile private var topUrl: String? = null
    // site -> the exact addresses blocked from it (host + path, no "?..."), so one can be asked for on its own.
    private val blockedFrames: MutableMap<String, MutableSet<String>> = java.util.Collections.synchronizedMap(LinkedHashMap())
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
    @Volatile private var soundOffHere = false
    private val mediaOffHere get() = photosOffHere || videosOffHere || soundOffHere
    /** What's off on the open page, e.g. "photos,sound". */
    private val offKinds get() = listOfNotNull("photos".takeIf { photosOffHere }, "videos".takeIf { videosOffHere },
        "sound".takeIf { soundOffHere }).joinToString(",")

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
        loadBar = findViewById<View>(R.id.loadBar).apply { setBackgroundColor(Ui.ACCENT); pivotX = 0f; scaleX = 0f }
        web.setBackgroundColor(Ui.PAGE)            // no white flash between pages in dark mode
        findViewById<View>(R.id.root).setBackgroundColor(Ui.BAR)
        window.statusBarColor = Ui.BAR
        window.navigationBarColor = Ui.BAR
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
        updateBanner.setOnClickListener {
            val r = availableUpdate
            if (r != null) startUpdate(r)
            else updateIo.execute {                               // one already downloaded and waiting: installed now
                val apk = Updater.waiting(applicationContext) ?: return@execute
                main.post { showBanner("Installing the update: the app closes for a moment") }
                runCatching { Updater.install(applicationContext, apk) }.onFailure { AppLog.e("Update", "Installing failed", it) }
            }
        }

        TempTime.load(this)       // time already used on "time on the site" temporary access
        AppLog.start(applicationContext)                      // the rolling log (About this phone → Share log)
        CrashLog.install(applicationContext)                  // a crash is recorded (About this phone shows it)
        if (CrashLog.takeUnsent(applicationContext)) main.postDelayed({ sendLog("after a crash", quiet = true) }, 5_000)
        if (intent?.action == AdminAlerts.ACTION_OPEN_ADMIN) main.post { openAdmin() }   // a notification tapped
        Whitelist.loadCache(this) // last known list, so it works offline
        val stopFilter = android.content.IntentFilter(PlaybackService.ACTION_MEDIA)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(stopReceiver, stopFilter)       // (before Android 13, only this app's own anyway)
        AdRules.start(applicationContext)                      // AdGuard's rules: once per app run (updated daily)
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
        else if (!openLinkFrom(intent)) goHome()                // opened with a link from another app: that link
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        AppLog.i("App", "Opened")
        InstallReceiver.showConfirm = { confirm -> runCatching { startActivity(confirm) }.onFailure { AppLog.e("Update", "Couldn't show the installer", it) } }
        installWaitingUpdate()
        askForAdminNotifications()
        main.removeCallbacks(soundCheck)                      // (the playing notification stays while something plays)
        web.settings.mediaPlaybackRequiresUserGesture = true  // pages start sound only after a tap again
        player?.settings?.mediaPlaybackRequiresUserGesture = true
        main.removeCallbacks(playCheck); main.postDelayed(playCheck, 3_000)
        main.removeCallbacks(adminTick); main.postDelayed(adminTick, 2_000)
        refreshWhitelist() // check GitHub every time the app comes to the front
        AdRules.start(applicationContext)                   // AdGuard's ad rules: once a day (checked at most hourly)
        maybeAutoCheckForUpdate()
        if (Config.LOCK_TASK) runCatching { startLockTask() }
        main.removeCallbacks(tempTask)
        main.postDelayed(tempTask, tempTick)
    }

    override fun onPause() {
        isResumedNow = false
        InstallReceiver.showConfirm = null
        AppLog.i("App", "Left")
        main.removeCallbacks(refreshTask)
        main.removeCallbacks(tempTask)
        keepPlayingIfSound()
        super.onPause()
    }

    // ---------- the player tab: a site's sound keeps playing while you browse elsewhere ----------

    private var player: WebView? = null          // the playing page, kept running out of sight
    private var playerSite = ""
    private var playerIdleChecks = 0
    private var mainPlaying = false              // is the open page playing sound? (checked every few seconds)
    private var playerBar: LinearLayout? = null

    /** Leaving the open page for [target] (another site) while it plays: it should move to the player tab. */
    private fun leavesPlayingSite(target: String?): Boolean {
        if (!mainPlaying || target == null) return false
        val here = siteKeyOf(web.url) ?: return false
        val there = siteKeyOf(target)
        return there == null || (there != here && !there.endsWith(".$here") && !here.endsWith(".$there"))
    }

    /** Moves the playing page to the player tab (it keeps playing), and opens a fresh tab to browse on. */
    private fun moveToPlayer() {
        val old = web
        val box = old.parent as? android.widget.FrameLayout ?: return
        stopPlayer()                                           // only one at a time
        playerSite = siteKeyOf(old.url) ?: "a website"
        AppLog.i("Sound", "$playerSite moved to the player tab (keeps playing)")
        // The player: no navigating on its own, no pop-ups, ad blocking by its own site.
        val site = Uri.parse(old.url ?: "").host
        old.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || request.isForMainFrame) return null
                val host = request.url.host ?: return null
                val groups = adGroups(site)
                if ("ads" in groups && AdBlock.isAd(host, Whitelist.state.adblockExceptions)) return blockedAd(request)
                if (groups.isNotEmpty() && !adExcepted(host) && AdRules.blocks(request.url, site, groups)) return blockedAd(request)
                return null
            }
        }
        old.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: android.webkit.JsResult?) = true.also { r?.cancel() }
            override fun onJsConfirm(v: WebView?, u: String?, m: String?, r: android.webkit.JsResult?) = true.also { r?.cancel() }
            override fun onJsPrompt(v: WebView?, u: String?, m: String?, d: String?, r: android.webkit.JsPromptResult?) = true.also { r?.cancel() }
            override fun onJsBeforeUnload(v: WebView?, u: String?, m: String?, r: android.webkit.JsResult?) = true.also { r?.confirm() }
        }
        old.layoutParams = android.widget.FrameLayout.LayoutParams(1, 1)   // out of sight, still running
        old.alpha = 0f
        old.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        player = old
        playerIdleChecks = 0
        // A fresh tab to browse on (under the loading bar).
        val fresh = WebView(this)
        box.addView(fresh, box.indexOfChild(loadBar), android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = fresh
        mainPlaying = false
        setupWebView()
        showPlayerBar()
    }

    /** "Open" on the player bar: back to the playing page (the tab you were browsing in closes). */
    private fun openPlayer() {
        val p = player ?: return
        val cur = web
        val box = cur.parent as? android.widget.FrameLayout ?: return
        player = null
        hidePlayerBar()
        p.layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        p.alpha = 1f
        p.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        box.removeView(cur)
        runCatching { cur.stopLoading(); cur.destroy() }
        web = p
        setupWebView()                                         // the full browser again
        mainPlaying = true
        updateUi()
    }

    /** "Stop" on the player bar (or the notification): the playing page closes. */
    private fun stopPlayer() {
        val p = player ?: return
        player = null
        hidePlayerBar()
        runCatching {
            p.stopLoading(); p.loadUrl("about:blank")
            (p.parent as? ViewGroup)?.removeView(p)
            p.destroy()
        }
        if (!isResumedNow) { AppLog.i("Sound", "Player tab closed: the notification goes"); PlaybackService.stop(this) }
    }

    /** The slim bar under the top bar while the player tab plays: "Playing from youtube.com", Open, Stop. */
    private fun showPlayerBar() {
        hidePlayerBar()
        val box = web.parent as? View ?: return
        val root = box.parent as? LinearLayout ?: return
        fun dp(v: Int) = Ui.dp(this, v)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.SOFT)
            setPadding(dp(14), dp(2), dp(4), dp(2))
            minimumHeight = dp(44)
        }
        bar.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_d_sound)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT_TEXT)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
        bar.addView(Ui.text(this, "Playing from $playerSite", 14f, Ui.INK, "bold").apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun action(label: String, run: () -> Unit) = bar.addView(Button(this).apply {
            text = label; isAllCaps = false; typeface = Ui.boldFace; setTextColor(Ui.ACCENT_TEXT); background = null
            minWidth = dp(56); minimumWidth = dp(56); setOnClickListener { run() }
        })
        action("Open") { openPlayer() }
        action("Stop") { stopPlayer() }
        root.addView(bar, root.indexOfChild(box), LinearLayout.LayoutParams(-1, -2))
        playerBar = bar
    }

    private fun hidePlayerBar() {
        playerBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        playerBar = null
    }

    /** Every few seconds while the app is open: is the page playing? And has the player stopped for a minute? */
    private val playCheck = object : Runnable {
        override fun run() {
            if (!isResumedNow || isDestroyed) return
            web.evaluateJavascript("(window.__wlbPlaying ? window.__wlbPlaying() : 0)") { n -> mainPlaying = (n?.trim()?.toIntOrNull() ?: 0) > 0 }
            // What's playing (the player tab's, or this page's): the notification shows while it plays.
            mediaInfo(soundView) { info -> lastInfo = info; mediaTick(info) }
            player?.evaluateJavascript("(window.__wlbPlaying ? window.__wlbPlaying() : 0)") { n ->
                if ((n?.trim()?.toIntOrNull() ?: 0) > 0) playerIdleChecks = 0
                else if (++playerIdleChecks >= 20) stopPlayer()     // nothing for a minute: it closes
            }
            main.postDelayed(this, 3_000)
        }
    }

    /**
     * An update downloaded but not installed yet (waiting for sound to stop, or Android wanted to ask first): the
     * banner says so; tapping it installs it.
     */
    private fun installWaitingUpdate() {
        updateIo.execute {
            if (Updater.waiting(applicationContext) == null) return@execute
            main.post { if (!isDestroyed && !updating) showBanner("An update is ready. Tap to install it (the app closes for a moment).") }
        }
    }

    // ---------- admin phones ----------

    /** While the app is open, an admin phone looks for new requests, logs and crashes every minute. */
    private val adminTick = object : Runnable {
        override fun run() {
            if (!isResumedNow || isDestroyed) return
            if (AdminAlerts.active(this@MainActivity)) io.execute { AdminAlerts.check(applicationContext) }
            main.postDelayed(this, 60_000)
        }
    }

    /** An admin phone with phone notifications on, but Android not allowing them: asked (at most once a day). */
    private fun askForAdminNotifications() {
        if (!AdminAlerts.isAdminPhone() || !AdminAlerts.wanted(this) || AdminAlerts.allowed(this) || Build.VERSION.SDK_INT < 33) return
        val p = getSharedPreferences("adminAlerts", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("asked", 0L) < 86_400_000L) return
        p.edit().putLong("asked", System.currentTimeMillis()).apply()
        AppLog.i("Notifications", "Asking Android to allow notifications (admin phone)")
        withAndroidPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { granted ->
            AppLog.i("Notifications", if (granted.isEmpty()) "Notifications not allowed" else "Notifications allowed")
        }
    }

    /** A notification tapped: the admin screen (which still needs its PIN). */
    private fun openAdmin() { runCatching { startActivity(Intent(this, AdminActivity::class.java)) } }

    // ---------- sound in the background ----------

    /** What the playing page says is playing (title, artist, artwork, its buttons), or null. */
    private fun mediaInfo(view: WebView, done: (org.json.JSONObject?) -> Unit) {
        view.evaluateJavascript("(window.__wlbMediaInfo ? JSON.stringify(window.__wlbMediaInfo()) : '')") { raw ->
            // (The answer comes back as a quoted string.)
            val text = runCatching { org.json.JSONArray("[" + (raw ?: "\"\"") + "]").getString(0) }.getOrDefault("")
            done(runCatching { org.json.JSONObject(text) }.getOrNull())
        }
    }
    /** The page whose sound the notification follows: this one if it's playing, else the player tab's (if any). */
    private val soundView: WebView get() = if (mainPlaying || player == null) web else player!!
    private val soundSite: String get() = if (soundView === player) playerSite else (siteKeyOf(web.url) ?: "a website")
    @Volatile private var lastInfo: org.json.JSONObject? = null
    private var nothingSince = 0L         // nothing to play since (a moment between songs isn't the end)
    private var pausedSince = 0L

    /** Leaving the app with a site's sound playing: it keeps playing, with media controls in a notification. */
    private fun keepPlayingIfSound() {
        main.removeCallbacks(playCheck)
        // (Usually showing already; if it only just started playing, it's shown now, while still allowed.)
        mediaTick(lastInfo)
        if (!notifying) return
        AppLog.i("Sound", "Left the app while $soundSite plays: it keeps playing")
        // While away, a page may start sound without a tap (the next song, or a media button): restored on return.
        soundView.settings.mediaPlaybackRequiresUserGesture = false
        main.postDelayed(soundCheck, 3_000)
    }

    /**
     * The playing notification, every few seconds (in the app and away from it): it shows as soon as something
     * plays (Android always allows that while the app is on screen), keeps up with it, and goes after 30 seconds with
     * nothing to play (between songs there's a moment) or 10 minutes paused.
     */
    private var notifying = false
    private fun mediaTick(info: org.json.JSONObject?) {
        val now = System.currentTimeMillis()
        if (!notifying) {
            if (info == null || !info.optBoolean("playing")) return
            notifying = true; pausedSince = 0L; nothingSince = 0L
            AppLog.i("Sound", "$soundSite plays: \"${info.optString("title").take(60)}\" (buttons from the site: " +
                listOfNotNull(if (info.optBoolean("prev")) "previous" else null, if (info.optBoolean("next")) "next" else null).ifEmpty { listOf("none") }.joinToString() + ")")
            PlaybackService.show(this, soundSite, info)
            return
        }
        when {
            info == null || !info.optBoolean("has") -> {
                if (nothingSince == 0L) nothingSince = now
                if (now - nothingSince > 30_000L) { AppLog.i("Sound", "Nothing playing for 30 seconds: the notification goes"); notifying = false; PlaybackService.stop(this); return }
                return
            }
            info.optBoolean("playing") -> { pausedSince = 0L; nothingSince = 0L }
            pausedSince == 0L -> pausedSince = now
            now - pausedSince > 10 * 60_000L -> { AppLog.i("Sound", "Paused for 10 minutes: the notification goes"); notifying = false; PlaybackService.stop(this); return }
        }
        PlaybackService.show(this, soundSite, info)
    }

    /** Away from the app: the same, every few seconds, while the notification shows. */
    private val soundCheck = object : Runnable {
        override fun run() {
            if (isResumedNow || isDestroyed || !notifying) return
            mediaInfo(soundView) { info -> if (!isResumedNow) { mediaTick(info); if (notifying) main.postDelayed(this, 3_000) } }
        }
    }

    /** The notification's Stop: everything playing on the page stops, and the notification goes. */
    private fun stopSound() {
        notifying = false
        if (player != null) stopPlayer()
        else web.evaluateJavascript("window.__wlbStopAll && window.__wlbStopAll()", null)
        PlaybackService.stop(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        stopPlayer()
        if (notifying) AppLog.i("Sound", "The app's screen closed (swiped away, or Android closed it): the sound stops")
        PlaybackService.stop(this)                              // closed: nothing keeps playing
        runCatching { unregisterReceiver(stopReceiver) }
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
            useWideViewPort = true                  // lets a desktop site lay out at desktop width
        }
        if (phoneAgent.isEmpty() || web.settings.userAgentString.contains("Android")) phoneAgent = web.settings.userAgentString
        registerDesktopScripts()
        // Sound keeps playing in the background: on every page, before its own scripts (see PlaybackService).
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, PlaybackService.PAGE_SCRIPT, setOf("*")) }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) AppLog.w("Page", "Couldn't open ${AppLog.site(request.url.toString())}: ${error?.description} (${error?.errorCode})")
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                if (request?.isForMainFrame == true) AppLog.w("Page", "${AppLog.site(request.url.toString())} answered ${response?.statusCode} ${response?.reasonPhrase}")
            }

            // Links, form posts, JS navigation and server redirects.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Buttons on our own pages ("Ask for this site") use wlb: links. Websites can't use them.
                // The home page's tiles were dragged into a new order: keep it (only from the home page).
                if (url.startsWith("wlb://tile-order")) {
                    if (HomePage.isHome(view?.url)) {
                        val keys = request.url.getQueryParameter("keys").orEmpty().split(',').filter { it.isNotBlank() }
                        if (keys.isNotEmpty()) Tiles.setOrder(this@MainActivity, keys)
                    }
                    return true
                }
                // "Ask for it" on a blocked part of a page: ask for the blocked parts.
                if (url.startsWith("wlb://ask-frames")) {
                    if (request.hasGesture()) showFramesRequest(request.url.getQueryParameter("src"))
                    return true
                }
                if (url.startsWith("wlb://ask-media")) {
                    // A tapped "Photo blocked" or "Video blocked" placeholder: ask for that kind on this page.
                    if (request.isForMainFrame && request.hasGesture() && mediaOffHere) {
                        val kind = request.url.getQueryParameter("kind")
                        val src = request.url.getQueryParameter("src")?.takeIf { it.startsWith("http") }
                        showRequestDialog(Requests.Action.ALLOW, view?.url, mediaBack = true,
                            mediaKind = if (kind == "photos" || kind == "videos" || kind == "sound") kind else offKinds,
                            mediaItem = src)
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
                    ExternalLinks.Kind.FORBIDDEN -> {
                        // file:, content:, chrome:, view-source: links can't be opened here: say why nothing happened.
                        if (request.isForMainFrame && request.hasGesture()) toast("This kind of link can't be opened in this browser.")
                        return true
                    }
                    ExternalLinks.Kind.WEB -> Unit
                }
                if (request.isForMainFrame) {
                    if (request.hasGesture() && !request.isRedirect) trail.clear() // a new tap: a new route
                    if (trail.lastOrNull() != url) trail += url
                    while (trail.size > 20) trail.removeAt(0)
                }
                // A link to another site while this one plays: it keeps playing in the player tab.
                if (request.isForMainFrame && !request.isRedirect && leavesPlayingSite(url)) { moveToPlayer(); navigate(url); return true }
                if (Whitelist.isAllowed(url, request.isForMainFrame)) {
                    // Opening a page: its tracking codes (utm_source, fbclid…) are taken out of its address first.
                    // (Not on a redirect: the server chose that address, e.g. GitHub's signed download addresses.)
                    if (request.isForMainFrame && !request.isRedirect && request.method.equals("GET", ignoreCase = true)) {
                        val clean = AdRules.cleanUrl(url, adGroups(request.url.host))
                        if (clean != url) { registerEarlyScripts(clean); view?.loadUrl(clean); return true }
                    }
                    if (request.isForMainFrame) registerEarlyScripts(url)     // AdGuard's scripts: before the page's own
                    // Going to a site with the other version (desktop or phone): open it again as that.
                    if (request.isForMainFrame && !request.isRedirect && useAgentFor(url)) { view?.loadUrl(url); return true }
                    return false
                }
                // Frames inside a page on a site with "Allow content embedded from other sites" may come
                // from anywhere (the ad and adult filters still apply). Leaving the page is still checked.
                if (!request.isForMainFrame && frameOk(url, request.url.host ?: "", view?.url)) return false
                // A download from an allowed page, kept on another site (e.g. GitHub's files): if it's a
                // file, download it; if it turns out to be a page, it's blocked as usual (never shown).
                if (request.isForMainFrame) {
                    val from = if (request.isRedirect) trail.getOrNull(trail.size - 2) else view?.url
                    if (from != null && Whitelist.isAllowed(from) && (request.isRedirect || (request.hasGesture() && looksLikeFile(url)))) {
                        downloadIfFile(url)
                        return true
                    }
                }
                if (request.isForMainFrame) showBlocked(url) else request.url.host?.let { noteBlockedFrame(it, url) }
                return true
            }

            // Safety net: never fetch a non-listed page. Images/scripts/CDNs are not filtered,
            // so whitelisted sites keep working.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && request.url.host == HomePage.HOST) {
                    return HomePage.respond(this@MainActivity, request.url.path ?: "")
                }
                // "No photos or videos" pages: media files and players get an empty answer.
                // Picture and sound separately: with videos off but sound on, a video's stream still downloads (its
                // sound plays; the page script hides the picture). An embedded player from another site can't have
                // its picture hidden, so it's blocked whenever videos are off.
                if (request != null && !request.isForMainFrame && mediaOffHere &&
                    ((photosOffHere && MediaBlock.isImage(request)) ||
                        (videosOffHere && MediaBlock.isVideo(request) && !MediaBlock.isStream(request)) ||
                        (videosOffHere && soundOffHere && MediaBlock.isVideo(request) && !(Whitelist.hasAllowedPlayer() && MediaBlock.isStream(request))) ||
                        (soundOffHere && MediaBlock.isSound(request, videosAllowed = !videosOffHere))) &&
                    !Whitelist.mediaAllowed(request.url.toString())) {        // a single photo or video allowed anyway
                    pageMedia.incrementAndGet()
                    return MediaBlock.emptyResponse()
                }
                // Ad blocking: things a page loads from ad and tracker domains get an empty answer.
                val pageHost = topUrl?.let { Uri.parse(it).host }
                if (request != null && !request.isForMainFrame && "ads" in adGroups(pageHost)) {
                    val host = request.url.host
                    if (host != null && AdBlock.isAd(host, Whitelist.state.adblockExceptions)) return blockedAd(request)
                }
                // AdGuard's lists, by group (ads, trackers, annoyances): particular addresses and paths. A harmless
                // empty answer (also for its "stand-in" rules). Not for sites on the "Never block these" list.
                if (request != null && !request.isForMainFrame) {
                    val groups = adGroups(pageHost)
                    val host = request.url.host
                    if (groups.isNotEmpty() && host != null && !adExcepted(host) &&
                        AdRules.blocks(request.url, pageHost, groups)) return blockedAd(request)
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
                        noteBlockedFrame(host, u)
                        pageFrames.incrementAndGet()
                        return frameBlockedResponse(host, u)
                    }
                }
                if (request != null && request.isForMainFrame && !Whitelist.isAllowed(request.url.toString())) {
                    // Caught here rather than above (a redirect Android didn't report, a form sent to another
                    // site...). Never fail silently: show the blocked page, with "Ask to open". From an allowed
                    // page, first check whether it's a file to download (e.g. GitHub's downloads).
                    val caught = request.url.toString()
                    val post = request.method.equals("POST", ignoreCase = true)
                    main.post {
                        if (isDestroyed) return@post
                        if (!post && Whitelist.isAllowed(web.url)) downloadIfFile(caught) else showBlocked(caught)
                    }
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(),
                        ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                topUrl = url
                if (!HomePage.isHome(url)) showLoading(8)          // at once: something is happening
                if (!HomePage.isHome(url) && url?.startsWith("http") == true) {
                    pageAds.set(0); pageMedia.set(0); pageFrames.set(0); pageScriptErrors.set(0)
                    pageStartedAt = System.currentTimeMillis()
                    AppLog.i("Page", "Opening ${AppLog.site(url)}" + (if (isDesktop(url)) " (desktop site)" else ""))
                }
                view?.evaluateJavascript(PlaybackService.PAGE_SCRIPT, null)   // (if it didn't run first already)
                if (isDesktop(url)) view?.evaluateJavascript(DESKTOP_SCRIPT, null)
                addAdScripts(view, url, early = true)    // YouTube's: as early as possible (before its player reads its data)
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
                fitDesktop(view, url)
                if (mediaOffHere) view?.evaluateJavascript(MediaBlock.script(photosOffHere, videosOffHere, soundOffHere, Whitelist.mediaAllowList()), null)
                addAdScripts(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (pageStartedAt > 0 && url?.startsWith("http") == true) {
                    val frameSites = synchronized(blockedFrames) { blockedFrames.keys.take(5) }
                    AppLog.i("Page", "Opened ${AppLog.site(url)} in ${System.currentTimeMillis() - pageStartedAt} ms; blocked: " +
                        "${pageAds.get()} ads/trackers, ${pageMedia.get()} photos/videos/sound, ${pageFrames.get()} embedded parts" +
                        (if (pageFrames.get() > 0 && frameSites.isNotEmpty()) " (from ${frameSites.joinToString()})" else "") +
                        (if (pageScriptErrors.get() > 0) "; ${pageScriptErrors.get()} script errors" else ""))
                    pageStartedAt = 0L
                }
                // Where this site was left off (the home page's "Open where you left off").
                if (url != null && Whitelist.isAllowed(url)) siteScope(url)?.let { Tiles.rememberPage(this@MainActivity, it, url, view?.title) }
                if (mediaOffHere) view?.evaluateJavascript(MediaBlock.script(photosOffHere, videosOffHere, soundOffHere, Whitelist.mediaAllowList()), null)
                addAdScripts(view, url)
                fitDesktop(view, url)
                updateUi()
            }

            // Sites like YouTube change pages without reloading. Check those page changes too.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                topUrl = url
                if (url != null && !Whitelist.isAllowed(url)) showBlocked(url)
                // Sites that change pages without reloading: a new page may have a different media setting.
                val photosBefore = photosOffHere
                val videosBefore = videosOffHere
                val soundBefore = soundOffHere
                setMediaMode(url)
                if ((photosOffHere && !photosBefore) || (videosOffHere && !videosBefore) || (soundOffHere && !soundBefore)) {
                    view?.evaluateJavascript(MediaBlock.script(photosOffHere, videosOffHere, soundOffHere, Whitelist.mediaAllowList()), null)
                }
                if ((!photosOffHere && photosBefore) || (!videosOffHere && videosBefore) || (!soundOffHere && soundBefore)) {
                    // Allowed again on this page: a reload shows them. Not while sound plays (it would end the song):
                    // then at the page's next load.
                    if (mainPlaying) AppLog.i("Page", "${AppLog.site(url)}: photos/videos/sound allowed here now; not reloaded while sound plays")
                    else { AppLog.i("Page", "${AppLog.site(url)}: reloaded (photos/videos/sound allowed on this page)"); view?.reload() }
                }
                updateUi()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // A page's own script errors (at most 15 a page), e.g. something a filter broke, or the app's own scripts.
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                if (m != null && m.message().startsWith("[wlb] ")) { AppLog.i("Page", "${AppLog.site(web.url)}: ${m.message().removePrefix("[wlb] ").take(200)}"); return true }
                if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && pageScriptErrors.incrementAndGet() <= 15) {
                    AppLog.w("Page script", "${AppLog.site(web.url)}: ${m.message().take(200)} (${AppLog.site(m.sourceId())}:${m.lineNumber()})")
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (HomePage.isHome(view?.url) && newProgress < 100) return          // the home page opens at once
                showLoading(newProgress)
            }

            // A website's own pop-ups (alert, confirm, prompt, "Leave this page?"): in the app's look, headed with
            // the site's name. The site always gets an answer (closing the card counts as Cancel / Stay).
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                siteDialog(url, message, result, "alert", null)
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                siteDialog(url, message, result, "confirm", null)
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean =
                siteDialog(url, message, result, "prompt", defaultValue)
            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                siteDialog(url, message, result, "leave", null)

            // "Choose file" / "Upload" on a website: the phone's own file picker (one file, or several).
            override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                pendingFiles?.onReceiveValue(null)
                pendingFiles = callback
                val pick: Intent = runCatching { params?.createIntent() }.getOrNull()
                    ?: Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                try {
                    startActivityForResult(pick, FILE_REQUEST)
                } catch (e: Exception) {
                    pendingFiles = null
                    callback?.onReceiveValue(null)
                    toast("There's no app on this phone for choosing files.")
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request != null) handleMediaRequest(request)
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (origin != null && callback != null) handleLocationRequest(origin, callback)
            }
        }

        // Files a page makes itself ("blob:" addresses, e.g. GitHub's download button on a file): the page hands
        // them over through this bridge, which only accepts files the app asked for (a one-time code).
        web.addJavascriptInterface(BlobBridge(), "WLBlobSaver")
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    // ---------- navigation ----------

    private fun navigate(input: String) {
        if (leavesPlayingSite(input) && input.startsWith("http")) moveToPlayer()   // the sound keeps playing
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
        if (Whitelist.isAllowed(text)) {
            val clean = AdRules.cleanUrl(text, adGroups(Uri.parse(text).host))   // without tracking codes
            useAgentFor(clean); registerEarlyScripts(clean); web.loadUrl(clean)
        } else showBlocked(text)
    }

    // ---------- fuller ad blocking ----------

    /** On the "Never block these" list (admin page): no ad blocking for it. */
    private fun adExcepted(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return Whitelist.state.adblockExceptions.any { h == it || h.endsWith(".$it") }
    }

    /**
     * AdGuard's scripts for the site about to open, registered to run before the page's own scripts (the way AdGuard
     * runs them: YouTube reads its ad data as it loads, so later is too late). Called just before a page opens.
     */
    private var earlyScripts: androidx.webkit.ScriptHandler? = null
    private fun registerEarlyScripts(url: String) {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        earlyScripts?.remove(); earlyScripts = null
        val u = Uri.parse(url)
        val host = u.host ?: return
        if (u.scheme != "https" && u.scheme != "http") return
        if (adExcepted(host)) return
        val code = AdRules.pageScript(host, adGroups(host)) ?: return
        earlyScripts = runCatching {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, code, setOf("https://$host", "http://$host"))
        }.getOrNull()
    }

    /** Hides ad elements on the page, and on YouTube removes its ads from the player's data (when ads are blocked). */
    /**
     * AdGuard's groups switched on for this phone (admin page: Block ads, Block trackers, Hide annoyances), minus any
     * switched off on the pages of [pageHost]'s site (admin page: Filters off on some sites).
     */
    private fun adGroups(pageHost: String? = null): Set<String> {
        val s = Whitelist.state
        val on = buildSet { if (s.adblock) add("ads"); if (s.trackers) add("trackers"); if (s.annoyances) add("annoyances") }
        val h = pageHost?.lowercase()?.removePrefix("www.") ?: return on
        val off = s.sitesFiltersOff.filterKeys { h == it || h.endsWith(".$it") }.values.flatten().toSet()
        return on - off
    }

    private fun addAdScripts(view: WebView?, url: String?, early: Boolean = false) {
        if (view == null || url == null) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val host = Uri.parse(url).host ?: return
        val groups = adGroups(host)
        if (groups.isEmpty()) return
        if (adExcepted(host)) return
        AdRules.pageScript(host, groups)?.let { view.evaluateJavascript(it, null) }                // scripts and scriptlets
        if (!early) {                                                                               // once the page is there
            AdRules.hideScript(host, groups)?.let { view.evaluateJavascript(it, null) }
            AdRules.extendedScript(host, groups)?.let { view.evaluateJavascript(it, null) }       // advanced element rules
        }
    }

    // ---------- desktop site ----------

    private var phoneAgent = ""
    private val desktopPrefs by lazy { getSharedPreferences("desktop", Context.MODE_PRIVATE) }

    /** The sites shown as their desktop version (remembered per site, until switched off). */
    private fun desktopSites(): Set<String> = desktopPrefs.getStringSet("sites", emptySet()) ?: emptySet()
    private fun siteKeyOf(url: String?): String? = url?.let { Uri.parse(it).host }?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    private fun isDesktop(url: String?): Boolean = siteKeyOf(url)?.let { k -> desktopSites().any { k == it || k.endsWith(".$it") } } ?: false

    /** How a desktop browser introduces itself (the phone's own, without "Android" and "Mobile"). */
    private fun desktopAgent(): String {
        val chrome = Regex("Chrome/[\\d.]+").find(phoneAgent)?.value ?: "Chrome/124.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    /**
     * On a desktop site, before its own scripts: lays the page out at desktop width (1024) whatever its viewport
     * setting says (responsive sites choose their layout by width, not by who's asking), and answers "is this a
     * phone?" and "touch points?" like a desktop. As Chrome's "Desktop site" does.
     */
    private val DESKTOP_SCRIPT = """
(function () {
  if (window.__wlbDesk) return; window.__wlbDesk = true;
  // Desktop width, opened zoomed out so the whole page fits the screen (pinch to zoom in).
  var fit = Math.min(1, Math.max(0.2, (screen.width || 390) / 1024));
  var W = 'width=1024, initial-scale=' + fit.toFixed(3);
  function fix(m) { if (m && m.getAttribute && (m.getAttribute('name') || '').toLowerCase() === 'viewport' && m.getAttribute('content') !== W) m.setAttribute('content', W); }
  function ensure() {
    var m = document.querySelector('meta[name="viewport" i]');
    if (m) fix(m);
    else if (document.head) { m = document.createElement('meta'); m.setAttribute('name', 'viewport'); m.setAttribute('content', W); document.head.appendChild(m); }
  }
  new MutationObserver(function (records) {
    records.forEach(function (r) {
      if (r.type === 'attributes') fix(r.target);
      else r.addedNodes.forEach(function (n) { if (n.tagName === 'META') fix(n); });
    });
  }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
  ensure();
  document.addEventListener('DOMContentLoaded', ensure);
  try { Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { configurable: true, get: function () { return 0; } }); } catch (e) {}
  try {
    var ua = navigator.userAgentData;
    if (ua) {
      var fake = { brands: ua.brands, mobile: false, platform: 'Linux',
        getHighEntropyValues: function (h) { return ua.getHighEntropyValues(h).then(function (v) { return Object.assign({}, v, { mobile: false, platform: 'Linux', model: '' }); }); },
        toJSON: function () { return { brands: ua.brands, mobile: false, platform: 'Linux' }; } };
      Object.defineProperty(Navigator.prototype, 'userAgentData', { configurable: true, get: function () { return fake; } });
    }
  } catch (e) {}
})();
"""

    /**
     * A desktop site's page, zoomed out until the whole page fits the screen (as a pinch would): Android's browser
     * doesn't always honour the page's own starting zoom. Once when it first shows, and again when it's loaded.
     */
    private fun fitDesktop(view: WebView?, url: String?) {
        if (view == null || !isDesktop(url)) return
        main.postDelayed({
            if (view.url == url) {
                var steps = 0
                while (steps < 25 && view.zoomOut()) steps++          // stops at "fits the screen"
            }
        }, 250)
    }

    /** The desktop sites' script, registered to run before their pages' own scripts (re-registered when they change). */
    private var desktopScripts: androidx.webkit.ScriptHandler? = null
    private fun registerDesktopScripts() {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        desktopScripts?.remove(); desktopScripts = null
        val sites = desktopSites()
        if (sites.isEmpty()) return
        val origins = sites.flatMap { listOf("https://$it", "https://*.$it", "http://$it", "http://*.$it") }.toSet()
        desktopScripts = runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, DESKTOP_SCRIPT, origins) }.getOrNull()
    }

    /** Sets how the browser introduces itself for [url]'s site: desktop or phone. True if it changed. */
    private fun useAgentFor(url: String?): Boolean {
        if (phoneAgent.isEmpty()) return false
        val desktop = isDesktop(url)
        val want = if (desktop) desktopAgent() else phoneAgent
        if (web.settings.userAgentString == want) return false
        web.settings.userAgentString = want
        web.settings.loadWithOverviewMode = desktop             // the whole desktop page fits the screen at first
        return true
    }

    /** ⋮ → Desktop site: switches this site between its desktop and phone versions, and reloads it. */
    private fun toggleDesktop() {
        val cur = web.url ?: return
        val key = siteKeyOf(cur) ?: return
        val now = desktopSites().toMutableSet()
        val on = !isDesktop(cur)
        if (on) now.add(key) else now.removeAll { key == it || key.endsWith(".$it") }
        desktopPrefs.edit().putStringSet("sites", now).apply()
        AppLog.i("Desktop site", "${if (on) "On" else "Off"} for $key")
        registerDesktopScripts()
        useAgentFor(cur)
        web.reload()
        toast(if (on) "Showing the desktop site" else "Showing the phone site")
    }

    // ---------- websites' own pop-ups ----------

    private val siteDialogTimes = ArrayList<Long>()

    /**
     * A website's alert / confirm / prompt / "Leave this page?", as the app's own card headed "<site> says".
     * Always answers the site exactly once. A site firing pop-ups over and over (some do, to trap people) has
     * the rest quietly dismissed after a few.
     */
    private fun siteDialog(url: String?, message: String?, result: android.webkit.JsResult?, kind: String, defaultValue: String?): Boolean {
        if (result == null) return false
        val now = System.currentTimeMillis()
        siteDialogTimes.removeAll { now - it > 10_000 }
        siteDialogTimes.add(now)
        if (siteDialogTimes.size > 4 || isFinishing) {
            if (kind == "leave") result.confirm() else result.cancel()
            if (siteDialogTimes.size == 5) toast("This site keeps showing messages, so they're hidden for now.")
            return true
        }
        val host = url?.let { Uri.parse(it).host?.removePrefix("www.") }?.takeIf { it.isNotBlank() } ?: "This page"
        var answered = false
        val answer = { ok: Boolean, typed: String? ->
            if (!answered) {
                answered = true
                if (ok && result is android.webkit.JsPromptResult) result.confirm(typed ?: "")
                else if (ok) result.confirm()
                else result.cancel()
            }
        }
        val field: EditText? = if (kind == "prompt") Ui.field(this, "", defaultValue ?: "") else null
        val d = Ui.AppDialog(this, sheet = false)
        d.title(if (kind == "leave") "Leave this page?" else "$host says", icon = R.drawable.ic_d_globe)
        val text = message?.takeIf { it.isNotBlank() }
        if (kind == "leave") d.add(Ui.text(this, text ?: "Changes you made here may not be saved.", 15f, Ui.INK2))
        else if (text != null) d.add(Ui.text(this, text.take(2000), 15f, Ui.INK2))
        if (field != null) d.add(field, 10)
        when (kind) {
            "alert" -> d.button("OK", Ui.Kind.PRIMARY) { it.dismiss(); answer(true, null) }
            "leave" -> {
                d.button("Stay", Ui.Kind.GHOST) { it.dismiss(); answer(false, null) }
                d.button("Leave", Ui.Kind.PRIMARY) { it.dismiss(); answer(true, null) }
            }
            else -> {
                d.button("Cancel", Ui.Kind.GHOST) { it.dismiss(); answer(false, null) }
                d.button("OK", Ui.Kind.PRIMARY) { val typed = field?.text?.toString(); it.dismiss(); answer(true, typed) }
            }
        }
        d.onDismiss { answer(kind == "alert", null) }      // closed another way: OK for an alert, otherwise Cancel / Stay
        d.show()
        return true
    }

    // ---------- choosing files to upload ----------

    private var pendingFiles: android.webkit.ValueCallback<Array<Uri>>? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_REQUEST) return
        val cb = pendingFiles ?: return
        pendingFiles = null
        // One file, or several (picked together).
        val clip = data?.clipData
        val files: Array<Uri>? = when {
            resultCode != RESULT_OK -> null
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            else -> WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        cb.onReceiveValue(files)
    }

    // ---------- a browser for the phone ----------

    /**
     * A web link handed over by another app (WhatsApp, email, a news app…), when this app opens web links or is
     * the phone's browser. It goes through the same checks as anything typed or tapped: not on the list, and it
     * shows the blocked page with "Ask to open". True if there was one.
     */
    private fun openLinkFrom(i: Intent?): Boolean {
        if (i?.action != Intent.ACTION_VIEW) return false
        val link = i.data?.toString() ?: return false
        if (!link.startsWith("http://") && !link.startsWith("https://")) return false
        i.data = null                                           // handled: not again (e.g. when the screen turns)
        navigate(link)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == AdminAlerts.ACTION_OPEN_ADMIN) { openAdmin(); return }   // an admin phone's notification tapped
        openLinkFrom(intent)                                    // a link from another app while this one is open
    }

    /** The media controls (notification, lock screen, headphones): a message only this app can send. */
    private val stopReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != PlaybackService.ACTION_MEDIA) return
            AppLog.i("Sound", "Media button: ${i.getStringExtra(PlaybackService.EXTRA_CMD)}")
            when (val cmd = i.getStringExtra(PlaybackService.EXTRA_CMD)) {
                "stop" -> stopSound()
                "play", "pause", "nexttrack", "previoustrack", "seekbackward", "seekforward" -> {
                    // Pressed on the page that's playing (the player tab, if there is one), then the controls catch up.
                    // A page may only start sound after a tap on it: a media button counts as one, just for a moment.
                    val target = soundView
                    target.settings.mediaPlaybackRequiresUserGesture = false        // (until the app comes back)
                    if (isResumedNow) main.postDelayed({ runCatching { target.settings.mediaPlaybackRequiresUserGesture = true } }, 8_000)
                    target.evaluateJavascript("window.__wlbMediaDo && window.__wlbMediaDo('$cmd')", null)
                    main.removeCallbacks(soundCheck); main.postDelayed(soundCheck, 700)
                }
                else -> if (cmd?.startsWith("seekto:") == true && cmd.drop(7).all { it.isDigit() })      // dragging the progress bar
                    soundView.evaluateJavascript("window.__wlbMediaDo && window.__wlbMediaDo('$cmd')", null)
            }
        }
    }

    /** Is this app the phone's default browser? */
    private fun isPhonesBrowser(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) return rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
        }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        @Suppress("DEPRECATION")
        val best = packageManager.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return best?.activityInfo?.packageName == packageName
    }

    /** Asks Android to make this the phone's browser (its own question), or opens the right settings page. */
    private fun becomePhonesBrowser() {
        if (isPhonesBrowser()) { toast("It's already the phone's browser"); return }
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                runCatching { startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 7341); return }
            }
        }
        val settings = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        if (runCatching { startActivity(settings) }.isFailure) runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        toast("Choose Whitelist Browser under Browser")
    }

    private fun goHome() {
        if (leavesPlayingSite(HomePage.URL)) moveToPlayer()      // the sound keeps playing in the player tab
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
        // Light or dark, and the app's colours right now (so the page matches, the phone's colours too).
        val pal = listOf(Ui.PAGE, Ui.INK, Ui.INK2, Ui.CARD, Ui.OUTLINE, Ui.ACCENT, Ui.ACCENT_TEXT, Ui.SOFT, Ui.RED_BG, Ui.RED_INK)
            .joinToString(".") { Ui.hex(it).removePrefix("#") }
        val look = (if (Ui.dark) "&theme=dark" else "") + "&pal=$pal"
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

    // A list check started by hand (the list button): it says how it went when it's done.
    private var checkingByHand = false
    private fun checkListNow() {
        checkingByHand = true
        toast("Checking the list")
        refreshWhitelist()
    }

    private fun refreshWhitelist() {
        main.removeCallbacks(refreshTask)
        io.execute {
            val before = Whitelist.state
            val error = try {
                Whitelist.refresh(applicationContext); null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (error != null) AppLog.w("Lists", "Checking for changes failed: $error")
            if (error == null) AdminAlerts.schedule(applicationContext)        // admin phones: the background check
            // The admin page asked for this phone's log: sent once per request.
            val asked = Whitelist.state.logRequested
            val logPrefs = getSharedPreferences("log", Context.MODE_PRIVATE)
            if (error == null && asked > 0 && asked != logPrefs.getLong("answered", 0L)) {
                logPrefs.edit().putLong("answered", asked).apply()
                main.post { AppLog.i("Log", "The admin asked for the log"); sendLog("asked for", quiet = true) }
            }
            main.post {
                if (isDestroyed) return@post
                Whitelist.lastError = error
                // Photos and videos were switched on or off for the open page: reload it.
                if (mediaChanged(web.url)) {
                    setMediaMode(web.url)
                    if (mainPlaying) AppLog.i("Page", "${AppLog.site(web.url)}: photos/videos/sound settings changed; not reloaded while sound plays")
                    else { AppLog.i("Page", "${AppLog.site(web.url)}: reloaded (its photos/videos/sound settings changed)"); web.reload() }
                }
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
                if (checkingByHand) {
                    checkingByHand = false
                    toast(when {
                        error != null -> "Couldn't check the list (no internet?). The saved list is still in use."
                        !Whitelist.state.sameContent(before) -> "The list has changed. It's up to date now."
                        else -> "The list is up to date."
                    })
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
        findViewById<View>(R.id.listCheck).setOnClickListener { checkListNow() }
        if (tinyBar) {                             // room for the site's name: these go in the ⋮ menu
            forwardBtn.visibility = View.GONE
            findViewById<View>(R.id.reload).visibility = View.GONE
            findViewById<View>(R.id.listCheck).visibility = View.GONE
        }
        // Holding the site's name shows it in full (the top bar cuts long names short).
        pageTitle.setOnLongClickListener { showFullTitle(); true }
        findViewById<View>(R.id.home).setOnClickListener { goHome() }
        findViewById<View>(R.id.menu).setOnClickListener { showMenu(it) }
        // The slim line: tapping it does what it's about (try the list again, or ask for photos, videos, sound).
        status.setOnClickListener {
            when {
                Whitelist.lastError != null -> checkListNow()
                mediaOffHere -> showRequestDialog(Requests.Action.ALLOW, web.url, mediaBack = true, mediaKind = offKinds)
            }
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
        val mediaNote = tempNote + if (mediaOffHere) "${Requests.mediaWords(offKinds).replaceFirstChar { it.uppercase() }} " +
            "${if (offKinds.contains(',')) "are" else if (offKinds == "sound") "is" else "are"} off on this page. " else ""
        // The slim line under the top bar: only when there's something to say (time left, photos or videos
        // off, or the list couldn't be checked). Otherwise it's hidden.
        val problem = when {
            err != null && s.allow.isEmpty() -> "No list loaded yet. Tap to try again."
            err != null -> "Offline: using the saved list. Tap to try again."
            else -> ""
        }
        val line = (mediaNote + problem).trim()
        status.text = line
        status.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
    }

    /** A small bubble under the top bar with the open site's full name (the page's title, and its site). */
    private fun showFullTitle() {
        val cur = web.url ?: return
        if (HomePage.isHome(cur) || cur.startsWith(BLOCKED_PAGE)) return
        val full = web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: return
        val host = Uri.parse(cur).host?.removePrefix("www.") ?: ""
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(if (Ui.dark) Ui.CARD else Ui.INK, Ui.dp(this@MainActivity, 12).toFloat())
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 10))
            elevation = Ui.dp(this@MainActivity, 8).toFloat()
            val fg = if (Ui.dark) Ui.INK else android.graphics.Color.WHITE
            addView(Ui.text(this@MainActivity, full, 14.5f, fg, "bold"))
            addView(Ui.text(this@MainActivity, host, 12.5f, if (Ui.dark) Ui.MUTED else 0xFFB9C9C6.toInt()))
        }
        val width = minOf(resources.displayMetrics.widthPixels - Ui.dp(this, 24), Ui.dp(this, 420))
        val pop = android.widget.PopupWindow(box, width, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = Ui.dp(this@MainActivity, 8).toFloat()
        }
        pop.showAsDropDown(pageTitle, 0, Ui.dp(this, 6))
        main.postDelayed({ if (pop.isShowing) pop.dismiss() }, 4000)
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

    /**
     * The ⋮ menu: a compact card in the app's look. Asking about this page (blocking names the site), then new
     * sites and requests (with counts), then Settings. On tiny screens, Forward, Reload and
     * "Check the list" are a row of buttons at the top (they're not in the top bar there).
     */
    private fun showMenu(anchor: View) {
        val cur = web.url
        val onRealSite = cur != null && !HomePage.isHome(cur) && blockedTarget(cur) == null &&
            (cur.startsWith("https://") || cur.startsWith("http://"))
        val compact = tinyBar
        fun dp(v: Int) = Ui.dp(this, v)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, dp(18).toFloat(), Ui.LINE, dp(1))
            setPadding(0, dp(4), 0, dp(6))
        }
        val pop = android.widget.PopupWindow(card, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        fun divider() = card.addView(View(this).apply { setBackgroundColor(Ui.LINE) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(2); bottomMargin = dp(2) })
        fun item(icon: Int, label: String, badge: String? = null, warn: Boolean = false, enabled: Boolean = true,
                 sub: String? = null, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)                    // the comfortable minimum for tapping
                setPadding(dp(if (compact) 12 else 14), dp(4), dp(if (compact) 12 else 14), dp(4))
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.55f
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.SEG), null,
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
                contentDescription = label + (badge?.let { " ($it)" } ?: "")
                if (enabled) setOnClickListener { pop.dismiss(); action() }
            }
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(if (enabled) Ui.ACCENT_TEXT else Ui.MUTED)
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(Ui.text(this, label, if (compact) 14f else 15f, if (enabled) Ui.INK else Ui.MUTED, "bold").apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (sub != null) words.addView(Ui.text(this, sub, 12f, Ui.MUTED))
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            if (badge != null) row.addView(Ui.text(this, badge, 12.5f, if (warn) Ui.AMBER_INK else android.graphics.Color.WHITE, "bold").apply {
                gravity = android.view.Gravity.CENTER
                minWidth = dp(22); minimumWidth = dp(22)
                setPadding(dp(7), dp(1), dp(7), dp(1))
                background = Ui.rounded(if (warn) Ui.AMBER_BG else Ui.ACCENT, dp(11).toFloat())
            })
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        // Tiny screens: Forward, Reload and Check the list, as a row of buttons.
        if (compact) {
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(4)) }
            fun iconButton(icon: Int, label: String, enabled: Boolean, action: () -> Unit) =
                bar.addView(android.widget.ImageButton(this).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT_TEXT)
                    background = Ui.rounded(Ui.SOFT, dp(12).toFloat())
                    contentDescription = label
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.45f
                    setOnClickListener { pop.dismiss(); action() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (bar.childCount > 0) marginStart = dp(6) })
            iconButton(R.drawable.ic_d_fwd, "Forward", web.canGoForward()) { if (web.canGoForward()) web.goForward() }
            iconButton(R.drawable.ic_d_reload, "Reload", true) { web.reload() }
            iconButton(R.drawable.ic_d_listcheck, "Check the list now", true) { checkListNow() }
            card.addView(bar)
            divider()
        }

        // Asking about this page.
        val partsCount = synchronized(blockedFrames) { blockedFrames.size }
        var asked = false
        if (partsCount > 0 && Requests.isSetUp()) {
            item(R.drawable.ic_d_parts, "Blocked parts", partsCount.toString(), warn = true) { showFramesRequest() }
            asked = true
        }
        if (mediaOffHere && onRealSite) {
            item(R.drawable.ic_d_photo, "Ask for ${Requests.mediaWords(offKinds)}") { showRequestDialog(Requests.Action.ALLOW, cur, mediaBack = true, mediaKind = offKinds) }
            asked = true
        }
        if (onRealSite) {
            item(R.drawable.ic_d_ban, "Ask to block") { showRequestDialog(Requests.Action.BLOCK, cur) }   // this site (the request screen names it)
            asked = true
        }
        if (onRealSite) {
            if (asked) divider()
            item(R.drawable.ic_d_desktop, "Desktop site", if (isDesktop(cur)) "On" else null) { toggleDesktop() }
            asked = true
        }
        if (asked) divider()
        // New sites, and requests (with how many are waiting for an answer).
        item(R.drawable.ic_d_plus, "Ask for a new site") { showRequestDialog(Requests.Action.ALLOW, null) }
        val waiting = MyRequests.all(this).count { it.status == "waiting" && !it.archived } + Outbox.waitingRequests(this).size
        item(R.drawable.ic_d_inbox, "My requests", if (waiting > 0) waiting.toString() else null) { showMyRequests() }
        divider()
        item(R.drawable.ic_d_gear, "Settings") { showSettings() }

        // As wide as its widest item needs, within limits (long labels end in "…").
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        pop.width = card.measuredWidth.coerceIn(dp(176), dp(232))
        // Shown just under the ⋮, which is highlighted while it's open.
        pop.elevation = dp(12).toFloat()
        pop.isOutsideTouchable = true
        val before = anchor.background
        anchor.background = Ui.rounded(0x29FFFFFF, dp(22).toFloat())
        pop.setOnDismissListener { anchor.background = before }
        pop.showAsDropDown(anchor, 0, -dp(2), android.view.Gravity.END)
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
        // Android 13+: notifications need permission, so "Update ready: tap to install" can show with the app closed.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("updates", Context.MODE_PRIVATE).getBoolean("askedNotify", false)) {
            getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("askedNotify", true).apply()
            toast("Allow notifications, so you're told when the update is ready to install")
            withAndroidPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { startUpdate(release) }
            return
        }
        updating = true
        if (Config.LOCK_TASK) runCatching { stopLockTask() }       // the installer screen needs to open
        // Already downloaded (Android's "update?" screen was closed, say): installed again, not downloaded again.
        Updater.readyFile(applicationContext, release)?.let { apk ->
            AppLog.i("Update", "Already downloaded: installing")
            showBanner("Installing version ${release.versionName}: the app closes for a moment")
            updateIo.execute { runCatching { Updater.install(applicationContext, apk) }.onFailure { AppLog.e("Update", "Installing failed", it) } }
            main.postDelayed({ updating = false }, 3_000)
            return
        }
        // Android's download manager: it carries on if the app is minimised or closed, and installs when it's done.
        try { if (!Updater.downloading(applicationContext, release)) Updater.startDownload(applicationContext, release) }
        catch (e: Exception) { updating = false; showBanner("Update failed: ${e.message ?: "unknown error"}. Tap to try again."); return }
        val name = release.versionName
        showBanner("Downloading version $name (it carries on if you close the app, and installs by itself)")
        val watch = object : Runnable {
            override fun run() {
                if (isDestroyed) return
                val pct = Updater.progress(applicationContext)
                when {
                    pct == null -> { updating = false; showBanner("Update failed. Tap to try again.") }
                    pct >= 100 -> { updating = false; showBanner(if (PlaybackService.isPlaying) "Version $name is ready: it installs once nothing's playing"
                        else "Installing version $name: the app closes for a moment") }
                    else -> { showBanner("Downloading version $name: $pct% (it carries on if you close the app, and installs by itself)"); main.postDelayed(this, 1_000) }
                }
            }
        }
        main.postDelayed(watch, 1_000)
    }

    private fun showBanner(text: String) {
        updateBanner.text = text
        updateBanner.visibility = View.VISIBLE
    }

    /**
     * A short message: a small rounded bar near the bottom, in the app's colours, for a few seconds. While one of
     * the app's dialogs is open, it shows inside that dialog instead (it would be hidden behind it).
     */
    private var messageBar: TextView? = null
    private val hideMessage = Runnable {
        val bar = messageBar
        if (bar != null) bar.animate().alpha(0f).setDuration(200).withEndAction {
            (bar.parent as? ViewGroup)?.removeView(bar)
            if (messageBar === bar) messageBar = null
        }.start()
    }
    private fun toast(text: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { main.post { toast(text) }; return }
        if (isFinishing || isDestroyed) return
        val open = Ui.AppDialog.showing
        if (open != null && open.isShowing) { open.note(text); return }
        val host = window?.decorView as? android.widget.FrameLayout
        if (host == null) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); return }
        main.removeCallbacks(hideMessage)
        messageBar?.let { host.removeView(it) }
        val bar = Ui.text(this, text, 14.5f, if (Ui.dark) Ui.INK else android.graphics.Color.WHITE).apply {
            background = if (Ui.dark) Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 14).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
                else Ui.rounded(Ui.INK, Ui.dp(this@MainActivity, 14).toFloat())
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12))
            elevation = Ui.dp(this@MainActivity, 8).toFloat()
            gravity = android.view.Gravity.CENTER
            alpha = 0f
            setOnClickListener { main.removeCallbacks(hideMessage); hideMessage.run() }
        }
        @Suppress("DEPRECATION")
        val bottomInset = host.rootWindowInsets?.systemWindowInsetBottom ?: 0
        val side = Ui.dp(this, 16)
        host.addView(bar, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { setMargins(side, 0, side, bottomInset + Ui.dp(this@MainActivity, 28)) })
        bar.animate().alpha(1f).setDuration(150).start()
        bar.announceForAccessibility(text)
        messageBar = bar
        main.postDelayed(hideMessage, 3500)
    }

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

    private val FILE_EXT = setOf("apk", "zip", "rar", "7z", "tar", "gz", "tgz", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "rtf", "txt", "csv", "epub", "ics", "vcf", "json", "xml", "mp3", "m4a", "wav", "mp4", "mov",
        "jpg", "jpeg", "png", "gif", "webp", "svg", "exe", "msi", "dmg", "iso")

    /** Does this address look like a file (by its ending, or a "download" in it)? */
    private fun looksLikeFile(url: String): Boolean {
        val u = Uri.parse(url)
        val name = (u.path ?: "").substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase() in FILE_EXT || "/download" in (u.path ?: "").lowercase() ||
            (u.query ?: "").contains("attachment", ignoreCase = true)
    }

    /**
     * Checks in the background whether [url] is a file (the server says "download this", or it isn't a web
     * page): then it's downloaded. If it's a page, it's blocked as usual. (A file stored on a site that isn't
     * on the list, reached from an allowed page, such as GitHub's downloads.)
     */
    private fun downloadIfFile(url: String) {
        // Never from a site on the malware, adult or gambling list.
        val host = Uri.parse(url).host ?: return showBlocked(url)
        if (Whitelist.filteredAs(host).isNotEmpty() && !Whitelist.isUnfiltered(host)) return showBlocked(url)
        val agent = web.settings.userAgentString
        toast("Checking the download")
        io.execute {
            val found = runCatching { fileInfo(url, agent) }.getOrNull()
            main.post {
                if (isDestroyed) return@post
                if (found != null) startDownload(url, agent, found.first, found.second) else showBlocked(url)
            }
        }
    }

    /** The file's "Content-Disposition" and type, or null if [url] is a web page (or can't be reached). */
    private fun fileInfo(url: String, agent: String): Pair<String?, String?>? {
        fun ask(method: String): java.net.HttpURLConnection {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.requestMethod = method
            c.instanceFollowRedirects = true
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", agent)
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            if (method == "GET") c.setRequestProperty("Range", "bytes=0-0")   // just the start: the headers are enough
            return c
        }
        // Some file stores only answer GET (their links are signed for it), so fall back to that.
        var c = ask("HEAD")
        if (c.responseCode !in 200..399) { c.disconnect(); c = ask("GET") }
        try {
            if (c.responseCode !in 200..399 && c.responseCode != 416) return null
            val disposition = c.getHeaderField("Content-Disposition")
            val type = c.contentType?.substringBefore(';')?.trim()?.lowercase()
            val page = type == null || type == "text/html" || type == "application/xhtml+xml"
            return if (disposition?.contains("attachment", ignoreCase = true) == true || !page) disposition to type else null
        } finally {
            c.disconnect()
        }
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        // A file the page made itself, or one written into the page: saved by the app (DownloadManager can't).
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            saveFromPage(url, contentDisposition, mimeType)
            return
        }
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
            val name = runCatching { downloadName(url, contentDisposition, mimeType) }.getOrDefault("download")   // (also for the log below)
            try {
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    typeFor(name, mimeType)?.let { setMimeType(it) }   // from the name if the server only said "binary"
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    if (userAgent != null) addRequestHeader("User-Agent", userAgent)
                    setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                AppLog.i("Download", "Downloading $name from ${AppLog.site(url)} (type ${mimeType ?: "?"})")
                toast("Downloading $name to the Downloads folder")
            } catch (e: Exception) {
                AppLog.e("Download", "$name from ${AppLog.site(url)} failed", e)
                toast("Download failed: ${e.message}")
            }
        }
    }

    // Files the page makes itself ("blob:") are read in the page and handed over with a one-time code.
    private val blobCodes = java.util.Collections.synchronizedMap(HashMap<String, Pair<String, String>>()) // code -> name, type

    private inner class BlobBridge {
        @android.webkit.JavascriptInterface
        fun save(code: String, dataUrl: String, pageName: String) {
            val (name, type) = blobCodes.remove(code) ?: return          // not one the app asked for: ignored
            // The name the page gave the download (Android doesn't pass it on), else the one worked out before.
            val real = pageName.takeIf { it.isNotBlank() }?.let { downloadName("", "attachment; filename=\"${it.replace("\"", "")}\"", type) } ?: name
            main.post { writeDownload(real, typeFor(real, type) ?: type, dataUrl) }
        }

        @android.webkit.JavascriptInterface
        fun failed(code: String, why: String) {
            if (blobCodes.remove(code) != null) { AppLog.w("Download", "A file the page built couldn't be saved: $why"); main.post { toast("This file couldn't be saved ($why)") } }
        }
    }

    /** Saves a "blob:" or "data:" file from the page into Downloads. */
    private fun saveFromPage(url: String, contentDisposition: String?, mimeType: String?) {
        val type = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val name = downloadName(if (url.startsWith("data:")) "" else url, contentDisposition, type)
        val perms = if (Build.VERSION.SDK_INT < 29) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray()
        withAndroidPermissions(perms) { granted ->
            if (perms.isNotEmpty() && granted.isEmpty()) {
                toast("Storage permission is needed to download files")
                return@withAndroidPermissions
            }
            if (url.startsWith("data:")) { writeDownload(name, type, url); return@withAndroidPermissions }
            val code = java.util.UUID.randomUUID().toString()
            blobCodes[code] = name to type
            toast("Saving $name")
            // The file as the page kept it (it may already have let go of the address), else fetched from the address.
            val js = "(function(u,c){var k=window.__wlbFiles&&window.__wlbFiles.get(u);" +
                "(k?Promise.resolve(k):fetch(u).then(function(r){return r.blob();})).then(function(b){" +
                "var n=(window.__wlbNames&&window.__wlbNames.get(u))||'';" +
                "var f=new FileReader();f.onloadend=function(){if(f.error)WLBlobSaver.failed(c,String(f.error));else WLBlobSaver.save(c,String(f.result),n);};f.readAsDataURL(b);" +
                "}).catch(function(e){WLBlobSaver.failed(c,String(e&&e.message||e));});})(" + org.json.JSONObject.quote(url) + "," + org.json.JSONObject.quote(code) + ");"
            web.evaluateJavascript(js, null)
        }
    }

    /**
     * A downloaded file's name. Android's own guess (URLUtil.guessFileName) turns names into "….bin" when a server
     * says only "binary" (application/octet-stream), as GitHub does. So, in order: the name the server gives
     * (Content-Disposition, including the filename*= form for names with accents), else the last part of the
     * address, and only if that has no ending, one from the file's type.
     */
    private fun downloadName(url: String, contentDisposition: String?, mimeType: String?): String {
        fun clean(n: String) = n.trim().trim('"', '\'').substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_").take(150)
        val cd = contentDisposition.orEmpty()
        val fromHeader = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            runCatching { java.net.URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), m.groupValues[1].ifBlank { "UTF-8" }) }.getOrNull()
        } ?: Regex("filename\\s*=\\s*(\"[^\"]*\"|[^;]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
        val fromUrl = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        var name = listOfNotNull(fromHeader, fromUrl).map { clean(it) }.firstOrNull { it.isNotBlank() && it != "." && it != ".." } ?: "download"
        if (!name.contains('.')) {
            val ext = mimeType?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it.substringBefore(';').trim()) }
            if (ext != null && ext != "bin") name += ".$ext"
        }
        return name
    }

    /**
     * The file's type, from its name's ending whenever Android knows that ending. Android adds the ending it expects
     * when the type and the name disagree ("report.pdf" saved as plain text became "report.pdf.txt"), so the name
     * decides. An ending Android doesn't know (".md"): "any kind of file", which leaves the name as it is.
     */
    private fun typeFor(name: String, mimeType: String?): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext != name.lowercase()) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
            return "application/octet-stream"
        }
        val m = mimeType?.substringBefore(';')?.trim()?.lowercase()
        return m?.takeIf { it.isNotEmpty() }
    }

    /** Writes a "data:" address's contents into the phone's Downloads folder. */
    private fun writeDownload(name: String, type: String, dataUrl: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) throw IllegalArgumentException("no data")
            val head = dataUrl.substring(0, comma)
            val body = dataUrl.substring(comma + 1)
            val bytes = if (head.endsWith(";base64")) android.util.Base64.decode(body, android.util.Base64.DEFAULT)
                else Uri.decode(body).toByteArray()
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, typeFor(name, type) ?: type)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no place to save")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("can't write")
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                java.io.File(dir, name).writeBytes(bytes)
            }
            AppLog.i("Download", "Saved $name ($type, ${if (bytes.size < 1024) "${bytes.size} bytes" else "${bytes.size / 1024} KB"})")
            toast("Saved $name to the Downloads folder")
        } catch (e: Exception) {
            AppLog.e("Download", "Saving $name failed", e)
            toast("This file couldn't be saved: ${e.message}")
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
        if (steps == 0) { goHome(); return }
        // Going back to another site while this one plays: it keeps playing in the player tab.
        val list = web.copyBackForwardList()
        val target = list.getItemAtIndex(list.currentIndex + steps)?.url
        if (target != null && leavesPlayingSite(target)) { moveToPlayer(); navigate(target); return }
        web.goBackOrForward(steps)
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
                                  route: Passthrough.Route? = null, mediaKind: String = "both", mediaItem: String? = null) {
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
            mediaBack -> "Ask for ${Requests.mediaWords(mediaKind)}"
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
                    sendButton.text = "Send"
                }
            }
        })
        // "Checking scratch.mit.edu…" while a typed site is being checked.
        val checking = Ui.text(this, "", 13.5f, Ui.MUTED).apply { visibility = View.GONE }
        if (siteDomain == null) d.add(checking, 6)

        // The addresses the link passes through on the way: each needs opening too, so each gets its own
        // choice (just that page, or its whole site; a home page tile or not, off to start with).
        val hops = route?.hops.orEmpty().filter { it != pageUrl }.map { Requests.Hop(it) }
        if (hops.isNotEmpty()) {
            d.add(Ui.text(this, "This link goes through other addresses on the way. Each one needs opening too.", 14f, Ui.MUTED))
            hops.forEach { hop ->
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 14).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
                    val p = Ui.dp(this@MainActivity, 12)
                    setPadding(p, p, p, p)
                }
                box.addView(Ui.text(this, Whitelist.pageKey(hop.url)?.substringBefore('?') ?: hop.url, 14.5f, Ui.INK, "bold").apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                box.addView(Ui.Segmented(this, listOf("This page", "Whole site"), 0, false) { hop.scope = if (it == 1) "site" else "page" }.view,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 8) })
                val (tileRow, tileSwitch) = Ui.switchRow(this, "Home page tile", null, false)
                tileSwitch.setOnCheckedChangeListener { _, on -> hop.tile = on }
                box.addView(tileRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 8) })
                d.add(box, 8)
            }
            if (siteDomain != null) d.add(Ui.label(this, "$siteDomain itself"))
        }

        // Asking for one photo or video (a tapped placeholder): just that one, this page, or the whole site.
        var itemChoice = if (mediaBack && mediaItem != null) 0 else -1
        if (itemChoice == 0) {
            val opts = if (canChoose) listOf("Just this one", "This page", "Whole site") else listOf("Just this one", "Whole site")
            d.add(Ui.label(this, "Open"))
            d.add(Ui.Segmented(this, opts, 0, true) { itemChoice = if (!canChoose && it == 1) 2 else it }.view, 6)
        }

        // Just this page, or the whole site.
        var pageScope = canChoose
        if (canChoose && itemChoice < 0) {
            val which = Ui.text(this, pageKey ?: "", 13f, Ui.MUTED)
            d.add(Ui.label(this, "What"))
            d.add(Ui.Segmented(this, listOf("Just this page", "Whole site"), 0, narrow) {
                pageScope = it == 0
                which.text = if (pageScope) pageKey else siteDomain
            }.view, 6)
            d.add(which, 4)
        }

        // A home page tile for it (opening a site: on to start with).
        val (tileRow, tileSwitch) = Ui.switchRow(this, "Show on the home page", "Gives it a tile", true)
        if (action == Requests.Action.ALLOW && !mediaBack) d.add(tileRow)

        // "For how long?": always, or temporary, chosen on two scroll wheels (hours and minutes).
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
        Ui.styleWheel(hoursWheel)
        Ui.styleWheel(minutesWheel)
        val wheelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            val gap = Ui.dp(this@MainActivity, if (narrow) 6 else 10)
            addView(hoursWheel)
            addView(Ui.text(this@MainActivity, if (narrow) "h" else "hours", 13f, Ui.MUTED).apply { setPadding(gap / 2, 0, gap * 2, 0) })
            addView(minutesWheel)
            addView(Ui.text(this@MainActivity, "min", 13f, Ui.MUTED).apply { setPadding(gap / 2, 0, 0, 0) })
        }
        // The wheels on a card, with a soft band behind the chosen numbers (in place of grey lines).
        val wheels = android.widget.FrameLayout(this).apply {
            background = Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 14).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
            val band = View(this@MainActivity).apply { background = Ui.rounded(Ui.SOFT, Ui.dp(this@MainActivity, 10).toFloat()) }
            val bandLp = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 46), android.view.Gravity.CENTER_VERTICAL)
            bandLp.setMargins(Ui.dp(this@MainActivity, 10), 0, Ui.dp(this@MainActivity, 10), 0)
            addView(band, bandLp)
            addView(wheelRow, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        // Temporary: count only the time the site is open, or from when it's approved.
        val useBox = android.widget.CheckBox(this).apply {
            text = "Only count time while the site is open"
            setTextColor(Ui.INK2)
            typeface = Ui.bodyFace
            textSize = 14.5f
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            visibility = View.GONE
        }
        if (action == Requests.Action.ALLOW) {
            d.add(Ui.label(this, "For how long?"))
            d.add(Ui.Segmented(this, listOf("Always", "Temporary"), 0, false) {
                forAWhile = it == 1
                wheels.visibility = if (forAWhile) View.VISIBLE else View.GONE
                useBox.visibility = wheels.visibility
            }.view, 6)
            d.add(wheels, 8)
            d.add(useBox, 6)
        }

        // Photos, videos and sound, separately (any of them): opening without some of them; blocking
        // completely or only some of them; or (asking for them back) which of the ones that are off.
        val kindNames = listOf("photos", "videos", "sound")
        val kindIcons = listOf(R.drawable.ic_d_photo, R.drawable.ic_d_video, R.drawable.ic_d_sound)
        val kindLabels = listOf("Photos", "Videos", "Sound")
        var chips: Ui.Chips? = null
        var blockSome = false
        val offHere = Requests.kindList(mediaKind)
        if (!mediaBack) {
            if (action == Requests.Action.ALLOW) {
                d.add(Ui.label(this, "Block"))
                chips = Ui.Chips(this, kindLabels, kindIcons, emptySet(), tinyBar).also { d.add(it.view, 6) }
            } else {
                d.add(Ui.label(this, "Block"))
                val c = Ui.Chips(this, kindLabels, kindIcons, setOf(0, 1, 2), tinyBar)
                c.view.visibility = View.GONE
                chips = c
                d.add(Ui.Segmented(this, listOf("Completely", "Only some of it"), 0, narrow) {
                    blockSome = it == 1
                    c.view.visibility = if (blockSome) View.VISIBLE else View.GONE
                }.view, 6)
                d.add(c.view, 8)
            }
        } else if (offHere.size > 1 && mediaItem == null) {
            // More than one is off here: which to ask for (all of them to start with).
            d.add(Ui.label(this, "Which?"))
            chips = Ui.Chips(this, offHere.map { kindLabels[kindNames.indexOf(it)] }, offHere.map { kindIcons[kindNames.indexOf(it)] },
                offHere.indices.toSet(), tinyBar).also { d.add(it.view, 6) }
        }

        val noteField = Ui.field(this, "e.g. for maths homework",
            type = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        d.add(Ui.label(this, "Why? (optional)"))
        d.add(noteField, 6)

        // (Approving with the PIN is in "My requests" now: tap its title 7 times. The switch isn't shown here,
        // so no PIN is sent with a new request.)

        d.button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
        sendButton = d.button(if (filteredNow.isNotEmpty()) "Ask anyway" else "Send", Ui.Kind.PRIMARY) {
            val domain = siteDomain ?: Whitelist.normalize(siteField.text.toString())
            if (domain == null) {
                siteField.error = "Type a website address"
                return@button
            }
            val scope = when (itemChoice) {
                0, 1 -> if (canChoose) Requests.Scope.PAGE else Requests.Scope.SITE   // one item, or this page
                2 -> Requests.Scope.SITE
                else -> if (canChoose && pageScope) Requests.Scope.PAGE else Requests.Scope.SITE
            }
            val subject = if (scope == Requests.Scope.PAGE) pageKey!! else domain
            // Which of photos, videos and sound: the chips that are on.
            val picked = chips?.let { c -> c.selected.sorted().map { i ->
                if (mediaBack) offHere[i] else kindNames[i] } } ?: emptyList()
            val media = when {
                mediaBack -> Requests.Media.ON
                action == Requests.Action.ALLOW && picked.isNotEmpty() -> Requests.Media.OFF
                action == Requests.Action.BLOCK && blockSome -> Requests.Media.OFF
                else -> Requests.Media.UNCHANGED
            }
            if (action == Requests.Action.BLOCK && blockSome && picked.isEmpty()) {
                toast("Pick what to block, or choose Completely"); return@button
            }
            if (mediaBack && chips != null && picked.isEmpty()) { toast("Pick at least one"); return@button }
            val kind = when {
                mediaBack && chips == null -> mediaKind
                picked.isEmpty() -> "both"
                else -> picked.joinToString(",")
            }
            val item = if (itemChoice == 0) mediaItem else null
            val tile = !(action == Requests.Action.ALLOW && !mediaBack && !tileSwitch.isChecked)
            val timeMode = if (action == Requests.Action.ALLOW && forAWhile && useBox.isChecked) "use" else null
            val minutes = if (action == Requests.Action.ALLOW && forAWhile)
                hoursWheel.value * 60 + minutesWheel.value * 5 else 0
            if (action == Requests.Action.ALLOW && forAWhile && minutes == 0) {
                toast("Choose how long on the wheels, or pick Always") // the sheet stays open
                return@button
            }
            if (Requests.recentlySent(this, action, Requests.sentKey(subject, media, kind))) {
                d.dismiss()                                          // first, so the message isn't closed with it
                toast("You already asked about $subject. Wait for an answer, or cancel it in My requests to ask again.")
                return@button
            }
            val note = noteField.text.toString().trim()
            // "Send anyway" after the site couldn't be found: send it, marked as not found.
            if (siteDomain == null && sendAnyway == domain) {
                d.dismiss()
                sendRequest(action, scope, media, domain, null, note, hops, minutes, unverified = true, mediaKind = kind, tile = tile, timeMode = timeMode, item = item)
                return@button
            }
            if (siteDomain != null) {
                d.dismiss()
                sendRequest(action, scope, media, domain, pageUrl, note, hops, minutes, mediaKind = kind, tile = tile, timeMode = timeMode, item = item)
                return@button
            }
            // A typed site on a content filter's list: say which, and ask them to confirm first.
            val typedFiltered = if (action == Requests.Action.ALLOW) Whitelist.filteredAs(domain) else emptyList()
            if (typedFiltered.isNotEmpty() && askAnyway != domain) {
                filterWarning.text = filterWarningText(domain, typedFiltered, pinNote = Whitelist.state.pinApproval)
                filterWarning.visibility = View.VISIBLE
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
                        sendRequest(action, scope, media, domain, null, note, hops, minutes, mediaKind = kind, tile = tile, timeMode = timeMode, item = item)
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
                            domain: String, pageUrl: String?, note: String, hops: List<Requests.Hop> = emptyList(),
                            minutes: Int = 0, unverified: Boolean = false,
                            frames: List<String> = emptyList(), mediaKind: String = "both", tile: Boolean = true,
                            timeMode: String? = null, item: String? = null) {
        // Already asked (whichever way this one came: the request sheet, embedded parts, …): not sent again.
        val subject = (if (scope == Requests.Scope.PAGE) pageUrl?.let { Whitelist.pageKey(it) } else null) ?: domain
        if (Requests.alreadyAsked(this, Requests.fullKey(action, subject, media, mediaKind, frames))) {
            toast("You already asked about this. Wait for an answer, or cancel it in My requests to ask again.")
            return
        }
        toast("Sending request")
        updateIo.execute {
            val outcome = runCatching {
                // Which content filters list it (so you see that before approving).
                val filtered = if (action == Requests.Action.ALLOW) Whitelist.filteredAs(domain) else emptyList()
                val id = Requests.queue(applicationContext, action, scope, media, domain, pageUrl, note, hops, minutes, unverified,
                    if (frames.isEmpty()) filtered else emptyList(), frames, mediaKind, tile, timeMode, item)
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

    /** Where a request was about: its page, or its site's front page. */
    private fun requestAddress(r: org.json.JSONObject): String? {
        val url = r.optString("url").takeIf { it.startsWith("http") }
        val domain = r.optString("domain").takeIf { it.isNotBlank() }
        return if (r.optString("scope") == "page" && url != null) url else url ?: domain?.let { "https://$it/" }
    }

    /**
     * Has an approved change reached this phone (do its lists show it yet)? Asked each time the list is
     * checked; until then, the answer is held back, so "can now be opened" is true when it's read.
     */
    private fun changeArrived(item: MyRequests.Item): Boolean {
        val r = item.request ?: return true                    // asked by an older app: nothing to check
        val at = requestAddress(r) ?: return true
        val kinds = Requests.kindList(r.optString("mediaKind"))
        fun kindOff(kind: String) = when (kind) {
            "photos" -> Whitelist.photosBlocked(at); "videos" -> Whitelist.videosBlocked(at); else -> Whitelist.soundBlocked(at)
        }
        val frames = r.optJSONArray("frames")
        return when {
            // Embedded parts: each one allowed inside that site now.
            frames != null && frames.length() > 0 -> (0 until frames.length()).all { i ->
                Whitelist.embedAllowed(at, "https://" + frames.optString(i).removePrefix("https://").removePrefix("http://")) }
            // One photo or video.
            r.optString("media") == "on" && r.optString("item").isNotBlank() -> Whitelist.mediaAllowed(r.optString("item"))
            // Photos, videos or sound back on.
            r.optString("media") == "on" -> kinds.none { kindOff(it) }
            // Blocking only some of it, or all of it.
            r.optString("action") == "block" && r.optString("media") == "off" -> kinds.all { kindOff(it) }
            r.optString("action") == "block" -> !Whitelist.isAllowed(at)
            // Opening it.
            else -> Whitelist.isAllowed(at)
        }
    }

    /**
     * How a request shows in My requests: an approval only once its change has reached this phone (as with the
     * answer's pop-up), until then still waiting (and not tickable in approval mode); after 10 minutes, shown anyway.
     */
    private fun shownAs(item: MyRequests.Item): MyRequests.Item {
        val holding = item.status == "approved" && !item.seen && !changeArrived(item) &&
            System.currentTimeMillis() - item.answered < 10 * 60_000L
        if (!holding) return item
        return MyRequests.Item(item.number, item.summary, item.asked, "waiting", "Waiting for an answer", 0L, true,
            item.archived, item.request, pinChecking = true)
    }

    /** The technical details at this moment (put at the top of a shared log, rather than shown in About this phone). */
    private fun snapshot(): String {
        val pageHost = web.url?.takeUnless { HomePage.isHome(it) || it.startsWith(BLOCKED_PAGE) }?.let { Uri.parse(it).host }
        return listOf(
            "Setting up: ${Requests.setupStatus(this)}",
            "Ad blocking: ${AdRules.status}",
            "Ad lists: ${AdRules.listsSummary.ifEmpty { "(not loaded)" }}",
            "Ad blocking on the open page: " + AdRules.describe(pageHost, adGroups(pageHost),
                androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)),
            "Groups on: ${adGroups().joinToString().ifEmpty { "none" }}; blocked since the app started: ${AdBlock.blockedCount.get()}",
            "Now playing: ${PlaybackService.lastPlaying.ifEmpty { "nothing" }}",
            "Admin phone: " + (if (!AdminAlerts.isAdminPhone()) "no" else "yes; phone notifications " +
                (if (AdminAlerts.wanted(this)) "on" else "off") + "; Android " + (if (AdminAlerts.allowed(this)) "allows them" else "doesn't allow them") +
                "; last check: " + AdminAlerts.lastCheck(this)),
            "Desktop sites: ${desktopSites().joinToString().ifEmpty { "none" }}",
            "Waiting to send: ${Outbox.count(this)}" + (Outbox.lastProblem?.let { " ($it)" } ?: "")
        ).joinToString("\n") { "  $it" }
    }

    /** The log as it's shared or sent: a heading, the details at this moment, the entries, and the last crash. */
    private fun logReport(): String {
        val all = AppLog.text()
        val tail = if (all.length > 150_000) "(earlier entries left out)\n" + all.takeLast(150_000).substringAfter('\n') else all
        return "Whitelist Browser log · phone ${Device.id(this)} · ${Device.name(this) ?: ""} · version ${BuildConfig.VERSION_NAME}\n\n" +
            "Now:\n" + snapshot() + "\n\n" + tail + (CrashLog.last(this)?.let { "\n\nLast crash:\n$it" } ?: "")
    }

    /** Sends the log to whoever manages this browser (sealed; queued until online). */
    private fun sendLog(why: String, quiet: Boolean = false) {
        val text = logReport()
        io.execute {
            Requests.queueLog(applicationContext, why, text)
            Outbox.flush(applicationContext)
        }
        if (!quiet) toast("Sending the log to whoever manages this browser")
    }

    /** About this phone → Share log (admin phones): Android's share menu (WhatsApp, email, Drive, save to a file…). */
    private fun shareLog() {
        val text = logReport()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Whitelist Browser log")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, "Share the log")) }.onFailure { toast("Couldn't share the log: ${it.message}") }
    }

    /** Is the open page the one [item] was about (so reloading it shows the change)? */
    private fun aboutThisPage(item: MyRequests.Item): Boolean {
        val r = item.request ?: return false
        val cur = web.url ?: return false
        if (HomePage.isHome(cur) || cur.startsWith(BLOCKED_PAGE)) return false
        val at = requestAddress(r) ?: return false
        return if (r.optString("scope") == "page") Whitelist.pageKey(cur) == Whitelist.pageKey(at)
        else siteScope(cur) != null && siteScope(cur) == siteScope(at)
    }

    /**
     * Pops up answers that arrived since the user last looked. Approvals only once the change has reached
     * the phone; if the open page is the one it's about, with "Refresh now".
     */
    private fun showNewAnswers() {
        val fresh = MyRequests.takeNewAnswers(this) { changeArrived(it) }
        // Approvals still on their way to the phone: keep checking quickly until they arrive.
        if (MyRequests.anyHeld(this)) fastChecks(5)
        if (fresh.isEmpty()) return
        // "Refresh now" only where the app can't apply it to the open page by itself: an embedded part, or one
        // photo or video. (A site that was blocked opens by itself; photos, videos and sound reload the page.)
        val refresh = fresh.any { item ->
            val r = item.request
            item.status == "approved" && r != null && aboutThisPage(item) &&
                ((r.optJSONArray("frames")?.length() ?: 0) > 0 || r.optString("item").isNotBlank())
        }
        Ui.AppDialog(this, sheet = false).apply {
            title(if (fresh.size == 1) "Answer to your request" else "Answers to your requests")
            val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            fresh.forEachIndexed { i, answer ->
                list.addView(requestRow(answer.status, answer.summary, answer.message, null, card = true), LinearLayout.LayoutParams(-1, -2).apply {
                    if (i > 0) topMargin = Ui.dp(this@MainActivity, 10)
                })
            }
            add(list)
            if (refresh) {
                add(Ui.text(this@MainActivity, "Refresh this page to see the change.", 14.5f, Ui.INK2), 10)
                button("Later", Ui.Kind.GHOST) { it.dismiss() }
                button("Refresh now", Ui.Kind.PRIMARY) { it.dismiss(); web.reload() }
            } else {
                button("My requests", Ui.Kind.SECONDARY) { it.dismiss(); showMyRequests() }
                button("OK", Ui.Kind.PRIMARY) { it.dismiss() }
            }
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
            "cancelled" -> RowLook(R.drawable.ic_d_x, Ui.SEG, Ui.INK2, "Cancelled")
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
     * ⋮ → My requests. Answered requests: swipe right to archive (green), left to delete (red), or tap for
     * the same choices. They also move to the archive by themselves after 30 days. [archive]: show the
     * archive instead, where swiping right puts one back and left deletes it. Waiting and not-yet-sent
     * requests always stay in the main list.
     */
    private fun showMyRequests(archive: Boolean = false, approving: Boolean = false) {
        val d = Ui.AppDialog(this, sheet = true)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gap = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) }
        fun archivedCount() = MyRequests.all(this).count { it.archived }
        fun whenText(r: MyRequests.Item) = if (r.status == "waiting") "Asked ${shortDate(r.asked)}" else "Answered ${shortDate(r.answered)}"
        if (!archive) {
            val notSent = Outbox.waitingRequestsWithIds(this).reversed()
            val sent = MyRequests.all(this).filter { !it.archived }.map { shownAs(it) }
            var archiveButton: Button? = null
            d.title("My requests")
            // Tapping the title 7 times (within a few seconds): approval mode, to approve or deny with the PIN.
            var taps = 0
            var firstTap = 0L
            d.titleView?.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - firstTap > 4000) { firstTap = now; taps = 0 }
                if (++taps >= 7) {
                    taps = 0
                    when {
                        approving -> Unit
                        !Whitelist.state.pinApproval -> toast("No approval PIN is set for this phone")
                        else -> { d.dismiss(); showMyRequests(approving = true) }
                    }
                }
            }
            // Approval mode: the waiting requests ticked, then approved or denied with the PIN (typed once).
            val picked = LinkedHashSet<MyRequests.Item>()
            var approveButton: Button? = null
            var denyButton: Button? = null
            fun updateCounts() {
                val n = picked.size
                approveButton?.text = if (n > 0) "Approve ($n)" else "Approve"
                denyButton?.text = if (n > 0) "Deny ($n)" else "Deny"
            }
            if (approving) {
                val banner = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = Ui.rounded(Ui.AMBER_BG, Ui.dp(this@MainActivity, 14).toFloat())
                    val p = Ui.dp(this@MainActivity, 12)
                    setPadding(p, Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 8))
                }
                banner.addView(Ui.text(this, "Approval mode. Tick requests, then approve or deny them with the PIN.", 13.5f, Ui.AMBER_INK, "bold"),
                    LinearLayout.LayoutParams(0, -2, 1f))
                banner.addView(Button(this).apply {
                    text = "Exit"
                    isAllCaps = false
                    setTextColor(Ui.AMBER_INK)
                    typeface = Ui.boldFace
                    background = null
                    minWidth = Ui.dp(this@MainActivity, 56); minimumWidth = Ui.dp(this@MainActivity, 56)
                    setOnClickListener { d.dismiss(); showMyRequests() }
                })
                d.add(banner, 6)
            } else if (notSent.isEmpty() && sent.isEmpty()) {
                d.add(Ui.text(this, "Nothing here right now.", 15f, Ui.MUTED))
            } else {
                d.add(Ui.text(this, "Swipe a request right to archive it, or left to delete it (or cancel it, if it's still waiting).", 13f, Ui.MUTED), 4)
            }
            if (approving) {
                // Only waiting requests can be ticked; the rest are shown faded.
                notSent.forEach { (_, summary, created) ->
                    list.addView(requestRow("notsent", summary, "Not sent yet.", "Asked ${shortDate(created)}", card = true).apply { alpha = 0.5f },
                        LinearLayout.LayoutParams(gap))
                }
                sent.forEach { item ->
                    val row = requestRow(item.status, item.summary, item.message, whenText(item), card = true)
                    // Answered, or a PIN already sent for it (it comes back if the PIN was wrong): shown faded, not tickable.
                    if (item.status != "waiting" || item.pinChecking) { row.alpha = 0.5f; list.addView(row, LinearLayout.LayoutParams(gap)); return@forEach }
                    val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                    val box = android.widget.CheckBox(this).apply {
                        buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                        contentDescription = "Choose ${item.summary}"
                        minWidth = Ui.dp(this@MainActivity, 44); minimumWidth = Ui.dp(this@MainActivity, 44)
                    }
                    val r = Ui.dp(this, 16).toFloat()
                    box.setOnCheckedChangeListener { _, on ->
                        if (on) picked += item else picked -= item
                        row.background = if (on) Ui.rounded(Ui.CARD, r, Ui.ACCENT, Ui.dp(this, 2)) else Ui.rounded(Ui.CARD, r, Ui.LINE, Ui.dp(this, 1))
                        updateCounts()
                    }
                    row.setOnClickListener { box.toggle() }
                    wrap.addView(box)
                    wrap.addView(row, LinearLayout.LayoutParams(0, -2, 1f))
                    list.addView(wrap, LinearLayout.LayoutParams(gap))
                }
                d.add(list, 8)
                // Nothing that can be ticked (e.g. all waiting for their PIN to be checked): no Approve or Deny.
                if (sent.none { it.status == "waiting" && !it.pinChecking }) {
                    if (sent.any { it.pinChecking }) d.add(Ui.text(this, "Waiting for these to be answered.", 13.5f, Ui.MUTED), 8)
                    d.show()
                    return
                }
                denyButton = d.button("Deny", Ui.Kind.DANGER) {
                    if (picked.isEmpty()) toast("Tick the requests first")
                    else answerWithPin(picked.toList(), approve = false) { d.dismiss(); showMyRequests(approving = true) }
                }
                approveButton = d.button("Approve", Ui.Kind.PRIMARY) {
                    if (picked.isEmpty()) toast("Tick the requests first")
                    else answerWithPin(picked.toList(), approve = true) { d.dismiss(); showMyRequests(approving = true) }
                }
                d.show()
                return
            }
            notSent.forEach { (id, summary, created) ->
                val row = requestRow("notsent", summary, "No connection: it's sent when the phone is online.", "Asked ${shortDate(created)}", card = true)
                lateinit var holder: View
                val dontSend = { Outbox.cancel(this, id); list.removeView(holder); toast("It won't be sent") }
                holder = swipeRow(row, right = null, left = SwipeAction("Don't send", R.drawable.ic_d_x, Ui.DANGER, dontSend))
                list.addView(holder, LinearLayout.LayoutParams(gap))
            }
            sent.forEach { item ->
                val row = requestRow(item.status, item.summary, item.message, whenText(item), card = true)
                if (item.status == "waiting") {
                    // Waiting: it can be withdrawn (closed on GitHub too, so nobody answers it for nothing).
                    lateinit var waitingHolder: View
                    val cancelIt = {
                        toast("Cancelling the request")
                        updateIo.execute {
                            val ok = runCatching { Requests.cancel(item.number) }.getOrDefault(false)
                            main.post {
                                if (isDestroyed) return@post
                                if (ok) {
                                    MyRequests.markCancelled(this, item.asked)
                                    list.removeView(waitingHolder)
                                    toast("Request cancelled")
                                } else toast("Couldn't cancel it right now (no internet?). Try again later.")
                            }
                        }
                    }
                    // The card springs back and stays until GitHub has withdrawn it (it may not work offline).
                    waitingHolder = swipeRow(row, right = null, left = SwipeAction("Cancel", R.drawable.ic_d_x, Ui.DANGER, cancelIt, staysUntilDone = true))
                    list.addView(waitingHolder, LinearLayout.LayoutParams(gap))
                    return@forEach
                }
                lateinit var holder: View
                val removed = { list.removeView(holder); archiveButton?.text = "Archived (${archivedCount()})" }
                val archiveIt = { MyRequests.setArchived(this, item.asked, true); removed(); toast("Archived") }
                val deleteIt = { MyRequests.delete(this, item.asked); removed(); toast("Deleted") }
                holder = swipeRow(row,
                    right = SwipeAction("Archive", R.drawable.ic_d_archive, Ui.ACCENT, archiveIt),
                    left = SwipeAction("Delete", R.drawable.ic_d_trash, Ui.DANGER, deleteIt))
                list.addView(holder, LinearLayout.LayoutParams(gap))
            }
            d.add(list, 8)
            archiveButton = d.button("Archived (${archivedCount()})", Ui.Kind.SECONDARY) { it.dismiss(); showMyRequests(archive = true) }
            d.button("Close", Ui.Kind.PRIMARY) { it.dismiss() }
        } else {
            val items = MyRequests.all(this).filter { it.archived }
            d.title("Archived requests")
            d.add(Ui.text(this, if (items.isEmpty()) "Nothing archived." else
                "Swipe one right to put it back, or left to delete it. Only this phone's copy is deleted.", 13f, Ui.MUTED), 4)
            items.forEach { item ->
                val row = requestRow(item.status, item.summary, item.message, whenText(item), card = true)
                lateinit var holder: View
                val putBack = { MyRequests.setArchived(this, item.asked, false); list.removeView(holder); toast("Put back in My requests") }
                val deleteIt = { MyRequests.delete(this, item.asked); list.removeView(holder); toast("Deleted") }
                holder = swipeRow(row,
                    right = SwipeAction("Put back", R.drawable.ic_d_undo, Ui.ACCENT, putBack),
                    left = SwipeAction("Delete", R.drawable.ic_d_trash, Ui.DANGER, deleteIt))
                list.addView(holder, LinearLayout.LayoutParams(gap))
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

    /**
     * Approval mode: asks for the approval PIN once, then sends it for each of [items] (as a hidden note, which
     * GitHub deletes at once). GitHub checks it, then answers each as asked ([approve]), or denies it.
     */
    private fun answerWithPin(items: List<MyRequests.Item>, approve: Boolean, after: () -> Unit) {
        val n = items.size
        val field = Ui.field(this, "Approval PIN",
            type = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        Ui.AppDialog(this, sheet = false).apply {
            title(if (approve) "Approve $n request${if (n == 1) "" else "s"}?" else "Deny $n request${if (n == 1) "" else "s"}?",
                icon = R.drawable.ic_d_pin, iconBg = if (approve) Ui.SOFT else Ui.RED_BG, iconFg = if (approve) Ui.ACCENT_TEXT else Ui.RED_INK)
            add(Ui.text(this@MainActivity, items.joinToString("\n") { "• " + it.summary }, 14.5f, Ui.INK2))
            add(Ui.label(this@MainActivity, "Approval PIN"))
            add(field, 6)
            button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
            button(if (approve) "Approve" else "Deny", if (approve) Ui.Kind.PRIMARY else Ui.Kind.DANGER) { dlg ->
                val pin = field.text.toString().trim()
                if (!Regex("^\\d{4,8}$").matches(pin)) { field.error = "The PIN is 4 to 8 digits"; return@button }
                dlg.dismiss()
                toast("Sending")
                updateIo.execute {
                    // One PIN note for all of them (GitHub checks the PIN once).
                    val ok = runCatching { Requests.answerWithPin(items.map { it.number }, pin, approve) }.getOrDefault(false)
                    val sent = if (ok) items else emptyList()
                    main.post {
                        if (isDestroyed) return@post
                        if (sent.isEmpty()) { toast("Couldn't send it (no internet?). Try again later."); return@post }
                        MyRequests.markCheckingPin(this@MainActivity, sent.map { it.number })
                        toast(if (sent.size < n) "Sent ${sent.size} of $n. Answers arrive within a minute." else "Sent. Answers arrive within a minute.")
                        fastChecks(10)
                        after()
                    }
                }
            }
        }.show()
    }

    /** What swiping a row one way does: its word and icon on the trail, the trail's colour, and the action. */
    private class SwipeAction(val label: String, val icon: Int, val color: Int, val run: () -> Unit, val staysUntilDone: Boolean = false)

    /**
     * Puts [row] on a coloured trail, like Gmail: swiping right shows [right]'s colour, icon and word on
     * the left; swiping left shows [left]'s on the right. Let go past a third of the way and the row
     * slides off and its space closes, then the action runs; let go sooner and it springs back. Only a
     * clearly sideways move starts it, so the list still scrolls; a plain tap still works as a tap.
     * Returns the holder to add to the list (instead of [row]).
     */
    private fun swipeRow(row: View, right: SwipeAction?, left: SwipeAction): View {
        val radius = Ui.dp(this, 16).toFloat()
        val pad = Ui.dp(this, 20)
        val icon = android.widget.ImageView(this).apply { imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE) }
        val word = Ui.text(this, "", 14.5f, android.graphics.Color.WHITE, "bold")
        val trail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            visibility = View.INVISIBLE
        }
        val holder = android.widget.FrameLayout(this)
        holder.addView(trail, android.widget.FrameLayout.LayoutParams(-1, -1))
        holder.addView(row, android.widget.FrameLayout.LayoutParams(-1, -2))

        // Shows the trail for this direction: its colour, and its icon and word on the uncovered side.
        var shown = 0
        fun showTrail(direction: Int) {
            if (direction == shown) return
            shown = direction
            if (direction == 0) { trail.visibility = View.INVISIBLE; return }
            val a = (if (direction > 0) right else left) ?: return
            trail.background = Ui.rounded(a.color, radius)
            icon.setImageResource(a.icon)
            word.text = a.label
            trail.removeAllViews()
            val gapPx = Ui.dp(this, 8)
            if (direction > 0) {
                trail.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                trail.addView(icon, LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)).apply { marginEnd = gapPx })
                trail.addView(word)
            } else {
                trail.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
                trail.addView(word)
                trail.addView(icon, LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)).apply { marginStart = gapPx })
            }
            trail.visibility = View.VISIBLE
        }

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
                        val d = if (dx > 0 && right == null) 0f else dx       // no action that way: it doesn't move
                        v.translationX = d
                        showTrail(if (d > 0) 1 else if (d < 0) -1 else 0)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    val action = if (dx > 0) right else left
                    if (dragging && action != null && action.staysUntilDone && e.actionMasked == android.view.MotionEvent.ACTION_UP && Math.abs(dx) > v.width / 3f) {
                        // It takes a moment (e.g. GitHub): spring back, then do it; it removes the card when done.
                        v.animate().translationX(0f).setDuration(160).withEndAction { showTrail(0); action.run() }.start()
                    } else if (dragging && action != null && e.actionMasked == android.view.MotionEvent.ACTION_UP && Math.abs(dx) > v.width / 3f) {
                        // Slide off, close the gap, then do it.
                        v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat())
                            .setDuration(160).withEndAction {
                                val start = holder.height
                                val shrink = android.animation.ValueAnimator.ofInt(start, 0).setDuration(180)
                                shrink.addUpdateListener { a ->
                                    holder.layoutParams = holder.layoutParams.apply { height = a.animatedValue as Int }
                                }
                                shrink.addListener(object : android.animation.AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: android.animation.Animator) { action.run() }
                                })
                                shrink.start()
                            }.start()
                    } else {
                        v.animate().translationX(0f).setDuration(160).withEndAction { showTrail(0) }.start()
                        val moved = Math.abs(dx) > slop || Math.abs(e.rawY - downY) > slop
                        if (!dragging && !moved && e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
        return holder
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
        Whitelist.isAllowed(url, false) || Whitelist.framesAllowedOn(top) || Whitelist.embedAllowed(top, url)

    /** What shows in a blocked frame's place: a small note, in the app's look. */
    private fun frameBlockedResponse(host: String, frameUrl: String): WebResourceResponse {
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
        val site = host.removePrefix("www.").replace("<", "")
        val html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<body style='margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;" +
            "background:${hex(Ui.SEG)};color:${hex(Ui.MUTED)};font:600 14px/1.4 sans-serif;text-align:center;padding:8px;box-sizing:border-box'>" +
            "<div><div>Blocked: content from $site</div>" +
            (if (Requests.isSetUp()) "<a href='wlb://ask-frames?src=${Uri.encode(frameUrl)}' target='_top' style='display:inline-block;margin-top:10px;padding:9px 16px;" +
                "border-radius:999px;background:${hex(Ui.ACCENT)};color:#fff;text-decoration:none;font-weight:700'>Ask for it</a>" else "") +
            "</div></body>"
        return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), java.io.ByteArrayInputStream(html.toByteArray()))
    }

    /** Records a blocked frame's site, and shows the "parts were blocked" bar. (Any thread.) */
    /** A frame's exact address for asking: host + path, without "?..." (which often changes). */
    private fun frameKey(url: String): String? {
        val u = Uri.parse(url)
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return null
        return host + (u.path ?: "").trimEnd('/')
    }

    private fun noteBlockedFrame(host: String, frameUrl: String? = null) {
        val site = host.lowercase().removePrefix("www.")
        val isNew = synchronized(blockedFrames) {
            val known = blockedFrames.containsKey(site)
            val parts: MutableSet<String> = blockedFrames.getOrPut(site) { LinkedHashSet() }
            val key = if (frameUrl != null) frameKey(frameUrl) else null
            if (key != null) parts.add(key)
            !known
        }
        if (isNew) main.post { showFrameNote() }
    }

    private fun showFrameNote() {
        val top = web.url
        val note = findViewById<View>(R.id.frameNote)
        if (top == null || HomePage.isHome(top) || top.startsWith(BLOCKED_PAGE) || blockedFrames.isEmpty() || top == frameNoteClosedFor) return
        val sites = synchronized(blockedFrames) { blockedFrames.keys.toList() }
        findViewById<TextView>(R.id.frameNoteText).text = "Parts of this page were blocked (from ${sites.first()}" +
            (if (sites.size > 1) " and ${sites.size - 1} more)" else ")")
        findViewById<View>(R.id.frameNoteAsk).visibility = if (Requests.isSetUp()) View.VISIBLE else View.GONE
        note.visibility = View.VISIBLE
    }

    private fun hideFrameNote() {
        findViewById<View>(R.id.frameNote)?.visibility = View.GONE
    }

    /**
     * "Ask" on the bar (or "Ask for it" in a blocked part, [focus]: its address): a request for the blocked
     * parts, to show inside this site's pages. For each blocked site: a tick box, and "Just this one" (only
     * that video, map or box) or "Everything from it".
     */
    private fun showFramesRequest(focus: String? = null) {
        val top = web.url ?: return
        val site = siteScope(top) ?: return
        val blocked = synchronized(blockedFrames) { blockedFrames.mapValues { it.value.toList() } }
            .filterKeys { it != site && !it.endsWith(".$site") }
        if (blocked.isEmpty()) { hideFrameNote(); return }
        if (!Requests.isSetUp()) { toast("Requests aren't set up for this app yet"); return }
        val focusKey = focus?.let { frameKey(it) }
        val focusSite = focusKey?.substringBefore('/')
        val d = Ui.AppDialog(this, sheet = true)
        d.title("Ask for the blocked parts", sub = site)
        d.add(Ui.text(this, "Parts of this page, like a video or a map, come from other sites and were blocked. " +
            "If they're approved, they show inside $site's pages only.", 14.5f, Ui.MUTED), 6)
        // One card per blocked site: ticked (all, or just the one tapped), and just these parts or everything.
        class Choice(val site: String, val parts: List<String>, var ticked: Boolean, var everything: Boolean)
        val choices = blocked.map { (s, parts) -> Choice(s, parts, focusSite == null || focusSite == s, false) }
        choices.forEach { c ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 14).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
                val p = Ui.dp(this@MainActivity, 12)
                setPadding(p, Ui.dp(this@MainActivity, 4), p, p)
            }
            val box = android.widget.CheckBox(this).apply {
                text = c.site
                isChecked = c.ticked
                setTextColor(Ui.INK)
                typeface = Ui.boldFace
                textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                minHeight = Ui.dp(this@MainActivity, 44)
            }
            val many = c.parts.size > 1
            val justLabel = if (many) "Just these ${c.parts.size}" else "Just this one"
            val which = Ui.Segmented(this, listOf(justLabel, "Everything from it"), 0, narrow) { c.everything = it == 1 }.view
            which.visibility = if (c.ticked) View.VISIBLE else View.GONE
            box.setOnCheckedChangeListener { _, on -> c.ticked = on; which.visibility = if (on) View.VISIBLE else View.GONE }
            card.addView(box)
            if (c.parts.isNotEmpty()) card.addView(Ui.text(this, c.parts.joinToString("\n") { it.substringAfter('/', "").let { p -> if (p.isEmpty()) it else "/$p" } }, 12.5f, Ui.MUTED).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Ui.dp(this@MainActivity, 4), 0, 0, Ui.dp(this@MainActivity, 6))
            })
            card.addView(which, LinearLayout.LayoutParams(-1, -2))
            d.add(card, 8)
        }
        val noteField = Ui.field(this, "e.g. the video for my homework",
            type = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        d.add(Ui.label(this, "Why? (optional)"))
        d.add(noteField, 6)
        d.button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
        d.button("Send", Ui.Kind.PRIMARY) {
            // Just the parts (their exact addresses), or the whole site, for each ticked one.
            val chosen = choices.filter { it.ticked }.flatMap { c ->
                if (c.everything || c.parts.isEmpty()) listOf(c.site) else c.parts
            }
            if (chosen.isEmpty()) { toast("Tick at least one, or tap Cancel"); return@button }
            it.dismiss()
            hideFrameNote()
            sendRequest(Requests.Action.ALLOW, Requests.Scope.SITE, Requests.Media.UNCHANGED, site, top,
                noteField.text.toString().trim(), frames = chosen)
        }
        d.show()
    }

    // ---------- settings ----------

    /** ⋮ → Settings: appearance, cookies and site data, cache, app update, and about this phone. */
    private fun showSettings() {
        val d = Ui.AppDialog(this, sheet = true)
        d.title("Settings")
        fun row(icon: Int, title: String, sub: String, onTap: () -> Unit): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = Ui.dp(this@MainActivity, 14)
            setPadding(p, Ui.dp(this@MainActivity, 12), p, Ui.dp(this@MainActivity, 12))
            minimumHeight = Ui.dp(this@MainActivity, 60)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.SEG), null, android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
            addView(Ui.badge(this@MainActivity, icon, Ui.SOFT, Ui.ACCENT_TEXT, 38, 12).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(this@MainActivity, 12)
            })
            val words = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            words.addView(Ui.text(this@MainActivity, title, 15.5f, Ui.INK, "bold"))
            words.addView(Ui.text(this@MainActivity, sub, 13f, Ui.MUTED))
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { d.dismiss(); onTap() }
        }
        val look = when (Ui.choice(this)) { "light" -> "Light"; "dark" -> "Dark"; else -> "Phone's setting" } +
            if (Ui.usePhoneColours(this)) ", phone's colours" else ""
        val name = Device.name(this) ?: "Not registered yet"
        val rows = listOf(
            row(R.drawable.ic_d_theme, "Appearance", look) { showAppearance() },
            *(if (AdminAlerts.isAdminPhone()) arrayOf(row(R.drawable.ic_d_inbox, "Phone notifications",
                when {
                    !AdminAlerts.wanted(this@MainActivity) -> "Off"
                    !AdminAlerts.allowed(this@MainActivity) -> "Blocked by Android: tap to allow notifications"
                    else -> "On: new requests, logs and crashes, as notifications on this phone"
                }) {
                // On, but Android blocks them: its settings for this app's notifications.
                if (AdminAlerts.wanted(this@MainActivity) && !AdminAlerts.allowed(this@MainActivity)) {
                    runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                    return@row
                }
                val on = !AdminAlerts.wanted(this@MainActivity)
                AdminAlerts.setWanted(this@MainActivity, on)
                if (on && Build.VERSION.SDK_INT >= 33) withAndroidPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { granted ->
                    if (granted.isEmpty()) toast("Notifications are off for this app in the phone's settings")
                }
                toast(if (on) "Phone notifications on" else "Phone notifications off")
                showSettings()                                    // (the row closed the sheet: open again, updated)
            }) else emptyArray<LinearLayout>()),
            row(R.drawable.ic_d_globe, "Phone's browser",
                if (isPhonesBrowser()) "This is the phone's browser: links from other apps open here"
                else "Make it the phone's browser, so links from other apps open here") { becomePhonesBrowser() },
            row(R.drawable.ic_d_cookie, "Cookies and site data", "Sign out of sites, reset camera and location answers") { confirmClearCookies() },
            row(R.drawable.ic_d_broom, "Clear cache", "Frees space; pages load fresh") { clearCache() },
            row(R.drawable.ic_d_update, "App update", "Version ${BuildConfig.VERSION_NAME}. Check for a new one") { checkForUpdate(manual = true) },
            row(R.drawable.ic_d_user, "About this phone", "$name · ${Device.id(this)}") { showAbout() })
        d.add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, Ui.dp(this@MainActivity, 16).toFloat(), Ui.LINE, Ui.dp(this@MainActivity, 1))
            clipToOutline = true
            rows.forEachIndexed { i, r ->
                if (i > 0) addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE2) }, LinearLayout.LayoutParams(-1, Ui.dp(this@MainActivity, 1)))
                addView(r)
            }
        })
        d.button("Close", Ui.Kind.PRIMARY) { it.dismiss() }
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
            // The phone's own colours, from its wallpaper: only on Android 12 and newer.
            val wasPhoneColours = Ui.usePhoneColours(this@MainActivity)
            val (colourRow, colourSwitch) = Ui.switchRow(this@MainActivity, "Use my phone's colours", "Matches your wallpaper", wasPhoneColours)
            if (Ui.phoneColoursAvailable) add(colourRow)
            button("Cancel", Ui.Kind.GHOST) { it.dismiss() }
            button("Done", Ui.Kind.PRIMARY) {
                it.dismiss()
                val colourChanged = Ui.phoneColoursAvailable && colourSwitch.isChecked != wasPhoneColours
                if (colourChanged) Ui.setPhoneColours(this@MainActivity, colourSwitch.isChecked)
                if (picked != current) Ui.setChoice(this@MainActivity, options[picked].first)
                if (colourChanged || (picked != current && Ui.wantsDark(this@MainActivity) != Ui.dark)) recreate()   // redraw (pages are kept)
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
        soundOffHere = Whitelist.soundBlocked(url)
        // Android's "no images at all" switch can't make exceptions: with single photos allowed, keep it on and
        // let the app block photos one by one (it knows which are allowed).
        val loadImages = !photosOffHere || Whitelist.mediaAllowList().isNotEmpty()
        if (web.settings.loadsImagesAutomatically != loadImages) web.settings.loadsImagesAutomatically = loadImages
    }

    /** Have photos or videos been switched on or off for [url] since the page opened? */
    private fun mediaChanged(url: String?) =
        Whitelist.photosBlocked(url) != photosOffHere || Whitelist.videosBlocked(url) != videosOffHere ||
            Whitelist.soundBlocked(url) != soundOffHere

    // ---------- first launch: their name ----------

    /**
     * Asks for the user's name the first time the app opens (works offline). It's sent when the phone
     * registers and becomes the phone's name on GitHub and the admin page, where it can be changed.
     */
    private fun askName() {
        val nameType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val firstField = Ui.field(this, "e.g. Emma", type = nameType)
        val lastField = Ui.field(this, "e.g. Smith", type = nameType)
        // First and last name: side by side, or one above the other on small screens.
        fun column(label: String, f: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.label(this@MainActivity, label))
            addView(f, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 6) })
        }
        val names = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            val gapPx = Ui.dp(this@MainActivity, 10)
            if (narrow) {
                addView(column("First name", firstField))
                addView(column("Last name", lastField), LinearLayout.LayoutParams(-1, -2).apply { topMargin = gapPx })
            } else {
                addView(column("First name", firstField), LinearLayout.LayoutParams(0, -2, 1f))
                addView(column("Last name", lastField), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gapPx })
            }
        }
        Ui.AppDialog(this, sheet = false, cancelable = false).apply {
            title("What's your name?", icon = R.drawable.ic_d_user)
            add(Ui.text(this@MainActivity, "So whoever manages this browser knows whose phone this is.", 14.5f, Ui.MUTED))
            add(names)
            button("Continue", Ui.Kind.PRIMARY) {
                val first = firstField.text.toString().trim()
                val last = lastField.text.toString().trim()
                if (first.isEmpty()) { firstField.error = "Type your first name"; return@button }
                if (last.isEmpty()) { lastField.error = "Type your last name"; return@button }
                Device.setNames(this@MainActivity, first, last)
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
            row("Lists", st.listNames.ifEmpty { listOf("none") }.joinToString(", ") { n ->
                // A phone's personal list is named after its ID: show the person's name instead.
                if (n.equals(id, ignoreCase = true)) "${st.deviceName ?: Device.name(this) ?: "This phone"} (personal list)"
                else if (n == "public") "Default public list" else n
            }),
            row("Allowed sites", st.allow.distinct().size.toString()))
        // Only while it isn't set up yet (once it is, it's in the log).
        Requests.setupStatus(this).takeIf { it != "Set up" }?.let { phone.add(2, row("Setting up", it)) }
        val waiting = Outbox.count(this)
        if (waiting > 0) phone += row("Waiting to send", "$waiting (${Outbox.lastProblem ?: "sends when online"})")
        phone += row("App version", BuildConfig.VERSION_NAME)
        // (The technical details, ad blocking, what's playing, the last crash, go in the log: Share log.)
        Ui.AppDialog(this, sheet = true).apply {
            title("About this phone", icon = R.drawable.ic_d_user)
            add(table(phone))
            add(Ui.label(this@MainActivity, "Filters"))
            add(table(listOf(
                row("Ads and trackers", if (st.adblock || st.trackers) "On · ${AdBlock.blockedCount.get()} blocked" else "Off"),
                row("Annoyances", if (st.annoyances) "On" else "Off"),
                row("Adult content", f(st.adult, Filters.adult)),
                row("Gambling", f(st.gambling, Filters.gambling)),
                row("Malware and scams", f(st.malware, Filters.malware)))), 6)
            // The log: its own section (the bottom bar keeps Copy ID and Close, which fit across a phone).
            add(Ui.label(this@MainActivity, "Log"))
            add(Ui.text(this@MainActivity, "The log lists the sites opened (names only). It's sent to whoever manages this browser only " +
                "when you tap Send to admin, when they ask for it, or after the app crashes.", 12.5f, Ui.MUTED), 4)
            val ctx = this@MainActivity
            // (Equal shares of the width, as tall as their words need: large text sizes wrap instead of being cut off.)
            fun logButton(label: String, onClick: () -> Unit) = Button(ctx).apply {
                text = label; isAllCaps = false; typeface = Ui.boldFace; stateListAnimator = null
                setTextColor(Ui.ACCENT_TEXT)
                background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 14).toFloat(), Ui.OUTLINE, Ui.dp(ctx, 1))
                minHeight = Ui.dp(ctx, 44); minimumHeight = Ui.dp(ctx, 44)
                maxLines = 2
                setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
                setOnClickListener { onClick() }
            }
            add(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(logButton("Send to admin") { sendLog("sent from the phone") },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (AdminAlerts.isAdminPhone()) addView(logButton("Share log") { shareLog() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 10) })
            }, 8)
            button("Copy ID", Ui.Kind.SECONDARY) { copy() }
            button("Close", Ui.Kind.PRIMARY) { it.dismiss() }
        }.show()
    }
}
