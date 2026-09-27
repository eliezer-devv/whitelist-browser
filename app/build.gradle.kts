plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each GitHub Actions build gets a higher number, which the in-app updater compares.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.example.whitelistbrowser"
    compileSdk = 34

    defaultConfig {
        // The app's unique name on Android (not shown to users). Changing it makes it a different app:
        // phones can't update across it.
        applicationId = "com.appcustom.whitelistbrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // Key that lets the app create request issues (GitHub secret REQUESTS_TOKEN). Stored reversed.
        val requestsToken = (System.getenv("REQUESTS_TOKEN") ?: "").trim().reversed()
        buildConfigField("String", "REQUESTS_TOKEN_REV", "\"$requestsToken\"")
    }

    // The release key comes from GitHub secrets (see the "Create signing key" workflow).
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    // Don't let style warnings stop the build.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
