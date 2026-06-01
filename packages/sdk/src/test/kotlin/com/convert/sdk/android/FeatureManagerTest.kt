/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.model.FeatureStatus
import com.convert.sdk.core.model.generated.ConfigResponseData
import kotlinx.serialization.json.Json
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

/**
 * Robolectric-backed tests for Story 4.1 — [FeatureManager.evaluate] and
 * [FeatureManager.evaluateAll]. Because the manager routes feature
 * resolution through [ConvertContext.runExperience] (sticky + rule gate +
 * bucketing + persistence + enqueue + fire), the tests use a real
 * Robolectric-backed Android context — the same pattern used by
 * [ConvertContextRunExperienceTest].
 *
 * ### Why decode configs from JSON strings
 *
 * `ConfigResponseData` contains `List<ExperienceChangeServing>` on each
 * variation. `ExperienceChangeServing` is an interface the OpenAPI
 * generator emits; the concrete `ExperienceChangeFullStackFeatureServing`
 * data class does NOT declare `: ExperienceChangeServing` (generator
 * quirk). That rules out constructing typed fixtures directly — the
 * Kotlin compiler rejects the cast. Instead, we build the config as a
 * JSON string and decode it through the sdk's shared JSON, which uses
 * [com.convert.sdk.core.internal.RawExperienceChangeServingSerializer] to
 * materialise the `changes` entries as raw-JSON-backed holders
 * implementing the interface. Bonus: this exercises the real decode
 * path the SDK uses in production.
 *
 * ### Fixture shapes
 *
 * `featureConfigJson()` — one experience (`welcome`) with two 50/50
 * variations; the control variation (`var-a`) carries a
 * `fullStackFeature` change referencing feature `100` with a typed
 * variable payload. `visitor_abc` hashes into `var-a`, so the feature
 * is ENABLED. `visitor_xyz` hashes into `var-b` (no feature change) —
 * so the same feature is DISABLED for that visitor. Feature `200`
 * (`orphan-feature`) is declared but never surfaced by any variation,
 * so it always reports DISABLED.
 *
 * `twoExperienceFeatureConfigJson()` — both experiences expose feature
 * `100`. Used to assert declaration-order winner semantics.
 */
@RunWith(RobolectricTestRunner::class)
internal class FeatureManagerTest {

    private lateinit var appContext: Context

    /** Shared JSON with the sdk's full serializer module wired in. */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = sharedSerializersModule
    }

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    // --- fixtures --------------------------------------------------------

    private fun featureConfigJson(): String = """
    {
      "experiences": [
        {
          "id": "exp-1",
          "key": "welcome",
          "variations": [
            {
              "id": "var-a",
              "key": "control",
              "traffic_allocation": 50.0,
              "changes": [
                {
                  "id": 1,
                  "type": "fullStackFeature",
                  "data": {
                    "feature_id": 100,
                    "variables_data": {
                      "color": "blue",
                      "count": 3,
                      "flag": true,
                      "ratio": 0.5
                    }
                  }
                }
              ]
            },
            {
              "id": "var-b",
              "key": "treatment",
              "traffic_allocation": 50.0,
              "changes": []
            }
          ]
        }
      ],
      "features": [
        {
          "id": "100",
          "key": "dark-mode",
          "name": "Dark Mode",
          "variables": [
            {"key": "color", "type": "string"},
            {"key": "count", "type": "integer"},
            {"key": "flag", "type": "boolean"},
            {"key": "ratio", "type": "float"}
          ]
        },
        {
          "id": "200",
          "key": "orphan-feature",
          "name": "Orphan",
          "variables": []
        }
      ]
    }
    """.trimIndent()

    private fun twoExperienceFeatureConfigJson(): String = """
    {
      "experiences": [
        {
          "id": "exp-first",
          "key": "first-exp",
          "variations": [
            {
              "id": "v1-a",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {
                  "id": 1,
                  "type": "fullStackFeature",
                  "data": {
                    "feature_id": 100,
                    "variables_data": {"from": "first"}
                  }
                }
              ]
            }
          ]
        },
        {
          "id": "exp-second",
          "key": "second-exp",
          "variations": [
            {
              "id": "v2-a",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {
                  "id": 2,
                  "type": "fullStackFeature",
                  "data": {
                    "feature_id": 100,
                    "variables_data": {"from": "second"}
                  }
                }
              ]
            }
          ]
        }
      ],
      "features": [
        {
          "id": "100",
          "key": "dark-mode",
          "name": "Dark Mode",
          "variables": []
        }
      ]
    }
    """.trimIndent()

    private fun decodeConfig(payload: String): ConfigResponseData =
        json.decodeFromString(payload)

    private fun buildSdk(configJson: String): ConvertSDK {
        val config = decodeConfig(configJson)
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-7: unknown feature key returns null --------------------------

    @Test
    fun `evaluate returns null for unknown feature key`() {
        val sdk = buildSdk(featureConfigJson())
        val ctx = sdk.createContext("visitor_abc")

        val result = sdk.featureManager.evaluate(ctx, "does-not-exist")

        assertNull(result)
    }

    // --- AC-1 / AC-4: bucketed variation with feature change -> ENABLED --

    @Test
    fun `evaluate returns ENABLED Feature with variables when visitor bucketed into variation exposing feature`() {
        val sdk = buildSdk(featureConfigJson())
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
        val vars = result?.variables
        assertNotNull(vars)
        assertEquals("blue", (vars?.get("color") as? JsonPrimitive)?.contentOrNull)
        assertEquals(3, (vars?.get("count") as? JsonPrimitive)?.intOrNull)
        assertEquals(true, (vars?.get("flag") as? JsonPrimitive)?.booleanOrNull)
        assertEquals(0.5, (vars?.get("ratio") as? JsonPrimitive)?.doubleOrNull ?: 0.0, 0.0001)
    }

    // --- AC-8: declared but not surfaced -> DISABLED ---------------------

    @Test
    fun `evaluate returns DISABLED Feature when declared but no variation exposes it`() {
        val sdk = buildSdk(featureConfigJson())
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
        // visitor_xyz hashes into var-b which has no feature change.
        val sdk = buildSdk(featureConfigJson())
        val ctx = sdk.createContext("visitor_xyz")

        val bucketed = ctx.runExperience("welcome")
        // Sanity: visitor_xyz goes to var-b.
        assertEquals("var-b", bucketed?.id)

        val result = sdk.featureManager.evaluate(ctx, "dark-mode")

        assertNotNull(result)
        assertEquals(FeatureStatus.DISABLED, result?.status)
        assertNull(result?.variables)
    }

    // --- AC-5: sticky bucketing respected --------------------------------

    @Test
    fun `evaluate respects sticky bucketing on second call`() {
        val sdk = buildSdk(featureConfigJson())
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
        val sdk = buildSdk(featureConfigJson())
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
        val sdk = buildSdk(twoExperienceFeatureConfigJson())
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
        val sdk = buildSdk(featureConfigJson())
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
        val sdk = buildSdk(featureConfigJson())
        val received = mutableListOf<Map<String, Any?>>()
        val callback = ConvertContextRunExperienceTest.RecordingEventCallback(received)
        sdk.on(SystemEvents.BUCKETING, callback)
        val ctx = sdk.createContext("visitor_abc")

        sdk.featureManager.evaluate(ctx, "dark-mode")

        awaitCondition { received.isNotEmpty() }
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
