package com.example.whitelistbrowser

object Config {
    // Your GitHub username. This is the only line you must change.
    const val GITHUB_USERNAME = "YOUR_USERNAME"

    // The repository name. Only change it if you named the repo something else.
    const val REPO_NAME = "whitelist-browser"

    // How often (in hours) the app looks for a new version by itself.
    const val UPDATE_CHECK_HOURS = 6

    // true = pin the app to the screen so it can't be left without unpinning.
    const val LOCK_TASK = false

    // Worked out from the lines above. No need to edit.
    val PAGES_BASE get() = "https://${GITHUB_USERNAME.lowercase()}.github.io/$REPO_NAME/"
    val WHITELIST_URL get() = "${PAGES_BASE}whitelist.json"   // the public list
    val GITHUB_REPO get() = "$GITHUB_USERNAME/$REPO_NAME"
}
