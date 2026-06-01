/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.model.FeatureStatus
import com.convert.sdk.core.model.generated.ConfigResponseData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric-backed tests for Story 4.1 AC-2 / AC-3 — the
 * [ConvertContext.runFeature] / [ConvertContext.runFeatures] thin
 * wrappers over [FeatureManager]. Config fixtures are decoded from JSON
 * strings so the real deserialisation path (including the raw-change
 * polymorphic serialiser) is exercised.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextRunFeatureTest {

    private lateinit var appContext: Context

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

    private fun basicFeatureConfigJson(): String = """
    {
      "experiences": [
        {
          "id": "exp-1",
          "key": "welcome",
          "variations": [
            {
              "id": "var-a",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {
                  "id": 1,
                  "type": "fullStackFeature",
                  "data": {
                    "feature_id": 100,
                    "variables_data": {"color": "blue"}
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
          "variables": [{"key": "color", "type": "string"}]
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

    private fun buildSdk(configJson: String): ConvertSDK {
        val config: ConfigResponseData = json.decodeFromString(configJson)
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-2: runFeature delegates to FeatureManager --------------------

    @Test
    fun `runFeature returns null for unknown feature`() {
        val sdk = buildSdk(basicFeatureConfigJson())
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runFeature("does-not-exist")

        assertNull(result)
    }

    @Test
    fun `runFeature returns ENABLED Feature when visitor bucketed into variation exposing feature`() {
        val sdk = buildSdk(basicFeatureConfigJson())
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
        val sdk = buildSdk(basicFeatureConfigJson())
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
        val sdk = buildSdk(basicFeatureConfigJson())
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

    // --- AC-6: no double-enqueue for runFeature following runExperience --

    @Test
    fun `runFeature after runExperience does not re-enqueue bucketing event (sticky)`() {
        val sdk = buildSdk(basicFeatureConfigJson())
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
