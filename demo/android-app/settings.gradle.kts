/*
 * Convert Android SDK Demo App — settings
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 *
 * Story 7.1 AC-1: this demo is a standalone Android Studio project — NOT
 * included in the root SDK `settings.gradle.kts`. The SDK is resolved via
 * Gradle's composite-build mechanism (`includeBuild("../../")`) which
 * substitutes any dependency matching the included build's published
 * coordinates with the locally-built equivalent. The demo declares
 * `implementation("com.convert:sdk-android")` in its `app/build.gradle.kts`;
 * the SDK build's `mavenPublishing { coordinates("com.convert", "sdk-android", ...) }`
 * block (see `packages/sdk/build.gradle.kts`) exposes those coordinates,
 * and Gradle's dependency substitution swaps in the local artifact
 * automatically. Consumers pulling the demo from the repo therefore never
 * need to publish the SDK to Maven Local first — `./gradlew :app:assembleDebug`
 * works out of the box.
 *
 * Gotcha 1 (from the story): if SDK changes don't propagate, run
 * `./gradlew --stop` + `./gradlew clean` in both this project and the
 * parent SDK project, or use Android Studio's "Reload Gradle Project".
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "convert-android-sdk-demo"

// Composite build — pulls the SDK's project tree (`../../` from this demo
// directory resolves to the repo root at `android-sdk/`) into this build
// as a dependency source. See the block comment above for substitution
// semantics.
//
// Gradle's automatic dependency substitution matches when the included
// build's project `group` + `name` equal the requested coordinates. The
// SDK project uses `group = "android-sdk.packages"` and `name = "sdk"`
// (Gradle's auto-derivation from the `rootProject.name` + module path);
// its Maven-Central publish coordinates `com.convert:sdk-android` are
// set ONLY in the `mavenPublishing` block and not as project-wide
// coordinates. We therefore declare an explicit substitution rule here
// mapping the requested Maven coordinates to the SDK project path. This
// is the Gradle-documented pattern for composite builds that want to
// consume substitution-time Maven coordinates without churning the
// upstream project's `group` property.
includeBuild("../../") {
    dependencySubstitution {
        substitute(module("com.convert:sdk-android")).using(project(":packages:sdk"))
    }
}

include(":app")
