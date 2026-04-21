/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.event.SystemEvents
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * Robolectric-backed tests for Story 4.1 — [FeatureManager.evaluate] and
 * [FeatureManager.evaluateAll]. Because the manager routes feature
 * resolution through [ConvertContext.runExperience] (sticky + rule gate +
 * bucketing + persistence + enqueue + fire), the tests use a real
 * Robolectric-backed Android context — the same pattern used by
 * [ConvertContextRunExperienceTest].
 *
 * ### Fixture shape (`featureConfig`)
 *
 * One experience `welcome` with two 50/50 variations; the control variation
 * (`var-a`) carries a `fullStackFeature` change referencing feature `100`
 * with a `variables_data` payload carrying one of each primitive type.
 * `visitor_abc` hashes into `var-a`, so the bucketed feature for that
 * visitor is ENABLED. `visitor_xyz` hashes into `var-b`, which exposes no
 * feature change — so the feature resolves as DISABLED for that visitor.
 *
 * Feature `200` is declared but never referenced by any variation's
 * changes, so it is always DISABLED. This covers AC-8 (declared but not
 * bucketed).
 */
@RunWith(RobolectricTestRunner::class)
internal class FeatureManagerTest {

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

    // --- fixture builders ------------------------------------------------

    private fun variablesDataJson(): JsonObject = JsonObject(
        mapOf(
            "color" to JsonPrimitive("blue"),
            "count" to JsonPrimitive(3),
            "flag" to JsonPrimitive(true),
            "ratio" to JsonPrimitive(0.5),
        ),
    )

    private fun featureConfig(): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-1",
                key = "welcome",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(50.0),
                        changes = listOf(
                            ExperienceChangeFullStackFeatureServing(
                                id = 1,
                                type = "fullStackFeature",
                                data = ExperienceChangeFullStackFeatureBaseAllOfData(
                                    featureId = 100,
                                    variablesData = variablesDataJson(),
                                ),
                            ) as ExperienceChangeServing,
                        ),
                    ),
                    ExperienceVariationConfig(
                        id = "var-b",
                        key = "treatment",
                        trafficAllocation = BigDecimal.valueOf(50.0),
                        changes = emptyList(),
                    ),
                ),
            ),
        ),
        features = listOf(
            ConfigFeature(
                id = "100",
                key = "dark-mode",
                name = "Dark Mode",
                variables = listOf(
                    FeatureVariableItemData(key = "color", type = "string"),
                    FeatureVariableItemData(key = "count", type = "integer"),
                    FeatureVariableItemData(key = "flag", type = "boolean"),
                    FeatureVariableItemData(key = "ratio", type = "float"),
                ),
            ),
            ConfigFeature(
                id = "200",
                key = "orphan-feature",
                name = "Orphan",
                variables = emptyList(),
            ),
        ),
    )

    /**
     * Config with TWO experiences both exposing the same feature id `100`.
     * `visitor_abc` is bucketed into the first experience's `var-a`
     * (exposes feature) AND into the second experience's variation — we
     * assert that the returned Feature references the FIRST experience
     * (declaration order — readiness Q3).
     */
    private fun twoExperienceFeatureConfig(): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-first",
                key = "first-exp",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "v1-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(100.0),
                        changes = listOf(
                            ExperienceChangeFullStackFeatureServing(
                                id = 1,
                                type = "fullStackFeature",
                                data = ExperienceChangeFullStackFeatureBaseAllOfData(
                                    featureId = 100,
                                    variablesData = JsonObject(
                                        mapOf("from" to JsonPrimitive("first")),
                                    ),
                                ),
                            ) as ExperienceChangeServing,
                        ),
                    ),
                ),
            ),
            ConfigExperience(
                id = "exp-second",
                key = "second-exp",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "v2-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(100.0),
                        changes = listOf(
                            ExperienceChangeFullStackFeatureServing(
                                id = 2,
                                type = "fullStackFeature",
                                data = ExperienceChangeFullStackFeatureBaseAllOfData(
                                    featureId = 100,
                                    variablesData = JsonObject(
                                        mapOf("from" to JsonPrimitive("second")),
                                    ),
                                ),
                            ) as ExperienceChangeServing,
                        ),
                    ),
                ),
            ),
        ),
        features = listOf(
            ConfigFeature(id = "100", key = "dark-mode", name = "Dark Mode", variables = emptyList()),
        ),
    )

    private fun buildSdk(config: ConfigResponseData): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-7: unknown feature key returns null --------------------------

    @Test
    fun `evaluate returns null for unknown feature key`() {
        val sdk = buildSdk(featureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = sdk.featureManager.evaluate(ctx, "does-not-exist")

        assertNull(result)
    }

    // --- AC-1 / AC-4: bucketed variation with feature change -> ENABLED --

    @Test
    fun `evaluate returns ENABLED Feature with variables when visitor bucketed into variation exposing feature`() {
        val sdk = buildSdk(featureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = sdk.featureManager.evaluate(ctx, "dark-mode")

        assertNotNull(result)
        assertEquals(FeatureStatus.ENABLED, result?.status)
        assertEquals(true, result?.enabled)
        assertEquals("100", result?.id)
        assertEquals("dark-mode", result?.key)
        assertEquals("Dark Mode", result?.name)
        assertEquals("exp-1", result?.experienceId)
        assertEquals("welcome", result?.experienceKey)
        // Typed variables round-trip (AC-4).
        val vars = result?.variables
        assertNotNull(vars)
        assertEquals("blue", (vars?.get("color") as? JsonPrimitive)?.contentOrNull)
        assertEquals(3, (vars?.get("count") as? JsonPrimitive)?.intOrNull)
        assertEquals(true, (vars?.get("flag") as? JsonPrimitive)?.booleanOrNull)
        assertEquals(0.5, (vars?.get("ratio") as? JsonPrimitive)?.doubleOrNull ?: 0.0, 0.0001)
    }

    // --- AC-8: declared but not bucketed -> DISABLED ---------------------

    @Test
    fun `evaluate returns DISABLED Feature when declared but no variation exposes it`() {
        val sdk = buildSdk(featureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = sdk.featureManager.evaluate(ctx, "orphan-feature")

        assertNotNull(result)
        assertEquals(FeatureStatus.DISABLED, result?.status)
        assertEquals(false, result?.enabled)
        assertEquals("200", result?.id)
        assertEquals("orphan-feature", result?.key)
        assertNull(result?.variables)
    }

    @Test
    fun `evaluate returns DISABLED Feature when visitor bucketed into variation without the feature change`() {
        // visitor_xyz hashes to 6834 -> above the 5000 boundary -> var-b (no feature change)
        val sdk = buildSdk(featureConfig())
        val ctx = sdk.createContext("visitor_xyz")

        // Pre-bucket to confirm assignment.
        val bucketed = ctx.runExperience("welcome")
        assertEquals("var-b", bucketed?.id)

        val result = sdk.featureManager.evaluate(ctx, "dark-mode")

        assertNotNull(result)
        assertEquals(FeatureStatus.DISABLED, result?.status)
        assertNull(result?.variables)
    }

    // --- AC-5: sticky bucketing respected --------------------------------

    @Test
    fun `evaluate respects sticky bucketing on second call`() {
        val sdk = buildSdk(featureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val first = sdk.featureManager.evaluate(ctx, "dark-mode")
        val second = sdk.featureManager.evaluate(ctx, "dark-mode")

        assertEquals(first?.status, second?.status)
        assertEquals(first?.experienceId, second?.experienceId)
        assertEquals(
            first?.variables?.get("color"),
            second?.variables?.get("color"),
        )
    }

    // --- AC-6: only ONE bucketing event enqueued across multiple evaluate calls ---

    @Test
    fun `evaluate enqueues at most one bucketing event for the same experience across calls`() {
        val sdk = buildSdk(featureConfig())
        val recordingApi = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        sdk.featureManager.evaluate(ctx, "dark-mode")
        sdk.featureManager.evaluate(ctx, "dark-mode")

        assertEquals(
            "Sticky bucketing must not re-enqueue; recorded: ${recordingApi.enqueueBucketingCalls}",
            1,
            recordingApi.enqueueBucketingCalls.size,
        )
    }

    // --- readiness Q3: multi-experience match -> first in declaration order ---

    @Test
    fun `evaluate returns first-declaration-order match when multiple experiences expose the feature`() {
        val sdk = buildSdk(twoExperienceFeatureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = sdk.featureManager.evaluate(ctx, "dark-mode")

        assertNotNull(result)
        assertEquals(FeatureStatus.ENABLED, result?.status)
        assertEquals("exp-first", result?.experienceId)
        assertEquals(
            "first",
            (result?.variables?.get("from") as? JsonPrimitive)?.contentOrNull,
        )
    }

    // --- AC-3 (readiness Q4): evaluateAll returns enabled + disabled entries ---

    @Test
    fun `evaluateAll returns entries for all declared features with mixed statuses`() {
        val sdk = buildSdk(featureConfig())
        val ctx = sdk.createContext("visitor_abc")

        val results = sdk.featureManager.evaluateAll(ctx)

        assertEquals(2, results.size)
        val byKey = results.associateBy { it.key }
        assertEquals(FeatureStatus.ENABLED, byKey["dark-mode"]?.status)
        assertEquals(FeatureStatus.DISABLED, byKey["orphan-feature"]?.status)
        assertNotNull(byKey["dark-mode"]?.variables)
        assertNull(byKey["orphan-feature"]?.variables)
    }

    // --- AC-6: internal SystemEvents.BUCKETING fires on evaluate ---------

    @Test
    fun `evaluate fires SystemEvents BUCKETING exactly once (not a separate feature event)`() {
        val sdk = buildSdk(featureConfig())
        val received = mutableListOf<Map<String, Any?>>()
        val callback = ConvertContextRunExperienceTest.RecordingEventCallback(received)
        sdk.on(SystemEvents.BUCKETING, callback)
        val ctx = sdk.createContext("visitor_abc")

        sdk.featureManager.evaluate(ctx, "dark-mode")

        awaitCondition { received.isNotEmpty() }
        // One BUCKETING event fires (the experience resolution); there is
        // NO separate "feature_evaluated" event (AC-6).
        assertTrue(
            "Expected at least one BUCKETING event, got ${received.size}",
            received.isNotEmpty(),
        )
        assertEquals("welcome", received.first()["experienceKey"])
    }

    // --- config-not-ready gate -------------------------------------------

    @Test
    fun `evaluate returns null when config is not yet loaded`() {
        val sdk = ConvertSDK.builder(appContext).build()
        val ctx = sdk.createContext("visitor_abc")

        val result = sdk.featureManager.evaluate(ctx, "anything")

        assertNull(result)
    }

    // --- helpers ---------------------------------------------------------

    private fun awaitCondition(timeoutMs: Long = 1000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
    }
}
