# Convert Android SDK — Demo App

Standalone Compose demo app showcasing the Convert Android SDK. This is **not** part of the root SDK Gradle build — open `demo/android-app/` as its own Android Studio project (File → Open → select the `demo/android-app` directory).

## How the SDK is discovered

`settings.gradle.kts` uses `includeBuild("../../")` to pull the root SDK project (`android-sdk/`) in as a composite build. Gradle substitutes the `implementation("com.convert:sdk-android")` dependency declared in `app/build.gradle.kts` with the locally-built AAR. No prior `./gradlew publishToMavenLocal` needed.

If SDK code changes aren't picked up, run `./gradlew --stop` in both projects and clean, or use Android Studio's "Reload Gradle Project" action.

## Setup

1. Open `demo/android-app/` in Android Studio.
2. (Optional) Copy `local.properties.example` to `local.properties` and fill in your `convertSdkKey=...`. When omitted, the demo builds with a placeholder key — the first config fetch will fail quietly but the UI still launches.
3. Run the `app` configuration on an emulator or device.

## Structure

```
demo/android-app/
├── settings.gradle.kts          # includeBuild("../../")
├── build.gradle.kts             # empty root module
├── gradle.properties
├── local.properties.example
└── app/
    ├── build.gradle.kts         # Compose + SDK deps
    └── src/main/
        ├── AndroidManifest.xml  # portrait-only, ACCESS_NETWORK_STATE
        └── kotlin/com/convert/sdk/demo/
            ├── DemoApplication.kt     # SDK init
            ├── MainActivity.kt        # BottomSheetScaffold shell
            ├── ui/navigation/         # 5-destination bottom nav
            ├── ui/screen/             # stub screens (full impl in 7.2–7.6)
            ├── ui/component/          # inspector stub
            ├── viewmodel/             # SdkViewModel + InspectorEvent
            └── logger/                # DemoLogger (Logcat + UI sink)
```

## Story map

| Story | Scope |
|-------|-------|
| 7.1 *(this)* | App scaffold, shared layout, stubs |
| 7.2 | Full EventInspectorSheet (two tabs, badges) |
| 7.3 | Experiences screen full impl |
| 7.4 | Features screen full impl |
| 7.5 | Conversions journey |
| 7.6 | Offline/airplane-mode demo |
