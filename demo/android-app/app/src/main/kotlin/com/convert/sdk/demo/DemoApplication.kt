/*
 * Convert Android SDK Demo App — DemoApplication
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo

import android.app.Application
import com.convert.sdk.android.ConvertContext
import com.convert.sdk.android.ConvertSDK
import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.model.Variation
import com.convert.sdk.demo.viewmodel.ConfigSnapshot
import com.convert.sdk.demo.viewmodel.ConfigSnapshotProvider
import com.convert.sdk.demo.viewmodel.ConversionTracker
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.ExperienceRunner
import com.convert.sdk.demo.viewmodel.FeatureRunner
import com.convert.sdk.demo.viewmodel.PreviewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Story 7.1 AC-3 (F-166) — Application subclass that initialises the
 * [ConvertSDK] singleton on a background dispatcher at process start.
 *
 * ### Background-dispatcher init (architecture NFR)
 *
 * Architecture §4 NFR-12 (`no main-thread blocking`) and §11
 * (`Never use Dispatchers.Main inside the SDK`) are contractually
 * stronger than this story's earlier "synchronous init in onCreate"
 * draft: ConvertSDK.builder(...)…build() pulls in disk I/O (cache),
 * code-generation (the OpenAPI client), and reflection — all of which
 * can stretch into the hundreds of milliseconds on cold start. The
 * 2026-05-05 demo-app logs ("Choreographer: Skipped 174 frames!" and
 * a 4002 ms Davey on cold start) confirmed the symptom in production.
 *
 * The fix moves the build chain into a [Deferred] launched on
 * [Dispatchers.Default] — `onCreate()` returns immediately after
 * starting the deferred; consumers (event subscribers, runners) await
 * it as needed. The SDK is therefore visible to consumers as a
 * suspending value, not a synchronously-available field.
 *
 * The same off-main-thread discipline extends to the per-visitor
 * [ConvertContext]: `sdk.createContext()` reads/writes the visitor-id
 * file, so it is also pre-warmed inside [contextDeferred] on
 * [Dispatchers.Default] (Story 7.3 propagation of F-166). Synchronous
 * runner calls (`runExperience` etc.) read from the deferred when it
 * is already complete and return `null`/empty otherwise — matching
 * the [ExperienceRunner] docstring.
 *
 * The SDK key is compiled into [BuildConfig.convertSdkKey] — see
 * `app/build.gradle.kts` for how `local.properties`'s `convertSdkKey`
 * entry flows into the build. When no entry is present the default
 * `"demo-sdk-key"` is used; the SDK initialises, the first config
 * fetch fails quietly, and the rest of the demo still launches
 * cleanly (plenty for scaffolding / AC-10).
 *
 * Story 7.7 — the deployment environment is ALSO sourced from
 * `local.properties` via [BuildConfig.convertEnvironment]. When the
 * value is blank (the default — fallback literal is `""`), the
 * `ConvertSDK.Builder.environment(...)` call is skipped entirely and
 * the Config screen's Environment row renders `(not set)`. When
 * populated (e.g. `convertEnvironment=staging`), the value is passed
 * through to the builder and surfaced in the Config snapshot.
 */
class DemoApplication : Application() {

    /**
     * Application-wide scope. Backed by [SupervisorJob] +
     * [Dispatchers.Default] (see [newApplicationScope]) so child
     * failures do not propagate up and so SDK init never runs on the
     * main thread.
     */
    private val applicationScope: CoroutineScope = newApplicationScope()

    /**
     * Lazily-built [ConvertSDK] singleton. The build() chain executes
     * on [Dispatchers.Default] inside an async() coroutine —
     * never on the main thread. [onCreate] starts the deferred eagerly
     * but does not await; consumers await on demand.
     *
     * Exposed `internal` so a future story (Event Inspector / runners)
     * can compose against the deferred directly without touching the
     * private field.
     */
    internal val sdkDeferred: Deferred<ConvertSDK> by lazy {
        applicationScope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            buildSdk()
        }
    }

    /**
     * Pre-warmed per-visitor [ConvertContext]. Awaits [sdkDeferred] then
     * calls `sdk.createContext()` on [Dispatchers.Default] — the
     * createContext call itself touches disk for visitor-id
     * persistence and so must not run on the main thread either.
     *
     * After construction the context is seeded with:
     *  - **Visitor attributes** from [BuildConfig.convertVisitorAttributes]
     *    (a JSON object string, e.g. `{"mobile":true}`). The staging
     *    project's "adv-audience" rule requires `mobile=true` OR
     *    `(desktop=true AND browser!="CH")` — setting `mobile=true` here
     *    satisfies the mobile branch, which is correct for any Android target.
     *  - **Location properties** from [BuildConfig.convertLocationProperties]
     *    (a JSON object string, e.g. `{"location":"pricing"}`). The staging
     *    project's "pricing-location" rule requires `location=pricing` —
     *    the default value satisfies that gate. Both values are tunable
     *    via `local.properties` without rebuilding the source.
     *
     * Empty JSON objects (`{}`) are treated as "no attributes / no location
     * properties" so the test build (which reads `test.properties` pins of
     * `{}` for both fields) never injects staging-specific state into the
     * fake-runner unit-test path.
     *
     * Kept `internal` for the same reason as [sdkDeferred] — future
     * runners (features, conversions) can compose against it directly.
     */
    internal val contextDeferred: Deferred<ConvertContext> by lazy {
        applicationScope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            buildContext()
        }
    }

    /**
     * qs-08 (experiment-preview) demo testbed — the dedicated preview
     * [ConvertContext] installed by [previewController]'s `setPreview`,
     * or `null` when no preview is active.
     *
     * `@Volatile` because [previewController]'s `setPreview` writes it
     * from a coroutine on [Dispatchers.Default] while [activeContext]
     * (and therefore every runner) reads it synchronously — possibly
     * from a different thread — without any lock.
     *
     * A dedicated context (rather than mutating [contextDeferred]'s
     * base context) is what makes preview isolation (qs-08 AC7) AND a
     * working Clear possible: [com.convert.sdk.android.ConvertContext.setPreview]
     * has no SDK-level "unset" — the only way to leave preview mode
     * cleanly is to stop routing through the previewed context and go
     * back to the base one.
     */
    @Volatile
    private var previewContextOverride: ConvertContext? = null

    /**
     * qs-08 demo testbed — the [ConvertContext] every runner factory
     * below should read from: the active preview override when one is
     * set, otherwise the pre-warmed base context from [contextDeferred]
     * (or `null` if that has not landed yet — the existing
     * "SDK not ready" fallback every runner already implements).
     */
    private fun activeContext(): ConvertContext? =
        previewContextOverride
            ?: if (contextDeferred.isCompleted) contextDeferred.getCompleted() else null

    /**
     * Synchronous part of per-visitor [ConvertContext] construction —
     * creates the context and seeds it with [BuildConfig.convertVisitorAttributes]
     * / [BuildConfig.convertLocationProperties], exactly as [contextDeferred]
     * always has. Extracted so [previewController]'s `setPreview` can
     * build a SEPARATE, identically-seeded context without duplicating
     * the seeding logic (qs-08 demo testbed).
     */
    private suspend fun buildContext(): ConvertContext {
        val ctx = sdkDeferred.await().createContext()
        val attrs = parseJsonObjectToMap(BuildConfig.convertVisitorAttributes)
        if (attrs.isNotEmpty()) ctx.setAttributes(attrs)
        val locationProps = parseJsonObjectToMap(BuildConfig.convertLocationProperties)
        if (locationProps.isNotEmpty()) ctx.setLocationProperties(locationProps)
        return ctx
    }

    override fun onCreate() {
        super.onCreate()
        // Trigger background SDK + context construction; do not await
        // — the main thread must return promptly. start() returns
        // synchronously and only enqueues the async block on
        // Dispatchers.Default.
        sdkDeferred.start()
        contextDeferred.start()
    }

    /**
     * Synchronous part of SDK construction — runs inside the
     * [sdkDeferred] coroutine on [Dispatchers.Default]. Extracted so
     * the dispatcher hop happens at exactly one point and the build
     * chain stays readable.
     *
     * Story 7.7 — assembles the builder via a step-by-step `var` so
     * `.environment(...)` is applied only when the `local.properties`
     * override is non-blank. The empty-string fallback for
     * [BuildConfig.convertEnvironment] signals "not configured" and
     * MUST NOT be forwarded to the builder (doing so would set a
     * real but invalid environment tag on the SDK).
     */
    private fun buildSdk(): ConvertSDK {
        var builder = ConvertSDK.builder(this)
            .sdkKey(BuildConfig.convertSdkKey)
            .logLevel(LogLevel.DEBUG)
        val environment = BuildConfig.convertEnvironment
        if (environment.isNotBlank()) {
            builder = builder.environment(environment)
        }
        // qs-08 (experiment-preview) — QA config transport. Blank (the
        // fallback literal) skips the call entirely so the demo's normal
        // config-fetch/cache behavior is unchanged when no token is
        // configured; a non-blank `local.properties` override widens
        // config visibility (draft/paused statuses) for the whole SDK
        // instance, independent of the per-context `setPreview` surface.
        val debugToken = BuildConfig.convertDebugToken
        if (debugToken.isNotBlank()) {
            builder = builder.debugToken(debugToken)
        }
        return builder.build()
    }

    /**
     * Builds an [EventSubscriber] that bridges the demo ViewModel's
     * `subscribe(event, callback)` contract to the SDK's
     * `on(event, EventCallback)` surface — without requiring the
     * consumer to wait for SDK init.
     *
     * Implementation lives in [buildEventSubscriber]: each
     * `subscribe()` launches a coroutine on [applicationScope] that
     * awaits [sdkDeferred] and then registers via [SdkEventSource].
     * If the consumer closes the returned token before the deferred
     * completes, the post-completion coroutine detects the race via a
     * CAS sentinel and disposes the subscription immediately.
     */
    fun eventSubscriber(): EventSubscriber {
        val sourceDeferred: Deferred<SdkEventSource> =
            applicationScope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                sdkDeferred.await().asSdkEventSource()
            }
        return buildEventSubscriber(applicationScope, sourceDeferred)
    }

    /**
     * Builds an [ExperienceRunner] that delegates to the pre-warmed
     * per-visitor [ConvertContext] from [contextDeferred].
     *
     * Synchronous-by-contract: the [ExperienceRunner] docstring says
     * `runExperience` returns `null` "when the visitor is not
     * bucketed, the experience is unknown, **or the SDK is not
     * ready**" and `runExperiences` returns an empty list "when the
     * config is not ready". The runner therefore reads [activeContext]
     * (an O(1) check) and returns null/empty when no context has
     * landed yet — never blocks, never re-creates the context, never
     * touches the SDK on the main thread.
     *
     * qs-08 demo testbed — routes through [activeContext] rather than
     * [contextDeferred] directly, so a running preview override (see
     * [previewController]) transparently forces the previewed
     * experience's variation for the SAME primary/secondary buttons the
     * Experiences screen already renders.
     */
    fun experienceRunner(): ExperienceRunner = object : ExperienceRunner {
        override fun runExperience(experienceKey: String): Variation? =
            activeContext()?.runExperience(experienceKey)

        override fun runExperiences(): List<Variation> =
            activeContext()?.runExperiences() ?: emptyList()
    }

    /**
     * Story 7.4 — builds a [FeatureRunner] that delegates to the
     * pre-warmed per-visitor [ConvertContext] from [contextDeferred].
     *
     * Same off-main-thread + null-on-not-ready discipline as
     * [experienceRunner]: reads [activeContext] (an O(1) check); when
     * no context has landed yet, [runFeature] returns `null` and
     * [runFeatures] returns an empty list — exactly matching the
     * [FeatureRunner] contract ("when the feature is unknown or the SDK
     * is not ready" / "when no features are configured or the config is
     * not loaded").
     *
     * qs-08 demo testbed — [activeContext] also routes a running
     * preview override here, same as [experienceRunner].
     */
    fun featureRunner(): FeatureRunner = object : FeatureRunner {
        override fun runFeature(featureKey: String): Feature? =
            activeContext()?.runFeature(featureKey)

        override fun runFeatures(): List<Feature> =
            activeContext()?.runFeatures() ?: emptyList()
    }

    /**
     * Story 7.5 — builds a [ConversionTracker] that delegates to the
     * pre-warmed per-visitor [ConvertContext] from [contextDeferred].
     *
     * Unlike the experience / feature runners, [trackConversion]
     * returns [Unit] — there is no "null when not ready" escape hatch
     * in the contract. Dropping a tap because the SDK has not landed
     * yet would be silently lossy, so the tracker fires the call
     * inside `applicationScope.launch { contextDeferred.await(); … }`:
     * the launch returns immediately (no main-thread block), the
     * coroutine waits for the context, then issues the
     * [ConvertContext.trackConversion] call on [Dispatchers.Default].
     * Calls dispatched before the context is ready are buffered in
     * the coroutine queue and replayed in order once it lands.
     *
     * Per-visitor dedup (Story 4.3 AC-6) lives inside the SDK and is
     * unaffected — both runners and this tracker observe the same
     * sticky [ConvertContext] via [contextDeferred].
     *
     * qs-08 demo testbed — [trackConversion] resolves the target context
     * as [previewContextOverride] (already fully built by the time it is
     * installed — see [previewController]) when a preview is active, else
     * awaits [contextDeferred] as before. This is the ONLY runner method
     * that cannot use the plain [activeContext] O(1) read, because the
     * non-preview path must still buffer-and-await rather than drop the
     * call when the base context has not landed yet.
     */
    fun conversionTracker(): ConversionTracker = object : ConversionTracker {
        override fun trackConversion(goalKey: String, goalData: List<GoalData>) {
            val previewCtx = previewContextOverride
            applicationScope.launch {
                val ctx = previewCtx ?: contextDeferred.await()
                ctx.trackConversion(goalKey = goalKey, goalData = goalData)
            }
        }

        // Synchronous best-effort, mirroring the experience / feature
        // runners' activeContext() guard: when no context has landed
        // yet the goal cannot be confirmed, so report false (the screen
        // then surfaces the unknown-goal card rather than a false-positive
        // success). Once a context is ready the call delegates straight
        // to ConvertContext.hasGoal with no main-thread block.
        override fun hasGoal(goalKey: String): Boolean =
            activeContext()?.hasGoal(goalKey) ?: false
    }

    /**
     * Story 7.6 AC-5 — builds a [ConfigSnapshotProvider] that reads
     * the SDK's current state through its **public** API surface.
     *
     * The demo cannot read `sdk.dataManager` directly (the property is
     * `internal` to the SDK module) but it can infer the two lists the
     * panel requires by asking the per-visitor [ConvertContext] for
     * its eligible experience and feature sets — `runExperiences()` /
     * `runFeatures()`, the same calls the other screens drive. Each
     * `Variation` carries its `experienceKey`; each `Feature` carries
     * its `key`.
     *
     * Honest naming: "Active" in the panel means "eligible for the
     * current visitor". A visitor outside an experience's audience
     * will see that experience omitted from the list — which is the
     * correct signal for a developer debugging audience rules.
     *
     * `trackingEnabled` comes from the SDK's public
     * [com.convert.sdk.android.ConvertSDK.isTrackingEnabled] accessor.
     * The `null` branch (API manager not yet wired, or the SDK
     * deferred has not landed yet) renders as `"—"` in
     * [ConfigInfoPanel].
     *
     * Synchronous-by-contract: the [ConfigSnapshotProvider] docstring
     * says `snapshot()` is called on the SDK's event-dispatch thread
     * and must not block. The implementation therefore reads
     * [sdkDeferred] only when already complete and [activeContext] (an
     * O(1) check, same as every runner above); missing values fall back
     * to empty lists / `null`, matching the "cannot produce a meaningful
     * snapshot" path the contract anticipates for early calls before the
     * first config fetch lands.
     */
    fun configSnapshotProvider(): ConfigSnapshotProvider = ConfigSnapshotProvider {
        val sdk = if (sdkDeferred.isCompleted) sdkDeferred.getCompleted() else null
        val context = activeContext()
        val experiences = context?.let { runCatching { it.runExperiences() }.getOrDefault(emptyList()) } ?: emptyList()
        val features = context?.let { runCatching { it.runFeatures() }.getOrDefault(emptyList()) } ?: emptyList()
        val tracking = sdk?.let { runCatching { it.isTrackingEnabled() }.getOrNull() }
        ConfigSnapshot(
            sdkKey = BuildConfig.convertSdkKey,
            environment = BuildConfig.convertEnvironment.takeIf { it.isNotBlank() },
            experienceKeys = experiences.mapNotNull { it.experienceKey },
            featureKeys = features.mapNotNull { it.key },
            trackingEnabled = tracking,
        )
    }

    /**
     * qs-08 (experiment-preview) demo testbed — builds a [PreviewController]
     * backed by a DEDICATED preview [ConvertContext], separate from the
     * pre-warmed base context in [contextDeferred].
     *
     * [PreviewController.setPreview] never touches the main thread: it
     * launches on [applicationScope] (already [Dispatchers.Default] by
     * construction — see [newApplicationScope]), builds a fresh context
     * via [buildContext] (identical attribute/location seeding to the
     * base context), applies [ConvertContext.setPreview], and ONLY THEN
     * installs it into [previewContextOverride] — so a half-built
     * context is never visible to [activeContext] or any runner.
     *
     * [PreviewController.clearPreview] simply drops the override; every
     * runner's [activeContext] read falls back to the base context on
     * its very next call — there is no SDK-level "unset" to await.
     */
    fun previewController(): PreviewController = object : PreviewController {
        override fun setPreview(experienceId: String, variationId: String) {
            applicationScope.launch {
                val ctx = buildContext()
                ctx.setPreview(experienceId, variationId)
                previewContextOverride = ctx
            }
        }

        override fun clearPreview() {
            previewContextOverride = null
        }

        override fun isPreviewActive(): Boolean = previewContextOverride != null
    }
}

/**
 * Parses a JSON object string (e.g. `{"mobile":true,"browser":"CH"}`) into
 * a `Map<String, Any?>` suitable for [com.convert.sdk.android.ConvertContext.setAttributes]
 * and [com.convert.sdk.android.ConvertContext.setLocationProperties].
 *
 * Coercion rules for JSON primitive values:
 *  - Boolean JSON values → Kotlin [Boolean]
 *  - Numeric JSON values that are exact integers → Kotlin [Int]
 *  - Other numeric JSON values → Kotlin [Double]
 *  - String JSON values → Kotlin [String]
 *
 * An empty JSON object (`{}`) or a blank string returns [emptyMap], which
 * the [DemoApplication] contextDeferred treats as "no attributes to set" —
 * this is intentional so the `test.properties` pin of `{}` for both
 * attribute fields is a clean no-op in the unit-test path.
 *
 * Any parse error is swallowed and returns [emptyMap] — the demo must not
 * crash if a developer puts a malformed string in `local.properties`.
 */
private fun parseJsonObjectToMap(jsonString: String): Map<String, Any?> {
    if (jsonString.isBlank()) return emptyMap()
    return runCatching {
        val obj = Json.parseToJsonElement(jsonString) as? JsonObject
            ?: return@runCatching emptyMap()
        obj.entries.associate { (key, element) ->
            val primitive = runCatching { element.jsonPrimitive }.getOrNull()
            val value: Any? = when {
                primitive == null -> element.toString()
                primitive.booleanOrNull != null -> primitive.boolean
                primitive.doubleOrNull != null -> {
                    val d = primitive.doubleOrNull!!
                    if (d % 1.0 == 0.0 && !d.isInfinite()) d.toInt() else d
                }
                else -> primitive.content
            }
            key to value
        }
    }.getOrDefault(emptyMap())
}
