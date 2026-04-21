/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.model.FeatureStatus
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigFeature
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceChangeFullStackFeatureBaseAllOfData
import com.convert.sdk.core.model.generated.ExperienceChangeFullStackFeatureServing
import com.convert.sdk.core.model.generated.ExperienceChangeServing
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.FeatureVariableItemData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * Robolectric-backed tests for Story 4.1 AC-2 / AC-3 — the
 * [ConvertContext.runFeature] / [ConvertContext.runFeatures] thin
 * wrappers over [FeatureManager]. `FeatureManagerTest` covers the
 * resolution algorithm directly; these tests verify the public context
 * surface + wiring correctness (runFeature returns the same thing
 * FeatureManager.evaluate does, runFeatures iterates all features).
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextRunFeatureTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun basicFeatureConfig(): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-1",
                key = "welcome",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(100.0),
                        changes = listOf(
                            ExperienceChangeFullStackFeatureServing(
                                id = 1,
                                type = "fullStackFeature",
                                data = ExperienceChangeFullStackFeatureBaseAllOfData(
                                    featureId = 100,
                                    variablesData = JsonObject(
                                        mapOf("color" to JsonPrimitive("blue")),
                                    ),
                                ),
                            ) as ExperienceChangeServing,
                        ),
                    ),
                ),
            ),
        ),
        features = listOf(
            ConfigFeature(
                id = "100",
                key = "dark-mode",
                name = "Dark Mode",
                variables = listOf(FeatureVariableItemData(key = "color", type = "string")),
            ),
            ConfigFeature(
                id = "200",
                key = "orphan-feature",
                name = "Orphan",
                variables = emptyList(),
            ),
        ),
    )

    private fun buildSdk(config: ConfigResponseData): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-2: runFeature delegates to FeatureManager --------------------

    @Test
    fun `runFeature returns null for unknown feature`() {
        val sdk = buildSdk(basicFeatureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runFeature("does-not-exist")

        assertNull(result)
    }

    @Test
    fun `runFeature returns ENABLED Feature when visitor bucketed into variation exposing feature`() {
        val sdk = buildSdk(basicFeatureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runFeature("dark-mode")

        assertNotNull(result)
        assertEquals(FeatureStatus.ENABLED, result?.status)
        assertEquals("dark-mode", result?.key)
        // Extension function also works (Story 4.1 AC-4).
        assertEquals("blue", result?.getString("color"))
    }

    @Test
    fun `runFeature returns DISABLED Feature when declared but orphan`() {
        val sdk = buildSdk(basicFeatureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runFeature("orphan-feature")

        assertNotNull(result)
        assertEquals(FeatureStatus.DISABLED, result?.status)
        assertFalse(result?.enabled ?: true)
    }

    @Test
    fun `runFeature returns null when sdk reference is absent`() {
        val ctx = ConvertContext(visitorId = "v")

        val result = ctx.runFeature("dark-mode")

        assertNull(result)
    }

    // --- AC-3: runFeatures iterates all declared features ----------------

    @Test
    fun `runFeatures returns entries for every declared feature`() {
        val sdk = buildSdk(basicFeatureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val results = ctx.runFeatures()

        assertEquals(2, results.size)
        val byKey = results.associateBy { it.key }
        assertEquals(FeatureStatus.ENABLED, byKey["dark-mode"]?.status)
        assertEquals(FeatureStatus.DISABLED, byKey["orphan-feature"]?.status)
    }

    @Test
    fun `runFeatures returns empty when sdk reference is absent`() {
        val ctx = ConvertContext(visitorId = "v")
        assertEquals(emptyList<Any>(), ctx.runFeatures())
    }

    // --- AC-6: only one bucketing event per experience even across runFeature + runExperience ---

    @Test
    fun `runFeature after runExperience does not re-enqueue bucketing event (sticky)`() {
        val sdk = buildSdk(basicFeatureConfig())
        val recordingApi = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        ctx.runExperience("welcome")
        ctx.runFeature("dark-mode")

        assertEquals(
            "Expected exactly one bucketing enqueue (runFeature hits sticky); got: " +
                "${recordingApi.enqueueBucketingCalls}",
            1,
            recordingApi.enqueueBucketingCalls.size,
        )
    }

    // --- helpers ---------------------------------------------------------

    private fun awaitCondition(timeoutMs: Long = 1000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
    }
}
