# Convert Android SDK

[![CI](https://github.com/convertcom/android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/convertcom/android-sdk/actions/workflows/ci.yml)

Native Android SDK for the Convert Experiences A/B testing platform.

## Installation

Add the SDK to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.convert:sdk-android:<version>")
}
```

The `sdk-core` module is pulled in transitively — no separate dependency
required.

The SDK is published to [Maven Central](https://central.sonatype.com/artifact/com.convert/sdk-android),
so no custom repository configuration is needed (Maven Central is included
by default in Android's `settings.gradle.kts`).

A full quick-start guide will be published in Story 6.2.

## Prerequisites

- JDK 17 or newer
- Android SDK (command-line tools sufficient for library builds)

## Build

```bash
./gradlew build
```

## Release

See [RELEASE.md](./RELEASE.md) for the automated release pipeline, required
GitHub secrets, and one-time Sonatype Central Portal setup.
