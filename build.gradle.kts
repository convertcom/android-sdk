plugins {
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // Intentionally NOT declaring alias(libs.plugins.kotlin.android): AGP 9.1.0
    // provides built-in Kotlin support for Android modules and rejects the
    // standalone org.jetbrains.kotlin.android plugin. Retaining the alias in
    // libs.versions.toml for the demo app (Story 7.1) and documentation.
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension>("detekt") {
        config.setFrom(files("$rootDir/detekt.yml"))
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = false
        parallel = true
    }

    dependencies {
        val detektFormatting =
            rootProject
                .extensions
                .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
                .named("libs")
                .findLibrary("detekt-formatting")
                .get()
        "detektPlugins"(detektFormatting)
    }
}
