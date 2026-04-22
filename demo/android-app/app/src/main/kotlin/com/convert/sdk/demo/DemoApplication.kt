/*
 * Convert Android SDK Demo App — DemoApplication
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo

import android.app.Application
import com.convert.sdk.android.ConvertSDK
import com.convert.sdk.android.EventCallback
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.demo.viewmodel.EventSubscriber

/**
 * Story 7.1 AC-3 — Application subclass that initialises the
 * [ConvertSDK] singleton at process start.
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
     * The SDK instance, assembled in [onCreate]. Exposed so the
     * [com.convert.sdk.demo.MainActivity] can build an [EventSubscriber]
     * adapter for [com.convert.sdk.demo.viewmodel.SdkViewModel].
     */
    lateinit var sdk: ConvertSDK
        private set

    override fun onCreate() {
        super.onCreate()
        sdk = ConvertSDK.builder(this)
            .sdkKey(BuildConfig.convertSdkKey)
            .logLevel(LogLevel.DEBUG)
            .build()
    }

    /**
     * Builds an [EventSubscriber] that bridges the demo ViewModel's
     * in-memory `subscribe(event, callback)` contract to the SDK's
     * public `on(event, EventCallback)` surface.
     *
     * The returned subscriber captures a reference to [sdk] — it must
     * only be called after [onCreate] has run.
     *
     * The `@Suppress("ConvertSdkInitializedBeforeUse")` is required
     * because the Epic 6.3 custom lint rule performs local flow
     * analysis only: it can see that `sdk` is a `lateinit` field here
     * but cannot prove that `onCreate` (which builds it) has already
     * been called by the time `eventSubscriber()` is invoked. The
     * invariant IS held — `MainActivity.onCreate` is the sole caller
     * and always runs after `Application.onCreate` — but the detector
     * can't trace that cross-class ordering. This is exactly the
     * "DI-container-lookup" suppression pattern the lint rule's own
     * explanation endorses for legitimate async/lazy SDK wiring.
     */
    @Suppress("ConvertSdkInitializedBeforeUse")
    fun eventSubscriber(): EventSubscriber = EventSubscriber { event, callback ->
        val token = sdk.on(event, EventCallback { data -> callback(data) })
        AutoCloseable { sdk.off(event, token) }
    }
}
