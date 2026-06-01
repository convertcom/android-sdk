/*
 * Convert Android SDK — sdk/lifecycle
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.lifecycle

import android.content.Context
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.Initializer

/**
 * App Startup [Initializer] that guarantees
 * [androidx.lifecycle.ProcessLifecycleOwner] is initialised before any
 * SDK code runs.
 *
 * ### Role (Story 5.3 AC-8, F-127)
 *
 * Consumers bootstrap the SDK explicitly in their `Application.onCreate`
 * by calling `ConvertSDK.builder(context).build()`. That chain eventually
 * reaches into `ProcessLifecycleOwner.get()` — owned by
 * [ProcessLifecycleInitializer], which the `androidx.lifecycle-process`
 * artifact registers with App Startup as a hard dependency.
 *
 * **WorkManager note (F-127):** As of WorkManager 2.6+,
 * `WorkManagerInitializer` is NOT the correct App Startup dependency for
 * the `work-runtime` artifact. WorkManager 2.6+ self-initializes via its
 * own `WorkManagerInitializationStartup` (or directly, depending on the
 * build variant); referencing the removed `WorkManagerInitializer` class
 * causes a compile error or initialization failure at runtime. Verified
 * against work-runtime 2.11.1 release notes: https://developer.android.com/jetpack/androidx/releases/work
 *
 * `ProcessLifecycleInitializer` (from `androidx.lifecycle:lifecycle-process`)
 * is the only required dependency — it guarantees `ProcessLifecycleOwner`
 * is ready. [Source: AndroidX App Startup —
 * https://developer.android.com/topic/libraries/app-startup]
 *
 * ### Why `create()` is deliberately empty
 *
 * The SDK is NOT auto-instantiated here. Consumers always call
 * `ConvertSDK.builder(context).build()` themselves — otherwise there is
 * no `sdkKey` / `sdkKeySecret` to work with. The initializer only exists
 * to express dependency order.
 *
 * ### Manifest wiring
 *
 * `packages/sdk/src/main/AndroidManifest.xml` declares the App Startup
 * provider with a `<meta-data>` entry for this class. See Gotcha 1 in
 * the Story 5.3 Dev Notes: without the manifest entry, App Startup
 * never finds the initializer and its dependency declaration has no
 * effect.
 */
internal class SdkInitializer : Initializer<Unit> {

    /**
     * Deliberately a no-op. See the class-level KDoc for why the SDK
     * does not self-construct inside the initializer.
     */
    override fun create(context: Context) {
        // Intentional no-op — consumers call ConvertSDK.builder(context).build()
        // in Application.onCreate. The initializer's sole purpose is to
        // make dependencies() take effect under App Startup.
    }

    /**
     * Declares a hard dependency on [ProcessLifecycleInitializer] so
     * App Startup finishes it before any ConvertSDK consumer code runs.
     *
     * `WorkManagerInitializer` is intentionally excluded (F-127): as of
     * WorkManager 2.6+ the class no longer exists as a public App Startup
     * initializer. WorkManager self-initializes in `work-runtime` 2.11.1
     * without needing a consumer-side dependency declaration.
     */
    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(
        ProcessLifecycleInitializer::class.java,
    )
}
