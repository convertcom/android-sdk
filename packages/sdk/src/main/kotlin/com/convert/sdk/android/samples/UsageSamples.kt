/*
 * Convert Android SDK — sdk/samples
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
@file:Suppress("UnusedPrivateMember", "unused")

package com.convert.sdk.android.samples

import android.content.Context
import com.convert.sdk.android.ConvertSDK

/**
 * Runnable snippets referenced from the public API via `@sample`.
 *
 * These are real, compilable Kotlin — Dokka renders them into the IDE's
 * KDoc tooltip next to the member they document, and the compiler
 * ensures they stay in sync with the public API. The file lives under
 * `src/main/kotlin/.../samples/` because Dokka does not compile test
 * sources; the samples package is implicitly internal (no `public`
 * declarations), so nothing here leaks into the published API.
 */

/**
 * Bootstraps the SDK and runs an experience once it is ready.
 *
 * Referenced from `ConvertSDK.onReady` and
 * `ConvertContext.runExperience` KDoc via
 * `@sample com.convert.sdk.android.samples.basicUsageSample`.
 */
internal fun basicUsageSample(context: Context) {
    val sdk = ConvertSDK.builder(context)
        .sdkKey("my-sdk-key")
        .build()

    sdk.onReady {
        val ctx = sdk.createContext("visitor-123")
        val variation = ctx.runExperience("homepage-redesign")
        if (variation != null) {
            // Render `variation.key` against your UI.
        }
        ctx.trackConversion("signup-completed")
    }
}

/**
 * Tracks a conversion with optional goal data.
 *
 * Referenced from `ConvertContext.trackConversion` KDoc via
 * `@sample com.convert.sdk.android.samples.trackConversionSample`.
 */
internal fun trackConversionSample(sdk: ConvertSDK) {
    sdk.onReady {
        val ctx = sdk.createContext("visitor-123")
        // Minimal form: goal key only.
        ctx.trackConversion("checkout-completed")
        // With additional goal data (amount, transactionId, etc.)
        // — use `trackConversion(goalKey, goalData)` overload.
    }
}
