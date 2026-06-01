# Convert Android SDK — Demo App

Standalone Compose demo app showcasing the Convert Android SDK. This is **not** part of the root SDK Gradle build — open `demo/android-app/` as its own Android Studio project (File → Open → select the `demo/android-app` directory).

## Quick start (macOS / Linux)

From `demo/android-app/`:

```bash
./scripts/setup-emulator.sh                  # installs system image + creates AVD; prints the exact launch command at the end
cp local.properties.example local.properties # then edit to add convertSdkKey + tunables for your Convert account
# 1) Paste the launch command from setup-emulator.sh's output to start the emulator in a standalone window.
# 2) In Android Studio: device dropdown → Convert_Demo_API_34 → ▶ Run.
```

The script auto-detects your Android SDK location (via `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, or the macOS default `~/Library/Android/sdk`), so you do not need to set any environment variables first. It installs the only system image known to work for this demo (Google APIs ARM64 / x86_64 for API 34 — NOT Android XR) and creates an AVD bound to it. See [Setup](#setup) for `local.properties` details and [Run the demo in Android Studio](#run-the-demo-in-android-studio) for the cross-platform manual path (including Windows).

## How the SDK is discovered

`settings.gradle.kts` uses `includeBuild("../../")` to pull the root SDK project (`android-sdk/`) in as a composite build. Gradle substitutes the `implementation("com.convert:sdk-android")` dependency declared in `app/build.gradle.kts` with the locally-built AAR. No prior `./gradlew publishToMavenLocal` needed.

If SDK code changes aren't picked up, run `./gradlew --stop` in both projects and clean, or use Android Studio's "Reload Gradle Project" action.

## Setup

1. **Provision an emulator.** On macOS / Linux, run `./scripts/setup-emulator.sh` (see [Quick start](#quick-start-macos--linux)). On Windows, follow the manual path in [Run the demo in Android Studio](#run-the-demo-in-android-studio) → "Manual setup via Android Studio".
2. **Open `demo/android-app/`** in Android Studio.
3. **(Optional) Copy `local.properties.example` to `local.properties`** and fill in any of the exposed tunables: `convertSdkKey`, `convertEnvironment`, `convertExperienceKey`, `convertFeatureKey`, `convertGoalKey`, `convertVisitorAttributes`, `convertLocationProperties`. Every entry is optional — when omitted, the demo falls back to the hardcoded literals documented in `local.properties.example` and still builds cleanly.
4. **Run the demo** (see the [Run the demo in Android Studio](#run-the-demo-in-android-studio) section below).

### Configuring the demo against your Convert account

The demo ships with **generic placeholder fallbacks** (`test-experience`, `test-feature`, `purchase-goal`, plus empty `{}` for visitor attributes and location properties). The expectation is that anyone cloning this repo points the demo at experiences/features/goals that exist in **their own** Convert account — the placeholders are NOT live keys.

Minimum viable `local.properties`:

```
convertSdkKey=<your-sdk-key>
convertExperienceKey=<an-experience-key-that-exists-in-your-account>
convertFeatureKey=<a-feature-key-that-exists-in-your-account>
convertGoalKey=<a-goal-key-that-exists-in-your-account>
```

If any of those experiences/features have audience rules that gate on visitor attributes, add the matching attributes:

```
convertVisitorAttributes={"<your-attr-key>":<value>, ...}
```

If any have location rules, add the matching location properties:

```
convertLocationProperties={"<your-location-key>":"<value>"}
```

All five non-`convertSdkKey` fields are optional — the demo still launches if they're omitted, but with placeholder keys the `runExperience` / `runFeature` / `trackConversion` calls will return null/no-op and the inspector will surface "No eligible experiences" or "No variation for experience …" cards. That's an honest signal that the demo needs to be pointed at real keys.

## Run the demo in Android Studio

1. **Open the project.** In Android Studio: `File → Open` and select the `demo/android-app/` folder (not the repo root — the demo is a standalone Gradle project wired to the SDK via `includeBuild("../../")`).
2. **Wait for Gradle sync.** On first open, Android Studio indexes the project and syncs Gradle. When you see the **"Sync successful"** toast at the bottom, the `app` run configuration is auto-generated from the `:app` module's AGP metadata and pre-selected in the toolbar dropdown — no manual creation needed.
3. **Pick a target device.** Either start an emulator (two paths below) or plug in a physical device with USB debugging enabled. The device dropdown sits immediately to the right of the run-configuration dropdown.

   **A. Recommended one-shot CLI setup (macOS + Linux):**

   ```bash
   ./scripts/setup-emulator.sh
   ```

   Installs the right Google APIs system image (~1.5 GB on first run) and creates an AVD named `Convert_Demo_API_34`. Idempotent. The script auto-detects the Android SDK location (via `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, or the macOS default `~/Library/Android/sdk`) so no environment-variable setup is required. After it finishes, the script prints the exact `emulator -avd Convert_Demo_API_34 &` command with the resolved absolute path — paste that to launch the emulator in a standalone window, then select `Convert_Demo_API_34` in Android Studio's device dropdown. **Skip section B if you used this.**

   **B. Manual setup via Android Studio (cross-platform, including Windows):**

   - **System image — use a "Google APIs" or "Google Play" image, NOT "Android XR".** Pick any Pixel hardware profile on Android 7.0 / API 24 or later, then in the system-image step select an image whose tag is "Google APIs" or "Google Play". On Apple Silicon Macs that means the `arm64-v8a` variant. Do **not** select an "Android XR" image — those are intended for XR headset / glasses development and remap host mouse input through a spatial pointer model, so on a 2D phone skin Compose `onClick` handlers never fire and drag-to-expand on the inspector sheet is inert. If your SDK Manager only offers an Android XR image for the API level you want, install a Google APIs image first: **SDK Manager → SDK Platforms → (tick) Show Package Details → Android 14 (or your target) → "Google APIs ARM 64 v8a System Image"** (Apple Silicon) or **"Google APIs Intel x86_64 System Image"** (Intel).
   - **Run the emulator in a standalone window, not the embedded "Running Devices" tool window.** The tool window does not dispatch multi-touch gestures (see [emulator troubleshooting docs](https://developer.android.com/studio/run/emulator-troubleshooting) — "Multi-touch does not work in tool window"), so drag-to-expand on the inspector sheet won't work there. It also only forwards full keyboard/mouse events to the app when **Hardware Input** mode is enabled. The simplest reliable setup is to launch the emulator in its own window: **Android Studio → Settings… → Tools → Emulator** (on macOS: **Android Studio menu → Settings…**) → **deselect** *"Launch in the Running Devices tool window"* → **Apply** → **OK**. Then cold-boot the AVD via **Device Manager → ⋮ → Cold Boot Now** so the change takes effect. The emulator now opens in a standalone window with full mouse and multi-touch support.

4. **Click the green ▶ Run button** (or `Shift+F10` on Linux/Windows, `⌃R` on macOS). Android Studio builds the `:app` module, installs the APK, and launches the demo. Launch success = the demo home screen renders with the **Experiences** tab selected.

You do NOT need to create a new run configuration — Android Studio picks up the Application configuration for the `:app` module automatically at sync time. If the dropdown shows "Add Configuration" instead of `app`, Gradle sync hasn't completed yet; wait for it.

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

## Try it: Experiences screen

The first tab of the demo — Experiences — exercises the SDK's A/B-testing surface through a three-beat loop:

1. **Tap** `Run Experience` (primary) or `Run Experiences` (secondary).
2. **Result card** appears at the top of the Experiences screen showing the experience key and the resolved variation.
3. **Inspector** event appears in the bottom-sheet Events tab — one `BUCKETING` event per bucketed experience.

### Default experience key

`Run Experience` (the primary button) targets the hardcoded experience key `"test-experience"`. If the Convert account behind your `convertSdkKey` (see the `local.properties` step above) has an experience with that key, the visitor is bucketed and you see a green **success** card:

> **Experience: test-experience**
> Variation: treatment

If the key is unknown — which is the default when you use the placeholder `"demo-sdk-key"` — the `runExperience` call returns `null` and the screen renders a red **error** card instead. This is a useful visual test of the error path and an honest signal that your config needs work:

> **No variation for experience test-experience**
> Hint: Check experience config or audience eligibility.

`Run Experiences` (the secondary button) calls the batch surface — it evaluates every experience the visitor is eligible for and renders one card per resolved variation. An empty list yields a single hint card (`"No eligible experiences"`).

### Configuring a real experience

1. Add `convertSdkKey=<your-sdk-key>` to `local.properties` (see Setup step 2).
2. In the Convert dashboard, create a FullStack experience in your project. Any key will do — but `"test-experience"` avoids needing to edit the demo source. Add at least one variation and point the audience rules at a visitor the demo can match (the demo uses an auto-generated UUID visitor id and no custom attributes, so an "all traffic" audience is simplest).
3. Rebuild and relaunch. The primary button now renders the success card and the inspector fires a `BUCKETING` event carrying `experienceKey`, `variationKey`, and `visitorId`.

You can also override the key itself without touching source — set `convertExperienceKey=<your-experience-key>` in `local.properties` and rebuild.

The result-card list is capped at 20 entries — older cards drop off when you keep tapping, so the screen stays usable in a demo loop.

## Try it: Features screen

The second tab of the demo — Features — exercises the SDK's feature-flag surface. The loop mirrors the Experiences screen's three beats:

1. **Tap** `Run Feature` (primary) or `Run Features` (secondary).
2. **Result card** appears at the top of the Features screen showing the feature key, its `enabled` / `disabled` status, the owning experience key (when the feature resolved through bucketing), and every typed variable rendered as `name: value [Type]`.
3. **Inspector** `BUCKETING` event appears in the bottom-sheet Events tab — one event per feature resolved by the tap. Features resolve through experience bucketing per the SDK's Story 4.1 contract, so the payload carries `experienceKey`, `variationKey`, and `visitorId` just like the Experiences screen.

### Default feature key

`Run Feature` (the primary button) targets the hardcoded feature key `"test-feature"`. When the Convert account behind your `convertSdkKey` (see the `local.properties` step above) has a feature with that key, the visitor is bucketed and you see a green **success** card:

> **Feature: test-feature**
> Status: enabled
> Experience: homepage-banner
> buttonColor: "blue" [string]
> maxRetries: 3 [integer]
> showBanner: true [boolean]
> discountFactor: 0.15 [float]

Each typed variable shows its **name**, its **value** (strings get double-quoted; numbers and booleans render unquoted), and its **type annotation** — the `[Type]` suffix is rendered in `labelMedium` typography with the Material 3 `outline` colour so it reads as visually secondary to the value.

If the key is unknown — which is the default when you use the placeholder `"demo-sdk-key"` — the `runFeature` call returns `null` and the screen renders a red **error** card instead. This is a useful visual test of the error path and an honest signal that your config needs work:

> **No feature for key test-feature**
> Hint: Check feature config or audience eligibility.

`Run Features` (the secondary button) calls the batch surface — it evaluates every feature the visitor is eligible for and renders one card per returned feature. An empty list yields a single hint card (`"No eligible features"`).

### Configuring a real feature

1. Add `convertSdkKey=<your-sdk-key>` to `local.properties` (see Setup step 2).
2. In the Convert dashboard, create a FullStack feature in your project. Any key will do — but `"test-feature"` avoids needing to edit the demo source. Attach the feature to at least one variation of an experience, add a few variables of different types (a `String`, an `Int`, a `Boolean`, and a `Double` hits every row the screen renders), and point the audience rules at a visitor the demo can match.
3. Rebuild and relaunch. The primary button now renders the success card with typed variables; the inspector fires a `BUCKETING` event carrying the resolving experience and variation.

You can also override the key itself without touching source — set `convertFeatureKey=<your-feature-key>` in `local.properties` and rebuild.

The feature result-card list is capped at 20 entries, independent of the Experiences list — tapping one screen's buttons never drops the other's cards.

## Try it: Conversions screen

The third tab of the demo — Conversions — exercises the SDK's conversion-tracking surface AND the per-visitor dedup guard shipped in Story 4.3. The full A/B loop (bucketing → variation → user action → conversion tracked) is observable here:

1. **Tap** `Run Experience` on the Experiences tab first — this buckets the visitor and fires a `BUCKETING` event (so the subsequent conversion has an experience/variation to attribute to).
2. **Switch to the Conversions tab** and tap `Buy`.
3. **Result card** appears at the top of the Conversions screen:

   > **Conversion tracked: purchase-goal**
   > Amount: 10.3
   > ProductsCount: 2

4. **Inspector** `CONVERSION` event appears in the bottom-sheet Events tab carrying `goalKey`, `amount`, and `productsCount`. Because you bucketed first, the event is attributed to the correct experience/variation behind the scenes.

### Dedup behaviour (second tap)

Tap `Buy` a second time and you'll see:

> **Conversion already tracked (dedup)**
> Goal: purchase-goal

AND a `DEBUG` line in the bottom-sheet **Logs** tab reading:

> `Goal 'purchase-goal' already tracked for visitor, skipping`

No new `CONVERSION` event appears in the Events tab — the SDK's Story 4.3 AC-6 dedup guard silently skips the repeat track for the same goal + visitor combination.

### Goal key and payload

`Buy` is hardcoded to track the goal key `"purchase-goal"` with the payload:

```kotlin
listOf(
    GoalData(GoalDataKey.AMOUNT, JsonPrimitive(10.3)),
    GoalData(GoalDataKey.PRODUCTS_COUNT, JsonPrimitive(2)),
)
```

If the Convert account behind your `convertSdkKey` does not have a goal with key `"purchase-goal"`, the SDK logs a `WARN` (visible in the Logs tab) and no `CONVERSION` event fires — this is a useful error path for exercising the "unknown goal" branch.

### Configuring a real goal

1. Add `convertSdkKey=<your-sdk-key>` to `local.properties` (see Setup step 2).
2. In the Convert dashboard, create a goal in your project with the key `"purchase-goal"` (or pick a different key and override it via `convertGoalKey=<your-goal-key>` in `local.properties`). Set the goal type to `Revenue` or `Monetary` so `AMOUNT` and `PRODUCTS_COUNT` payload values are meaningful.
3. Rebuild and relaunch. The primary button now fires a real `CONVERSION` event against the configured goal.

You can also override the key itself without touching source — set `convertGoalKey=<your-goal-key>` in `local.properties` and rebuild.

### Dedup persists across app restarts

The SDK's dedup is **per-visitor**, not per-session. If you close the app, relaunch, and tap `Buy` again, the dedup still kicks in because the visitor id is auto-persisted. This is the correct Story 4.3 behaviour — it mirrors how production analytics dedupe repeat conversions. If you want to exercise a "fresh" conversion, clear the app's data (or uninstall + reinstall) to get a new visitor id.

The conversion result-card list is capped at 20 entries, independent of the Experiences and Features lists — tapping one screen's buttons never drops the other screens' cards. Clearing the Conversions list through a future `Clear` button (not wired in this MVP) would NOT reset the dedup memory — only a fresh visitor id does.

## Try it: Offline screen

The fourth tab — Offline — proves the SDK's offline resilience contract from Story 5.2. The demonstration is a four-beat cycle anchored on the device's **airplane mode** toggle, because modern Android does not let apps programmatically enable or disable connectivity (Story 7.6 Gotcha 1):

1. **Enable airplane mode** on the device/emulator (Settings → Network & internet → Airplane mode, or long-press the power button → Airplane mode). The inspector's network-status pill and the Offline screen's status banner both switch to `Offline` (red dot).
2. **Tap `Run Experience` or `Buy` while offline.** A result card appears at the top of the Offline screen (the same ResultCard the Experiences / Conversions screens render), and the inspector's Events tab shows a new BUCKETING or CONVERSION event with the **QUEUED** badge (amber). The SDK has captured the event locally but has not flushed it.
3. **Disable airplane mode.** Story 5.2's NetworkObserver fires on the OS's `onAvailable` callback; the SDK drains its event queue; a `api.queue.released` event fires with `statusCode=200`; the inspector transitions every currently-queued event to the **DELIVERED** badge (green). Zero events are lost.
4. **Compare counts.** The number of DELIVERED events matches the number of taps you made while offline — the SDK's offline guarantee is visible, not theoretical.

### Why `FLUSHING` (blue) is rare

The inspector's lifecycle enum includes a `FLUSHING` state between `QUEUED` and `DELIVERED`, but the current SDK does not fire a dedicated `flush-started` event — so queued events typically jump straight from QUEUED to DELIVERED when `api.queue.released` fires. The state remains in the enum for forward-compatibility with a future SDK that surfaces flush-in-flight visibility; for now, the two observable states are QUEUED (amber) and DELIVERED (green).

### Offline screen buttons

- **Run Experience** targets the same hardcoded `"test-experience"` key as the Experiences screen. Sticky bucketing (Story 3.2) means a visitor bucketed on the Experiences tab stays bucketed here — same variation, same card.
- **Buy** calls the same `trackPurchaseConversion()` path as the Conversions tab. A first tap produces a tracked card; a second tap produces the dedup card and the corresponding DEBUG log line. The inspector's CONVERSION event still fires on the first tap only.

## Try it: Config screen

The fifth tab — Config — is a read-only panel showing what the SDK currently knows about your project. There are no taps here; the panel updates reactively whenever the SDK fires `ready` or `config.updated`.

### Panel rows

| Row | Source | Example |
|-----|--------|---------|
| SDK Key | `BuildConfig.convertSdkKey` (from `local.properties`) | `abcdef12...` |
| Environment | `BuildConfig.convertEnvironment` (from `local.properties`) | `staging` or `(not set)` |
| Active Experiences | `ConvertContext.runExperiences()` count + keys | `2 active — welcome, checkout` |
| Active Features | `ConvertContext.runFeatures()` count + keys | `1 active — banner` |
| Config Last Fetched | Stamped when the ViewModel observes `ready` / `config.updated` | `14:32:17.842` |
| Tracking Enabled | `ConvertSDK.isTrackingEnabled()` | `Yes` / `No` / `—` |

### SDK key masking

The SDK Key row **never** renders the full key. Any key longer than 8 characters is masked to `<first 8 chars>...` so a screenshare or screenshot cannot leak it. Short placeholders (`"demo-sdk-key"` is 12 chars, so it also masks to `demo-sdk...`) still render masked. Your real SDK key lives in `local.properties` which is git-ignored.

### States

- **Loading** — a `CircularProgressIndicator` plus "Fetching configuration..." text renders until the SDK fires its first `ready` event.
- **Loaded** — the `ConfigInfoPanel` replaces the spinner with the six rows above.
- **Failed** — an error card renders when the SDK has not fired `ready` AND a `WARN` or `ERROR` log has accumulated (typically "no cached config + network fetch failed"). The card shows the captured log message as the reason and the canonical `Check network + SDK key` hint. Fix your `convertSdkKey` and relaunch.

### Active vs configured

The panel says "Active" because it reads the **eligible** sets for the current visitor via `ConvertContext.runExperiences()` / `runFeatures()`, not the raw project config. A visitor outside an experience's audience will see that experience omitted. This is the most honest answer the SDK's public API surfaces today; the dashboard's project view is the source of truth for the configured superset.
