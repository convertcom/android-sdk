import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    // AGP 9.1.0 ships built-in Kotlin support and refuses to apply alongside
    // org.jetbrains.kotlin.android — see https://kotl.in/gradle/agp-built-in-kotlin.
    // This deviates from Story 1.1's Dev Notes which predate the final AGP 9.1.0 behaviour.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    // Story 1.4 — vanniktech Maven Publish plugin drives the Android-aware
    // publish config (AAR + sources JAR + Javadoc JAR, GPG signing, POM).
    alias(libs.plugins.vanniktech.maven.publish)
}

// Publish coordinates — read the version from the Gradle version catalog so
// semantic-release's bump-version.mjs is the single source of truth.
// `libs.versions.sdk.version.get()` corresponds to the kebab-case key
// `sdk-version` under `[versions]` in gradle/libs.versions.toml.
version = libs.versions.sdk.version.get()

android {
    namespace = "com.convert.sdk.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":packages:core"))

    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.startup)

    testImplementation(libs.junit.jupiter)
    // Gradle 9 no longer adds the JUnit Platform launcher transitively; surface it explicitly
    // so the test executor can load the platform.
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Story 1.4 — Maven Central publish configuration via vanniktech plugin (0.36.0).
//
// In 0.36.0 the plugin consolidated its Maven-Central targeting: the legacy
// `SonatypeHost.CENTRAL_PORTAL` / `SonatypeHost.DEFAULT` enum was removed
// along with OSSRH support (OSSRH was sunset June 2025). `publishToMavenCentral()`
// now targets the Central Portal unconditionally.
//
// `AndroidSingleVariantLibrary(JavadocJar, SourcesJar, variant)` — new typed
// constructor (the boolean overload is deprecated in 0.36.0). Supplies:
//   - `JavadocJar.Empty()` — empty Javadoc-stub JAR. Maven Central's
//     validator requires a Javadoc JAR to be present but does not inspect
//     its content; an empty stub is the conventional minimum. (Dokka-HTML
//     output is not a Javadoc substitute for the validator, and would
//     inflate the artifact with no consumer value.)
//   - `SourcesJar.Sources()` — publish a real sources JAR so consumers can
//     navigate to Kotlin sources from IDE decompile.
//   - `variant = "release"` — publish only the Release variant; Debug is a
//     developer-only artifact.
//
// `signAllPublications()` — enables GPG signing for every generated artifact
// (AAR, sources JAR, Javadoc JAR, POM, module metadata). Maven Central
// rejects any artifact missing a `.asc` signature.
//
// `coordinates("com.convert", "sdk-android", ...)` wires the groupId +
// artifactId + version consumers reference as:
//     implementation("com.convert:sdk-android:1.0.0")
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        groupId = "com.convert",
        artifactId = "sdk-android",
        version = libs.versions.sdk.version.get(),
    )

    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )

    pom {
        name.set("Convert Android SDK")
        description.set("Android SDK for Convert Experiences A/B testing and feature flags.")
        url.set("https://github.com/convertcom/android-sdk")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("convertcom")
                name.set("Convert.com")
                email.set("support@convert.com")
            }
        }
        scm {
            url.set("https://github.com/convertcom/android-sdk")
            connection.set("scm:git:git://github.com/convertcom/android-sdk.git")
            developerConnection.set("scm:git:ssh://github.com:convertcom/android-sdk.git")
        }
    }
}

// Coverage — NFR19 requires ≥ 70% line coverage for `packages/sdk`. The lower
// bound (vs. packages/core's 85%) reflects the Android-glue nature of this
// module; many branches exercise real Android framework classes that stay
// out of scope for JVM unit tests until Robolectric-backed stories land.
//
// Story 1.2 landed `ConvertSDK`, `ConvertContext`, `ConvertSDK.Builder`, and
// `EventCallback` as API skeletons — the method bodies return placeholders
// or assemble config objects for setters that will later drive real managers
// (Story 2.1+). Coverage tests for the placeholder bodies would assert
// placeholder behaviour, which locks in wrong expectations and creates churn
// when real implementations land. Exclude these classes until their owning
// stories wire real behaviour and write meaningful tests.
//
// As classes graduate from skeleton to implemented, remove them from this
// exclusion list in the corresponding story's dev-story changes.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.convert.sdk.android.ConvertSDK",
                    "com.convert.sdk.android.ConvertSDK\$Builder",
                    "com.convert.sdk.android.ConvertSDK\$Companion",
                    "com.convert.sdk.android.ConvertContext",
                    "com.convert.sdk.android.EventCallback",
                )
            }
        }
        verify {
            rule {
                minBound(70)
            }
        }
    }
}

// AGP 9.1.0's Android library plugin ships its own unit-test runner but
// doesn't wire JUnit Platform by default. Story 1.3 adds a tiny smoke test
// under `testDebugUnitTest` — register the platform so the task recognises
// JUnit 5 tests without forcing each later story to repeat the boilerplate.
androidComponents {
    onVariants { variant ->
        tasks
            .matching { it.name == "test${variant.name.replaceFirstChar { c -> c.uppercase() }}UnitTest" }
            .configureEach {
                (this as Test).useJUnitPlatform()
            }
    }
}
