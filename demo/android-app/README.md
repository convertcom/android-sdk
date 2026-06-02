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
| 7.3 | Experiences screen full impl |
| 7.4 *(this)* | Features screen full impl |
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

## Try it: Features screen

The second tab of the demo — Features — exercises the SDK's feature-flag
surface. The loop mirrors the Experiences screen's three beats:

1. **Tap** `Run Feature` (primary) or `Run Features` (secondary).
2. **Result card** appears at the top of the Features screen showing the
   feature key, its `enabled` / `disabled` status, the owning
   experience key (when the feature resolved through bucketing), and
   every typed variable rendered as `name: value [Type]`.
3. **Inspector** `BUCKETING` event appears in the bottom-sheet Events
   tab — one event per feature resolved by the tap. Features resolve
   through experience bucketing per the SDK's Story 4.1 contract, so
   the payload carries `experienceKey`, `variationKey`, and
   `visitorId` just like the Experiences screen.

### Default feature key

`Run Feature` (the primary button) targets the hardcoded feature key
`"test-feature"`. When the Convert account behind your `convertSdkKey`
(see the `local.properties` step above) has a feature with that key,
the visitor is bucketed and you see a green **success** card:

> **Feature: test-feature**
> Status: enabled
> Experience: homepage-banner
> buttonColor: "blue" [string]
> maxRetries: 3 [integer]
> showBanner: true [boolean]
> discountFactor: 0.15 [float]

Each typed variable shows its **name**, its **value** (strings get
double-quoted; numbers and booleans render unquoted), and its
**type annotation** — the `[type]` suffix uses the JS SDK canonical
lowercase type vocabulary (`string`, `integer`, `float`, `boolean`,
`json`, `unknown`) so the demo reads with the same names as backend
config and JS SDK docs, and is rendered in `labelMedium` typography
with the Material 3 `outline` colour so it reads as visually
secondary to the value.

If the key is unknown — which is the default when you use the
placeholder `"demo-sdk-key"` — the `runFeature` call returns `null` and
the screen renders a red **error** card instead. This is a useful
visual test of the error path and an honest signal that your config
needs work:

> **No feature for key test-feature**
> Hint: Check feature config or audience eligibility.

`Run Features` (the secondary button) calls the batch surface — it
evaluates every feature the visitor is eligible for and renders one
card per returned feature. An empty list yields a single hint card
(`"No eligible features"`).

### Configuring a real feature

1. Add `convertSdkKey=<your-sdk-key>` to `local.properties` (see Setup step 2).
2. In the Convert dashboard, create a FullStack feature in your project.
   Any key will do — but `"test-feature"` avoids needing to edit the demo
   source. Attach the feature to at least one variation of an
   experience, add a few variables of different types (a `String`, an
   `Int`, a `Boolean`, and a `Double` hits every row the screen
   renders), and point the audience rules at a visitor the demo can
   match.
3. Rebuild and relaunch. The primary button now renders the success
   card with typed variables; the inspector fires a `BUCKETING` event
   carrying the resolving experience and variation.

The feature result-card list is capped at 20 entries, independent of
the Experiences list — tapping one screen's buttons never drops the
other's cards.
