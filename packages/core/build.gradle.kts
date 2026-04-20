import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    // Story 1.4 — `packages/core` is published as a standalone JAR because
    // `packages/sdk` declares it with `api(project(":packages:core"))`, so
    // Maven Central must host both artifacts at matching versions.
    alias(libs.plugins.vanniktech.maven.publish)
}

// Publish coordinates — same version catalog entry as packages/sdk so the
// two artifacts always ship in lockstep.
version = libs.versions.sdk.version.get()

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

// Story 1.4 — Maven Central publish configuration (vanniktech 0.36.0).
//
// `KotlinJvm(JavadocJar, SourcesJar)` — new typed constructor (the boolean
// overload is deprecated). For a pure Kotlin-JVM library we publish:
//   - a main JAR,
//   - `SourcesJar.Sources()` — real sources JAR so IDEs can show original
//     Kotlin instead of the decompiled classfile,
//   - `JavadocJar.Empty()` — empty Javadoc-stub JAR. Maven Central's
//     validator requires a Javadoc artifact to be present but does not
//     inspect its content; an empty stub is the conventional minimum for
//     Kotlin libraries (Dokka-HTML output is not a Javadoc substitute).
//
// `publishToMavenCentral()` — 0.36.0 targets the Central Portal
// unconditionally (the legacy `SonatypeHost` enum was removed along with
// OSSRH support in this version).
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        groupId = "com.convert",
        artifactId = "sdk-core",
        version = libs.versions.sdk.version.get(),
    )

    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )

    pom {
        name.set("Convert Android SDK Core")
        description.set(
            "Pure-Kotlin core module for the Convert Android SDK — " +
                "interfaces, domain models, and platform-neutral utilities.",
        )
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
