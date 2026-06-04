import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.android.library)
    // NOTE: org.jetbrains.kotlin.android is intentionally NOT applied here.
    // AGP 9.0+ ships with built-in Kotlin support for com.android.library modules
    // and rejects the explicit plugin with "The 'org.jetbrains.kotlin.android'
    // plugin is no longer required for Kotlin support since AGP 9.0."
    // (See kotl.in/gradle/agp-built-in-kotlin.)
    // The kotlin-android catalog alias has been removed from libs.versions.toml
    // accordingly — see architecture.md §Verified-Technology-Versions.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.vanniktech.maven.publish)
    // Story 6.1: Dokka generates HTML API reference under build/dokka/html/
    // for the public Android SDK surface. Vanniktech's
    // `JavadocJar.Dokka("dokkaHtml")` hook below feeds this into the
    // published Javadoc JAR.
    alias(libs.plugins.dokka)
}

// Story 6.1 [F-079]: pin Dokka's analyser to JDK 17 so it matches the
// `jvmToolchain(17)` configured below (Story 1.1). Without this, Dokka
// runs under whatever JDK the Gradle daemon picked, which can diverge
// from the Kotlin compiler's target on dev machines that auto-pick a
// higher JDK. CI is already on Temurin 17 (Story 1-3) so this is a
// dev-environment guard. `configureEach` is used because AGP-derived
// source sets are named after the published variant (e.g. `release`),
// not `main` as on a Kotlin-JVM module.
dokka {
    dokkaSourceSets.configureEach {
        jdkVersion.set(17)
    }
}

android {
    namespace = "com.convert.sdk.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    // No compileOptions / kotlinOptions.jvmTarget here — JDK level is set by the
    // explicit kotlin { jvmToolchain(17) } block below. Per the Kotlin Gradle plugin
    // docs (https://kotlinlang.org/docs/gradle-configure-project.html#gradle-java-toolchains-support),
    // jvmToolchain(17) automatically derives jvmTarget AND sourceCompatibility/
    // targetCompatibility = VERSION_17 for AGP 8+; any duplicate declaration produces
    // a Gradle warning and must be omitted.

    // Robolectric (Story 2.1) reads `testOptions.unitTests.isIncludeAndroidResources`
    // to hand the shadow android.content.Context a real resources/assets surface.
    // Without this, ShadowLog works but any test that materialises resources
    // via getSystemService/getSharedPreferences hits a NotFoundException.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

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
}

dependencies {
    api(project(":packages:core"))

    // Story 6.3: bundle the custom lint-rule JAR into the published AAR
    // so consumer apps pick the rules up automatically — no extra
    // consumer-side dependency, lint runs on every `lintDebug` /
    // `lintRelease` as soon as this SDK is on the classpath.
    //
    // `lintPublish` (not `lintChecks`) is the correct scope per story
    // Gotcha 1: `lintChecks` runs the rules on THIS module's own lint
    // report, `lintPublish` adds them to the AAR for downstream
    // consumers. We want the latter — the SDK module itself doesn't
    // call `ConvertSDK.builder(…)` and would only emit noise.
    lintPublish(project(":packages:sdk-lint"))

    implementation(libs.okhttp)
    // Story 2.2: FileConfigCache serialises/deserialises ConfigResponseData
    // directly in this module (not through the core ApiManager). The core
    // module declares kotlinx.serialization-json as `implementation`, so it
    // does not leak transitively — the sdk module needs its own dep.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.startup.runtime)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Story 2.3 AC-10: TestLifecycleOwner provides a programmatic LifecycleOwner
    // whose handleLifecycleEvent() drives ON_START / ON_STOP synchronously,
    // letting SdkLifecycleObserverTest verify the observer callbacks without
    // a full Android runtime. Pinned to match lifecycle-process's version to
    // keep the androidx.lifecycle artifact family ABI-consistent.
    testImplementation(libs.androidx.lifecycle.runtime.testing)
    // Story 5.3 AC-10 test dependency: work-testing provides
    // TestListenableWorkerBuilder (drives CoroutineWorker.doWork under
    // Robolectric) and WorkManagerTestInitHelper (installs a synchronous
    // WorkManager implementation so enqueueUniqueWork can be verified
    // without a real scheduler).
    testImplementation(libs.androidx.work.testing)
    // Robolectric 4.16 — provides JVM-side shadows for android.util.Log,
    // android.content.Context, SharedPreferences. Required by Story 2.1
    // AC-10 tests (AndroidLoggerTest, SharedPrefsDataStoreTest, ConvertSDKTest).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    // Gradle 9+ needs the launcher wired explicitly; the junit-jupiter aggregator
    // no longer brings it in transitively. Without it, `gradle test` fails with
    // "Failed to load JUnit Platform".
    testRuntimeOnly(libs.junit.platform.launcher)
    // Robolectric itself ships as a JUnit 4 runner. JUnit 5 loads it via
    // the vintage engine. Without this, @RunWith(RobolectricTestRunner::class)
    // tests silently don't execute.
    testRuntimeOnly(libs.junit.vintage.engine)
}

// Maven Central publishing (Story 1.4). vanniktech 0.36.0 removed the
// explicit SonatypeHost parameter — publishToMavenCentral() always targets
// the new Central Portal (the legacy OSSRH endpoint was retired June 2025).
mavenPublishing {
    // Configure the Android library publication: publish only the `release`
    // variant, build a sources JAR, and a Dokka-generated Javadoc JAR
    // (Story 6.1). `JavadocJar.Dokka("dokkaGenerateHtml")` wires vanniktech
    // to the Dokka V2 task output so consumers browsing Maven Central get
    // real API reference docs (not an empty stub). Task name is the V2 form
    // — the V1 alias `dokkaHtml` was removed in Dokka 2.2.0.
    // The (String, Boolean, Boolean) constructor is deprecated in 0.36.0;
    // the canonical form is (JavadocJar, SourcesJar, variant).
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Dokka("dokkaGenerateHtml"),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )

    coordinates(
        groupId = "com.convert",
        artifactId = "sdk-android",
        version = libs.versions.sdk.version.get(),
    )

    // Route to the Central Portal with automatic release: after the upload
    // passes Central's validation, the deployment is published to Maven
    // Central automatically — no manual "Publish" click in the Portal UI.
    // (`automaticRelease` is a publisher-side plugin setting, not a Central
    // Portal UI option; it sets the deployment's publishingType=AUTOMATIC.)
    publishToMavenCentral(automaticRelease = true)

    // Every artifact uploaded to Maven Central MUST be GPG-signed. The plugin
    // reads the signing key from ORG_GRADLE_PROJECT_signingInMemoryKey etc.
    // at CI time (wired in .github/workflows/release.yml — Story 1.4 / AC-6).
    // Skip signing when the in-memory key is absent so `publishToMavenLocal`
    // works for local smoke-tests without requiring a GPG key on every dev
    // machine. CI always provides the env var, so the production path stays
    // signed.
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }

    // POM metadata required by Maven Central (name, description, url,
    // licenses, developers, scm all mandatory for publication approval).
    pom {
        name.set("Convert Android SDK")
        description.set("Android SDK for Convert Experiences A/B testing and feature flags.")
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

// AGP's generated unit-test tasks don't default to JUnit 5. Opt every
// `test*UnitTest` task into JUnit Platform so JUnit Jupiter gets used.
//
// AC-6.1 isolation: forkEvery=1 spawns a fresh JVM for each test class so
// Robolectric's JVM-static singletons (ShadowLog, WorkManager, etc.) are
// fully reset between classes — no cross-class pollution under parallel
// execution. The per-fork JVM startup cost is acceptable for the test suite
// size; if it becomes a bottleneck, raise forkEvery to 5–10 and re-verify.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    forkEvery = 1
}

kover {
    reports {
        verify {
            rule {
                // NFR19 target: 70% for packages/sdk. Story 2.1 lands the
                // Robolectric-backed tests (ConvertSDKTest, AndroidLoggerTest,
                // SharedPrefsDataStoreTest, OkHttpClientAdapterTest) that
                // exercise the full Builder path, the coroutine scope, and
                // every adapter. Measured line coverage after Story 2.1:
                // 244/(244+12) = 95.3% — comfortably above the NFR floor.
                // The bound is ratcheted to 70 (not 95) to leave headroom
                // for Story 2.2+ code that may land alongside incomplete
                // test coverage during the RED phase of each subsequent
                // story; 70 matches the NFR target, and any regression
                // below 70 fails the build.
                minBound(70)
            }
        }
    }
}
