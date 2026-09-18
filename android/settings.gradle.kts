// The Android client is its own Gradle build, not a subproject of the server's.
//
// It needs the Android SDK to configure at all, and `just check` is the gate every contributor runs
// on a machine that may not have one. Keeping the builds separate means the server's gate never
// asks for an SDK, and `just check-android` asks for it plainly. The version catalogue is shared,
// so a version is still stated once.
rootProject.name = "mantel-android"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
