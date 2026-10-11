package com.appcustom.whitelistbrowser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Other browsers": keeping this managed browser the only way to the web on a supervised phone.
 *
 * This is parental-control / supervised-device protection, set up by whoever manages the phone with physical access
 * and device-owner or device-admin rights. It is deliberately *visible*: a blocked app shows a plain screen that
 * names this browser and says who manages it, nothing is hidden or disguised, and the admin can switch the whole
 * thing off from the admin page. It never touches apps Android itself needs.
 *
 * What's blocked is decided on the admin page (per app: allow or block) and comes down with the phone's lists
 * ([Protect]). The phone also *finds* apps that can open websites, reports their names to the admin page, and — when
 * "block new browsers straight away" is on — blocks a newly found one at once and asks the admin about it.
 */
object BrowserGuard {
    private const val TAG = "Browsers"
    private const val PREFS = "browser_guard"

    /** How the managed browser is kept in place, from the admin page. */
    data class Protect(
        val mode: String,                       // "owner" (device owner), "cover" (accessibility), or "off"
        val overlay: Boolean,                   // the screen cover is on (admin can switch it off)
        val blockNew: Boolean,                  // a newly found browser is blocked straight away
        val blockInstalls: Boolean,             // device owner: new apps need the admin's OK
        val lockClock: Boolean,                 // device owner: keep automatic date and time on
        val apps: Map<String, String>           // package -> "allow" | "block"
    )

    fun parse(o: JSONObject?): Protect? {
        if (o == null) return null
        val apps = HashMap<String, String>()
        o.optJSONObject("apps")?.let { a -> a.keys().forEach { k -> a.optString(k).let { if (it == "allow" || it == "block") apps[k] = it } } }
        return Protect(
            mode = o.optString("mode", "off").let { if (it == "owner" || it == "cover") it else "off" },
            overlay = o.optBoolean("overlay", true),
            blockNew = o.optBoolean("blockNew", true),
            blockInstalls = o.optBoolean("blockInstalls", false),
            lockClock = o.optBoolean("lockClock", true),
            apps = apps)
    }

    private val protect get() = Whitelist.state.protect
    /** Protection is set up (not off), so the phone should enforce and the cover may run. */
    fun on(): Boolean = protect?.let { it.mode != "off" } ?: false
    /** The screen cover should run now: cover mode, or device-owner mode as a backup, and the admin hasn't switched it off. */
    fun coverOn(): Boolean = protect?.let { it.mode != "off" && it.overlay } ?: false

    /** Our own package, and the Android pieces that must never be blocked. */
    private fun ownPackage(ctx: Context) = ctx.packageName
    private val NEVER_BLOCK = setOf("com.android.settings", "com.android.systemui", "android", "com.android.phone",
        "com.google.android.gms", "com.android.vending")   // settings/system/phone/Play (we cover screens, never block these)

    /** Apps on the phone that can open an https page (web browsers), except this one. Names only. */
    fun browserApps(ctx: Context): List<ResolvedApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val list = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }.logged(TAG, "Listing browsers").getOrDefault(emptyList())
        val own = ownPackage(ctx)
        val seen = HashMap<String, ResolvedApp>()
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == own || pkg in seen) continue
            val ai = ri.activityInfo.applicationInfo
            val system = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            seen[pkg] = ResolvedApp(pkg, label.take(60), if (system) "system" else "browser", installedAt(pm, pkg))
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    data class ResolvedApp(val pkg: String, val label: String, val kind: String, val installed: Long)

    /** Apps with a launcher icon (for "Add an app": picking one the phone did not spot as a browser). */
    fun launcherApps(ctx: Context): List<ResolvedApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }.logged(TAG, "Listing apps").getOrDefault(emptyList())
        val own = ownPackage(ctx)
        val seen = HashMap<String, ResolvedApp>()
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == own || pkg in seen || pkg in NEVER_BLOCK) continue
            val ai = ri.activityInfo.applicationInfo
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            seen[pkg] = ResolvedApp(pkg, label.take(60), "app", installedAt(pm, pkg))
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    private fun installedAt(pm: PackageManager, pkg: String): Long =
        runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Is [pkg] blocked on this phone right now? Allowed if the admin allowed it; blocked if the admin blocked it;
     * otherwise a browser we found is blocked when "block new browsers straight away" is on. Never our own app,
     * and never an app Android needs.
     */
    fun blocked(ctx: Context, pkg: String): Boolean {
        val p = protect ?: return false
        if (p.mode == "off") return false
        if (pkg == ownPackage(ctx) || pkg in NEVER_BLOCK) return false
        when (p.apps[pkg]) {
            "allow" -> return false
            "block" -> return true
        }
        // Not decided yet: a browser we can see is blocked if "block new" is on (and reported either way).
        return p.blockNew && knownBrowserPkgs(ctx).contains(pkg)
    }

    /** Package names of browsers we've found (cached between checks, refreshed by [report]). */
    private fun knownBrowserPkgs(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("browserPkgs", emptySet()) ?: emptySet()

    /** Every package that should be kept from opening right now (for the cover and for device owner to suspend). */
    fun blockedPackages(ctx: Context): Set<String> {
        val p = protect ?: return emptySet()
        if (p.mode == "off") return emptySet()
        val out = HashSet<String>()
        for ((pkg, v) in p.apps) if (v == "block" && pkg != ownPackage(ctx) && pkg !in NEVER_BLOCK) out.add(pkg)
        if (p.blockNew) for (pkg in knownBrowserPkgs(ctx)) if (p.apps[pkg] != "allow" && pkg != ownPackage(ctx) && pkg !in NEVER_BLOCK) out.add(pkg)
        return out
    }

    /**
     * Looks at which apps can open websites, keeps the list for the admin page (sent with the next check-in), and —
     * when "block new browsers straight away" is on — asks the admin about any newly found one. Quick; call it off
     * the main thread, every few minutes and when an app is installed or removed.
     */
    fun report(ctx: Context) {
        val found = browserApps(ctx)
        val pkgs = found.map { it.pkg }.toSet()
        val before = knownBrowserPkgs(ctx)
        prefs(ctx).edit().putStringSet("browserPkgs", pkgs).apply()
        // The full app list for the admin page's Other browsers screen: browsers first (what we found), then other
        // apps the person could pick to block (one with its own web view the phone did not spot as a browser).
        val added = protect?.apps?.keys.orEmpty().filter { it !in pkgs }
        val appList = JSONArray()
        found.forEach { appList.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("kind", it.kind).put("installed", it.installed)) }
        for (pkg in added) if (pkg !in pkgs) appList.put(JSONObject().put("pkg", pkg).put("label", labelOf(ctx, pkg)).put("kind", "added"))
        val shownPkgs = pkgs + added
        for (app in launcherApps(ctx)) if (app.pkg !in shownPkgs && appList.length() < 200)
            appList.put(JSONObject().put("pkg", app.pkg).put("label", app.label).put("kind", "app").put("installed", app.installed))
        prefs(ctx).edit().putString("appList", appList.toString()).apply()
        // A newly found browser the admin hasn't decided on yet: ask (blocked straight away if "block new" is on).
        val p = protect ?: return
        if (!on()) return
        val reported = prefs(ctx).getStringSet("reported", emptySet()) ?: emptySet()
        val newOnes = found.filter { it.pkg !in before && it.pkg !in reported && p.apps[it.pkg] == null && it.kind != "system" }
        if (newOnes.isNotEmpty() && Requests.isSetUp()) {
            val now = HashSet(reported)
            for (app in newOnes.take(5)) {
                runCatching {
                    Requests.queuePhone(ctx, "browser",
                        JSONObject().put("pkg", app.pkg).put("label", app.label).put("blocked", p.blockNew).put("installed", app.installed),
                        "", "New browser: ${app.label}")
                    now.add(app.pkg)
                    AppLog.i(TAG, "Found a new browser: ${app.label} (${app.pkg}) — ${if (p.blockNew) "blocked, asking" else "asking"}")
                }.logged(TAG, "Reporting a new browser")
            }
            prefs(ctx).edit().putStringSet("reported", now).apply()
            runCatching { Outbox.flush(ctx) }.logged(TAG, "Sending browser reports")
        }
    }

    fun labelOf(ctx: Context, pkg: String): String =
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg).take(60)

    /** The app list and protection state to send to the admin page with the check-in record. */
    fun recordExtras(ctx: Context): Pair<JSONArray, JSONObject> {
        val apps = runCatching { JSONArray(prefs(ctx).getString("appList", "[]")) }.getOrDefault(JSONArray())
        val state = JSONObject()
            .put("owner", DeviceOwner.isOwner(ctx))
            .put("admin", DeviceOwner.isAdminActive(ctx))
            .put("cover", GuardService.running)
            .put("installBlock", protect?.blockInstalls == true && DeviceOwner.isOwner(ctx))
        return apps to state
    }
}
