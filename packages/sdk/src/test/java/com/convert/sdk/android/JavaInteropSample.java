/*
 * Convert Android SDK — sdk (test source set)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android;

import android.content.Context;

import com.convert.sdk.core.model.Variation;

/**
 * Story 6.1 AC-3 / AC-8 — Java interop smoke test.
 *
 * This file is not a JUnit test class; it compiles as part of the test
 * source set and never runs. If it compiles, the public API's Java
 * interop annotations ({@code @JvmStatic}, {@code @JvmOverloads},
 * {@code fun interface}) are correct — the compiler is the test.
 *
 * The snippet exercises the canonical call sequence from the story:
 * {@code ConvertSDK.builder(context).sdkKey("test").build()},
 * {@code sdk.onReady(() -> ...)}, {@code sdk.createContext("visitor")},
 * {@code ctx.runExperience("exp")}, {@code ctx.trackConversion("goal")}.
 *
 * There are no Kotlin-specific gymnastics: no {@code Unit.INSTANCE}
 * returns, no {@code Function0<Unit>} manual wrappers, no
 * {@code kotlin.jvm.functions.*} imports. If any of those were required,
 * the sample would surface the gap immediately.
 */
@SuppressWarnings({"unused", "MethodMayBeStatic", "UnusedReturnValue"})
public class JavaInteropSample {

    /**
     * Primary interop scenario — builder, onReady, createContext,
     * runExperience, trackConversion.
     *
     * @param context an Android {@link Context}; supplied by the test
     *     harness, never called at runtime.
     */
    public void bootstrapAndRun(Context context) {
        // Builder (static entry point + fluent setter + build).
        ConvertSDK sdk = ConvertSDK.builder(context)
            .sdkKey("test")
            .build();

        // onReady accepts a java.lang.Runnable — no Function0<Unit> wrapper needed.
        sdk.onReady(() -> {
            // createContext: @JvmOverloads lets Java call the one-arg form
            // (no need to pass `attributes = null` — Java has no default args).
            ConvertContext ctx = sdk.createContext("visitor");

            // runExperience: returns a Variation? in Kotlin; Java sees Variation
            // with the usual nullable-from-Kotlin convention. May be null when
            // the visitor is not bucketed into the experience.
            Variation v = ctx.runExperience("exp");
            if (v != null) {
                // v.getKey() / v.getName() — standard Kotlin data-class Java accessors.
                String key = v.getKey();
                String name = v.getName();
                // Suppress unused-warnings — the compiler is the test.
                assert key == null || !key.isEmpty();
                assert name == null || !name.isEmpty();
            }

            // trackConversion: @JvmOverloads lets Java call the goal-key-only form.
            ctx.trackConversion("goal");
        });
    }

    /**
     * Demonstrates that the public API does not require any Kotlin-specific
     * types at the Java call site — asserts the canonical idiom via
     * explicit method references.
     */
    public void apiSurfaceShape() {
        // ConvertSDK.builder is @JvmStatic — reachable as a static method.
        java.util.function.Function<Context, ConvertSDK.Builder> entry = ConvertSDK::builder;
        assert entry != null;

        // onReady accepts a plain Runnable — standard Java functional interface.
        Runnable onReady = () -> { /* no-op */ };
        assert onReady != null;
    }
}
