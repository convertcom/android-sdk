plugins {
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // No kotlin-android plugin: AGP 9.1.0 ships with built-in Kotlin support for
    // com.android.library modules. See gradle/libs.versions.toml for the rationale.
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kover) apply false
}

// Resolve the detekt version from the version catalog at configuration time
// so we can wire detekt-formatting as a detektPlugins dep in every subproject.
val detektVersion: String = libs.versions.detekt.get()

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(files("$rootDir/detekt.yml"))
        buildUponDefaultConfig = true
        allRules = false
    }

    dependencies {
        add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:$detektVersion")
    }
}
