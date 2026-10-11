package com.appcustom.whitelistbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.ByteArrayInputStream

/**
 * The admin page, inside the app. Opened by tapping the name at the top of the browser 7 times.
 *
 * It's the same page as docs/admin.html (packed into the app at build time), in its own WebView
 * that isn't limited by the whitelist. Signing in is with the admin's email and password (a new phone is confirmed
 * by email), then a PIN for this phone (5 wrong PINs sign it out). No GitHub token is ever typed or kept.
 */
class AdminActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * Opens [url] outside this app: in the GitHub app if it's installed, else in another browser (not this one,
     * which only opens the sites on its lists).
     */
    private fun openOutside(url: Uri) {
        val view = Intent(Intent.ACTION_VIEW, url)
        val gh = Intent(view).setPackage("com.github.android")
        if (runCatching { startActivity(gh) }.isSuccess) return
        @Suppress("DEPRECATION")
        val other = packageManager.queryIntentActivities(view, 0).map { it.activityInfo.packageName }.firstOrNull { it != packageName }
        if (other != null && runCatching { startActivity(Intent(view).setPackage(other)) }.isSuccess) return
        Toast.makeText(this, "No other browser on this phone to open it with", Toast.LENGTH_LONG).show()
    }

    /** A file from the admin page, saved to the phone's Downloads folder. True if it was. */
    private fun saveToDownloads(name: String, mime: String, base64: String): Boolean = runCatching {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, safe).writeBytes(bytes)
        }
        AppLog.i("Download", "Saved $safe from the admin page (${bytes.size / 1024} KB)")
        true
    }.getOrElse { AppLog.e("Download", "Saving $name from the admin page failed", it); false }

    /** Fingerprint or face unlock (Android 10 and newer, set up on the phone). */
    private fun canUseBiometric(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        val bm = getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun askBiometric(done: (Boolean) -> Unit) {
        if (!canUseBiometric() || android.os.Build.VERSION.SDK_INT < 29) { done(false); return }
        val executor = mainExecutor
        var answered = false
        val once = { ok: Boolean -> if (!answered) { answered = true; done(ok) } }
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("Open the admin")
            .setSubtitle("Use your fingerprint (or face)")
            .setNegativeButton("Cancel", executor) { _, _ -> once(false) }
            .build()
        runCatching {
            prompt.authenticate(android.os.CancellationSignal(), executor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { once(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { once(false) }
            })
        }.onFailure { once(false) }
    }

    /** A link from an admin email (#confirm=…, #invite=…, #reset=…), opened in the app: the page handles it. */
    private fun linkPart(): String = intent?.getStringExtra(EXTRA_LINK)
        ?.takeIf { Regex("(confirm|invite|reset)=[A-Za-z0-9_.-]+").matches(it) }?.let { "#$it" } ?: ""

    /** Opened from a notification: what it was about (the page opens it after the PIN). */
    private fun focusParams(): String {
        val phone = intent?.getStringExtra(AdminAlerts.EXTRA_PHONE)?.takeIf { Regex("[A-Z0-9]{4}-[A-Z0-9]{4}").matches(it) }
        val file = intent?.getStringExtra(AdminAlerts.EXTRA_FILE)?.takeIf { Regex("[\\w.-]+\\.txt").matches(it) }
        val request = intent?.getIntExtra(AdminAlerts.EXTRA_REQUEST, 0) ?: 0
        return when {
            phone != null -> "&phone=" + Uri.encode(phone) + (file?.let { "&log=" + Uri.encode(it) } ?: "")
            request > 0 -> "&request=$request"
            else -> ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)                         // the same light or dark as the rest of the app
        setTheme(if (Ui.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        if (BuildConfig.ADMIN_APP) AppLog.start(applicationContext)    // (the admin app: this is the whole app)
        Updater.init(this)
        // No screenshots or app-switcher previews of the admin screen (a password is typed here).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        web = WebView(this)
        web.setBackgroundColor(Ui.PAGE)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // this phone's sign-in and its keys (kept by the page)
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false

        // The admin page's downloads (spreadsheets, a log): saved to the phone's Downloads. Android's browser ignores a
        // page's own downloads here.
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun save(name: String, mime: String, base64: String): Boolean = saveToDownloads(name, mime, base64)

            /** A problem the admin page showed (a save refused, say): kept in the app's log, to see what went wrong. */
            @android.webkit.JavascriptInterface
            fun log(text: String) { AppLog.w("Admin page", text.take(500)) }

            /** This phone's ID: so an admin can turn on notifications for this phone (Settings → Your account). */
            @android.webkit.JavascriptInterface
            fun deviceId(): String = Device.id(this@AdminActivity)

            /** Test versions of the app on this phone (Settings → Your account → This device). */
            @android.webkit.JavascriptInterface
            fun testVersions(): Boolean = Updater.testVersions(this@AdminActivity)
            @android.webkit.JavascriptInterface
            fun setTestVersions(on: Boolean) {
                Updater.setTestVersions(applicationContext, on)
                // (Look again soon: the next check finds the newest test build.)
                getSharedPreferences("adminApp", MODE_PRIVATE).edit().putLong("updateCheck", 0L).apply()
                getSharedPreferences("updates", MODE_PRIVATE).edit().putLong("lastCheck", 0L).apply()
            }

            /** The admin app: is this it (notifications work differently: it isn't a listed phone)? */
            @android.webkit.JavascriptInterface
            fun adminApp(): Boolean = BuildConfig.ADMIN_APP

            /** The admin app's own public key: its notifications are sealed with it, so only it can read them. */
            @android.webkit.JavascriptInterface
            fun notifyKey(): String = if (BuildConfig.ADMIN_APP) runCatching { Seal.publicKey() }.getOrDefault("") else ""

            /** The admin app: notifications on (or off) on this phone, as saved on the admin page. */
            @android.webkit.JavascriptInterface
            fun setNotify(on: Boolean) {
                if (!BuildConfig.ADMIN_APP) return
                AdminAlerts.setAppOn(applicationContext, on)
                if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    runOnUiThread { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 33) }
            }

            /** Fingerprint (or face) unlock: can this phone do it? */
            @android.webkit.JavascriptInterface
            fun canBiometric(): Boolean = canUseBiometric()

            /** Asks for the fingerprint; the page hears back through window.wlBioDone(true or false). */
            @android.webkit.JavascriptInterface
            fun biometric() { runOnUiThread { askBiometric { ok -> web.evaluateJavascript("window.wlBioDone && window.wlBioDone($ok)", null) } } }
        }, "WLBAdmin")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                return if (url.host == HomePage.HOST && url.path?.startsWith(PATH) == true) serve(url.path ?: "") else null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                if (url.scheme == "wladmin") {                  // the page's Close button
                    if (url.host == "close") finish()
                    // "Look at the site first" on a request: in another browser (this app opens only listed sites).
                    if (url.host == "look") url.getQueryParameter("u")?.let { Uri.parse(it) }
                        ?.takeIf { it.scheme == "https" || it.scheme == "http" }?.let { openOutside(it) }
                    return true
                }
                if (url.host == HomePage.HOST && url.path?.startsWith(PATH) == true) return false
                // GitHub's own pages (its notification settings, say): the GitHub app, or else another browser.
                val host = url.host?.lowercase().orEmpty()
                if ((url.scheme == "https") && (host == "github.com" || host.endsWith(".github.com"))) { openOutside(url); return true }
                Toast.makeText(this@AdminActivity, "Open links like this on a computer", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // The admin page's own script errors: in the app's log too.
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                    AppLog.w("Admin page", "${m.message().take(400)} (line ${m.lineNumber()})")
                return true
            }
            // "Choose a file" for spreadsheet import. (Setting a WebChromeClient also enables the page's
            // confirm/prompt dialogs.)
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params!!.createIntent(), PICK_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        web.loadUrl("https://${HomePage.HOST}${PATH}admin.html?inapp=1" +
            "&owner=${Uri.encode(Config.GITHUB_USERNAME)}&repo=${Uri.encode(PrivateRepo.NAME)}" +
            "&theme=${if (Ui.dark) "dark" else "light"}" + (if (BuildConfig.ADMIN_APP) "&app=admin" else "") + focusParams() + linkPart())
    }

    override fun onPause() {
        AdminAlerts.adminScreenOpen = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        AdminAlerts.adminScreenOpen = true
        if (BuildConfig.ADMIN_APP) {
            checkForUpdate()
            // Notifications: a look now (the background check also runs every 15 minutes or so).
            val ctx = applicationContext
            if (AdminAlerts.active(ctx)) Thread { runCatching { AdminAlerts.check(ctx) }.logged("Admin", "Checking for notes") }.start()
        }
    }

    /**
     * The admin app keeps itself up to date (the browser does this on its own screen): every 6 hours at most, the
     * newest version is downloaded in the background and installed (Android may ask first).
     */
    private fun checkForUpdate() {
        val p = getSharedPreferences("adminApp", MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("updateCheck", 0L) < Config.UPDATE_CHECK_HOURS * 3_600_000L) return
        p.edit().putLong("updateCheck", System.currentTimeMillis()).apply()
        val ctx = applicationContext
        Thread {
            runCatching {
                Updater.waiting(ctx)?.let { Updater.install(ctx, it); return@runCatching }
                val r = Updater.fetchLatest() ?: return@runCatching
                if (Updater.isNewer(r) && !Updater.downloading(ctx, r)) {
                    Updater.startDownload(ctx, r)
                    runOnUiThread { Toast.makeText(this, "Downloading an update (version ${r.versionName})", Toast.LENGTH_SHORT).show() }
                }
            }.onFailure { AppLog.w("Update", "Couldn't check for an update: ${it.message}") }
        }.start()
    }

    private fun serve(path: String): WebResourceResponse {
        val headers = mapOf("Cache-Control" to "no-store")
        if (path == "${PATH}admin.html") {
            runCatching { return WebResourceResponse("text/html", "utf-8", 200, "OK", headers, assets.open("admin.html")) }
        }
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
            ByteArrayInputStream("The admin page isn't in this build of the app.".toByteArray()))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** Back goes back a screen within the admin page, then closes it. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.wlBack && window.wlBack()) ? 'yes' : 'no'") { r ->
            if (r?.contains("yes") != true) finish()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /** The part after # of an admin email's link. */
        const val EXTRA_LINK = "adminLink"
        private const val PATH = "/admin/"
        private const val PICK_FILE = 7
    }
}
