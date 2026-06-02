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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":packages:core"))
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.startup.runtime)
}
