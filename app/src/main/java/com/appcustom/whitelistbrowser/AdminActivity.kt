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
 * that isn't limited by the whitelist. It still needs the admin token: pasted each time, or kept
 * on the phone encrypted with a PIN (the page wipes it after 5 wrong PINs).
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

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)                         // the same light or dark as the rest of the app
        setTheme(if (Ui.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        // No screenshots or app-switcher previews of the admin screen (the token is typed here).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        web = WebView(this)
        web.setBackgroundColor(Ui.PAGE)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // for the PIN-protected token
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false

        // The admin page's downloads (spreadsheets, a log): saved to the phone's Downloads. Android's browser ignores a
        // page's own downloads here.
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun save(name: String, mime: String, base64: String): Boolean = saveToDownloads(name, mime, base64)
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
            "&theme=${if (Ui.dark) "dark" else "light"}")
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
        private const val PATH = "/admin/"
        private const val PICK_FILE = 7
    }
}
