/*
 * Convert Android SDK Demo App — DemoApplication
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo

import android.app.Application
import com.convert.sdk.android.ConvertSDK
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.demo.viewmodel.EventSubscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

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

    override fun onCreate() {
        super.onCreate()
        // Trigger background SDK construction; do not await — the main
        // thread must return promptly. start() returns synchronously
        // and only enqueues the async block on Dispatchers.Default.
        sdkDeferred.start()
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
}
