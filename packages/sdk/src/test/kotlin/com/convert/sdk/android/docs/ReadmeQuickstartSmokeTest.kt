/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
@file:Suppress("unused", "UnusedPrivateMember", "UNUSED_VARIABLE")

package com.convert.sdk.android.docs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.android.ConvertSDK
import com.convert.sdk.android.EventCallback
import com.convert.sdk.android.getBoolean
import com.convert.sdk.android.getDouble
import com.convert.sdk.android.getInt
import com.convert.sdk.android.getString
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.FeatureStatus
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.LogLevel
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compile-check smoke test for README.md Quick Start and docs/user-guide.md
 * snippets (Story 6.2 AC-3).
 *
 * Every public consumer-facing code block from `README.md` and
 * `docs/user-guide.md` is reproduced verbatim (or minimally adapted
 * for the test harness — fake `Application`, placeholder `sdkKey`) as a
 * named helper below. The Kotlin compiler enforces that every API
 * referenced in the docs still exists and still has the documented
 * signature. When an API changes shape, this file fails to compile and
 * the docs must be updated before `./gradlew :packages:sdk:compileTestKotlin`
 * goes green again — bitrot is surfaced at the exact symbol that broke.
 *
 * Each helper is `private`; they are never called at runtime. The single
 * `@Test` below exists only so that the test runner compiles and loads
 * this class (otherwise JUnit could tree-shake it in some configurations).
 * The REAL check is compilation: if the snippets don't compile, the
 * module won't compile, and CI fails.
 *
 * ### Design choice — no runtime execution
 *
 * Running the snippets end-to-end would require a real HTTP server,
 * a bucketing config, and a WorkManager with a real scheduler. That
 * is already exercised by the Story 5.5 full-chain integration test.
 * This file's purpose is narrower: proof that the TYPES and SYMBOLS
 * the docs reference are still part of the shipped public API. The
 * compile step is the check.
 */
@RunWith(RobolectricTestRunner::class)
internal class ReadmeQuickstartSmokeTest {

    /**
     * Keeps JUnit from tree-shaking the class. Compilation is the real
     * assertion — if any snippet below references a symbol the shipped
     * SDK no longer exposes, `:packages:sdk:compileTestKotlin` fails
     * before this test ever runs.
     */
    @Test
    fun classCompilesAndLoads() {
        // Touching the Application context forces Robolectric to load —
        // the snippets themselves never execute.
        val context: Context = ApplicationProvider.getApplicationContext()
        assertNotNull(context)
    }

    // -------------------------------------------------------------------
    // README.md — "Quick Start"
    // -------------------------------------------------------------------

    /**
     * README Quick Start — step 2: initialise in `Application.onCreate()`.
     *
     * Dropped: `super.onCreate()` line. Test harness: we declare the
     * `Application` subclass to prove the builder composes cleanly; the
     * harness never actually calls `onCreate()`.
     */
    private class QuickStartApp : Application() {
        lateinit var convertSdk: ConvertSDK

        override fun onCreate() {
            super.onCreate()

            convertSdk = ConvertSDK.builder(this)
                .sdkKey("YOUR_SDK_KEY")
                .logLevel(LogLevel.INFO)
                .build()
        }
    }

    /**
     * README Quick Start — step 3: run an experience.
     */
    private fun readmeRunExperienceSnippet(sdk: ConvertSDK) {
        sdk.onReady {
            val ctx = sdk.createContext()
            val variation = ctx.runExperience("homepage-redesign")
            when (variation?.key) {
                "control" -> { /* renderControl() */ }
                "treatment" -> { /* renderTreatment() */ }
                null -> { /* renderControl() — SDK not ready or not bucketed */ }
            }
        }
    }

    /**
     * README Quick Start — step 4: track a conversion.
     */
    private fun readmeTrackConversionSnippet(sdk: ConvertSDK) {
        val ctx = sdk.createContext()

        ctx.trackConversion("signup-completed")

        ctx.trackConversion(
            goalKey = "purchase-completed",
            goalData = listOf(
                GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(49.99)),
                GoalData(key = GoalDataKey.TRANSACTION_ID, value = JsonPrimitive("tx-42")),
            ),
        )
    }

    // -------------------------------------------------------------------
    // docs/user-guide.md — representative snippets
    // -------------------------------------------------------------------

    /** User guide — direct-data (pre-fetched config) mode. */
    private fun userGuideDirectDataMode(appContext: Context) {
        val config = loadBakedConfigStub()
        val sdk = ConvertSDK.builder(appContext)
            .data(config)
            .build()
        // Touch sdk to avoid UNUSED_VARIABLE warning through the test class.
        sdk.onReady { /* no-op */ }
    }

    /** User guide — subscribing to the bucketing event. */
    private fun userGuideOnBucketing(sdk: ConvertSDK) {
        val token = sdk.on(
            SystemEvents.BUCKETING,
            EventCallback { data ->
                // data = {"experienceKey": ..., "variationKey": ..., "visitorId": ...}
                val experienceKey = data["experienceKey"]
                val variationKey = data["variationKey"]
                val visitorId = data["visitorId"]
            },
        )
        sdk.off(SystemEvents.BUCKETING, token)
    }

    /** User guide — three createContext overloads. */
    private fun userGuideCreateContexts(sdk: ConvertSDK) {
        val ctxAuto = sdk.createContext()
        val ctxExplicit = sdk.createContext("visitor-42")
        val ctxExplicitWithAttrs = sdk.createContext(
            visitorId = "visitor-42",
            attributes = mapOf(
                "plan" to "premium",
                "accountAgeDays" to 120,
            ),
        )
    }

    /** User guide — attribute replace semantics. */
    private fun userGuideAttributes(sdk: ConvertSDK) {
        val ctx = sdk.createContext()
        ctx.setAttributes(mapOf("plan" to "premium"))
        ctx.setAttributes(mapOf("tier" to "gold"))
    }

    /** User guide — run all experiences. */
    private fun userGuideRunAllExperiences(sdk: ConvertSDK) {
        val ctx = sdk.createContext()
        val variations = ctx.runExperiences()
        variations.forEach { variation ->
            // applyVariation(variation)
            val key = variation.key
        }
    }

    /** User guide — per-call tracking suppression. */
    private fun userGuidePerCallTrackingOff(sdk: ConvertSDK) {
        val ctx = sdk.createContext()
        val variation = ctx.runExperience("homepage-redesign", enableTracking = false)
        val all = ctx.runExperiences(enableTracking = false)
    }

    /** User guide — feature flag with typed variables. */
    private fun userGuideFeatureFlagTypedVariables(sdk: ConvertSDK) {
        val ctx = sdk.createContext()
        val feature = ctx.runFeature("checkout-v2")
        if (feature?.enabled == true) {
            // enableCheckoutV2()
        }
        // Also exercise the explicit status check to keep the bitrot-check
        // honest — the user-guide mentions both forms.
        if (feature?.status == FeatureStatus.ENABLED) {
            // enableCheckoutV2()
        }
        val color = feature?.getString("ctaColor") ?: "#0066ff"
        val limit = feature?.getInt("maxItems") ?: 20
        val price = feature?.getDouble("price") ?: 9.99
        val experimental = feature?.getBoolean("experimental") == true
    }

    /** User guide — run all features. */
    private fun userGuideRunAllFeatures(sdk: ConvertSDK) {
        val ctx = sdk.createContext()
        val all = ctx.runFeatures()
    }

    /** User guide — conversion with all GoalDataKey values. */
    private fun userGuideFullGoalData(sdk: ConvertSDK) {
        val ctx = sdk.createContext()
        ctx.trackConversion(
            goalKey = "purchase-completed",
            goalData = listOf(
                GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(49.99)),
                GoalData(key = GoalDataKey.PRODUCTS_COUNT, value = JsonPrimitive(3)),
                GoalData(key = GoalDataKey.TRANSACTION_ID, value = JsonPrimitive("tx-42")),
                GoalData(key = GoalDataKey.CUSTOM_DIMENSION_1, value = JsonPrimitive("vip")),
                GoalData(key = GoalDataKey.CUSTOM_DIMENSION_2, value = JsonPrimitive("vip")),
                GoalData(key = GoalDataKey.CUSTOM_DIMENSION_3, value = JsonPrimitive("vip")),
                GoalData(key = GoalDataKey.CUSTOM_DIMENSION_4, value = JsonPrimitive("vip")),
                GoalData(key = GoalDataKey.CUSTOM_DIMENSION_5, value = JsonPrimitive("vip")),
            ),
        )
    }

    /** User guide — forceMultipleTransactions bypass. */
    private fun userGuideForceMultipleTransactions(sdk: ConvertSDK) {
        val ctx = sdk.createContext()
        ctx.trackConversion(
            goalKey = "purchase-completed",
            goalData = listOf(GoalData(GoalDataKey.AMOUNT, JsonPrimitive(49.99))),
            conversionSetting = mapOf("forceMultipleTransactions" to true),
        )
    }

    /** User guide — default + custom segments. */
    private fun userGuideSegments(sdk: ConvertSDK) {
        val ctx = sdk.createContext()
        ctx.setDefaultSegments(
            mapOf(
                "plan" to "premium",
                "country" to "US",
            ),
        )
        ctx.setCustomSegments(
            mapOf(
                "lifetimeValue" to 1250.75,
                "isBetaTester" to true,
                "interests" to listOf("running", "cycling"),
            ),
        )
    }

    /** User guide — tracking control (SDK-level). */
    private fun userGuideTrackingControl(sdk: ConvertSDK) {
        sdk.setTrackingEnabled(false)
        sdk.setTrackingEnabled(true)
        val current = sdk.isTrackingEnabled()
    }

    /** User guide — builder-time tracking default. */
    private fun userGuideBuilderTrackingDefault(appContext: Context) {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("YOUR_SDK_KEY")
            .trackingEnabled(false)
            .build()
        sdk.setTrackingEnabled(true)
    }

    /** User guide — logLevel builder method. */
    private fun userGuideLoggingBuilder(appContext: Context) {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("...")
            .logLevel(LogLevel.DEBUG)
            .build()
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Stub loader for the direct-data snippet — the user guide shows
     * `loadBakedConfig()` but leaves the implementation to the consumer.
     * The test harness returns an empty [com.convert.sdk.core.model.generated.ConfigResponseData]
     * so the compiler is satisfied; never called at runtime.
     */
    private fun loadBakedConfigStub(): com.convert.sdk.core.model.generated.ConfigResponseData =
        com.convert.sdk.core.model.generated.ConfigResponseData()
}
