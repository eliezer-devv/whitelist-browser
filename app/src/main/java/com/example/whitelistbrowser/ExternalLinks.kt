package com.example.whitelistbrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Links that aren't web pages (tel:, mailto:, whatsapp:, intent:, market: ...) are handed to the app
 * made for them. They never open inside this browser, and never in another web browser,
 * because that would get around the whitelist.
 */
object ExternalLinks {
    enum class Kind { WEB, EXTERNAL, FORBIDDEN }

    sealed class Result {
        data class OpenHere(val url: String) : Result()           // really a web link: open in this app (whitelist applies)
        data class Launch(val intent: Intent, val appName: String?) : Result()
        object NoApp : Result()
        object Forbidden : Result()
    }

    private val WEB = setOf("http", "https", "about", "javascript", "data", "blob")
    private val FORBIDDEN = setOf("file", "content", "chrome", "chrome-extension", "view-source", "android-app")

    fun kind(url: String): Kind {
        val scheme = Uri.parse(url).scheme?.lowercase() ?: return Kind.WEB
        return when (scheme) {
            in WEB -> Kind.WEB
            in FORBIDDEN -> Kind.FORBIDDEN
            else -> Kind.EXTERNAL
        }
    }

    @Suppress("DEPRECATION")
    fun resolve(ctx: Context, url: String): Result {
        val intent = try {
            if (url.startsWith("intent:", ignoreCase = true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (e: Exception) {
            return Result.NoApp
        }
        val fallback = intent.getStringExtra("browser_fallback_url")

        // Same safety rules as Chrome: only apps that accept links (BROWSABLE),
        // no hand-picked internal screens, no file-access grants.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        when (intent.data?.scheme?.lowercase()) {
            "http", "https" -> return Result.OpenHere(intent.dataString ?: return Result.NoApp)
            "file", "content" -> return Result.Forbidden
        }

        val pm = ctx.packageManager
        val browsers = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.toSet() + ctx.packageName

        val all = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val apps = all.filter { it.activityInfo.packageName !in browsers }

        if (apps.isEmpty()) {
            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                return Result.OpenHere(fallback)
            }
            return Result.NoApp
        }
        if (apps.size == 1) {
            val a = apps[0].activityInfo
            intent.setClassName(a.packageName, a.name)
            return Result.Launch(intent, apps[0].loadLabel(pm).toString())
        }
        // Several apps can open it: let the user pick, with browsers left out.
        val excluded = all.filter { it.activityInfo.packageName in browsers }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .toTypedArray()
        val chooser = Intent.createChooser(intent, "Open with")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Result.Launch(chooser, null)
    }
}
