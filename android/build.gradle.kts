plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

// One target. Compose Multiplatform is here because SDD.md 10 says so and because the alternative
// is a second UI toolkit to learn; it is not an invitation to a second platform. iOS is not planned.
kotlin {
    jvmToolchain(21)
    androidTarget()

    sourceSets {
        androidMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.androidx.work)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)

            // @Preview renders a composable in the IDE without a device. The annotation ships; the
            // renderer that reads it is a debug-only dependency.
            implementation(libs.androidx.compose.ui.tooling.preview)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

dependencies {
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * The release keystore, from the environment.
 *
 * The app is sideloaded (SDD.md 10), so the signature is the only thing that says an update is from
 * the same author as the install. The key never enters the repository: the build reads it from the
 * environment, and a build without one produces an unsigned APK rather than a silently different
 * signature. `docs/android.md` says how to make one.
 */
val keystorePath: String? = System.getenv("MANTEL_KEYSTORE")

android {
    namespace = "com.mantel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mantel.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MANTEL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MANTEL_KEY_ALIAS")
                keyPassword = System.getenv("MANTEL_KEY_PASSWORD") ?: System.getenv("MANTEL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        named("release") {
            // R8 is off. The app is sideloaded from a release page, not competing for a download
            // over a mobile connection, and a shrinker needs a reason and a measurement.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
