import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.vanniktech.maven.publish)
    // Story 6.1: Dokka generates HTML API reference under build/dokka/html/
    // and its output is fed to the Javadoc JAR via vanniktech's
    // `JavadocJar.Dokka("dokkaGenerateHtml")` hook below.
    alias(libs.plugins.dokka)
}

// Story 6.1: Dokka source-set tuning.
// 1) `jdkVersion.set(17)` aligns Dokka's analyser with the
//    `jvmToolchain(17)` pinned below (Story 1.1). Without this, Dokka
//    runs under whatever JDK the Gradle daemon picked, which can diverge
//    from the Kotlin compiler's target on dev machines that auto-pick a
//    higher JDK. CI is unaffected (Story 1-3 provisions Temurin 17), but
//    explicit alignment removes the dev-vs-CI doc drift class. [F-079]
// 2) Exclude the auto-generated OpenAPI types — they carry ApiModel-derived
//    descriptions (e.g. "[incremental_number]", "[ISO_datetime]") that are
//    human-language annotations, not Kotlin symbol references. Dokka (V2)
//    tries to link them and emits noisy warnings. Same exclusion as Kover's
//    coverage filter.
dokka {
    dokkaSourceSets.named("main") {
        jdkVersion.set(17)
        perPackageOption {
            matchingRegex.set("com\\.convert\\.sdk\\.core\\.model\\.generated(\\..*)?")
            suppress.set(true)
        }
    }
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
        // Exclude auto-generated OpenAPI types (Story 1.5) — they are compiled
        // sources with no business logic and no tests, so including them in
        // the coverage denominator would push the percentage below the 85%
        // threshold without reflecting real project health. The round-trip
        // serialization test (AC-8) exercises the generated code's runtime
        // correctness; Kover's line-coverage metric is only meaningful for
        // hand-written code.
        filters {
            excludes {
                packages("com.convert.sdk.core.model.generated")
            }
        }
        verify {
            rule {
                minBound(85)
            }
        }
    }
}

// Maven Central publishing (Story 1.4). `packages/core` is published as a
// standalone JAR because `packages/sdk` declares api(project(":packages:core"))
// — consumers of `com.convert:sdk-android` get `com.convert:sdk-core` pulled
// transitively, so the core JAR must exist on Maven Central at the same
// version as the AAR.
mavenPublishing {
    // Kotlin-JVM publication with a real sources JAR and a Dokka-generated
    // Javadoc JAR (Story 6.1). `JavadocJar.Dokka("dokkaGenerateHtml")` wires
    // the vanniktech plugin to the Dokka V2 task output so consumers browsing
    // Maven Central get real API reference docs (not an empty stub).
    // Task name is the V2 form (`dokkaGenerateHtml`) — the V1 alias
    // `dokkaHtml` was removed in Dokka 2.2.0.
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Dokka("dokkaGenerateHtml"),
            sourcesJar = true,
        ),
    )

    coordinates(
        groupId = "com.convert",
        artifactId = "sdk-core",
        version = libs.versions.sdk.version.get(),
    )

    // vanniktech 0.36.0 removed the SonatypeHost argument — publishToMavenCentral()
    // always routes to the new Central Portal (OSSRH retired June 2025).
    publishToMavenCentral()

    // GPG signing is mandatory for Maven Central. CI wires the in-memory key
    // via ORG_GRADLE_PROJECT_signingInMemoryKey (see .github/workflows/release.yml).
    // Skip signing when the key is absent so `publishToMavenLocal` works for
    // local smoke-tests without requiring every dev to import a GPG key.
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }

    // Mirror the SDK module's POM metadata so both artifacts present the
    // same project identity to Maven Central consumers.
    pom {
        name.set("Convert Android SDK — Core")
        description.set(
            "Pure-Kotlin core module of the Convert Android SDK: bucketing, rules, " +
                "models, and configuration shared with the Android-specific :packages:sdk.",
        )
        url.set("https://github.com/convertcom/android-sdk")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("convertcom")
                name.set("Convert.com")
                email.set("support@convert.com")
                organization.set("Convert.com")
                organizationUrl.set("https://www.convert.com/")
            }
        }
        scm {
            url.set("https://github.com/convertcom/android-sdk")
            connection.set("scm:git:git://github.com/convertcom/android-sdk.git")
            developerConnection.set("scm:git:ssh://github.com:convertcom/android-sdk.git")
        }
    }
}
