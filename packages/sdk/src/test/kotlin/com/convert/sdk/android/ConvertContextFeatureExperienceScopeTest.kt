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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * CAP-2 (SPEC-per-call-bucketing-attributes) behavioural RED tests for the
 * `experienceKeys` parameter on [ConvertContext.runFeature] /
 * [ConvertContext.runFeatures]. Asserted at the [ConvertContext] boundary
 * per the spec's success criterion — a [FeatureManager]-level test would
 * already pass, because that class is where the filter is added.
 *
 * Kotlin's static typing makes the compile failure itself the RED: no
 * call below resolves until `experienceKeys` exists on both entry points.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextFeatureExperienceScopeTest {

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

    // --- fixture ------------------------------------------------------

    /**
     * Two experiences, each exposing only its own feature, both at 100%
     * traffic allocation. `id` deliberately differs from `key` on both
     * experiences so a test can pass an id where a key is expected and
     * observe it treated as unknown.
     */
    private fun twoExperienceConfigJson(): String = """
    {
      "experiences": [
        {
          "id": "$EXP_A_ID",
          "key": "$KEY_A",
          "variations": [
            {
              "id": "var-a1",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {"id": 1, "type": "fullStackFeature", "data": {"feature_id": 100, "variables_data": {}}}
              ]
            }
          ]
        },
        {
          "id": "$EXP_B_ID",
          "key": "$KEY_B",
          "variations": [
            {
              "id": "var-b1",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {"id": 2, "type": "fullStackFeature", "data": {"feature_id": 200, "variables_data": {}}}
              ]
            }
          ]
        }
      ],
      "features": [
        {"id": "100", "key": "$FEATURE_A", "name": "Feature A", "variables": []},
        {"id": "200", "key": "$FEATURE_B", "name": "Feature B", "variables": []}
      ]
    }
    """.trimIndent()

    // --- helpers --------------------------------------------------------

    private fun buildSdk(configJson: String): ConvertSDK {
        val config: ConfigResponseData = json.decodeFromString(configJson)
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    /** Synchronized per Finding #2 — see [ConvertContextRunExperienceTest.RecordingEventCallback]. */
    private fun recordingSink(): MutableList<Map<String, Any?>> =
        Collections.synchronizedList(mutableListOf())

    private fun awaitCondition(timeoutMs: Long = 1000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    /**
     * Runs `runFeatures(experienceKeys = ...)` on a fresh context for
     * [visitorId] and asserts both declared features' resolved status,
     * without shrinking the returned list — CAP-2's "narrowing does not
     * shrink the result list" constraint.
     */
    private fun assertFeatureStatuses(
        sdk: ConvertSDK,
        visitorId: String,
        experienceKeys: List<String>?,
        expectedAStatus: FeatureStatus,
        expectedBStatus: FeatureStatus,
    ) {
        val ctx = sdk.createContext(visitorId)
        val features = ctx.runFeatures(experienceKeys = experienceKeys)
        assertEquals(2, features.size)
        assertEquals(expectedAStatus, features.first { it.key == FEATURE_A }.status)
        assertEquals(expectedBStatus, features.first { it.key == FEATURE_B }.status)
    }

    // --- fixture sanity ---------------------------------------------------

    @Test
    fun `fixture exposes each feature to only its own experience`() {
        val config: ConfigResponseData = json.decodeFromString(twoExperienceConfigJson())
        val changesA = config.experiences?.first { it.key == KEY_A }
            ?.variations?.single()?.changes.orEmpty()
        val changesB = config.experiences?.first { it.key == KEY_B }
            ?.variations?.single()?.changes.orEmpty()

        assertEquals(1, changesA.size)
        assertEquals(1, changesB.size)
    }

    // --- baseline and narrowing --------------------------------------------

    @Test
    fun `runFeatures with no experienceKeys argument reports both features ENABLED`() {
        val sdk = buildSdk(twoExperienceConfigJson())
        val ctx = sdk.createContext("visitor_baseline")

        val features = ctx.runFeatures()

        assertEquals(2, features.size)
        assertEquals(FeatureStatus.ENABLED, features.first { it.key == FEATURE_A }.status)
        assertEquals(FeatureStatus.ENABLED, features.first { it.key == FEATURE_B }.status)
    }

    @Test
    fun `runFeatures narrowed to experience A reports B DISABLED not omitted`() {
        val sdk = buildSdk(twoExperienceConfigJson())

        assertFeatureStatuses(
            sdk = sdk,
            visitorId = "visitor_narrow_a",
            experienceKeys = listOf(KEY_A),
            expectedAStatus = FeatureStatus.ENABLED,
            expectedBStatus = FeatureStatus.DISABLED,
        )
    }

    @Test
    fun `runFeature narrowed away from its own experience returns DISABLED not null`() {
        val sdk = buildSdk(twoExperienceConfigJson())
        val ctx = sdk.createContext("visitor_single_b")

        val feature = ctx.runFeature(FEATURE_B, experienceKeys = listOf(KEY_A))

        assertNotNull(feature)
        assertEquals(FeatureStatus.DISABLED, feature?.status)
    }

    // --- scoping is real, not cosmetic --------------------------------------

    @Test
    fun `runFeatures narrowed to A writes no sticky and enqueues nothing for the excluded experience`() {
        val sdk = buildSdk(twoExperienceConfigJson())
        val api = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(api)
        val sink = recordingSink()
        sdk.on(SystemEvents.BUCKETING, ConvertContextRunExperienceTest.RecordingEventCallback(sink))
        val visitorId = "visitor_scoping"
        val ctx = sdk.createContext(visitorId)

        ctx.runFeatures(experienceKeys = listOf(KEY_A))

        // Positive control — A's fire proves the bus works before the
        // sleep barrier below is trusted to mean B never fired (Finding #1).
        awaitCondition { sink.isNotEmpty() }
        Thread.sleep(200)

        assertEquals(1, sink.size)
        assertEquals(1, api.enqueueBucketingCalls.size)
        assertEquals(EXP_A_ID, api.enqueueBucketingCalls.single().experienceId)
        val bucketing = sdk.dataManager.getStoreData(visitorId).bucketing.orEmpty()
        assertTrue(bucketing.containsKey(KEY_A))
        assertTrue(!bucketing.containsKey(KEY_B))
    }

    // --- the four edge inputs ------------------------------------------

    @Test
    fun `runFeatures with null experienceKeys resolves every experience`() {
        val sdk = buildSdk(twoExperienceConfigJson())

        assertFeatureStatuses(
            sdk = sdk,
            visitorId = "visitor_edge_null",
            experienceKeys = null,
            expectedAStatus = FeatureStatus.ENABLED,
            expectedBStatus = FeatureStatus.ENABLED,
        )
    }

    @Test
    fun `runFeatures with an empty experienceKeys list resolves every experience per D-5`() {
        val sdk = buildSdk(twoExperienceConfigJson())

        assertFeatureStatuses(
            sdk = sdk,
            visitorId = "visitor_edge_empty",
            experienceKeys = emptyList(),
            expectedAStatus = FeatureStatus.ENABLED,
            expectedBStatus = FeatureStatus.ENABLED,
        )
    }

    @Test
    fun `runFeatures with one unknown key among known keys still resolves the known experiences`() {
        val sdk = buildSdk(twoExperienceConfigJson())

        assertFeatureStatuses(
            sdk = sdk,
            visitorId = "visitor_edge_unknown_among_known",
            experienceKeys = listOf(KEY_A, KEY_B, "does-not-exist"),
            expectedAStatus = FeatureStatus.ENABLED,
            expectedBStatus = FeatureStatus.ENABLED,
        )
    }

    @Test
    fun `runFeatures with every key unknown reports every feature DISABLED with no writes or throw`() {
        val sdk = buildSdk(twoExperienceConfigJson())
        val api = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(api)
        val sink = recordingSink()
        sdk.on(SystemEvents.BUCKETING, ConvertContextRunExperienceTest.RecordingEventCallback(sink))

        // Positive control on a DISTINCT visitor, same sdk instance — proves
        // the bus genuinely fires before the all-unknown arm's silence is
        // trusted (Finding #1); distinct visitor per Finding #3.
        val controlCtx = sdk.createContext("visitor_edge_all_unknown_control")
        controlCtx.runFeatures()
        awaitCondition { sink.size >= 2 }
        assertEquals(2, api.enqueueBucketingCalls.size)

        val visitorId = "visitor_edge_all_unknown"
        val ctx = sdk.createContext(visitorId)
        val features = ctx.runFeatures(experienceKeys = listOf("unknown-1", "unknown-2"))

        Thread.sleep(200)
        assertEquals(2, features.size)
        assertEquals(FeatureStatus.DISABLED, features.first { it.key == FEATURE_A }.status)
        assertEquals(FeatureStatus.DISABLED, features.first { it.key == FEATURE_B }.status)
        assertEquals(2, api.enqueueBucketingCalls.size)
        assertEquals(2, sink.size)
        assertTrue(sdk.dataManager.getStoreData(visitorId).bucketing.orEmpty().isEmpty())
    }

    // --- matching is by key, not id ------------------------------------

    @Test
    fun `runFeature narrowed by an experience id instead of its key treats the id as unknown`() {
        val sdk = buildSdk(twoExperienceConfigJson())
        val ctx = sdk.createContext("visitor_id_not_key")

        val feature = ctx.runFeature(FEATURE_A, experienceKeys = listOf(EXP_A_ID))

        assertNotNull(feature)
        assertEquals(FeatureStatus.DISABLED, feature?.status)
    }

    private companion object {
        const val KEY_A: String = "welcome-a"
        const val KEY_B: String = "welcome-b"
        const val FEATURE_A: String = "feature-a"
        const val FEATURE_B: String = "feature-b"
        const val EXP_A_ID: String = "exp-a-id"
        const val EXP_B_ID: String = "exp-b-id"
    }
}
