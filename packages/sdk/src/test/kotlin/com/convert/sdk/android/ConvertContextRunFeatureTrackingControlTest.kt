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
 * CAP-1 (SPEC-per-call-bucketing-attributes) behavioural RED tests for the
 * `enableTracking` parameter on [ConvertContext.runFeature] /
 * [ConvertContext.runFeatures]. Asserted at the [ConvertContext] boundary,
 * never against [FeatureManager] directly — that class already threads
 * `enableTracking`, which is exactly why the public-surface gap survived.
 *
 * Every negative fire assertion below carries a positive control in the
 * SAME test: [SystemEvents] dispatch is scope-scheduled, so an
 * unconditional "stayed empty" read is vacuous unless a fire that MUST
 * happen is first proven to land.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextRunFeatureTrackingControlTest {

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

    // --- fixtures ----------------------------------------------------------

    /**
     * Two experiences, each exposing only its own feature, both variations
     * at 100% traffic allocation — every visitor is bucketed regardless of
     * hash, so the fixture needs no visitor-specific hash bookkeeping.
     */
    private fun twoExperienceConfigJson(): String = """
    {
      "experiences": [
        {
          "id": "exp-1",
          "key": "welcome-a",
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
          "id": "exp-2",
          "key": "welcome-b",
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
        {"id": "100", "key": "feature-a", "name": "Feature A", "variables": []},
        {"id": "200", "key": "feature-b", "name": "Feature B", "variables": []}
      ]
    }
    """.trimIndent()

    /**
     * One experience carrying TWO features on its sole variation — the
     * sticky-revisit fixture: `evaluateAll`'s second declared feature
     * revisits this SAME experience via the sticky path.
     */
    private fun dualFeatureConfigJson(): String = """
    {
      "experiences": [
        {
          "id": "exp-dual",
          "key": "dual-flags",
          "variations": [
            {
              "id": "var-dual",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {"id": 1, "type": "fullStackFeature", "data": {"feature_id": 100, "variables_data": {}}},
                {"id": 2, "type": "fullStackFeature", "data": {"feature_id": 200, "variables_data": {}}}
              ]
            }
          ]
        }
      ],
      "features": [
        {"id": "100", "key": "feature-a", "name": "Feature A", "variables": []},
        {"id": "200", "key": "feature-b", "name": "Feature B", "variables": []}
      ]
    }
    """.trimIndent()

    // --- helpers -------------------------------------------------------

    private fun buildSdk(configJson: String): ConvertSDK {
        val config: ConfigResponseData = json.decodeFromString(configJson)
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    /** Synchronized per Finding #2 — [ConvertContextRunExperienceTest.RecordingEventCallback]'s
     * plain `MutableList` loses entries under concurrent `add` once a test asserts across
     * two-or-more fires. */
    private fun recordingSink(): MutableList<Map<String, Any?>> =
        Collections.synchronizedList(mutableListOf())

    private fun awaitCondition(timeoutMs: Long = 1000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    // --- fixture sanity: the exposes-only-its-own-feature invariant --------

    @Test
    fun `twoExperienceConfigJson exposes each feature to only its own experience`() {
        val config: ConfigResponseData = json.decodeFromString(twoExperienceConfigJson())
        val changesA = config.experiences?.first { it.key == "welcome-a" }
            ?.variations?.single()?.changes.orEmpty()
        val changesB = config.experiences?.first { it.key == "welcome-b" }
            ?.variations?.single()?.changes.orEmpty()

        assertEquals(1, changesA.size)
        assertEquals(1, changesB.size)
    }

    // --- CAP-1: runFeature(key, enableTracking = false) --------------------

    @Test
    fun `runFeature untracked suppresses enqueue and fire, still persists sticky, matches tracked result`() {
        // Positive control — a tracked call on its own sdk/visitor MUST enqueue and fire.
        val trackedSdk = buildSdk(twoExperienceConfigJson())
        val trackedApi = ConvertContextRunExperienceTest.RecordingApiManager()
        trackedSdk.attachTestApiManager(trackedApi)
        val trackedSink = recordingSink()
        trackedSdk.on(
            SystemEvents.BUCKETING,
            ConvertContextRunExperienceTest.RecordingEventCallback(trackedSink),
        )
        val trackedCtx = trackedSdk.createContext("visitor_feature_a_tracked")

        val trackedFeature = trackedCtx.runFeature("feature-a", enableTracking = true)
        awaitCondition { trackedSink.isNotEmpty() }
        assertEquals(1, trackedApi.enqueueBucketingCalls.size)
        assertEquals(1, trackedSink.size)

        // Untracked arm — a distinct sdk + visitor from the control above, so
        // neither the recorded counters nor the sticky state are shared.
        val untrackedSdk = buildSdk(twoExperienceConfigJson())
        val untrackedApi = ConvertContextRunExperienceTest.RecordingApiManager()
        untrackedSdk.attachTestApiManager(untrackedApi)
        val untrackedSink = recordingSink()
        untrackedSdk.on(
            SystemEvents.BUCKETING,
            ConvertContextRunExperienceTest.RecordingEventCallback(untrackedSink),
        )
        val untrackedCtx = untrackedSdk.createContext("visitor_feature_a_untracked")

        val untrackedFeature = untrackedCtx.runFeature("feature-a", enableTracking = false)
        Thread.sleep(200)

        assertTrue(
            "Expected no enqueueBucketingEvent calls, got ${untrackedApi.enqueueBucketingCalls}",
            untrackedApi.enqueueBucketingCalls.isEmpty(),
        )
        assertTrue(
            "Expected no SystemEvents.BUCKETING fires, got $untrackedSink",
            untrackedSink.isEmpty(),
        )
        assertEquals(
            "var-a1",
            untrackedSdk.dataManager.getStoreData("visitor_feature_a_untracked")
                .bucketing?.get("welcome-a"),
        )
        assertNotNull(untrackedFeature)
        assertEquals(FeatureStatus.ENABLED, untrackedFeature?.status)
        assertEquals(trackedFeature, untrackedFeature)
    }

    // --- CAP-1: runFeatures(enableTracking = false) -------------------------

    @Test
    fun `runFeatures untracked suppresses enqueue and fire for both, still persists sticky, matches tracked list`() {
        val trackedSdk = buildSdk(twoExperienceConfigJson())
        val trackedApi = ConvertContextRunExperienceTest.RecordingApiManager()
        trackedSdk.attachTestApiManager(trackedApi)
        val trackedSink = recordingSink()
        trackedSdk.on(
            SystemEvents.BUCKETING,
            ConvertContextRunExperienceTest.RecordingEventCallback(trackedSink),
        )
        val trackedCtx = trackedSdk.createContext("visitor_features_tracked")

        val trackedFeatures = trackedCtx.runFeatures(enableTracking = true)
        awaitCondition { trackedSink.size >= 2 }
        assertEquals(2, trackedApi.enqueueBucketingCalls.size)
        assertEquals(2, trackedSink.size)

        val untrackedSdk = buildSdk(twoExperienceConfigJson())
        val untrackedApi = ConvertContextRunExperienceTest.RecordingApiManager()
        untrackedSdk.attachTestApiManager(untrackedApi)
        val untrackedSink = recordingSink()
        untrackedSdk.on(
            SystemEvents.BUCKETING,
            ConvertContextRunExperienceTest.RecordingEventCallback(untrackedSink),
        )
        val untrackedCtx = untrackedSdk.createContext("visitor_features_untracked")

        val untrackedFeatures = untrackedCtx.runFeatures(enableTracking = false)
        Thread.sleep(200)

        assertTrue(
            "Expected no enqueueBucketingEvent calls, got ${untrackedApi.enqueueBucketingCalls}",
            untrackedApi.enqueueBucketingCalls.isEmpty(),
        )
        assertTrue(
            "Expected no SystemEvents.BUCKETING fires, got $untrackedSink",
            untrackedSink.isEmpty(),
        )
        val storedBucketing = untrackedSdk.dataManager
            .getStoreData("visitor_features_untracked").bucketing
        assertEquals("var-a1", storedBucketing?.get("welcome-a"))
        assertEquals("var-b1", storedBucketing?.get("welcome-b"))
        assertEquals(2, untrackedFeatures.size)
        assertEquals(trackedFeatures, untrackedFeatures)
    }

    // --- CAP-1: the default arities stay byte-identical to today -----------

    @Test
    fun `runFeature and runFeatures without the parameter still enqueue and fire exactly as today`() {
        val sdk = buildSdk(twoExperienceConfigJson())
        val api = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(api)
        val sink = recordingSink()
        sdk.on(SystemEvents.BUCKETING, ConvertContextRunExperienceTest.RecordingEventCallback(sink))
        val ctx = sdk.createContext("visitor_default_arity")

        val singleFeature = ctx.runFeature("feature-a")
        awaitCondition { sink.isNotEmpty() }
        assertEquals(1, api.enqueueBucketingCalls.size)
        assertEquals(1, sink.size)
        assertNotNull(singleFeature)

        val allSdk = buildSdk(twoExperienceConfigJson())
        val allApi = ConvertContextRunExperienceTest.RecordingApiManager()
        allSdk.attachTestApiManager(allApi)
        val allSink = recordingSink()
        allSdk.on(SystemEvents.BUCKETING, ConvertContextRunExperienceTest.RecordingEventCallback(allSink))
        val allCtx = allSdk.createContext("visitor_default_arity_all")

        val allFeatures = allCtx.runFeatures()
        awaitCondition { allSink.size >= 2 }
        assertEquals(2, allApi.enqueueBucketingCalls.size)
        assertEquals(2, allSink.size)
        assertEquals(2, allFeatures.size)
    }

    // --- CAP-1: sticky-revisit within one experience carrying two features -

    @Test
    fun `runFeatures on one experience carrying two features enqueues once but fires twice`() {
        val sdk = buildSdk(dualFeatureConfigJson())
        val api = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(api)
        val sink = recordingSink()
        sdk.on(SystemEvents.BUCKETING, ConvertContextRunExperienceTest.RecordingEventCallback(sink))
        val ctx = sdk.createContext("visitor_dual_feature")

        val results = ctx.runFeatures()
        awaitCondition { sink.size >= 2 }

        assertEquals(2, results.size)
        assertEquals(
            "Expected exactly one enqueue — the second declared feature revisits the " +
                "SAME experience via the sticky path",
            1,
            api.enqueueBucketingCalls.size,
        )
        assertEquals(2, sink.size)
    }
}
