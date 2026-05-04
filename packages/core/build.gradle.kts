plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

// Authoritative JVM toolchain for this module.
// Per the Kotlin Gradle plugin docs (https://kotlinlang.org/docs/gradle-configure-project.html#gradle-java-toolchains-support),
// the toolchain block automatically sets `jvmTarget = "17"` for the Kotlin compiler AND
// `sourceCompatibility/targetCompatibility = VERSION_17` for AGP 8+. Any duplicate
// declaration via `java { toolchain {} }` produces a Gradle warning and must be omitted.
//
// Vendor is pinned to Eclipse Adoptium (Temurin) so Gradle's toolchain resolver does NOT
// pick a different JDK provider than the one CI installs via `actions/setup-java@v4`
// with `distribution: temurin`. Without `vendor.set(...)` the resolver may auto-download
// any matching `languageVersion = 17` JDK (Zulu, Microsoft, GraalVM, …), causing CI host
// JDK and Gradle build JDK to diverge. See:
// https://docs.gradle.org/current/userguide/toolchains.html#sec:vendors
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.murmurhash)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Gradle 9+ needs the launcher wired explicitly; the junit-jupiter aggregator
    // no longer brings it in transitively. Without it, `gradle test` fails with
    // "Failed to load JUnit Platform".
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

kover {
    reports {
        verify {
            rule {
                minBound(85)
            }
        }
    }
}
