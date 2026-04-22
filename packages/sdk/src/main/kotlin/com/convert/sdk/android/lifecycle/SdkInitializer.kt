/*
 * Convert Android SDK — sdk/lifecycle
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.lifecycle

import android.content.Context
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer

/**
 * App Startup [Initializer] that guarantees
 * [androidx.lifecycle.ProcessLifecycleOwner] and [androidx.work.WorkManager]
 * are initialised before any SDK code runs.
 *
 * ### Role (Story 5.3 AC-8)
 *
 * Consumers bootstrap the SDK explicitly in their `Application.onCreate`
 * by calling `ConvertSDK.builder(context).build()`. That chain eventually
 * reaches into:
 *
 *  - `ProcessLifecycleOwner.get()` — owned by
 *    [ProcessLifecycleInitializer], which the `androidx.lifecycle-process`
 *    artifact registers with App Startup as a hard dependency.
 *  - `WorkManager.getInstance(context)` — owned by
 *    [WorkManagerInitializer], which the `androidx.work-runtime` artifact
 *    registers with App Startup.
 *
 * Both services normally initialise on their own via App Startup's
 * `InitializationProvider`. The SDK declares this [SdkInitializer] —
 * itself a no-op `create()` — solely so App Startup's dependency-resolver
 * sees a consumer that REQUIRES both to be ready. That prevents a race
 * where an earlier consumer's initialiser reaches `WorkManager.getInstance`
 * before the work-runtime initializer has finished.
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
     * Declares hard dependencies on the `androidx.lifecycle.process` and
     * `androidx.work.runtime` initializers so App Startup finishes both
     * before any ConvertSDK consumer code runs.
     */
    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(
        ProcessLifecycleInitializer::class.java,
        WorkManagerInitializer::class.java,
    )
}
