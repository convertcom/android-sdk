/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smoke coverage for the public SDK surface.
 *
 * Exercises the [ConvertSDK.Builder] and [ConvertContext] skeletons from
 * Story 1.2 so Kover can attribute coverage for the `packages/sdk`
 * module ahead of the manager wiring that lands in Story 2.1+.
 *
 * Both constructors are `internal`, so this same-module test can skip
 * the `ConvertSDK.builder(context)` entry point entirely. That keeps
 * this test purely JVM-based — no MockK / ByteBuddy / Robolectric
 * machinery, no `android.content.Context` stubbing. Real tests that
 * exercise the `builder(context)` path arrive with the HTTP/adapter
 * wiring stories (which land Robolectric).
 */
internal class SmokeTest {

    private fun sdkWithDefaults(): ConvertSDK = ConvertSDK(config = ConvertConfig())

    @Test
    fun `public SDK classes are accessible from the test classpath`() {
        assertNotNull(ConvertSDK::class.java)
        assertNotNull(ConvertContext::class.java)
        assertNotNull(EventCallback::class.java)
    }

    @Test
    fun `SDK holds on to the configuration it was constructed with`() {
        val config = ConvertConfig(sdkKey = "TEST-KEY", environment = "staging")
        val sdk = ConvertSDK(config = config)
        assertSame(config, sdk.config)
        assertEquals("TEST-KEY", sdk.config.sdkKey)
        assertEquals("staging", sdk.config.environment)
    }

    @Test
    fun `SDK with default config exposes the configured defaults`() {
        val sdk = sdkWithDefaults()
        assertNull(sdk.config.sdkKey)
        assertNull(sdk.config.api)
        assertNull(sdk.config.events)
        assertNull(sdk.config.bucketing)
        assertNull(sdk.config.logger)
        assertNull(sdk.config.network)
        assertNotNull(sdk.config.environment)
    }

    @Test
    fun `createContext without args mints a fresh visitor id each call`() {
        val sdk = sdkWithDefaults()
        val first = sdk.createContext()
        val second = sdk.createContext()
        assertNotNull(first.visitorId)
        assertNotNull(second.visitorId)
        assertNotSame(first, second)
        assertTrue(first.visitorId != second.visitorId)
    }

    @Test
    fun `createContext with explicit visitor id echoes it back`() {
        val sdk = sdkWithDefaults()
        val context = sdk.createContext("visitor-42")
        assertEquals("visitor-42", context.visitorId)
    }

    @Test
    fun `createContext with attributes applies them to the new context`() {
        val sdk = sdkWithDefaults()
        val attrs = mapOf("plan" to "pro", "region" to "EU", "churned" to false)
        val context = sdk.createContext("visitor-42", attrs)
        assertEquals("visitor-42", context.visitorId)
        assertEquals(attrs, context.debugSnapshot()["attributes"])
    }

    @Test
    fun `createContext with null attributes leaves attribute map empty`() {
        val sdk = sdkWithDefaults()
        val context = sdk.createContext("visitor-42", null)
        assertEquals("visitor-42", context.visitorId)
        assertNull(context.debugSnapshot()["attributes"])
    }

    @Test
    fun `onReady and event subscription hooks are fluent`() {
        val sdk = sdkWithDefaults()
        val ready = Runnable { /* no-op */ }
        val callback = EventCallback { /* no-op */ }

        assertSame(sdk, sdk.onReady(ready))
        assertSame(sdk, sdk.on("experienceEvaluated", callback))
        assertSame(sdk, sdk.off("experienceEvaluated", callback))
        // Idempotent off — callback already removed, second off is a no-op.
        assertSame(sdk, sdk.off("experienceEvaluated", callback))
    }

    @Test
    fun `ConvertContext setters are fluent and expose values via debugSnapshot`() {
        val context = ConvertContext(visitorId = "visitor-42")

        val attrs = mapOf("plan" to "pro")
        val locationProps = mapOf("country" to "PT")
        val defaultSegments = mapOf("segment-a" to "value-a")
        val customSegments = mapOf<String, Any?>("custom-x" to 7)

        assertSame(context, context.setAttributes(attrs))
        assertSame(context, context.setLocationProperties(locationProps))
        assertSame(context, context.setDefaultSegments(defaultSegments))
        assertSame(context, context.setCustomSegments(customSegments))

        val snapshot = context.debugSnapshot()
        assertEquals(attrs, snapshot["attributes"])
        assertEquals(locationProps, snapshot["locationProperties"])
        assertEquals(defaultSegments, snapshot["defaultSegments"])
        assertEquals(customSegments, snapshot["customSegments"])
    }

    @Test
    fun `ConvertContext run methods return stub defaults and record their inputs`() {
        val context = ConvertContext(visitorId = "visitor-42")

        assertNull(context.runExperience("exp-a"))
        assertNull(context.runExperience("exp-b", enableTracking = false))
        assertEquals(emptyList<com.convert.sdk.core.model.Variation>(), context.runExperiences())
        assertEquals(
            emptyList<com.convert.sdk.core.model.Variation>(),
            context.runExperiences(enableTracking = false),
        )

        assertNull(context.runFeature("flag-a"))
        assertEquals(emptyList<com.convert.sdk.core.model.Feature>(), context.runFeatures())

        context.trackConversion("goal-a")
        context.trackConversion(
            "goal-b",
            listOf(GoalData(key = GoalDataKey.AMOUNT)),
        )

        val snapshot = context.debugSnapshot()
        assertEquals("exp-b", snapshot["lastExperienceKey"])
        assertEquals("flag-a", snapshot["lastFeatureKey"])
        assertEquals(false, snapshot["lastRunWithTracking"])
        assertEquals("goal-b", snapshot["lastConversionGoalKey"])
        assertNotNull(snapshot["lastConversionGoalData"])
    }

    // Note: the `ConvertSDK.builder(context)` entry point and every
    // setter on `ConvertSDK.Builder` will be exercised by the
    // Robolectric-backed tests that land with the HTTP/adapter stories
    // (Story 2.2+). Covering them here would require a full Android
    // Context — out of scope for this pure-JVM smoke test.
}
