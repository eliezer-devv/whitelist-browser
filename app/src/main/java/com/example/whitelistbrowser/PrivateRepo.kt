package com.example.whitelistbrowser

/**
 * The private repository: requests, registrations and check-ins go there as issues, so nobody else
 * can see them. It's the public repository's name with "-private" on the end.
 * (Kept out of Config.kt so updating the app never touches your settings there.)
 */
object PrivateRepo {
    const val NAME = "${Config.REPO_NAME}-private"
    val FULL get() = "${Config.GITHUB_USERNAME}/$NAME"
}
