plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
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
    // Gradle 9 no longer adds the JUnit Platform launcher transitively; surface it explicitly
    // so the test executor can load the platform. See
    // https://docs.gradle.org/9.0/userguide/upgrading_version_8.html#test_framework_implementation_dependencies
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Coverage — NFR19 requires ≥ 85% line coverage for `packages/core`. The
// `main` verification rule applies to the default Kover report; the Kover
// 0.9.x DSL exposes these via `reports { verify { rule { ... } } }`.
//
// The `com.convert.sdk.core.port` package is excluded from coverage because it
// contains only `interface` declarations and a trivial `NoOp` logger
// singleton. Real exercise of these ports happens in `packages/sdk` where the
// concrete adapters live (OkHttp-backed HttpClient, android.util.Log-backed
// Logger, etc., landing in Story 2.2 onwards). Interface byte-code
// (default-argument synthetic methods, companion-object `NoOp` singletons)
// registers as "missed lines" under Kover despite being genuinely untestable
// — excluding ports keeps the 85% bar meaningful rather than padding it with
// trivial tests of no-op defaults.
kover {
    reports {
        // The top-level `filters` block seeds every report set (XML, HTML,
        // log) with a default exclusion of the pure-interface `port` package.
        filters {
            excludes {
                packages("com.convert.sdk.core.port")
            }
        }
        verify {
            rule {
                minBound(85)
            }
        }
    }
}
