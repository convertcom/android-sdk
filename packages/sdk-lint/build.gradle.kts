/*
 * Convert Android SDK — sdk-lint
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 *
 * Story 6.3: custom Android Lint rules shipped inside the consumer-facing
 * AAR (via `lintPublish` in `:packages:sdk`). This module is a pure-JVM
 * Kotlin lint-rule producer — no Android resources, no `minSdk`. The
 * `com.android.lint` plugin performs the manifest wiring and packaging
 * so the rule JAR is consumable by both AGP's lint tool and the standalone
 * lint CLI.
 */
plugins {
    // `com.android.lint` is AGP's dedicated plugin for lint-rule modules.
    // It applies `java-library` under the hood and publishes the artifact
    // as a plain JAR with the lint-service descriptor baked in. It does
    // NOT auto-apply any Kotlin plugin, so we pair it with `kotlin-jvm`
    // below to compile the detectors as Kotlin.
    //
    // Applied without an explicit version: AGP is already on the
    // buildscript classpath (`android.library` / `android.application`
    // pull it in from the root `build.gradle.kts`), and Gradle's
    // plugin-management rejects a second version ref when one is already
    // resolved ("plugin is already on the classpath with an unknown
    // version"). The `android-lint` entry stays in `libs.versions.toml`
    // so the version is documented alongside the rest of the AGP ABI,
    // but we don't use `alias(…)` here.
    id("com.android.lint")
    // Kotlin JVM — provides `kotlin { jvmToolchain(…) }`, the Kotlin
    // compile tasks, and teaches Gradle about the `.kt` source set.
    // Required because `com.android.lint` only registers `java-library`
    // configurations.
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

// AGP's `lintPublish` configuration requires exactly one JAR in the
// outgoing variant — namely this module's lint-rule JAR. By default,
// the Kotlin JVM plugin declares `kotlin-stdlib` as an `api`
// dependency, which propagates to the consumer via `lintPublish` and
// trips AGP's
//   "Found more than one jar in the 'lintPublish' configuration"
// guard at `prepareLintJarForPublish`.
//
// Remove stdlib from the `api`/`implementation`/`runtimeOnly` buckets
// and re-add it as `compileOnly` — still on the compile classpath so
// Kotlin sources compile, but absent from the outgoing JAR set. The
// consumer's lint runtime bundles its own Kotlin stdlib, so this is
// behaviour-preserving for the published rules.
configurations.named("api") {
    dependencies.removeAll { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" }
}
configurations.named("implementation") {
    dependencies.removeAll { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" }
}
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

dependencies {
    // lint-api is the detector authoring surface. `compileOnly` — the
    // consumer's lint tool provides the runtime JAR; bundling it into
    // the detector artifact would collide with the consumer's own
    // classpath version. Gotcha 2 from the story: a mismatch between the
    // version we compile against and the version at consumer runtime
    // crashes lint, so we pin both sides to 32.1.0 via
    // `libs.versions.lintApi`.
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    // lint-tests backs `LintDetectorTest` / `TestLintTask` in the unit
    // tests below. Not shipped, so `testImplementation` is correct.
    testImplementation(libs.lint.tests)
    // lint-api / lint-checks are `compileOnly` for the production
    // classpath (consumer lint tool provides them at runtime), but the
    // test tree still needs them to reference `Issue` constants and
    // the detector infrastructure — promote them to `testImplementation`.
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.junit.jupiter)
    // lint-tests is JUnit 4 under the hood — the vintage engine adapts
    // its `@Test` annotations so JUnit Jupiter's platform runner still
    // executes them.
    testImplementation(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// `com.android.lint` registers a plain `test` task driven by Gradle's
// built-in test plugin. Opt into JUnit Platform so JUnit Jupiter wires up.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
