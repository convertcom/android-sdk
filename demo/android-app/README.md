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
| 7.1 | App scaffold, shared layout, stubs |
| 7.2 | Full EventInspectorSheet (two tabs, badges) |
| 7.3 *(this)* | Experiences screen full impl |
| 7.4 | Features screen full impl |
| 7.5 | Conversions journey |
| 7.6 | Offline/airplane-mode demo |

## Try it: Experiences screen

The first tab of the demo — Experiences — exercises the SDK's A/B-testing
surface through a three-beat loop:

1. **Tap** `Run Experience` (primary) or `Run Experiences` (secondary).
2. **Result card** appears at the top of the Experiences screen showing the
   experience key and the resolved variation.
3. **Inspector** event appears in the bottom-sheet Events tab — one
   `BUCKETING` event per bucketed experience.

### Default experience key

`Run Experience` (the primary button) targets the hardcoded experience key
`"test-experience"`. If the Convert account behind your `convertSdkKey`
(see the `local.properties` step above) has an experience with that key,
the visitor is bucketed and you see a green **success** card:

> **Experience: test-experience**
> Variation: treatment

If the key is unknown — which is the default when you use the placeholder
`"demo-sdk-key"` — the `runExperience` call returns `null` and the screen
renders a red **error** card instead. This is a useful visual test of the
error path and an honest signal that your config needs work:

> **No variation for experience test-experience**
> Hint: Check experience config or audience eligibility.

`Run Experiences` (the secondary button) calls the batch surface — it
evaluates every experience the visitor is eligible for and renders one card
per resolved variation. An empty list yields a single hint card
(`"No eligible experiences"`).

### Configuring a real experience

1. Add `convertSdkKey=<your-sdk-key>` to `local.properties` (see Setup step 2).
2. In the Convert dashboard, create a FullStack experience in your project.
   Any key will do — but `"test-experience"` avoids needing to edit the demo
   source. Add at least one variation and point the audience rules at a
   visitor the demo can match (the demo uses an auto-generated UUID visitor
   id and no custom attributes, so an "all traffic" audience is simplest).
3. Rebuild and relaunch. The primary button now renders the success card
   and the inspector fires a `BUCKETING` event carrying `experienceKey`,
   `variationKey`, and `visitorId`.

The result-card list is capped at 20 entries — older cards drop off when
you keep tapping, so the screen stays usable in a demo loop.
