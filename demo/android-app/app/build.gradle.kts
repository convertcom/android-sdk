/*
 * Convert Android SDK Demo App — :app module build
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 *
 * Story 7.1 AC-2 — standard Compose application module. The
 * `com.convert:sdk-android` dependency resolves against the composite
 * include in `settings.gradle.kts` (`includeBuild("../../")`) — Gradle
 * substitutes the locally-built AAR at sync time.
 *
 * ### Plugin choices
 *
 * AGP 9.1.0's `com.android.application` ships with Kotlin support
 * built-in — applying `org.jetbrains.kotlin.android` is no longer
 * required AND is rejected at apply time with a hard error (not just
 * a warning). See `packages/sdk/build.gradle.kts` for the canonical
 * comment and https://kotl.in/gradle/agp-built-in-kotlin for the
 * upstream announcement.
 *
 * `org.jetbrains.kotlin.plugin.compose` IS still required on Kotlin
 * 2.x — the legacy `composeOptions { kotlinCompilerExtensionVersion
 * = ... }` block was retired when the Compose compiler moved out of
 * AGP and into its own plugin (released with Kotlin 2.0).
 */

import java.util.Properties

plugins {
    id("com.android.application") version "9.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

// Demo-tunable BuildConfig fields are read from one of TWO independent
// properties files, chosen by what's in the gradle task graph:
//
//   - `local.properties`  — the developer's personal config for
//     running the demo against staging via Android Studio /
//     `installDebug` / `assembleDebug`. Git-ignored. Free to hold
//     real SDK keys / environment overrides / experience keys.
//
//   - `test.properties`   — committed test fixtures. Consumed when
//     the gradle invocation contains a `test` or `check` task
//     (`:app:testDebugUnitTest`, `:app:check`, etc.). Keeps the unit
//     tests deterministic regardless of what's in `local.properties`.
//     Every assertion in `app/src/test/...` is written against these
//     values.
//
// Each key is optional in either file — when missing, a fallback
// literal is injected so `./gradlew :app:assembleDebug` (or test
// runs without `test.properties`) succeeds cleanly on a fresh
// clone. The fallbacks MUST stay in lockstep with the values
// committed in `test.properties` and the assertions in `app/src/test/`:
//
//   | property              | fallback literal      |
//   | --------------------- | --------------------- |
//   | convertSdkKey         | "demo-sdk-key"        |
//   | convertEnvironment    | ""  (empty sentinel)  |
//   | convertExperienceKey  | "test-experience"     |
//   | convertFeatureKey     | "test-feature"        |
//   | convertGoalKey        | "purchase-goal"       |
//
// The empty-string sentinel for `convertEnvironment` signals "not
// configured" to `DemoApplication.onCreate`, which skips the builder
// call when the value is blank. `BuildConfig` emits `@NonNull` Java
// strings, so empty-string is preferred over `null` (which would
// require nullable typing at every read site).
//
// Detection looks at gradle task names — robust against
// `./gradlew :app:testDebugUnitTest`, `./gradlew test`,
// `./gradlew check`, Android Studio's "Run test" actions (which
// invoke gradle with `--tests` filters on a `test*` task), and
// combined invocations like `./gradlew test lintDebug assembleDebug`
// (which we still treat as a test run because the tests dominate).
// `sdk.dir` is read by AGP itself from `local.properties` and does
// NOT flow through this selector.
private val isTestBuild: Boolean = gradle.startParameter.taskNames.any { taskName ->
    val lower = taskName.lowercase()
    lower.contains("test") || lower.contains("check")
}

private val convertTunablesFile: java.io.File = if (isTestBuild) {
    rootProject.file("test.properties")
} else {
    rootProject.file("local.properties")
}

private val convertTunables: Properties = Properties().apply {
    if (convertTunablesFile.exists()) {
        convertTunablesFile.inputStream().use { load(it) }
    }
}

private fun demoTunable(key: String, fallback: String): String =
    convertTunables.getProperty(key) ?: fallback

val convertSdkKey: String = demoTunable("convertSdkKey", "demo-sdk-key")
val convertEnvironment: String = demoTunable("convertEnvironment", "")
val convertExperienceKey: String = demoTunable("convertExperienceKey", "test-experience")
val convertFeatureKey: String = demoTunable("convertFeatureKey", "test-feature")
val convertGoalKey: String = demoTunable("convertGoalKey", "purchase-goal")

android {
    namespace = "com.convert.sdk.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.convert.sdk.demo"
        minSdk = 24
        // targetSdk 35 — Play Store's current requirement (API 35 = Android
        // 15) and aligns with the SDK module's compileSdk so we share one
        // toolchain surface.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "convertSdkKey", "\"$convertSdkKey\"")
        buildConfigField("String", "convertEnvironment", "\"$convertEnvironment\"")
        buildConfigField("String", "convertExperienceKey", "\"$convertExperienceKey\"")
        buildConfigField("String", "convertFeatureKey", "\"$convertFeatureKey\"")
        buildConfigField("String", "convertGoalKey", "\"$convertGoalKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Story 7.2 — EventInspectorSheet renders event timestamps with
        // `java.time.DateTimeFormatter` (Gotcha 2: "use
        // DateTimeFormatter.ofPattern(\"HH:mm:ss.SSS\") from java.time").
        // `java.time` was added in API 26; minSdk is 24. Core-library
        // desugaring backports the API set so these calls are safe on
        // API 24/25 devices. This is the single-dependency
        // standard-library shim recommended by Google for new code.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP 9 source set layout — kotlin source lives under `src/main/kotlin`
    // (the project uses the same convention as the SDK modules).
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Use JUnit 5 for unit tests — matches the SDK module's stack and lets
    // the demo consume the same junit-jupiter + mockk + coroutines-test
    // version train without a second testing paradigm for devs to learn.
    //
    // `includeAndroidResources = true` is required by Robolectric Compose
    // UI tests: `createComposeRule()` stands up an `androidx.activity.
    // ComponentActivity` via `ActivityScenario`, and that activity is
    // declared in the `ui-test-manifest` artifact's AndroidManifest.xml
    // — the resource merger only picks it up when this flag is on.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The SDK — resolved via composite build to the locally-built AAR.
    // Coordinates mirror `packages/sdk/build.gradle.kts` mavenPublishing.
    implementation("com.convert:sdk-android")

    // AndroidX core + lifecycle + activity. Versions pinned to the last
    // compileSdk-35-compatible release of each library (later releases
    // force compileSdk 36+). When Android Studio bumps the installed
    // platforms to 36, this project can raise compileSdk + these
    // versions in lockstep without any other change.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.0")

    // Compose BOM — pinned to 2025.04.00 in the demo's own version
    // catalog (`gradle/libs.versions.toml`) per Story 7.1 AC-2 (F-075 +
    // F-144 audit remediation). Story Gotcha 2 ties this version to
    // Kotlin 2.3.20: the Compose compiler plugin (applied above at
    // Kotlin 2.3.20) governs compiler compatibility while the BOM
    // governs runtime artifact versions. Catalog reference rather than
    // an inline string keeps the version definition in one place — the
    // catalog — for future bumps.
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation Compose — 2.8.x is the last line compatible with
    // compileSdk 35.
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Kotlinx coroutines (the Compose runtime pulls a recent core in
    // transitively, but we declare it explicitly so the version is obvious
    // to developers reading this file).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Story 7.4 — kotlinx-serialization-json. The SDK's public
    // [com.convert.sdk.core.model.Feature.variables] field is typed
    // `Map<String, JsonElement>?`, but `packages/core` declares
    // serialization as an `implementation` dependency (not `api`) so
    // the `JsonElement` / `JsonPrimitive` types do NOT transitively land
    // on the demo's compile classpath. The Features screen inspects
    // variables' primitive shape to emit user-facing type labels
    // (`[String]`, `[Int]`, etc. per AC-4) — that inspection requires
    // the types be visible here. Version pinned to match the SDK's
    // version catalog (`gradle/libs.versions.toml` → `kotlinxSerialization`).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Debug-only Compose tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Story 7.2 — core-library desugaring runtime. Ships the API 26+
    // `java.time` classes back-compat to API 24. Required by the
    // `isCoreLibraryDesugaringEnabled = true` flag above.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Tests — match the SDK module's stack
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Story 7.2 AC-8 — Compose UI tests for EventInspectorSheet.
    //
    // `ui-test-junit4` is a JUnit-4 rule library (`createComposeRule` /
    // `createAndroidComposeRule`). This module runs on JUnit 5, but
    // the junit-vintage-engine below lets JUnit 4 tests cohabit with
    // JUnit Jupiter tests (same pattern `:packages:sdk` uses for its
    // Robolectric suite).
    //
    // `ui-test-manifest` is already added to `debugImplementation`
    // above (that's the AGP-recommended configuration for a
    // dependency that's only used by tests but needs to be on the
    // packaged resource path).
    //
    // Robolectric provides the Android shadow runtime so the Compose
    // rule can stand up a `ComponentActivity` without an instrumented
    // device. The project already uses Robolectric 4.16 in the SDK
    // module's version catalog.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.16")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
}
