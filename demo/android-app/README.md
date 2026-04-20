# Convert Android SDK — Demo App

Placeholder. The full demo Android application lands in Epic 7 (Story 7.1+).

This directory is intentionally excluded from the SDK's `settings.gradle.kts`.
Once Story 7.1 implements the demo app, it will discover the SDK as a composite
build via `includeBuild("../../")` rather than being wired as a Gradle subproject.
