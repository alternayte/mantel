plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
}

group = "com.mantel"
version = "0.1.0"

application {
    mainClass.set("com.mantel.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)

    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.angus.mail)
    implementation(libs.argon2)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.hikari)
    runtimeOnly(libs.postgresql)
    implementation(libs.aws.s3)
    implementation(libs.logback)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.postgresql)
}

// Flyway 10+ finds its own SQL resolvers through META-INF/services. A fat jar that drops those
// files leaves Flyway unable to recognise "V1__baseline.sql" as a migration, so the packaged app
// silently applies none and fails later on a missing column. Merging them is not optional.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
}

// The version reaches the running app from one place. An OpenAPI document that names a version the
// build does not is the same class of drift as an enum typed out by hand.
tasks.processResources {
    filesMatching("version.properties") { expand("version" to project.version.toString()) }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The SPA is built by Vite into build/web-resources/web and served from the jar.
// `just build` runs the web build first; the directory may be absent during a server-only build.
sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("web-resources"))
}
