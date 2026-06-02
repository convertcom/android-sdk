plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Authoritative JVM toolchain for this module.
// Per the Kotlin Gradle plugin docs (https://kotlinlang.org/docs/gradle-configure-project.html#gradle-java-toolchains-support),
// `jvmToolchain(17)` automatically sets `jvmTarget = "17"` for the Kotlin compiler AND
// `sourceCompatibility/targetCompatibility = VERSION_17` for AGP 8+. Any duplicate
// declaration via `java { toolchain {} }` produces a Gradle warning and must be omitted.
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
}

tasks.test {
    useJUnitPlatform()
}
