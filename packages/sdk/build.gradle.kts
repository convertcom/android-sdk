plugins {
    alias(libs.plugins.android.library)
    // NOTE: org.jetbrains.kotlin.android is intentionally NOT applied here.
    // AGP 9.0+ ships with built-in Kotlin support and rejects the explicit
    // plugin with "The 'org.jetbrains.kotlin.android' plugin is no longer
    // required for Kotlin support since AGP 9.0." (See kotl.in/gradle/agp-built-in-kotlin.)
    // The kotlin-android catalog alias stays in libs.versions.toml so future
    // toolchain changes can reintroduce it if needed.
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.androidx.startup.runtime)
}
