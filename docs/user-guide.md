# Convert Android SDK — User Guide

This guide walks you from zero to production integration. It assumes you're comfortable with Android + Kotlin (Java notes are called out where they matter) but have never used Convert. Terms are defined inline the first time they appear.

- [Concepts (Quick Glossary)](#concepts-quick-glossary)
- [Installation](#installation)
- [Initialization](#initialization)
- [Configuration Options](#configuration-options)
- [Visitor Context](#visitor-context)
- [Running Experiences](#running-experiences)
- [Feature Flags](#feature-flags)
- [Conversion Tracking](#conversion-tracking)
- [Visitor Segmentation](#visitor-segmentation)
- [Offline Behavior](#offline-behavior)
- [Tracking Control](#tracking-control)
- [Logging](#logging)
- [Google Play Data Safety](#google-play-data-safety)
- [Troubleshooting](#troubleshooting)
- [Java Interop Notes](#java-interop-notes)

---

## Concepts (Quick Glossary)

| Term | Meaning |
|---|---|
| **Experience** | An A/B test or personalisation, keyed by a merchant-defined string (e.g. `homepage-redesign`). |
| **Variation** | One arm of an experience (e.g. `control`, `treatment-a`). The SDK buckets each visitor into exactly one variation. |
| **Bucketing** | Deterministic hash of `(visitorId, experienceId)` — the same visitor always lands in the same variation for a given experience (sticky). |
| **Feature flag** | A named boolean/variable bundle delivered via the same bucketing mechanism. Guards code paths without a full experience. |
| **Goal** | A named event (e.g. `signup-completed`, `purchase-completed`) that counts toward experience success metrics. |
| **Segment** | A key/value label on the visitor (e.g. `plan=premium`). Used to target audiences and reported with every tracking event. |
| **Audience** | A server-side rule that gates an experience to visitors matching a segment/attribute pattern. |

---

## Installation

### Gradle dependency

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.convert:sdk-android:+")  // pin to a specific version in production
}
```

### Android Gradle Plugin & JDK

- **JDK 17** to run Gradle.
- **AGP 8.x** or newer.
- **`minSdk` 24** (Android 7.0) — the SDK will refuse to compile against a lower value.
- **`compileSdk` 35** recommended to match the SDK's own compile target.

### ProGuard / R8

The SDK publishes a `consumer-rules.pro` inside the AAR. If your app enables R8, consumer rules are applied automatically — you do **not** need to copy any rules into your own proguard file.

If you hit a stripped-class warning from R8, open an issue with the obfuscation report; that's a bug we need to fix in the consumer rules.

### Permissions

For best offline-recovery behaviour, declare `ACCESS_NETWORK_STATE` in your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

The SDK uses this permission to observe connectivity changes via `ConnectivityManager.registerDefaultNetworkCallback` — when the device regains connectivity, the SDK triggers an immediate flush of any events it queued while offline.

If the permission is missing the SDK **degrades gracefully**: the network observer's registration call catches the `SecurityException`, the SDK logs nothing, and the foreground-retry path still delivers queued events eventually (the only loss is the "push immediately on reconnect" optimisation — events still flush on the next scheduled batch).

No other permissions are required. `INTERNET` is auto-merged from AGP's default library manifest.

---

## Initialization

### The Builder

`ConvertSDK` has no public constructor. All instances come through `ConvertSDK.builder(context).build()`:

```kotlin
import com.convert.sdk.android.ConvertSDK
import com.convert.sdk.core.model.LogLevel

val sdk = ConvertSDK.builder(applicationContext)
    .sdkKey("YOUR_SDK_KEY")
    .logLevel(LogLevel.INFO)     // optional
    .build()
```

Call `build()` exactly once per process — ideally from `Application.onCreate()`. The SDK owns a long-lived coroutine scope, a SQLite queue, a WorkManager registration, and network observers; creating multiple instances wastes resources and can cause duplicate tracking events.

### Direct-data mode (offline-only / pre-fetched config)

If you already have a config blob (e.g. shipped with your app bundle, fetched by a separate layer, or a test fixture) you can skip the network fetch:

```kotlin
import com.convert.sdk.core.model.generated.ConfigResponseData

val config: ConfigResponseData = loadBakedConfig()  // your own loader
val sdk = ConvertSDK.builder(applicationContext)
    .data(config)
    .build()
```

When both `sdkKey(...)` and `.data(...)` are set, the SDK logs a WARN and prefers `.data` (the pre-fetched config overrides the fetch). Neither set → every call becomes a no-op (returns `null`/empty) and a WARN is logged.

### `onReady { ... }` — waiting for config to land

Config fetch is asynchronous. Until the first config is loaded, every `runExperience` call returns `null` and `trackConversion` is a silent skip. Use `onReady` to gate your first interaction:

```kotlin
sdk.onReady {
    val ctx = sdk.createContext()
    val variation = ctx.runExperience("homepage-redesign")
    applyVariation(variation)
}
```

Callbacks registered **after** the SDK is already ready are dispatched on the next coroutine tick — you never miss the event because of a timing race. Dispatch thread is `Dispatchers.Default`; marshal back to the main thread inside your callback if you touch UI.

### Subscribing to other events

Beyond `onReady`, the SDK emits named events (`bucketing`, `conversion`, `config.updated`, `api.queue.released`, `segments`). Subscribe with `on(event, callback)`:

```kotlin
import com.convert.sdk.android.EventCallback
import com.convert.sdk.core.event.SystemEvents

val token = sdk.on(SystemEvents.BUCKETING, EventCallback { data ->
    // data = {"experienceKey": ..., "variationKey": ..., "visitorId": ...}
})

// Later:
sdk.off(SystemEvents.BUCKETING, token)
```

Both the token form and the callback-identity form of `off(...)` work; prefer the token form for new code.

---

## Configuration Options

Every `Builder` method is optional except `sdkKey(...)` OR `.data(...)`. Set what you need, leave the rest at defaults.

| Method | Purpose | Default |
|---|---|---|
| `sdkKey(value)` | Merchant-facing SDK key | required (unless `.data` supplied) |
| `sdkKeySecret(value)` | Confidential SDK secret — used when the server requires signed requests | `null` |
| `data(config)` | Pre-fetched `ConfigResponseData`; bypasses the initial network fetch | `null` |
| `environment(value)` | `"staging"` / `"prod"` hint forwarded with every request | library default |
| `configEndpoint(url)` | Override the config-fetch URL (staging / on-prem) | Convert CDN |
| `trackEndpoint(url)` | Override the tracking URL (staging / on-prem) | Convert tracking endpoint |
| `dataRefreshInterval(millis)` | Config re-fetch interval | `600_000` (10 min) |
| `batchSize(size)` | Maximum events per outbound batch | JS-SDK parity |
| `releaseInterval(millis)` | Minimum delay between flushes | JS-SDK parity |
| `hashSeed(seed)` | MurmurHash3 seed for bucketing | JS-SDK parity |
| `maxTraffic(basisPoints)` | Total traffic basis for bucketing | `10000` |
| `excludeExperienceIdHash(bool)` | Legacy-account bucketing compatibility | `false` |
| `logLevel(level)` | Minimum log severity | `ERROR` |
| `trackingEnabled(bool)` | Boot-time tracking switch (see [Tracking Control](#tracking-control)) | `true` |
| `cacheLevel(level)` | HTTP cache directive hint | `"default"` |
| `rulesKeysCaseSensitive(bool)` | Rule-engine key case sensitivity | `true` |
| `rulesNegation(str)` | Rule-engine negation semantics | library default |

All options mirror the Convert JS SDK's `Config` type. If you're porting from the JS SDK, every field name is reachable — the Android builder simply exposes them through camelCase setters.

---

## Visitor Context

### Creating a context

A `ConvertContext` binds one visitor to the SDK:

```kotlin
// 1) Auto-visitor — UUID v4 persisted in SharedPreferences.
// Same id on every app launch; lost only on app uninstall.
val ctx = sdk.createContext()

// 2) Explicit visitor — you own the id (e.g. your own login id).
val ctx = sdk.createContext("visitor-42")

// 3) Explicit visitor + initial attributes.
val ctx = sdk.createContext(
    visitorId = "visitor-42",
    attributes = mapOf(
        "plan" to "premium",
        "accountAgeDays" to 120,
    ),
)
```

The auto-visitor path is what you want for anonymous flows. The explicit-id overloads **do not** read or write the auto-UUID — they're independent, so you can hold both in the same session (e.g. anonymous browse, then signed-in checkout).

### Attributes

Attributes are any key/value pairs keyed by string. They feed the audience/location rule engine and do **not** flow into outbound tracking events (segments do — see below). Replace, not merge:

```kotlin
ctx.setAttributes(mapOf("plan" to "premium"))
ctx.setAttributes(mapOf("tier" to "gold"))
// currentAttributes() == {"tier": "gold"} — "plan" is gone.
```

Merge yourself if that's what you want: `ctx.setAttributes(old + new)`.

### Persistence

The auto-UUID persists in `SharedPreferences` under the file `com.convert.sdk.visitor` (key `visitor_id`). Sticky bucketing decisions persist per visitor in the SDK's internal store — the same visitor always gets the same variation across launches. Segments persist too (see [Visitor Segmentation](#visitor-segmentation)).

---

## Running Experiences

### Single experience

```kotlin
val variation = ctx.runExperience("homepage-redesign")
when (variation?.key) {
    "control" -> renderControl()
    "treatment" -> renderTreatment()
    null -> renderControl()  // SDK not ready, visitor not bucketed, or experience not declared
}
```

`variation.key` is the merchant-defined variation key from the Convert dashboard; `variation.id` is the numeric id; `variation.bucketingAllocation` (nullable) is the basis-point allocation the visitor hit, populated on fresh bucketing and `null` on a sticky lookup.

`runExperience` is safe to call before the SDK is ready — it just returns `null`. It's also safe to call from any thread (the implementation is wait-free for the sticky path).

### All experiences

```kotlin
val variations = ctx.runExperiences()
variations.forEach { applyVariation(it) }
```

Returns only the experiences this visitor is bucketed into; filters out experiences where the visitor failed audience/location rules or wasn't bucketed.

### Per-call tracking control

Every `runExperience` call emits a `bucketing` tracking event so the dashboard can report visitor counts. Suppress the event for a specific call by passing `enableTracking = false`:

```kotlin
// Consent-denied flow — evaluate but don't report.
val variation = ctx.runExperience("homepage-redesign", enableTracking = false)
```

Bucketing decisions, sticky persistence, audience rules, and internal event fire (`SystemEvents.BUCKETING` observer) **all still work** when `enableTracking = false` — only the outbound network event is skipped.

`runExperiences(enableTracking = false)` applies the flag uniformly to every experience in the batch.

---

## Feature Flags

Feature flags ride the same bucketing machinery as experiences but return a `Feature` with typed variables instead of a `Variation`.

```kotlin
import com.convert.sdk.core.model.FeatureStatus

val feature = ctx.runFeature("checkout-v2")
if (feature?.enabled == true) {
    enableCheckoutV2()
}
// Equivalent to: if (feature?.status == FeatureStatus.ENABLED) { ... }
```

The `enabled` property is a convenience for the `status == ENABLED` check that matches the JS SDK's idiomatic usage.

### Typed variable helpers

When a feature is `ENABLED`, its `variables` map holds `JsonElement` values — typically `JsonPrimitive` for scalars, occasionally `JsonObject` / `JsonArray` for structured variables. For the common scalar case, use the extension helpers in `com.convert.sdk.android.FeatureExtensions`:

```kotlin
import com.convert.sdk.android.getString
import com.convert.sdk.android.getInt
import com.convert.sdk.android.getDouble
import com.convert.sdk.android.getBoolean

val color = feature?.getString("ctaColor") ?: "#0066ff"
val limit = feature?.getInt("maxItems") ?: 20
val price = feature?.getDouble("price") ?: 9.99
val experimental = feature?.getBoolean("experimental") == true
```

Coercion is strict-then-loose: numeric primitives resolve directly; numeric strings fall back through `toXxxOrNull()`. `JsonNull`, arrays, nested objects, and absent keys all return `null`.

### All features

```kotlin
val all = ctx.runFeatures()  // every declared feature, evaluated for this visitor
```

Each element is a `Feature` — `DISABLED` entries have `variables == null` and the accessors return `null`.

---

## Conversion Tracking

```kotlin
// Bare goal (most common case).
ctx.trackConversion("signup-completed")

// With transactional goal data.
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import kotlinx.serialization.json.JsonPrimitive

ctx.trackConversion(
    goalKey = "purchase-completed",
    goalData = listOf(
        GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(49.99)),
        GoalData(key = GoalDataKey.PRODUCTS_COUNT, value = JsonPrimitive(3)),
        GoalData(key = GoalDataKey.TRANSACTION_ID, value = JsonPrimitive("tx-42")),
        GoalData(key = GoalDataKey.CUSTOM_DIMENSION_1, value = JsonPrimitive("vip")),
    ),
)
```

Supported `GoalDataKey` values:

| Key | JSON name | Typical value |
|---|---|---|
| `AMOUNT` | `amount` | number |
| `PRODUCTS_COUNT` | `productsCount` | integer |
| `TRANSACTION_ID` | `transactionId` | string |
| `CUSTOM_DIMENSION_1..5` | `customDimension1..5` | string/number |

`trackConversion` is fire-and-forget — it returns immediately and dispatches enqueue to the SDK scope. An unknown `goalKey` logs a WARN and returns silently.

### Dedup & `forceMultipleTransactions`

By default, calling `trackConversion("purchase")` twice for the same visitor only records the first conversion. To bypass dedup (e.g. a subscription renewal where each payment is a distinct transaction):

```kotlin
ctx.trackConversion(
    goalKey = "purchase-completed",
    goalData = listOf(GoalData(GoalDataKey.AMOUNT, JsonPrimitive(49.99))),
    conversionSetting = mapOf("forceMultipleTransactions" to true),
)
```

On the second call with `forceMultipleTransactions = true`:
- The **bare** conversion enqueue is **skipped** (the first call already recorded the goal hit).
- The **transaction** enqueue (the `goalData` payload) **is** sent.
- The `SystemEvents.CONVERSION` observer fires.

This matches the JS SDK's `ConversionAttributes.conversionSetting.forceMultipleTransactions` semantics.

---

## Visitor Segmentation

Segments are labels attached to the visitor that flow into outbound tracking events and feed audience rules.

### Default segments (string values)

```kotlin
ctx.setDefaultSegments(mapOf(
    "plan" to "premium",
    "country" to "US",
))
```

### Custom segments (any JSON value)

```kotlin
ctx.setCustomSegments(mapOf(
    "lifetimeValue" to 1250.75,
    "isBetaTester" to true,
    "interests" to listOf("running", "cycling"),  // coerced to JsonPrimitive(value.toString())
))
```

### Merge semantics & persistence

The outbound event carries `defaults ∪ customs`, with custom values winning on key collision. Both setters **replace** (not merge) — merge yourself if needed.

Segments persist across launches via the SDK's internal store, so the next session's audience-rule evaluation sees the same segments without needing them re-set.

Passing `emptyMap()` clears the persisted state to an empty map (not `null`) — use this on sign-out.

---

## Offline Behavior

The SDK is designed to never lose tracking events, even in flaky network conditions.

### What happens when the network is down

1. **Config fetch fails** → the last successfully cached config (stored as a JSON file in the app's internal storage) is loaded instead. If there's no cache either, `runExperience` returns `null` until the next successful fetch.
2. **Tracking events generated while offline** → enqueued to a local SQLite database. The queue is durable across app restarts.
3. **When connectivity returns** → a `NetworkObserver` listens for `ACCESS_NETWORK_STATE` transitions and triggers a drain of the queue automatically.
4. **Backoff & retry** → flushes that fail schedule themselves via **WorkManager** with exponential backoff (30s initial, doubling, capped). WorkManager survives app-kill and device reboot, so events enqueued on Monday will still ship on Tuesday after the user next launches the app.

### What you don't need to do

- No manual flush call — the SDK batches & flushes on its own.
- No manual config-refresh — the `dataRefreshInterval` timer handles it.
- No WorkManager registration — the SDK registers itself via AndroidX Startup.

### What's NOT persistent

- Events enqueued for a visitor id **after app uninstall** are obviously lost — `SharedPreferences` and the SQLite queue both live in the app's internal storage.

---

## Tracking Control

Two independent axes control outbound tracking.

### SDK-level toggle (dynamic)

```kotlin
sdk.setTrackingEnabled(false)  // e.g. on consent withdrawal
// ...
sdk.setTrackingEnabled(true)   // e.g. on consent re-grant
val current = sdk.isTrackingEnabled()
```

When `false`, every `enqueueBucketingEvent` / `enqueueConversionEvent` call is a no-op — events **are not** enqueued. This means:

- Events generated **while disabled** are lost (there's no silent buffer).
- Events already sitting on the queue from before the disable **continue flushing** through the normal timer/batch mechanism.
- Bucketing, rule evaluation, sticky persistence, goal dedup — all continue normally. Only the network side is silenced.

Boot-time variant: `ConvertSDK.builder(...).trackingEnabled(false).build()`. Flip it at runtime with `setTrackingEnabled(true)` when consent is granted.

### Per-call override

```kotlin
val variation = ctx.runExperience("homepage-redesign", enableTracking = false)
ctx.runExperiences(enableTracking = false)
```

Per-call wins over the SDK-level toggle: passing `enableTracking = false` suppresses **this** event regardless of the SDK-level state. The SDK-level toggle is an AND gate over the per-call flag (both must be true for the event to ship).

### Consent scenarios

| Scenario | What to do |
|---|---|
| Consent unknown at launch | `.trackingEnabled(false)` in Builder; flip to `true` after the consent dialog resolves. |
| Consent withdrawn mid-session | `sdk.setTrackingEnabled(false)`. In-flight queue drains; new events silent. |
| Consent per-feature | Keep SDK-level `true`, pass `enableTracking = false` on the calls you want silent. |

---

## Logging

The SDK logs via the adapter `AndroidLogger`, which wraps `android.util.Log`. The tag is `ConvertSDK` (plus a finer-grained tag per class).

```kotlin
import com.convert.sdk.core.model.LogLevel

ConvertSDK.builder(context)
    .sdkKey("...")
    .logLevel(LogLevel.DEBUG)   // ERROR | WARN | INFO | DEBUG
    .build()
```

Capture logs in development with:

```bash
adb logcat -s ConvertSDK:V
```

In CI / instrumentation tests, use Robolectric's `ShadowLog.getLogs()` or an in-memory `Logger` implementation if you need to assert on messages.

---

## Google Play Data Safety

The Convert Android SDK collects **no personally identifiable information**. The only data it generates locally is an app-scoped UUID used for experiment bucketing (lost on app uninstall). Events sent to Convert contain: visitor ID (UUID), segments you set, and experiment/goal identifiers. No device identifiers, no location, no contacts, no user profile data.

### Data Safety form mapping

Use the following answers when completing the Google Play Data Safety form for the portion of data collection attributable to this SDK.

| Question | Answer | Notes |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (if you set segments containing user-derived data). Otherwise **No**. | The SDK itself collects only the app-scoped UUID and what you choose to pass via `setAttributes` / `setDefaultSegments` / `setCustomSegments`. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | HTTPS-only to the Convert endpoints. |
| Do you provide a way for users to request that their data be deleted? | **User-controlled via app uninstall** | The UUID and local queue are discarded on uninstall. Server-side deletion is handled via the Convert dashboard. |

#### Per-data-type declarations (SDK-generated data only)

| Data type | Collected | Shared | Purposes | Optional / Required |
|---|---|---|---|---|
| **App activity: In-app actions** (bucketing + conversion events) | Yes | Yes (sent to Convert) | Analytics, App functionality, Product personalization | Required for A/B testing to function |
| **Device or other IDs** | **No** | **No** | — | The SDK does not collect Android ID, Advertising ID, IMEI, or any hardware identifier. The `visitorId` is an app-scoped UUID v4 generated locally. |
| **Personal info** | No | No | — | None. |
| **Location** | No | No | — | None collected by the SDK. (Audience location rules use inputs **you** provide via `setLocationProperties`.) |
| **Financial info** | No | No | — | None. Transaction data you pass via `goalData` (`AMOUNT`, `TRANSACTION_ID`) is aggregate analytics, not per-user financial info. |
| **Contacts / Messages / Photos & videos / Audio / Files & docs / Calendar / Health / Web browsing / Device/other IDs** | No | No | — | None. |

If your app passes user-derived attributes (e.g. `plan`, `country`, `accountAgeDays`) into `setAttributes` or `setCustomSegments`, those flow through to Convert — declare them separately according to your own privacy posture.

---

## Troubleshooting

### "Events aren't appearing in Convert Live Logs"

Check in order:
1. `sdk.isTrackingEnabled()` — is it `true`? Builder default is `true` unless you called `.trackingEnabled(false)`.
2. Are your `runExperience` / `trackConversion` calls using `enableTracking = true`? Per-call `false` silently skips the network event.
3. Is the SDK ready? Wrap calls in `sdk.onReady { ... }` — pre-ready calls return `null` / skip silently.
4. Is your SDK key correct and pointed at the right environment? Check `logLevel(LogLevel.DEBUG)` output for `config.response` details.
5. Is the device online? Events enqueue offline and ship when connectivity resumes — the unique WorkManager work name is `convert-event-flush`. Inspect the pending job via `adb shell dumpsys jobscheduler | grep convert-event-flush` or via Android Studio's **App Inspection → Background Task Inspector**.

### "`runExperience` always returns `null`"

- Is the experience key correct? Check the Convert dashboard for the exact `key` (case-sensitive).
- Is the experience status `RUNNING`?
- Does the visitor meet the audience rules? `logLevel(LogLevel.DEBUG)` logs the gate decision.
- Is the sum of variation allocations < 100%? Visitors not bucketed into any allocation-slot return `null`.

### "Visitor ID changes between launches"

- Don't pass a new explicit id each time. Call `sdk.createContext()` (no arg) for the auto-persisted UUID, or reuse the same explicit id.
- Don't clear the app's storage between runs in your debug flow — that deletes `com.convert.sdk.visitor`. If you need to reset for QA, call the clear yourself and then regenerate.

### "Why does `trackConversion` do nothing on the second call?"

Goal dedup — the same goal can only fire once per visitor by default. Pass `conversionSetting = mapOf("forceMultipleTransactions" to true)` if you want repeat transactions (renewals, multi-purchases) to each record.

### "I'm getting a class-stripped warning from R8"

Open a GitHub issue with the obfuscation report. The SDK's `consumer-rules.pro` is supposed to prevent this — we need to patch it.

---

## Java Interop Notes

- `ConvertSDK.builder(context)` — static factory, callable from Java as `ConvertSDK.builder(context)`.
- `sdk.onReady(runnable)` — takes a `Runnable` directly, so Java lambdas work verbatim: `sdk.onReady(() -> doStuff());`.
- `runExperience(key)` / `runExperience(key, enableTracking)` — `@JvmOverloads` generates the arity-1 method so Java callers don't need to specify the default.
- `runExperiences()` / `runExperiences(enableTracking)` — same pattern.
- `trackConversion(key)` / `trackConversion(key, goalData)` / `trackConversion(key, goalData, conversionSetting)` — `@JvmOverloads` generates all three arities.
- `FeatureExtensions.getString(feature, "key")` — use the `FeatureExtensions` static facade (from `@file:JvmName`). Alternatively, read `feature.getVariables().get("key")` and cast the `JsonPrimitive` yourself.
- `EventCallback` is a Kotlin `fun interface` — Java lambdas SAM-convert to it automatically: `sdk.on("bucketing", data -> handle(data));`.

---

## Next steps

- Read the [README](../README.md) for the 2-minute quickstart.
- Browse the generated API reference (published as the Javadoc JAR with every Maven Central release).
- File integration issues at the [android-sdk GitHub tracker](https://github.com/convertcom/android-sdk/issues).
