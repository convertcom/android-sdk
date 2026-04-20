plugins {
    // AGP 9.1.0 ships built-in Kotlin support and refuses to apply alongside
    // org.jetbrains.kotlin.android — see https://kotl.in/gradle/agp-built-in-kotlin.
    // This deviates from Story 1.1's Dev Notes which predate the final AGP 9.1.0 behaviour.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

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
