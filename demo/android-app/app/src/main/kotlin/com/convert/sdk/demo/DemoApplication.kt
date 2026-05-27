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
import com.convert.sdk.demo.viewmodel.ConversionTracker
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.ExperienceRunner
import com.convert.sdk.demo.viewmodel.FeatureRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

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
     * Kept `internal` for the same reason as [sdkDeferred] — future
     * runners (features, conversions) can compose against it directly.
     */
    internal val contextDeferred: Deferred<ConvertContext> by lazy {
        applicationScope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            sdkDeferred.await().createContext()
        }
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
     */
    private fun buildSdk(): ConvertSDK = ConvertSDK.builder(this)
        .sdkKey(BuildConfig.convertSdkKey)
        .logLevel(LogLevel.DEBUG)
        .build()

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
     * config is not ready". The runner therefore does an O(1)
     * `isCompleted` check against [contextDeferred] and returns
     * null/empty when the context has not landed yet — never blocks,
     * never re-creates the context, never touches the SDK on the main
     * thread.
     */
    fun experienceRunner(): ExperienceRunner = object : ExperienceRunner {
        override fun runExperience(experienceKey: String): Variation? =
            if (contextDeferred.isCompleted) {
                contextDeferred.getCompleted().runExperience(experienceKey)
            } else {
                null
            }

        override fun runExperiences(): List<Variation> =
            if (contextDeferred.isCompleted) {
                contextDeferred.getCompleted().runExperiences()
            } else {
                emptyList()
            }
    }

    /**
     * Story 7.4 — builds a [FeatureRunner] that delegates to the
     * pre-warmed per-visitor [ConvertContext] from [contextDeferred].
     *
     * Same off-main-thread + null-on-not-ready discipline as
     * [experienceRunner]: an O(1) `isCompleted` check against
     * [contextDeferred] guards the SDK access; when the context has
     * not landed yet, [runFeature] returns `null` and [runFeatures]
     * returns an empty list — exactly matching the [FeatureRunner]
     * contract ("when the feature is unknown or the SDK is not
     * ready" / "when no features are configured or the config is not
     * loaded").
     *
     * Sharing [contextDeferred] across both runners is intentional:
     * `ConvertSDK.createContext()` reads the auto-persisted visitor id
     * once per process, so re-creating it would be wasteful. The two
     * runners observe the same sticky bucketing.
     */
    fun featureRunner(): FeatureRunner = object : FeatureRunner {
        override fun runFeature(featureKey: String): Feature? =
            if (contextDeferred.isCompleted) {
                contextDeferred.getCompleted().runFeature(featureKey)
            } else {
                null
            }

        override fun runFeatures(): List<Feature> =
            if (contextDeferred.isCompleted) {
                contextDeferred.getCompleted().runFeatures()
            } else {
                emptyList()
            }
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
     */
    fun conversionTracker(): ConversionTracker = object : ConversionTracker {
        override fun trackConversion(goalKey: String, goalData: List<GoalData>) {
            applicationScope.launch {
                contextDeferred.await().trackConversion(goalKey = goalKey, goalData = goalData)
            }
        }

        // Synchronous best-effort, mirroring the experience / feature
        // runners' O(1) isCompleted guard: when the context has not landed
        // yet the goal cannot be confirmed, so report false (the screen
        // then surfaces the unknown-goal card rather than a false-positive
        // success). Once the context is ready the call delegates straight
        // to ConvertContext.hasGoal with no main-thread block.
        override fun hasGoal(goalKey: String): Boolean =
            if (contextDeferred.isCompleted) {
                contextDeferred.getCompleted().hasGoal(goalKey)
            } else {
                false
            }
    }
}
