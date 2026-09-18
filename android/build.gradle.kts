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
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.mantel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mantel.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
        }
    }
}
