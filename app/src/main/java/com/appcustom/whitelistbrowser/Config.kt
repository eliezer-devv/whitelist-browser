package com.appcustom.whitelistbrowser

object Config {
    // Your GitHub username. This is the only line you must change.
    const val GITHUB_USERNAME = "eliezer-devv"

    // The repository name. Only change it if you named the repo something else.
    const val REPO_NAME = "whitelist-browser"

    // How often (in hours) the app looks for a new version by itself.
    const val UPDATE_CHECK_HOURS = 6

    // true = pin the app to the screen so it can't be left without unpinning.
    const val LOCK_TASK = false

    // Worked out from the lines above. No need to edit.
    val PAGES_BASE get() = "https://${GITHUB_USERNAME.lowercase()}.github.io/$REPO_NAME/"
    val GITHUB_REPO get() = "$GITHUB_USERNAME/$REPO_NAME"

    // Fuller ad blocking comes entirely from AdGuard: phones download these themselves once a day (and a copy is
    // packed into the app when it's built). Base includes EasyList; Quick Fixes is AdGuard's fastest-updated list,
    // where its YouTube fixes go first.
    // In three groups, each with its own switch (admin page): ads, trackers, annoyances.
    private const val AG = "https://filters.adtidy.org/extension/chromium/filters/"
    val AD_FILTER_GROUPS = mapOf(
        // (Several addresses may be given, " | " between them: tried in order.)
        "ads" to listOf("adguard-base.txt" to "${AG}2.txt", "adguard-mobile.txt" to "${AG}11.txt"),
        "trackers" to listOf("adguard-tracking.txt" to "${AG}3.txt", "adguard-urltracking.txt" to "${AG}17.txt"),
        "annoyances" to listOf("adguard-cookies.txt" to "${AG}18.txt", "adguard-popups.txt" to "${AG}19.txt",
            "adguard-appbanners.txt" to "${AG}20.txt", "adguard-otherannoyances.txt" to "${AG}21.txt",
            "adguard-widgets.txt" to "${AG}22.txt", "adguard-social.txt" to "${AG}4.txt"),
    )
    // AdGuard's code for its advanced element rules ("hide the box containing…"), tried in this order.
    val AD_EXTENDED_CSS_URLS = listOf(
        "https://cdn.jsdelivr.net/npm/@adguard/extended-css@latest/dist/extended-css.min.js",
        "https://cdn.jsdelivr.net/npm/@adguard/extended-css@latest/dist/extended-css.js",
    )
    // AdGuard's own code for its scriptlets (the small named functions its lists use, e.g. to remove YouTube's
    // ads from its player data), published for apps.
    const val AD_SCRIPTLETS_URL = "https://cdn.jsdelivr.net/npm/@adguard/scriptlets@latest/dist/scriptlets.corelibs.json"
}
