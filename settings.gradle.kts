pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// foojay-resolver-convention enables Gradle to auto-provision JDK 17 when the
// host only has newer JDKs installed (e.g., JDK 23 via Homebrew). Without this,
// the Kotlin/Java toolchain { languageVersion = 17 } configuration fails on
// machines lacking a local JDK 17 install.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-sdk"

include(":packages:core", ":packages:sdk")
