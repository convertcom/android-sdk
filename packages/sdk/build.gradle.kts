plugins {
    // AGP 9.1.0 ships built-in Kotlin support and refuses to apply alongside
    // org.jetbrains.kotlin.android — see https://kotl.in/gradle/agp-built-in-kotlin.
    // This deviates from Story 1.1's Dev Notes which predate the final AGP 9.1.0 behaviour.
    alias(libs.plugins.android.library)
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
    implementation(libs.androidx.startup)
}
