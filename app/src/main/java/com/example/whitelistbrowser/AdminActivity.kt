package com.example.whitelistbrowser

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots or app-switcher previews of the admin screen (the token is typed here).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // for the PIN-protected token
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false

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
            "&owner=${Uri.encode(Config.GITHUB_USERNAME)}&repo=${Uri.encode(PrivateRepo.NAME)}")
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
